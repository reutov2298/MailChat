package ru.mailchat.app

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

object McSyncScheduler {
    private const val WORK_NAME = "mc_background_mail_sync"

    fun schedule(context: Context, closedIntervalSeconds: Int) {
        // Android WorkManager has a hard minimum periodic interval of 15 minutes.
        val intervalMinutes = ceil(closedIntervalSeconds / 60.0)
            .toLong()
            .coerceAtLeast(15L)
        val request = PeriodicWorkRequestBuilder<McSyncWorker>(
            intervalMinutes,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
