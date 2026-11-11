package ru.cs2a.helper.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.cs2a.helper.Api
import ru.cs2a.helper.LOTTERIES
import ru.cs2a.helper.TokenStore

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TokenStore.init(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    AlarmScheduler.ACTION_FARM -> {
                        Api.farmEggAttempt(context)
                        if (TokenStore.getBool("farmAuto")) AlarmScheduler.scheduleFarm(context)
                    }
                    AlarmScheduler.ACTION_CASHBACK -> {
                        Api.cashbackAttempt(context)
                        if (TokenStore.getBool("cashbackAuto")) AlarmScheduler.scheduleCashback(context)
                    }
                    AlarmScheduler.ACTION_LOTTERY -> {
                        val id = intent.getIntExtra(AlarmScheduler.EXTRA_LOTTERY_ID, -1)
                        val item = LOTTERIES.find { it.id == id }
                        if (item != null) {
                            Api.joinLottery(context, item)
                            if (TokenStore.getBool("rafflesAuto")) AlarmScheduler.scheduleLottery(context, item.id, item.cycleMin)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
