package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ph.edu.mmsu.ee.grades.BuildConfig
import ph.edu.mmsu.ee.grades.data.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profile: Profile?,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onSignOut: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            if (profile != null) {
                Text(
                    text = profile.name.ifBlank { profile.id },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                InfoRow(if (profile.isAdmin) "E-mail" else "Student no.", profile.id)
                InfoRow("Programme", profile.program)
                InfoRow("Year level", profile.yearLevel)
                InfoRow("Role", if (profile.isAdmin) profile.adminRole.ifBlank { "Admin" } else "Student")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onChangePassword,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Change password") }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign out") }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            SectionHeader("About")
            InfoRow("App version", BuildConfig.VERSION_NAME)
            InfoRow("Server", serverHost())
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Grades are read from the department's Apps Script portal. " +
                    "A copy of the last screen you opened is kept on this phone so it " +
                    "can be read without a connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Shows the host only — the deployment id is not something to put on screen. */
private fun serverHost(): String = try {
    java.net.URI(BuildConfig.API_URL).host ?: "not configured"
} catch (e: Exception) {
    "not configured"
}
