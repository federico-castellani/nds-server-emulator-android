package io.github.federicocastellani.ndsstation.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.UpdateInfo
import io.github.federicocastellani.ndsstation.data.UpdateState
import kotlinx.coroutines.launch

/** What's new in a release, and the download + install of it. */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val updater = NdsApp.instance.updater
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsState()
    // The player may grant "install unknown apps" in the system settings and come back.
    var canInstall by remember { mutableStateOf(updater.canInstall()) }
    LifecycleResumeEffect(Unit) {
        canInstall = updater.canInstall()
        onPauseOrDispose {}
    }
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing

    AlertDialog(
        onDismissRequest = { if (!busy) { updater.dismissError(); onDismiss() } },
        title = { Text("NDS Station ${info.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    plainNotes(info.notes),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                )
                when (val s = state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Text("Download in corso… ${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    UpdateState.Installing -> Text("Conferma l'installazione nella finestra di Android.", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    UpdateState.Idle -> Text(
                        if (canInstall) "Versione in uso: ${updater.currentVersion}. Partite, salvataggi e impostazioni restano come sono."
                        else "Per aggiornare, consenti a NDS Station di installare app (una sola volta), poi torna qui.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (!canInstall) context.startActivity(updater.installPermissionIntent())
                    else scope.launch { updater.downloadAndInstall(info) }
                },
            ) { Text(if (canInstall) "Aggiorna" else "Consenti") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                if (state is UpdateState.Failed) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl)))
                updater.dismissError()
                onDismiss()
            }) { Text(if (state is UpdateState.Failed) "Apri la pagina" else "Più tardi") }
        },
    )
}

/** Release notes are Markdown; shown as plain text. */
private fun plainNotes(markdown: String): String {
    val text = markdown.replace("\r\n", "\n")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .trim()
    return text.ifEmpty { "Nessuna nota di rilascio." }
}
