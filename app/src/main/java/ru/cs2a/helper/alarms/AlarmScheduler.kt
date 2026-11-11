package ru.cs2a.helper.alarms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import java.util.TimeZone

/**
 * Аналог chrome.alarms из background.js: будильники на фиксированное время по МСК (ферма,
 * кэшбек) и на относительную задержку (розыгрыши — свой цикл у каждого). Используется
 * AlarmManager.setExactAndAllowWhileIdle, как ближайший аналог chrome.alarms, переживающий
 * Doze — срабатывание чуть менее точное, чем у расширения, но достаточно близкое.
 */
object AlarmScheduler {
    const val ACTION_FARM_EGG = "ru.cs2a.helper.FARM_EGG"
    const val ACTION_CASHBACK = "ru.cs2a.helper.CASHBACK"
    const val ACTION_LOTTERY = "ru.cs2a.helper.LOTTERY"
    const val EXTRA_LOTTERY_ID = "lotteryId"

    private const val REQ_FARM_EGG = 1001
    private const val REQ_CASHBACK = 1002
    private const val REQ_LOTTERY_BASE = 2000

    private fun am(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Следующее время (эпоха, мс) для hh:mm:ss по МСК (UTC+3, без перехода на летнее время). */
    fun nextMskTime(hourMsk: Int, minuteMsk: Int, secondMsk: Int): Long {
        val tz = TimeZone.getTimeZone("UTC")
        val cal = Calendar.getInstance(tz)
        val utcHour = ((hourMsk - 3) + 24) % 24
        cal.set(Calendar.HOUR_OF_DAY, utcHour)
        cal.set(Calendar.MINUTE, minuteMsk)
        cal.set(Calendar.SECOND, secondMsk)
        cal.set(Calendar.MILLISECOND, 0)
        var t = cal.timeInMillis
        if (t <= System.currentTimeMillis()) t += 24L * 3600 * 1000
        return t
    }

    private fun setExact(ctx: Context, reqCode: Int, triggerAt: Long, action: String, lotteryId: Int? = null) {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(action)
        if (lotteryId != null) intent.putExtra(EXTRA_LOTTERY_ID, lotteryId)
        val pi = PendingIntent.getBroadcast(
            ctx, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (e: SecurityException) {
            // нет разрешения на точные будильники (редкий случай на некоторых прошивках) — неточный запасной вариант
            am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancel(ctx: Context, reqCode: Int, action: String, lotteryId: Int? = null) {
        val intent = Intent(ctx, AlarmReceiver::class.java).setAction(action)
        if (lotteryId != null) intent.putExtra(EXTRA_LOTTERY_ID, lotteryId)
        val pi = PendingIntent.getBroadcast(
            ctx, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am(ctx).cancel(pi)
    }

    fun scheduleFarmEgg(ctx: Context) = setExact(ctx, REQ_FARM_EGG, nextMskTime(3, 0, 2), ACTION_FARM_EGG)
    fun cancelFarmEgg(ctx: Context) = cancel(ctx, REQ_FARM_EGG, ACTION_FARM_EGG)

    fun scheduleCashback(ctx: Context) = setExact(ctx, REQ_CASHBACK, nextMskTime(3, 0, 2), ACTION_CASHBACK)
    fun cancelCashback(ctx: Context) = cancel(ctx, REQ_CASHBACK, ACTION_CASHBACK)

    fun scheduleLotteryIn(ctx: Context, id: Int, minutesFromNow: Long) {
        val triggerAt = System.currentTimeMillis() + minutesFromNow * 60_000
        setExact(ctx, REQ_LOTTERY_BASE + id, triggerAt, ACTION_LOTTERY, id)
    }

    fun cancelLottery(ctx: Context, id: Int) = cancel(ctx, REQ_LOTTERY_BASE + id, ACTION_LOTTERY, id)
}
