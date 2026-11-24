package ph.edu.mmsu.ee.grades.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ph.edu.mmsu.ee.grades.MainActivity
import ph.edu.mmsu.ee.grades.R
import ph.edu.mmsu.ee.grades.data.NewScore
import ph.edu.mmsu.ee.grades.data.UpdateMessage
import java.util.Locale

/**
 * Posts the "new score" notification. One notification at a time: a later
 * check replaces it rather than stacking up.
 *
 * Scores are personal, so on a locked screen only "New score posted" shows;
 * the details appear once the phone is unlocked.
 */
object ScoreNotifier {

    private const val CHANNEL_ID = "new_scores"
    private const val NOTIFICATION_ID = 2001
    private const val MAX_LINES = 6

    private const val MESSAGE_CHANNEL_ID = "instructor_messages"
    private const val MESSAGE_NOTIFICATION_ID = 2002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New scores", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When your instructor records a new or changed score"
                }
            )
        }
        if (manager.getNotificationChannel(MESSAGE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(MESSAGE_CHANNEL_ID, "Messages from your instructor", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Results, schedules and other notices your instructor sends from the portal"
                }
            )
        }
    }

    /**
     * Instructor messages ("✉ Message students" in the portal). The text is
     * personal (scores, results), so the lock screen only says a message came.
     */
    @SuppressLint("MissingPermission") // checked in allowed()
    fun showMessages(context: Context, messages: List<UpdateMessage>) {
        if (messages.isEmpty() || !allowed(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val first = messages.first()
        val title = if (messages.size == 1) first.subject.ifBlank { "Message from your instructor" }
            else "${messages.size} messages from your instructor"
        val lockScreen = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(if (messages.size == 1) "New message from your instructor" else title)
            .setContentText("Open EE Grades or your e-mail to read it")
            .build()
        val builder = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen)
        if (messages.size == 1) {
            builder.setContentText(first.body.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty())
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(first.body))
        } else {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            messages.take(MAX_LINES).forEach { style.addLine(it.subject) }
            if (messages.size > MAX_LINES) style.setSummaryText("+${messages.size - MAX_LINES} more")
            builder.setContentText(messages.joinToString(" · ") { it.subject }).setStyle(style)
        }
        NotificationManagerCompat.from(context).notify(MESSAGE_NOTIFICATION_ID, builder.build())
    }

    private fun allowed(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // checked in allowed()
    fun show(context: Context, scores: List<NewScore>) {
        if (scores.isEmpty() || !allowed(context)) return
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val first = scores.first()
        val title: String
        val text: String
        if (scores.size == 1) {
            title = "New score in ${first.classLabel.ifBlank { "your class" }}"
            text = "${first.name}: ${num(first.score)} / ${num(first.max)}"
        } else {
            title = "${scores.size} new scores"
            text = scores.map { it.classLabel }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        }

        val lockScreen = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(if (scores.size == 1) "New score posted" else "${scores.size} new scores posted")
            .setContentText("Open EE Grades to see it")
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen)

        if (scores.size > 1) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            scores.take(MAX_LINES).forEach {
                style.addLine("${it.classLabel} · ${it.name}: ${num(it.score)} / ${num(it.max)}")
            }
            if (scores.size > MAX_LINES) style.setSummaryText("+${scores.size - MAX_LINES} more")
            builder.setStyle(style)
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    /** 18.0 -> "18", 17.5 -> "17.5", 17.25 -> "17.25". */
    private fun num(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}
