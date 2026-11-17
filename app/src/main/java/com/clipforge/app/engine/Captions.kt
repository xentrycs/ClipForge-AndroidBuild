package com.clipforge.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// =====================================================================================================================
//  Subtitle model
// =====================================================================================================================

/** One spoken word on the finished video's timeline. [src] is the recognised word it came from (edits map back through it). */
class OutWord(val startMs: Long, val endMs: Long, val text: String, val src: Word? = null)

/** A caption line: the words shown together. It stays on screen until [showUntilMs]. */
class CapLine(val startMs: Long, val endMs: Long, val words: List<OutWord>, var showUntilMs: Long = endMs + 400)

/** Where the background box goes: none, behind the whole line, or only behind the word being said. */
enum class CapBox { NONE, LINE, WORD }

/** ALL shows the whole line at once; PROGRESSIVE types the words in as they are spoken. */
enum class CapReveal { ALL, PROGRESSIVE }

/** Everything that makes one subtitle look. Sizes are fractions of the font size so styles scale with the frame. */
class CaptionStyle(
    val id: String,
    val name: String,
    val family: String = "sans-serif",
    val bold: Boolean = true,
    val italic: Boolean = false,
    val upper: Boolean = false,
    val sizeMul: Float = 1f,
    val text: Int = 0xFFFFFFFF.toInt(),
    val active: Int? = null,          // colour of the word being spoken
    val stroke: Int? = null,          // outline colour
    val strokeW: Float = 0f,          // visible outline width, as a fraction of the font size
    val shadow: Int? = null,
    val glow: Int? = null,
    val box: CapBox = CapBox.NONE,
    val boxColor: Int = 0,            // box behind the whole line
    val activeBox: Int = 0,           // box behind the spoken word (box = WORD)
    val activeText: Int? = null,      // text colour on top of that box
    val gradient: IntArray? = null,   // fills the text with a top-to-bottom gradient
    val reveal: CapReveal = CapReveal.ALL,
    val words: Int = 4,               // default words per line
    val pop: Boolean = true,          // the spoken word bounces in
    val popAmt: Float = 0.16f
)

private const val YELLOW = 0xFFFFD60A
private const val BLACK = 0xFF000000

val CAPTION_STYLES: List<CaptionStyle> = listOf(
    // Big, heavy, outlined: the "viral creator" look. The spoken word turns yellow and pops.
    CaptionStyle(
        "hormozi", "Hormozi", family = "sans-serif-black", upper = true, sizeMul = 1.15f,
        active = YELLOW.toInt(), stroke = BLACK.toInt(), strokeW = 0.16f, shadow = 0x99000000.toInt(), words = 3
    ),
    // Condensed, huge, thick outline.
    CaptionStyle(
        "beast", "Beast", family = "sans-serif-condensed", upper = true, sizeMul = 1.45f,
        active = 0xFF30D158.toInt(), stroke = BLACK.toInt(), strokeW = 0.2f, shadow = 0xAA000000.toInt(), words = 2, popAmt = 0.2f
    ),
    // Dark line box, spoken word sits on a coloured pill.
    CaptionStyle(
        "karaoke", "Karaoke", box = CapBox.WORD, boxColor = 0, activeBox = 0xFFFF2D55.toInt(),
        activeText = 0xFFFFFFFF.toInt(), shadow = 0xCC000000.toInt(), sizeMul = 1.05f, words = 4
    ),
    // Marker-pen highlight on the spoken word.
    CaptionStyle(
        "marker", "Marker", family = "sans-serif-black", upper = true, box = CapBox.WORD,
        activeBox = YELLOW.toInt(), activeText = BLACK.toInt(), stroke = BLACK.toInt(), strokeW = 0.12f, words = 3, sizeMul = 1.1f
    ),
    // Dark text with a thick white edge, like a cut-out sticker.
    CaptionStyle(
        "sticker", "Sticker", family = "sans-serif-black", upper = true, text = 0xFF111111.toInt(),
        active = 0xFFFF2D55.toInt(), stroke = 0xFFFFFFFF.toInt(), strokeW = 0.26f, shadow = 0x88000000.toInt(), words = 3, sizeMul = 1.1f
    ),
    // Yellow-to-red gradient with a white pop on the spoken word.
    CaptionStyle(
        "sunset", "Sunset", family = "sans-serif-black", upper = true, text = YELLOW.toInt(), active = 0xFFFFFFFF.toInt(),
        gradient = intArrayOf(0xFFFFE259.toInt(), 0xFFFF9500.toInt(), 0xFFFF2D55.toInt()),
        stroke = BLACK.toInt(), strokeW = 0.12f, shadow = 0x99000000.toInt(), words = 3, sizeMul = 1.1f
    ),
    // Glowing tube lights.
    CaptionStyle(
        "neon", "Neon", upper = true, text = 0xFFE6FBFF.toInt(), glow = 0xFF00E5FF.toInt(), active = 0xFFFF2BD6.toInt(), words = 3, sizeMul = 1.05f
    ),
    // One big word at a time.
    CaptionStyle(
        "oneword", "One Word", family = "sans-serif-black", upper = true, sizeMul = 1.9f,
        stroke = BLACK.toInt(), strokeW = 0.18f, shadow = 0x99000000.toInt(), words = 1, popAmt = 0.25f
    ),
    // Plain white text on a dark box. Easy to read on anything.
    CaptionStyle(
        "classic", "Classic", box = CapBox.LINE, boxColor = 0xB3000000.toInt(), pop = false, words = 5
    ),
    // Light and quiet: soft shadow only.
    CaptionStyle(
        "minimal", "Minimal", family = "sans-serif-medium", bold = false, sizeMul = 0.95f, shadow = 0xDD000000.toInt(), pop = false, words = 5
    ),
    // Serif italic with a gold spoken word.
    CaptionStyle(
        "elegant", "Elegant", family = "serif", italic = true, sizeMul = 1.08f, active = 0xFFFFD27F.toInt(),
        shadow = 0xDD000000.toInt(), pop = false, words = 4
    ),
    // Words are typed in as they are said.
    CaptionStyle(
        "typewriter", "Typewriter", family = "monospace", box = CapBox.LINE, boxColor = 0xCC000000.toInt(),
        active = 0xFF30D158.toInt(), reveal = CapReveal.PROGRESSIVE, pop = false, words = 4, sizeMul = 0.95f
    )
)

/** What the user chose for the captions: a style plus the tweaks on top of it. */
class CapOpts(
    val style: CaptionStyle,
    val sizeScale: Float,
    val posY: Float,              // 0 = top of the frame, 1 = bottom; the middle of the caption block sits here
    val words: Int,               // 0 = the style's own words per line
    val highlight: Int?,          // overrides the style's spoken-word colour
    val pop: Boolean,
    val keywords: Boolean         // also colour hook / emotion words
)

// =====================================================================================================================
//  Words -> lines
// =====================================================================================================================

/** Caption words moved onto the finished timeline, following every cut and speed change, with sentence-style capitals. */
fun outWords(segs: List<Seg>, words: List<Word>): List<OutWord> {
    val raw = ArrayList<OutWord>()
    var off = 0L
    for (s in segs) {
        for (w in words) {
            if (w.startMs >= s.startMs && w.startMs < s.endMs) {
                val a = off + ((w.startMs - s.startMs) / s.speed).toLong()
                val b = off + ((min(w.endMs, s.endMs) - s.startMs) / s.speed).toLong()
                raw.add(OutWord(a, max(b, a + 120), w.text, w))
            }
        }
        off += s.outMs
    }
    val sorted = raw.sortedBy { it.startMs }
    // The recogniser gives lower-case words. Capitalise "I" and the start of each sentence (after a pause).
    return sorted.mapIndexed { i, w ->
        val prev = sorted.getOrNull(i - 1)
        val startsSentence = prev == null || w.startMs - prev.endMs > 700
        var t = w.text
        if (t == "i" || t.startsWith("i'")) t = "I" + t.drop(1)
        else if (startsSentence) t = t.replaceFirstChar { it.uppercase() }
        OutWord(w.startMs, w.endMs, t, w.src)
    }
}

private val WEAK_END = setOf("a", "an", "the", "and", "or", "but", "to", "of", "in", "on", "at", "for", "with", "my", "your", "our", "is", "are", "was", "that", "so")

/** Groups words into lines: a pause, the word limit or the width ends one. A line never ends on "the", "and", "to"... */
fun buildLines(ow: List<OutWord>, maxChars: Int, maxWords: Int): List<CapLine> {
    val out = ArrayList<CapLine>()
    var cur = ArrayList<OutWord>()
    var chars = 0
    fun flush(carryWeak: Boolean) {
        if (cur.isEmpty()) return
        var keep: OutWord? = null
        if (carryWeak && cur.size > 1 && cur.last().text.lowercase() in WEAK_END) keep = cur.removeAt(cur.size - 1)
        out.add(CapLine(cur.first().startMs, cur.last().endMs, cur))
        cur = ArrayList()
        chars = 0
        if (keep != null) { cur.add(keep); chars = keep.text.length + 1 }
    }
    for (w in ow) {
        if (cur.isNotEmpty()) {
            val gap = w.startMs - cur.last().endMs
            if (gap > 600) flush(false)
            else if (cur.size >= maxWords || chars + 1 + w.text.length > maxChars) flush(true)
        }
        cur.add(w)
        chars += w.text.length + 1
    }
    flush(false)
    for (i in out.indices) {
        val next = out.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        out[i].showUntilMs = max(out[i].endMs, min(out[i].endMs + 400, next))
    }
    return out
}

/** The caption line that should be on screen at [tMs]. */
fun activeLine(lines: List<CapLine>, tMs: Long): CapLine? =
    lines.firstOrNull { tMs >= it.startMs - 80 && tMs < it.showUntilMs }

private fun frameSizePx(o: CapOpts, frameW: Int) = frameW * 0.058f * o.style.sizeMul * o.sizeScale

/** The lines the style shows, for the current cuts. */
fun captionLinesFor(m: EditorModel, frameW: Int): List<CapLine> {
    val o = m.capOpts()
    val sizePx = frameSizePx(o, frameW)
    val perRow = (frameW * 0.88f / (sizePx * 0.6f)).toInt()
    val maxChars = max(10, perRow * 2 - 2)
    val maxWords = if (o.words > 0) o.words else o.style.words
    return buildLines(outWords(m.segs.toList(), m.words), maxChars, maxWords)
}

/** Longer lines for reading and fixing text, whatever style is picked. */
fun readingLines(m: EditorModel): List<CapLine> = buildLines(outWords(m.segs.toList(), m.words), 38, 7)

// ---------------------------------------------------------------- .srt

private fun srtTime(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", t / 3_600_000, (t / 60_000) % 60, (t / 1000) % 60, t % 1000)
}

/** A standard SubRip file for the finished timeline, ready for any player or editor. */
fun buildSrt(m: EditorModel): String {
    val sb = StringBuilder()
    for ((i, l) in readingLines(m).withIndex()) {
        sb.append(i + 1).append('\n')
            .append(srtTime(l.startMs)).append(" --> ").append(srtTime(l.showUntilMs)).append('\n')
            .append(l.words.joinToString(" ") { it.text }).append("\n\n")
    }
    return sb.toString()
}

// =====================================================================================================================
//  Drawing: one renderer, used by the live preview AND the export, so what you see is what you get
// =====================================================================================================================

object CaptionRenderer {
    private const val POP_MS = 160L

    // Limited by memory, not by count: a 4K source makes each picture much bigger than a 1080p one.
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val samples = HashMap<String, Bitmap>()
    private val faces = HashMap<String, Typeface>()

    /** A 1x1 clear picture, used when no caption is showing. */
    val blank: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    private fun face(s: CaptionStyle): Typeface = synchronized(faces) {
        faces.getOrPut(s.id) {
            val st = when {
                s.bold && s.italic -> Typeface.BOLD_ITALIC
                s.bold -> Typeface.BOLD
                s.italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(s.family, st)
        }
    }

    /** The picture for [line] at time [tMs]. Same inputs give the same Bitmap object, so the GPU texture is reused. */
    fun render(line: CapLine, tMs: Long, o: CapOpts, frameW: Int): Bitmap {
        var cur = 0
        for ((i, w) in line.words.withIndex()) if (w.startMs <= tMs) cur = i
        val popOn = o.pop && o.style.pop
        val phase = if (popOn) (((tMs - line.words[cur].startMs).coerceIn(0L, POP_MS) * 4) / POP_MS).toInt() else 4
        val key = StringBuilder()
            .append(o.style.id).append('|').append(frameW).append('|').append(o.sizeScale).append('|')
            .append(o.highlight).append('|').append(o.keywords).append('|').append(cur).append('|').append(phase)
        for (w in line.words) key.append('|').append(w.text)
        val k = key.toString()
        cache.get(k)?.let { return it }
        val bmp = draw(line.words, cur, phase, o, frameW)
        cache.put(k, bmp)
        return bmp
    }

    /** A small "Your words" picture for the style picker. */
    fun sample(s: CaptionStyle, highlight: Int?): Bitmap = synchronized(samples) {
        samples.getOrPut(s.id + "|" + highlight) {
            val ws = if (s.words == 1) listOf(OutWord(0, 800, "Words")) else listOf(OutWord(0, 400, "Your"), OutWord(400, 800, "words"))
            val o = CapOpts(s, 1.9f, 0.5f, 0, highlight, true, false)
            // second word is being said, 100 ms in, so the pop shows
            draw(ws, ws.size - 1, 2, o, 300)
        }
    }

    private fun draw(words: List<OutWord>, cur: Int, phase: Int, o: CapOpts, frameW: Int): Bitmap {
        val s = o.style
        val sizePx = frameSizePx(o, frameW)
        val pad = sizePx * 0.6f
        val rowH = sizePx * 1.3f
        val bh = (rowH * 3 + pad * 2).toInt()
        val bmp = Bitmap.createBitmap(frameW, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = face(s)
        paint.textSize = sizePx
        val fm = paint.fontMetrics

        val texts = words.map { if (s.upper) it.text.uppercase() else it.text }
        val space = sizePx * 0.28f
        val widths = texts.map { paint.measureText(it) }

        // Wrap into at most 3 rows.
        val maxW = frameW * 0.92f - sizePx * 0.6f
        val rows = ArrayList<ArrayList<Int>>()
        var row = ArrayList<Int>()
        var rw = 0f
        for (i in texts.indices) {
            val add = if (row.isEmpty()) widths[i] else space + widths[i]
            if (row.isNotEmpty() && rw + add > maxW && rows.size < 2) {
                rows.add(row)
                row = ArrayList()
                rw = widths[i]
            } else rw += add
            row.add(i)
        }
        if (row.isNotEmpty()) rows.add(row)

        val hi = o.highlight ?: s.active
        val keyColor = o.highlight ?: s.active ?: YELLOW.toInt()
        val textH = fm.descent - fm.ascent
        val top = (bh - rows.size * rowH) / 2f

        for ((r, idxs) in rows.withIndex()) {
            var rowW = space * (idxs.size - 1)
            for (i in idxs) rowW += widths[i]
            val base = top + r * rowH + (rowH - textH) / 2f - fm.ascent
            var x = (frameW - rowW) / 2f

            if (s.box == CapBox.LINE) {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.FILL
                paint.color = s.boxColor
                val px = sizePx * 0.32f
                val py = sizePx * 0.08f
                cv.drawRoundRect(RectF(x - px, base + fm.ascent - py, x + rowW + px, base + fm.descent + py), sizePx * 0.3f, sizePx * 0.3f, paint)
                paint.typeface = face(s); paint.textSize = sizePx
            }

            for (i in idxs) {
                val active = i == cur
                if (s.reveal == CapReveal.ALL || i <= cur) {
                    val isKey = o.keywords && !active && TextScore.isKeyword(words[i].text.lowercase())
                    val scale = if (active && o.pop && s.pop) 1f + s.popAmt * sin(Math.PI * phase / 4.0).toFloat() else 1f
                    val cx = x + widths[i] / 2f
                    val cy = base + (fm.ascent + fm.descent) / 2f
                    cv.save()
                    if (scale != 1f) cv.scale(scale, scale, cx, cy)

                    if (s.box == CapBox.WORD && active) {
                        paint.style = Paint.Style.FILL
                        paint.shader = null
                        paint.clearShadowLayer()
                        paint.color = o.highlight ?: s.activeBox
                        val px = sizePx * 0.2f
                        val py = sizePx * 0.06f
                        cv.drawRoundRect(RectF(x - px, base + fm.ascent - py, x + widths[i] + px, base + fm.descent + py), sizePx * 0.26f, sizePx * 0.26f, paint)
                    }

                    val fill = when {
                        s.box == CapBox.WORD && active -> s.activeText ?: s.text
                        active && hi != null -> hi
                        isKey -> keyColor
                        else -> s.text
                    }
                    val glowColor = if (s.glow == null) null else (if (active && hi != null) hi else s.glow)
                    drawStyled(cv, paint, texts[i], x, base, fill, glowColor, s, sizePx, fm, s.gradient != null && fill == s.text)
                    cv.restore()
                }
                x += widths[i] + space
            }
        }
        return bmp
    }

    private fun drawStyled(
        cv: Canvas, p: Paint, t: String, x: Float, y: Float, fill: Int, glow: Int?,
        s: CaptionStyle, size: Float, fm: Paint.FontMetrics, gradient: Boolean
    ) {
        val strokeColor = s.stroke
        val strokeOn = strokeColor != null && s.strokeW > 0f
        p.shader = null
        p.clearShadowLayer()

        if (glow != null) {
            // soft light around the letters: drawn twice to make it stronger
            p.style = Paint.Style.FILL
            p.color = fill
            p.setShadowLayer(size * 0.4f, 0f, 0f, glow)
            cv.drawText(t, x, y, p)
            cv.drawText(t, x, y, p)
            p.clearShadowLayer()
        } else if (s.shadow != null) {
            // drop shadow (drawn from the outline when there is one, so the shadow hugs the outer edge)
            if (strokeOn) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = size * s.strokeW * 2f
                p.strokeJoin = Paint.Join.ROUND
                p.color = strokeColor!!
            } else {
                p.style = Paint.Style.FILL
                p.color = fill
            }
            p.setShadowLayer(size * 0.10f, 0f, size * 0.06f, s.shadow)
            cv.drawText(t, x, y, p)
            p.clearShadowLayer()
        }

        if (strokeOn) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = size * s.strokeW * 2f
            p.strokeJoin = Paint.Join.ROUND
            p.color = strokeColor!!
            cv.drawText(t, x, y, p)
        }

        p.style = Paint.Style.FILL
        val colors = s.gradient
        if (gradient && colors != null) p.shader = LinearGradient(0f, y + fm.ascent, 0f, y + fm.descent, colors, null, Shader.TileMode.CLAMP)
        p.color = fill
        cv.drawText(t, x, y, p)
        p.shader = null
    }
}

/** The export overlay: Media3 asks for a picture for every video frame; this answers with the caption for that moment. */
@UnstableApi
class CaptionOverlay(
    private val lines: List<CapLine>, private val opts: CapOpts, private val frameW: Int, anchorY: Float
) : BitmapOverlay() {
    private val settings = StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, anchorY).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val t = presentationTimeUs / 1000
        val line = activeLine(lines, t) ?: return CaptionRenderer.blank
        return CaptionRenderer.render(line, t, opts, frameW)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
}
