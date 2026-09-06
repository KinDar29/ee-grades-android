package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.edu.mmsu.ee.grades.data.ClassSummary
import ph.edu.mmsu.ee.grades.data.RosterEntry

/* --------------------------------- home ----------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminHomeScreen(
    onOpenSection: (ClassSummary) -> Unit,
    onOpenSettings: () -> Unit,
    onAuthLost: (String?) -> Unit
) {
    val vm: AdminHomeViewModel = viewModel()

    LaunchedEffect(Unit) { vm.start() }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }

    val data = vm.data

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Classes") },
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
                data == null && vm.loading -> LoadingBlock()

                data == null -> MessageBlock(
                    title = "Nothing to show yet",
                    body = vm.error ?: "Try again when you have a connection.",
                    actionLabel = "Try again",
                    onAction = { vm.refresh() }
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = data.profile.name.ifBlank { data.profile.email },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Students", data.counts.students, Modifier.weight(1f))
                            StatTile("Classes", data.counts.sections, Modifier.weight(1f))
                            StatTile("Subjects", data.counts.subjects, Modifier.weight(1f))
                        }
                    }

                    vm.error?.let { message ->
                        item { ErrorBanner(message = message, onRetry = { vm.refresh() }) }
                    }

                    if (data.semesters.size > 1) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(data.semesters, key = { it.id }) { semester ->
                                    FilterChip(
                                        selected = data.semesterId == semester.id,
                                        onClick = { vm.selectSemester(semester.id) },
                                        label = { Text(semester.name.ifBlank { semester.id }) }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        SectionHeader(
                            "${data.sections.size} class${if (data.sections.size == 1) "" else "es"}"
                        )
                    }

                    items(data.sections, key = { it.id }) { section ->
                        SectionCard(section = section, onClick = { onOpenSection(section) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CardDefaults.shape
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 12.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionCard(section: ClassSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = section.subjectCode.ifBlank { section.name },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${section.enrolled} enrolled",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (section.subjectTitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(section.subjectTitle, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            val meta = listOf(section.name, section.schedule, section.room)
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

/* ------------------------------- section view ------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminSectionScreen(
    sectionId: String,
    onBack: () -> Unit,
    onAuthLost: (String?) -> Unit
) {
    val vm: AdminSectionViewModel = viewModel()

    LaunchedEffect(sectionId) { vm.start(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }

    val data = vm.data
    val roster = data?.students.orEmpty().let { list ->
        val q = vm.query.trim().lowercase()
        if (q.isBlank()) list
        else list.filter {
            (it.studentId + " " + it.lastName + " " + it.firstName).lowercase().contains(q)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = data?.section?.run { subjectCode.ifBlank { name } } ?: "Class",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
                data == null && vm.loading -> LoadingBlock()

                data == null -> MessageBlock(
                    title = "Could not open this class",
                    body = vm.error ?: "Try again when you have a connection.",
                    actionLabel = "Try again",
                    onAction = { vm.refresh() }
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp
                    )
                ) {
                    item {
                        Column {
                            InfoRow("Section", data.section.name)
                            InfoRow("Subject", data.section.subjectTitle)
                            InfoRow("Instructor", data.section.instructor)
                            InfoRow("Schedule", data.section.schedule)
                            InfoRow("Room", data.section.room)
                            InfoRow("Enrolled", "${data.section.enrolled}")
                        }
                    }

                    vm.error?.let { message ->
                        item {
                            Column {
                                Spacer(Modifier.height(10.dp))
                                ErrorBanner(message = message, onRetry = { vm.refresh() })
                            }
                        }
                    }

                    item {
                        Column {
                            SectionHeader("${data.assessments.size} assessments")
                            // Weights that do not add up to 100 produce grades that
                            // look wrong to students, so say so rather than hide it.
                            val weighted = data.assessments.any { it.weight > 0.0 }
                            if (weighted && kotlin.math.abs(data.totalWeight - 100.0) > 0.01) {
                                ErrorBanner(
                                    message = "Assessment weights total " +
                                        "${data.totalWeight.tidy()}%, not 100%."
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }

                    items(data.assessments, key = { it.id }) { assessment ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = assessment.name.ifBlank { assessment.type },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = buildList {
                                        add(assessment.type)
                                        add("max ${assessment.max.tidy()}")
                                        if (assessment.weight > 0.0) {
                                            add("${assessment.weight.tidy()}%")
                                        }
                                        if (!assessment.published) add("unpublished")
                                    }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Column {
                            SectionHeader("Roster")
                            OutlinedTextField(
                                value = vm.query,
                                onValueChange = { vm.query = it },
                                label = { Text("Search by name or number") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    items(roster, key = { it.studentId }) { entry ->
                        RosterRow(entry)
                    }

                    if (roster.isEmpty()) {
                        item {
                            Text(
                                text = if (vm.query.isBlank()) {
                                    "No students enrolled yet."
                                } else {
                                    "No one matches that search."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RosterRow(entry: RosterEntry) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = entry.studentId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = entry.grade.percent?.let { "${it.tidy()}%" } ?: "—",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${entry.grade.recorded}/${entry.grade.items}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            GradePill(grade = entry.grade.grade, remark = "")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
