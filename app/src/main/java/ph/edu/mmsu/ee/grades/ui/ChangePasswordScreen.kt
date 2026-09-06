package ph.edu.mmsu.ee.grades.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Shown two ways: as a wall the student cannot pass when the server says
 * mustChangePassword, and as an ordinary screen reached from Settings.
 * [forced] is what tells them apart.
 */
@Composable
fun ChangePasswordScreen(
    forced: Boolean,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null
) {
    val vm: ChangePasswordViewModel = viewModel()

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
                text = if (forced) "Set your own password" else "Change password",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (forced) {
                    "You are signed in with a password your instructor set. " +
                        "Choose one only you know before going on."
                } else {
                    "Choose a new password of at least six characters."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = vm.current,
                onValueChange = { vm.current = it },
                label = { Text("Current password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = vm.next,
                onValueChange = { vm.next = it },
                label = { Text("New password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

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

            vm.error?.let {
                Spacer(Modifier.height(14.dp))
                ErrorBanner(message = it)
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { vm.submit(onDone) },
                enabled = !vm.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(if (vm.busy) "Saving…" else "Save password")
            }

            if (!forced && onCancel != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel") }
            }

            if (forced && onSignOut != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign out instead") }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
