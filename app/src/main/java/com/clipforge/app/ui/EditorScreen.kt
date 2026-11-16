@file:OptIn(ExperimentalMaterial3Api::class)

package com.clipforge.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.clipforge.app.AppViewModel
import com.clipforge.app.engine.AspectMode
import com.clipforge.app.engine.CAPTION_STYLES
import com.clipforge.app.engine.activeCaption
import com.clipforge.app.engine.captionLinesFor
import com.clipforge.app.engine.locateOut
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun EditorScreen(vm: AppViewModel, nav: NavController) {
    val m = vm.editor
    if (m == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val ctx = LocalContext.current
    BackHandler { nav.popBackStack() }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var outPos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }

    val segSnap = m.segs.toList()
    val total = segSnap.sumOf { it.outMs }
    val sel = segSnap.getOrNull(m.selected)

    // Rebuild the preview whenever a cut / trim / speed changes, keeping the playhead where it was.
    LaunchedEffect(segSnap) {
        val items = segSnap.map { s ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(m.sourcePath)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s.startMs).setEndPositionMs(s.endMs).build()
                )
                .build()
        }
        val (i, p) = locateOut(segSnap, outPos.coerceAtMost(max(0L, total - 1)))
        player.setMediaItems(items, i, p)
        player.prepare()
    }
    // Playhead + per-piece speed and volume in the preview.
    LaunchedEffect(player) {
        var lastSeg: com.clipforge.app.engine.Seg? = null
        while (true) {
            delay(80)
            val segs = m.segs.toList()
            val idx = player.currentMediaItemIndex
            val seg = segs.getOrNull(idx)
            if (seg != null) {
                if (seg != lastSeg) {
                    player.playbackParameters = PlaybackParameters(seg.speed)
                    player.volume = seg.volume.coerceIn(0f, 1f)
                    lastSeg = seg
                }
                var off = 0L
                for (k in 0 until idx) off += segs[k].outMs
                outPos = off + (player.currentPosition / seg.speed).toLong()
            }
            playing = player.isPlaying
        }
    }

    fun seekOut(t: Long) {
        val (i, p) = locateOut(m.segs.toList(), t.coerceIn(0L, total))
        player.seekTo(i, p)
        outPos = t.coerceIn(0L, total)
    }

    val ratio = m.canvas.ratio ?: (if (m.heightPx > 0) m.widthPx.toFloat() / m.heightPx else 16f / 9f)

    Column(Modifier.fillMaxSize().appBackdrop().systemBarsPadding().padding(horizontal = 12.dp)) {
        // ---- top bar
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✕",
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { nav.popBackStack() }.padding(horizontal = 12.dp, vertical = 8.dp),
                color = TextPrimary, fontSize = 20.sp
            )
            Text(m.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            GradientButton("Export", Modifier.width(110.dp)) { player.pause(); vm.exportEdit(m) }
        }

        // ---- preview: the whole picture is fitted inside the canvas, exactly like the export
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            BoxWithConstraints(
                Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(10.dp)).background(Color.Black)
            ) {
                val fs = maxWidth.value
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c -> PlayerView(c).apply { useController = false; this.player = player } },
                    update = { it.player = player }
                )
                // text on the picture
                for (t in m.texts) {
                    if (outPos >= t.startMs && outPos < t.endMs) {
                        Box(
                            Modifier.fillMaxSize().padding(top = maxHeight * 0.08f, bottom = maxHeight * 0.08f),
                            contentAlignment = when (t.pos) { 0 -> Alignment.TopCenter; 1 -> Alignment.Center; else -> Alignment.BottomCenter }
                        ) {
                            Text(
                                t.text, color = Color(t.color), fontSize = (fs * 0.07f).sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(Color(0x80000000)).padding(horizontal = 6.dp)
                            )
                        }
                    }
                }
                // captions
                if (m.captionsOn && m.words.isNotEmpty()) {
                    val style = CAPTION_STYLES[m.style.coerceIn(0, CAPTION_STYLES.lastIndex)]
                    val lines = remember(m.words, segSnap, m.style, m.canvas) { captionLinesFor(m, if (m.canvas.w > 0) m.canvas.w else max(m.widthPx, 480)) }
                    activeCaption(lines, outPos)?.let { (line, cur) ->
                        Box(Modifier.fillMaxSize().padding(bottom = maxHeight * 0.14f), contentAlignment = Alignment.BottomCenter) {
                            val txt = buildAnnotatedString {
                                line.words.forEachIndexed { i, w ->
                                    if (i > 0) append(" ")
                                    val word = if (style.upper) w.text.uppercase() else w.text
                                    if (i == cur && style.highlight != null) withStyle(SpanStyle(color = Color(style.highlight!!))) { append(word) }
                                    else append(word)
                                }
                            }
                            Text(
                                txt, color = Color.White, fontSize = (fs * 0.058f * style.sizeMul).sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(Color(0xB3000000)).padding(horizontal = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---- transport
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (playing) "⏸" else "▶",
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    if (player.isPlaying) player.pause() else {
                        if (outPos >= total - 100) seekOut(0)
                        player.play()
                    }
                }.padding(horizontal = 14.dp, vertical = 6.dp),
                color = TextPrimary, fontSize = 22.sp
            )
            Text("${clock(outPos)} / ${clock(total)}", color = TextSecondary, fontSize = 12.sp)
            Slider(
                value = outPos.toFloat().coerceIn(0f, max(1f, total.toFloat())),
                onValueChange = { seekOut(it.toLong()) },
                valueRange = 0f..max(1f, total.toFloat()),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }

        // ---- timeline: one block per piece, tap to select
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).glass(12.dp)) {
            Row(Modifier.fillMaxSize().padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                segSnap.forEachIndexed { i, s ->
                    Box(
                        Modifier.weight(max(1L, s.outMs).toFloat()).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .background(if (i == m.selected) Accent.copy(alpha = 0.85f) else GlassFillStrong)
                            .clickable { m.selected = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            clock(s.outMs) + if (s.speed != 1f) "  ${s.speed}x" else "",
                            color = TextPrimary, fontSize = 11.sp, maxLines = 1
                        )
                    }
                }
            }
            val frac = if (total > 0) outPos.toFloat() / total else 0f
            Box(Modifier.offset(x = maxWidth * frac.coerceIn(0f, 1f)).width(2.dp).fillMaxHeight().background(Color.White))
        }

        // ---- panels
        Spacer(Modifier.height(8.dp))
        if (panel.isNotEmpty() && sel != null) {
            Column(Modifier.fillMaxWidth().glass(18.dp).padding(12.dp)) {
                when (panel) {
                    "trim" -> {
                        Muted("Trim the selected piece")
                        var r by remember(sel.id, sel.startMs, sel.endMs) {
                            mutableStateOf(sel.startMs.toFloat()..sel.endMs.toFloat())
                        }
                        RangeSlider(
                            value = r,
                            onValueChange = { r = it },
                            valueRange = 0f..m.durationMs.toFloat(),
                            onValueChangeFinished = {
                                val a = r.start.toLong()
                                val b = r.endInclusive.toLong()
                                if (b - a >= 500) m.updateSelected { it.copy(startMs = a, endMs = b) }
                            }
                        )
                        Muted("${clock(r.start.toLong())} to ${clock(r.endInclusive.toLong())}")
                    }
                    "speed" -> {
                        Muted("Speed of the selected piece")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f).forEach { sp ->
                                FilterChip(selected = sel.speed == sp, onClick = { m.updateSelected { it.copy(speed = sp) } }, label = { Text("${sp}x") })
                            }
                        }
                    }
                    "volume" -> {
                        Muted("Volume of the selected piece (0 = muted)")
                        var v by remember(sel.id, sel.volume) { mutableFloatStateOf(sel.volume) }
                        Slider(
                            value = v, onValueChange = { v = it }, valueRange = 0f..2f,
                            onValueChangeFinished = { m.updateSelected { it.copy(volume = v) } }
                        )
                        Muted("${(v * 100).toInt()}%  (the preview plays up to 100%)")
                    }
                    "canvas" -> {
                        Muted("Frame shape. The whole video stays visible; bars fill the empty space.")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AspectMode.entries.forEach { a ->
                                FilterChip(selected = m.canvas == a, onClick = { m.canvas = a }, label = { Text(a.label) })
                            }
                        }
                    }
                    "text" -> TextPanel(m, outPos)
                    "captions" -> CaptionPanel(vm, m)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- tools
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolChip("Split", false) { m.splitAt(outPos) }
            ToolChip("Delete", false) { m.deleteSelected() }
            ToolChip("Trim", panel == "trim") { panel = if (panel == "trim") "" else "trim" }
            ToolChip("Speed", panel == "speed") { panel = if (panel == "speed") "" else "speed" }
            ToolChip("Volume", panel == "volume") { panel = if (panel == "volume") "" else "volume" }
            ToolChip("◀ Move", false) { m.move(-1) }
            ToolChip("Move ▶", false) { m.move(1) }
            ToolChip("Canvas", panel == "canvas") { panel = if (panel == "canvas") "" else "canvas" }
            ToolChip("Text", panel == "text") { panel = if (panel == "text") "" else "text" }
            ToolChip("Captions", panel == "captions") { panel = if (panel == "captions") "" else "captions" }
        }
    }

    // ---- export progress / result
    if (m.exportBusy || m.exportDone || m.exportError != null) {
        AlertDialog(
            onDismissRequest = { if (!m.exportBusy) { m.exportDone = false; m.exportError = null } },
            containerColor = Bg2,
            title = { Text(if (m.exportBusy) "Rendering your video…" else if (m.exportDone) "Saved to your gallery" else "Export failed", color = TextPrimary) },
            text = {
                Column {
                    when {
                        m.exportBusy -> {
                            LinearProgressIndicator(progress = { m.exportProgress }, Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
                            Spacer(Modifier.height(8.dp))
                            Muted("${(m.exportProgress * 100).toInt()}%  It keeps going if you leave the app.")
                        }
                        m.exportDone -> Muted("Find it in your gallery under Movies/ClipForge.")
                        else -> Text(m.exportError ?: "", color = Danger, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                if (m.exportBusy) TextButton(onClick = { vm.cancelEditExport(m) }) { Text("Cancel", color = Danger) }
                else TextButton(onClick = { m.exportDone = false; m.exportError = null }) { Text("OK", color = Accent) }
            }
        )
    }
}

@Composable
private fun ToolChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.glass(14.dp, strong = active).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (active) Accent2 else TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TextPanel(m: com.clipforge.app.engine.EditorModel, outPos: Long) {
    var text by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(2) }
    var color by remember { mutableStateOf(0xFFFFFFFF.toInt()) }
    Muted("Adds text at the playhead for 3 seconds")
    OutlinedTextField(
        value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("Type your text", color = TextSecondary) },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Color(0x40FFFFFF),
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent
        )
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Top", "Middle", "Bottom").forEachIndexed { i, l -> FilterChip(selected = pos == i, onClick = { pos = i }, label = { Text(l) }) }
        listOf("White" to 0xFFFFFFFF.toInt(), "Yellow" to 0xFFFFD60A.toInt(), "Blue" to 0xFF64D2FF.toInt()).forEach { (l, c) ->
            FilterChip(selected = color == c, onClick = { color = c }, label = { Text(l) })
        }
    }
    Spacer(Modifier.height(8.dp))
    SoftButton("Add text", Modifier.fillMaxWidth(), enabled = text.isNotBlank()) {
        m.addText(text.trim(), outPos, pos, color)
        text = ""
    }
    m.texts.toList().forEach { t ->
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${clock(t.startMs)}  ${t.text}", color = TextPrimary, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("✕", color = Danger, modifier = Modifier.clickable { m.texts.remove(t) }.padding(8.dp))
        }
    }
}

@Composable
private fun CaptionPanel(vm: AppViewModel, m: com.clipforge.app.engine.EditorModel) {
    val busy = m.capBusy
    when {
        busy != null -> {
            Muted(busy)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
        }
        m.words.isEmpty() -> {
            Muted("Creates captions from the speech in your video. Works offline; the first time it downloads a ~40 MB speech model. English only.")
            Spacer(Modifier.height(8.dp))
            GradientButton("Generate captions", Modifier.fillMaxWidth()) { vm.generateCaptions(m) }
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show captions", color = TextPrimary, modifier = Modifier.weight(1f))
                Switch(checked = m.captionsOn, onCheckedChange = { m.captionsOn = it })
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CAPTION_STYLES.forEachIndexed { i, s ->
                    FilterChip(selected = m.style == i, onClick = { m.style = i }, label = { Text(s.name) })
                }
            }
            Spacer(Modifier.height(6.dp))
            SoftButton("Regenerate", Modifier.fillMaxWidth()) { vm.generateCaptions(m) }
        }
    }
    m.capError?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Danger, fontSize = 13.sp) }
}
