package com.blacksingularities.opensync.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.blacksingularities.opensync.data.Job
import com.blacksingularities.opensync.data.ScheduleMode
import com.blacksingularities.opensync.data.Store
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** Planowanie zadań w WorkManagerze: harmonogram, warunki (Wi-Fi, ładowanie…) i wyzwalacz nowych zdjęć. */
object Scheduler {
    private fun periodicName(id: String) = "job-$id"
    private fun mediaName(id: String) = "media-$id"
    fun manualName(id: String) = "run-$id"

    private fun constraints(job: Job, scheduled: Boolean): Constraints {
        val c = job.conditions
        return Constraints.Builder()
            .setRequiredNetworkType(if (c.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .apply {
                if (scheduled) {
                    setRequiresCharging(c.chargingOnly)
                    setRequiresBatteryNotLow(c.batteryNotLow)
                    setRequiresDeviceIdle(c.idleOnly)
                }
            }
            .build()
    }

    /** Uzgadnia zaplanowaną pracę ze stanem zadań. [reset] = nowy termin (po edycji harmonogramu). */
    fun reconcile(context: Context, reset: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val jobs = Store.get(context).jobs.value
        for (job in jobs) apply(context, job, reset)
        // Usunięte zadania.
        val ids = jobs.map { it.id }.toSet()
        runCatching {
            wm.getWorkInfosByTag(BackupWorker.TAG_ALL).get().forEach { info ->
                val jobTag = info.tags.firstOrNull { it.startsWith("job:") }?.removePrefix("job:")
                if (jobTag != null && jobTag !in ids && info.state != WorkInfo.State.RUNNING) wm.cancelWorkById(info.id)
            }
        }
    }

    fun apply(context: Context, job: Job, reset: Boolean) {
        val wm = WorkManager.getInstance(context)
        val s = job.schedule
        if (!job.enabled || s.mode == ScheduleMode.MANUAL) {
            wm.cancelUniqueWork(periodicName(job.id))
        } else {
            val (period, delay) = when (s.mode) {
                ScheduleMode.INTERVAL -> Duration.ofHours(s.intervalHours.toLong()) to Duration.ZERO
                ScheduleMode.DAILY -> {
                    val now = LocalDateTime.now()
                    var next = now.withHour(s.hour).withMinute(s.minute).withSecond(0).withNano(0)
                    if (!next.isAfter(now)) next = next.plusDays(1)
                    Duration.ofDays(1) to Duration.between(now, next)
                }
                else -> {
                    val now = LocalDateTime.now()
                    var next = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(s.weekday)))
                        .withHour(s.hour).withMinute(s.minute).withSecond(0).withNano(0)
                    if (!next.isAfter(now)) next = next.plusWeeks(1)
                    Duration.ofDays(7) to Duration.between(now, next)
                }
            }
            val flex = Duration.ofMinutes((period.toMinutes() / 8).coerceIn(15, 120))
            val req = PeriodicWorkRequestBuilder<BackupWorker>(period.toMinutes(), TimeUnit.MINUTES, flex.toMinutes(), TimeUnit.MINUTES)
                .setInputData(Data.Builder().putString(BackupWorker.KEY_JOB, job.id).putString(BackupWorker.KEY_OP, BackupWorker.OP_BACKUP).build())
                .setConstraints(constraints(job, true))
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(BackupWorker.TAG_ALL).addTag(BackupWorker.TAG_SCHEDULED).addTag("job:${job.id}")
                .build()
            wm.enqueueUniquePeriodicWork(
                periodicName(job.id),
                if (reset) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
        if (job.enabled && job.conditions.onNewMedia) scheduleMediaTrigger(context, job, afterRun = false)
        else wm.cancelUniqueWork(mediaName(job.id))
    }

    /** Kopia uruchamiana przez system po pojawieniu się nowych zdjęć lub filmów. */
    fun scheduleMediaTrigger(context: Context, job: Job, afterRun: Boolean) {
        if (!job.enabled || !job.conditions.onNewMedia) return
        val c = Constraints.Builder()
            .setRequiredNetworkType(if (job.conditions.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(job.conditions.batteryNotLow)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(2, TimeUnit.MINUTES)
            .setTriggerContentMaxDelay(15, TimeUnit.MINUTES)
            .build()
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(Data.Builder().putString(BackupWorker.KEY_JOB, job.id).putString(BackupWorker.KEY_OP, BackupWorker.OP_BACKUP).build())
            .setConstraints(c)
            .addTag(BackupWorker.TAG_ALL).addTag(BackupWorker.TAG_MEDIA).addTag("job:${job.id}")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            mediaName(job.id),
            if (afterRun) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            req,
        )
    }

    /** Uruchomienie teraz (kopia, weryfikacja, porządkowanie, przywracanie). */
    fun runNow(context: Context, jobId: String, op: String = BackupWorker.OP_BACKUP, extra: Map<String, String> = emptyMap()) {
        val job = Store.get(context).job(jobId) ?: return
        val data = Data.Builder().putString(BackupWorker.KEY_JOB, jobId).putString(BackupWorker.KEY_OP, op)
        extra.forEach { (k, v) -> data.putString(k, v) }
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(data.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (job.destinationId.isNotEmpty()) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(BackupWorker.TAG_ALL).addTag("job:$jobId").addTag("op:$op")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(manualName(jobId), ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun cancel(context: Context, jobId: String) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(manualName(jobId))
        runCatching {
            wm.getWorkInfosByTag("job:$jobId").get()
                .filter { it.state == WorkInfo.State.RUNNING }
                .forEach { wm.cancelWorkById(it.id) }
        }
    }

    fun remove(context: Context, jobId: String) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(periodicName(jobId))
        wm.cancelUniqueWork(mediaName(jobId))
        wm.cancelUniqueWork(manualName(jobId))
    }

    /** Najbliższy zaplanowany termin kopii albo null. */
    fun nextRun(context: Context, jobId: String): Long? = runCatching {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(periodicName(jobId)).get()
            .firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis?.takeIf { it in 1 until Long.MAX_VALUE }
    }.getOrNull()
}

/** Po restarcie telefonu: odtworzenie planu (WorkManager pamięta go sam, to tylko zabezpieczenie). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { Scheduler.reconcile(context) }
    }
}
