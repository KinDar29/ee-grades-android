package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.edu.mmsu.ee.grades.data.Profile

/**
 * The wall shown when the portal says this student must confirm an e-mail
 * address (portal `MUST_EMAIL`). The address on file is filled in; a code is
 * e-mailed and entered here. Only "Sign out" leads anywhere else.
 */
@Composable
fun ConfirmEmailScreen(
    currentEmail: String,
    onDone: (Profile) -> Unit,
    onSignOut: () -> Unit
) {
    val vm: ConfirmEmailViewModel = viewModel()
    LaunchedEffect(currentEmail) { vm.prefill(currentEmail) }
    val codeSent = vm.sentTo != null

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(40.dp))

            Text(
                text = "Confirm your e-mail address",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Before you continue, add an e-mail address you check. We'll send it a " +
                    "6-digit code. It is where your password-reset codes will go if you ever " +
                    "forget your password.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = vm.email,
                onValueChange = { vm.onEmailChange(it) },
                label = { Text("E-mail address") },
                placeholder = { Text("e.g. juan.delacruz@gmail.com") },
                supportingText = {
                    if (currentEmail.isNotBlank() && !codeSent) {
                        Text("This is the address we have on file. Change it if it is wrong or old.")
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { vm.send() }),
                modifier = Modifier.fillMaxWidth()
            )

            if (codeSent) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vm.code,
                    onValueChange = { v -> vm.code = v.filter { it.isDigit() }.take(6) },
                    label = { Text("Code from the e-mail") },
                    placeholder = { Text("6 digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { vm.verify(onDone) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            vm.info?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            vm.error?.let {
                Spacer(Modifier.height(14.dp))
                ErrorBanner(message = it)
            }

            Spacer(Modifier.height(24.dp))

            if (codeSent) {
                Button(
                    onClick = { vm.verify(onDone) },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (vm.busy) "Checking…" else "Confirm") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.send() },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send a new code") }
            } else {
                Button(
                    onClick = { vm.send() },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (vm.busy) "Sending…" else "Send code") }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }

            Spacer(Modifier.height(40.dp))
        }
    }
}
