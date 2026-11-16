package com.clipforge.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.clipforge.app.engine.AspectMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun dateText(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

/** Gradient primary button. */
@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) AccentBrush else androidx.compose.ui.graphics.SolidColor(GlassFill))
            .border(1.dp, androidx.compose.ui.graphics.Color(0x40FFFFFF), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) androidx.compose.ui.graphics.Color.White else TextSecondary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0x33FFFFFF)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = GlassFillStrong,
            contentColor = if (danger) Danger else TextPrimary,
            disabledContainerColor = GlassFill,
            disabledContentColor = TextSecondary
        )
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Accent) {
    Text(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium
    )
}

/** Loads an image file off the main thread. Shows a plain placeholder if it is missing. */
@Composable
fun FileImage(path: String?, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            if (File(path).exists()) BitmapFactory.decodeFile(path) else null
        }
    }
    Box(modifier.background(Bg2)) {
        bmp?.let {
            Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Real Media3 playback. [aspect] fits the whole picture inside the frame so you can preview 9:16 / 1:1 / 16:9 exports. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    aspect: AspectMode = AspectMode.ORIGINAL,
    autoPlay: Boolean = false,
    loop: Boolean = false,
    cornerRadius: Dp = 18.dp
) {
    val ctx = LocalContext.current
    val player = remember(uri, aspect) {
        ExoPlayer.Builder(ctx).build().apply {
            if (aspect.w > 0) {
                setVideoEffects(listOf(Presentation.createForWidthAndHeight(aspect.w, aspect.h, Presentation.LAYOUT_SCALE_TO_FIT)))
            }
            setMediaItem(MediaItem.fromUri(uri))
            if (loop) repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = autoPlay
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Lets a screen pause a player that scrolled off-screen and start the one that scrolled in.
    LaunchedEffect(player, autoPlay) { player.playWhenReady = autoPlay }
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)).background(androidx.compose.ui.graphics.Color.Black),
        factory = { c -> PlayerView(c).apply { useController = true; this.player = player } },
        update = { it.player = player }
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = TextSecondary, fontSize = 13.sp, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun BackBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "←",
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp),
            color = TextPrimary,
            fontSize = 22.sp
        )
        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
