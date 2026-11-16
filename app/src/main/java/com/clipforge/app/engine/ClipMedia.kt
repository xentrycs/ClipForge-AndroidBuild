package com.clipforge.app.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AspectMode(val label: String, val ratio: Float?, val w: Int = 0, val h: Int = 0) {
    ORIGINAL("Original", null),
    VERTICAL("9:16", 9f / 16f, 1080, 1920),
    SQUARE("1:1", 1f, 1080, 1080),
    WIDE("16:9", 16f / 9f, 1920, 1080)
}

enum class Quality(val label: String, val bitrate: Int) {
    HIGH("High", 10_000_000),
    STANDARD("Standard", 5_000_000)
}

class VideoMeta(val durationMs: Long, val width: Int, val height: Int)

object Meta {
    fun read(path: String): VideoMeta? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (dur > 0 && w > 0 && h > 0) VideoMeta(dur, w, h) else null
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }
}

object Thumbs {
    fun bitmap(path: String, timeMs: Long, width: Int = 480): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val f = r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val h = (f.height * (width.toFloat() / f.width)).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(f, width, h, true)
            if (s !== f) f.recycle()
            s
        } catch (e: Exception) {
            null
        } finally {
            r.release()
        }
    }

    fun save(path: String, timeMs: Long, out: File): Boolean {
        val b = bitmap(path, timeMs) ?: return false
        return try {
            FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            true
        } catch (e: Exception) {
            false
        } finally {
            b.recycle()
        }
    }
}

@UnstableApi
object ClipMedia {

    /** Cuts [startMs, endMs] of the source into a real, playable MP4 at [out]. */
    suspend fun cut(ctx: Context, src: String, startMs: Long, endMs: Long, out: File) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(File(src)))
            .setClippingConfiguration(clipping)
            .build()
        run(ctx, { t, p -> t.start(EditedMediaItem.Builder(item).build(), p) }, out, null, trimOptimized = true) {}
        if (!out.exists() || out.length() == 0L || Meta.read(out.path) == null) {
            out.delete()
            throw IllegalStateException("The clip file could not be created.")
        }
    }

    /** Re-renders an existing clip to a new aspect ratio (center-crop reframe). */
    suspend fun render(
        ctx: Context,
        input: File,
        out: File,
        aspect: AspectMode,
        quality: Quality,
        onProgress: (Float) -> Unit
    ) {
        val ratio = aspect.ratio ?: throw IllegalArgumentException("Original aspect needs no rendering")
        val effects = ArrayList<Effect>()
        // The whole picture is kept and fitted inside the frame (bars fill the rest), like CapCut. Nothing is cropped.
        effects.add(Presentation.createForWidthAndHeight(aspect.w, aspect.h, Presentation.LAYOUT_SCALE_TO_FIT))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList(), effects))
            .build()
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(quality.bitrate).build())
            .build()
        run(ctx, { t, p -> t.start(edited, p) }, out, encoder, onProgress = onProgress)
        if (!out.exists() || out.length() == 0L || Meta.read(out.path) == null) {
            out.delete()
            throw IllegalStateException("The exported file could not be created.")
        }
    }

    /** Renders a whole editor composition (cuts, speed, text, captions...) to [out]. */
    suspend fun exportComposition(ctx: Context, composition: Composition, out: File, quality: Quality, onProgress: (Float) -> Unit) {
        val encoder = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(quality.bitrate).build())
            .build()
        run(ctx, { t, p -> t.start(composition, p) }, out, encoder, onProgress = onProgress)
    }

    private suspend fun run(
        ctx: Context,
        begin: (Transformer, String) -> Unit,
        out: File,
        encoder: DefaultEncoderFactory?,
        trimOptimized: Boolean = false,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.Main) {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        var transformer: Transformer? = null
        coroutineScope {
            val poll = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    delay(300)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                        onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                    }
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val builder = Transformer.Builder(ctx)
                    if (encoder != null) builder.setEncoderFactory(encoder)
                    // For MP4 sources, only the first few frames are re-encoded; the rest is copied (much faster).
                    // Falls back to normal cutting automatically when it can't apply.
                    if (trimOptimized) builder.experimentalSetTrimOptimizationEnabled(true)
                    val t = builder.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    }).build()
                    transformer = t
                    cont.invokeOnCancellation {
                        // Transformer must be touched from the app's main thread.
                        Handler(Looper.getMainLooper()).post { t.cancel() }
                    }
                    begin(t, out.absolutePath)
                }
            } finally {
                poll.cancel()
            }
        }
    }
}

/** Saves videos into the public gallery under Movies/ClipForge. */
object Gallery {
    fun save(ctx: Context, src: File, displayName: String, folder: String = "Movies/ClipForge", prefix: String = "ClipForge_"): Uri {
        val name = displayName.replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().take(60).ifEmpty { "clip" }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${prefix}${name}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, folder)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create a gallery item.")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("Could not write to the gallery.")
            stream.use { o -> src.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }
}
