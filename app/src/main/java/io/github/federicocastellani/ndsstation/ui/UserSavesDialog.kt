package io.github.federicocastellani.ndsstation.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.AdminSave
import io.github.federicocastellani.ndsstation.data.AdminUser
import io.github.federicocastellani.ndsstation.data.ApiException
import io.github.federicocastellani.ndsstation.data.GameInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val MAX_SAVE_BYTES = 4L * 1024 * 1024   // the server's limit

// DeSmuME appends this to its exports; the server removes it, sizes are compared without it.
private val DESMUME_FOOTER_END = "|-DESMUME SAVE-|".toByteArray()
private const val DESMUME_FOOTER_LENGTH = 122

private class PendingUpload(val game: GameInfo, val current: AdminSave?, val fileName: String, val data: ByteArray)

/**
 * An admin's view of a player's saves: download one, or replace it with a .sav file (for
 * example brought over from another emulator) to fix the player's progress.
 */
@Composable
fun UserSavesDialog(user: AdminUser, onDismiss: () -> Unit) {
    val app = NdsApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<GameInfo>?>(null) }
    var saves by remember { mutableStateOf<Map<String, AdminSave>>(emptyMap()) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var downloadFor by remember { mutableStateOf<GameInfo?>(null) }
    var uploadFor by remember { mutableStateOf<GameInfo?>(null) }
    var pending by remember { mutableStateOf<PendingUpload?>(null) }

    suspend fun reload() {
        try {
            val all = app.api.games().sortedBy { it.name.lowercase() }
            saves = app.api.userSaves(user.id).associateBy { it.gameId }
            games = all
        } catch (e: ApiException) {
            games = games ?: emptyList()
            feedback = Feedback(e.message ?: "Impossibile leggere i salvataggi.", true)
        }
    }
    LaunchedEffect(user.id) { reload() }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val game = downloadFor
        downloadFor = null
        if (uri == null || game == null) return@rememberLauncherForActivityResult
        scope.launch {
            feedback = try {
                val data = app.api.downloadUserSave(user.id, game.id)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) } ?: throw IOException("Impossibile scrivere il file.")
                }
                Feedback("${game.name}: salvataggio scaricato.", false)
            } catch (e: ApiException) {
                Feedback(e.message ?: "Download non riuscito.", true)
            } catch (e: IOException) {
                Feedback(e.message ?: "Impossibile scrivere il file.", true)
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val game = uploadFor
        uploadFor = null
        if (uri == null || game == null) return@rememberLauncherForActivityResult
        scope.launch {
            val file = withContext(Dispatchers.IO) { readDocument(context, uri) }
            when {
                file == null -> feedback = Feedback("Impossibile leggere il file.", true)
                file.second.isEmpty() || file.second.size > MAX_SAVE_BYTES + DESMUME_FOOTER_LENGTH ->
                    feedback = Feedback("Il file è vuoto o troppo grande per essere un salvataggio DS.", true)
                else -> pending = PendingUpload(game, saves[game.id], file.first, file.second)
            }
        }
    }

    pending?.let { p ->
        val size = rawSize(p.data)
        var message = if (p.current == null)
            "${p.game.name}: verrà creato il salvataggio di ${user.username} con ${p.fileName} (${formatSize(size)})."
        else
            "${p.game.name}: il salvataggio di ${user.username} (${formatSize(p.current.size)}) verrà sostituito con ${p.fileName} (${formatSize(size)}). " +
                "La versione attuale resta nella cronologia del server."
        if (p.current != null && p.current.size != size)
            message += "\n\nAttenzione: le dimensioni sono diverse da quelle del salvataggio attuale, controlla che il file sia di questo gioco."
        ConfirmDialog(if (p.current == null) "Creare il salvataggio?" else "Sostituire il salvataggio?", message,
            if (p.current == null) "Carica" else "Sostituisci") { ok ->
            pending = null
            if (ok) scope.launch {
                feedback = try {
                    val stored = app.api.replaceUserSave(user.id, p.game.id, p.fileName, p.data)
                    reload()
                    Feedback("${p.game.name}: salvataggio di ${user.username} aggiornato (${formatSize(stored)}).", false)
                } catch (e: ApiException) {
                    Feedback(e.message ?: "Caricamento non riuscito.", true)
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvataggi di ${user.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Scarica un salvataggio o sostituiscilo con un file .sav, anche di un altro emulatore. Se il giocatore " +
                        "sta giocando sulla versione vecchia, all'uscita gli verrà chiesto quale tenere.",
                    style = MaterialTheme.typography.bodySmall,
                )
                FeedbackText(feedback)
                val list = games
                if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (list.isEmpty()) Text("La libreria non contiene giochi.")
                else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.id }) { game ->
                        val save = saves[game.id]
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(game.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    save?.let { formatSize(it.size) + (formatDate(it.updatedAt)?.let { d -> " · $d" } ?: "") } ?: "Nessun salvataggio",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(enabled = save != null, onClick = {
                                downloadFor = game
                                createDocument.launch("${game.name} - ${user.username}.sav")
                            }) { Text("Scarica") }
                            TextButton(onClick = {
                                uploadFor = game
                                openDocument.launch(arrayOf("*/*"))
                            }) { Text(if (save == null) "Carica" else "Sostituisci") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

/** Display name and content of a picked document, or null when it cannot be read. */
private fun readDocument(context: android.content.Context, uri: Uri): Pair<String, ByteArray>? = try {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: "salvataggio.sav"
    context.contentResolver.openInputStream(uri)?.use { input ->
        // Read at most a little more than the limit: a bigger file is rejected anyway.
        val bytes = input.readNBytesCompat(MAX_SAVE_BYTES.toInt() + DESMUME_FOOTER_LENGTH + 1)
        name to bytes
    }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private fun rawSize(data: ByteArray): Long {
    val n = data.size
    val hasFooter = n > DESMUME_FOOTER_LENGTH &&
        data.copyOfRange(n - DESMUME_FOOTER_END.size, n).contentEquals(DESMUME_FOOTER_END)
    return (if (hasFooter) n - DESMUME_FOOTER_LENGTH else n).toLong()
}

private fun formatSize(bytes: Long): String {
    fun amount(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)
    return when {
        bytes >= 1024 * 1024 -> amount(bytes / (1024.0 * 1024)) + " MB"
        bytes >= 1024 -> amount(bytes / 1024.0) + " KB"
        else -> "$bytes byte"
    }
}

private fun formatDate(iso: String?): String? = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    }.getOrNull()
}
