package com.clipforge.app.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DetectedClip(
    val startMs: Long,
    val endMs: Long,
    val score: Int,
    val category: String,
    val title: String
)

/** User choices made before analysis. count = 0 means "decide automatically". */
data class ClipSettings(val minSec: Int = 15, val maxSec: Int = 45, val count: Int = 0)

/**
 * Finds the moments people would actually stop scrolling for.
 *
 * Step 1, per second of video, it measures (see AudioAnalyzer / SceneAnalyzer):
 *   loudness, build-up (jump above the last 30 s), shouting/hype, sharp hits, picture change and, new in v3:
 *   - emphasis:  someone is TALKING (voice band + speech rhythm) and suddenly gets louder / sharper
 *   - reveal:    a short silence or dip followed by a loud burst (punchline, twist, drop)
 *   - reaction:  noise-like loud sound that is not a voice (laughter, applause, crowd roar)
 * The weights switch between "action" and "talk" mode depending on how much of the video is speech.
 *
 * Step 2, it scores every possible clip window (many lengths, every second) like a viewer would:
 *   a strong first 4 seconds (the hook), sustained interest, a peak with payoff after the opening,
 *   a real arc (not flat), not starting or ending in the middle of a sentence, a sweet-spot length.
 * Best windows win, with no overlap. Start and end are then snapped to pauses (half-second precision).
 */
object HighlightDetector {

    fun detect(
        audio: AudioSignal,
        visual: List<VisualSample>,
        durationMs: Long,
        settings: ClipSettings = ClipSettings(),
        transcript: Transcript? = null
    ): List<DetectedClip> {
        val n = ceil(durationMs / 1000.0).toInt()
        if (n < 12) throw IllegalStateException("This video is too short to find highlights in (about 12 seconds minimum).")

        val hasAudio = !audio.isEmpty() && audio.rms.any { it > 0.0005f }
        val hasVisual = visual.any { it.change > 0f }
        if (!hasAudio && !hasVisual) {
            throw IllegalStateException("No usable audio or picture signal was found in this video.")
        }
        val spectral = hasAudio && audio.hasSpectral
        // Words, when the speech recogniser produced a usable transcript.
        val tx = transcript?.takeIf { it.usable }
        val ts = if (tx != null) TextScore.series(tx, n) else null
        val txtN = ts?.hook ?: FloatArray(n)
        val fillerS = ts?.filler ?: FloatArray(n)
        val deadS = ts?.dead ?: FloatArray(n)

        // ---------- per half-second (hop) loudness, used for pauses and for voice detection ----------
        val hops = if (hasAudio) audio.size else 0
        val hopDb = FloatArray(hops) { 20f * log10(audio.rms[it] + 1e-5f) }
        val audibleFloor = if (hops > 0) percentile(hopDb, 0.20f) + 2f else 0f

        // voiceness per hop: voice band + speech-like rhythm + not noise-like + audible
        val voiceHop = FloatArray(hops)
        val reactHop = FloatArray(hops)
        if (spectral) {
            for (i in 0 until hops) {
                if (hopDb[i] < audibleFloor) continue
                val vb = ((audio.voice[i] - 0.50f) / 0.30f).coerceIn(0f, 1f)
                val md = (audio.mod[i] / 0.55f).coerceIn(0f, 1f)
                val noisy = ((audio.flat[i] - 0.08f) / 0.22f).coerceIn(0f, 1f)
                voiceHop[i] = vb * md * (1f - noisy)
                reactHop[i] = noisy * (1f - voiceHop[i])
            }
        }

        // ---------- per second ----------
        val db = FloatArray(n) { -100f }
        val peakDb = FloatArray(n) { -100f }
        val brightSum = FloatArray(n)
        val brightCnt = IntArray(n)
        val voiceS = FloatArray(n)
        val reactS = FloatArray(n)
        val perSec = IntArray(n)
        if (hasAudio) {
            for (i in 0 until audio.size) {
                val s = (i.toLong() * AudioAnalyzer.HOP_MS / 1000).toInt()
                if (s >= n) continue
                if (hopDb[i] > db[s]) db[s] = hopDb[i]
                val pk = 20f * log10(audio.peak[i] + 1e-5f)
                if (pk > peakDb[s]) peakDb[s] = pk
                brightSum[s] += audio.bright[i]
                brightCnt[s]++
                voiceS[s] += voiceHop[i]
                reactS[s] += reactHop[i]
                perSec[s]++
            }
            for (s in 0 until n) if (perSec[s] > 0) { voiceS[s] /= perSec[s]; reactS[s] /= perSec[s] }
        }
        val bright = FloatArray(n) { if (brightCnt[it] > 0) brightSum[it] / brightCnt[it] else 0f }

        val energy = if (hasAudio) normalizeRange(db) else FloatArray(n)

        val rise = FloatArray(n)
        if (hasAudio) {
            for (s in 8 until n) {
                val from = max(0, s - 30)
                rise[s] = max(0f, db[s] - median(db, from, s))
            }
        }
        val riseN = normalizeTop(rise)

        val hitN = FloatArray(n)
        if (hasAudio) {
            val crest = FloatArray(n) { max(0f, peakDb[it] - db[it]) }
            val cn = normalizeTop(crest)
            for (s in 0 until n) hitN[s] = cn[s] * energy[s]
        }

        val excN = FloatArray(n)
        if (hasAudio) {
            val bn = normalizeRange(bright)
            for (s in 0 until n) excN[s] = bn[s] * energy[s]
        }

        val vis = FloatArray(n)
        val sorted = visual.sortedBy { it.timeMs }
        for (i in sorted.indices) {
            val from = (sorted[i].timeMs / 1000).toInt()
            val to = if (i + 1 < sorted.size) (sorted[i + 1].timeMs / 1000).toInt() else min(n, from + 1)
            for (s in from until min(to, n)) if (s >= 0) vis[s] = max(vis[s], sorted[i].change)
        }
        val visN = normalizeTop(vis)

        // Emphasis: speaking AND suddenly louder / sharper than the speaker's own recent level.
        val empN = FloatArray(n)
        // Reveal: loud burst right after a quiet beat (punchline, twist, drop).
        val revN = FloatArray(n)
        // Reaction: laughter / applause / crowd roar.
        val reaN = FloatArray(n)
        if (hasAudio) {
            for (s in 0 until n) {
                empN[s] = voiceS[s].coerceIn(0f, 1f) * max(riseN[s], excN[s])
                if (s >= 2) {
                    val before = min(db[s - 1], db[s - 2])
                    revN[s] = ((db[s] - before - 8f) / 14f).coerceIn(0f, 1f) * (if (energy[s] > 0.3f) 1f else 0f)
                }
                reaN[s] = reactS[s].coerceIn(0f, 1f) * energy[s]
            }
        }
        val reaTop = normalizeTop(reaN)
        val empTop = normalizeTop(empN)

        // How much of this video is somebody talking?
        var talkSec = 0
        for (s in 0 until n) if (voiceS[s] > 0.4f) talkSec++
        val talk = ((talkSec.toFloat() / n - 0.15f) / 0.35f).coerceIn(0f, 1f)

        // ---------- weights: [loud, build-up, hype, hits, visual, emphasis, reveal, reaction] ----------
        fun mix(action: Float, talkW: Float) = action + (talkW - action) * talk
        val audioFactor = if (hasAudio) ((percentile(db, 0.95f) - percentile(db, 0.10f)) / 12f).coerceIn(0.35f, 1f) else 0f
        val visualFactor = if (hasVisual) (percentile(vis, 0.95f) / 0.10f).coerceIn(0.35f, 1f) else 0f
        val sp = if (spectral) 1f else 0f
        val w = floatArrayOf(
            mix(0.20f, 0.10f) * audioFactor,
            mix(0.22f, 0.20f) * audioFactor,
            mix(0.12f, 0.14f) * audioFactor,
            mix(0.12f, 0.02f) * audioFactor,
            mix(0.24f, 0.08f) * visualFactor,
            mix(0.00f, 0.24f) * audioFactor * sp,
            mix(0.00f, 0.12f) * audioFactor,
            mix(0.10f, 0.10f) * audioFactor * sp,
            mix(0.06f, 0.22f) * (if (ts != null) 1f else 0f)
        )
        val wTotal = w.sum()
        for (i in w.indices) w[i] = w[i] / wTotal

        val raw = FloatArray(n) {
            w[0] * energy[it] + w[1] * riseN[it] + w[2] * excN[it] + w[3] * hitN[it] + w[4] * visN[it] +
                w[5] * empTop[it] + w[6] * revN[it] + w[7] * reaTop[it] + w[8] * txtN[it]
        }
        // light smoothing (+-1 s) so the opening seconds of a clip still count sharply
        val interest = FloatArray(n) { s ->
            var sum = 0f
            var cnt = 0
            for (k in max(0, s - 1)..min(n - 1, s + 1)) { sum += raw[k]; cnt++ }
            0.6f * raw[s] + 0.4f * (sum / cnt)
        }
        val pre = FloatArray(n + 1)
        for (s in 0 until n) pre[s + 1] = pre[s] + interest[s]
        // Seconds where a clip could start / end cleanly: a new sentence begins after a pause, a finished thought ends before one.
        val startOk = FloatArray(n + 1)
        val endOk = FloatArray(n + 1)
        if (tx != null) {
            val ss = tx.sentences
            for (i in ss.indices) {
                val sk = (ss[i].startMs / 1000).toInt().coerceIn(0, n)
                startOk[sk] = max(startOk[sk], max(0f, startQuality(ss, i)))
                val ek = ceil(ss[i].endMs / 1000.0).toInt().coerceIn(0, n)
                endOk[ek] = max(endOk[ek], max(0f, endQuality(ss, i)))
            }
        }
        val preDead = FloatArray(n + 1)
        val preFill = FloatArray(n + 1)
        for (s in 0 until n) { preDead[s + 1] = preDead[s] + deadS[s]; preFill[s + 1] = preFill[s] + fillerS[s] }

        // ---------- score every clip window like a viewer would ----------
        val minLen = settings.minSec.coerceIn(5, n)
        val maxLen = settings.maxSec.coerceIn(minLen, n)
        val target = when {
            settings.count > 0 -> settings.count
            n < 45 -> 1
            else -> (n / 120).coerceIn(2, 10)
        }
        val lens = LinkedHashSet<Int>()
        val lenStep = max(1, (maxLen - minLen) / 14)
        var ll = minLen
        while (ll < maxLen) { lens.add(ll); ll += lenStep }
        lens.add(maxLen)
        val ideal = 30.coerceIn(minLen, maxLen)
        val edge = min(4, n / 10)

        class Cand(val total: Float, val a: Int, val e: Int)
        val cands = ArrayList<Cand>()
        for (a in 0..(n - minLen)) {
            var best: Cand? = null
            for (len in lens) {
                val e = a + len
                if (e > n) continue
                val mean = (pre[e] - pre[a]) / len

                // hook: how strong are the first 4 seconds
                val hl = min(4, len)
                var first = 0f
                var firstSpike = 0f
                for (k in a until a + hl) {
                    first += interest[k]
                    firstSpike = max(firstSpike, max(revN[k], empTop[k]))
                }
                var firstText = 0f
                for (k in a until min(e, a + 5)) firstText = max(firstText, txtN[k])
                val hook = if (ts != null) (0.50f * (first / hl) + 0.20f * firstSpike + 0.30f * firstText).coerceIn(0f, 1f)
                else (0.65f * (first / hl) + 0.35f * firstSpike).coerceIn(0f, 1f)

                // peak and where it sits
                var peak = 0f
                var peakAt = a
                for (k in a until e) if (interest[k] > peak) { peak = interest[k]; peakAt = k }
                val pos = (peakAt - a).toFloat() / len
                val payoff = peak * (if (pos in 0.2f..0.9f) 1f else 0.82f)
                val spread = ((peak - mean) * 2f).coerceIn(0f, 1f)

                // do not start or end in the middle of someone talking loudly
                var pen = 0.10f * voiceS[e - 1] * energy[e - 1] + 0.06f * voiceS[a] * energy[a]
                if (ts != null) {
                    // dead air and "um/uh" talk make a clip drag
                    pen += 0.15f * (preDead[e] - preDead[a]) / len + 0.25f * (preFill[e] - preFill[a]) / len
                }
                val lenBonus = 1f - min(1f, abs(len - ideal) / 20f)

                var total = 0.38f * mean + 0.22f * hook + 0.25f * payoff + 0.10f * spread + 0.05f * lenBonus - pen
                if (tx != null) total += 0.05f * startOk[a] + 0.05f * endOk[e]
                if (a < edge || e > n - edge) total *= 0.85f   // intros and outros are often loud for no reason
                if (best == null || total > best.total) best = Cand(total, a, e)
            }
            best?.let { cands.add(it) }
        }
        if (cands.isEmpty()) throw IllegalStateException("No standout moments were found.")
        cands.sortByDescending { it.total }

        val bestTotal = cands[0].total
        val floor = if (settings.count > 0) 0.08f else max(0.22f, 0.55f * bestTotal)

        val chosen = ArrayList<Cand>()
        for (c in cands) {
            if (chosen.size >= target) break
            if (c.total < floor) break
            if (chosen.any { c.a < it.e + 2 && c.e > it.a - 2 }) continue
            chosen.add(c)
        }
        if (chosen.isEmpty()) {
            throw IllegalStateException("No standout moments were found. The video may be too flat (constant volume, no visual changes).")
        }

        // ---------- build the clips: snap to pauses, name them ----------
        val results = ArrayList<DetectedClip>()
        val used = ArrayList<LongArray>()
        for (c in chosen) {
            var startMs = c.a * 1000L
            var endMs = min(durationMs, c.e * 1000L)
            var snapped = false
            if (tx != null) {
                val cut = snapToSentences(tx.sentences, interest, c.a * 1000L, c.e * 1000L, minLen * 1000L, maxLen * 1000L, durationMs)
                if (cut != null) { startMs = cut[0]; endMs = cut[1]; snapped = true }
            }
            if (!snapped && hops > 0) {
                val sh = quietHop(hopDb, c.a * 2 - 4, c.a * 2 + 3)
                if (sh >= 0) startMs = max(0L, (sh + 1) * 500L - 150L)
                val eh = quietHop(hopDb, c.e * 2 - 3, c.e * 2 + 5)
                if (eh >= 0) endMs = min(durationMs, eh * 500L + 250L)
            }
            if (endMs - startMs > maxLen * 1000L || endMs - startMs < minLen * 1000L) {
                startMs = c.a * 1000L
                endMs = min(durationMs, c.e * 1000L)
            }
            if (used.any { startMs < it[1] && endMs > it[0] }) continue
            used.add(longArrayOf(startMs, endMs))

            // what drove this clip?
            val parts = FloatArray(9)
            val src = arrayOf(energy, riseN, excN, hitN, visN, empTop, revN, reaTop, txtN)
            for (s in c.a until c.e) for (k in parts.indices) parts[k] += w[k] * src[k][s]
            var bi = 0
            for (k in parts.indices) if (parts[k] > parts[bi]) bi = k
            val category = when {
                parts[0] > 0 && parts[4] > 0 && energy.slice(c.a until c.e).average() > 0.6 && visN.slice(c.a until c.e).average() > 0.5 -> "High-energy scene"
                else -> when (bi) {
                    0 -> "Loud moment"
                    1 -> "Sudden build-up"
                    2 -> "Shout / hype"
                    3 -> "Big hit"
                    4 -> "Visual spike"
                    5 -> "Emphatic talk"
                    6 -> "Big reveal"
                    7 -> "Crowd / laugh reaction"
                    else -> "Hook line"
                }
            }
            results.add(
                DetectedClip(
                    startMs = startMs,
                    endMs = endMs,
                    score = (c.total * 125f).roundToInt().coerceIn(1, 99),
                    category = category,
                    title = if (tx != null) {
                        val preview = tx.words.filter { it.startMs >= startMs && it.startMs < endMs }.take(7).joinToString(" ") { it.text }
                        if (preview.isBlank()) "$category at ${stamp((startMs / 1000).toInt())}" else "$category: \u201c$preview\u2026\u201d"
                    } else "$category at ${stamp((startMs / 1000).toInt())}"
                )
            )
        }
        if (results.isEmpty()) {
            throw IllegalStateException("No standout moments were found. The video may be too flat (constant volume, no visual changes).")
        }
        return results.sortedByDescending { it.score }
    }

    private val weakEnd = setOf(
        "and", "but", "so", "because", "the", "a", "an", "to", "of", "that", "which", "or", "if", "with", "for",
        "in", "on", "at", "as", "than", "then", "my", "your", "his", "her", "their", "our", "is", "are", "was", "i"
    )
    private val weakStart = setOf("and", "but", "so", "because", "which", "then", "also", "or", "though", "cause")

    /** 1 = starts a fresh thought after a pause, lower = continues the previous sentence. */
    private fun startQuality(ss: List<Sentence>, i: Int): Float {
        val gap = if (i > 0) ss[i].startMs - ss[i - 1].endMs else 1500L
        var q = min(gap, 1500L) / 1500f
        if (ss[i].words.first().text in weakStart) q -= 0.5f
        return q
    }

    /** 1 = a finished thought followed by a pause; lower = trails off into the next words. */
    private fun endQuality(ss: List<Sentence>, i: Int): Float {
        val gap = if (i < ss.size - 1) ss[i + 1].startMs - ss[i].endMs else 1500L
        var q = min(gap, 1500L) / 1500f
        if (ss[i].words.last().text in weakEnd) q -= 0.6f
        return q
    }

    private fun sentenceInterest(s: Sentence, interest: FloatArray): Float {
        val from = (s.startMs / 1000).toInt().coerceIn(0, interest.size - 1)
        val to = (s.endMs / 1000).toInt().coerceIn(from, interest.size - 1)
        var sum = 0f
        for (k in from..to) sum += interest[k]
        return sum / (to - from + 1)
    }

    /**
     * Picks the start and end sentences for a clip near [a0, e0]: starts where a new thought begins,
     * ends where one is finished, never inside a sentence, drops a weak "cold open" sentence if the clip
     * is stronger without it, and always respects the length limits. Returns null if no sentences fit.
     */
    private fun snapToSentences(
        ss: List<Sentence>, interest: FloatArray, a0: Long, e0: Long, minMs: Long, maxMs: Long, durationMs: Long
    ): LongArray? {
        if (ss.isEmpty()) return null
        var si = -1
        var bestS = -9f
        for (i in ss.indices) {
            val d = abs(ss[i].startMs - a0)
            if (d > 3000) continue
            val sc = startQuality(ss, i) - d / 3000f * 0.6f
            if (sc > bestS) { bestS = sc; si = i }
        }
        if (si < 0) return null

        fun pickEnd(from: Int, strict: Boolean): Int {
            var best = -1
            var bestSc = -9f
            for (j in from until ss.size) {
                val len = ss[j].endMs - ss[from].startMs + 500
                if (len > maxMs) break
                if (len < minMs) continue
                val d = ss[j].endMs - e0
                if (strict && (d < -3000 || d > 4500)) continue
                val sc = endQuality(ss, j) - abs(d) / 4500f * 0.5f
                if (sc > bestSc) { bestSc = sc; best = j }
            }
            return best
        }
        var ei = pickEnd(si, true)
        if (ei < 0) ei = pickEnd(si, false)
        if (ei < 0) return null

        // cold open: drop up to two weak lead-in sentences if the rest is clearly stronger and still long enough
        var clipMean = 0f
        for (k in si..ei) clipMean += sentenceInterest(ss[k], interest)
        clipMean /= (ei - si + 1)
        var trims = 0
        while (trims < 2 && si + 1 < ei &&
            ss[ei].endMs - ss[si + 1].startMs + 500 >= minMs &&
            sentenceInterest(ss[si], interest) < 0.65f * clipMean
        ) { si++; trims++ }

        return longArrayOf(max(0L, ss[si].startMs - 200L), min(durationMs, ss[ei].endMs + 300L))
    }

    /**
     * The half-second whose loudness is lowest inside [lo, hi] (a pause), or -1 if there is no real dip there
     * (it must be clearly quieter than its surroundings, otherwise the original cut point is kept).
     */
    private fun quietHop(db: FloatArray, lo: Int, hi: Int): Int {
        if (db.isEmpty()) return -1
        val a = lo.coerceIn(0, db.size - 1)
        val b = hi.coerceIn(a, db.size - 1)
        var best = a
        var sum = 0f
        for (i in a..b) { sum += db[i]; if (db[i] < db[best]) best = i }
        val avg = sum / (b - a + 1)
        return if (db[best] < avg - 4f) best else -1
    }

    /** Median of a[from until to] (the middle value; no averaging needed for this use). */
    private fun median(a: FloatArray, from: Int, to: Int): Float {
        if (to <= from) return a[from.coerceIn(0, a.size - 1)]
        val tmp = a.copyOfRange(from, to)
        tmp.sort()
        return tmp[tmp.size / 2]
    }

    private fun stamp(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    /** Index of the lowest-loudness second in [lo, hi]; on ties prefers the late or early side. */
    private fun quietest(db: FloatArray, lo: Int, hi: Int, preferLate: Boolean): Int {
        val a = lo.coerceIn(0, db.size - 1)
        val b = hi.coerceIn(a, db.size - 1)
        var best = a
        for (i in a..b) {
            val better = if (preferLate) db[i] <= db[best] else db[i] < db[best]
            if (better) best = i
        }
        return best
    }

    private fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sortedArray()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    /** Maps the 10th..95th percentile range to 0..1. */
    private fun normalizeRange(v: FloatArray): FloatArray {
        val lo = percentile(v, 0.10f)
        val hi = percentile(v, 0.95f)
        if (hi - lo < 1e-3f) return FloatArray(v.size)
        return FloatArray(v.size) { ((v[it] - lo) / (hi - lo)).coerceIn(0f, 1f) }
    }

    /** Scales so the 95th percentile becomes 1.0. */
    private fun normalizeTop(v: FloatArray): FloatArray {
        val hi = percentile(v, 0.95f)
        if (hi < 1e-4f) return FloatArray(v.size)
        return FloatArray(v.size) { (v[it] / hi).coerceIn(0f, 1f) }
    }
}
