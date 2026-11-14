package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import ph.edu.mmsu.ee.grades.data.Assessment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Per-class management in the app (phase 3): requirements, enrolment and
 * final status, new / edited exams and problem sets. Same admin.* routes as
 * the portal. Nothing here deletes scores or student work.
 */

private fun s(e: JsonElement?): String = (e as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun nowManila(plusMinutes: Long = 0): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Manila") }
    .format(Date(System.currentTimeMillis() + plusMinutes * 60_000))
private fun num(d: Double?): String = when {
    d == null -> ""
    d % 1.0 == 0.0 -> d.toLong().toString()
    else -> d.toString()
}

/** One view model for every class-level change: run an action, then reload. */
class AdminClassManageViewModel : AdminToolViewModel() {
    var notice by mutableStateOf<String?>(null)
    var exams by mutableStateOf<AdmExamSetup?>(null)
        private set
    var sets by mutableStateOf<AdmPsSetup?>(null)
        private set

    fun loadExams(sid: String) = act { exams = repo.json.decodeFromJsonElement(AdmExamSetup.serializer(), call("admin.exams", buildJsonObject { put("sectionId", sid) })) }
    fun loadSets(sid: String) = act { sets = repo.json.decodeFromJsonElement(AdmPsSetup.serializer(), call("admin.assignments", buildJsonObject { put("sectionId", sid) })) }

    /** Runs [action]; the reply replaces the exam / problem-set lists when it is one of those. */
    fun send(action: String, payload: JsonObject, message: ((JsonElement) -> String?)? = null, done: () -> Unit = {}) = act {
        val out = call(action, payload)
        when {
            action.startsWith("admin.exam") -> exams = repo.json.decodeFromJsonElement(AdmExamSetup.serializer(), out)
            action.startsWith("admin.assignment") -> sets = repo.json.decodeFromJsonElement(AdmPsSetup.serializer(), out)
        }
        notice = message?.invoke(out)
        done()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(title: String, onBack: () -> Unit, busy: Boolean, error: String?, action: @Composable () -> Unit = {}, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { action() }
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { ErrorBanner(message = it) } }
            content()
        }
    }
}

@Composable
private fun Form(title: String, onDismiss: () -> Unit, enabled: Boolean, onSave: () -> Unit, saveLabel: String = "Save", content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } },
        confirmButton = { Button(onClick = onSave, enabled = enabled) { Text(saveLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun In(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, lines: Int = 1) =
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = lines == 1, minLines = lines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth()
    )

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = on, onCheckedChange = onChange)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pick(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) -> FilterChip(selected = id == selected, onClick = { onPick(id) }, label = { Text(label) }) }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickMany(title: String, options: List<AdmSibling>, picked: MutableMap<String, Boolean>) {
    if (options.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = picked[o.id] == true, onClick = { picked[o.id] = picked[o.id] != true }, label = { Text(o.name) }) }
    }
}

@Composable
private fun Ask(title: String, body: String, yes: String, onDismiss: () -> Unit, onYes: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
    confirmButton = { Button(onClick = { onYes(); onDismiss() }) { Text(yes) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
)

@Composable
private fun Told(text: String?, onDismiss: () -> Unit) {
    if (text != null) AlertDialog(onDismissRequest = onDismiss, text = { Text(text) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

/* ------------------------------ requirements ------------------------------ */

private val TYPES = listOf("Exam", "Quiz", "Assignment", "Problem Set", "Activity", "Project", "Recitation", "Laboratory", "Attendance", "Other")

@Composable
fun AdminRequirementsScreen(sectionId: String, onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val section: AdminSectionViewModel = viewModel()
    val vm: AdminClassManageViewModel = viewModel()
    LaunchedEffect(sectionId) { section.start(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    val data = section.data
    var editing by remember { mutableStateOf<Assessment?>(null) }
    val scored: (String) -> Int = { id -> data?.students.orEmpty().count { s(it.scores[id]).isNotBlank() } }

    Shell("Requirements", onBack, vm.busy || section.loading, vm.error ?: section.error, action = {
        TextButton(onClick = { editing = Assessment(id = "", name = "", type = "Quiz") }) { Text("+ New") }
    }) {
        if (data == null) return@Shell
        item {
            Text("Total weight: ${num(data.assessments.sumOf { it.weight })}%" + if (data.assessments.sumOf { it.weight }.let { it > 0 && it != 100.0 }) " (should be 100)" else "",
                style = MaterialTheme.typography.bodySmall)
        }
        items(data.assessments, key = { it.id }) { a ->
            Column(Modifier.fillMaxWidth().clickable { editing = a }.padding(vertical = 6.dp)) {
                Text(a.name, fontWeight = FontWeight.Medium)
                Text(listOf(a.type, "max ${num(a.max)}", "weight ${num(a.weight)}%", a.date, if (a.published) "" else "hidden from students", "${scored(a.id)} scored")
                    .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }

    editing?.let { a ->
        val isNew = a.id.isBlank()
        var name by remember(a) { mutableStateOf(a.name) }
        var type by remember(a) { mutableStateOf(a.type) }
        var max by remember(a) { mutableStateOf(if (isNew) "100" else num(a.max)) }
        var weight by remember(a) { mutableStateOf(if (isNew) "0" else num(a.weight)) }
        var date by remember(a) { mutableStateOf(a.date) }
        var published by remember(a) { mutableStateOf(a.published) }
        var askDelete by remember { mutableStateOf(false) }
        val count = if (isNew) 0 else scored(a.id)
        Form(if (isNew) "New requirement" else "Edit requirement", { editing = null }, name.isNotBlank() && max.toDoubleOrNull() != null && !vm.busy, onSave = {
            vm.send(if (isNew) "admin.assessment.create" else "admin.assessment.update", buildJsonObject {
                put("sectionId", sectionId); if (!isNew) put("id", a.id)
                put("name", name.trim()); put("type", type); put("max", max.toDouble()); put("weight", weight.toDoubleOrNull() ?: 0.0)
                put("date", date.trim()); put("published", published)
            }) { editing = null; section.refresh() }
        }) {
            In("Name", name, { name = it })
            Text("Type", style = MaterialTheme.typography.labelLarge)
            Pick(TYPES.map { it to it }, type) { type = it }
            In("Max score", max, { max = it }, number = true)
            In("Weight (%)", weight, { weight = it }, number = true)
            In("Date (yyyy-mm-dd, optional)", date, { date = it })
            Toggle("Shown to students", published) { published = it }
            if (!isNew) {
                HorizontalDivider()
                if (count == 0) TextButton(onClick = { askDelete = true }) { Text("Delete requirement", color = MaterialTheme.colorScheme.error) }
                else Text("$count student(s) have a score here, so it can't be deleted from the app.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (askDelete) Ask("Delete “${a.name}”?", "Its gradebook column is removed. Nobody has a score in it yet.", "Delete", { askDelete = false }) {
            vm.send("admin.assessment.delete", buildJsonObject { put("sectionId", sectionId); put("id", a.id) }) { editing = null; section.refresh() }
        }
    }
    Told(vm.notice) { vm.notice = null }
}

/* -------------------------------- enrolment ------------------------------- */

@Composable
fun AdminEnrolmentScreen(sectionId: String, onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val section: AdminSectionViewModel = viewModel()
    val vm: AdminClassManageViewModel = viewModel()
    LaunchedEffect(sectionId) { section.start(sectionId) }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    val data = section.data
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ph.edu.mmsu.ee.grades.data.RosterEntry?>(null) }

    Shell("Enrolment (${data?.students?.size ?: 0})", onBack, vm.busy || section.loading, vm.error ?: section.error, action = {
        TextButton(onClick = { vm.send("admin.section.sync", buildJsonObject { put("sectionId", sectionId) }, { "Roster synced with the registry." }) { section.refresh() } }) { Text("Sync") }
        TextButton(onClick = { adding = true }) { Text("+ Add") }
    }) {
        if (data == null) return@Shell
        item { Text("Final status: INC or Dropped show on the class record and Summary of Grades. Blank = computed from the grade.", style = MaterialTheme.typography.bodySmall) }
        items(data.students, key = { it.studentId }) { st ->
            val current = when (st.standing.uppercase()) { "INC" -> "INC"; "DROPPED" -> "Dropped"; else -> "" }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.displayName, fontWeight = FontWeight.Medium)
                        Text(st.studentId, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { removing = st }) { Text("Remove") }
                }
                Pick(listOf("" to "—", "INC" to "INC", "Dropped" to "Dropped"), current) { pick ->
                    if (pick != current) vm.send("admin.section.status", buildJsonObject { put("sectionId", sectionId); put("studentId", st.studentId); put("status", pick) }) { section.refresh() }
                }
            }
            HorizontalDivider()
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        Form("Add students", { adding = false }, text.isNotBlank() && !vm.busy, saveLabel = "Enrol", onSave = {
            val ids = text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
            vm.send("admin.section.enroll", buildJsonObject { put("sectionId", sectionId); put("studentIds", JsonArray(ids.map { JsonPrimitive(it) })) }, { out ->
                val o = out as? JsonObject
                val missing = (o?.get("missing") as? JsonArray)?.map { s(it) }.orEmpty()
                "Enrolled ${s(o?.get("added"))}." + if (missing.isNotEmpty()) " Not in the registry (add them under Students first): ${missing.joinToString(", ")}" else ""
            }) { adding = false; section.refresh() }
        }) {
            Text("Student numbers separated by spaces, commas or new lines.", style = MaterialTheme.typography.bodySmall)
            In("Student numbers", text, { text = it }, lines = 4)
        }
    }
    removing?.let { st ->
        Ask("Remove ${st.displayName}?", "They leave this class roster. Their scores stay in the gradebook and come back if they are enrolled again.", "Remove", { removing = null }) {
            vm.send("admin.section.unenroll", buildJsonObject { put("sectionId", sectionId); put("studentId", st.studentId) }) { section.refresh() }
        }
    }
    Told(vm.notice) { vm.notice = null }
}

/* ---------------------------------- exams --------------------------------- */

/** "New exam" / bank status block shown above the exam list. */
@Composable
fun ExamSetupBlock(sectionId: String, onChanged: () -> Unit) {
    val vm: AdminClassManageViewModel = viewModel(key = "exam-setup-$sectionId")
    LaunchedEffect(sectionId) { vm.loadExams(sectionId) }
    val setup = vm.exams
    var creating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        vm.error?.let { ErrorBanner(message = it) }
        val bank = setup?.bank
        when {
            setup == null -> {}
            bank == null -> {
                Text("This subject has no question bank yet.")
                OutlinedButton(onClick = { vm.send("admin.exams.bank", buildJsonObject { put("sectionId", sectionId) }, { "Question bank made (Drive › Question Banks). Fill it in Google Sheets, then come back." }) }, enabled = !vm.busy) {
                    Text("Create question bank")
                }
            }
            else -> {
                Text("Question bank: ${bank.total} questions · " + bank.summary.joinToString(", ") { "${it.unit} ${it.level} ${it.count}" }, style = MaterialTheme.typography.bodySmall)
                bank.problems.take(3).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { creating = true }, enabled = bank.total > 0 && !vm.busy) { Text("+ New exam") }
            }
        }
    }
    if (creating && setup?.bank != null) NewExamDialog(setup, vm.busy, onDismiss = { creating = false }) { payload ->
        vm.send("admin.exam.create", buildJsonObject { put("sectionId", sectionId); payload.forEach { (k, v) -> put(k, v) } }, { "Exam created. Students see it when it opens." }) {
            creating = false; onChanged()
        }
    }
    Told(vm.notice) { vm.notice = null }
}

@Composable
private fun NewExamDialog(setup: AdmExamSetup, busy: Boolean, onDismiss: () -> Unit, onSave: (JsonObject) -> Unit) {
    var title by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("60") }
    var opens by remember { mutableStateOf(setup.now.ifBlank { nowManila() }) }
    var closes by remember { mutableStateOf(nowManila(24 * 60)) }
    var maxExits by remember { mutableStateOf("3") }
    var immediate by remember { mutableStateOf(false) }
    var column by remember { mutableStateOf("new") }
    val counts = remember { mutableStateMapOf<String, String>() }
    val also = remember { mutableStateMapOf<String, Boolean>() }
    val total = setup.bank?.summary.orEmpty().sumOf { (counts[it.unit + "|" + it.level]?.toIntOrNull() ?: 0) }
    Form("New exam", onDismiss, title.isNotBlank() && total > 0 && !busy, saveLabel = "Create", onSave = {
        onSave(buildJsonObject {
            put("title", title.trim()); put("duration", duration.toIntOrNull() ?: 60)
            put("opensAt", opens.trim()); put("closesAt", closes.trim())
            put("maxExits", maxExits.toIntOrNull() ?: 0); put("showScore", if (immediate) "IMMEDIATE" else "RELEASED")
            put("blueprint", JsonArray(setup.bank?.summary.orEmpty().mapNotNull { r ->
                val c = counts[r.unit + "|" + r.level]?.toIntOrNull() ?: 0
                if (c > 0) buildJsonObject { put("unit", r.unit); put("level", r.level); put("count", c) } else null
            }))
            put("column", buildJsonObject { if (column == "new") put("mode", "new") else { put("mode", "existing"); put("assessmentId", column) } })
            put("alsoSectionIds", JsonArray(also.filterValues { it }.keys.map { JsonPrimitive(it) }))
        })
    }) {
        In("Title", title, { title = it })
        Text("Questions to draw (random per student)", style = MaterialTheme.typography.labelLarge)
        setup.bank?.summary.orEmpty().forEach { r ->
            val k = r.unit + "|" + r.level
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${r.unit} · ${r.level} (${r.count} in bank)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(counts[k].orEmpty(), { counts[k] = it.filter(Char::isDigit) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(80.dp))
            }
        }
        Text("$total items", fontWeight = FontWeight.Medium)
        In("Time limit (minutes)", duration, { duration = it }, number = true)
        In("Opens (yyyy-mm-dd hh:mm, Manila)", opens, { opens = it })
        In("Closes (yyyy-mm-dd hh:mm, Manila)", closes, { closes = it })
        In("Lock after this many exits (0 = never)", maxExits, { maxExits = it }, number = true)
        Toggle("Show the score right after submitting", immediate) { immediate = it }
        Text("Gradebook column", style = MaterialTheme.typography.labelLarge)
        Pick(listOf("new" to "New column") + setup.columns.map { it.id to it.name }, column) { column = it }
        PickMany("Also give it to these sections", setup.siblings, also)
    }
}

/** Edit / remove for one exam (from the results screen). */
@Composable
fun EditExamDialog(sectionId: String, exam: AdmExam, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val vm: AdminClassManageViewModel = viewModel(key = "exam-edit-${exam.id}")
    var title by remember { mutableStateOf(exam.title) }
    var duration by remember { mutableStateOf(exam.duration.toString()) }
    var opens by remember { mutableStateOf(exam.opensAt) }
    var closes by remember { mutableStateOf(exam.closesAt) }
    var maxExits by remember { mutableStateOf(exam.maxExits.toString()) }
    var immediate by remember { mutableStateOf(exam.showScore == "IMMEDIATE") }
    var askRemove by remember { mutableStateOf(false) }
    Form("Edit exam", onDismiss, title.isNotBlank() && !vm.busy, onSave = {
        vm.send("admin.exam.update", buildJsonObject {
            put("sectionId", sectionId); put("id", exam.id); put("title", title.trim()); put("duration", duration.toIntOrNull() ?: exam.duration)
            put("opensAt", opens.trim()); put("closesAt", closes.trim()); put("maxExits", maxExits.toIntOrNull() ?: 0)
            put("showScore", if (immediate) "IMMEDIATE" else "RELEASED")
        }) { onDismiss(); onChanged() }
    }) {
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        In("Title", title, { title = it })
        In("Time limit (minutes)", duration, { duration = it }, number = true)
        In("Opens (yyyy-mm-dd hh:mm)", opens, { opens = it })
        In("Closes (yyyy-mm-dd hh:mm)", closes, { closes = it })
        In("Lock after this many exits (0 = never)", maxExits, { maxExits = it }, number = true)
        Toggle("Show the score right after submitting", immediate) { immediate = it }
        Text("The questions can't change once an exam exists.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        if (exam.counts.started == 0 && exam.counts.submitted == 0) TextButton(onClick = { askRemove = true }) { Text("Remove exam", color = MaterialTheme.colorScheme.error) }
        else Text("Students have taken it, so it can only be removed on the web portal.", style = MaterialTheme.typography.bodySmall)
    }
    if (askRemove) Ask("Remove “${exam.title}”?", "Nobody has started it. Its gradebook column stays.", "Remove", { askRemove = false }) {
        vm.send("admin.exam.remove", buildJsonObject { put("sectionId", sectionId); put("id", exam.id) }) { onDismiss(); onChanged() }
    }
}

/* ------------------------------ problem sets ------------------------------ */

@Composable
fun ProblemSetSetupBlock(sectionId: String, onChanged: () -> Unit) {
    val vm: AdminClassManageViewModel = viewModel(key = "ps-setup-$sectionId")
    LaunchedEffect(sectionId) { vm.loadSets(sectionId) }
    var creating by remember { mutableStateOf(false) }
    vm.error?.let { ErrorBanner(message = it) }
    Button(onClick = { creating = true }, enabled = vm.sets != null && !vm.busy) { Text("+ New problem set") }
    val setup = vm.sets
    if (creating && setup != null) ProblemSetDialog(sectionId, setup, null, vm, onDismiss = { creating = false }, onChanged = onChanged)
    Told(vm.notice) { vm.notice = null }
}

/** Edit button content for one problem set in the list. */
@Composable
fun EditProblemSetDialog(sectionId: String, id: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val vm: AdminClassManageViewModel = viewModel(key = "ps-edit-$id")
    LaunchedEffect(id) { vm.loadSets(sectionId) }
    val setup = vm.sets ?: return
    val ps = setup.assignments.firstOrNull { it.id == id } ?: return
    ProblemSetDialog(sectionId, setup, ps, vm, onDismiss, onChanged)
}

@Composable
private fun ProblemSetDialog(sectionId: String, setup: AdmPsSetup, ps: AdmPs?, vm: AdminClassManageViewModel, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var title by remember { mutableStateOf(ps?.title.orEmpty()) }
    var instructions by remember { mutableStateOf(ps?.instructions.orEmpty()) }
    var due by remember { mutableStateOf(ps?.dueAt ?: nowManila(7 * 24 * 60).take(10) + " 23:59") }
    var allowLate by remember { mutableStateOf(ps?.allowLate ?: false) }
    var open by remember { mutableStateOf(ps?.open ?: true) }
    var material by remember { mutableStateOf(ps?.materialId.orEmpty()) }
    var column by remember { mutableStateOf("new") }
    var max by remember { mutableStateOf("100") }
    var askRemove by remember { mutableStateOf(false) }
    val also = remember { mutableStateMapOf<String, Boolean>() }
    Form(if (ps == null) "New problem set" else "Edit problem set", onDismiss, title.isNotBlank() && !vm.busy, saveLabel = if (ps == null) "Create" else "Save", onSave = {
        vm.send(if (ps == null) "admin.assignment.create" else "admin.assignment.update", buildJsonObject {
            put("sectionId", sectionId); ps?.let { put("id", it.id) }
            put("title", title.trim()); put("instructions", instructions.trim()); put("dueAt", due.trim())
            put("allowLate", allowLate); put("open", open); put("materialId", material)
            if (ps == null) {
                put("column", buildJsonObject { if (column == "new") { put("mode", "new"); put("max", max.toDoubleOrNull() ?: 100.0) } else { put("mode", "existing"); put("assessmentId", column) } })
                put("alsoSectionIds", JsonArray(also.filterValues { it }.keys.map { JsonPrimitive(it) }))
            }
        }) { onDismiss(); onChanged() }
    }) {
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        In("Title", title, { title = it })
        In("Instructions", instructions, { instructions = it }, lines = 3)
        In("Due (yyyy-mm-dd hh:mm, Manila)", due, { due = it })
        Toggle("Accept late files (marked late)", allowLate) { allowLate = it }
        Toggle("Shown to students", open) { open = it }
        Text("Problem-set PDF (upload it under 📄 Materials first)", style = MaterialTheme.typography.labelLarge)
        Pick(listOf("" to "None") + setup.materials.filter { it.type == "PROBLEM_SET" }.map { it.id to it.title }, material) { material = it }
        if (ps == null) {
            Text("Gradebook column", style = MaterialTheme.typography.labelLarge)
            Pick(listOf("new" to "New column") + setup.columns.map { it.id to it.name }, column) { column = it }
            if (column == "new") In("Max score", max, { max = it }, number = true)
            PickMany("Also give it to these sections", setup.siblings, also)
        } else {
            HorizontalDivider()
            if (ps.counts.submitted == 0) TextButton(onClick = { askRemove = true }) { Text("Remove problem set", color = MaterialTheme.colorScheme.error) }
            else Text("Students have submitted files, so it can only be removed on the web portal.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (askRemove && ps != null) Ask("Remove “${ps.title}”?", "Nobody has submitted yet. Its gradebook column stays.", "Remove", { askRemove = false }) {
        vm.send("admin.assignment.remove", buildJsonObject { put("sectionId", sectionId); put("id", ps.id) }) { onDismiss(); onChanged() }
    }
}
