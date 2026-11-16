package com.clipforge.app

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.clipforge.app.data.AppDb
import com.clipforge.app.data.ClipEntity
import com.clipforge.app.data.Paths
import com.clipforge.app.data.ProjectDao
import com.clipforge.app.data.ProjectEntity
import com.clipforge.app.engine.AudioAnalyzer
import com.clipforge.app.engine.ClipMedia
import com.clipforge.app.engine.ClipSettings
import com.clipforge.app.engine.HighlightDetector
import com.clipforge.app.engine.SceneAnalyzer
import com.clipforge.app.engine.Thumbs
import com.clipforge.app.engine.Transcriber
import com.clipforge.app.engine.VoskModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * What to analyse.
 *  - stagingDir != null: a freshly imported video that still has to be moved into its project folder.
 *  - retry = true: the project already exists (e.g. it was interrupted); analyse its saved source video again.
 */
class AnalysisRequest(
    val projectId: String,
    val title: String,
    val kind: String,
    val durationMs: Long,
    val source: File,
    val stagingDir: File?,
    val settings: ClipSettings,
    val retry: Boolean
)

/** Progress of an export, mirrored here so the foreground service can keep the app alive while it runs. */
class ExportNote(
    val text: String,
    val progress: Float,
    val finished: Boolean = false,
    val error: String? = null,
    val title: String = "ClipForge is saving your clips",
    /** true = no "done" notification afterwards (used for downloads, the app flow continues on its own) */
    val quiet: Boolean = false,
    val doneTitle: String = "Clips saved",
    val doneText: String = "Find them in your gallery under Movies/ClipForge."
)

/**
 * Runs the analysis outside of any screen or ViewModel, so it keeps going when the app is minimised or the
 * screen turns off (the foreground service keeps the process alive and shows the notification).
 */
@UnstableApi
object AnalysisManager {
    val ui = MutableStateFlow<AnalysisUi?>(null)
    val exportNote = MutableStateFlow<ExportNote?>(null)

    /** Set by MainActivity: true while the app is on screen (used to skip the "ready" notification). */
    @Volatile var appVisible = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    /** Returns false when another analysis is still running. */
    @Synchronized
    fun start(app: Context, req: AnalysisRequest): Boolean {
        if (isRunning()) return false
        val ctx = app.applicationContext
        ui.value = AnalysisUi(req.projectId, "Preparing…", 0f)
        AnalysisService.start(ctx)
        job = scope.launch { run(ctx, req) }
        return true
    }

    fun cancel() {
        job?.cancel()
        ui.value = null
    }

    fun clear() {
        ui.value = null
    }

    private fun setStage(id: String, stage: String, progress: Float) {
        ui.value = AnalysisUi(id, stage, progress.coerceIn(0f, 1f))
    }

    private suspend fun removeClips(dao: ProjectDao, projectId: String) {
        for (c in dao.clipsOnce(projectId)) {
            File(c.filePath).delete()
            val thumb = c.thumbPath
            if (thumb != null) File(thumb).delete()
        }
        dao.deleteClipsOf(projectId)
    }

    private suspend fun run(ctx: Context, req: AnalysisRequest) {
        val dao = AppDb.get(ctx).dao()
        val id = req.projectId
        val projDir = File(Paths.projects(ctx), id)
        val staging = req.stagingDir
        try {
            val src: File
            if (staging != null) {
                withContext(Dispatchers.IO) {
                    projDir.parentFile?.mkdirs()
                    if (!staging.renameTo(projDir)) {
                        staging.copyRecursively(projDir, overwrite = true)
                        staging.deleteRecursively()
                    }
                }
                src = File(projDir, req.source.name)
            } else {
                src = req.source
            }
            val duration = req.durationMs
            val thumbFile = File(projDir, "thumb.jpg")
            if (!thumbFile.exists()) {
                withContext(Dispatchers.IO) { Thumbs.save(src.path, duration / 10, thumbFile) }
            }

            if (req.retry) {
                removeClips(dao, id)
                dao.setStatus(id, "ANALYZING", null)
            } else {
                dao.upsert(
                    ProjectEntity(
                        id = id, title = req.title, sourceTitle = req.title, createdAt = System.currentTimeMillis(),
                        durationMs = duration, thumbPath = thumbFile.takeIf { it.exists() }?.path,
                        status = "ANALYZING", statusMessage = null, sourcePath = src.path, sourceKind = req.kind
                    )
                )
            }

            // Audio, picture and speech are analysed at the same time.
            var audioP = 0f
            var visualP = 0f
            var textP = 0f
            var stageText = "Analysing audio, picture and speech…"
            fun push() = setStage(id, stageText, (audioP + visualP + textP) / 3f * 0.6f)
            setStage(id, stageText, 0f)
            val (audio, visual, transcript) = coroutineScope {
                val a = async { AudioAnalyzer.analyze(src.path, duration) { audioP = it; push() } }
                val v = async { SceneAnalyzer.analyze(src.path, duration) { visualP = it; push() } }
                val t = async {
                    // Speech is a bonus: if anything goes wrong the clips are still found from sound and picture.
                    try {
                        val dir = VoskModel.ensure(ctx) { p ->
                            stageText = "Downloading speech model (first time only)…"
                            textP = p * 0.2f; push()
                        }
                        stageText = "Analysing audio, picture and speech…"
                        if (dir == null) { textP = 1f; push(); null }
                        else Transcriber.transcribe(dir, src.path, duration) { textP = 0.2f + it * 0.8f; push() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        textP = 1f; push(); null
                    }
                }
                Triple(a.await(), v.await(), t.await())
            }

            setStage(id, "Ranking the best moments…", 0.6f)
            val found = withContext(Dispatchers.Default) { HighlightDetector.detect(audio, visual, duration, req.settings, transcript) }

            var made = 0
            found.forEachIndexed { i, d ->
                val base = 0.65f + 0.35f * i / found.size
                setStage(id, "Cutting clip ${i + 1} of ${found.size}…", base)
                val clipId = UUID.randomUUID().toString()
                val out = File(projDir, "clips/$clipId.mp4")
                try {
                    ClipMedia.cut(ctx, src.path, d.startMs, d.endMs, out)
                    val thumb = File(projDir, "clips/$clipId.jpg")
                    withContext(Dispatchers.IO) { Thumbs.save(out.path, 1000, thumb) }
                    dao.insertClip(
                        ClipEntity(
                            id = clipId, projectId = id, title = d.title, category = d.category, score = d.score,
                            startMs = d.startMs, endMs = d.endMs, filePath = out.path,
                            thumbPath = thumb.takeIf { it.exists() }?.path
                        )
                    )
                    made++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    out.delete()   // skip this clip, keep going
                }
            }
            if (made == 0) throw IllegalStateException("Highlights were found but none of the clips could be rendered on this device.")

            dao.setStatus(id, "READY", null)
            ui.value = AnalysisUi(id, "Done", 1f, done = true)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (req.retry) {
                    // Keep the saved video so the project can be retried again later.
                    removeClips(dao, id)
                    dao.setStatus(id, "FAILED", "Cancelled. Tap Retry to analyse again.")
                } else {
                    projDir.deleteRecursively()
                    staging?.deleteRecursively()
                    dao.deleteProject(id)
                }
            }
            ui.value = null
            throw e
        } catch (e: Throwable) {
            val msg = e.message ?: "Analysis failed."
            withContext(NonCancellable) { dao.setStatus(id, "FAILED", msg) }
            ui.value = AnalysisUi(id, "Failed", 0f, error = msg)
        }
    }
}
