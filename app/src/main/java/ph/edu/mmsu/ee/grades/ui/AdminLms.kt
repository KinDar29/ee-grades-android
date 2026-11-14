package ph.edu.mmsu.ee.grades.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ph.edu.mmsu.ee.grades.ServiceLocator
import ph.edu.mmsu.ee.grades.data.ApiException
import ph.edu.mmsu.ee.grades.data.Submission
import java.io.File
import java.util.Locale

/*
 * Admin tools in the app (phase 2): problem-set grading side by side, exam
 * results, score editing and materials. Every action is the portal's own
 * admin.* route, so rules and data are exactly the web portal's.
 */

/* --------------------------------- models -------------------------------- */

@Serializable data class AdmCounts(
    val students: Int = 0, val submitted: Int = 0, val late: Int = 0, val graded: Int = 0, val files: Int = 0,
    val started: Int = 0
)
@Serializable data class AdmAssignment(
    val id: String = "", val title: String = "", val dueAt: String = "", val materialId: String = "",
    val columnName: String = "", val max: Double? = null, val open: Boolean = true, val counts: AdmCounts = AdmCounts()
)
@Serializable data class AdmAssignments(val assignments: List<AdmAssignment> = emptyList())
@Serializable data class AdmSubStudent(
    val studentId: String = "", val name: String = "", val submission: Submission? = null, val score: Double? = null
)
@Serializable data class AdmSubs(
    val assignment: AdmAssignment = AdmAssignment(), val max: Double? = null,
    val students: List<AdmSubStudent> = emptyList(), val problems: List<String> = emptyList()
)
@Serializable data class AdmExam(
    val id: String = "", val title: String = "", val items: Int = 0, val duration: Int = 0,
    val opensAt: String = "", val closesAt: String = "", val state: String = "", val released: Boolean = false,
    val showScore: String = "", val maxExits: Int = 0, val columnName: String = "", val counts: AdmCounts = AdmCounts()
)
@Serializable data class AdmExams(val exams: List<AdmExam> = emptyList())
@Serializable data class AdmResultRow(
    val studentId: String = "", val name: String = "", val status: String = "", val submittedAt: String = "",
    val score: Double? = null, val items: Int = 0, val away: Int = 0
)
@Serializable data class AdmStats(val submitted: Int = 0, val mean: Double? = null, val high: Double? = null, val low: Double? = null)
@Serializable data class AdmItem(
    val id: String = "", val unit: String = "", val level: String = "", val answer: String = "",
    val text: String = "", val shown: Int = 0, val correct: Int = 0, val percent: Double = 0.0
)
@Serializable data class AdmGroup(val unit: String = "", val level: String = "", val percent: Double = 0.0, val shown: Int = 0)
@Serializable data class AdmResults(
    val exam: AdmExam = AdmExam(), val students: List<AdmResultRow> = emptyList(), val stats: AdmStats = AdmStats(),
    val analysis: List<AdmItem> = emptyList(), val groups: List<AdmGroup> = emptyList()
)
@Serializable data class AdmMaterial(
    val id: String = "", val type: String = "", val typeLabel: String = "", val title: String = "",
    val group: String = "", val name: String = "", val size: Long = 0, val visible: Boolean = true,
    val visibleFrom: String = "", val released: Boolean = true
)
@Serializable data class AdmType(val id: String = "", val label: String = "")
@Serializable data class AdmMaterials(val materials: List<AdmMaterial> = emptyList(), val types: List<AdmType> = emptyList())

private fun n(d: Double?): String = when {
    d == null -> "—"
    d % 1.0 == 0.0 -> d.toLong().toString()
    else -> String.format(Locale.US, "%.1f", d)
}

/* ------------------------------ shared view model ------------------------------ */

/** Loads one admin.* reply, runs actions, and reports errors. */
open class AdminToolViewModel : ViewModel() {
    protected val repo = ServiceLocator.repo
    var busy by mutableStateOf(false)
        protected set
    var error by mutableStateOf<String?>(null)
        protected set
    var authLost by mutableStateOf<String?>(null)
        protected set

    protected fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            error = null
            try { block() } catch (e: ApiException) {
                if (e.authRequired) authLost = e.message else error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong. Please try again."
            } finally { busy = false }
        }
    }

    protected suspend fun call(action: String, payload: JsonObject): JsonElement = repo.adminCall(action, payload)
}

/* ------------------------------ problem sets ------------------------------ */

class AdminProblemSetsViewModel : AdminToolViewModel() {
    var list by mutableStateOf<List<AdmAssignment>>(emptyList())
        private set
    var subs by mutableStateOf<AdmSubs?>(null)
        private set
    var pdf by mutableStateOf<File?>(null)
        private set
    var pdfFor by mutableStateOf("")
        private set
    private var sectionId = ""

    fun load(sid: String) {
        sectionId = sid
        act { list = repo.json.decodeFromJsonElement(AdmAssignments.serializer(), call("admin.assignments", buildJsonObject { put("sectionId", sid) })).assignments }
    }

    fun open(a: AdmAssignment) = act {
        subs = repo.json.decodeFromJsonElement(AdmSubs.serializer(), call("admin.submissions", buildJsonObject {
            put("sectionId", sectionId); put("assignmentId", a.id)
        }))
    }

    fun close() { subs = null; pdf = null; pdfFor = "" }

    fun showFile(cacheDir: File, studentId: String) = act {
        val a = subs?.assignment ?: return@act
        pdf = null
        pdfFor = studentId
        pdf = repo.adminPdf("admin.submission", buildJsonObject {
            put("sectionId", sectionId); put("assignmentId", a.id); put("studentId", studentId)
        }, cacheDir)
    }

    fun showSet(cacheDir: File) = act {
        val a = subs?.assignment ?: return@act
        if (a.materialId.isBlank()) return@act
        pdf = null
        pdfFor = "set"
        pdf = repo.adminPdf("admin.material", buildJsonObject { put("sectionId", sectionId); put("id", a.materialId) }, cacheDir)
    }

    fun grade(studentId: String, value: String, then: () -> Unit) = act {
        val a = subs?.assignment ?: return@act
        val out = repo.json.decodeFromJsonElement(AdmSubs.serializer(), call("admin.submissions.grade", buildJsonObject {
            put("sectionId", sectionId); put("assignmentId", a.id)
            put("changes", JsonArray(listOf(buildJsonObject { put("studentId", studentId); put("value", value.trim()) })))
        }))
        subs = out
        if (out.problems.isNotEmpty()) error = out.problems.joinToString("; ") else then()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminProblemSetsScreen(sectionId: String, onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminProblemSetsViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(sectionId) { vm.load(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    val subs = vm.subs
    if (subs != null) {
        GraderScreen(vm, subs, context, onBack = { vm.close(); vm.load(sectionId) })
        return
    }
    var editSet by remember { mutableStateOf("") }
    if (editSet.isNotBlank()) EditProblemSetDialog(sectionId, editSet, onDismiss = { editSet = "" }, onChanged = { vm.load(sectionId) })
    ToolScaffold("Problem sets", onBack, vm.busy, vm.error) {
        item { ProblemSetSetupBlock(sectionId, onChanged = { vm.load(sectionId) }) }
        if (vm.list.isEmpty() && !vm.busy) item { Text("No problem sets yet.", Modifier.padding(16.dp)) }
        items(vm.list, key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth().clickable { vm.open(a) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editSet = a.id }) { Text("Edit") }
                    }
                    Text("Due ${whenLabel(a.dueAt)} · column “${a.columnName}”", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${a.counts.submitted}/${a.counts.students} submitted · ${a.counts.late} late · ${a.counts.graded} graded",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Side by side on a phone means stacked: the PDF on top, the score box under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GraderScreen(vm: AdminProblemSetsViewModel, subs: AdmSubs, context: Context, onBack: () -> Unit) {
    val withFiles = subs.students.filter { it.submission != null && it.submission.fileDeleted.not() }
    var idx by remember { mutableStateOf(withFiles.indexOfFirst { it.score == null }.coerceAtLeast(0)) }
    val st = withFiles.getOrNull(idx)
    var score by remember(st?.studentId, st?.score) { mutableStateOf(st?.score?.let { n(it) } ?: "") }
    LaunchedEffect(st?.studentId) { if (st != null) vm.showFile(context.cacheDir, st.studentId) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text(subs.assignment.title, maxLines = 1); Text("${withFiles.count { it.score != null }}/${withFiles.size} graded", style = MaterialTheme.typography.bodySmall) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        if (st == null) {
            MessageBlock(title = "No files to grade", body = "Nobody has a file for this problem set yet.", modifier = Modifier.padding(pad))
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = vm.pdfFor != "set", onClick = { vm.showFile(context.cacheDir, st.studentId) }, label = { Text("Answer") })
                if (subs.assignment.materialId.isNotBlank()) {
                    FilterChip(selected = vm.pdfFor == "set", onClick = { vm.showSet(context.cacheDir) }, label = { Text("Problem set") })
                }
            }
            val f = vm.pdf
            if (f == null) LoadingBlock(Modifier.weight(1f)) else PdfPages(f, Modifier.weight(1f).fillMaxWidth())
            HorizontalDivider()
            Column(Modifier.padding(12.dp)) {
                Text("${idx + 1} of ${withFiles.size} · ${st.name}" + if (st.submission?.late == true) " · late" else "", fontWeight = FontWeight.SemiBold)
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = score, onValueChange = { score = it }, singleLine = true,
                        label = { Text("Score" + (subs.max?.let { " / ${n(it)}" } ?: "")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.grade(st.studentId, score) { if (idx < withFiles.size - 1) idx++ } }),
                        modifier = Modifier.width(140.dp)
                    )
                    OutlinedButton(onClick = { if (idx > 0) idx-- }, enabled = idx > 0) { Text("←") }
                    Button(onClick = { vm.grade(st.studentId, score) { if (idx < withFiles.size - 1) idx++ } }, enabled = !vm.busy) {
                        Text(if (vm.busy) "Saving…" else "Save & next")
                    }
                }
            }
        }
    }
}

/* ---------------------------------- exams --------------------------------- */

class AdminExamsViewModel : AdminToolViewModel() {
    var list by mutableStateOf<List<AdmExam>>(emptyList())
        private set
    var results by mutableStateOf<AdmResults?>(null)
        private set
    private var sectionId = ""

    fun load(sid: String) {
        sectionId = sid
        act { list = repo.json.decodeFromJsonElement(AdmExams.serializer(), call("admin.exams", buildJsonObject { put("sectionId", sid) })).exams }
    }

    fun open(examId: String) = act { results = res("admin.exam.results", examId) }
    fun release() = act { results?.let { results = res("admin.exam.release", it.exam.id) } }
    fun reset(studentId: String) = act {
        val id = results?.exam?.id ?: return@act
        call("admin.exam.reset", buildJsonObject { put("sectionId", sectionId); put("examId", id); put("studentId", studentId) })
        results = res("admin.exam.results", id)
    }
    fun close() { results = null; load(sectionId) }

    private suspend fun res(action: String, examId: String) =
        repo.json.decodeFromJsonElement(AdmResults.serializer(), call(action, buildJsonObject { put("sectionId", sectionId); put("examId", examId) }))
}

@Composable
fun AdminExamsScreen(sectionId: String, onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminExamsViewModel = viewModel()
    LaunchedEffect(sectionId) { vm.load(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    val r = vm.results
    if (r != null) {
        ExamResultsScreen(vm, r)
        return
    }
    var editExam by remember { mutableStateOf<AdmExam?>(null) }
    editExam?.let { e -> EditExamDialog(sectionId, e, onDismiss = { editExam = null }, onChanged = { vm.load(sectionId) }) }
    ToolScaffold("Exams", onBack, vm.busy, vm.error) {
        item { ExamSetupBlock(sectionId, onChanged = { vm.load(sectionId) }) }
        if (vm.list.isEmpty() && !vm.busy) item { Text("No exams yet.", Modifier.padding(16.dp)) }
        items(vm.list, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth().clickable { vm.open(e.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editExam = e }) { Text("Edit") }
                    }
                    Text("${e.items} items · ${e.duration} min · ${whenLabel(e.opensAt)} → ${whenLabel(e.closesAt)}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${e.state} · ${e.counts.submitted}/${e.counts.students} submitted · ${if (e.released) "scores released" else "scores hidden"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ExamResultsScreen(vm: AdminExamsViewModel, r: AdmResults) {
    var resetFor by remember { mutableStateOf<AdmResultRow?>(null) }
    var askRelease by remember { mutableStateOf(false) }
    ToolScaffold(r.exam.title, { vm.close() }, vm.busy, vm.error) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("${r.stats.submitted} submitted · average ${n(r.stats.mean)}% · high ${n(r.stats.high)}% · low ${n(r.stats.low)}%")
                    Spacer(Modifier.height(8.dp))
                    if (r.exam.released) Text("Scores are released to students.", color = MaterialTheme.colorScheme.primary)
                    else Button(onClick = { askRelease = true }, enabled = !vm.busy) { Text("Release scores") }
                }
            }
        }
        item { Text("Students", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(r.students, key = { it.studentId }) { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.Medium)
                    Text(
                        s.status.replace('_', ' ') + (if (s.away > 0) " · left exam ${s.away}×" else "") +
                            (s.score?.let { " · ${n(it)}/${s.items}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.status == "locked") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (s.status != "not_started") TextButton(onClick = { resetFor = s }) { Text("Reset") }
            }
        }
        if (r.groups.isNotEmpty()) {
            item { Text("By unit × level", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(r.groups) { g -> Text("${g.unit} · ${g.level}: ${n(g.percent)}% correct (${g.shown} answers)") }
        }
        if (r.analysis.isNotEmpty()) {
            item { Text("Hardest questions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(r.analysis.sortedBy { it.percent }.take(10), key = { "q" + it.id }) { q ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${n(q.percent)}% correct · ${q.id} · answer ${q.answer}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                    Text(q.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
    resetFor?.let { s ->
        AlertDialog(
            onDismissRequest = { resetFor = null },
            title = { Text("Reset ${s.name}'s attempt?") },
            text = { Text("Their answers and score for this exam are removed and they can take it again from the start.") },
            confirmButton = { Button(onClick = { vm.reset(s.studentId); resetFor = null }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetFor = null }) { Text("Cancel") } }
        )
    }
    if (askRelease) {
        AlertDialog(
            onDismissRequest = { askRelease = false },
            title = { Text("Release scores?") },
            text = { Text("Students will see their score, and it is written to the gradebook column.") },
            confirmButton = { Button(onClick = { vm.release(); askRelease = false }) { Text("Release") } },
            dismissButton = { TextButton(onClick = { askRelease = false }) { Text("Cancel") } }
        )
    }
}

/* --------------------------------- scores --------------------------------- */

class AdminScoresViewModel : AdminToolViewModel() {
    var saved by mutableStateOf(0)
        private set
    fun save(sectionId: String, assessmentId: String, changes: Map<String, String>, then: () -> Unit) = act {
        val arr = JsonArray(changes.map { (sid, v) ->
            buildJsonObject { put("studentId", sid); put("assessmentId", assessmentId); put("value", v.trim()) }
        })
        val out = call("admin.scores.save", buildJsonObject { put("sectionId", sectionId); put("changes", arr) }) as JsonObject
        saved = out["saved"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: changes.size
        val problems = (out["problems"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        if (problems.isNotEmpty()) error = problems.joinToString("; ")
        then()
    }
}

/** Type scores for one requirement of the class (uses the class data already loaded). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminScoresScreen(sectionId: String, onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val section: AdminSectionViewModel = viewModel()
    val vm: AdminScoresViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(sectionId) { section.start(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    val data = section.data
    var pick by remember { mutableStateOf("") }
    val edits = remember(pick) { mutableStateMapOf<String, String>() }
    val a = data?.assessments?.firstOrNull { it.id == pick }

    ToolScaffold(a?.name ?: "Edit scores", onBack, vm.busy || section.loading, vm.error) {
        if (data == null) return@ToolScaffold
        item {
            Text("Requirement", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                data.assessments.forEach { x -> FilterChip(selected = x.id == pick, onClick = { pick = x.id }, label = { Text(x.name) }) }
            }
        }
        if (a == null) return@ToolScaffold
        item {
            Text("Out of ${n(a.max)}. Leave blank for no score. Changes are saved when you tap Save.", style = MaterialTheme.typography.bodySmall)
        }
        items(data.students, key = { it.studentId }) { s ->
            val current = (s.scores[a.id] as? JsonPrimitive)?.contentOrNull.orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.displayName, Modifier.weight(1f))
                OutlinedTextField(
                    value = edits[s.studentId] ?: current,
                    onValueChange = { edits[s.studentId] = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp)
                )
            }
        }
        item {
            Button(
                onClick = {
                    vm.save(sectionId, a.id, edits.toMap()) {
                        Toast.makeText(context, "Saved ${vm.saved} score(s).", Toast.LENGTH_SHORT).show()
                        edits.clear(); section.refresh()
                    }
                },
                enabled = edits.isNotEmpty() && !vm.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(if (edits.isEmpty()) "No changes" else "Save ${edits.size} change(s)") }
        }
    }
}

/* -------------------------------- materials ------------------------------- */

class AdminMaterialsViewModel : AdminToolViewModel() {
    var data by mutableStateOf(AdmMaterials())
        private set
    var viewing by mutableStateOf<Pair<File, String>?>(null)
    private var sectionId = ""

    private fun decode(e: JsonElement) = repo.json.decodeFromJsonElement(AdmMaterials.serializer(), e)

    fun load(sid: String) { sectionId = sid; act { data = decode(call("admin.materials", buildJsonObject { put("sectionId", sid) })) } }
    fun setVisible(m: AdmMaterial, v: Boolean) = act {
        data = decode(call("admin.materials.update", buildJsonObject { put("sectionId", sectionId); put("id", m.id); put("visible", v) }))
    }
    fun remove(m: AdmMaterial) = act {
        data = decode(call("admin.materials.remove", buildJsonObject { put("sectionId", sectionId); put("id", m.id) }))
    }
    fun view(m: AdmMaterial, cacheDir: File) = act {
        viewing = repo.adminPdf("admin.material", buildJsonObject { put("sectionId", sectionId); put("id", m.id) }, cacheDir) to m.title
    }
    fun upload(uri: android.net.Uri, context: Context, type: String, title: String, group: String) = act {
        val resolver = context.contentResolver
        var name = "file.pdf"
        resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) name = c.getString(i) ?: name
        }
        val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes() } }
            ?: throw IllegalStateException("Could not read that file.")
        if (bytes.size > 20 * 1024 * 1024) throw IllegalStateException("The file is larger than 20 MB.")
        data = decode(call("admin.materials.upload", buildJsonObject {
            put("sectionId", sectionId); put("type", type); put("title", title.trim()); put("group", group.trim())
            put("visible", true); put("name", if (name.endsWith(".pdf", true)) name else "$name.pdf")
            put("mimeType", "application/pdf"); put("data", java.util.Base64.getEncoder().encodeToString(bytes))
        }))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminMaterialsScreen(sectionId: String, onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminMaterialsViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(sectionId) { vm.load(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    var type by remember { mutableStateOf("MODULE") }
    var title by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var removeIt by remember { mutableStateOf<AdmMaterial?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.upload(uri, context, type, title, group); title = "" }
    }
    vm.viewing?.let { (f, t) -> ViewOnlyPdf(file = f, title = t, onClose = { vm.viewing = null }) }

    ToolScaffold("Materials", onBack, vm.busy, vm.error) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a PDF (≤ 20 MB) to this class", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.data.types.ifEmpty { listOf(AdmType("MODULE", "Module"), AdmType("PROBLEM_SET", "Problem set"), AdmType("SYLLABUS", "Syllabus"), AdmType("TOS", "TOS"), AdmType("OTHER", "Other")) }
                            .forEach { t -> FilterChip(selected = type == t.id, onClick = { type = t.id }, label = { Text(t.label) }) }
                    }
                    OutlinedTextField(title, { title = it }, label = { Text("Title (blank = file name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(group, { group = it }, label = { Text("Week / unit (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { pick.launch(arrayOf("application/pdf")) }, enabled = !vm.busy) { Text(if (vm.busy) "Working…" else "Choose PDF and upload") }
                }
            }
        }
        items(vm.data.materials, key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { vm.view(m, context.cacheDir) }) {
                    Text(m.title, fontWeight = FontWeight.Medium)
                    Text(
                        listOf(m.typeLabel, m.group, if (!m.released && m.visible && m.visibleFrom.isNotBlank()) "shows from ${m.visibleFrom}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = m.visible, onCheckedChange = { vm.setVisible(m, it) }, enabled = !vm.busy)
                TextButton(onClick = { removeIt = m }) { Text("Remove") }
            }
        }
    }
    removeIt?.let { m ->
        AlertDialog(
            onDismissRequest = { removeIt = null },
            title = { Text("Remove “${m.title}”?") },
            text = { Text("Students will no longer see it in this class.") },
            confirmButton = { Button(onClick = { vm.remove(m); removeIt = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeIt = null }) { Text("Cancel") } }
        )
    }
}

/* -------------------------------- scaffold -------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    busy: Boolean,
    error: String?,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (busy) item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { ErrorBanner(message = it) } }
            content()
        }
    }
}

/** Buttons on the admin class page that open the tools above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminToolsRow(onTool: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { onTool("scores") }) { Text("✎ Scores") }
        OutlinedButton(onClick = { onTool("problemsets") }) { Text("📝 Problem sets") }
        OutlinedButton(onClick = { onTool("exams") }) { Text("🧪 Exams") }
        OutlinedButton(onClick = { onTool("materials") }) { Text("📄 Materials") }
        OutlinedButton(onClick = { onTool("requirements") }) { Text("📋 Requirements") }
        OutlinedButton(onClick = { onTool("enrolment") }) { Text("👤 Enrolment") }
    }
}
