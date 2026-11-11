package ru.cs2a.helper.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.cs2a.helper.LOTTERIES
import ru.cs2a.helper.TokenStore

// После перезагрузки телефона все будильники AlarmManager слетают — переставляем те,
// что были включены (аналог farmRestoreAlarm() в background.js).
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        TokenStore.init(context)
        if (TokenStore.getBool("farmAuto")) AlarmScheduler.scheduleFarm(context)
        if (TokenStore.getBool("cashbackAuto")) AlarmScheduler.scheduleCashback(context)
        if (TokenStore.getBool("rafflesAuto")) {
            for (item in LOTTERIES) AlarmScheduler.scheduleLottery(context, item.id, item.cycleMin)
        }
    }
}
