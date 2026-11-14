package ph.edu.mmsu.ee.grades.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ph.edu.mmsu.ee.grades.data.ExamInfo
import ph.edu.mmsu.ee.grades.data.ProblemSet
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/* ------------------------------- shared bits ------------------------------ */

private val manila: TimeZone = TimeZone.getTimeZone("Asia/Manila")

/** 'yyyy-MM-dd HH:mm' (Manila) → "Mon, Nov 16 · 1:00 AM". Falls back to the text as given. */
fun whenLabel(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = manila }
        val outFmt = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US).apply { timeZone = manila }
        outFmt.format(inFmt.parse(raw.trim().take(16))!!)
    } catch (e: Exception) {
        raw
    }
}

@Composable
private fun Chip(text: String, tone: Color) {
    Surface(color = tone.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text(
            text,
            color = tone,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private val Green = Color(0xFF1E7A4C)
private val Gold = Color(0xFF9A6B00)
private val Red = Color(0xFFB3261E)
private val Grey = Color(0xFF5F6B66)

private fun fmtNum(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.US, "%.2f", d)

/* ---------------------------------- exams --------------------------------- */

@Composable
fun ExamsCard(exams: List<ExamInfo>, onStart: (ExamInfo) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Exams",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            exams.forEachIndexed { i, e ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        when (e.state) {
                            "open" -> Chip("Open", Green)
                            "in_progress" -> Chip("In progress", Gold)
                            "upcoming" -> Chip("Not open yet", Grey)
                            "missed" -> Chip("Missed", Red)
                            else -> if (e.locked) Chip("🔒 Locked", Red) else Chip("✓ Submitted", Green)
                        }
                    }
                    Text(
                        "${e.items} questions · ${e.duration} min · ${whenLabel(e.opensAt)} → ${whenLabel(e.closesAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (e.state == "submitted") {
                        val score = e.score
                        Text(
                            if (score != null) "Score: ${fmtNum(score)} / ${e.scoreItems ?: e.items}"
                            else "Your score will appear here once your teacher releases it.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (e.state == "open" || e.state == "in_progress") {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onStart(e) }) {
                            Text(if (e.state == "open") "Start exam" else "Continue")
                        }
                    }
                }
            }
        }
    }
}

/** The rules, shown once before the exam begins (the timer starts on "Start"). */
@Composable
fun ExamRulesDialog(exam: ExamInfo, onStart: () -> Unit, onCancel: () -> Unit) {
    val continuing = exam.state == "in_progress"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (continuing) "Continue the exam?" else "Start the exam?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!continuing) {
                    Text("${exam.items} questions, ${exam.duration} minutes. The timer starts now and keeps running even if you close the app.")
                }
                Text("The app pins itself to the screen. Leaving the exam (going home, switching apps, split screen, a pop-up window, or unpinning) is recorded and your teacher sees it.")
                if (exam.maxExits > 0) {
                    Text(
                        "After ${exam.maxExits} time${if (exam.maxExits == 1) "" else "s"} the exam locks and your answers so far are submitted.",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text("Screenshots and screen recording are blocked.")
            }
        },
        confirmButton = { Button(onClick = onStart) { Text(if (continuing) "Continue" else "Start") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/* ------------------------------- problem sets ----------------------------- */

@Composable
fun ProblemSetsCard(
    sets: List<ProblemSet>,
    busyId: String?,
    opening: String?,
    onViewSet: (ProblemSet) -> Unit,
    onUpload: (ProblemSet) -> Unit,
    onViewMine: (ProblemSet) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "Problem sets",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            sets.forEachIndexed { i, ps ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                val sub = ps.submission
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ps.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        val score = ps.score
                        when {
                            score != null -> Chip("${fmtNum(score)}${ps.max?.let { " / " + fmtNum(it) } ?: ""}", Green)
                            sub != null -> Chip(if (sub.late) "Submitted · late" else "✓ Submitted", if (sub.late) Gold else Green)
                            ps.pastDue && !ps.allowLate -> Chip("Missed", Red)
                            ps.pastDue -> Chip("Late", Gold)
                            else -> Chip("To do", Grey)
                        }
                    }
                    if (ps.dueAt.isNotBlank()) {
                        Text(
                            "Due ${whenLabel(ps.dueAt)}" + if (ps.allowLate) " · late work accepted" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ps.pastDue) Red else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (ps.instructions.isNotBlank()) {
                        Text(ps.instructions, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (sub != null) {
                        Text(
                            if (sub.fileDeleted) "Your file was removed after grading to save space."
                            else "Your file: ${sub.fileName} · ${sub.submittedAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ps.materialId.isNotBlank()) {
                            OutlinedButton(onClick = { onViewSet(ps) }, enabled = opening == null) {
                                if (opening == ps.materialId) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Problem set")
                            }
                        }
                        if (sub != null && !sub.fileDeleted) {
                            TextButton(onClick = { onViewMine(ps) }, enabled = opening == null) {
                                if (opening == "sub:${ps.id}") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("My file")
                            }
                        }
                        if (ps.canSubmit) {
                            Button(onClick = { onUpload(ps) }, enabled = busyId == null) {
                                if (busyId == ps.id) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.padding(start = 6.dp))
                                    Text("Uploading…")
                                } else {
                                    Text(if (sub == null) "Upload PDF" else "Replace")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
