package com.clipforge.app.engine

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Job
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One piece of the timeline: a range of the source video, with its own speed and volume. */
data class Seg(val id: Int, val startMs: Long, val endMs: Long, val speed: Float = 1f, val volume: Float = 1f) {
    /** How long this piece lasts in the finished video. */
    val outMs: Long get() = ((endMs - startMs) / speed).toLong()
}

/** Text on the picture. Times are on the finished video's timeline. pos: 0 top, 1 middle, 2 bottom. */
data class TextItem(val id: Int, val text: String, val startMs: Long, val endMs: Long, val pos: Int, val color: Int)

/** Everything about one edit in progress. Lives in the ViewModel so it survives leaving the screen. */
class EditorModel(val sourcePath: String, val title: String, val widthPx: Int, val heightPx: Int, val durationMs: Long) {
    private var ids = 0
    val segs = mutableStateListOf(Seg(ids++, 0L, durationMs))
    val texts = mutableStateListOf<TextItem>()
    var selected by mutableStateOf(0)
    var canvas by mutableStateOf(AspectMode.VERTICAL)

    // ---- subtitles
    var words by mutableStateOf<List<Word>>(emptyList())      // recognised words, on the SOURCE video's timeline
    var captionsOn by mutableStateOf(false)
    var style by mutableStateOf(0)                            // index into CAPTION_STYLES
    var capSize by mutableStateOf(1f)                         // size multiplier on top of the style
    var capPos by mutableStateOf(0.78f)                       // 0 = top .. 1 = bottom
    var capWords by mutableStateOf(0)                         // words per line, 0 = the style's own
    var capColor by mutableStateOf<Int?>(null)                // spoken-word colour override
    var capPop by mutableStateOf(true)
    var capKeywords by mutableStateOf(false)
    var capLang by mutableStateOf(SpeechModels.default)
    var capBusy by mutableStateOf<String?>(null)
    var capError by mutableStateOf<String?>(null)
    var capInfo by mutableStateOf<String?>(null)

    fun capOpts() = CapOpts(
        CAPTION_STYLES[style.coerceIn(0, CAPTION_STYLES.lastIndex)], capSize, capPos, capWords, capColor, capPop, capKeywords
    )

    /** Frame width in pixels that captions are drawn for (the same number the export uses). */
    fun frameWidth(): Int = if (canvas.w > 0) canvas.w else max(widthPx, 480)

    /** Replaces the text of one caption line. The new words share the line's time span. Empty text deletes the line. */
    fun editCaption(line: CapLine, newText: String) {
        val srcWords = line.words.mapNotNull { it.src }
        if (srcWords.isEmpty()) return
        val lineSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Word, Boolean>())
        lineSet.addAll(srcWords)
        val at = words.indexOfFirst { it in lineSet }
        if (at < 0) return
        val kept = words.filter { it !in lineSet }
        val tokens = newText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val a = srcWords.minOf { it.startMs }
        val b = srcWords.maxOf { it.endMs }
        val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
        var t = a
        val fresh = tokens.map { tok ->
            val d = (b - a) * tok.length / totalChars
            Word(t, t + d, tok, 1f).also { t += d }
        }
        val merged = ArrayList<Word>(kept)
        merged.addAll(at.coerceAtMost(merged.size), fresh)
        words = merged.sortedBy { it.startMs }
    }

    var exportBusy by mutableStateOf(false)
    var exportProgress by mutableStateOf(0f)
    var exportDone by mutableStateOf(false)
    var exportError by mutableStateOf<String?>(null)
    var exportJob: Job? = null

    fun outTotal(): Long = segs.sumOf { it.outMs }

    fun splitAt(outMs: Long) {
        val list = segs.toList()
        var off = 0L
        for ((i, s) in list.withIndex()) {
            if (outMs < off + s.outMs) {
                val srcT = s.startMs + ((outMs - off) * s.speed).toLong()
                if (srcT - s.startMs >= 300 && s.endMs - srcT >= 300) {
                    segs[i] = s.copy(endMs = srcT)
                    segs.add(i + 1, Seg(ids++, srcT, s.endMs, s.speed, s.volume))
                    selected = i + 1
                }
                return
            }
            off += s.outMs
        }
    }

    fun deleteSelected() {
        if (segs.size > 1) {
            segs.removeAt(selected)
            selected = selected.coerceIn(0, segs.size - 1)
        }
    }

    fun move(delta: Int) {
        val j = selected + delta
        if (j in segs.indices) {
            val t = segs[selected]; segs[selected] = segs[j]; segs[j] = t
            selected = j
        }
    }

    fun updateSelected(f: (Seg) -> Seg) {
        if (selected in segs.indices) segs[selected] = f(segs[selected])
    }

    fun addText(text: String, startMs: Long, pos: Int, color: Int) {
        texts.add(TextItem(ids++, text, startMs, startMs + 3000, pos, color))
    }
}

/** Which piece plays at [outMs] on the finished timeline, and how far into that piece (in source milliseconds). */
fun locateOut(segs: List<Seg>, outMs: Long): Pair<Int, Long> {
    var off = 0L
    for ((i, s) in segs.withIndex()) {
        if (outMs < off + s.outMs) return i to ((outMs - off) * s.speed).toLong()
        off += s.outMs
    }
    val last = segs.lastIndex.coerceAtLeast(0)
    return last to (segs.getOrNull(last)?.let { it.endMs - it.startMs } ?: 0L)
}

// ---------------------------------------------------------------- export

@UnstableApi
private class TimedTextOverlay(private val item: TextItem, private val sizePx: Int, y: Float) : TextOverlay() {
    private val settings = StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, y).build()

    override fun getText(presentationTimeUs: Long): SpannableString {
        val t = presentationTimeUs / 1000
        if (t < item.startMs || t >= item.endMs) return blank(sizePx)
        val sp = SpannableString(item.text)
        val f = Spanned.SPAN_INCLUSIVE_INCLUSIVE
        sp.setSpan(AbsoluteSizeSpan(sizePx), 0, sp.length, f)
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, sp.length, f)
        sp.setSpan(ForegroundColorSpan(item.color), 0, sp.length, f)
        sp.setSpan(BackgroundColorSpan(0x80000000.toInt()), 0, sp.length, f)
        return sp
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}

private fun blank(sizePx: Int): SpannableString {
    val sp = SpannableString(" ")
    sp.setSpan(AbsoluteSizeSpan(sizePx), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    sp.setSpan(ForegroundColorSpan(0), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    return sp
}

@UnstableApi
object EditorExport {

    private fun constantSpeed(s: Float) = object : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = s
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private fun volume(v: Float): AudioProcessor {
        val p = ChannelMixingAudioProcessor()
        for (ch in 1..2) p.putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(v))
        return p
    }

    /** Builds the whole edit as one Media3 composition: cuts, speed, volume, canvas, text and captions. */
    fun build(m: EditorModel): Composition {
        val src = Uri.fromFile(File(m.sourcePath))
        val items = m.segs.toList().map { s ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build()
            val b = EditedMediaItem.Builder(MediaItem.Builder().setUri(src).setClippingConfiguration(clipping).build())
            val audioFx = ArrayList<AudioProcessor>()
            val videoFx = ArrayList<Effect>()
            if (s.speed != 1f) {
                // Media3 1.6 removed EditedMediaItem.Builder.setSpeed: speed is now an audio processor + a video effect.
                audioFx.add(SpeedChangingAudioProcessor(constantSpeed(s.speed)))
                videoFx.add(SpeedChangeEffect(s.speed))
            }
            if (s.volume != 1f) audioFx.add(volume(s.volume))
            if (audioFx.isNotEmpty() || videoFx.isNotEmpty()) b.setEffects(Effects(audioFx, videoFx))
            b.build()
        }

        val canvas = m.canvas
        val frameW = if (canvas.w > 0) canvas.w else max(m.widthPx, 480)
        val vfx = ArrayList<Effect>()
        // Fit the WHOLE picture inside the canvas (bars fill the rest), like CapCut. Nothing is cropped.
        if (canvas.w > 0) vfx.add(Presentation.createForWidthAndHeight(canvas.w, canvas.h, Presentation.LAYOUT_SCALE_TO_FIT))

        val overlays = ArrayList<TextureOverlay>()
        if (m.captionsOn && m.words.isNotEmpty()) {
            val opts = m.capOpts()
            val lines = captionLinesFor(m, frameW)
            // Anchor: +1 is the top edge of the frame, -1 the bottom edge; the middle of the caption block sits there.
            if (lines.isNotEmpty()) overlays.add(CaptionOverlay(lines, opts, frameW, 1f - 2f * opts.posY))
        }
        for (t in m.texts) {
            val y = when (t.pos) { 0 -> 0.78f; 1 -> 0f; else -> -0.80f }
            overlays.add(TimedTextOverlay(t, (frameW * 0.07f).toInt(), y))
        }
        if (overlays.isNotEmpty()) vfx.add(OverlayEffect(ImmutableList.copyOf(overlays)))

        val sequence = EditedMediaItemSequence.Builder(*items.toTypedArray()).build()
        return Composition.Builder(sequence).setEffects(Effects(emptyList(), vfx)).build()
    }
}
