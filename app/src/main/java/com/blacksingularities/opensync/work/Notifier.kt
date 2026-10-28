package com.blacksingularities.opensync.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blacksingularities.opensync.MainActivity
import com.blacksingularities.opensync.R
import com.blacksingularities.opensync.data.RunRecord
import com.blacksingularities.opensync.data.RunStatus
import com.blacksingularities.opensync.engine.Progress
import com.blacksingularities.opensync.ui.formatBytes

object Notifier {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_RESULTS = "results"
    const val CHANNEL_UPDATES = "updates"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_PROGRESS, "Kopia w toku", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
                NotificationChannel(CHANNEL_RESULTS, "Wyniki kopii", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_UPDATES, "Aktualizacje aplikacji", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progressNotification(context: Context, p: Progress, cancel: PendingIntent?): android.app.Notification {
        val text = buildString {
            append(p.phase)
            if (p.filesTotal > 0) append(" · ${p.filesDone}/${p.filesTotal}")
            if (p.bytesPerSec > 0) append(" · ${formatBytes(p.bytesPerSec)}/s")
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(p.title)
            .setContentText(text)
            .setSubText(if (p.uploaded > 0) "wysłano ${formatBytes(p.uploaded)}" else null)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (p.detail.isNotBlank()) "$text\n${p.detail}" else text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setProgress(1000, (p.fraction.coerceIn(0f, 1f) * 1000).toInt(), p.fraction < 0)
            .apply { if (cancel != null) addAction(0, "Przerwij", cancel) }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun result(context: Context, r: RunRecord) {
        val title = when (r.status) {
            RunStatus.OK -> "${r.kind.label} gotowa: ${r.jobName}"
            RunStatus.WARN -> "${r.kind.label} z ostrzeżeniami: ${r.jobName}"
            RunStatus.FAILED -> "${r.kind.label} nieudana: ${r.jobName}"
            RunStatus.SKIPPED -> "Pominięto: ${r.jobName}"
            RunStatus.CANCELLED -> "Przerwano: ${r.jobName}"
        }
        val text = r.message.ifBlank {
            "${r.files} plików · ${formatBytes(r.bytesTotal)} · nowe ${formatBytes(r.bytesNew)}" +
                if (r.warnings.isNotEmpty()) " · ostrzeżenia: ${r.warnings.size}" else ""
        }
        val n = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(r.jobId.hashCode() + r.kind.ordinal, n) }
    }
}
