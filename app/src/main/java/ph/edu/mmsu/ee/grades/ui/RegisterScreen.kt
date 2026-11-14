package ph.edu.mmsu.ee.grades.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.edu.mmsu.ee.grades.data.LoginResult

/**
 * New-student registration, same steps as the portal: fill in the form →
 * a 6-digit code is e-mailed → the code creates the account (enrolled in the
 * classes ticked) and the student is signed in.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RegisterScreen(
    onSignedIn: (LoginResult) -> Unit,
    onCancel: () -> Unit
) {
    val vm: RegisterViewModel = viewModel()
    val codeStep = vm.sentTo != null
    BackHandler { if (codeStep) vm.editForm() else onCancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create your account") },
                navigationIcon = {
                    IconButton(onClick = { if (codeStep) vm.editForm() else onCancel() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val o = vm.options
        when {
            o == null && vm.loading -> LoadingBlock(Modifier.padding(padding))
            o == null -> MessageBlock(
                title = "Could not open the form",
                body = vm.error ?: "Try again when you have a connection.",
                modifier = Modifier.padding(padding),
                actionLabel = "Try again",
                onAction = { vm.load() }
            )
            !o.open -> MessageBlock(
                title = "Registration is closed",
                body = "Please contact your instructor.",
                modifier = Modifier.padding(padding),
                actionLabel = "Back",
                onAction = onCancel
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp)
            ) {
                if (codeStep) {
                    Spacer(Modifier.height(16.dp))
                    Text("Check your e-mail", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "We sent a 6-digit code to ${vm.sentTo}. It expires in 30 minutes. " +
                            "Not there? Check your spam or promotions folder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = vm.code,
                        onValueChange = { v -> vm.code = v.filter { it.isDigit() }.take(6) },
                        label = { Text("Code from the e-mail") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.verify(onSignedIn) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                    vm.error?.let { Spacer(Modifier.height(12.dp)); ErrorBanner(message = it) }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { vm.verify(onSignedIn) },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(if (vm.busy) "Creating your account…" else "Create my account") }
                    TextButton(onClick = { vm.editForm() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Change my details / send a new code")
                    }
                    Spacer(Modifier.height(40.dp))
                    return@Column
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Fill in your details exactly as they appear in your school records. " +
                        "We'll e-mail you a code to confirm your address, and then you are signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                Field(vm.studentId, { vm.studentId = it }, "Student number", "e.g. 25-050366", KeyboardType.Text)
                Field(vm.email, { vm.email = it.trim() }, "E-mail address", "e.g. juan.delacruz@gmail.com", KeyboardType.Email)
                Field(vm.lastName, { vm.lastName = it }, "Last name")
                Field(vm.firstName, { vm.firstName = it }, "First name")
                Field(vm.middleName, { vm.middleName = it }, "Middle name (optional)")

                Label("Program")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    o.programs.forEach { p ->
                        FilterChip(selected = vm.program == p, onClick = { vm.program = p }, label = { Text(p) })
                    }
                }
                Label("Year level")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    o.years.forEach { y ->
                        FilterChip(selected = vm.yearLevel == y, onClick = { vm.yearLevel = y }, label = { Text(y) })
                    }
                }
                Spacer(Modifier.height(8.dp))

                Field(vm.password, { vm.password = it }, "Password", "At least ${o.minPassword} characters", KeyboardType.Password, secret = true)
                Field(vm.confirm, { vm.confirm = it }, "Repeat password", null, KeyboardType.Password, secret = true)

                Label("Your classes this semester (tick every class you are enrolled in)")
                if (o.classes.isEmpty()) {
                    Text("No classes are open yet. Ask your instructor.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        o.classes.forEachIndexed { i, c ->
                            if (i > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.toggle(c.id) }
                                    .padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = c.id in vm.picked, onCheckedChange = { vm.toggle(c.id) })
                                Column {
                                    Text("${c.subject} · ${c.section}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    val sub = listOf(c.title, c.schedule).filter { it.isNotBlank() }.joinToString(" · ")
                                    if (sub.isNotBlank()) {
                                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                vm.error?.let { Spacer(Modifier.height(14.dp)); ErrorBanner(message = it) }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { vm.send() },
                    enabled = !vm.busy && o.classes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (vm.busy) "Sending…" else "Send verification code") }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    type: KeyboardType = KeyboardType.Text,
    secret: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder != null) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    )
}
