package com.clipforge.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class YtInfo(val title: String, val durationMs: Long, val thumbUrl: String?)

/**
 * YouTube support through yt-dlp (youtubedl-android). Only for videos you own or have permission to save.
 * No DRM or access restrictions are bypassed: private/age-gated/restricted videos simply fail with an error.
 */
object YouTubeFetcher {
    private val urlRegex = Regex("^https://(www\\.|m\\.)?(youtube\\.com|youtu\\.be)/.+", RegexOption.IGNORE_CASE)
    private var ready = false

    private val instaRegex = Regex("^https://(www\\.)?(instagram\\.com|instagr\\.am)/(reel|reels|p|tv)/.+", RegexOption.IGNORE_CASE)

    fun isValid(url: String) = urlRegex.matches(url.trim())
    fun isInstagram(url: String) = instaRegex.matches(url.trim())

    private fun ensureReady(ctx: Context) {
        if (!ready) {
            YoutubeDL.getInstance().init(ctx.applicationContext)
            try { FFmpeg.getInstance().init(ctx.applicationContext) } catch (_: Throwable) { }
            ready = true
        }
    }

    suspend fun info(ctx: Context, url: String): YtInfo = withContext(Dispatchers.IO) {
        ensureReady(ctx)
        val i = YoutubeDL.getInstance().getInfo(url)
        YtInfo(
            title = i.title ?: "YouTube video",
            durationMs = i.duration.toLong() * 1000L,
            thumbUrl = i.thumbnail
        )
    }

    suspend fun thumbnail(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return@withContext null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(ctx: Context, url: String, dir: File, highQuality: Boolean = false, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            ensureReady(ctx)
            dir.mkdirs()
            val pid = "dl-${System.nanoTime()}"
            val request = YoutubeDLRequest(url)
            request.addOption("-o", File(dir, "video.%(ext)s").absolutePath)
            if (highQuality) {
                // best picture up to 1080p + best audio, merged into one mp4 (needs ffmpeg); falls back to a ready-made mp4
                request.addOption("-f", "bv*[ext=mp4][height<=1080]+ba[ext=m4a]/b[ext=mp4]/bv*[height<=1080]+ba/b")
                request.addOption("--merge-output-format", "mp4")
            } else {
                request.addOption("-f", "best[ext=mp4]/best")
            }
            request.addOption("--no-playlist")
            coroutineScope {
                // If this coroutine is cancelled, stop the yt-dlp process.
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        YoutubeDL.getInstance().destroyProcessById(pid)
                    }
                }
                try {
                    try {
                        YoutubeDL.getInstance().execute(request, pid) { progress, _, _ ->
                            onProgress((progress / 100f).coerceIn(0f, 1f))
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Sites change often: update the downloader engine once and retry.
                        try { YoutubeDL.getInstance().updateYoutubeDL(ctx.applicationContext) } catch (_: Throwable) { }
                        YoutubeDL.getInstance().execute(request, pid) { progress, _, _ ->
                            onProgress((progress / 100f).coerceIn(0f, 1f))
                        }
                    }
                } finally {
                    watcher.cancel()
                }
            }
            dir.listFiles()
                ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("The download finished but no video file was found.")
        }
}
