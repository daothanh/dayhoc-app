package vn.dayhoc.app.teacher.attendance

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import vn.dayhoc.app.DayHocApp

/** Chạy nền khi có mạng: gửi outbox; còn lô chưa gửi được thì để WorkManager thử lại theo backoff. */
class AttendanceSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as DayHocApp).container
        return when (container.syncNow()) {
            FlushResult.Retry -> Result.retry()
            FlushResult.Done -> Result.success()
        }
    }

    companion object {
        private const val NAME = "attendance-sync"

        /** Hẹn một lần chạy có ràng buộc mạng; lần hẹn mới thay lần cũ (lần cũ có thể đang chờ backoff). */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<AttendanceSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
