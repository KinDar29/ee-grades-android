package ph.edu.mmsu.ee.grades.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * "Forgot password", in two steps on one screen:
 *  1. the person names their account and the server e-mails a 6-digit code;
 *  2. they type the code and a new password.
 * Their old password keeps working until step 2 succeeds.
 */
@Composable
fun ForgotPasswordScreen(
    role: String,
    initialUsername: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    val vm: ForgotPasswordViewModel = viewModel()
    val isAdmin = role == "admin"

    LaunchedEffect(Unit) { vm.prefill(initialUsername) }
    val cancel = { vm.reset(); onCancel() }
    val done: (String) -> Unit = { user -> vm.reset(); onDone(user) }
    BackHandler(onBack = cancel)

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
                text = "Reset your password",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (vm.codeStep) {
                    "Enter the 6-digit code from the e-mail and choose a new password."
                } else {
                    "Enter your ${if (isAdmin) "e-mail address" else "student number"}. " +
                        "We'll e-mail a 6-digit code to the address on file."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = vm.username,
                onValueChange = { vm.username = it },
                label = { Text(if (isAdmin) "E-mail address" else "Student number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isAdmin) KeyboardType.Email else KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (vm.codeStep) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vm.code,
                    onValueChange = { typed -> vm.code = typed.filter { it.isDigit() }.take(6) },
                    label = { Text("Code from the e-mail") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vm.next,
                    onValueChange = { vm.next = it },
                    label = { Text("New password") },
                    supportingText = { Text("At least 6 characters.") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = vm.confirm,
                    onValueChange = { vm.confirm = it },
                    label = { Text("Repeat new password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            vm.info?.let {
                Spacer(Modifier.height(14.dp))
                InfoBanner(message = it)
            }
            vm.error?.let {
                Spacer(Modifier.height(14.dp))
                ErrorBanner(message = it)
            }

            Spacer(Modifier.height(24.dp))

            if (vm.codeStep) {
                Button(
                    onClick = { vm.submit(role, done) },
                    enabled = !vm.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(if (vm.busy) "Saving…" else "Set new password")
                }
                TextButton(
                    onClick = { vm.sendCode(role) },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send me a new code") }
            } else {
                Button(
                    onClick = { vm.sendCode(role) },
                    enabled = !vm.busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(if (vm.busy) "Sending…" else "Send code")
                }
                TextButton(
                    onClick = { vm.haveCode() },
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("I already have a code") }
            }

            TextButton(
                onClick = cancel,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back to sign in") }

            Spacer(Modifier.height(40.dp))
        }
    }
}
