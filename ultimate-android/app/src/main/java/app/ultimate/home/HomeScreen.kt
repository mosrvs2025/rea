package app.ultimate.home

import app.ultimate.insights.InsightsSheet
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import kotlinx.coroutines.flow.map
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LibraryRow
import app.ultimate.core.Tool
import app.ultimate.core.ToolLaunch
import app.ultimate.core.formatBytes
import kotlinx.coroutines.flow.combine

@Composable
fun HomeScreen(app: UltimateApp, pad: PaddingValues) {
    val shell = app.shell
    val libraries = remember { combine(app.tools.map { it.library }) { it.toList().flatten() } }
    val all by libraries.collectAsStateWithLifecycle(emptyList())
    val saved = all.sumOf { it.savedBytes }
    val running by remember {
        combine(app.work.sources.map { src -> src.work.map { src to it } }) { it.toList().filter { (_, w) -> w.active } }
    }.collectAsStateWithLifecycle(emptyList())
    val recent = all.sortedByDescending { it.createdAt }.take(3)
    var showInsights by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showInsights) InsightsSheet(app) { showInsights = false }

    // Two columns: every tool, the space-saved hero and the latest results fit on one screen. An odd last tool takes the full row.
    LazyVerticalGrid(
        GridCells.Fixed(2), Modifier.padding(pad), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ultimate", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Hero(saved, all.size)
            }
        }
        if (running.isNotEmpty()) items(running, key = { "run-${it.first.workId}" }, span = { GridItemSpan(maxLineSpan) }) { (source, snap) ->
            RunningBanner(source, snap, app.tools.firstOrNull { it.id == source.workId }, onView = { app.tools.firstOrNull { it.id == source.workId }?.let(shell::open) })
        }
        itemsIndexed(app.tools, key = { _, t -> t.id }, span = { i, _ -> if (app.tools.size % 2 == 1 && i == app.tools.lastIndex) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { _, tool ->
            ToolTile(
                tool, wide = app.tools.size % 2 == 1 && tool === app.tools.last(),
                active = running.any { it.first.workId == tool.id }, stop = app.work.sources.firstOrNull { it.workId == tool.id }?.takeIf { src -> running.any { it.first === src } },
                onLibrary = { shell.showLibraryOf(tool.id) }, onClick = { shell.open(tool) },
            )
        }
        if (recent.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    TextButton({ shell.showLibrary() }) { Text("See all") }
                }
            }
            items(recent, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { item ->
                val tool = app.tools.first { it.id == item.toolId }
                LibraryRow(item, tool, onClick = { openItem(app, tool, item) })
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { ShapeCard { showInsights = true } }
    }
}

/** Anything still working in the background, with the one-tap way to end it, so nothing is left running by accident. */
@Composable
private fun RunningBanner(source: WorkSource, snap: WorkSnapshot, tool: Tool?, onView: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "run").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).clickable(onClick = onView).padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = pulse)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${tool?.title ?: source.workTitle} is running", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                snap.title + (snap.percent?.let { " · $it%" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(source::cancelWork) { Text(source.cancelLabel) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolTile(tool: Tool, wide: Boolean, active: Boolean, stop: WorkSource?, onLibrary: () -> Unit, onClick: () -> Unit) {
    val headline by tool.headline.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
            ),
        ) {
            val icon = @Composable {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(tool.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            val text = @Composable { mod: Modifier ->
                Column(mod) {
                    Text(tool.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(headline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (wide) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    icon(); Spacer(Modifier.width(14.dp)); text(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            } else {
                Column(Modifier.padding(14.dp).heightIn(min = 92.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    icon(); Spacer(Modifier.height(10.dp)); text(Modifier)
                }
            }
        }
        // Long-press for shortcuts, so common jumps are one gesture instead of a trip through screens.
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Open ${tool.title}") }, { menu = false; onClick() }, leadingIcon = { Icon(tool.icon, null) })
            DropdownMenuItem({ Text("Its results in Library") }, { menu = false; onLibrary() }, leadingIcon = { Icon(Icons.Rounded.VideoLibrary, null) })
            stop?.let { DropdownMenuItem({ Text(it.cancelLabel) }, { menu = false; it.cancelWork() }, leadingIcon = { Icon(Icons.Rounded.Stop, null) }) }
        }
    }
}

/** Space saved so far, as a small counting-up chip beside the title. */
@Composable
private fun Hero(saved: Long, count: Int) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(saved) { shown.animateTo(saved.toFloat(), tween(1400, easing = FastOutSlowInEasing)) }
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(formatBytes(shown.value.toLong()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            if (count == 0) "space saved" else "saved · $count made",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

internal fun openItem(app: UltimateApp, tool: app.ultimate.core.Tool, item: app.ultimate.core.LibraryItem) {
    if (tool.opensOwnScreen(item)) app.shell.open(tool, ToolLaunch(openItemId = item.id)) else app.shell.showLibrary()
}

/** The way back to whoever builds Ultimate: a report of what is used and what is missing. */
@Composable
private fun ShapeCard(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.secondaryContainer).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(androidx.compose.material.icons.Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Help shape Ultimate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("Export a usage report to plan the next tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
