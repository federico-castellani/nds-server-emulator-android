package io.github.federicocastellani.ndsstation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.federicocastellani.ndsstation.NdsApp
import io.github.federicocastellani.ndsstation.data.ApiException
import io.github.federicocastellani.ndsstation.emulation.Question
import kotlinx.coroutines.launch

const val MIN_PASSWORD_LENGTH = 6

/**
 * Asks for a new password twice and sends it to the server. [forced] explains why it is
 * needed and turns "Annulla" into "Esci". [onResult] gets true once the server accepted it.
 */
@Composable
fun ChangePasswordDialog(forced: Boolean, onResult: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy && !forced) onResult(false) },
        icon = { Icon(Icons.Outlined.Password, contentDescription = null) },
        title = { Text(if (forced) "Imposta una nuova password" else "Cambia password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (forced) Text("Per motivi di sicurezza devi scegliere una nuova password prima di continuare.")
                OutlinedTextField(
                    value = first, onValueChange = { first = it }, label = { Text("Nuova password") },
                    supportingText = { Text("Almeno $MIN_PASSWORD_LENGTH caratteri") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = second, onValueChange = { second = it }, label = { Text("Conferma password") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                when {
                    first.length < MIN_PASSWORD_LENGTH -> error = "La password deve avere almeno $MIN_PASSWORD_LENGTH caratteri."
                    first != second -> error = "Le due password non coincidono."
                    else -> scope.launch {
                        busy = true
                        try {
                            NdsApp.instance.api.changePassword(first)
                            onResult(true)
                        } catch (e: ApiException) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onResult(false) }) { Text(if (forced) "Esci" else "Annulla") } },
    )
}

/** Shows a question raised by the emulator session (which save to use, retry upload...). */
@Composable
fun QuestionDialog(question: Question) {
    AlertDialog(
        onDismissRequest = { question.answer(question.dismissIndex) },
        title = { Text(question.title) },
        text = { Text(question.message) },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                question.options.forEachIndexed { index, option ->
                    TextButton(onClick = { question.answer(index) }) { Text(option) }
                }
            }
        },
    )
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Annulla") } },
    )
}
