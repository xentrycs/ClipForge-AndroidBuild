package com.clipforge.app.engine

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** change: 0..1 how eventful the picture is at this time (cuts, flashes, heavy motion). */
class VisualSample(val timeMs: Long, val change: Float)

/**
 * Samples real frames from the video and measures how eventful the picture is.
 *
 * At every sample point it grabs two exact frames 0.25 s apart:
 *  - motion: how much the picture changes within those 0.25 s (fast action, camera movement, effects)
 *  - cut:    how different this moment looks from the previous sample (scene changes, flashes)
 * Exact frames are used (not just keyframes), otherwise most samples would return the same keyframe
 * and motion would be invisible.
 */
object SceneAnalyzer {
    private const val W = 64
    private const val H = 36
    private const val BINS = 16
    private const val PAIR_GAP_US = 250_000L

    private class Frame(val luma: IntArray, val hist: FloatArray)

    private fun grab(r: MediaMetadataRetriever, timeUs: Long): Frame? {
        val raw = r.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, W, H) ?: return null
        val small = if (raw.width == W && raw.height == H) raw else Bitmap.createScaledBitmap(raw, W, H, true)
        if (small !== raw) raw.recycle()
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        small.recycle()
        val luma = IntArray(px.size)
        val hist = FloatArray(BINS)
        for (i in px.indices) {
            val p = px[i]
            val r8 = (p shr 16) and 0xFF
            val g8 = (p shr 8) and 0xFF
            val b8 = p and 0xFF
            val y = (r8 * 299 + g8 * 587 + b8 * 114) / 1000
            luma[i] = y
            hist[(y * BINS) / 256]++
        }
        for (i in hist.indices) hist[i] /= px.size
        return Frame(luma, hist)
    }

    suspend fun analyze(path: String, durationMs: Long, onProgress: (Float) -> Unit): List<VisualSample> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val stepMs = maxOf(1000L, durationMs / 1400L)
                val out = ArrayList<VisualSample>()
                var prevHist: FloatArray? = null
                var t = 0L
                while (t < durationMs) {
                    ensureActive()
                    val a = grab(retriever, t * 1000)
                    if (a != null) {
                        // motion inside this moment
                        var motion = 0f
                        val b = grab(retriever, t * 1000 + PAIR_GAP_US)
                        if (b != null) {
                            var md = 0f
                            for (i in a.luma.indices) md += abs(a.luma[i] - b.luma[i])
                            motion = (md / (a.luma.size * 255f) * 4f).coerceIn(0f, 1f)
                        }
                        // difference from the previous sample
                        var cut = 0f
                        val ph = prevHist
                        if (ph != null) {
                            var hd = 0f
                            for (i in a.hist.indices) hd += abs(a.hist[i] - ph[i])
                            cut = (hd / 2f).coerceIn(0f, 1f)
                        }
                        if (ph != null) out.add(VisualSample(t, (0.45f * cut + 0.55f * motion).coerceIn(0f, 1f)))
                        prevHist = a.hist
                    }
                    t += stepMs
                    onProgress((t.toFloat() / durationMs).coerceIn(0f, 1f))
                }
                onProgress(1f)
                out
            } finally {
                retriever.release()
            }
        }
}
