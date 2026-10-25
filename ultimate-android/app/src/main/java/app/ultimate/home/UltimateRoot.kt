package app.ultimate.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ultimate.UltimateApp

@Composable
fun UltimateRoot(app: UltimateApp) {
    val shell = app.shell
    val route by shell.route.collectAsStateWithLifecycle()
    val incoming by shell.incoming.collectAsStateWithLifecycle()

    when (val r = route) {
        is Route.InTool -> r.tool.Screen(onExit = shell::exitTool, launch = r.launch)
        else -> {
            BackHandler(enabled = r == Route.Library, onBack = shell::showHome)
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(r == Route.Home, shell::showHome, { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
                        NavigationBarItem(r == Route.Library, shell::showLibrary, { Icon(Icons.Rounded.VideoLibrary, null) }, label = { Text("Library") })
                    }
                },
            ) { pad ->
                if (r == Route.Library) LibraryScreen(app, pad) else HomeScreen(app, pad)
            }
        }
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
                            modifier = androidx.compose.ui.Modifier.clickable { shell.chooseForIncoming(tool) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(shell::dismissIncoming) { Text("Cancel") } },
        )
    }
}
