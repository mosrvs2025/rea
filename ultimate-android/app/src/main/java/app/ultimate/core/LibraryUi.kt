package app.ultimate.core

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single result row, shared by the Library, Home and each tool's own landing page. */
@Composable
fun LibraryRow(item: LibraryItem, tool: Tool, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ThumbView(item.thumb, tool, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(item.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text("${tool.title} · ${relativeTime(item.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThumbView(thumb: Thumb, tool: Tool, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when (thumb) {
            is Thumb.Frame -> {
                var bitmap by remember(thumb.path) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(thumb.path) { bitmap = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(thumb.path) }.getOrNull() } }
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is Thumb.AppIcon -> AppIcon(thumb.packageName, size = 72.dp)
            is Thumb.Waveform -> Waveform(thumb.peaks, Modifier.fillMaxSize().padding(8.dp), bars = 24.coerceAtMost(thumb.peaks.size.coerceAtLeast(1)))
            Thumb.Generic -> Icon(tool.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDetailSheet(item: LibraryItem, tool: Tool, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (item.mimeType.startsWith("audio/") && item.uri != null) {
                AudioPlayerBar(Uri.parse(item.uri), (item.thumb as? Thumb.Waveform)?.peaks.orEmpty())
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.details.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { copyText(context, label, value) }) {
                            Text(label, Modifier.weight(0.8f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
                        }
                    }
                    Text("Tap a value to copy it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    { item.uri?.let { share(context, it, item.mimeType) } }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                ) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Share") }
                Button({ confirm = true }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(8.dp)); Text("Remove")
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove ${item.title}?") },
            text = { Text("You can keep the file on your phone or delete it too.") },
            confirmButton = {
                TextButton({
                    confirm = false
                    scope.launch {
                        if (tool.remove(item.id, true)) onDismiss() else Toast.makeText(context, "Couldn't delete the file. Remove it from your files app instead.", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Delete file", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                Row {
                    TextButton({ confirm = false }) { Text("Cancel") }
                    TextButton({ confirm = false; scope.launch { tool.remove(item.id, false); onDismiss() } }) { Text("Keep file") }
                }
            },
        )
    }
}

private fun share(context: android.content.Context, uri: String, mime: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share"))
}

/** A tool's latest results for its landing page, so the lower half is useful instead of empty. Shows nothing until there is a result. */
@Composable
fun RecentResults(tool: Tool, heading: String, onOpenOwn: (LibraryItem) -> Unit, modifier: Modifier = Modifier, limit: Int = 4) {
    val items by tool.library.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    val nav = LocalShellNav.current
    if (items.isEmpty()) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (items.size > limit) TextButton({ nav?.openLibrary?.invoke() }) { Text("See all ${items.size}") }
        }
        items.sortedByDescending { it.createdAt }.take(limit).forEach { item ->
            LibraryRow(item, tool, onClick = { if (tool.opensOwnScreen(item)) onOpenOwn(item) else sheet = item })
        }
    }
    sheet?.let { LibraryDetailSheet(it, tool, onDismiss = { sheet = null }) }
}
