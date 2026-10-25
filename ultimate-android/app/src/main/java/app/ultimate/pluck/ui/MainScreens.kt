package app.ultimate.pluck.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.DisposableEffect
import app.ultimate.core.*
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.pluck.engine.SavedEntry
import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.AppItem
import app.ultimate.pluck.model.AppSource
import app.ultimate.pluck.model.Filter
import app.ultimate.pluck.model.Sort
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun PluckScreen(c: PluckController, onExit: () -> Unit) {
    val extraction by c.extraction.collectAsStateWithLifecycle()
    val detailPackage by c.detailPackage.collectAsStateWithLifecycle()
    val withConsent = rememberNotificationConsent()
    val onExtract: (List<AppItem>, Boolean) -> Unit = { items, share -> withConsent { c.extract(items, share) } }
    DisposableEffect(Unit) {
        c.reload() // an app may have been installed or uninstalled while Pluck was closed
        onDispose {}
    }
    when {
        extraction.jobs.isNotEmpty() -> ExtractScreen(c, extraction)
        detailPackage != null -> DetailScreen(c, onExtract)
        else -> MainScreen(c, onExtract, onExit)
    }
}

// ───────────────────────────── Main shell ────────────────────────

@Composable
private fun MainScreen(vm: PluckController, onExtract: (List<AppItem>, Boolean) -> Unit, onExit: () -> Unit) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val selected = apps.filter { it.packageName in selection }

    BackHandler {
        when {
            selection.isNotEmpty() -> vm.clearSelection()
            tab != Tab.Apps -> vm.tab.value = Tab.Apps
            else -> onExit()
        }
    }

    Scaffold(
        bottomBar = {
            if (selection.isNotEmpty() && tab == Tab.Apps) {
                SelectionBar(selected, onAll = vm::selectAllVisible, onClose = vm::clearSelection, onExtract = { onExtract(selected, false) }, onShare = { onExtract(selected, true) })
            } else {
                NavigationBar {
                    NavigationBarItem(tab == Tab.Apps, { vm.tab.value = Tab.Apps }, { Icon(Icons.Rounded.Apps, null) }, label = { Text("Apps") })
                    NavigationBarItem(tab == Tab.Insights, { vm.tab.value = Tab.Insights }, { Icon(Icons.Rounded.Insights, null) }, label = { Text("Insights") })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Apps -> AppsTab(vm, onExtract, onExit)
                Tab.Insights -> InsightsTab(vm, onExit)
            }
        }
    }
}

@Composable
private fun SelectionBar(selected: List<AppItem>, onAll: () -> Unit, onClose: () -> Unit, onExtract: () -> Unit, onShare: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Rounded.Close, "Clear selection") }
                Column(Modifier.weight(1f)) {
                    Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium)
                    Text(formatBytes(selected.sumOf { it.apkBytes }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onAll) { Text("Select all") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onExtract, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Extract") }
                FilledTonalButton(onShare, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Extract & share")
                }
            }
        }
    }
}

// ───────────────────────────── Apps tab ──────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppsTab(vm: PluckController, onExtract: (List<AppItem>, Boolean) -> Unit, onExit: () -> Unit) {
    val visible by vm.visible.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val userApps = apps.filter { !it.isSystem }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Apps", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${visible.size} shown · ${formatBytes(visible.sumOf { it.apkBytes })}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton({ moreMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Extract all user apps (${userApps.size})") },
                        onClick = { moreMenu = false; confirmAll = true },
                        enabled = userApps.isNotEmpty(),
                    )
                }
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search by name or package") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
            singleLine = true, shape = RoundedCornerShape(28.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f -> FilterChip(filter == f, { vm.filter.value = f }, { Text(f.label) }) }
            Box {
                FilterChip(false, { sortMenu = true }, { Text(sort.label) }, leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) })
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    Sort.entries.forEach { s -> DropdownMenuItem({ Text(s.label) }, { vm.sort.value = s; sortMenu = false }) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        when {
            loading && apps.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            visible.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("No apps match.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app, selected = app.packageName in selection,
                        onClick = { if (selection.isNotEmpty()) vm.toggleSelected(app.packageName) else vm.openDetail(app.packageName) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.toggleSelected(app.packageName) },
                    )
                }
            }
        }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Extract ${userApps.size} apps?") },
            text = { Text("About ${formatBytes(userApps.sumOf { it.apkBytes })} will be saved to Downloads/Pluck. This can take a few minutes, and runs in the background.") },
            confirmButton = { TextButton({ confirmAll = false; onExtract(userApps, false) }) { Text("Extract all") } },
            dismissButton = { TextButton({ confirmAll = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(app: AppItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "rowBackground")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AppIcon(app.packageName)
            val pop by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "check")
            if (pop > 0.01f) {
                Icon(
                    Icons.Rounded.CheckCircle, null,
                    Modifier.align(Alignment.BottomEnd).size(22.dp).graphicsLayer { scaleX = pop; scaleY = pop }
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                buildString {
                    append("v${app.versionName} · ${formatBytes(app.apkBytes)}")
                    if (app.isSplit) append(" · ${app.apkPaths.size} APKs")
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Updated ${relativeTime(app.updateTime)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ──────────────────────────── Insights tab ───────────────────────

@Composable
private fun InsightsTab(vm: PluckController, onExit: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val scans by vm.scans.collectAsStateWithLifecycle()
    val progress by vm.scanProgress.collectAsStateWithLifecycle()
    val user = apps.filter { !it.isSystem }
    val kinds = TrackerScanner.catalog.associate { it.name to it.kind }

    val scanned = user.mapNotNull { a -> scans[a.packageName]?.takeIf { it.versionCode == a.versionCode && it.updateTime == a.updateTime }?.let { a to it.sdks } }
    val withAds = scanned.filter { (_, sdks) -> sdks.any { kinds[it] == SdkKind.Ads } }
    val byCount = scanned.filter { it.second.isNotEmpty() }.sortedByDescending { it.second.size }.take(8)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column {
                    Text("Insights", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Everything here is worked out on your phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Your apps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${user.size}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "${formatBytes(user.sumOf { it.apkBytes })} of APKs · ${apps.size - user.size} system apps",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        item {
            InsightCard("Biggest apps") {
                val top = user.sortedByDescending { it.apkBytes }.take(8)
                val max = top.firstOrNull()?.apkBytes?.coerceAtLeast(1) ?: 1
                top.forEach { a -> BarRow(a, a.apkBytes.toFloat() / max, formatBytes(a.apkBytes), vm::openDetail) }
            }
        }
        item {
            InsightCard("Where your apps came from") {
                val counts = user.groupingBy { it.source }.eachCount()
                val colors = mapOf(
                    AppSource.PlayStore to MaterialTheme.colorScheme.primary, AppSource.OtherStore to MaterialTheme.colorScheme.tertiary,
                    AppSource.Sideloaded to MaterialTheme.colorScheme.error, AppSource.System to MaterialTheme.colorScheme.outline,
                )
                Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape)) {
                    AppSource.entries.filter { (counts[it] ?: 0) > 0 }.forEach { s ->
                        Box(Modifier.weight(counts.getValue(s).toFloat()).fillMaxHeight().background(colors.getValue(s)))
                    }
                }
                AppSource.entries.filter { (counts[it] ?: 0) > 0 }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(colors.getValue(s), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(s.label, Modifier.weight(1f))
                        Text("${counts.getValue(s)}", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            InsightCard("Asks for the most sensitive permissions") {
                user.sortedByDescending { it.dangerousCount }.take(5).filter { it.dangerousCount > 0 }.forEach { a ->
                    BarRow(a, a.dangerousCount / 15f, "${a.dangerousCount}", vm::openDetail, color = MaterialTheme.colorScheme.tertiary)
                }
                Text("Counts permissions Android treats as dangerous that the app requests, whether or not you granted them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            val old = user.filter { it.targetSdk < 30 }.sortedBy { it.targetSdk }
            InsightCard("Built for older Android") {
                if (old.isEmpty()) {
                    Text("All your apps target Android 11 or newer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${old.size} app${if (old.size == 1) "" else "s"} target Android 10 or older and skip newer privacy protections.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    old.take(5).forEach { a -> BarRow(a, 0f, "API ${a.targetSdk}", vm::openDetail, showBar = false) }
                }
            }
        }
        item {
            InsightCard("Ads & trackers") {
                val p = progress
                if (p != null) {
                    LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, Modifier.fillMaxWidth())
                    Text("Scanning ${p.done} of ${p.total}…", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(vm::cancelScan) { Text("Stop") }
                } else {
                    if (scanned.isNotEmpty()) {
                        Text("${withAds.size} of ${scanned.size} scanned apps contain an ad SDK.", style = MaterialTheme.typography.titleSmall)
                        byCount.forEach { (a, sdks) -> BarRow(a, sdks.size / 8f, "${sdks.size} SDKs", vm::openDetail, color = MaterialTheme.colorScheme.error) }
                    }
                    FilledTonalButton(vm::scanAll) { Text(if (scanned.isEmpty()) "Scan my apps" else "Scan new and updated apps") }
                    Text(
                        "Looks inside each app's code for known ad, analytics and crash-reporting SDKs. It can miss renamed SDKs, and finding one shows the code is there, not that it's in use.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarRow(
    app: AppItem, fraction: Float, value: String, onClick: (String) -> Unit,
    color: Color = MaterialTheme.colorScheme.primary, showBar: Boolean = true,
) {
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(fraction) { target = fraction.coerceIn(0.02f, 1f) }
    val animated by animateFloatAsState(target, tween(700), label = "bar")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onClick(app.packageName) }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.packageName, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (showBar) Box(Modifier.padding(top = 4.dp).fillMaxWidth(animated).height(8.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
    }
}
