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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.edu.mmsu.ee.grades.data.ClassSummary
import ph.edu.mmsu.ee.grades.data.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    profile: Profile?,
    onOpenClass: (ClassSummary) -> Unit,
    onOpenSettings: () -> Unit,
    onAuthLost: (String?) -> Unit
) {
    val vm: HomeViewModel = viewModel()

    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }

    val home = vm.data
    val semesters = home?.semesters.orEmpty()
    val allClasses = home?.classes.orEmpty()
    val filtered = if (vm.semesterFilter.isBlank()) {
        allClasses
    } else {
        allClasses.filter { it.semesterId == vm.semesterFilter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My classes") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (vm.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (vm.showingCache && vm.savedAt > 0L) {
                StaleNotice(vm.savedAt)
            }

            when {
                home == null && vm.loading -> LoadingBlock()

                home == null -> MessageBlock(
                    title = "Nothing to show yet",
                    body = vm.error ?: "Pull down a fresh copy when you have a connection.",
                    actionLabel = "Try again",
                    onAction = { vm.refresh() }
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        ProfileHeader(profile ?: home.profile)
                    }

                    if (home.announcement.isNotBlank()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = home.announcement,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }

                    vm.error?.let { message ->
                        item { ErrorBanner(message = message, onRetry = { vm.refresh() }) }
                    }

                    if (semesters.size > 1) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(semesters, key = { it.id }) { semester ->
                                    FilterChip(
                                        selected = vm.semesterFilter == semester.id,
                                        onClick = {
                                            vm.semesterFilter =
                                                if (vm.semesterFilter == semester.id) "" else semester.id
                                        },
                                        label = { Text(semester.name.ifBlank { semester.id }) }
                                    )
                                }
                            }
                        }
                    }

                    item { SectionHeader("${filtered.size} class${if (filtered.size == 1) "" else "es"}") }

                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = "No classes in this semester.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }

                    items(filtered, key = { it.id }) { klass ->
                        ClassCard(klass = klass, onClick = { onOpenClass(klass) })
                    }

                    if (home.contactEmail.isNotBlank()) {
                        item {
                            Text(
                                text = "Questions about a grade? Contact ${home.contactEmail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: Profile) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = profile.initials,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = profile.name.ifBlank { profile.id },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val detail = listOf(profile.id, profile.program, profile.yearLevel)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassCard(klass: ClassSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = klass.subjectCode.ifBlank { klass.name },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                if (klass.units != null) {
                    Text(
                        text = "${klass.units.tidy(1)} units",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = klass.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (klass.subjectTitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = klass.subjectTitle,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(8.dp))
            val meta = listOf(klass.instructor, klass.schedule, klass.room)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
