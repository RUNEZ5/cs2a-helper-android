package ru.cs2a.helper.alarms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.cs2a.helper.net.LOTTERIES
import ru.cs2a.helper.util.Prefs

/** После перезагрузки (или обновления приложения) будильники AlarmManager пропадают — восстанавливаем. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs.getBool(context, Prefs.K_FARM_AUTO)) AlarmScheduler.scheduleFarmEgg(context)
        if (Prefs.getBool(context, Prefs.K_CASHBACK_AUTO)) AlarmScheduler.scheduleCashback(context)
        if (Prefs.getBool(context, Prefs.K_RAFFLES_AUTO)) {
            for (item in LOTTERIES) AlarmScheduler.scheduleLotteryIn(context, item.id, item.cycleMinutes)
        }
    }
}
