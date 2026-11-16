package com.clipforge.app.engine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Per-0.5 s measurements of the real audio track (all arrays have the same length):
 *  - rms:    loudness (0..1)
 *  - peak:   loudest single sample (0..1); a peak far above the rms means a sharp hit (impact, gunshot, explosion)
 *  - bright: how much of the sound is high-frequency (0..~1.4); shouting and excitement are brighter than
 *            game music or a calm voice
 */
class AudioSignal(
    val rms: FloatArray,
    val peak: FloatArray,
    val bright: FloatArray,
    /** share of the sound energy in the human-voice band (300-3400 Hz), 0..1 */
    val voice: FloatArray = FloatArray(0),
    /** spectral flatness 0..1: near 1 for noise-like sound (applause, laughter, crowd), low for tones and voices */
    val flat: FloatArray = FloatArray(0),
    /** how fast the sound spectrum is changing (new words, new sounds) */
    val flux: FloatArray = FloatArray(0),
    /** loudness modulation inside the window: speech rises and falls ~4x per second, steady music does not */
    val mod: FloatArray = FloatArray(0)
) {
    val size: Int get() = rms.size
    fun isEmpty(): Boolean = rms.isEmpty()
    val hasSpectral: Boolean get() = voice.size == rms.size && rms.isNotEmpty()

    companion object {
        val EMPTY = AudioSignal(FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Decodes the real audio track of the video and measures loudness, peaks and brightness in 0.5 s windows.
 * Returns an empty signal when the video has no audio track.
 */
object AudioAnalyzer {
    const val HOP_MS = 500
    private const val N = 1024          // FFT frame size
    private const val FRAMES_PER_HOP = 4
    private const val SUBS = 8          // loudness sub-blocks per hop (for modulation)

    private val cosT = FloatArray(N / 2) { Math.cos(2.0 * Math.PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { -Math.sin(2.0 * Math.PI * it / N).toFloat() }
    private val hann = FloatArray(N) { (0.5 - 0.5 * Math.cos(2.0 * Math.PI * it / (N - 1))).toFloat() }

    /** In-place radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= N) {
            val half = len / 2
            val step = N / len
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }

    suspend fun analyze(path: String, durationMs: Long, onProgress: (Float) -> Unit): AudioSignal =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(path)
                var track = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        track = i
                        format = f
                        break
                    }
                }
                if (track < 0 || format == null) return@withContext AudioSignal.EMPTY

                extractor.selectTrack(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                val c = MediaCodec.createDecoderByType(mime)
                codec = c
                c.configure(format, null, null, 0)
                c.start()

                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                var windowFrames = (rate.toLong() * HOP_MS / 1000).toInt().coerceAtLeast(1)
                var floatPcm = false

                val rmsList = ArrayList<Float>()
                val peakList = ArrayList<Float>()
                val brightList = ArrayList<Float>()
                val voiceList = ArrayList<Float>()
                val flatList = ArrayList<Float>()
                val fluxList = ArrayList<Float>()
                val modList = ArrayList<Float>()
                val fre = FloatArray(N)
                val fim = FloatArray(N)
                val frame = FloatArray(N)
                var capture = 0                 // samples still to collect for the current FFT frame
                var prevSpec: FloatArray? = null
                var voiceSum = 0f; var flatSum = 0f; var fluxSum = 0f; var specCnt = 0
                val subSq = DoubleArray(SUBS)
                var subFrames = 0
                var sumSq = 0.0
                var diffSq = 0.0
                var peak = 0.0
                var prev = 0.0
                var framesInWindow = 0

                fun closeWindow() {
                    val rms = sqrt(sumSq / framesInWindow)
                    rmsList.add(rms.toFloat())
                    peakList.add(peak.toFloat())
                    // Energy of the sample-to-sample difference compared with the signal energy:
                    // near 0 for deep/slow sound, higher for sharp, high-pitched sound.
                    brightList.add(sqrt(diffSq / (sumSq + 1e-12)).coerceIn(0.0, 2.0).toFloat())
                    voiceList.add(if (specCnt > 0) voiceSum / specCnt else 0f)
                    flatList.add(if (specCnt > 0) flatSum / specCnt else 0f)
                    fluxList.add(if (specCnt > 0) fluxSum / specCnt else 0f)
                    // modulation depth of loudness across the sub-blocks
                    var m = 0.0
                    for (x in subSq) m += x
                    m /= SUBS
                    var v = 0.0
                    for (x in subSq) v += (x - m) * (x - m)
                    modList.add((sqrt(v / SUBS) / (m + 1e-12)).coerceIn(0.0, 3.0).toFloat())
                    java.util.Arrays.fill(subSq, 0.0)
                    subFrames = 0
                    voiceSum = 0f; flatSum = 0f; fluxSum = 0f; specCnt = 0
                    sumSq = 0.0
                    diffSq = 0.0
                    peak = 0.0
                    framesInWindow = 0
                }

                fun analyseFrame() {
                    var tot = 0f
                    for (i in 0 until N) { fre[i] = frame[i] * hann[i]; fim[i] = 0f }
                    fft(fre, fim)
                    val binHz = rate.toFloat() / N
                    val lo = (80f / binHz).toInt().coerceAtLeast(1)
                    val hi = (8000f / binHz).toInt().coerceAtMost(N / 2 - 1)
                    if (hi <= lo) return
                    val spec = FloatArray(hi - lo + 1)
                    var voiceE = 0f
                    var logSum = 0f
                    var arith = 0f
                    val vLo = (300f / binHz).toInt()
                    val vHi = (3400f / binHz).toInt()
                    for (b in lo..hi) {
                        val pw = fre[b] * fre[b] + fim[b] * fim[b] + 1e-12f
                        spec[b - lo] = pw
                        tot += pw
                        if (b in vLo..vHi) voiceE += pw
                        logSum += Math.log(pw.toDouble()).toFloat()
                        arith += pw
                    }
                    if (tot < 1e-6f) return          // silence: nothing to measure
                    val cnt = spec.size
                    val geo = Math.exp((logSum / cnt).toDouble()).toFloat()
                    flatSum += (geo / (arith / cnt)).coerceIn(0f, 1f)
                    voiceSum += voiceE / tot
                    val pv = prevSpec
                    if (pv != null && pv.size == spec.size) {
                        var d = 0f
                        var pt = 0f
                        for (i in spec.indices) pt += pv[i]
                        for (i in spec.indices) d += abs(spec[i] / tot - pv[i] / (pt + 1e-12f))
                        fluxSum += (d / 2f).coerceIn(0f, 1f)
                    }
                    prevSpec = spec
                    specCnt++
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
                            for (k in 0 until avail) {
                                var mono = 0.0
                                for (ch in 0 until channels) {
                                    mono += if (fb != null) fb.get().toDouble() else sb!!.get() / 32768.0
                                }
                                mono /= channels
                                sumSq += mono * mono
                                if (subFrames < windowFrames) {
                                    val sb2 = (subFrames.toLong() * SUBS / windowFrames).toInt().coerceIn(0, SUBS - 1)
                                    subSq[sb2] += mono * mono
                                    subFrames++
                                }
                                if (capture == 0 && framesInWindow % (windowFrames / FRAMES_PER_HOP).coerceAtLeast(N) == 0) {
                                    capture = N
                                }
                                if (capture > 0) {
                                    frame[N - capture] = mono.toFloat()
                                    capture--
                                    if (capture == 0) analyseFrame()
                                }
                                val d = mono - prev
                                diffSq += d * d
                                prev = mono
                                val a = abs(mono)
                                if (a > peak) peak = a
                                framesInWindow++
                                if (framesInWindow >= windowFrames) closeWindow()
                            }
                            if (durationMs > 0) {
                                onProgress((info.presentationTimeUs / 1000f / durationMs).coerceIn(0f, 1f))
                            }
                            c.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val nf = c.outputFormat
                            rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            windowFrames = (rate.toLong() * HOP_MS / 1000).toInt().coerceAtLeast(1)
                            floatPcm = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                }
                if (framesInWindow > 0) closeWindow()
                onProgress(1f)
                AudioSignal(
                    rmsList.toFloatArray(), peakList.toFloatArray(), brightList.toFloatArray(),
                    voiceList.toFloatArray(), flatList.toFloatArray(), fluxList.toFloatArray(), modList.toFloatArray()
                )
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                extractor.release()
            }
        }
}
