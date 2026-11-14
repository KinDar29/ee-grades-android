package ph.edu.mmsu.ee.grades.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Admin management in the app (phase 3): students, semesters / subjects /
 * classes, portal settings, and per class: requirements, enrolment, new exams
 * and new problem sets. Every action is the portal's own admin.* route.
 * Anything that would throw away student work (deleting a requirement that
 * has scores, removing an exam someone started or a problem set someone
 * submitted) is refused here and left to the web portal.
 */

/* --------------------------------- models -------------------------------- */

@Serializable data class AdmStudent(
    val id: String = "", val lastName: String = "", val firstName: String = "", val middleName: String = "",
    val program: String = "", val yearLevel: String = "", val email: String = "", val status: String = "Active",
    val currentPassword: String = "", val hasOwnPassword: Boolean = false, val mustChange: Boolean = false,
    val lastLogin: String = ""
)
@Serializable data class AdmStudentPage(val total: Int = 0, val page: Int = 1, val items: List<AdmStudent> = emptyList())
@Serializable data class AdmSemester(val id: String = "", val name: String = "", val schoolYear: String = "", val term: String = "", val active: Boolean = false)
@Serializable data class AdmSubject(val id: String = "", val code: String = "", val title: String = "", val units: Double? = null, val description: String = "")
@Serializable data class AdmSection(
    val id: String = "", val name: String = "", val subjectId: String = "", val subjectCode: String = "",
    val instructor: String = "", val schedule: String = "", val room: String = "", val status: String = "Active", val enrolled: Int = 0
)
@Serializable data class AdmHomeData(
    val semesters: List<AdmSemester> = emptyList(), val semesterId: String = "",
    val subjects: List<AdmSubject> = emptyList(), val sections: List<AdmSection> = emptyList(),
    val settings: Map<String, String> = emptyMap(), val assessmentTypes: List<String> = emptyList()
)
@Serializable data class AdmBankRow(val unit: String = "", val level: String = "", val count: Int = 0)
@Serializable data class AdmBank(val id: String = "", val url: String = "", val total: Int = 0, val summary: List<AdmBankRow> = emptyList(), val problems: List<String> = emptyList())
@Serializable data class AdmColumn(val id: String = "", val name: String = "", val max: Double? = null, val type: String = "")
@Serializable data class AdmSibling(val id: String = "", val name: String = "")
@Serializable data class AdmMatLite(val id: String = "", val title: String = "", val type: String = "")
@Serializable data class AdmExamSetup(
    val bank: AdmBank? = null, val exams: List<AdmExam> = emptyList(), val columns: List<AdmColumn> = emptyList(),
    val siblings: List<AdmSibling> = emptyList(), val now: String = ""
)
@Serializable data class AdmPsSetup(
    val assignments: List<AdmPs> = emptyList(), val materials: List<AdmMatLite> = emptyList(),
    val columns: List<AdmColumn> = emptyList(), val siblings: List<AdmSibling> = emptyList(), val now: String = ""
)
@Serializable data class AdmPs(
    val id: String = "", val title: String = "", val instructions: String = "", val dueAt: String = "",
    val materialId: String = "", val allowLate: Boolean = false, val open: Boolean = true,
    val columnName: String = "", val counts: AdmCounts = AdmCounts()
)

private fun str(e: JsonElement?): String = (e as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun manilaNow(plusMinutes: Long = 0): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Manila") }
    .format(Date(System.currentTimeMillis() + plusMinutes * 60_000))
private fun JsonObjectBuilder.ids(key: String, list: Collection<String>) = put(key, JsonArray(list.map { JsonPrimitive(it) }))

/* ------------------------------ shared pieces ----------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageScaffold(
    title: String, onBack: () -> Unit, busy: Boolean, error: String?,
    actions: @Composable () -> Unit = {}, content: LazyListScope.() -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { actions() }
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { ErrorBanner(message = it) } }
            content()
        }
    }
}

/** A dialog with a scrolling form and Save / Cancel. */
@Composable
private fun FormDialog(
    title: String, onDismiss: () -> Unit, confirm: String = "Save", enabled: Boolean = true,
    onConfirm: () -> Unit, extra: (@Composable () -> Unit)? = null, content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
                extra?.invoke()
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = enabled) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MField(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, lines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = lines == 1, minLines = lines,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Confirm(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { Button(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Notice(text: String?, onDismiss: () -> Unit) {
    if (text == null) return
    AlertDialog(onDismissRequest = onDismiss, text = { Text(text) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, label) -> FilterChip(selected = id == selected, onClick = { onPick(id) }, label = { Text(label) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiChips(options: List<AdmSibling>, picked: Set<String>, onToggle: (String) -> Unit) {
    if (options.isEmpty()) return
    Text("Also add to these sections", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { s -> FilterChip(selected = s.id in picked, onClick = { onToggle(s.id) }, label = { Text(s.name) }) }
    }
}

/** Home screen buttons for the management screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminManageRow(onManage: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { onManage("students") }) { Text("👥 Students") }
        OutlinedButton(onClick = { onManage("catalog") }) { Text("🗂 Semesters & classes") }
        OutlinedButton(onClick = { onManage("settings") }) { Text("⚙ Portal settings") }
    }
}

/* -------------------------------- students -------------------------------- */

class AdminStudentsViewModel : AdminToolViewModel() {
    var items by mutableStateOf<List<AdmStudent>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var notice by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    private var page = 1

    fun search(more: Boolean = false) = act {
        page = if (more) page + 1 else 1
        val out = repo.json.decodeFromJsonElement(AdmStudentPage.serializer(), call("admin.students.list", buildJsonObject {
            put("q", query.trim()); put("page", page); put("pageSize", 100)
        }))
        items = if (more) items + out.items else out.items
        total = out.total
    }

    fun create(f: Map<String, String>, done: () -> Unit) = act {
        val out = call("admin.students.create", buildJsonObject { f.forEach { (k, v) -> put(k, v.trim()) } }) as JsonObject
        notice = "Added ${str(out["id"])}. Starting password: ${str(out["password"])}" +
            (if (str(out["emailed"]) == "true") " (sent to their e-mail)." else " — give it to the student; they must change it at first sign-in.")
        done(); search()
    }

    fun update(f: Map<String, String>, done: () -> Unit) = act {
        call("admin.students.update", buildJsonObject { f.forEach { (k, v) -> put(k, v.trim()) } })
        done(); search()
    }

    fun resetPassword(id: String) = act {
        val out = call("admin.students.resetPassword", buildJsonObject { put("studentId", id) }) as JsonObject
        notice = "New starting password for $id: ${str(out["password"])}" +
            (if (str(out["emailed"]) == "true") " (sent to their e-mail)." else ". They must change it at first sign-in.")
        search()
    }

    fun delete(id: String) = act {
        call("admin.students.delete", buildJsonObject { put("id", id) })
        notice = "$id removed."
        search()
    }

    fun importText(text: String, done: () -> Unit) = act {
        val out = call("admin.students.import", buildJsonObject { put("text", text) }) as JsonObject
        val skipped = (out["skipped"] as? JsonArray)?.size ?: str(out["skipped"]).toIntOrNull() ?: 0
        notice = "Added ${str(out["added"])}, updated ${str(out["updated"])}, skipped $skipped." +
            (str(out["emailed"]).takeIf { it.isNotBlank() && it != "0" }?.let { " Passwords e-mailed: $it." } ?: "")
        done(); search()
    }
}

@Composable
fun AdminStudentsScreen(onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminStudentsViewModel = viewModel()
    LaunchedEffect(Unit) { vm.search() }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    var editing by remember { mutableStateOf<AdmStudent?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    ManageScaffold("Students (${vm.total})", onBack, vm.busy, vm.error, actions = {
        TextButton(onClick = { importing = true }) { Text("Import") }
        TextButton(onClick = { adding = true }) { Text("Add") }
    }) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(vm.query, { vm.query = it }, label = { Text("Search number, name, program") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.search() }) { Text("Search") }
            }
        }
        items(vm.items, key = { it.id }) { s ->
            Column(Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = 6.dp)) {
                Text("${s.lastName}, ${s.firstName} ${s.middleName}".trim().trimEnd(','), fontWeight = FontWeight.Medium)
                Text(
                    listOf(s.id, s.program, s.yearLevel.takeIf { it.isNotBlank() }?.let { "Year $it" }.orEmpty(), s.status.takeIf { it != "Active" }.orEmpty(),
                        if (s.email.isBlank()) "no e-mail" else "")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()
        }
        if (vm.items.size < vm.total) item { OutlinedButton(onClick = { vm.search(more = true) }, modifier = Modifier.fillMaxWidth()) { Text("Show more") } }
    }

    if (adding) StudentDialog(null, vm.busy, onDismiss = { adding = false }) { vm.create(it) { adding = false } }
    editing?.let { s ->
        StudentDialog(s, vm.busy, onDismiss = { editing = null },
            onReset = { vm.resetPassword(s.id); editing = null },
            onDelete = { vm.delete(s.id); editing = null }) { vm.update(it) { editing = null } }
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        FormDialog("Import students", { importing = false }, confirm = "Import", enabled = text.isNotBlank() && !vm.busy,
            onConfirm = { vm.importText(text) { importing = false } }) {
            Text("One student per line: Student No., Last Name, First Name, Middle Name, Program, Year Level, E-mail. Commas or tabs. Existing numbers are updated; passwords are not touched.", style = MaterialTheme.typography.bodySmall)
            MField("Paste here", text, { text = it }, lines = 6)
        }
    }
    Notice(vm.notice) { vm.notice = null }
}

@Composable
private fun StudentDialog(
    s: AdmStudent?, busy: Boolean, onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onSave: (Map<String, String>) -> Unit
) {
    val f = remember(s?.id) {
        mutableStateMapOf(
            "id" to (s?.id ?: ""), "lastName" to (s?.lastName ?: ""), "firstName" to (s?.firstName ?: ""),
            "middleName" to (s?.middleName ?: ""), "program" to (s?.program ?: ""), "yearLevel" to (s?.yearLevel ?: ""),
            "email" to (s?.email ?: "")
        )
    }
    var active by remember(s?.id) { mutableStateOf(s?.status != "Inactive") }
    var ask by remember { mutableStateOf("") }
    FormDialog(
        if (s == null) "Add student" else s.id, onDismiss, enabled = !busy,
        onConfirm = { onSave(f.toMap() + if (s != null) mapOf("status" to if (active) "Active" else "Inactive") else emptyMap()) },
        extra = if (s == null) null else ({
            HorizontalDivider()
            Text(
                when {
                    s.hasOwnPassword -> "Uses their own password."
                    s.currentPassword.isNotBlank() -> "Starting password: ${s.currentPassword} (not changed yet)"
                    else -> "No password set."
                } + (if (s.lastLogin.isNotBlank()) " Last sign-in ${s.lastLogin}." else ""),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ask = "reset" }) { Text("Reset password") }
                TextButton(onClick = { ask = "delete" }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        })
    ) {
        if (s == null) MField("Student number (e.g. 25-050432)", f["id"]!!, { f["id"] = it })
        MField("Last name", f["lastName"]!!, { f["lastName"] = it })
        MField("First name", f["firstName"]!!, { f["firstName"] = it })
        MField("Middle name", f["middleName"]!!, { f["middleName"] = it })
        MField("Program", f["program"]!!, { f["program"] = it })
        MField("Year level", f["yearLevel"]!!, { f["yearLevel"] = it })
        MField("E-mail", f["email"]!!, { f["email"] = it })
        if (s != null) SwitchRow("Active (can sign in)", active) { active = it }
        if (s == null) Text("A unique starting password is made for them (e-mailed when there is an e-mail).", style = MaterialTheme.typography.bodySmall)
    }
    if (ask == "reset") Confirm("Reset ${s?.id}'s password?", "They get a new starting password and must change it when they sign in.", "Reset", { ask = "" }) { onReset?.invoke() }
    if (ask == "delete") Confirm("Delete ${s?.id}?", "The student is removed from the registry and from every class roster. Their scores stay in the class sheets.", "Delete", { ask = "" }) { onDelete?.invoke() }
}

/* ------------------------ semesters, subjects, classes ------------------------ */

class AdminCatalogViewModel : AdminToolViewModel() {
    var home by mutableStateOf<AdmHomeData?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    private var semesterId = ""

    fun load(sid: String = semesterId) = act {
        home = repo.json.decodeFromJsonElement(AdmHomeData.serializer(), call("admin.home", buildJsonObject { if (sid.isNotBlank()) put("semesterId", sid) }))
        semesterId = home?.semesterId.orEmpty()
    }

    fun saveSettings(values: Map<String, String>, done: () -> Unit) = act {
        call("admin.settings.save", buildJsonObject { put("values", buildJsonObject { values.forEach { (k, v) -> put(k, v.trim()) } }) })
        notice = "Saved. Students see the change the next time a page loads."
        done()
        home = repo.json.decodeFromJsonElement(AdmHomeData.serializer(), call("admin.home", buildJsonObject { if (semesterId.isNotBlank()) put("semesterId", semesterId) }))
    }

    fun save(action: String, fields: Map<String, Any?>, done: () -> Unit) = act {
        call(action, buildJsonObject {
            fields.forEach { (k, v) ->
                when (v) {
                    is Boolean -> put(k, v)
                    is Number -> put(k, v)
                    null -> {}
                    else -> put(k, v.toString().trim())
                }
            }
        })
        done()
        home = repo.json.decodeFromJsonElement(AdmHomeData.serializer(), call("admin.home", buildJsonObject { if (semesterId.isNotBlank()) put("semesterId", semesterId) }))
    }
}

@Composable
fun AdminCatalogScreen(onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminCatalogViewModel = viewModel()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    val h = vm.home
    var semester by remember { mutableStateOf<AdmSemester?>(null) }
    var subject by remember { mutableStateOf<AdmSubject?>(null) }
    var section by remember { mutableStateOf<AdmSection?>(null) }
    val activeId = h?.settings?.get("ACTIVE_SEMESTER_ID").orEmpty()

    ManageScaffold("Semesters & classes", onBack, vm.busy, vm.error) {
        if (h == null) return@ManageScaffold
        item { HeaderRow("Semesters") { semester = AdmSemester() } }
        items(h.semesters, key = { "sem" + it.id }) { s ->
            Row(Modifier.fillMaxWidth().clickable { semester = s }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = if (s.id == h.semesterId) FontWeight.Bold else FontWeight.Normal)
                    Text(listOf(s.schoolYear, s.term, if (s.id == activeId) "current" else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (s.id != h.semesterId) TextButton(onClick = { vm.load(s.id) }) { Text("Show classes") }
            }
        }
        item { HeaderRow("Classes · ${h.semesters.firstOrNull { it.id == h.semesterId }?.name.orEmpty()}") { section = AdmSection() } }
        items(h.sections, key = { "sec" + it.id }) { c ->
            Column(Modifier.fillMaxWidth().clickable { section = c }.padding(vertical = 4.dp)) {
                Text("${c.subjectCode} · ${c.name}", fontWeight = FontWeight.Medium)
                Text(listOf(c.instructor, c.schedule, c.room, "${c.enrolled} enrolled", c.status.takeIf { it != "Active" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { HeaderRow("Subjects") { subject = AdmSubject() } }
        items(h.subjects, key = { "sub" + it.id }) { s ->
            Column(Modifier.fillMaxWidth().clickable { subject = s }.padding(vertical = 4.dp)) {
                Text("${s.code} — ${s.title}", fontWeight = FontWeight.Medium)
                s.units?.let { Text("${it.toString().removeSuffix(".0")} units", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    semester?.let { s ->
        val isNew = s.id.isBlank()
        var name by remember(s) { mutableStateOf(s.name) }
        var sy by remember(s) { mutableStateOf(s.schoolYear) }
        var term by remember(s) { mutableStateOf(s.term) }
        var current by remember(s) { mutableStateOf(isNew || s.id == activeId) }
        FormDialog(if (isNew) "New semester" else "Edit semester", { semester = null }, enabled = name.isNotBlank() && !vm.busy, onConfirm = {
            vm.save(if (isNew) "admin.semester.create" else "admin.semester.update",
                mapOf("id" to s.id.ifBlank { null }, "name" to name, "schoolYear" to sy, "term" to term, "makeCurrent" to current, "active" to true)) { semester = null }
        }) {
            MField("Name (e.g. 2026-2027 First Semester)", name, { name = it })
            MField("School year", sy, { sy = it })
            MField("Term", term, { term = it })
            SwitchRow("Current semester (opens by default)", current) { current = it }
            if (isNew) Text("A Drive folder is made for it.", style = MaterialTheme.typography.bodySmall)
        }
    }

    subject?.let { s ->
        val isNew = s.id.isBlank()
        var code by remember(s) { mutableStateOf(s.code) }
        var title by remember(s) { mutableStateOf(s.title) }
        var units by remember(s) { mutableStateOf(s.units?.toString()?.removeSuffix(".0").orEmpty()) }
        var desc by remember(s) { mutableStateOf(s.description) }
        var askDelete by remember { mutableStateOf(false) }
        FormDialog(if (isNew) "New subject" else "Edit subject", { subject = null }, enabled = code.isNotBlank() && title.isNotBlank() && !vm.busy,
            onConfirm = {
                vm.save(if (isNew) "admin.subject.create" else "admin.subject.update",
                    mapOf("id" to s.id.ifBlank { null }, "code" to code, "title" to title, "units" to units, "description" to desc)) { subject = null }
            },
            extra = if (isNew) null else ({ TextButton(onClick = { askDelete = true }) { Text("Delete subject", color = MaterialTheme.colorScheme.error) } })
        ) {
            MField("Code (e.g. EE 121)", code, { code = it })
            MField("Title", title, { title = it })
            MField("Units", units, { units = it }, number = true)
            MField("Description", desc, { desc = it })
        }
        if (askDelete) Confirm("Delete ${s.code}?", "Only possible when no class uses this subject.", "Delete", { askDelete = false }) {
            vm.save("admin.subject.delete", mapOf("id" to s.id)) { subject = null }
        }
    }

    section?.let { c ->
        val isNew = c.id.isBlank()
        var subjectId by remember(c) { mutableStateOf(c.subjectId) }
        var name by remember(c) { mutableStateOf(c.name) }
        var instructor by remember(c) { mutableStateOf(c.instructor) }
        var schedule by remember(c) { mutableStateOf(c.schedule) }
        var room by remember(c) { mutableStateOf(c.room) }
        var askArchive by remember { mutableStateOf(false) }
        FormDialog(if (isNew) "New class" else "Edit class", { section = null }, enabled = name.isNotBlank() && subjectId.isNotBlank() && !vm.busy,
            onConfirm = {
                vm.save(if (isNew) "admin.section.create" else "admin.section.update", mapOf(
                    "id" to c.id.ifBlank { null }, "semesterId" to (if (isNew) h?.semesterId else null), "subjectId" to subjectId,
                    "name" to name, "instructor" to instructor, "schedule" to schedule, "room" to room
                )) { section = null }
            },
            extra = if (isNew) null else ({
                if (c.status == "Archived") TextButton(onClick = { vm.save("admin.section.update", mapOf("id" to c.id, "status" to "Active")) { section = null } }) { Text("Un-archive") }
                else TextButton(onClick = { askArchive = true }) { Text("Archive class") }
            })
        ) {
            Text("Subject", style = MaterialTheme.typography.labelLarge)
            Chips(h?.subjects.orEmpty().map { it.id to it.code }, subjectId) { subjectId = it }
            MField("Section name (e.g. BSEE 2-A)", name, { name = it })
            MField("Instructor", instructor, { instructor = it })
            MField("Schedule", schedule, { schedule = it })
            MField("Room", room, { room = it })
            if (isNew) Text("Its gradebook spreadsheet is made in the semester's folder.", style = MaterialTheme.typography.bodySmall)
        }
        if (askArchive) Confirm("Archive ${c.name}?", "It is hidden from students; the gradebook stays. You can un-archive it later.", "Archive", { askArchive = false }) {
            vm.save("admin.section.remove", mapOf("id" to c.id, "mode" to "archive")) { section = null }
        }
    }
    Notice(vm.notice) { vm.notice = null }
}

@Composable
private fun HeaderRow(title: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd) { Text("+ New") }
    }
}

/* -------------------------------- settings -------------------------------- */

private val SETTING_ROWS = listOf(
    "PORTAL_TITLE" to "Portal title", "PORTAL_SUBTITLE" to "Subtitle", "ANNOUNCEMENT" to "Announcement to every student (blank = none)",
    "CONTACT_EMAIL" to "Contact e-mail for students who can't sign in",
    "SHOW_RUNNING_GRADE" to "Students see their running grade", "SHOW_CLASS_AVERAGE" to "Students see the class average",
    "COUNT_BLANK_AS_ZERO" to "Blank scores count as zero", "ALLOW_PASSWORD_CHANGE" to "Students may change their password",
    "REGISTRATION_OPEN" to "New students can create an account", "REQUIRE_EMAIL_VERIFY" to "Students must confirm their e-mail",
    "SUMMARY_CHAIR_NAME" to "Chair on the Summary of Grades (blank = template's)", "SUMMARY_DEAN_NAME" to "Dean on the Summary of Grades (blank = template's)"
)
private val BOOL_SETTINGS = setOf("SHOW_RUNNING_GRADE", "SHOW_CLASS_AVERAGE", "COUNT_BLANK_AS_ZERO", "ALLOW_PASSWORD_CHANGE", "REGISTRATION_OPEN", "REQUIRE_EMAIL_VERIFY")

@Composable
fun AdminPortalSettingsScreen(onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminCatalogViewModel = viewModel()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    val edits = remember { mutableStateMapOf<String, String>() }
    val s = vm.home?.settings
    ManageScaffold("Portal settings", onBack, vm.busy, vm.error) {
        if (s == null) return@ManageScaffold
        items(SETTING_ROWS, key = { it.first }) { (key, label) ->
            val value = edits[key] ?: s[key].orEmpty()
            if (key in BOOL_SETTINGS) {
                // A blank cell means on for the two registration switches (portal default).
                val on = value.equals("TRUE", true) || (value.isBlank() && key in setOf("REGISTRATION_OPEN", "REQUIRE_EMAIL_VERIFY"))
                SwitchRow(label, on) { edits[key] = if (it) "TRUE" else "FALSE" }
            } else MField(label, value, { edits[key] = it }, lines = if (key == "ANNOUNCEMENT") 3 else 1)
        }
        item {
            Button(
                onClick = { vm.saveSettings(edits.toMap()) { edits.clear() } },
                enabled = edits.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth()
            ) { Text(if (edits.isEmpty()) "No changes" else "Save ${edits.size} change(s)") }
        }
    }
    Notice(vm.notice) { vm.notice = null }
}
