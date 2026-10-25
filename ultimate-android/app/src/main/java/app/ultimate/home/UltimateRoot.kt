package app.ultimate.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp
import app.ultimate.core.LocalShellNav
import app.ultimate.core.ShellNav
import app.ultimate.core.ToolLaunch
import kotlinx.coroutines.flow.combine

@Composable
fun UltimateRoot(app: UltimateApp) {
    val shell = app.shell
    val route by shell.route.collectAsStateWithLifecycle()
    val incoming by shell.incoming.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val standalone = app.tools.size == 1
    val nav = remember { ShellNav(standalone, openLibrary = shell::showLibraryForCurrentTool, openActivity = shell::showActivity) }

    CompositionLocalProvider(LocalShellNav provides nav) {
        if (standalone) StandaloneRoot(app, route, onExitApp = { (context as? Activity)?.finish() }) else SuiteRoot(app, route)
    }

    incoming?.let { pending ->
        AlertDialog(
            onDismissRequest = shell::dismissIncoming,
            title = { Text(if (pending.uris.size == 1) "What should happen to this video?" else "What should happen to these ${pending.uris.size} videos?") },
            text = {
                Column {
                    pending.candidates.forEach { tool ->
                        ListItem(
                            headlineContent = { Text(tool.title) },
                            supportingContent = { Text(tool.tagline) },
                            leadingContent = { Icon(tool.icon, null) },
                            modifier = Modifier.clickable { shell.chooseForIncoming(tool) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(shell::dismissIncoming) { Text("Cancel") } },
        )
    }
}

/** The full app: Home, Library and Activity tabs, with tools opening full screen. */
@Composable
private fun SuiteRoot(app: UltimateApp, route: Route) {
    val shell = app.shell
    val active by remember { combine(app.work.sources.map { it.work }) { s -> s.any { it.active } } }.collectAsStateWithLifecycle(false)
    when (route) {
        is Route.InTool -> route.tool.Screen(onExit = shell::exitTool, launch = route.launch)
        else -> {
            BackHandler(enabled = route != Route.Home, onBack = shell::showHome)
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(route == Route.Home, shell::showHome, { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
                        NavigationBarItem(route == Route.Library, { shell.showLibrary() }, { Icon(Icons.Rounded.VideoLibrary, null) }, label = { Text("Library") })
                        NavigationBarItem(
                            route == Route.Activity, shell::showActivity,
                            { BadgedBox(badge = { if (active) Badge() }) { Icon(Icons.Rounded.Terminal, null) } },
                            label = { Text("Activity") },
                        )
                    }
                },
            ) { pad ->
                when (route) {
                    Route.Library -> LibraryScreen(app, pad)
                    Route.Activity -> ActivityScreen(app, pad)
                    else -> HomeScreen(app, pad)
                }
            }
        }
    }
}

/** A build with one tool: the tool is the home screen; Library and Activity open from its top bar. */
@Composable
private fun StandaloneRoot(app: UltimateApp, route: Route, onExitApp: () -> Unit) {
    val shell = app.shell
    val tool = app.tools.single()
    when (route) {
        Route.Library, Route.Activity -> {
            BackHandler(onBack = shell::showHome)
            androidx.compose.material3.Scaffold(topBar = { PageBar(if (route == Route.Library) "Library" else "Activity", shell::showHome) }) { pad ->
                // Standalone bars already give a back arrow, so the screens drop their own top padding duplication.
                val inner = PaddingValues(top = pad.calculateTopPadding())
                if (route == Route.Library) LibraryScreen(app, inner) else ActivityScreen(app, inner)
            }
        }
        is Route.InTool -> tool.Screen(onExit = shell::exitTool, launch = route.launch)
        else -> tool.Screen(onExit = onExitApp, launch = remember { ToolLaunch() })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(title: String, onBack: () -> Unit) {
    androidx.compose.material3.TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            androidx.compose.material3.IconButton(onBack) {
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
        },
    )
}
