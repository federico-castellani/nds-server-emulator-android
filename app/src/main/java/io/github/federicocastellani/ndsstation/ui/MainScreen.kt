package io.github.federicocastellani.ndsstation.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.GameInfo
import kotlinx.coroutines.launch

private data class Tab(val title: String, val selected: ImageVector, val unselected: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onPlay: (GameInfo) -> Unit, onSignOut: (String?) -> Unit) {
    val app = NdsApp.instance
    val settings = app.settings
    val tabs = buildList {
        add(Tab("Libreria", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary))
        if (settings.isAdmin) add(Tab("Amministrazione", Icons.Filled.AdminPanelSettings, Icons.Outlined.AdminPanelSettings))
        add(Tab("Impostazioni", Icons.Filled.Settings, Icons.Outlined.Settings))
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    if (selected >= tabs.size) selected = 0
    val tab = tabs[selected]

    var accountMenu by remember { mutableStateOf(false) }
    var changePassword by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // A newer release: announced once, then marked on the Settings tab until installed.
    val update by app.updater.available.collectAsState()
    var showUpdate by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.updater.checkInBackground(settings.checkForUpdates) }
    LaunchedEffect(update?.version) {
        val info = update ?: return@LaunchedEffect
        val result = snackbar.showSnackbar("NDS Station ${info.version} è disponibile", "Aggiorna", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) showUpdate = true
    }
    update?.let { info -> if (showUpdate) UpdateDialog(info) { showUpdate = false } }

    if (changePassword) {
        ChangePasswordDialog(forced = false) { changed ->
            changePassword = false
            if (changed) scope.launch { snackbar.showSnackbar("Password aggiornata.") }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(tab.title) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { accountMenu = true }) { Icon(Icons.Outlined.AccountCircle, "Account") }
                    DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("${settings.username} · ${app.api.baseUrl}") },
                            onClick = {}, enabled = false,
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Cambia password") },
                            leadingIcon = { Icon(Icons.Outlined.Password, null) },
                            onClick = { accountMenu = false; changePassword = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Esci") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                            onClick = { accountMenu = false; onSignOut(null) },
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = index == selected,
                        onClick = { selected = index },
                        icon = {
                            val icon = if (index == selected) t.selected else t.unselected
                            if (t.title == "Impostazioni" && update != null) BadgedBox(badge = { Badge() }) { Icon(icon, null) }
                            else Icon(icon, null)
                        },
                        label = { Text(t.title) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab.title) {
            "Libreria" -> LibraryScreen(modifier, onPlay, onSessionExpired = { onSignOut("La sessione è scaduta. Accedi di nuovo.") })
            "Amministrazione" -> AdminScreen(modifier)
            else -> SettingsScreen(modifier)
        }
    }
}
