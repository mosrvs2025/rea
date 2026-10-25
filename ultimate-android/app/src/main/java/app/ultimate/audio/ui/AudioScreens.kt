package app.ultimate.audio.ui

import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.audio.engine.AudioJob
import app.ultimate.audio.engine.AudioQueue
import app.ultimate.audio.engine.AudioStatus
import app.ultimate.audio.engine.PickedVideo
import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.OutFormat
import app.ultimate.core.AudioPlayerBar
import app.ultimate.core.BackBar
import app.ultimate.core.SuccessCheck
import app.ultimate.core.ToolLaunch
import app.ultimate.core.VideoThumb
import app.ultimate.core.Waveform
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import app.ultimate.core.rememberNotificationConsent
import app.ultimate.core.shareFiles
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AudioScreen(c: AudioController, onExit: () -> Unit, launch: ToolLaunch) {
    val queue by c.queue.collectAsStateWithLifecycle()
    val picked by c.picked.collectAsStateWithLifecycle()
    val probing by c.probing.collectAsStateWithLifecycle()
    val settings by c.settings.collectAsStateWithLifecycle()
    val withConsent = rememberNotificationConsent()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { c.add(it) }
    val onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    LaunchedEffect(launch) {
        if (!launch.consumed) {
            launch.consumed = true
            if (launch.uris.isNotEmpty()) c.add(launch.uris)
        }
    }

    when {
        queue.jobs.isNotEmpty() -> RunScreen(queue, onCancel = c::cancel, onDone = c::dismissResults)
        picked.isNotEmpty() || probing > 0 -> ConfigureScreen(
            picked, probing > 0, settings, onChange = c::update, onAdd = onPick, onRemove = c::remove, onBack = c::clear,
            onStart = { withConsent { c.start() } },
        )
        else -> HomeScreen(onPick, onExit)
    }
}

// ───────────────────────────── Home ─────────────────────────────

@Composable
private fun HomeScreen(onPick: () -> Unit, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val demo = remember(phase) {
        List(40) { i -> (0.25f + 0.65f * kotlin.math.abs(kotlin.math.sin((i / 4.5f) + phase * 6.28f)) * (0.5f + 0.5f * kotlin.math.sin(i / 9f))).coerceIn(0.05f, 1f) }
    }
    Scaffold(topBar = { BackBar(onExit) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Waveform(demo, Modifier.fillMaxWidth().height(96.dp), bars = 40, played = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(28.dp))
            Text("Video to audio", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pull the sound out of any video as MP3, M4A or WAV. Trim it first if you only want part.",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(36.dp))
            Button(onClick = onPick, Modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp)) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(10.dp))
                Text("Choose videos", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

// ─────────────────────────── Configure ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigureScreen(
    picked: List<PickedVideo>,
    probing: Boolean,
    settings: AudioSettings,
    onChange: ((AudioSettings) -> AudioSettings) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Uri) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val usable = picked.filter { it.audio != null }
    val single = usable.size == 1
    val effective = if (single) settings else settings.copy(trimStartMs = 0, trimEndMs = -1)
    val total = usable.sumOf { AudioPlanner.estimateBytes(it.audio!!, effective) }
    val anyCopyable = usable.any { it.audio!!.canCopy }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (picked.size == 1) "1 video" else "${picked.size} videos") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(onAdd) { Icon(Icons.Rounded.Add, "Add videos") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = onStart, enabled = usable.isNotEmpty() && !probing,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp), shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        when {
                            probing -> "Reading videos…"
                            usable.isEmpty() -> "No audio to convert"
                            usable.size == 1 -> "Convert to ${AudioPlanner.effectiveFormat(usable[0].audio!!, effective).label}"
                            else -> "Convert ${usable.size} videos"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { EstimateCard(total, usable.size) }
            item { FormatSection(settings, anyCopyable, onChange) }
            if (single) item { TrimSection(usable[0].audio!!, settings, onChange) }
            items(picked, key = { it.uri.toString() }) { VideoRow(it, effective, onRemove = { onRemove(it.uri) }) }
            if (probing) item { Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) } }
        }
    }
}

@Composable
private fun EstimateCard(bytes: Long, count: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("Estimated result", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                if (count == 0) "…" else "≈ ${formatBytes(bytes)}",
                style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                if (count > 1) "across $count files" else "Saved to Music/Ultimate",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FormatSection(settings: AudioSettings, anyCopyable: Boolean, onChange: ((AudioSettings) -> AudioSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OutFormat.entries.forEachIndexed { i, f ->
                SegmentedButton(
                    selected = settings.format == f, onClick = { onChange { it.copy(format = f) } },
                    shape = SegmentedButtonDefaults.itemShape(i, OutFormat.entries.size),
                    label = { Text(f.label) },
                )
            }
        }
        Text(settings.format.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (settings.format == OutFormat.M4a && !anyCopyable) {
            Text(
                "These videos don't use AAC audio, so they'll be converted to MP3 instead.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (settings.format == OutFormat.Mp3 || (settings.format == OutFormat.M4a && !anyCopyable)) {
            Text("Quality", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioPlanner.MP3_BITRATES.forEach { k ->
                    FilterChip(selected = settings.kbps == k, onClick = { onChange { it.copy(kbps = k) } }, label = { Text("$k kbps") })
                }
            }
            Text(
                AudioPlanner.bitrateLabel(settings.kbps).let { if (it.isEmpty()) "" else "$it quality" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (settings.format != OutFormat.M4a) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mono", style = MaterialTheme.typography.bodyLarge)
                    Text("Halves the size of voice recordings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.mono, { v -> onChange { it.copy(mono = v) } })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimSection(src: AudioSource, settings: AudioSettings, onChange: ((AudioSettings) -> AudioSettings) -> Unit) {
    val end = if (settings.trimEndMs > 0) minOf(settings.trimEndMs, src.durationMs) else src.durationMs
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Trim", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            RangeSlider(
                value = settings.trimStartMs.toFloat()..end.toFloat(),
                onValueChange = { r -> onChange { it.copy(trimStartMs = r.start.toLong(), trimEndMs = if (r.endInclusive >= src.durationMs - 50) -1 else r.endInclusive.toLong()) } },
                valueRange = 0f..src.durationMs.toFloat(),
            )
            Text(
                "Keep ${formatDuration(settings.trimStartMs)} – ${formatDuration(end)}  (${formatDuration(end - settings.trimStartMs)})",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoRow(video: PickedVideo, settings: AudioSettings, onRemove: () -> Unit) {
    val src = video.audio
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        VideoThumb(video.uri, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), src?.durationMs ?: 0)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(video.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (src == null) {
                Text("No audio track", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(sourceSummary(src), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val format = AudioPlanner.effectiveFormat(src, settings)
                Text(
                    "→ ${format.label} · ≈ ${formatBytes(AudioPlanner.estimateBytes(src, settings))}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        IconButton(onRemove) { Icon(Icons.Rounded.Close, "Remove") }
    }
}

private fun sourceSummary(s: AudioSource): String {
    val codec = when (s.mime.lowercase()) {
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        else -> s.mime.substringAfter('/').uppercase()
    }
    val layout = when (s.channels) { 1 -> "mono"; 2 -> "stereo"; else -> "${s.channels} ch" }
    return "$codec · ${String.format(Locale.US, "%.1f", s.sampleRate / 1000f)} kHz · $layout · ${formatDuration(s.durationMs)}"
}

// ───────────────────────────── Run ──────────────────────────────

@Composable
private fun RunScreen(queue: AudioQueue, onCancel: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    BackHandler(enabled = !queue.active, onBack = onDone)
    val done = queue.done
    LaunchedEffect(queue.active) {
        if (!queue.active && done.isNotEmpty()) {
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        }
    }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (queue.active) {
                        OutlinedButton(onCancel, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Cancel") }
                    } else {
                        if (done.isNotEmpty()) {
                            FilledTonalButton({ shareFiles(context, done.map { Uri.parse(it.uri) }) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                                Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text(if (done.size == 1) "Share" else "Share all")
                            }
                        }
                        Button(onDone, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Done", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { RunHero(queue) }
            items(queue.jobs, key = { it.id }) { RunRow(it) }
        }
    }
}

@Composable
private fun RunHero(queue: AudioQueue) {
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val total = queue.jobs.size.coerceAtLeast(1)
    val finished = queue.jobs.count { it.status !is AudioStatus.Queued && it.status !is AudioStatus.Running }
    val running = queue.jobs.firstOrNull { it.status is AudioStatus.Running }?.status as? AudioStatus.Running
    val overall = ((finished + (running?.progress ?: 0f)) / total).coerceIn(0f, 1f)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                queue.active -> {
                    Text("Extracting sound", style = MaterialTheme.typography.labelLarge, color = onContainer)
                    Text("${(overall * 100).roundToInt()}%", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold, color = onContainer)
                    Spacer(Modifier.height(12.dp))
                    Waveform(
                        running?.peaks.orEmpty(), Modifier.fillMaxWidth().height(80.dp), bars = 160,
                        played = MaterialTheme.colorScheme.primary, rest = MaterialTheme.colorScheme.primary,
                        ghost = onContainer.copy(alpha = 0.15f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "File ${minOf(finished + 1, total)} of $total" + (running?.speedX?.let { String.format(Locale.US, " · %.1f× real time", it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = onContainer,
                    )
                    Text("You can leave the app. It keeps working.", style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.8f))
                }
                queue.done.isEmpty() -> {
                    Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(56.dp))
                    Text("Nothing was converted", style = MaterialTheme.typography.titleLarge, color = onContainer)
                }
                else -> {
                    SuccessCheck(88.dp, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (queue.done.size == 1) queue.done.first().displayName else "${queue.done.size} audio files",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = onContainer, textAlign = TextAlign.Center,
                    )
                    Text("${formatBytes(queue.done.sumOf { it.bytes })} in Music/Ultimate", style = MaterialTheme.typography.titleSmall, color = onContainer)
                }
            }
        }
    }
}

@Composable
private fun RunRow(job: AudioJob) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoThumb(job.uri, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), job.source.durationMs)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                when (val s = job.status) {
                    AudioStatus.Queued -> Sub("Waiting")
                    is AudioStatus.Running -> {
                        LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
                        Sub("${(s.progress * 100).roundToInt()}%")
                    }
                    is AudioStatus.Done -> {
                        val e = s.entry
                        Text(
                            "${e.format.label} · ${formatBytes(e.bytes)} · ${formatDuration(e.durationMs)}",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    is AudioStatus.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    AudioStatus.Cancelled -> Sub("Cancelled")
                }
            }
        }
        (job.status as? AudioStatus.Done)?.let { AudioPlayerBar(Uri.parse(it.entry.uri), it.entry.peaks, Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
