package app.ultimate.squeeze.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.ultimate.core.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.squeeze.engine.Item
import app.ultimate.squeeze.engine.QueueState
import app.ultimate.squeeze.engine.Status
import app.ultimate.squeeze.model.Codec
import app.ultimate.squeeze.model.LibraryEntry
import app.ultimate.squeeze.model.CodecChoice
import app.ultimate.squeeze.model.Mode
import app.ultimate.squeeze.model.Plan
import app.ultimate.squeeze.model.Preset
import app.ultimate.squeeze.model.Settings
import app.ultimate.squeeze.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun SqueezeScreen(c: SqueezeController, onExit: () -> Unit, launch: ToolLaunch) {
    val queue by c.queue.collectAsStateWithLifecycle()
    val entries by c.entries.collectAsStateWithLifecycle()
    val probing by c.isProbing.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val library by c.library.collectAsStateWithLifecycle()
    val detailId by c.detailId.collectAsStateWithLifecycle()
    val detail = library.firstOrNull { it.id == detailId }
    val withConsent = rememberNotificationConsent()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { c.add(it) }
    val onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    LaunchedEffect(launch) {
        if (!launch.consumed) {
            launch.consumed = true
            if (launch.uris.isNotEmpty()) c.add(launch.uris)
            launch.openItemId?.let { c.openDetail(it, fromLibrary = true) }
        }
    }

    when {
        detail != null -> DetailScreen(
            entry = detail, beforeFile = c.frameFile(detail.id, before = true), afterFile = c.frameFile(detail.id, before = false), clipFile = c.clipFile(detail.id),
            onBack = { c.closeDetail(onExit) }, onRemove = { deleteFile -> c.removeFromLibrary(detail.id, deleteFile) },
        )
        queue.jobs.isNotEmpty() -> RunScreen(queue, library, c, onCancel = c::cancel, onDone = c::dismissResults, onOpen = { c.openDetail(it) })
        entries.isNotEmpty() || probing -> ConfigureScreen(
            entries = entries, probing = probing, settings = settings, hevc = c.capabilities.hevc, av1 = c.capabilities.av1,
            onChange = c::update, onAdd = onPick, onRemove = c::remove, onBack = c::clear, onStart = { withConsent { c.start() } },
        )
        else -> HomeScreen(onPick, onExit)
    }
}

// ───────────────────────────── Home ─────────────────────────────

@Composable
private fun HomeScreen(onPick: () -> Unit, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Scaffold(topBar = { BackBar(onExit) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(104.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Compress, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(28.dp))
            Text("Squeeze", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Shrink videos to a fraction of the size.\nNothing ever leaves your phone.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Button(onClick = onPick, Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(10.dp))
                Text("Choose videos", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(36.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Fact(Icons.Rounded.Block, "No ads")
                Fact(Icons.Rounded.CloudOff, "Works offline")
                Fact(Icons.Rounded.VisibilityOff, "No tracking")
            }
        }
    }
}

@Composable
private fun Fact(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ─────────────────────────── Configure ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureScreen(
    entries: List<Entry>,
    probing: Boolean,
    settings: Settings,
    hevc: Boolean,
    av1: Boolean,
    onChange: ((Settings) -> Settings) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Uri) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val ready = entries.filter { it.plan != null }
    val before = ready.sumOf { it.probed.source!!.sizeBytes }
    val after = ready.sumOf { it.plan!!.estimatedBytes }
    val toEncode = ready.count { it.plan!!.skipReason == null }
    var advanced by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entries.size == 1) "1 video" else "${entries.size} videos") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(onAdd) { Icon(Icons.Rounded.Add, "Add videos") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onStart,
                    enabled = toEncode > 0 && !probing,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        when {
                            probing -> "Reading videos…"
                            toEncode == 0 -> "Nothing to compress"
                            toEncode == 1 -> "Compress video"
                            else -> "Compress $toEncode videos"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { EstimateCard(before, after, ready.isNotEmpty()) }
            item { ModeSection(settings, entries, onChange) }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Advanced", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton({ advanced = !advanced }) { Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Toggle advanced") }
                    }
                    AnimatedVisibility(advanced) { AdvancedSection(settings, hevc, av1, onChange) }
                }
            }
            items(entries, key = { it.probed.uri.toString() }) { VideoRow(it, onRemove = { onRemove(it.probed.uri) }) }
            if (probing) item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
        }
    }
}

@Composable
private fun EstimateCard(before: Long, after: Long, show: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("Estimated result", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            if (!show) {
                Text("…", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("≈ ${formatBytes(after)}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text("from ${formatBytes(before)}", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { if (before > 0) (after.toFloat() / before).coerceIn(0.02f, 1f) else 1f },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Saves about ${percentSaved(before, after)}%. Real size varies with how busy the footage is.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ModeSection(settings: Settings, entries: List<Entry>, onChange: ((Settings) -> Settings) -> Unit) {
    data class Choice(val label: String, val mode: Mode, val preset: Preset?)
    val choices = listOf(
        Choice("Balanced", Mode.Preset, Preset.Balanced),
        Choice("Small", Mode.Preset, Preset.Small),
        Choice("Tiny", Mode.Preset, Preset.Tiny),
        Choice("Size", Mode.TargetSize, null),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { i, c ->
                val selected = if (c.mode == Mode.TargetSize) settings.mode == Mode.TargetSize else settings.mode == Mode.Preset && settings.preset == c.preset
                SegmentedButton(
                    selected = selected,
                    onClick = { onChange { s -> if (c.preset != null) s.copy(mode = Mode.Preset, preset = c.preset) else s.copy(mode = Mode.TargetSize) } },
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    label = { Text(c.label, maxLines = 1) },
                )
            }
        }
        if (settings.mode == Mode.Preset) {
            Text(settings.preset.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TargetSizeControls(settings, entries, onChange)
        }
    }
}

@Composable
private fun TargetSizeControls(settings: Settings, entries: List<Entry>, onChange: ((Settings) -> Settings) -> Unit) {
    val largestMb = max(2, ((entries.maxOfOrNull { it.probed.sizeBytes } ?: 100_000_000L) / 1_000_000L).toInt())
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Each video up to ${settings.targetMb} MB", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = settings.targetMb.toFloat().coerceIn(1f, largestMb.toFloat()),
            onValueChange = { v -> onChange { it.copy(targetMb = v.roundToInt().coerceAtLeast(1)) } },
            valueRange = 1f..largestMb.toFloat(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(8, 16, 25, 50, 100).forEach { mb ->
                FilterChip(selected = settings.targetMb == mb, onClick = { onChange { it.copy(targetMb = mb) } }, label = { Text("$mb MB") })
            }
        }
        Text(
            "Squeeze lowers frame rate and resolution as needed so the picture stays clean instead of blocky.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdvancedSection(settings: Settings, hevc: Boolean, av1: Boolean, onChange: ((Settings) -> Settings) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Label("Codec")
        ChipRow {
            CodecChoice.entries.filter { it != CodecChoice.Hevc || hevc }.filter { it != CodecChoice.Av1 || av1 }.forEach { c ->
                FilterChip(selected = settings.codec == c, onClick = { onChange { it.copy(codec = c) } }, label = { Text(c.label) })
            }
        }
        Text(
            if (hevc) "Auto uses HEVC: about 30% smaller than H.264 at the same quality." else "This phone has no hardware HEVC encoder, so Squeeze uses H.264.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Label("Maximum resolution")
        ChipRow {
            listOf<Pair<String, Int?>>("Original" to null, "1080p" to 1080, "720p" to 720, "480p" to 480).forEach { (l, v) ->
                FilterChip(selected = settings.maxShortSide == v, onClick = { onChange { it.copy(maxShortSide = v) } }, label = { Text(l) })
            }
        }
        Label("Maximum frame rate")
        ChipRow {
            listOf<Pair<String, Int?>>("Original" to null, "30 fps" to 30, "24 fps" to 24).forEach { (l, v) ->
                FilterChip(selected = settings.maxFps == v, onClick = { onChange { it.copy(maxFps = v) } }, label = { Text(l) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Remove audio", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(settings.muteAudio, { v -> onChange { it.copy(muteAudio = v) } })
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ChipRow(content: @Composable () -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
private fun VideoRow(entry: Entry, onRemove: () -> Unit) {
    val src = entry.probed.source
    val plan = entry.plan
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        VideoThumb(entry.probed.uri, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)), src?.durationMs ?: 0)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.probed.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (src == null || plan == null) {
                Text("Can't read this video", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(sourceSummary(src), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (plan.skipReason != null) {
                    Text(plan.skipReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                } else {
                    Text(
                        "≈ ${formatBytes(plan.estimatedBytes)}  ·  −${percentSaved(src.sizeBytes, plan.estimatedBytes)}%",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    )
                    Text(planSummary(plan), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    plan.warning?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        IconButton(onRemove) { Icon(Icons.Rounded.Close, "Remove") }
    }
}

private fun sourceSummary(s: Source) = "${s.shortSide}p · ${s.fps.roundToInt()} fps · ${formatDuration(s.durationMs)} · ${formatBytes(s.sizeBytes)}"

private fun planSummary(p: Plan) = buildString {
    append(p.codec.label).append(" · ").append(p.shortSide).append("p")
    p.fps?.let { append(" · ").append(it).append(" fps") }
    append(" · ").append(formatMbps(p.videoBitrate))
}

// ───────────────────────────── Run ──────────────────────────────

@Composable
private fun RunScreen(queue: QueueState, library: List<LibraryEntry>, vm: SqueezeController, onCancel: () -> Unit, onDone: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    BackHandler(enabled = !queue.active, onBack = onDone)
    val firstDone = queue.jobs.firstNotNullOfOrNull { job -> (job.status as? Status.Done)?.libraryId?.let { id -> library.firstOrNull { it.id == id } } }
    val celebrate = !queue.active && queue.jobs.any { it.status is Status.Done }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (queue.active) {
                        OutlinedButton(onCancel, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Cancel") }
                    } else {
                        Button(onDone, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Done", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { if (queue.active) ProgressHero(queue) else DoneHero(queue) }
                item {
                    val lines by vm.log.lines.collectAsStateWithLifecycle()
                    Terminal(lines, live = queue.active)
                }
                if (!queue.active) {
                    firstDone?.let { entry ->
                        item {
                            CompareBlock(
                                entry, beforeFile = vm.frameFile(entry.id, before = true), afterFile = vm.frameFile(entry.id, before = false),
                                clipFile = vm.clipFile(entry.id), modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    item { FunFacts(queue) }
                }
                items(queue.jobs, key = { it.id }) { ResultRow(it, context, onOpen) }
            }
            if (celebrate) Confetti(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ResultRow(job: Item, context: android.content.Context, onOpen: (String) -> Unit) {
    val libraryId = (job.status as? Status.Done)?.libraryId
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(if (libraryId != null) Modifier.clickable { onOpen(libraryId) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumb(job.uri, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), job.source.durationMs)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(job.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            when (val s = job.status) {
                Status.Queued -> Sub("Waiting")
                is Status.Running -> {
                    LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(CircleShape))
                    Sub("${(s.progress * 100).roundToInt()}%" + (s.speedX?.let { String.format(java.util.Locale.US, " · %.1f× real time", it) } ?: "") + (s.etaMs?.let { " · ${formatEta(it)}" } ?: ""))
                }
                is Status.Done -> {
                    Text(
                        "${formatBytes(job.source.sizeBytes)} → ${formatBytes(s.outputBytes)}  (−${percentSaved(job.source.sizeBytes, s.outputBytes)}%)",
                        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                    if (s.libraryId != null) Sub("Tap to compare and see stats")
                }
                is Status.Skipped -> Sub(s.reason)
                is Status.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Status.Cancelled -> Sub("Cancelled")
            }
        }
        when (val s = job.status) {
            is Status.Done -> {
                IconButton({ context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.output, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }) { Icon(Icons.Rounded.PlayArrow, "Play") }
                IconButton({
                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, s.output).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share video"))
                }) { Icon(Icons.Rounded.Share, "Share") }
            }
            is Status.Failed -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
            is Status.Skipped -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.outline)
            else -> {}
        }
    }
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
