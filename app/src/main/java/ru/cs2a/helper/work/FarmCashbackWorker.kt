package ru.cs2a.helper.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.cs2a.helper.alarms.AlarmScheduler
import ru.cs2a.helper.net.Repo
import ru.cs2a.helper.util.Prefs

class FarmCashbackWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val KEY_KIND = "kind"
        const val KIND_FARM = "farm"
        const val KIND_CASHBACK = "cashback"
    }

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        when (kind) {
            KIND_FARM -> {
                Repo.farmEggAttempt(applicationContext)
                // переставляем будильник на следующие сутки — только если автозабор всё ещё включён
                if (Prefs.getBool(applicationContext, Prefs.K_FARM_AUTO)) AlarmScheduler.scheduleFarmEgg(applicationContext)
            }
            KIND_CASHBACK -> {
                Repo.cashbackAttempt(applicationContext)
                if (Prefs.getBool(applicationContext, Prefs.K_CASHBACK_AUTO)) AlarmScheduler.scheduleCashback(applicationContext)
            }
        }
        return Result.success()
    }
}
