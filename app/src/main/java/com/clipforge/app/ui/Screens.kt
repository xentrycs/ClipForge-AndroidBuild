package com.clipforge.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.clipforge.app.AnalysisUi
import com.clipforge.app.AppViewModel
import com.clipforge.app.ImportUi
import com.clipforge.app.data.ClipEntity
import com.clipforge.app.data.ProjectEntity
import com.clipforge.app.engine.AspectMode
import com.clipforge.app.engine.Quality
import com.clipforge.app.engine.ClipSettings
import java.io.File
import kotlin.math.roundToInt

// ============================================================ HOME

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, nav: NavController) {
    val imp by vm.importUi.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val counts by vm.clipCounts.collectAsStateWithLifecycle()
    val unexported by vm.unexported.collectAsStateWithLifecycle()
    val live by vm.analysisUi.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    // -1 = not chosen yet: open on "Continue" when something is waiting, otherwise on "New project".
    var tab by rememberSaveable { mutableStateOf(-1) }
    val continueItems = projects.filter { p ->
        when (p.status) {
            "ANALYZING", "FAILED" -> true
            "READY" -> (unexported[p.id] ?: 0) > 0
            else -> false
        }
    }
    val tabIndex = if (tab >= 0) tab else if (continueItems.isNotEmpty()) 1 else 0

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importLocal(uri)
    }

    val editPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.openEditorFromUri(uri) { nav.navigate("editor") }
    }

    LaunchedEffect(imp) {
        if (imp is ImportUi.Ready) nav.navigate("source")
    }

    Column(
        Modifier.fillMaxSize().appBackdrop().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).glass(12.dp, strong = true), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.clipforge.app.R.drawable.ic_logo), null, Modifier.size(24.dp))
            }
            Spacer(Modifier.size(10.dp))
            Text("ClipForge", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(18.dp))
        HomeTabs(tabIndex, continueItems.size) { tab = it; vm.notice.value = null }
        if (tabIndex == 1) {
            Spacer(Modifier.height(18.dp))
            ContinueTab(vm, nav, continueItems, counts, unexported, live, notice)
            return@Column
        }

        if (tabIndex == 2) {
            DownloadTab(vm)
            return@Column
        }

        Spacer(Modifier.height(24.dp))
        Text("Turn long videos into\nshort highlights.", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
        Spacer(Modifier.height(10.dp))
        Muted("Give ClipForge a video. It finds the moments most likely to hook people (reveals, emphatic talk, big reactions, action) and cuts each one into its own clip.")

        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth().glass(24.dp).padding(16.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a YouTube link", color = TextSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent, unfocusedBorderColor = Color(0x40FFFFFF),
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent
                )
            )
            Spacer(Modifier.height(12.dp))
            GradientButton("Get video from link", Modifier.fillMaxWidth(), enabled = imp !is ImportUi.Loading) { vm.fetchYoutube(url) }
            Spacer(Modifier.height(10.dp))
            Muted("Only use videos you own or have permission to save.")
        }

        Spacer(Modifier.height(14.dp))
        SoftButton("Choose a video from this phone", Modifier.fillMaxWidth(), enabled = imp !is ImportUi.Loading) {
            picker.launch(arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/webm", "video/*"))
        }
        Spacer(Modifier.height(10.dp))
        SoftButton("Open the video editor", Modifier.fillMaxWidth(), enabled = imp !is ImportUi.Loading) {
            editPicker.launch(arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/webm", "video/*"))
        }

        when (val s = imp) {
            is ImportUi.Loading -> {
                Spacer(Modifier.height(16.dp))
                Muted(s.message)
                Spacer(Modifier.height(8.dp))
                if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
            }
            is ImportUi.Error -> {
                Spacer(Modifier.height(16.dp))
                Text(s.message, color = Danger, fontSize = 14.sp)
            }
            else -> {}
        }

        Spacer(Modifier.height(28.dp))
        Row(
            Modifier.fillMaxWidth().glass(20.dp).clickable { nav.navigate("projects") }.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("My Projects", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Muted(if (projects.isEmpty()) "Your finished projects will appear here" else "${projects.size} saved")
            }
            Text("→", color = Accent, fontSize = 20.sp)
        }
    }
}

@Composable
private fun HomeTabs(selected: Int, continueCount: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("New project", if (continueCount > 0) "Continue ($continueCount)" else "Continue", "Download")
    Row(Modifier.fillMaxWidth().glass(16.dp).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            val on = selected == i
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .background(if (on) GlassFillStrong else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) TextPrimary else TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

/** Paste a YouTube / Short / Instagram link and save the video straight to the gallery. */
@Composable
private fun DownloadTab(vm: AppViewModel) {
    val ui by vm.dlUi.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    Spacer(Modifier.height(24.dp))
    Text("Save videos to\nyour gallery.", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
    Spacer(Modifier.height(10.dp))
    Muted("Paste a YouTube video, YouTube Short or Instagram reel link. You get the original video, in up to 1080p, with no added watermark.")
    Spacer(Modifier.height(24.dp))
    Column(Modifier.fillMaxWidth().glass(24.dp).padding(16.dp)) {
        OutlinedTextField(
            value = link,
            onValueChange = { link = it; vm.clearSave() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Paste a link", color = TextSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = Color(0x40FFFFFF),
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent
            )
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Paste", Modifier.weight(1f), enabled = !ui.running) {
                clipboard.getText()?.text?.let { link = it.trim(); vm.clearSave() }
            }
            GradientButton("Download", Modifier.weight(2f), enabled = !ui.running && link.isNotBlank()) { vm.saveVideo(link) }
        }
        Spacer(Modifier.height(10.dp))
        Muted("Only save videos you own or have permission to save.")
    }

    Spacer(Modifier.height(16.dp))
    when {
        ui.running -> {
            Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
                if (ui.title.isNotBlank()) Text(ui.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Muted(ui.stage + (ui.progress?.let { " ${(it * 100).toInt()}%" } ?: ""))
                Spacer(Modifier.height(10.dp))
                if (ui.progress != null) LinearProgressIndicator(progress = { ui.progress!! }, Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
                Spacer(Modifier.height(12.dp))
                Muted("It keeps downloading if you leave the app.")
                Spacer(Modifier.height(10.dp))
                SoftButton("Cancel", Modifier.fillMaxWidth(), danger = true) { vm.cancelSave() }
            }
        }
        ui.done -> {
            Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
                Text("Saved to your gallery", color = Accent2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                if (ui.title.isNotBlank()) Text(ui.title, color = TextPrimary, fontSize = 14.sp, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Muted("Movies/ClipForge Downloads")
            }
        }
        ui.error != null -> Text(ui.error!!, color = Danger, fontSize = 14.sp)
    }
}

/** Projects you can pick up again: still analysing, analysed but never exported, or interrupted. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ContinueTab(
    vm: AppViewModel,
    nav: NavController,
    items: List<ProjectEntity>,
    counts: Map<String, Int>,
    unexported: Map<String, Int>,
    live: AnalysisUi?,
    notice: String?
) {
    Column(Modifier.fillMaxWidth()) {
        if (notice != null) {
            Text(notice, color = Danger, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
        }
        if (items.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nothing waiting", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Muted("Videos you analysed but haven't exported yet will show up here.")
            }
        } else {
            Muted("Pick up where you left off. A project stays here until you export its clips or dismiss it. Dismissed projects are still in My Projects.")
            Spacer(Modifier.height(14.dp))
            items.forEach { p ->
                val running = live != null && live.projectId == p.id && !live.done && live.error == null
                ContinueCard(
                    p = p,
                    total = counts[p.id] ?: 0,
                    waiting = unexported[p.id] ?: 0,
                    live = if (running) live else null,
                    canRetry = File(p.sourcePath).exists(),
                    onOpen = {
                        when {
                            running -> nav.navigate("analysis/${p.id}")
                            p.status == "READY" -> nav.navigate("results/${p.id}")
                            else -> {}
                        }
                    },
                    onRetry = {
                        val id = vm.retryProject(p)
                        if (id != null) nav.navigate("analysis/$id")
                    },
                    onDismiss = { vm.dismissProject(p.id) },
                    onDelete = { vm.deleteProject(p) }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ContinueCard(
    p: ProjectEntity,
    total: Int,
    waiting: Int,
    live: AnalysisUi?,
    canRetry: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth().glass(20.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileImage(p.thumbPath, Modifier.size(width = 104.dp, height = 66.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                when {
                    live != null -> {
                        Pill("Analysing ${(live.progress * 100).toInt()}%", color = Accent2)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { live.progress }, Modifier.fillMaxWidth(), color = Accent, trackColor = Bg2)
                    }
                    p.status == "READY" -> {
                        Pill("$waiting of $total clips not exported", color = Accent)
                        Spacer(Modifier.height(4.dp))
                        Muted("Analysed ${dateText(p.createdAt)}")
                    }
                    else -> {
                        Pill("Didn't finish", color = Danger)
                        Spacer(Modifier.height(4.dp))
                        Text(p.statusMessage ?: "Analysis didn't finish.", color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (live == null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (p.status == "READY") {
                    GradientButton("Open & export", Modifier.weight(1f), onClick = onOpen)
                    SoftButton("Dismiss", onClick = onDismiss)
                } else {
                    if (canRetry) GradientButton("Retry", Modifier.weight(1f), onClick = onRetry)
                    SoftButton("Delete", danger = true, onClick = onDelete)
                }
            }
        }
    }
}

// ============================================================ SOURCE

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(vm: AppViewModel, nav: NavController) {
    val imp by vm.importUi.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var lengthRange by remember { mutableStateOf(15f..45f) }
    var clipCount by remember { mutableStateOf(0) }   // 0 = auto
    val context = LocalContext.current
    val startNow: () -> Unit = {
        val id = vm.startAnalysis(
            ClipSettings(lengthRange.start.roundToInt(), lengthRange.endInclusive.roundToInt(), clipCount)
        )
        if (id != null) nav.navigate("analysis/$id") { popUpTo("home") }
    }
    // Android 13+ asks before ClipForge may show the "analysing / ready" notification. Analysis runs either way.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> startNow() }

    // Leaving this screen discards the prepared video (unless analysis already took ownership of it).
    DisposableEffect(Unit) { onDispose { vm.clearImport() } }

    val pending = (imp as? ImportUi.Ready)?.pending
    val loading = imp as? ImportUi.Loading

    // After clearing (e.g. analysis started) there is nothing to show.
    if (pending == null && loading == null) {
        Box(Modifier.fillMaxSize().appBackdrop()) {}
        return
    }

    BackHandler { nav.popBackStack() }

    Column(Modifier.fillMaxSize().appBackdrop().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        BackBar("Source video") { nav.popBackStack() }
        Spacer(Modifier.height(8.dp))

        val ready = pending
        if (ready != null) {
            if (ready.localPath != null) {
                VideoPlayer(Uri.fromFile(File(ready.localPath)), Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).glass(18.dp)) {
                    ready.thumb?.let {
                        androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(ready.title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(clock(ready.durationMs))
                Pill(if (ready.kind == "YOUTUBE") "YouTube" else "From phone", color = Accent2)
            }
            Spacer(Modifier.height(24.dp))
            if (notice != null) {
                Text(notice!!, color = Danger, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
            }
            if (ready.localPath == null) {
                Muted("To preview and analyse a YouTube video, ClipForge first downloads it to this phone.")
                Spacer(Modifier.height(12.dp))
                GradientButton("Download video", Modifier.fillMaxWidth()) { vm.downloadYoutube() }
            } else {
                Muted("Play the video above to check it, then choose your clip settings.")
                Spacer(Modifier.height(16.dp))

                Column(Modifier.fillMaxWidth().glass(20.dp).padding(16.dp)) {
                    Text("Clip length", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Muted("Each clip will be between ${lengthRange.start.roundToInt()}s and ${lengthRange.endInclusive.roundToInt()}s")
                    RangeSlider(
                        value = lengthRange,
                        onValueChange = { r ->
                            var a = r.start.roundToInt().toFloat()
                            var b = r.endInclusive.roundToInt().toFloat()
                            if (b - a < 5f) {
                                if (a != lengthRange.start) a = b - 5f else b = a + 5f
                            }
                            lengthRange = a.coerceAtLeast(5f)..b.coerceAtMost(180f)
                        },
                        valueRange = 5f..180f,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Bg2
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Number of clips", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Auto", 3 to "3", 5 to "5", 10 to "10", 15 to "15", 20 to "20").forEach { (n, label) ->
                            FilterChip(selected = clipCount == n, onClick = { clipCount = n }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Muted("If the video has fewer strong moments than you ask for, you get fewer clips.")
                }
                Spacer(Modifier.height(16.dp))
                GradientButton("Analyse video", Modifier.fillMaxWidth()) {
                    val needsAsk = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsAsk) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startNow()
                }
                Spacer(Modifier.height(8.dp))
                Muted("Analysis keeps running in the background. You can leave the app and come back from the Continue tab.")
            }
        }
        if (loading != null) {
            Spacer(Modifier.height(30.dp))
            Muted(loading.message)
            Spacer(Modifier.height(8.dp))
            if (loading.progress != null) LinearProgressIndicator(progress = { loading.progress }, Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
            Spacer(Modifier.height(12.dp))
            SoftButton("Cancel", danger = true) { vm.clearImport(); nav.popBackStack() }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================ ANALYSIS

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AnalysisScreen(vm: AppViewModel, nav: NavController, projectId: String) {
    val a by vm.analysisUi.collectAsStateWithLifecycle()

    // Set before any deliberate navigation so the "state became null" effect below doesn't fight it.
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(a) {
        val s = a
        if (s != null && s.projectId == projectId && s.done && !leaving) {
            leaving = true
            nav.navigate("results/$projectId") { popUpTo("home") }
        }
    }
    // Cancelled, or the process was restarted and nothing is running any more.
    LaunchedEffect(a == null) {
        if (a == null && !leaving) {
            leaving = true
            nav.navigate("home") { popUpTo("home") { inclusive = true } }
        }
    }
    // Back = keep working in the background (the Cancel button is the only thing that discards the work).
    BackHandler {
        leaving = true
        nav.navigate("home") { popUpTo("home") { inclusive = true } }
    }

    Column(
        Modifier.fillMaxSize().appBackdrop().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val s = a
        if (s?.error != null) {
            Text("Couldn't find highlights", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(s.error, color = Danger, fontSize = 15.sp)
            Spacer(Modifier.height(28.dp))
            GradientButton("Back to My Projects", Modifier.fillMaxWidth()) {
                leaving = true
                nav.navigate("projects") { popUpTo("home") }
            }
        } else if (s != null) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { s.progress }, modifier = Modifier.size(140.dp), color = Accent, trackColor = CardBg, strokeWidth = 8.dp)
                Text("${(s.progress * 100).toInt()}%", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))
            Text("Analysing your video", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Muted(s.stage)
            Spacer(Modifier.height(8.dp))
            Muted("You can leave the app. ClipForge keeps working and sends a notification when your highlights are ready. Long videos take a few minutes.")
            Spacer(Modifier.height(28.dp))
            GradientButton("Continue in background") {
                leaving = true
                nav.navigate("home") { popUpTo("home") { inclusive = true } }
            }
            Spacer(Modifier.height(10.dp))
            SoftButton("Cancel", danger = true) { vm.cancelAnalysis() }
        }
    }
}

// ============================================================ RESULTS

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(vm: AppViewModel, nav: NavController, projectId: String) {
    val project by vm.project(projectId).collectAsStateWithLifecycle(null)
    val clips by vm.clips(projectId).collectAsStateWithLifecycle(emptyList())
    val selected = remember { mutableStateListOf<String>() }
    var previewId by remember { mutableStateOf<String?>(null) }
    var deleteClip by remember { mutableStateOf<ClipEntity?>(null) }
    var exportList by remember { mutableStateOf<List<ClipEntity>?>(null) }

    val p = project
    Box(Modifier.fillMaxSize().appBackdrop()) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            BackBar(p?.title ?: "Project") { nav.popBackStack() }
            if (p != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("${clips.size} clips")
                    Pill("Saved in My Projects", color = Accent2)
                }
                if (p.status == "FAILED") {
                    Spacer(Modifier.height(12.dp))
                    Text(p.statusMessage ?: "Analysis failed.", color = Danger, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (clips.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Muted(if (p?.status == "FAILED") "No clips were created for this project." else "No clips.")
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(clips, key = { it.id }) { c ->
                    ClipCard(
                        c,
                        checked = c.id in selected,
                        onChecked = { if (it) selected.add(c.id) else selected.remove(c.id) },
                        onPreview = { previewId = c.id },
                        onExport = { exportList = listOf(c) },
                        onDelete = { deleteClip = c }
                    )
                }
            }
            Row(Modifier.fillMaxWidth().background(Bg2).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(
                    if (selected.isEmpty()) "Select clips" else "Export ${selected.size} selected",
                    Modifier.weight(1f), enabled = selected.isNotEmpty()
                ) { exportList = clips.filter { it.id in selected } }
                GradientButton("Export all", Modifier.weight(1f)) { exportList = clips }
            }
        }
        if (clips.isEmpty() && p != null) {
            Row(Modifier.padding(16.dp)) {
                SoftButton("Delete project", Modifier.fillMaxWidth(), danger = true) { vm.deleteProject(p); nav.popBackStack() }
            }
        }
    }

    previewId?.let { id ->
        ClipPreview(
            clips = clips,
            startId = id,
            onClose = { previewId = null },
            onExport = { c -> previewId = null; exportList = listOf(c) },
            onEdit = { c -> if (vm.openEditorForClip(c)) { previewId = null; nav.navigate("editor") } },
            onDelete = { c -> deleteClip = c }
        )
    }
    }   // end of the Box that holds the screen and the full-screen preview

    deleteClip?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteClip = null },
            containerColor = Bg2,
            title = { Text("Delete this clip?", color = TextPrimary) },
            text = { Text("The clip file will be removed from this project. Exported copies in your gallery are not touched.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { vm.deleteClip(c); selected.remove(c.id); deleteClip = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { deleteClip = null }) { Text("Cancel", color = TextPrimary) } }
        )
    }

    exportList?.let { list ->
        ExportSheet(vm, list) { exportList = null }
    }
}

@Composable
private fun ClipCard(
    c: ClipEntity,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val exists = File(c.filePath).exists()
    Column(Modifier.fillMaxWidth().glass(22.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(enabled = exists, onClick = onPreview)) {
            FileImage(c.thumbPath, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(54.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 22.sp)
            }
            Pill("${c.score}", Modifier.align(Alignment.TopStart).padding(10.dp), Accent)
            Checkbox(
                checked = checked, onCheckedChange = onChecked, modifier = Modifier.align(Alignment.TopEnd),
                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Color.White, checkmarkColor = Color.White)
            )
        }
        Column(Modifier.padding(16.dp)) {
            Text(c.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(c.category, color = Accent2)
                Pill(clock(c.endMs - c.startMs))
            }
            Spacer(Modifier.height(8.dp))
            Muted("In the original: ${clock(c.startMs)} – ${clock(c.endMs)}")
            if (!exists) {
                Spacer(Modifier.height(6.dp))
                Text("This clip's file is missing from storage. You can delete it.", color = Danger, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Preview", enabled = exists, onClick = onPreview)
                SoftButton("Export", enabled = exists, onClick = onExport)
                SoftButton("Delete", danger = true, onClick = onDelete)
            }
        }
    }
}

/**
 * Full-screen clip preview: swipe up/down (or use the buttons) to move between clips, the clip on screen
 * plays and loops, and you can check how it would look as 9:16 or 1:1 before exporting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipPreview(
    clips: List<ClipEntity>,
    startId: String,
    onClose: () -> Unit,
    onExport: (ClipEntity) -> Unit,
    onEdit: (ClipEntity) -> Unit,
    onDelete: (ClipEntity) -> Unit
) {
    BackHandler(onBack = onClose)
    if (clips.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val startIndex = remember { clips.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = startIndex, pageCount = { clips.size })
    val scope = rememberCoroutineScope()
    var aspect by remember { mutableStateOf(AspectMode.ORIGINAL) }
    val current = pager.currentPage.coerceIn(0, clips.size - 1)
    val cur = clips[current]

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "✕",
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClose).padding(horizontal = 14.dp, vertical = 8.dp),
                color = TextPrimary,
                fontSize = 20.sp
            )
            Text("Clip ${current + 1} of ${clips.size}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SoftButton("Edit") { onEdit(cur) }
        }

        VerticalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { clips[it].id }
        ) { page ->
            val c = clips[page]
            val file = File(c.filePath)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    if (file.exists()) {
                        VideoPlayer(
                            Uri.fromFile(file),
                            Modifier.fillMaxSize(),
                            aspect = aspect,
                            autoPlay = page == pager.currentPage,
                            loop = true,
                            cornerRadius = 14.dp
                        )
                    } else {
                        Text("This clip's file is missing from storage.", color = Danger, fontSize = 14.sp)
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(c.title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Score ${c.score}", color = Accent)
                        Pill(c.category, color = Accent2)
                        Pill(clock(c.endMs - c.startMs))
                    }
                    Spacer(Modifier.height(6.dp))
                    Muted("In the original video: ${clock(c.startMs)} – ${clock(c.endMs)}")
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Bg2).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Preview the frame", color = TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectMode.values().forEach { m ->
                    FilterChip(selected = aspect == m, onClick = { aspect = m }, label = { Text(m.label) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton("↑", enabled = current > 0) { scope.launch { pager.animateScrollToPage(current - 1) } }
                SoftButton("↓", enabled = current < clips.size - 1) { scope.launch { pager.animateScrollToPage(current + 1) } }
                SoftButton("Delete", danger = true) { onDelete(cur) }
                GradientButton("Export", Modifier.weight(1f)) { onExport(cur) }
            }
        }
    }
}

@Composable
fun PlayerDialog(title: String, file: File, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.glass(24.dp).padding(16.dp)) {
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (file.exists()) {
                VideoPlayer(Uri.fromFile(file), Modifier.fillMaxWidth().aspectRatio(16f / 9f), autoPlay = true)
            } else {
                Text("This file is missing from storage.", color = Danger)
            }
            Spacer(Modifier.height(12.dp))
            SoftButton("Close", Modifier.fillMaxWidth(), onClick = onClose)
        }
    }
}

// ============================================================ EXPORT

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(vm: AppViewModel, clips: List<ClipEntity>, onDismiss: () -> Unit) {
    val state by vm.exportUi.collectAsStateWithLifecycle()
    var aspect by remember { mutableStateOf(AspectMode.ORIGINAL) }
    var quality by remember { mutableStateOf(Quality.HIGH) }
    var preview by remember { mutableStateOf<Pair<String, Uri>?>(null) }

    DisposableEffect(Unit) { onDispose { if (!vm.exportUi.value.running) vm.resetExport() } }

    ModalBottomSheet(
        onDismissRequest = { if (!state.running) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg2
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            SectionTitle(if (clips.size == 1) "Export clip" else "Export ${clips.size} clips")
            Spacer(Modifier.height(4.dp))
            Muted("Format: MP4 · saved to Movies/ClipForge in your gallery")
            Spacer(Modifier.height(16.dp))

            if (!state.running && !state.finished) {
                Muted("Aspect ratio")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AspectMode.entries.forEach { m ->
                        FilterChip(selected = aspect == m, onClick = { aspect = m }, label = { Text(m.label) })
                    }
                }
                if (aspect != AspectMode.ORIGINAL) {
                    Spacer(Modifier.height(10.dp))
                    Muted("Quality")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Quality.entries.forEach { q ->
                            FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q.label) })
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Muted("Preview of \"${clips.first().title}\"")
                    Spacer(Modifier.height(6.dp))
                    val f = File(clips.first().filePath)
                    if (f.exists()) {
                        VideoPlayer(
                            Uri.fromFile(f),
                            Modifier.fillMaxWidth().height(if (aspect == AspectMode.VERTICAL) 380.dp else 300.dp),
                            aspect = aspect
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Muted("The whole picture is kept and fitted inside the frame, with bars filling the rest (like CapCut). Nothing is cropped.")
                } else {
                    Spacer(Modifier.height(8.dp))
                    Muted("Original keeps the clip exactly as cut, with no re-compression.")
                }
                Spacer(Modifier.height(18.dp))
                GradientButton("Export", Modifier.fillMaxWidth()) { vm.export(clips, aspect, quality) }
            }

            if (state.running) {
                Muted("Exporting clip ${state.index + 1} of ${state.total}…")
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth(), color = Accent, trackColor = CardBg)
                Spacer(Modifier.height(16.dp))
                SoftButton("Cancel", danger = true) { vm.cancelExport() }
            }

            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Danger, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                SoftButton("Try again") { vm.resetExport() }
            }

            if (state.finished) {
                Text("✓ Saved to your gallery (Movies/ClipForge)", color = Accent2, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                state.saved.forEach { (title, uri) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SoftButton("Play") { preview = title to uri }
                    }
                }
                Spacer(Modifier.height(14.dp))
                GradientButton("Done", Modifier.fillMaxWidth()) { vm.resetExport(); onDismiss() }
            }
        }
    }

    preview?.let { (title, uri) ->
        Dialog(onDismissRequest = { preview = null }) {
            Column(Modifier.glass(24.dp).padding(16.dp)) {
                Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                VideoPlayer(uri, Modifier.fillMaxWidth().height(320.dp), autoPlay = true)
                Spacer(Modifier.height(12.dp))
                SoftButton("Close", Modifier.fillMaxWidth()) { preview = null }
            }
        }
    }
}

// ============================================================ MY PROJECTS

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ProjectsScreen(vm: AppViewModel, nav: NavController) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val counts by vm.clipCounts.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    Column(Modifier.fillMaxSize().appBackdrop().systemBarsPadding().padding(horizontal = 20.dp)) {
        BackBar("My Projects") { nav.popBackStack() }
        Spacer(Modifier.height(8.dp))
        if (projects.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(72.dp).glass(22.dp, strong = true), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.clipforge.app.R.drawable.ic_logo), null, Modifier.size(40.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text("No projects yet", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Muted("Analyse a video and its clips will be saved here.")
                Spacer(Modifier.height(20.dp))
                GradientButton("Start a project") { nav.popBackStack() }
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                gridItems(projects, key = { it.id }) { p ->
                    Column(Modifier.glass(20.dp).clickable { nav.navigate("results/${p.id}") }) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                            FileImage(p.thumbPath, Modifier.fillMaxSize())
                            Pill(
                                when (p.status) { "READY" -> "Ready"; "ANALYZING" -> "Analysing"; else -> "Failed" },
                                Modifier.align(Alignment.TopStart).padding(8.dp),
                                if (p.status == "FAILED") Danger else Accent2
                            )
                        }
                        Column(Modifier.padding(12.dp)) {
                            Text(p.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Muted("${counts[p.id] ?: 0} clips · ${clock(p.durationMs)}")
                            Muted(dateText(p.createdAt))
                            Spacer(Modifier.height(6.dp))
                            Text("Delete", color = Danger, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { toDelete = p }.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = Bg2,
            title = { Text("Delete this project?", color = TextPrimary) },
            text = { Text("\"${p.title}\" and all of its clips will be removed from this phone. Copies you already exported to your gallery stay.", color = TextSecondary) },
            confirmButton = { TextButton(onClick = { vm.deleteProject(p); toDelete = null }) { Text("Delete", color = Danger) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel", color = TextPrimary) } }
        )
    }
}
