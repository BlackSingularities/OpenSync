package com.blacksingularities.opensync.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blacksingularities.opensync.data.RunKind
import com.blacksingularities.opensync.data.RunRecord
import com.blacksingularities.opensync.data.RunStatus
import com.blacksingularities.opensync.data.Store
import com.blacksingularities.opensync.engine.BackupEngine
import com.blacksingularities.opensync.engine.MaintenanceEngine
import com.blacksingularities.opensync.engine.Operations
import com.blacksingularities.opensync.engine.Progress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.io.File

/** Wykonuje kopię, weryfikację, porządkowanie albo przywracanie jako usługę pierwszoplanową. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val store = Store.get(context)
    private val jobId = params.inputData.getString(KEY_JOB) ?: ""
    private val op = params.inputData.getString(KEY_OP) ?: OP_BACKUP

    private fun notificationId() = 1000 + (jobId.hashCode() and 0xffff)

    private fun foreground(p: Progress): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return ForegroundInfo(notificationId(), Notifier.progressNotification(applicationContext, p, cancel), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(Progress(jobId, store.job(jobId)?.name ?: "OpenSync", "Przygotowanie"))

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override suspend fun doWork(): Result = coroutineScope {
        val job = store.job(jobId) ?: return@coroutineScope Result.failure()
        if (op == OP_BACKUP && !job.enabled && tags.contains(TAG_SCHEDULED)) return@coroutineScope Result.success()
        runCatching { setForeground(getForegroundInfo()) }

        val updater = launch {
            Operations.running.mapNotNull { it[jobId] }.sample(1000).collectLatest { p ->
                runCatching { setForeground(foreground(p)) }
            }
        }

        val record: RunRecord = when (op) {
            OP_CHECK -> MaintenanceEngine(applicationContext, job).check(job.verifySamplePct)
            OP_CHECK_FULL -> MaintenanceEngine(applicationContext, job).check(100)
            OP_PRUNE -> MaintenanceEngine(applicationContext, job).prune()
            OP_RESTORE -> {
                val snapshot = inputData.getString(KEY_SNAPSHOT) ?: ""
                val target = Uri.parse(inputData.getString(KEY_TARGET) ?: "")
                val listFile = inputData.getString(KEY_PATHS)?.let { File(it) }
                val paths = listFile?.takeIf { it.exists() }?.readLines()?.filter { it.isNotEmpty() }?.toSet()
                MaintenanceEngine(applicationContext, job).restore(snapshot, paths, target).also { listFile?.delete() }
            }
            else -> BackupEngine(applicationContext, job).run()
        }
        updater.cancel()
        store.addRun(record)

        // Okresowa weryfikacja po co N-tej kopii.
        if (op == OP_BACKUP && record.status in setOf(RunStatus.OK, RunStatus.WARN) && job.verifyEvery > 0 &&
            store.backupsSinceCheck(jobId) >= job.verifyEvery
        ) {
            val check = MaintenanceEngine(applicationContext, job).check(job.verifySamplePct)
            store.addRun(check)
            if (check.status == RunStatus.FAILED && store.notifyFailures) Notifier.result(applicationContext, check)
        }

        val notify = when (record.status) {
            RunStatus.FAILED -> store.notifyFailures
            RunStatus.OK, RunStatus.WARN -> job.notifySuccess || record.kind != RunKind.BACKUP || record.status == RunStatus.WARN && store.notifyFailures
            RunStatus.SKIPPED -> !tags.contains(TAG_SCHEDULED) && !tags.contains(TAG_MEDIA)
            RunStatus.CANCELLED -> false
        }
        if (notify) Notifier.result(applicationContext, record)

        if (tags.contains(TAG_MEDIA)) Scheduler.scheduleMediaTrigger(applicationContext, job, afterRun = true)

        when {
            record.status == RunStatus.SKIPPED && job.conditions.retryWhenUnreachable && tags.contains(TAG_SCHEDULED) && runAttemptCount < 6 -> Result.retry()
            record.status == RunStatus.FAILED && tags.contains(TAG_SCHEDULED) && runAttemptCount < 3 -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_JOB = "job"
        const val KEY_OP = "op"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_TARGET = "target"
        const val KEY_PATHS = "paths"
        const val OP_BACKUP = "backup"
        const val OP_CHECK = "check"
        const val OP_CHECK_FULL = "checkFull"
        const val OP_PRUNE = "prune"
        const val OP_RESTORE = "restore"
        const val TAG_ALL = "opensync"
        const val TAG_SCHEDULED = "scheduled"
        const val TAG_MEDIA = "media"
    }
}
