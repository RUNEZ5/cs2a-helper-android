package ru.cs2a.helper.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import ru.cs2a.helper.work.FarmCashbackWorker
import ru.cs2a.helper.work.RaffleWorker

/** Будильник сработал — быстро передаём задачу в WorkManager (сам BroadcastReceiver долго жить не может). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wm = WorkManager.getInstance(context)
        when (intent.action) {
            AlarmScheduler.ACTION_FARM_EGG -> {
                val req = OneTimeWorkRequestBuilder<FarmCashbackWorker>()
                    .setInputData(Data.Builder().putString(FarmCashbackWorker.KEY_KIND, FarmCashbackWorker.KIND_FARM).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                wm.enqueueUniqueWork("farm_egg", ExistingWorkPolicy.REPLACE, req)
            }
            AlarmScheduler.ACTION_CASHBACK -> {
                val req = OneTimeWorkRequestBuilder<FarmCashbackWorker>()
                    .setInputData(Data.Builder().putString(FarmCashbackWorker.KEY_KIND, FarmCashbackWorker.KIND_CASHBACK).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                wm.enqueueUniqueWork("cashback", ExistingWorkPolicy.REPLACE, req)
            }
            AlarmScheduler.ACTION_LOTTERY -> {
                val id = intent.getIntExtra(AlarmScheduler.EXTRA_LOTTERY_ID, -1)
                if (id <= 0) return
                val req = OneTimeWorkRequestBuilder<RaffleWorker>()
                    .setInputData(Data.Builder().putInt(RaffleWorker.KEY_ID, id).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                wm.enqueueUniqueWork("lottery_$id", ExistingWorkPolicy.REPLACE, req)
            }
        }
    }
}
