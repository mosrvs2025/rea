package app.ultimate.pluck.ui

import app.ultimate.core.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.AppDetail
import app.ultimate.pluck.model.AppItem
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: PluckController, onExtract: (List<AppItem>) -> Unit) {
    val pkg by vm.detailPackage.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val item = apps.firstOrNull { it.packageName == pkg }
    BackHandler(onBack = vm::closeDetail)
    if (item == null) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.label, maxLines = 1) },
                navigationIcon = { IconButton(vm::closeDetail) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton({ openInPlayStore(context, item.packageName) }) { Icon(Icons.Rounded.Storefront, "Open in store") }
                    IconButton({ openAppSettings(context, item.packageName) }) { Icon(Icons.Rounded.Settings, "App settings") }
                    if (!item.isSystem) IconButton({ uninstallApp(context, item.packageName) }) { Icon(Icons.Rounded.Delete, "Uninstall") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ onExtract(listOf(item)) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Extract…")
                    }
                    FilledTonalButton({ openApp(context, item.packageName) }, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open")
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Header(item, onOpen = { openApp(context, item.packageName) }) }
            val d = detail
            if (d == null) {
                item { Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() } }
            } else {
                item { InfoCard(d) }
                item { SignatureCard(d) }
                item { PermissionsCard(d) }
                if (item.isSplit) item { SplitsCard(d) }
                item { TrackersCard(vm, item) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(item: AppItem, onOpen: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(item.packageName, size = 84.dp)
            Spacer(Modifier.height(12.dp))
            Text(item.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
            Text(
                item.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { copyText(context, "Package name", item.packageName) },
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                SuggestionChip({}, { Text("v${item.versionName}") })
                SuggestionChip({}, { Text(formatBytes(item.apkBytes)) })
                SuggestionChip({}, { Text(item.source.label) })
                if (item.isSplit) SuggestionChip({}, { Text("${item.apkPaths.size} APKs") })
                if (!item.enabled) SuggestionChip({}, { Text("Disabled") })
                AssistChip(onOpen, { Text("Open") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)) })
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Fact(label: String, value: String) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Text(label, Modifier.weight(0.9f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
}

@Composable
private fun InfoCard(d: AppDetail) {
    val i = d.item
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    Section("Details") {
        Fact("Version", "${i.versionName} (${i.versionCode})")
        Fact("Package size", formatBytes(i.apkBytes) + if (i.isSplit) " · ${i.apkPaths.size} APKs" else "")
        Fact("Installed", fmt.format(Date(i.installTime)))
        Fact("Updated", fmt.format(Date(i.updateTime)))
        Fact("Installed from", i.source.label + (i.installer?.let { "\n$it" } ?: ""))
        Fact("Minimum Android", sdkText(i.minSdk))
        Fact("Built for", sdkText(i.targetSdk))
        Fact("Native code", d.abis.joinToString(", ").ifEmpty { "None" })
        Fact("Components", listOf("activities" to d.activities, "services" to d.services, "receivers" to d.receivers, "providers" to d.providers)
            .joinToString("\n") { (n, c) -> "${c ?: "?"} $n" })
        Fact("User ID", d.uid.toString())
    }
}

@Composable
private fun SignatureCard(d: AppDetail) {
    val context = LocalContext.current
    Section("Signing certificate") {
        if (d.signatures.isEmpty()) {
            Text("No signature found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            d.signatures.forEach { sha ->
                Text("SHA-256 (tap to copy)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    colonHex(sha), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { copyText(context, "SHA-256", sha) },
                )
            }
            Text(
                "Compare with the developer's published fingerprint to confirm an APK is genuine.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionsCard(d: AppDetail) {
    var open by remember { mutableStateOf(false) }
    val sensitive = d.permissions.count { it.dangerous }
    Section("Permissions") {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${d.permissions.size} requested · $sensitive sensitive",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand")
        }
        if (open) {
            d.permissions.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.short, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        if (p.dangerous) Text("Sensitive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(
                        if (p.granted) "Granted" else "Not granted", style = MaterialTheme.typography.labelMedium,
                        color = if (p.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitsCard(d: AppDetail) {
    Section("APK files") {
        d.apks.forEach { Fact(it.name, formatBytes(it.bytes)) }
        Text(
            "This app is split into several APKs. Extracting saves them together as one .apks bundle for a split-APK installer.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackersCard(vm: PluckController, item: AppItem) {
    val scans by vm.scans.collectAsStateWithLifecycle()
    val record = scans[item.packageName]?.takeIf { it.versionCode == item.versionCode && it.updateTime == item.updateTime }
    val catalog = TrackerScanner.catalog.associateBy { it.name }
    Section("Ads & trackers") {
        if (record == null) {
            FilledTonalButton({ vm.scanOne(item) }) { Text("Scan this app") }
        } else if (record.sdks.isEmpty()) {
            Text("No known ad, analytics or crash-reporting SDKs found.", style = MaterialTheme.typography.bodyMedium)
        } else {
            SdkKind.entries.forEach { kind ->
                val names = record.sdks.filter { catalog[it]?.kind == kind }
                if (names.isNotEmpty()) {
                    Text(kind.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { names.forEach { SuggestionChip({}, { Text(it) }) } }
                }
            }
        }
        Text(
            "Found by searching the app's code for known SDK names. Renamed SDKs can be missed, and a match shows the code is present, not in use.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.TextButton({ vm.closeDetail(); vm.openPage(Page.Trackers) }) { Text("Compare with all your apps") }
    }
}
