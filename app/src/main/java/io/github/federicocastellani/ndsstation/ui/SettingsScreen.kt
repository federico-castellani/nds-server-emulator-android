package io.github.federicocastellani.ndsstation.ui

import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.AppTheme
import io.github.federicocastellani.ndsstation.data.FastForwardSound
import io.github.federicocastellani.ndsstation.data.ScreenLayout
import io.github.federicocastellani.ndsstation.emulation.Gamepad
import io.github.federicocastellani.ndsstation.emulation.NativeCore
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
private fun Header(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row(icon: ImageVector, title: String, summary: String? = null, trailing: @Composable (() -> Unit)? = null) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing,
    )
}

@Composable
private fun <T> Choice(icon: ImageVector, title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = value == selected, onClick = { onSelect(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
        },
    )
}

@Composable
private fun SliderRow(icon: ImageVector, title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Slider(value, onChange, valueRange = range) },
        trailingContent = { Text(label, Modifier.width(44.dp)) },
    )
}

@Composable
fun SettingsScreen(modifier: Modifier) {
    val app = NdsApp.instance
    val s = app.settings
    val context = LocalContext.current
    var cacheSize by remember { mutableStateOf(app.store.cacheSize()) }
    var confirmClear by remember { mutableStateOf(false) }
    var noLog by remember { mutableStateOf(false) }
    val update by app.updater.available.collectAsState()
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    var autoUpdate by remember { mutableStateOf(s.checkForUpdates) }
    val scope = rememberCoroutineScope()
    update?.let { info -> if (showUpdate) UpdateDialog(info) { showUpdate = false } }
    var primaryDns by remember { mutableStateOf(s.primaryDns) }
    var secondaryDns by remember { mutableStateOf(s.secondaryDns) }
    val network = remember {
        if (app.coreAvailable) {
            NativeCore.configureNetwork()
            NativeCore.netDescription()
        } else "Non disponibile"
    }

    fun isIPv4(text: String) = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(text.trim())
        ?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true

    if (confirmClear) {
        ConfirmDialog(
            "Svuotare la cache?",
            "Le ROM verranno scaricate di nuovo dal server alla prossima partita. Salvataggi e stati non vengono toccati.",
            "Svuota",
        ) { ok ->
            confirmClear = false
            if (ok) {
                app.store.clearCache()
                cacheSize = app.store.cacheSize()
            }
        }
    }
    if (noLog) MessageDialog("Nessun log", "Non c'è ancora nessuna partita online registrata.") { noLog = false }

    LazyColumn(modifier.fillMaxSize()) {
        item { Header("Aspetto") }
        item {
            Choice(Icons.Outlined.Palette, "Tema", listOf(AppTheme.System to "Sistema", AppTheme.Light to "Chiaro", AppTheme.Dark to "Scuro"), s.theme) { s.theme = it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) item {
            Row(Icons.Outlined.Wallpaper, "Colori dinamici", "Usa i colori del tuo sfondo (Material You)") {
                Switch(s.dynamicColor, { s.dynamicColor = it })
            }
        }

        item { Header("Schermo") }
        item {
            Choice(
                Icons.Outlined.ViewAgenda, "Disposizione schermi",
                listOf(ScreenLayout.Auto to "Automatica", ScreenLayout.Vertical to "Verticale", ScreenLayout.SideBySide to "Affiancati"),
                s.screenLayout,
            ) { s.screenLayout = it }
        }

        item { Header("Audio") }
        item { SliderRow(Icons.Outlined.VolumeUp, "Volume", s.volume, 0f..1f, "${(s.volume * 100).toInt()}%") { s.volume = it } }
        item {
            Choice(
                Icons.Outlined.FastForward, "Avanti veloce",
                listOf(2f to "2×", 3f to "3×", 4f to "4×", 0f to "Max"), s.fastForwardSpeed,
            ) { s.fastForwardSpeed = it }
        }
        item {
            Choice(
                Icons.Outlined.GraphicEq, "Audio in avanti veloce",
                listOf(FastForwardSound.NormalPitch to "Normale", FastForwardSound.SpedUp to "Accelerato", FastForwardSound.Mute to "Muto"),
                s.fastForwardSound,
            ) { s.fastForwardSound = it }
        }

        item { Header("Controlli") }
        item { SliderRow(Icons.Outlined.Opacity, "Trasparenza dei tasti", s.controlsOpacity, 0.15f..1f, "${(s.controlsOpacity * 100).toInt()}%") { s.controlsOpacity = it } }
        item { SliderRow(Icons.Outlined.PhotoSizeSelectLarge, "Dimensione dei tasti", s.controlsScale, 0.7f..1.4f, "${(s.controlsScale * 100).toInt()}%") { s.controlsScale = it } }
        item { Row(Icons.Outlined.Vibration, "Vibrazione", "Breve vibrazione alla pressione dei tasti") { Switch(s.haptics, { s.haptics = it }) } }
        item {
            Row(
                Icons.Outlined.Gamepad, "Nascondi i tasti con un controller",
                if (Gamepad.connected) "Controller rilevato" else "Collega un controller Bluetooth o USB",
            ) { Switch(s.hideControlsWithGamepad, { s.hideControlsWithGamepad = it }) }
        }

        item { Header("Prestazioni") }
        item {
            Row(Icons.Outlined.Bolt, "Ricompilatore JIT", "Molto più veloce; disattivalo solo se un gioco si blocca") {
                Switch(s.useJit, { s.useJit = it })
            }
        }

        item { Header("Online (Nintendo Wi-Fi Connection)") }
        item { Row(Icons.Outlined.Wifi, "Connessione", network) }
        item {
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Dns, null) },
                headlineContent = { Text("Server DNS") },
                supportingContent = {
                    Column {
                        Text("Server WFC alternativo usato dai giochi online")
                        OutlinedTextField(
                            primaryDns, { primaryDns = it }, label = { Text("Primario") }, singleLine = true,
                            isError = !isIPv4(primaryDns),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .onFocusChanged { if (!it.isFocused && isIPv4(primaryDns)) s.primaryDns = primaryDns.trim() },
                        )
                        OutlinedTextField(
                            secondaryDns, { secondaryDns = it }, label = { Text("Secondario") }, singleLine = true,
                            isError = !isIPv4(secondaryDns),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                .onFocusChanged { if (!it.isFocused && isIPv4(secondaryDns)) s.secondaryDns = secondaryDns.trim() },
                        )
                    }
                },
            )
        }
        item {
            Row(Icons.Outlined.Share, "Log di rete", "Il riepilogo dell'ultima partita online, utile se lotte o scambi si interrompono") {
                TextButton(onClick = {
                    val log = app.store.latestNetworkLog()
                    if (log == null) noLog = true
                    else context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, log.name)
                                .putExtra(Intent.EXTRA_TEXT, log.readText()),
                            "Condividi il log di rete",
                        ),
                    )
                }) { Text("Condividi") }
            }
        }

        item { Header("Archiviazione") }
        item {
            Row(
                Icons.Outlined.Storage, "ROM in cache",
                if (cacheSize == 0L) "Nessuna ROM salvata sul dispositivo" else "${Formatter.formatShortFileSize(context, cacheSize)} per giocare più velocemente e offline",
            ) { TextButton(onClick = { confirmClear = true }, enabled = cacheSize > 0) { Text("Svuota") } }
        }

        item { Header("Aggiornamenti") }
        item {
            Row(
                Icons.Outlined.SystemUpdate, "Versione ${app.updater.currentVersion}",
                updateMessage ?: update?.let { "Disponibile la versione ${it.version}" } ?: "Le nuove versioni arrivano da GitHub",
            ) {
                TextButton(enabled = !checking, onClick = {
                    if (update != null) showUpdate = true
                    else scope.launch {
                        checking = true
                        updateMessage = try {
                            if (app.updater.check() == null) "Hai già l'ultima versione." else null.also { showUpdate = true }
                        } catch (e: IOException) {
                            "Controllo non riuscito: verifica la connessione a Internet."
                        }
                        checking = false
                    }
                }) { Text(if (update != null) "Aggiorna" else if (checking) "Controllo…" else "Controlla") }
            }
        }
        item {
            Row(Icons.Outlined.Update, "Controlla all'avvio", "Cerca una nuova versione ogni volta che apri l'app") {
                Switch(autoUpdate, { autoUpdate = it; s.checkForUpdates = it })
            }
        }

        item { Header("Informazioni") }
        item {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            Row(
                Icons.Outlined.Info, "NDS Station $version",
                "Emulazione basata su melonDS (GPLv3). " + (if (app.coreAvailable) "Core di emulazione caricato." else app.coreError ?: ""),
            )
        }
    }
}
