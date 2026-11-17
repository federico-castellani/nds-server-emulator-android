package io.github.federicocastellani.ndsstation.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.AdminUser
import io.github.federicocastellani.ndsstation.data.ApiClient
import io.github.federicocastellani.ndsstation.data.ApiException
import io.github.federicocastellani.ndsstation.emulation.RomBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

private fun generatePassword(): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val random = SecureRandom()
    return (1..10).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

/** A result line under a card: success or error. */
internal data class Feedback(val text: String, val error: Boolean)

@Composable
internal fun FeedbackText(feedback: Feedback?) {
    feedback ?: return
    Surface(
        color = if (feedback.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            feedback.text, Modifier.padding(12.dp).fillMaxWidth(),
            color = if (feedback.error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun AdminScreen(modifier: Modifier, onSignOut: (String?) -> Unit) {
    val app = NdsApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Upload
    var title by remember { mutableStateOf("") }
    var romUri by remember { mutableStateOf<Uri?>(null) }
    var romName by remember { mutableStateOf<String?>(null) }
    var romSize by remember { mutableStateOf(0L) }
    var uploadProgress by remember { mutableStateOf<Float?>(null) }
    var uploadFeedback by remember { mutableStateOf<Feedback?>(null) }

    // New user
    var newUser by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf(generatePassword()) }
    var creating by remember { mutableStateOf(false) }
    var createFeedback by remember { mutableStateOf<Feedback?>(null) }

    // Users
    var users by remember { mutableStateOf<List<AdminUser>>(emptyList()) }
    var usersLoading by remember { mutableStateOf(false) }
    var usersFeedback by remember { mutableStateOf<Feedback?>(null) }
    var userQuery by remember { mutableStateOf("") }
    var resetFor by remember { mutableStateOf<AdminUser?>(null) }
    var deleteFor by remember { mutableStateOf<AdminUser?>(null) }
    var renameFor by remember { mutableStateOf<AdminUser?>(null) }
    var savesFor by remember { mutableStateOf<AdminUser?>(null) }
    val selfId = ApiClient.tokenUserId(app.api.token)
    fun isSelf(user: AdminUser) = selfId?.let { it == user.id } ?: user.username.equals(app.settings.username, ignoreCase = true)

    suspend fun loadUsers() {
        usersLoading = true
        try {
            users = app.api.adminUsers().sortedBy { it.id }
        } catch (e: ApiException) {
            usersFeedback = Feedback(
                if (e.isUnauthorized) "Il tuo account non ha i permessi di amministratore su questo server." else e.message ?: "Errore", true,
            )
        } finally {
            usersLoading = false
        }
    }

    LaunchedEffect(Unit) { loadUsers() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        romUri = uri
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                romName = c.getString(0)
                romSize = c.getLong(1)
            }
        }
        // Suggest a title from the ROM banner if the admin has not typed one.
        if (title.isBlank()) scope.launch {
            val banner = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.let { RomBanner.read(it) } }
            title = banner?.name ?: romName?.substringBeforeLast('.') ?: ""
        }
    }

    resetFor?.let { user ->
        var password by remember(user) { mutableStateOf(generatePassword()) }
        AlertDialog(
            onDismissRequest = { resetFor = null },
            title = { Text("Reimposta password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${user.username} dovrà scegliere una nuova password al prossimo accesso.")
                    OutlinedTextField(password, { password = it }, label = { Text("Password temporanea") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    resetFor = null
                    if (password.length < MIN_PASSWORD_LENGTH) {
                        usersFeedback = Feedback("La password deve avere almeno $MIN_PASSWORD_LENGTH caratteri.", true)
                        return@TextButton
                    }
                    scope.launch {
                        try {
                            app.api.resetUserPassword(user.id, password)
                            usersFeedback = Feedback("Password di ${user.username} reimpostata a: $password", false)
                            loadUsers()
                        } catch (e: ApiException) {
                            usersFeedback = Feedback(e.message ?: "Errore", true)
                        }
                    }
                }) { Text("Reimposta") }
            },
            dismissButton = { TextButton(onClick = { resetFor = null }) { Text("Annulla") } },
        )
    }

    savesFor?.let { user -> UserSavesDialog(user) { savesFor = null } }

    renameFor?.let { user ->
        var name by remember(user) { mutableStateOf(user.username) }
        val self = isSelf(user)
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Cambia nome utente") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (self) "Dopo il cambio dovrai accedere di nuovo con il nuovo nome."
                        else "${user.username} verrà disconnesso e dovrà accedere con il nuovo nome. Salvataggi, console e amici restano invariati."
                    )
                    OutlinedTextField(name, { if (it.length <= 50) name = it }, label = { Text("Nuovo nome utente") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && name.trim() != user.username, onClick = {
                    renameFor = null
                    val newName = name.trim()
                    scope.launch {
                        try {
                            app.api.renameUser(user.id, newName)
                        } catch (e: ApiException) {
                            usersFeedback = Feedback(if (e.statusCode == 409) "Il nome “$newName” è già in uso." else e.message ?: "Errore", true)
                            return@launch
                        }
                        if (self) {
                            // This session ended with the rename: sign in again, with the new name filled in.
                            app.settings.username = newName
                            onSignOut("Il tuo nome utente ora è “$newName”: accedi di nuovo.")
                        } else {
                            usersFeedback = Feedback("“${user.username}” ora si chiama “$newName”: dovrà accedere di nuovo con il nuovo nome.", false)
                            loadUsers()
                        }
                    }
                }) { Text("Rinomina") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Annulla") } },
        )
    }

    deleteFor?.let { user ->
        ConfirmDialog("Eliminare l'utente?", "L'account “${user.username}” verrà eliminato dal server. L'operazione non si può annullare.", "Elimina") { ok ->
            deleteFor = null
            if (ok) scope.launch {
                try {
                    app.api.deleteUser(user.id)
                    usersFeedback = Feedback("Utente ${user.username} eliminato.", false)
                    loadUsers()
                } catch (e: ApiException) {
                    usersFeedback = Feedback(e.message ?: "Errore", true)
                }
            }
        }
    }

    val visibleUsers = users.filter { userQuery.isBlank() || it.username.contains(userQuery.trim(), ignoreCase = true) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SectionCard("Carica un gioco") {
                OutlinedTextField(
                    title, { title = it }, label = { Text("Titolo") }, placeholder = { Text("es. Pokémon Platino") },
                    singleLine = true, enabled = uploadProgress == null, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = uploadProgress == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FileOpen, null)
                    Text(romName?.let { "  $it" } ?: "  Scegli la ROM (.nds)")
                }
                Text("Senza file viene aggiunto solo il titolo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                uploadProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                Button(
                    enabled = uploadProgress == null,
                    onClick = {
                        if (title.isBlank()) {
                            uploadFeedback = Feedback("Inserisci un titolo.", true)
                            return@Button
                        }
                        scope.launch {
                            uploadFeedback = null
                            uploadProgress = 0f
                            try {
                                val uri = romUri
                                if (uri == null) {
                                    app.api.addGameTitle(title.trim())
                                    uploadFeedback = Feedback("“${title.trim()}” è stato aggiunto alla libreria.", false)
                                } else {
                                    app.api.uploadGame(
                                        title.trim(), romName ?: "rom.nds", romSize,
                                        open = { context.contentResolver.openInputStream(uri) ?: throw ApiException("Impossibile leggere il file.") },
                                    ) { p -> uploadProgress = p }
                                    uploadFeedback = Feedback("“${title.trim()}” è stato caricato.", false)
                                }
                                title = ""
                                romUri = null
                                romName = null
                            } catch (e: ApiException) {
                                uploadFeedback = Feedback(e.message ?: "Caricamento non riuscito", true)
                            } finally {
                                uploadProgress = null
                            }
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Upload, null)
                    Text(if (uploadProgress != null) "  Caricamento… ${((uploadProgress ?: 0f) * 100).toInt()}%" else if (romUri == null) "  Aggiungi titolo" else "  Carica gioco")
                }
                FeedbackText(uploadFeedback)
            }
        }

        item {
            SectionCard("Invita un utente") {
                OutlinedTextField(newUser, { newUser = it }, label = { Text("Nome utente") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        newPassword, { newPassword = it }, label = { Text("Password temporanea") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { newPassword = generatePassword() }) { Icon(Icons.Outlined.Refresh, "Genera") }
                }
                Text("Comunicala all'utente: potrà cambiarla dopo il primo accesso.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(enabled = !creating, onClick = {
                    if (newUser.isBlank() || newPassword.length < MIN_PASSWORD_LENGTH) {
                        createFeedback = Feedback("Inserisci un nome utente e una password di almeno $MIN_PASSWORD_LENGTH caratteri.", true)
                        return@FilledTonalButton
                    }
                    scope.launch {
                        creating = true
                        try {
                            app.api.registerUser(newUser.trim(), newPassword)
                            createFeedback = Feedback("Account “${newUser.trim()}” creato. Password temporanea: $newPassword", false)
                            newUser = ""
                            newPassword = generatePassword()
                            loadUsers()
                        } catch (e: ApiException) {
                            createFeedback = Feedback(e.message ?: "Errore", true)
                        } finally {
                            creating = false
                        }
                    }
                }) {
                    Icon(Icons.Outlined.PersonAdd, null)
                    Text("  Crea account")
                }
                FeedbackText(createFeedback)
            }
        }

        item {
            SectionCard("Utenti (${users.size})") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        userQuery, { userQuery = it }, placeholder = { Text("Cerca utente") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { scope.launch { loadUsers() } }) { Icon(Icons.Outlined.Refresh, "Aggiorna utenti") }
                }
                if (usersLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                FeedbackText(usersFeedback)
            }
        }

        items(visibleUsers, key = { it.id }) { user ->
            val self = isSelf(user)
            var menu by remember { mutableStateOf(false) }
            ListItem(
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.padding(horizontal = 4.dp),
                leadingContent = {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            Text(user.username.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                },
                headlineContent = { Text(user.username + if (self) " (tu)" else "") },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(if (user.isAdmin) "Admin" else "Utente", MaterialTheme.colorScheme.primary.takeIf { user.isAdmin })
                        Badge(
                            if (user.mustChangePassword) "Da configurare" else "Attivo",
                            if (user.mustChangePassword) Color(0xFFB26A00) else Color(0xFF2E7D32),
                        )
                        Text("#${user.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { savesFor = user }) { Icon(Icons.Outlined.SaveAs, "Salvataggi") }
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Altre azioni") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Cambia nome") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                    onClick = { menu = false; renameFor = user },
                                )
                                DropdownMenuItem(
                                    text = { Text("Reimposta password") }, leadingIcon = { Icon(Icons.Outlined.Key, null) },
                                    onClick = { menu = false; resetFor = user },
                                )
                                val danger = if (self) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
                                DropdownMenuItem(
                                    text = { Text("Elimina", color = danger) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = danger) },
                                    enabled = !self, onClick = { menu = false; deleteFor = user },
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color?) {
    val c = color ?: MaterialTheme.colorScheme.outline
    Surface(shape = RoundedCornerShape(8.dp), color = c.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = c)
    }
}
