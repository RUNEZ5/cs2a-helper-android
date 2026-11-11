package ru.cs2a.helper.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.cs2a.helper.alarms.AlarmScheduler
import ru.cs2a.helper.net.LOTTERIES
import ru.cs2a.helper.net.Repo
import ru.cs2a.helper.util.Prefs

class RaffleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object { const val KEY_ID = "id" }

    override suspend fun doWork(): Result {
        val id = inputData.getInt(KEY_ID, -1)
        if (id <= 0) return Result.failure()
        Repo.lotteryAttempt(applicationContext, id)
        if (Prefs.getBool(applicationContext, Prefs.K_RAFFLES_AUTO)) {
            val item = LOTTERIES.find { it.id == id }
            AlarmScheduler.scheduleLotteryIn(applicationContext, id, item?.cycleMinutes ?: 1440)
        }
        return Result.success()
    }
}
