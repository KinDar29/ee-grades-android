package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import java.io.File
import androidx.core.content.FileProvider
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import android.net.Uri
import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ph.edu.mmsu.ee.grades.data.ClassItem
import ph.edu.mmsu.ee.grades.data.CourseFile
import ph.edu.mmsu.ee.grades.data.ExamInfo
import ph.edu.mmsu.ee.grades.data.Grade
import ph.edu.mmsu.ee.grades.data.StudentClass
import ph.edu.mmsu.ee.grades.data.Survey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassScreen(
    sectionId: String,
    onBack: () -> Unit,
    onAuthLost: (String?) -> Unit,
    onOpenExam: (ExamInfo) -> Unit = {}
) {
    val vm: ClassViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(sectionId) { vm.start(sectionId) }
    // Coming back from an exam: show the new state and score.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        var first = true
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (first) first = false else vm.refresh()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }

    // Problem set waiting for the student to pick a PDF.
    var pickingFor by remember { mutableStateOf<String?>(null) }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pickingFor
        pickingFor = null
        if (uri != null && target != null) {
            vm.uploadProblemSet(
                assignmentId = target,
                uri = uri,
                resolver = context.contentResolver,
                onDone = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
                onError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            )
        }
    }
    // Exam the student tapped "Start" on, waiting for the rules dialog.
    var confirmExam by remember { mutableStateOf<ExamInfo?>(null) }
    confirmExam?.let { exam ->
        ExamRulesDialog(
            exam = exam,
            onStart = { confirmExam = null; onOpenExam(exam) },
            onCancel = { confirmExam = null }
        )
    }

    // A view-only course file (module, syllabus, TOS…) open in the in-app viewer.
    var viewing by remember { mutableStateOf<Pair<File, String>?>(null) }
    viewing?.let { (file, label) ->
        ViewOnlyPdf(file = file, title = label, onClose = { viewing = null })
    }

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

                    data.survey?.takeIf { it.url.startsWith("https://") }?.let { survey ->
                        item { SurveyCard(survey) }
                    }

                    if (data.files.isNotEmpty()) {
                        item {
                            CourseFilesCard(
                                files = data.files,
                                opening = vm.openingFile,
                                onOpen = { kind ->
                                    vm.openFile(
                                        kind = kind,
                                        cacheDir = context.cacheDir,
                                        onReady = { got ->
                                            if (got.downloadable) openPdf(context, got.file)
                                            else viewing = got.file to (data.files.firstOrNull { it.kind == kind }?.label ?: got.file.name)
                                        },
                                        onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                                    )
                                }
                            )
                        }
                    }

                    if (data.exams.isNotEmpty()) {
                        item {
                            ExamsCard(
                                exams = data.exams,
                                onStart = { confirmExam = it }
                            )
                        }
                    }

                    if (data.assignments.isNotEmpty()) {
                        item {
                            ProblemSetsCard(
                                sets = data.assignments,
                                busyId = vm.uploading,
                                opening = vm.openingFile,
                                onViewSet = { ps ->
                                    vm.openFile(
                                        kind = ps.materialId,
                                        cacheDir = context.cacheDir,
                                        onReady = { got ->
                                            if (got.downloadable) openPdf(context, got.file)
                                            else viewing = got.file to ps.title
                                        },
                                        onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                                    )
                                },
                                onUpload = { ps ->
                                    pickingFor = ps.id
                                    pickPdf.launch(arrayOf("application/pdf"))
                                },
                                onViewMine = { ps ->
                                    vm.openMySubmission(
                                        assignmentId = ps.id,
                                        cacheDir = context.cacheDir,
                                        onReady = { got -> openPdf(context, got.file) },
                                        onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                                    )
                                }
                            )
                        }
                    }

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

@Composable
private fun SurveyCard(survey: Survey) {
    val context = LocalContext.current
    val done = survey.submitted == true
    val open = {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(survey.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        Unit
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Course outcome self-assessment",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (done) "✓ Submitted — thank you."
                else "Rate how well you reached this class's course outcomes. It takes a few minutes.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            if (done) {
                OutlinedButton(onClick = open) { Text("Open the survey again") }
            } else {
                Button(onClick = open) { Text("Answer the survey") }
            }
        }
    }
}

@Composable
private fun CourseFilesCard(files: List<CourseFile>, opening: String?, onOpen: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                text = "Course files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            files.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = f.name + if (f.size > 0) " · " + fileSizeLabel(f.size) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (opening == f.kind) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { onOpen(f.kind) }, enabled = opening == null) { Text("View") }
                    }
                }
            }
        }
    }
}

private fun fileSizeLabel(bytes: Long): String =
    if (bytes >= 1_048_576) String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    else "${maxOf(1, bytes / 1024)} KB"

/** Hand the downloaded PDF to the phone's PDF viewer through our FileProvider. */
private fun openPdf(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Install a PDF viewer to open this file.", Toast.LENGTH_LONG).show()
    }
}
