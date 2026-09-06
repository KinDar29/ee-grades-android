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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/* ------------------------------- formatting ------------------------------- */

/** Trims a trailing ".0" so "3 units" does not read as "3.0 units". */
fun Double.tidy(decimals: Int = 2): String {
    val text = String.format(Locale.US, "%.${decimals}f", this)
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

/** Grades always show two decimals: 1.75, not 1.8. */
fun Double.asGrade(): String = String.format(Locale.US, "%.2f", this)

fun Double?.orDash(decimals: Int = 2): String = this?.tidy(decimals) ?: "—"

/** "just now", "12 minutes ago", "3 days ago". */
fun relativeTime(epochMillis: Long): String {
    val delta = abs(System.currentTimeMillis() - epochMillis)
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes minute${plural(minutes)} ago"
        hours < 24L -> "$hours hour${plural(hours)} ago"
        days < 30L -> "$days day${plural(days)} ago"
        else -> "a while ago"
    }
}

private fun plural(n: Long) = if (n == 1L) "" else "s"

/* -------------------------------- building blocks ------------------------- */

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * The empty / error state. One shape for "nothing here yet" and "that did not
 * work", because to the person reading it the difference is only the wording.
 */
@Composable
fun MessageBlock(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** A red strip for something that failed but did not empty the screen. */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/** Quiet line telling the reader the screen is showing a stored copy. */
@Composable
fun StaleNotice(savedAt: Long, modifier: Modifier = Modifier) {
    Text(
        text = "Offline — showing what was saved ${relativeTime(savedAt)}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

/**
 * The numeric grade, shown the way the transmutation table does: 1.00 to 5.00,
 * with the remark beneath. Gold when passing, error colour at 5.00.
 */
@Composable
fun GradePill(
    grade: Double?,
    remark: String,
    modifier: Modifier = Modifier
) {
    val failing = grade != null && grade >= 5.0
    val container = when {
        grade == null -> MaterialTheme.colorScheme.surfaceVariant
        failing -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        grade == null -> MaterialTheme.colorScheme.onSurfaceVariant
        failing -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = grade?.asGrade() ?: "—",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = content
        )
        if (remark.isNotBlank()) {
            Text(
                text = remark,
                style = MaterialTheme.typography.labelSmall,
                color = content
            )
        }
    }
}

/** Label above, value below. Used for schedule, room, instructor and friends. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    if (value.isBlank()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.US),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp)
    )
}
