package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.edu.mmsu.ee.grades.data.LoginResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    notice: String? = null,
    onSignedIn: (LoginResult) -> Unit
) {
    val vm: LoginViewModel = viewModel()
    val portal = vm.portal
    val isAdmin = vm.role == "admin"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(72.dp))

        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "EE",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = portal?.title ?: "Electrical Engineering",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = portal?.subtitle ?: "Student Grades Portal",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !isAdmin,
                onClick = { vm.setRole("student") },
                label = { Text("Student") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = isAdmin,
                onClick = { vm.setRole("admin") },
                label = { Text("Administrator") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))

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

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = vm.password,
            onValueChange = { vm.password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (vm.showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            trailingIcon = {
                IconButton(onClick = { vm.showPassword = !vm.showPassword }) {
                    Icon(
                        imageVector = if (vm.showPassword) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (vm.showPassword) {
                            "Hide password"
                        } else {
                            "Show password"
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        val message = vm.error ?: notice
        if (message != null) {
            Spacer(Modifier.height(14.dp))
            ErrorBanner(message = message)
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { vm.submit(onSignedIn) },
            enabled = !vm.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (vm.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("Signing in…")
            } else {
                Text("Sign in")
            }
        }

        val announcement = portal?.announcement.orEmpty()
        if (announcement.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = announcement,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        val contact = portal?.contactEmail.orEmpty()
        if (contact.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Trouble signing in? Contact $contact",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}
