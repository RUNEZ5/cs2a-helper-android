package ru.cs2a.helper.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

// Аналог chrome.alarms из background.js: будильники на фиксированное время по МСК (ферма,
// кэшбек) и будильники с относительной задержкой для розыгрышей (свой цикл у каждого).
object AlarmScheduler {
    const val ACTION_FARM = "ru.cs2a.helper.action.FARM"
    const val ACTION_CASHBACK = "ru.cs2a.helper.action.CASHBACK"
    const val ACTION_LOTTERY = "ru.cs2a.helper.action.LOTTERY"
    const val EXTRA_LOTTERY_ID = "lotteryId"

    private fun mgr(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    // 03:00 МСК (UTC+3, без перехода на летнее время) + 2 секунды запаса — как nextMskTime() в расширении.
    fun nextMskMillis(hourMsk: Int = 3, minuteMsk: Int = 0, secondMsk: Int = 2): Long {
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val utcHour = ((hourMsk - 3) + 24) % 24
        cal.set(Calendar.HOUR_OF_DAY, utcHour)
        cal.set(Calendar.MINUTE, minuteMsk)
        cal.set(Calendar.SECOND, secondMsk)
        cal.set(Calendar.MILLISECOND, 0)
        var t = cal.timeInMillis
        if (t <= System.currentTimeMillis()) t += 24L * 3600 * 1000
        return t
    }

    private fun exact(ctx: Context, triggerAt: Long, pi: PendingIntent) {
        val am = mgr(ctx)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun scheduleFarm(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, 1001, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        exact(ctx, nextMskMillis(), pi)
    }

    fun cancelFarm(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, 1001, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mgr(ctx).cancel(pi)
    }

    fun scheduleCashback(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, 1002, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CASHBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        exact(ctx, nextMskMillis(), pi)
    }

    fun cancelCashback(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, 1002, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CASHBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mgr(ctx).cancel(pi)
    }

    fun scheduleLottery(ctx: Context, lotteryId: Int, cycleMin: Long) {
        val pi = PendingIntent.getBroadcast(
            ctx, 2000 + lotteryId, Intent(ctx, AlarmReceiver::class.java)
                .setAction(ACTION_LOTTERY).putExtra(EXTRA_LOTTERY_ID, lotteryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + cycleMin * 60_000 + 10_000
        exact(ctx, triggerAt, pi)
    }

    fun cancelLottery(ctx: Context, lotteryId: Int) {
        val pi = PendingIntent.getBroadcast(
            ctx, 2000 + lotteryId, Intent(ctx, AlarmReceiver::class.java)
                .setAction(ACTION_LOTTERY).putExtra(EXTRA_LOTTERY_ID, lotteryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mgr(ctx).cancel(pi)
    }
}
