package com.clipforge.app.engine

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

class Word(val startMs: Long, val endMs: Long, val text: String, val conf: Float)
class Sentence(val startMs: Long, val endMs: Long, val words: List<Word>)

/** Words with timings, from the on-device speech recogniser. */
class Transcript(val words: List<Word>, durationMs: Long) {
    /** False when there is too little speech, or the recogniser was mostly guessing (music, another language). */
    val usable: Boolean =
        words.size >= 20 && words.size >= durationMs / 12000 && words.map { it.conf }.average() >= 0.55

    /** Sentences: a pause of 0.6 s or more (or 25 words) ends one. */
    val sentences: List<Sentence> by lazy {
        val out = ArrayList<Sentence>()
        var cur = ArrayList<Word>()
        for (w in words) {
            if (cur.isNotEmpty() && (w.startMs - cur.last().endMs >= 600 || cur.size >= 25)) {
                out.add(Sentence(cur.first().startMs, cur.last().endMs, cur)); cur = ArrayList()
            }
            cur.add(w)
        }
        if (cur.isNotEmpty()) out.add(Sentence(cur.first().startMs, cur.last().endMs, cur))
        out
    }
}

/** Downloads (once, ~40 MB) and unpacks the small English speech model into the app's private storage. */
object VoskModel {
    private const val NAME = "vosk-model-small-en-us-0.15"
    private const val LINK = "https://alphacephei.com/vosk/models/$NAME.zip"

    /** Returns the model folder, or null if it could not be downloaded (offline, blocked...). */
    suspend fun ensure(ctx: Context, onDownload: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
        val dir = File(root, NAME)
        if (File(dir, "am/final.mdl").exists()) return@withContext dir
        val zip = File(root, "model.zip")
        try {
            dir.deleteRecursively()
            val conn = URL(LINK).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.connect()
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onDownload((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val rootPath = root.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    ensureActive()
                    val e = zin.nextEntry ?: break
                    val target = File(root, e.name)
                    if (!target.canonicalPath.startsWith(rootPath)) continue   // never write outside the folder
                    if (e.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
            zip.delete()
            if (File(dir, "am/final.mdl").exists()) dir else null
        } catch (e: CancellationException) {
            zip.delete(); dir.deleteRecursively()
            throw e
        } catch (e: Throwable) {
            zip.delete(); dir.deleteRecursively()
            null
        }
    }
}

object Transcriber {
    private const val RATE = 16000

    /** Decodes the audio track, converts it to 16 kHz mono and runs the recogniser over it. */
    suspend fun transcribe(modelDir: File, path: String, durationMs: Long, onProgress: (Float) -> Unit): Transcript =
        withContext(Dispatchers.IO) {
            val words = ArrayList<Word>()
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var model: Model? = null
            var rec: Recognizer? = null
            try {
                extractor.setDataSource(path)
                var track = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
                }
                if (track < 0 || format == null) return@withContext Transcript(words, durationMs)

                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = Model(modelDir.path)
                model = m
                val recognizer = Recognizer(m, RATE.toFloat())
                rec = recognizer
                recognizer.setWords(true)

                extractor.selectTrack(track)
                val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = c
                c.configure(format, null, null, 0)
                c.start()

                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                var floatPcm = false

                val chunk = ShortArray(4000)
                var chunkN = 0
                var acc = 0.0
                var accN = 0
                var pos = 0.0

                fun feed() {
                    if (chunkN == 0) return
                    if (recognizer.acceptWaveForm(chunk, chunkN)) collect(recognizer.result, words)
                    chunkN = 0
                }

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    ensureActive()
                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = c.getInputBuffer(inIdx)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx >= 0 -> {
                            val out = c.getOutputBuffer(outIdx)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            out.order(ByteOrder.nativeOrder())
                            val fb = if (floatPcm) out.asFloatBuffer() else null
                            val sb = if (!floatPcm) out.asShortBuffer() else null
                            val avail = (if (fb != null) fb.remaining() else sb!!.remaining()) / channels
                            val ratio = rate.toDouble() / RATE
                            for (k in 0 until avail) {
                                var mono = 0.0
                                for (ch in 0 until channels) mono += if (fb != null) fb.get().toDouble() else sb!!.get() / 32768.0
                                mono /= channels
                                acc += mono; accN++; pos += 1.0
                                if (pos >= ratio) {
                                    pos -= ratio
                                    chunk[chunkN++] = (acc / accN * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
                                    acc = 0.0; accN = 0
                                    if (chunkN == chunk.size) feed()
                                }
                            }
                            if (durationMs > 0) onProgress((info.presentationTimeUs / 1000f / durationMs).coerceIn(0f, 1f))
                            c.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = c.outputFormat
                            rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            floatPcm = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                }
                feed()
                collect(recognizer.finalResult, words)
                onProgress(1f)
                Transcript(words, durationMs)
            } finally {
                try { rec?.close() } catch (_: Throwable) {}
                try { model?.close() } catch (_: Throwable) {}
                try { codec?.stop() } catch (_: Throwable) {}
                try { codec?.release() } catch (_: Throwable) {}
                extractor.release()
            }
        }

    private fun collect(json: String, out: MutableList<Word>) {
        try {
            val arr = JSONObject(json).optJSONArray("result") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Word(
                        (o.getDouble("start") * 1000).toLong(), (o.getDouble("end") * 1000).toLong(),
                        o.getString("word").lowercase(), o.optDouble("conf", 1.0).toFloat()
                    )
                )
            }
        } catch (_: Throwable) {
        }
    }
}

class TextSeries(val hook: FloatArray, val filler: FloatArray, val dead: FloatArray)

/** Turns words into per-second signals: hook lines, filler talk and dead air. */
object TextScore {
    private val hookWords = setOf(
        "secret", "nobody", "never", "biggest", "worst", "best", "mistake", "truth", "actually", "crazy", "insane",
        "wait", "stop", "reason", "problem", "lesson", "million", "billion", "free", "rich", "money", "dangerous",
        "wrong", "only", "everything", "nothing", "always", "impossible", "hack", "trick", "story", "happened",
        "suddenly", "finally", "realized", "discovered", "changed", "illegal", "banned", "scam", "truth", "shocking",
        "fuck", "fucking"
    )
    // Two-word hooks: counted when the words come one right after the other.
    private val hookPhrases = setOf("no" to "way")
    private val emoWords = setOf(
        "love", "hate", "afraid", "scared", "fear", "die", "died", "dead", "kill", "fight", "amazing", "incredible",
        "unbelievable", "wow", "god", "crying", "cried", "laughed", "angry", "furious", "hurt", "proud", "sorry",
        "beautiful", "terrible", "horrible", "worried", "regret", "dream", "miracle"
    )
    private val questionStart = setOf(
        "what", "why", "how", "who", "when", "where", "did", "do", "does", "can", "could", "would", "should",
        "have", "is", "are", "imagine", "ever", "ask"
    )
    private val fillers = setOf("um", "uh", "uhm", "er", "hmm", "erm")

    fun sentenceScore(s: Sentence): Float {
        if (s.words.size < 4) return 0f
        var hooks = 0
        var emo = 0
        for (w in s.words) { if (w.text in hookWords) hooks++; if (w.text in emoWords) emo++ }
        for (i in 0 until s.words.size - 1) if ((s.words[i].text to s.words[i + 1].text) in hookPhrases) hooks++
        val question = if (s.words[0].text in questionStart && s.words.size >= 5) 1f else 0f
        return (0.35f * min(1f, hooks / 2f) + 0.30f * question + 0.25f * min(1f, emo / 2f) + 0.10f * min(1f, s.words.size / 12f))
            .coerceIn(0f, 1f)
    }

    fun series(t: Transcript, n: Int): TextSeries {
        val hook = FloatArray(n)
        for (s in t.sentences) {
            val sc = sentenceScore(s)
            if (sc <= 0f) continue
            val from = (s.startMs / 1000).toInt().coerceIn(0, n - 1)
            val to = (s.endMs / 1000).toInt().coerceIn(from, n - 1)
            for (k in from..to) hook[k] = max(hook[k], sc)
        }
        val wc = IntArray(n)
        val fc = IntArray(n)
        for (w in t.words) {
            val k = (w.startMs / 1000).toInt()
            if (k in 0 until n) { wc[k]++; if (w.text in fillers) fc[k]++ }
        }
        val filler = FloatArray(n)
        val dead = FloatArray(n)
        for (s in 0 until n) {
            var words = 0
            var fills = 0
            for (k in max(0, s - 5)..min(n - 1, s + 5)) { words += wc[k]; fills += fc[k] }
            filler[s] = if (words > 0) fills.toFloat() / words else 0f
            var near = 0
            for (k in max(0, s - 3)..min(n - 1, s + 3)) near += wc[k]
            dead[s] = if (near == 0) 1f else 0f
        }
        return TextSeries(hook, filler, dead)
    }
}
