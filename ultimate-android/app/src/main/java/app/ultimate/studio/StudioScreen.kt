package app.ultimate.studio

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.core.Terminal
import app.ultimate.core.formatBytes
import app.ultimate.core.formatDuration
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val StudioColors = darkColorScheme(primary = Color(0xFFFFC46B), onPrimary = Color(0xFF302100), secondary = Color(0xFFA5D5C9),
    background = Color(0xFF101214), surface = Color(0xFF181C20), surfaceVariant = Color(0xFF272E34), onBackground = Color(0xFFE9ECEF), onSurface = Color(0xFFE9ECEF))
private enum class StudioPanel(val label: String) { Edit("Clip"), Look("Look"), Text("Titles"), Music("Music"), Export("Export") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(manager: StudioManager, onExit: () -> Unit) {
    val project by manager.project.collectAsStateWithLifecycle()
    val selectedId by manager.selected.collectAsStateWithLifecycle()
    val loading by manager.loading.collectAsStateWithLifecycle()
    val work by manager.work.collectAsStateWithLifecycle()
    val error by manager.error.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    val projects by manager.projects.collectAsStateWithLifecycle()
    val exports by manager.exports.collectAsStateWithLifecycle()
    val revision by manager.revision.collectAsStateWithLifecycle()
    val log by manager.log.lines.collectAsStateWithLifecycle()
    val clip = project.clips.firstOrNull { it.id == selectedId }
    var panel by remember { mutableStateOf(StudioPanel.Edit) }
    var playing by remember { mutableStateOf(false) }
    var localMs by remember { mutableLongStateOf(0) }
    var seek by remember { mutableStateOf<Long?>(null) }
    var showProjects by remember { mutableStateOf(false) }
    var showExports by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val videos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { manager.addVideos(it) }
    val music = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(manager::addMusic) }
    fun change(f: (StudioClip) -> StudioClip) { playing = false; clip?.let { manager.updateClip(it.id, f) } }
    LaunchedEffect(revision) { playing = false }
    LaunchedEffect(work.active) { if (work.active) panel = StudioPanel.Export }
    LaunchedEffect(selectedId, work.active) { localMs = 0; if (work.active) playing = false }
    BackHandler { playing = false; onExit() }
    MaterialTheme(colorScheme = StudioColors) {
        Scaffold(topBar = {
            TopAppBar(title = { Column { Text("STUDIO", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black); Text(status, style = MaterialTheme.typography.labelSmall) } },
                navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { playing = false; manager.undo() }, enabled = manager.canUndo && !work.active && !loading) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                    IconButton(onClick = { playing = false; manager.redo() }, enabled = manager.canRedo && !work.active && !loading) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
                    IconButton(onClick = { playing = false; showProjects = true }) { Icon(Icons.Rounded.FolderOpen, "Projects") }
                    FilledTonalButton(onClick = { playing = false; panel = StudioPanel.Export }, enabled = project.clips.isNotEmpty()) { Text("Export") }
                })
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        val wide = maxWidth > 700.dp
                        val preview: @Composable (Modifier) -> Unit = { modifier ->
                            Column(modifier) {
                                if (!work.active) StudioPreview(project, clip, playing, seek, { seek = null }, { localMs = it }, {
                                    if (playing) {
                                        val next = project.clips.indexOfFirst { it.id == selectedId } + 1
                                        if (next in project.clips.indices) manager.selected.value = project.clips[next].id else playing = false
                                    }
                                }, { playing = false }, { manager.error.value = it; playing = false },
                                    Modifier.fillMaxWidth().height(if (wide) 340.dp else 260.dp).clip(RoundedCornerShape(18.dp)))
                                else Box(Modifier.fillMaxWidth().height(if (wide) 340.dp else 260.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        CircularProgressIndicator()
                                        Text("Rendering your film · ${work.percent ?: 0}%")
                                        Text("Preview released for export", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { playing = !playing }, enabled = clip != null && !work.active && !loading) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play") }
                                    Text("${clock(Timeline.offset(project, selectedId.orEmpty()) + localMs)} / ${clock(project.durationMs)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { showExports = true }) { Text("Exports") }
                                }
                                if (clip != null) Slider(value = (Timeline.offset(project, clip.id) + localMs).toFloat().coerceIn(0f, project.durationMs.toFloat().coerceAtLeast(1f)), onValueChange = { value ->
                                    playing = false
                                    var remaining = value.toLong()
                                    val target = project.clips.firstOrNull { candidate ->
                                        if (remaining <= candidate.durationMs) true else { remaining -= candidate.durationMs; false }
                                    } ?: project.clips.last()
                                    manager.selected.value = target.id
                                    seek = remaining.coerceIn(0, target.durationMs)
                                    localMs = seek ?: 0
                                }, valueRange = 0f..project.durationMs.toFloat().coerceAtLeast(1f), enabled = !work.active)
                            }
                        }
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            preview(Modifier.weight(1.5f)); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProjectHeader(project, loading || work.active, manager)
                                Text("A canvas for your next idea.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Text("Pick a clip below. Shape the picture, add your words, then score it with music.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Button(onClick = { playing = false; videos.launch(arrayOf("video/*")) }, enabled = !loading && !work.active) { Icon(Icons.Rounded.Add, null); Text("Add videos") }
                            }
                        } else Column {
                            ProjectHeader(project, loading || work.active, manager); Spacer(Modifier.height(12.dp)); preview(Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("YOUR SEQUENCE", style = MaterialTheme.typography.labelLarge); Text("${project.clips.size} clips · ${project.shape.label}", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { playing = false; videos.launch(arrayOf("video/*")) }, enabled = !loading && !work.active) { Icon(Icons.Rounded.Add, null); Text("Add videos") }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(project.clips, key = { it.id }) { c -> ClipTile(c, c.id == selectedId) { if (!work.active && !loading) { playing = false; manager.selected.value = c.id } } }
                        if (project.clips.isEmpty()) item { OutlinedCard(onClick = { videos.launch(arrayOf("video/*")) }, modifier = Modifier.width(260.dp).height(110.dp)) {
                            Column(Modifier.padding(18.dp)) { Text("Bring your footage.", fontWeight = FontWeight.Bold); Text("Pick one video or build a sequence.") }
                        } }
                    }
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)); Text("Preparing your media…", Modifier.padding(horizontal = 16.dp)) }
                if (project.clips.isNotEmpty()) item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioPanel.entries.forEach { tab -> FilterChip(panel == tab, { playing = false; panel = tab }, { Text(tab.label) }) }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (panel) {
                            StudioPanel.Edit -> if (clip != null) ClipEditor(clip, localMs, project, loading || work.active, manager, ::change)
                            StudioPanel.Look -> if (clip != null) {
                                SectionTitle("Give it a look", "The same effects are used in preview and export.")
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Look.entries.forEach { look -> FilterChip(clip.look == look, { change { it.copy(look = look) } }, { Text(look.label) }, enabled = !work.active) }
                                }
                                ValueSlider("Exposure", clip.brightness, -0.5f..0.5f, !work.active) { value -> change { it.copy(brightness = value) } }
                                Row(verticalAlignment = Alignment.CenterVertically) { Text("Fill canvas", Modifier.weight(1f)); Switch(project.fillCanvas, { value -> manager.edit { it.copy(fillCanvas = value) } }, enabled = !work.active) }
                                Text("Fit preserves the full frame. Fill crops the edges to match your canvas.", style = MaterialTheme.typography.bodySmall)
                            }
                            StudioPanel.Text -> if (clip != null) {
                                SectionTitle("Say something.", "Titles stay with this clip. Split a clip to time your next lyric or caption.")
                                OutlinedTextField(clip.title, { value -> change { it.copy(title = value.take(200)) } }, label = { Text("Title or lyric") }, minLines = 2, modifier = Modifier.fillMaxWidth(), enabled = !work.active)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Center", "Top", "Bottom").forEachIndexed { index, label ->
                                    FilterChip(clip.titlePosition == index, { change { it.copy(titlePosition = index) } }, { Text(label) }, enabled = !work.active)
                                } }
                            }
                            StudioPanel.Music -> {
                                SectionTitle("Set the rhythm", "Music loops to fit your film. Beat guides use the BPM you enter, not automatic detection.")
                                Button(onClick = { music.launch(arrayOf("audio/*", "video/*")) }, enabled = !work.active && !loading) { Text(if (project.music == null) "Add soundtrack" else "Replace soundtrack") }
                                project.music?.let { m ->
                                    Text(m.name, fontWeight = FontWeight.SemiBold)
                                    ValueSlider("Music volume", m.volume, 0f..1f, !work.active) { v -> manager.edit { it.copy(music = m.copy(volume = v)) } }
                                    ValueSlider("Start music at ${clock(m.startMs)}", m.startMs.toFloat(), 0f..(m.durationMs - 100).toFloat().coerceAtLeast(1f), !work.active) { v -> manager.edit { it.copy(music = m.copy(startMs = v.toLong())) } }
                                    TextButton(onClick = { manager.edit { it.copy(music = null) } }, enabled = !work.active) { Text("Remove soundtrack") }
                                }
                                var bpm by remember(project.bpm) { mutableStateOf(project.bpm.toString()) }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(bpm, { bpm = it.filter(Char::isDigit).take(3) }, label = { Text("BPM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !work.active)
                                    Button(onClick = { bpm.toIntOrNull()?.takeIf { it in 30..300 }?.let { value -> manager.edit { it.copy(bpm = value) } } }, enabled = !work.active && bpm.toIntOrNull() in 30..300) { Text("Set grid") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { manager.edit { Timeline.cutToBeats(it, 4) } }, enabled = !work.active) { Text("Cut clips to 4 beats") }
                                    TextButton(onClick = { manager.edit { it.copy(beatOffsetMs = Timeline.offset(project, selectedId.orEmpty()) + localMs) } }, enabled = !work.active) { Text("Beat starts here") }
                                }
                                if (clip != null) TextButton(onClick = {
                                    val global = Timeline.offset(project, clip.id) + localMs
                                    val at = Timeline.nearestBeat(global, project.bpm, project.beatOffsetMs) - Timeline.offset(project, clip.id)
                                    manager.split(clip.id, at)
                                }, enabled = !work.active) { Text("Split at nearest beat") }
                            }
                            StudioPanel.Export -> {
                                SectionTitle("Make it a film.", "No added watermark. H.264 video + AAC audio, saved to Movies/Ultimate Studio.")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(720, 1080).forEach { side -> FilterChip(project.shortSide == side, { manager.edit { it.copy(shortSide = side) } }, { Text(if (side == 720) "Compact · 720" else "HD · 1080") }, enabled = !work.active) }
                                }
                                Text("${project.width} × ${project.height} · ${clock(project.durationMs)} · ${project.clips.size} clips")
                                if (work.active) {
                                    LinearProgressIndicator(progress = { (work.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                                    Text("Rendering · ${work.percent ?: 0}%")
                                    OutlinedButton(onClick = manager::cancelWork) { Text("Cancel export") }
                                } else Button(onClick = { playing = false; manager.export() }, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = project.clips.isNotEmpty() && !loading) { Text("Export MP4") }
                                TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide render details" else "Render details") }
                                if (showDetails) Terminal(log, work.active, startExpanded = true)
                                Text("Preview may have brief gaps at clip boundaries. The exported timeline is rendered as one sequence. HDR sources are tone-mapped to SDR.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                error?.let { message -> item {
                    Card(Modifier.padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) { Text(message); Row {
                            TextButton(onClick = { manager.error.value = null }) { Text("Dismiss") }
                            TextButton(onClick = manager::retrySave) { Text("Save again") }
                        } }
                    }
                } }
            }
        }
        if (showProjects) ModalBottomSheet(onDismissRequest = { showProjects = false }) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Your projects", style = MaterialTheme.typography.headlineMedium); Button(onClick = { playing = false; manager.newProject(); showProjects = false }, enabled = !work.active && !loading) { Text("New project") } }
                items(projects, key = { it.id }) { p -> ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.clips.size} clips · ${clock(p.durationMs)}") },
                    modifier = Modifier.clickable(enabled = !work.active && !loading) { playing = false; manager.open(p); showProjects = false }) }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
        if (showExports) ModalBottomSheet(onDismissRequest = { showExports = false }) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Finished films", style = MaterialTheme.typography.headlineMedium); if (exports.isEmpty()) Text("Your exported films will appear here and in Ultimate’s Library.") }
                items(exports, key = { it.id }) { e ->
                    ListItem(headlineContent = { Text(e.name) }, supportingContent = { Text("${formatBytes(e.bytes)} · ${e.width}×${e.height}") }, trailingContent = {
                        IconButton(onClick = {
                            try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(e.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share film")) }
                            catch (ex: Exception) { manager.error.value = ex.message }
                        }) { Icon(Icons.Rounded.Share, "Share film") }
                    })
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

@Composable private fun ProjectHeader(p: StudioProject, disabled: Boolean, manager: StudioManager) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(p.name, { name -> manager.edit { it.copy(name = name.take(80)) } }, singleLine = true, label = { Text("Project") }, enabled = !disabled, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CanvasShape.entries.forEach { shape -> FilterChip(p.shape == shape, { manager.edit { it.copy(shape = shape) } }, { Text(shape.label) }, enabled = !disabled) } }
    }
}
@Composable private fun SectionTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, commit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Slider(draft.coerceIn(range.start, range.endInclusive), { draft = it }, onValueChangeFinished = { commit(draft) }, valueRange = range, enabled = enabled)
}
@Composable private fun ClipEditor(c: StudioClip, at: Long, p: StudioProject, disabled: Boolean, manager: StudioManager, change: ((StudioClip) -> StudioClip) -> Unit) {
    SectionTitle(c.name, "Trim, split and arrange. Your original file stays untouched.")
    var trim by remember(c.startMs, c.endMs) { mutableStateOf(c.startMs.toFloat()..c.endMs.toFloat()) }
    Text("${clock(trim.start.toLong())} → ${clock(trim.endInclusive.toLong())} · source ${clock(c.sourceMs)}", style = MaterialTheme.typography.labelLarge)
    RangeSlider(trim, { range -> if (range.endInclusive - range.start >= Timeline.MIN_CLIP_MS) trim = range }, valueRange = 0f..c.sourceMs.toFloat(),
        onValueChangeFinished = { change { it.copy(startMs = trim.start.toLong(), endMs = trim.endInclusive.toLong()) } }, enabled = !disabled)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.5f, 1f, 1.5f, 2f).forEach { speed -> FilterChip(c.speed == speed, { change { it.copy(speed = speed) } }, { Text("${speed}×") }, enabled = !disabled) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { manager.split(c.id, at) }, enabled = !disabled) { Text("Split here") }
        OutlinedButton(onClick = { manager.edit { it.copy(clips = it.clips.flatMap { clip -> if (clip.id == c.id) listOf(clip, clip.copy(id = java.util.UUID.randomUUID().toString())) else listOf(clip) }) } }, enabled = !disabled) { Text("Duplicate") }
        TextButton(onClick = { change { it.copy(rotation = (it.rotation + 90) % 360) } }, enabled = !disabled) { Text("Rotate") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { manager.move(c.id, -1) }, enabled = !disabled && p.clips.first().id != c.id) { Text("Move left") }
        TextButton(onClick = { manager.move(c.id, 1) }, enabled = !disabled && p.clips.last().id != c.id) { Text("Move right") }
        TextButton(onClick = { manager.edit { it.copy(clips = it.clips.filterNot { clip -> clip.id == c.id }) } }, enabled = !disabled) { Text("Remove") }
    }
    ValueSlider("Clip volume · ${(c.volume * 100).toInt()}%", c.volume, 0f..1f, !disabled) { v -> change { it.copy(volume = v) } }
}
@Composable private fun ClipTile(c: StudioClip, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var frame by remember(c.uri, c.startMs) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(c.uri, c.startMs) {
        frame = withContext(Dispatchers.IO) { runCatching {
            val retriever = MediaMetadataRetriever()
            try { retriever.setDataSource(context, Uri.parse(c.uri)); retriever.getScaledFrameAtTime(c.startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 120) }
            finally { retriever.release() }
        }.getOrNull() }
    }
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(12.dp)).border(if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(70.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.BottomEnd) {
            frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(clock(c.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(4.dp))
        }
        Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
    }
}
private fun clock(ms: Long): String = String.format(Locale.US, "%02d:%02d.%01d", ms / 60000, ms / 1000 % 60, ms % 1000 / 100)
