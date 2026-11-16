package com.clipforge.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.clipforge.app.data.AppDb
import com.clipforge.app.data.ClipEntity
import com.clipforge.app.data.Paths
import com.clipforge.app.data.ProjectEntity
import com.clipforge.app.engine.AspectMode
import com.clipforge.app.engine.AudioAnalyzer
import com.clipforge.app.engine.ClipSettings
import com.clipforge.app.engine.ClipMedia
import com.clipforge.app.engine.EditorExport
import com.clipforge.app.engine.EditorModel
import com.clipforge.app.engine.Gallery
import com.clipforge.app.engine.Transcriber
import com.clipforge.app.engine.VoskModel
import com.clipforge.app.engine.HighlightDetector
import com.clipforge.app.engine.Meta
import com.clipforge.app.engine.Quality
import com.clipforge.app.engine.SceneAnalyzer
import com.clipforge.app.engine.Thumbs
import com.clipforge.app.engine.YouTubeFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class Pending(
    val kind: String,              // LOCAL | YOUTUBE
    val title: String,
    val durationMs: Long,
    val thumb: Bitmap?,
    val url: String?,
    val localPath: String?,        // null until a YouTube video has been downloaded
    val stagingDir: File?
)

sealed interface ImportUi {
    data object Idle : ImportUi
    data class Loading(val message: String, val progress: Float?) : ImportUi
    data class Ready(val pending: Pending) : ImportUi
    data class Error(val message: String) : ImportUi
}

data class AnalysisUi(
    val projectId: String,
    val stage: String,
    val progress: Float,
    val error: String? = null,
    val done: Boolean = false
)

data class ExportUi(
    val running: Boolean = false,
    val index: Int = 0,
    val total: Int = 0,
    val progress: Float = 0f,
    val saved: List<Pair<String, Uri>> = emptyList(),
    val error: String? = null,
    val finished: Boolean = false
)

@UnstableApi
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val dao = AppDb.get(app).dao()

    val projects: StateFlow<List<ProjectEntity>> =
        dao.projects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val clipCounts: StateFlow<Map<String, Int>> =
        dao.clipCounts().map { list -> list.associate { it.projectId to it.n } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** projectId -> number of clips that were never exported to the gallery. */
    val unexported: StateFlow<Map<String, Int>> =
        dao.unexportedCounts().map { list -> list.associate { it.projectId to it.n } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun project(id: String) = dao.project(id)
    fun clips(id: String) = dao.clips(id)

    val importUi = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val analysisUi: StateFlow<AnalysisUi?> get() = AnalysisManager.ui
    val exportUi = MutableStateFlow(ExportUi())
    /** One-off message shown on the Source screen (e.g. a failed download). */
    val notice = MutableStateFlow<String?>(null)

    // ---- Video editor ----
    var editor by mutableStateOf<EditorModel?>(null)

    /** Opens the editor on a clip that was already cut. */
    fun openEditorForClip(c: ClipEntity): Boolean {
        val f = File(c.filePath)
        val meta = Meta.read(f.path) ?: return false
        if (meta.durationMs <= 0) return false
        editor = EditorModel(f.path, c.title, meta.width, meta.height, meta.durationMs)
        return true
    }

    /** Copies a video from the phone into the app and opens the editor on it. */
    fun openEditorFromUri(uri: Uri, onReady: () -> Unit) {
        viewModelScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    val dir = File(ctx.filesDir, "editor").apply { mkdirs() }
                    dir.listFiles()?.forEach { if (it.path != editor?.sourcePath) it.delete() }
                    val f = File(dir, "src_${System.currentTimeMillis()}.mp4")
                    ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        ?: throw IllegalStateException("Could not open that video.")
                    f
                }
                val meta = Meta.read(dest.path) ?: throw IllegalStateException("That file doesn't look like a video.")
                editor = EditorModel(dest.path, "My edit", meta.width, meta.height, meta.durationMs)
                onReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notice.value = "Couldn't open the editor: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun generateCaptions(m: EditorModel) {
        if (m.capBusy != null) return
        viewModelScope.launch {
            m.capError = null
            m.capBusy = "Preparing the speech model…"
            try {
                val dir = VoskModel.ensure(ctx) { p -> m.capBusy = "Downloading speech model (first time only)… ${(p * 100).toInt()}%" }
                if (dir == null) {
                    m.capError = "Couldn't download the speech model. Check your internet and try again."
                } else {
                    m.capBusy = "Listening to the video…"
                    val t = Transcriber.transcribe(dir, m.sourcePath, m.durationMs) { p -> m.capBusy = "Listening to the video… ${(p * 100).toInt()}%" }
                    if (t.words.isEmpty()) m.capError = "No speech was found in this video."
                    else { m.words = t.words; m.captionsOn = true }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                m.capError = "Captions failed: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
            } finally {
                m.capBusy = null
            }
        }
    }

    fun exportEdit(m: EditorModel) {
        if (m.exportBusy) return
        m.exportJob = viewModelScope.launch {
            m.exportBusy = true; m.exportProgress = 0f; m.exportDone = false; m.exportError = null
            val noteTitle = "ClipForge is rendering your edit"
            AnalysisManager.exportNote.value = ExportNote("Rendering…", 0f, title = noteTitle)
            AnalysisService.start(ctx)
            val out = File(ctx.cacheDir, "edit_${System.currentTimeMillis()}.mp4")
            try {
                val composition = EditorExport.build(m)
                ClipMedia.exportComposition(ctx, composition, out, Quality.HIGH) { p ->
                    m.exportProgress = p
                    AnalysisManager.exportNote.value = ExportNote("Rendering… ${(p * 100).toInt()}%", p, title = noteTitle)
                }
                if (!out.exists() || out.length() == 0L) throw IllegalStateException("The video could not be created.")
                withContext(Dispatchers.IO) { Gallery.save(ctx, out, m.title) }
                m.exportDone = true
                AnalysisManager.exportNote.value = ExportNote(
                    "Saved", 1f, finished = true, title = noteTitle,
                    doneTitle = "Video saved", doneText = "Find it in your gallery under Movies/ClipForge."
                )
            } catch (e: CancellationException) {
                AnalysisManager.exportNote.value = null
                throw e
            } catch (e: Throwable) {
                m.exportError = "Export failed. ${e.message?.lineSequence()?.firstOrNull().orEmpty()}".trim()
                AnalysisManager.exportNote.value = ExportNote("Failed", 0f, error = "export failed", title = noteTitle)
            } finally {
                out.delete()
                m.exportBusy = false
            }
        }
    }

    fun cancelEditExport(m: EditorModel) { m.exportJob?.cancel() }

    // ---- "Download" tab: save a YouTube / Short / Instagram video straight to the gallery ----
    data class DownloadUi(
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float? = null,
        val title: String = "",
        val done: Boolean = false,
        val error: String? = null
    )
    val dlUi = MutableStateFlow(DownloadUi())
    private var dlJob: Job? = null

    fun saveVideo(rawUrl: String) {
        val url = rawUrl.trim()
        if (dlUi.value.running) return
        if (!YouTubeFetcher.isValid(url) && !YouTubeFetcher.isInstagram(url)) {
            dlUi.value = DownloadUi(error = "That link isn't supported. Paste a YouTube video, YouTube Short or Instagram reel/post link (starting with https://).")
            return
        }
        dlJob = viewModelScope.launch {
            val noteTitle = "ClipForge is downloading your video"
            dlUi.value = DownloadUi(running = true, stage = "Getting video info…")
            AnalysisManager.exportNote.value = ExportNote("Getting video info…", 0f, title = noteTitle, quiet = true)
            AnalysisService.start(ctx)
            val dir = File(ctx.cacheDir, "dl-${System.currentTimeMillis()}")
            try {
                val info = try {
                    YouTubeFetcher.info(ctx, url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                }
                val title = info?.title ?: "video"
                dlUi.value = DownloadUi(running = true, stage = "Downloading…", progress = 0f, title = title)
                val file = YouTubeFetcher.download(ctx, url, dir, highQuality = true) { p ->
                    dlUi.value = dlUi.value.copy(progress = p)
                    AnalysisManager.exportNote.value =
                        ExportNote("Downloading… ${(p * 100).toInt()}%", p, title = noteTitle, quiet = true)
                }
                dlUi.value = dlUi.value.copy(stage = "Saving to your gallery…", progress = null)
                Gallery.save(ctx, file, title, "Movies/ClipForge Downloads", "")
                dlUi.value = DownloadUi(done = true, title = title)
                AnalysisManager.exportNote.value = ExportNote(
                    "Saved", 1f, finished = true, title = noteTitle,
                    doneTitle = "Video saved", doneText = "Find it in your gallery under Movies/ClipForge Downloads."
                )
            } catch (e: CancellationException) {
                dlUi.value = DownloadUi()
                AnalysisManager.exportNote.value = null
                throw e
            } catch (e: Throwable) {
                val why = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }.orEmpty().take(160)
                val hint = if (YouTubeFetcher.isInstagram(url)) " Instagram often blocks downloads or needs a login; public reels work most of the time. Try again later." else ""
                dlUi.value = DownloadUi(error = "Download failed. $why$hint".trim())
                AnalysisManager.exportNote.value = ExportNote("Failed", 0f, error = "download failed", title = noteTitle, quiet = true)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    fun cancelSave() { dlJob?.cancel() }
    fun clearSave() { if (!dlUi.value.running) dlUi.value = DownloadUi() }

    private var importJob: Job? = null
    private var exportJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Only when nothing is running: an analysis that keeps going in the background must not be marked failed.
            if (!AnalysisManager.isRunning()) {
                dao.failInterrupted()
                Paths.staging(ctx).deleteRecursively()   // leftovers from an interrupted import
            }
        }
    }

    // ------------------------------------------------------------------ import

    fun importLocal(uri: Uri) {
        if (importUi.value is ImportUi.Loading) return
        importJob = viewModelScope.launch {
            importUi.value = ImportUi.Loading("Reading video…", 0f)
            val dir = File(Paths.staging(ctx), UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                val (name, size) = withContext(Dispatchers.IO) { nameAndSize(uri) }
                val ext = name.substringAfterLast('.', "mp4").lowercase()
                    .takeIf { it in setOf("mp4", "mov", "mkv", "webm", "m4v", "3gp") } ?: "mp4"
                val dest = File(dir, "source.$ext")
                withContext(Dispatchers.IO) {
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Can't open the selected file.")
                    input.use { i ->
                        dest.outputStream().use { o ->
                            val buf = ByteArray(256 * 1024)
                            var copied = 0L
                            while (true) {
                                val n = i.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                copied += n
                                if (size > 0) importUi.value = ImportUi.Loading("Reading video…", copied.toFloat() / size)
                            }
                        }
                    }
                }
                val meta = withContext(Dispatchers.IO) { Meta.read(dest.path) }
                    ?: throw IllegalStateException("This file can't be read as a video. Try MP4, MOV, MKV or WEBM.")
                val thumb = withContext(Dispatchers.IO) { Thumbs.bitmap(dest.path, meta.durationMs / 10) }
                importUi.value = ImportUi.Ready(
                    Pending("LOCAL", name.substringBeforeLast('.'), meta.durationMs, thumb, null, dest.path, dir)
                )
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                throw e
            } catch (e: Throwable) {
                dir.deleteRecursively()
                importUi.value = ImportUi.Error(e.message ?: "Could not import this video.")
            }
        }
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = "video"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return name to size
    }

    fun fetchYoutube(rawUrl: String) {
        val url = rawUrl.trim()
        if (importUi.value is ImportUi.Loading) return
        if (!YouTubeFetcher.isValid(url)) {
            importUi.value = ImportUi.Error("That doesn't look like a YouTube link. It should start with https://youtube.com/ or https://youtu.be/")
            return
        }
        importJob = viewModelScope.launch {
            importUi.value = ImportUi.Loading("Getting video info… (first time can take a minute)", null)
            try {
                val info = YouTubeFetcher.info(ctx, url)
                val thumb = YouTubeFetcher.thumbnail(info.thumbUrl)
                importUi.value = ImportUi.Ready(Pending("YOUTUBE", info.title, info.durationMs, thumb, url, null, null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                importUi.value = ImportUi.Error(
                    "This video can't be accessed (it may be private, restricted, removed, or you're offline). ${e.message?.lineSequence()?.firstOrNull().orEmpty()}".trim()
                )
            }
        }
    }

    fun downloadYoutube() {
        val p = (importUi.value as? ImportUi.Ready)?.pending ?: return
        val url = p.url ?: return
        importJob = viewModelScope.launch {
            val dir = File(Paths.staging(ctx), UUID.randomUUID().toString()).apply { mkdirs() }
            importUi.value = ImportUi.Loading("Downloading video…", 0f)
            val dlTitle = "ClipForge is downloading your video"
            AnalysisManager.exportNote.value = ExportNote("Downloading…", 0f, title = dlTitle, quiet = true)
            AnalysisService.start(ctx)
            try {
                val file = YouTubeFetcher.download(ctx, url, dir) {
                    importUi.value = ImportUi.Loading("Downloading video…", it)
                    AnalysisManager.exportNote.value = ExportNote("Downloading… ${(it * 100).toInt()}%", it, title = dlTitle, quiet = true)
                }
                val meta = withContext(Dispatchers.IO) { Meta.read(file.path) }
                    ?: throw IllegalStateException("The downloaded file can't be played.")
                importUi.value = ImportUi.Ready(
                    Pending("YOUTUBE", p.title, meta.durationMs, p.thumb, url, file.path, dir)
                )
                AnalysisManager.exportNote.value = ExportNote("Done", 1f, finished = true, quiet = true)
            } catch (e: CancellationException) {
                dir.deleteRecursively()
                AnalysisManager.exportNote.value = null
                throw e
            } catch (e: Throwable) {
                dir.deleteRecursively()
                AnalysisManager.exportNote.value = ExportNote("Failed", 0f, error = "download failed", quiet = true)
                importUi.value = ImportUi.Ready(p)
                notice.value = "YouTube download failed: ${e.message?.lineSequence()?.firstOrNull() ?: "check the link and your connection."}"
            }
        }
    }

    fun clearImport() {
        importJob?.cancel()
        val s = importUi.value
        if (s is ImportUi.Ready) s.pending.stagingDir?.deleteRecursively()
        importUi.value = ImportUi.Idle
        notice.value = null
    }

    // ---------------------------------------------------------------- analysis

    /** Starts analysis of the prepared video. Returns the new project id, or null if nothing could be started. */
    fun startAnalysis(settings: ClipSettings = ClipSettings()): String? {
        val p = (importUi.value as? ImportUi.Ready)?.pending ?: return null
        val local = p.localPath ?: return null
        val staging = p.stagingDir ?: return null
        val id = UUID.randomUUID().toString()
        val started = AnalysisManager.start(
            ctx, AnalysisRequest(id, p.title, p.kind, p.durationMs, File(local), staging, settings, retry = false)
        )
        if (!started) {
            notice.value = "Another video is still being analysed. Wait for it to finish or cancel it first."
            return null
        }
        importUi.value = ImportUi.Idle
        return id
    }

    /** Analyses an interrupted/failed project again from its saved source video. Returns the project id or null. */
    fun retryProject(p: ProjectEntity): String? {
        val src = File(p.sourcePath)
        if (!src.exists()) {
            notice.value = "The saved video for this project is missing, so it can't be analysed again."
            return null
        }
        val started = AnalysisManager.start(
            ctx, AnalysisRequest(p.id, p.title, p.sourceKind, p.durationMs, src, null, ClipSettings(), retry = true)
        )
        if (!started) {
            notice.value = "Another video is still being analysed. Wait for it to finish first."
            return null
        }
        return p.id
    }

    fun cancelAnalysis() {
        AnalysisManager.cancel()
    }

    fun clearAnalysis() {
        AnalysisManager.clear()
    }

    /** Removes a project from the Continue tab without exporting it (the clips stay in My Projects). */
    fun dismissProject(id: String) {
        viewModelScope.launch { dao.markProjectExported(id) }
    }

    // ------------------------------------------------------------------ export

    fun export(clips: List<ClipEntity>, aspect: AspectMode, quality: Quality) {
        if (exportUi.value.running || clips.isEmpty()) return
        exportJob = viewModelScope.launch {
            exportUi.value = ExportUi(running = true, total = clips.size)
            AnalysisManager.exportNote.value = ExportNote("Preparing…", 0f)
            AnalysisService.start(ctx)
            val saved = ArrayList<Pair<String, Uri>>()
            var tmp: File? = null
            try {
                clips.forEachIndexed { i, c ->
                    val file = File(c.filePath)
                    if (!file.exists()) throw IllegalStateException("The file for \"${c.title}\" is missing from storage.")
                    val toSave: File
                    if (aspect.ratio == null) {
                        toSave = file    // original aspect: copy the clip as-is, no quality loss
                    } else {
                        val out = File(ctx.cacheDir, "export_${c.id}.mp4")
                        tmp = out
                        ClipMedia.render(ctx, file, out, aspect, quality) { p ->
                            exportUi.value = exportUi.value.copy(index = i, progress = p)
                            AnalysisManager.exportNote.value =
                                ExportNote("Exporting clip ${i + 1} of ${clips.size}…", (i + p) / clips.size)
                        }
                        toSave = out
                    }
                    exportUi.value = exportUi.value.copy(index = i, progress = 1f)
                    val uri = withContext(Dispatchers.IO) { Gallery.save(ctx, toSave, c.title) }
                    dao.markExported(c.id)
                    tmp?.delete()
                    tmp = null
                    saved.add(c.title to uri)
                }
                exportUi.value = ExportUi(total = clips.size, saved = saved, finished = true)
                AnalysisManager.exportNote.value = ExportNote("Done", 1f, finished = true)
            } catch (e: CancellationException) {
                tmp?.delete()
                exportUi.value = ExportUi()
                AnalysisManager.exportNote.value = null
                throw e
            } catch (e: Throwable) {
                tmp?.delete()
                exportUi.value = ExportUi(total = clips.size, saved = saved, error = e.message ?: "Export failed.")
                AnalysisManager.exportNote.value = ExportNote("Failed", 0f, error = e.message ?: "Export failed.")
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun resetExport() {
        exportUi.value = ExportUi()
    }

    // ------------------------------------------------------------------ delete

    fun deleteClip(c: ClipEntity) {
        viewModelScope.launch {
            dao.deleteClip(c.id)
            withContext(Dispatchers.IO) {
                File(c.filePath).delete()
                c.thumbPath?.let { File(it).delete() }
            }
        }
    }

    fun deleteProject(p: ProjectEntity) {
        viewModelScope.launch {
            dao.deleteProject(p.id)   // clips rows are removed by cascade
            withContext(Dispatchers.IO) { File(Paths.projects(ctx), p.id).deleteRecursively() }
        }
    }
}
