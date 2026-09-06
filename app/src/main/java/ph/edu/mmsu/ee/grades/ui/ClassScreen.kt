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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.edu.mmsu.ee.grades.data.ClassItem
import ph.edu.mmsu.ee.grades.data.Grade
import ph.edu.mmsu.ee.grades.data.StudentClass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassScreen(
    sectionId: String,
    onBack: () -> Unit,
    onAuthLost: (String?) -> Unit
) {
    val vm: ClassViewModel = viewModel()

    LaunchedEffect(sectionId) { vm.start(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }

    val data = vm.data
    val title = data?.section?.run { subjectCode.ifBlank { name } }?.ifBlank { "Class" } ?: "Class"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1) },
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
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { ClassHeader(data) }

                    vm.error?.let { message ->
                        item { ErrorBanner(message = message, onRetry = { vm.refresh() }) }
                    }

                    if (data.showRunningGrade) {
                        item { RunningGradeCard(data.grade) }
                    }

                    item {
                        SectionHeader(
                            "${data.items.size} assessment${if (data.items.size == 1) "" else "s"}"
                        )
                    }

                    if (data.items.isEmpty()) {
                        item {
                            Text(
                                text = "Nothing has been published for this class yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }

                    items(data.items, key = { it.id }) { assessment ->
                        AssessmentRow(
                            item = assessment,
                            showClassAverage = data.showClassAverage
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassHeader(data: StudentClass) {
    Column(Modifier.padding(vertical = 6.dp)) {
        if (data.section.subjectTitle.isNotBlank()) {
            Text(
                text = data.section.subjectTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
        }
        InfoRow("Section", data.section.name)
        InfoRow("Instructor", data.section.instructor)
        InfoRow("Schedule", data.section.schedule)
        InfoRow("Room", data.section.room)
        InfoRow("Semester", data.section.semesterName)
        InfoRow("Class size", if (data.classSize > 0) "${data.classSize} students" else "")
    }
}

@Composable
private fun RunningGradeCard(grade: Grade) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Running grade",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = grade.percent?.let { "${it.tidy()}%" } ?: "Not yet available",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append("${grade.recorded} of ${grade.items} recorded")
                        if (grade.mode == "weighted" && grade.weightCovered != null) {
                            append(" · ${grade.weightCovered.tidy()}% of the weight")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            GradePill(grade = grade.grade, remark = grade.remark)
        }
    }
}

@Composable
private fun AssessmentRow(item: ClassItem, showClassAverage: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name.ifBlank { item.type },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                val meta = buildList {
                    add(item.type)
                    if (item.weight > 0.0) add("${item.weight.tidy()}% weight")
                    if (item.date.isNotBlank()) add(item.date)
                }.joinToString(" · ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (item.isRecorded) {
                    Text(
                        text = "${item.score.orDash()} / ${item.max.tidy()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    item.percent?.let {
                        Text(
                            text = "${it.tidy()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        text = "Not recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "out of ${item.max.tidy()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showClassAverage && item.classAverage != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("Class average ${item.classAverage.tidy()}")
                    item.classHigh?.let { append(" · high ${it.tidy()}") }
                    item.recordedCount?.let { append(" · $it recorded") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
