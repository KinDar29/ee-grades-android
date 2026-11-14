package ph.edu.mmsu.ee.grades.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale

/*
 * Admin reports in the app (phase 4): class record PDF + Summary of Grades
 * (.docx) for a class, and the Direct / Indirect assessment dashboards for a
 * semester. Same admin.* routes as the portal; charts are plain Compose bars.
 */

/* --------------------------------- models -------------------------------- */

@Serializable data class AdmSummaryFile(val name: String = "", val base64: String = "", val driveUrl: String = "", val error: String = "")
@Serializable data class AdmExport(
    val name: String = "", val students: Int = 0, val base64: String = "", val driveUrl: String = "",
    val sheetUrl: String = "", val summary: AdmSummaryFile? = null
)

@Serializable data class DirReq(val id: String = "", val name: String = "", val type: String = "", val max: Double = 0.0, val weight: Double = 0.0)
@Serializable data class DirStudent(
    val id: String = "", val name: String = "", val percent: Double? = null, val grade: Double? = null,
    val standing: String = "", val scores: List<Double?> = emptyList()
)
@Serializable data class DirSection(
    val id: String = "", val name: String = "", val subjectCode: String = "", val error: String = "",
    val assessments: List<DirReq> = emptyList(), val students: List<DirStudent> = emptyList()
)
@Serializable data class DirDash(val passingPercent: Double = 75.0, val sections: List<DirSection> = emptyList(), val checkedAt: String = "")

@Serializable data class IndSection(val id: String = "", val name: String = "", val enrolled: Int = 0, val answered: Int = 0)
@Serializable data class IndCo(
    val co: String = "", val description: String = "", val averageRating: Double? = null,
    val indirect: Double? = null, val direct: Double? = null, val gap: Double? = null, val answered: Int = 0
)
@Serializable data class IndStatement(val co: String = "", val text: String = "", val average: Double? = null, val answered: Int = 0)
@Serializable data class IndOverall(val indirect: Double? = null, val direct: Double? = null, val answered: Int = 0)
@Serializable data class IndData(
    val responses: Int = 0, val cos: List<IndCo> = emptyList(), val statements: List<IndStatement> = emptyList(),
    val overall: IndOverall? = null
)
@Serializable data class IndBlock(
    val subject: String = "", val surveyUrl: String = "", val workbookUrl: String = "", val error: String = "",
    val data: IndData? = null, val sections: List<IndSection> = emptyList()
)
@Serializable data class IndDash(val blocks: List<IndBlock> = emptyList(), val checkedAt: String = "")

private fun f1(d: Double?): String = d?.let { String.format(Locale.US, if (it % 1.0 == 0.0) "%.0f" else "%.1f", it) } ?: "—"

/* ------------------------------ class record ------------------------------ */

class AdminExportViewModel : AdminToolViewModel() {
    var result by mutableStateOf<AdmExport?>(null)
        private set
    var pdf by mutableStateOf<File?>(null)
        private set
    var docx by mutableStateOf<File?>(null)
        private set

    fun export(sectionId: String, cacheDir: File) = act {
        val out = repo.json.decodeFromJsonElement(AdmExport.serializer(), call("admin.export.pdf", buildJsonObject { put("sectionId", sectionId) }))
        result = out
        val dir = File(cacheDir, "course-files").apply { mkdirs() }
        withContext(Dispatchers.IO) {
            pdf = File(dir, safeName(out.name.ifBlank { "Class record.pdf" })).apply { writeBytes(java.util.Base64.getDecoder().decode(out.base64)) }
            docx = out.summary?.takeIf { it.base64.isNotBlank() }?.let { s ->
                File(dir, safeName(s.name.ifBlank { "Summary of Grades.docx" })).apply { writeBytes(java.util.Base64.getDecoder().decode(s.base64)) }
            }
        }
    }

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9 ._()-]"), "_")
}

private fun openFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open ${file.name}.", Toast.LENGTH_LONG).show()
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) { }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminClassRecordScreen(sectionId: String, onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminExportViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Class record") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Makes the printable class record (PDF) and rebuilds the semester's Summary of Grades (.docx). Both are also saved in Drive.")
            Button(onClick = { vm.export(sectionId, context.cacheDir) }, enabled = !vm.busy) { Text(if (vm.busy) "Making… (up to a minute)" else "Make class record") }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { ErrorBanner(message = it) }
            val r = vm.result
            if (r != null) {
                HorizontalDivider()
                Text("${r.name} · ${r.students} students", fontWeight = FontWeight.SemiBold)
                vm.pdf?.let { f -> Button(onClick = { openFile(context, f, "application/pdf") }) { Text("Open class record PDF") } }
                if (r.driveUrl.isNotBlank()) OutlinedButton(onClick = { openUrl(context, r.driveUrl) }) { Text("Open in Drive") }
                val s = r.summary
                when {
                    s == null -> {}
                    s.error.isNotBlank() -> Text("Summary of Grades could not be made: ${s.error}", color = MaterialTheme.colorScheme.error)
                    else -> {
                        vm.docx?.let { f -> Button(onClick = { openFile(context, f, "application/vnd.openxmlformats-officedocument.wordprocessingml.document") }) { Text("Open Summary of Grades (.docx)") } }
                        if (s.driveUrl.isNotBlank()) OutlinedButton(onClick = { openUrl(context, s.driveUrl) }) { Text("Summary of Grades in Drive") }
                    }
                }
            }
        }
    }
}

/* -------------------------------- dashboards ------------------------------- */

class AdminDashViewModel : AdminToolViewModel() {
    var direct by mutableStateOf<DirDash?>(null)
        private set
    var indirect by mutableStateOf<IndDash?>(null)
        private set
    var tab by mutableStateOf(0)
    var section by mutableStateOf("")   // "" = all sections

    fun load(refresh: Boolean = false) = act {
        if (tab == 0) direct = repo.json.decodeFromJsonElement(DirDash.serializer(), call(if (refresh) "admin.direct.refresh" else "admin.direct", buildJsonObject { }))
        else indirect = repo.json.decodeFromJsonElement(IndDash.serializer(), call(if (refresh) "admin.indirect.refresh" else "admin.indirect", buildJsonObject { }))
    }
}

private val STANDINGS = listOf(
    "PASSED" to Color(0xFF2E7D32), "CONDITIONAL" to Color(0xFFF9A825), "FAILED" to Color(0xFFC62828),
    "INC" to Color(0xFF6A1B9A), "DROPPED" to Color(0xFF546E7A), "NONE" to Color(0xFFB0BEC5)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardsScreen(onBack: () -> Unit, onAuthLost: (String?) -> Unit) {
    val vm: AdminDashViewModel = viewModel()
    val context = LocalContext.current
    LaunchedEffect(vm.tab) { vm.load() }
    LaunchedEffect(vm.authLost) { vm.authLost?.let(onAuthLost) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Dashboards") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { vm.load(refresh = true) }) { Icon(Icons.Filled.Refresh, "Refresh") } }
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = vm.tab) {
                Tab(selected = vm.tab == 0, onClick = { vm.tab = 0 }, text = { Text("Direct") })
                Tab(selected = vm.tab == 1, onClick = { vm.tab = 1 }, text = { Text("Indirect") })
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let { ErrorBanner(message = it, modifier = Modifier.padding(12.dp)) }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (vm.tab == 0) vm.direct?.let { d -> directItems(d, vm) }
                else vm.indirect?.let { d -> indirectItems(d, vm) { openUrl(context, it) } }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sectionPicker(names: List<Pair<String, String>>, vm: AdminDashViewModel) {
    if (names.size < 2) return
    item {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(selected = vm.section == "", onClick = { vm.section = "" }, label = { Text("All sections") }) }
            items(names) { (id, label) -> FilterChip(selected = vm.section == id, onClick = { vm.section = id }, label = { Text(label) }) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.directItems(d: DirDash, vm: AdminDashViewModel) {
    sectionPicker(d.sections.map { it.id to "${it.subjectCode} ${it.name}" }, vm)
    val secs = d.sections.filter { vm.section == "" || it.id == vm.section }
    val students = secs.flatMap { it.students }
    item {
        DashCard("Standing · ${students.size} students") {
            StackBar(STANDINGS.map { (k, c) -> Triple(k.lowercase().replaceFirstChar(Char::uppercase), students.count { it.standing.uppercase() == k }, c) })
            val pcts = students.mapNotNull { it.percent }
            if (pcts.isNotEmpty()) Text("Average ${f1(pcts.average())}% · passing at ${f1(d.passingPercent)}%", style = MaterialTheme.typography.bodySmall)
        }
    }
    item {
        DashCard("Grade distribution") {
            val grades = students.mapNotNull { it.grade }.groupingBy { it }.eachCount().toSortedMap()
            if (grades.isEmpty()) Text("No grades yet.") else Bars(grades.map { (g, n) -> String.format(Locale.US, "%.2f", g) to n.toDouble() }, suffix = "")
        }
    }
    if (secs.size > 1) item {
        DashCard("Classes compared (average %)") {
            Bars(secs.map { s -> "${s.subjectCode} ${s.name}" to (s.students.mapNotNull { it.percent }.takeIf { it.isNotEmpty() }?.average() ?: 0.0) }, suffix = "%", max = 100.0)
        }
    }
    secs.forEach { s ->
        if (s.error.isNotBlank()) item { DashCard("${s.subjectCode} ${s.name}") { Text(s.error, color = MaterialTheme.colorScheme.error) } }
        else if (s.assessments.isNotEmpty()) item {
            DashCard("${s.subjectCode} ${s.name} · requirements (average % of max)") {
                Bars(s.assessments.mapIndexed { i, a ->
                    val vals = s.students.mapNotNull { st -> st.scores.getOrNull(i) }
                    a.name to (if (vals.isEmpty() || a.max <= 0) 0.0 else vals.average() / a.max * 100)
                }, suffix = "%", max = 100.0)
            }
        }
    }
    val watch = students.filter { it.standing.uppercase() in setOf("FAILED", "CONDITIONAL", "INC") || (it.percent != null && it.percent < d.passingPercent) }
        .sortedBy { it.percent ?: 0.0 }.take(15)
    if (watch.isNotEmpty()) item {
        DashCard("Students to watch") {
            watch.forEach { st -> Text("${st.name} · ${f1(st.percent)}% · ${st.standing.lowercase()}", style = MaterialTheme.typography.bodySmall) }
        }
    }
    item { Text("Checked ${d.checkedAt} · data is cached up to 5 minutes; ↻ refreshes.", style = MaterialTheme.typography.bodySmall) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.indirectItems(d: IndDash, vm: AdminDashViewModel, open: (String) -> Unit) {
    if (d.blocks.isEmpty()) item { Text("No class has a survey or assessment workbook linked this semester.") }
    sectionPicker(d.blocks.flatMap { b -> b.sections.map { it.id to it.name } }, vm)
    d.blocks.forEach { b ->
        val secs = b.sections.filter { vm.section == "" || it.id == vm.section }
        if (secs.isEmpty()) return@forEach
        item {
            DashCard(b.subject) {
                Bars(secs.map { s -> "${s.name} (${s.answered}/${s.enrolled})" to (if (s.enrolled == 0) 0.0 else s.answered * 100.0 / s.enrolled) }, suffix = "%", max = 100.0)
                if (b.error.isNotBlank()) Text(b.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                b.data?.overall?.let { o -> Text("Overall: indirect ${f1(o.indirect)}% · direct ${f1(o.direct)}% (students at ≥ 70%)", fontWeight = FontWeight.Medium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (b.surveyUrl.isNotBlank()) OutlinedButton(onClick = { open(b.surveyUrl) }) { Text("Survey") }
                    if (b.workbookUrl.isNotBlank()) OutlinedButton(onClick = { open(b.workbookUrl) }) { Text("Workbook") }
                }
            }
        }
        val data = b.data ?: return@forEach
        if (data.cos.isNotEmpty()) item {
            DashCard("Course outcomes (class-wide)") {
                data.cos.forEach { c ->
                    Text("${c.co}: rating ${f1(c.averageRating)} · indirect ${f1(c.indirect)}% · direct ${f1(c.direct)}%" + (c.gap?.let { " · gap ${f1(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                Bars(data.cos.map { it.co to (it.averageRating ?: 0.0) }, suffix = "", max = 5.0)
            }
        }
        val rated = data.statements.filter { it.average != null }.sortedBy { it.average }
        if (rated.size >= 2) item {
            DashCard("Lowest and highest statements") {
                (rated.take(3) + rated.takeLast(3).reversed()).distinct().forEach { s ->
                    Text("${f1(s.average)} · ${s.co} · ${s.text}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun DashCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** One horizontal bar split by count, with a legend under it. */
@Composable
private fun StackBar(parts: List<Triple<String, Int, Color>>) {
    val total = parts.sumOf { it.second }
    if (total == 0) { Text("No students."); return }
    Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp))) {
        parts.filter { it.second > 0 }.forEach { (_, n, c) -> Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c)) }
    }
    parts.filter { it.second > 0 }.forEach { (label, n, c) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(10.dp).height(10.dp).background(c))
            Text("  $label $n (${f1(n * 100.0 / total)}%)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Labelled horizontal bars. */
@Composable
private fun Bars(rows: List<Pair<String, Double>>, suffix: String, max: Double? = null) {
    val top = max ?: (rows.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0)
    rows.forEach { (label, v) ->
        Column {
            Text("$label — ${f1(v)}$suffix", style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth((v / top).coerceIn(0.0, 1.0).toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
        }
    }
}
