package io.github.federicocastellani.ndsstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.ApiClient
import io.github.federicocastellani.ndsstation.data.ApiException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val app = NdsApp.instance
    val settings = app.settings
    val scope = rememberCoroutineScope()

    var server by remember { mutableStateOf(settings.serverUrl) }
    var username by remember { mutableStateOf(settings.username) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(LoginMessage.pending.also { LoginMessage.pending = null }) }
    var restoring by remember { mutableStateOf(settings.token != null) }
    var forcedChange by remember { mutableStateOf<(() -> Unit)?>(null) }
    val update by app.updater.available.collectAsState()
    var showUpdate by remember { mutableStateOf(false) }
    update?.let { info -> if (showUpdate) UpdateDialog(info) { showUpdate = false } }

    fun enterApp() {
        if (!settings.isAdmin && ApiClient.tokenSaysAdmin(app.api.token)) settings.isAdmin = true
        app.store.setScope(app.api.baseUrl, ApiClient.tokenUserId(app.api.token), settings.username)
        onSignedIn()
    }

    // Restore a saved session: the game list is the cheapest authenticated call.
    LaunchedEffect(Unit) {
        val token = settings.token ?: return@LaunchedEffect
        app.api.token = token
        try {
            withTimeout(15_000) { app.api.games() }
            if (restoring) enterApp()   // unless the user picked "another account" meanwhile
        } catch (e: ApiException) {
            if (e.isUnauthorized) {
                settings.token = null
                app.api.logout()
                error = "La sessione è scaduta. Accedi di nuovo."
            } else {
                error = e.message
            }
            restoring = false
        } catch (_: TimeoutCancellationException) {
            error = "Il server non risponde. Riprova più tardi."
            restoring = false
        }
    }

    fun signIn() {
        if (server.isBlank() || username.isBlank() || password.isEmpty()) {
            error = "Inserisci server, nome utente e password."
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                app.api.baseUrl = server.trim()
                val result = app.api.login(username.trim(), password)
                val finish = {
                    settings.serverUrl = app.api.baseUrl
                    settings.username = username.trim()
                    settings.isAdmin = result.isAdmin
                    settings.token = result.token
                    password = ""
                    enterApp()
                }
                if (result.mustChangePassword) forcedChange = finish else finish()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    forcedChange?.let { finish ->
        ChangePasswordDialog(forced = true) { changed ->
            forcedChange = null
            if (changed) finish()
            else {
                // Without a new password the account stays locked; don't keep the session.
                app.api.logout()
                error = "Devi impostare una nuova password per usare questo account."
            }
        }
    }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.Center) {
            if (restoring) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Accesso a ${app.api.baseUrl} come ${settings.username}…", textAlign = TextAlign.Center)
                    OutlinedButton(onClick = {
                        restoring = false
                        settings.token = null
                        app.api.logout()
                    }) { Text("Usa un altro account") }
                }
                return@Box
            }

            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 440.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(80.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.SportsEsports, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("NDS Station", style = MaterialTheme.typography.headlineMedium)
                Text("Accedi al tuo account", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        update?.let { info ->
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                                androidx.compose.foundation.layout.Row(
                                    Modifier.padding(start = 12.dp, end = 4.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("NDS Station ${info.version} è disponibile", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    TextButton(onClick = { showUpdate = true }) { Text("Aggiorna") }
                                }
                            }
                        }
                        error?.let {
                            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                                Text(it, Modifier.padding(12.dp).fillMaxWidth(), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        OutlinedTextField(
                            value = server, onValueChange = { server = it }, label = { Text("Server") },
                            placeholder = { Text("http://192.168.1.10:3000") },
                            leadingIcon = { Icon(Icons.Outlined.Dns, null) }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = username, onValueChange = { username = it }, label = { Text("Nome utente") },
                            leadingIcon = { Icon(Icons.Outlined.Person, null) }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password, onValueChange = { password = it }, label = { Text("Password") },
                            leadingIcon = { Icon(Icons.Outlined.Lock, null) }, singleLine = true, enabled = !busy,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (showPassword) "Nascondi password" else "Mostra password",
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { signIn() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = ::signIn, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text("Accedi")
                        }
                    }
                }
            }
        }
    }
}
