package ru.cs2a.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.cs2a.helper.alarm.AlarmScheduler
import ru.cs2a.helper.spam.SpamForegroundService
import ru.cs2a.helper.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Без разрешения на уведомления не будет видно, что служба спама работает", Toast.LENGTH_LONG).show()
    }
    private val panels by lazy {
        mapOf(
            "autoBet" to findViewById<View>(R.id.panelAutoBet),
            "farm" to findViewById<View>(R.id.panelFarm),
            "raffles" to findViewById<View>(R.id.panelRaffles),
            "spam" to findViewById<View>(R.id.panelSpam),
            "status" to findViewById<View>(R.id.panelStatus),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenStore.init(this)
        setContentView(R.layout.activity_main)

        showTab("farm")
        findViewById<Button>(R.id.tabAutoBet).setOnClickListener { showTab("autoBet") }
        findViewById<Button>(R.id.tabFarm).setOnClickListener { showTab("farm") }
        findViewById<Button>(R.id.tabRaffles).setOnClickListener { showTab("raffles") }
        findViewById<Button>(R.id.tabSpam).setOnClickListener { showTab("spam") }
        findViewById<Button>(R.id.tabStatus).setOnClickListener { showTab("status") }

        setupAutoBet()
        setupFarm()
        setupRaffles()
        setupSpam()
        setupStatus()
        refreshLoop()
        checkForUpdate(silent = true)
        ensureNotificationPermission()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------------- Обновления (GitHub Releases) ----------------
    private fun checkForUpdate(silent: Boolean) {
        lifecycleScope.launch {
            val info = UpdateManager.checkLatest()
            if (info == null) {
                if (!silent) Toast.makeText(this@MainActivity, "Обновлений нет — у вас последняя версия", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Доступно обновление: ${info.versionName}")
                .setMessage(info.notes.ifBlank { "Новая версия приложения." })
                .setPositiveButton("Скачать и установить") { _, _ ->
                    UpdateManager.downloadAndInstall(this@MainActivity, info) {
                        Toast.makeText(this@MainActivity, "Скачивание начато, уведомление появится по завершении", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun showTab(name: String) {
        for ((k, v) in panels) v.visibility = if (k == name) View.VISIBLE else View.GONE
    }

    // ---------------- Ферма / кэшбек ----------------
    private fun setupFarm() {
        val spinner = findViewById<Spinner>(R.id.farmEggSpinner)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FARM_EGGS.map { "${it.label} (tier ${it.tier})" })
        val savedTier = TokenStore.getInt("farmTier", DEFAULT_FARM_TIER)
        spinner.setSelection(FARM_EGGS.indexOfFirst { it.tier == savedTier }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                TokenStore.setInt("farmTier", FARM_EGGS[pos].tier)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val autoSwitch = findViewById<Switch>(R.id.farmAutoSwitch)
        autoSwitch.isChecked = TokenStore.getBool("farmAuto")
        autoSwitch.setOnCheckedChangeListener { _, on ->
            TokenStore.setBool("farmAuto", on)
            if (on) AlarmScheduler.scheduleFarm(this) else AlarmScheduler.cancelFarm(this)
            Logs.add(this, "farm", if (on) "Автозабор яйца включён (ежедневно, 03:00 МСК)" else "Автозабор яйца выключен", "")
        }

        val sellSwitch = findViewById<Switch>(R.id.farmSellSwitch)
        sellSwitch.isChecked = TokenStore.getBool("farmSellOpened")
        sellSwitch.setOnCheckedChangeListener { _, on -> TokenStore.setBool("farmSellOpened", on) }

        findViewById<Button>(R.id.farmClaimBtn).setOnClickListener {
            lifecycleScope.launch { Api.farmEggAttempt(this@MainActivity); refreshLogs() }
        }

        val cbAuto = findViewById<Switch>(R.id.cashbackAutoSwitch)
        cbAuto.isChecked = TokenStore.getBool("cashbackAuto")
        cbAuto.setOnCheckedChangeListener { _, on ->
            TokenStore.setBool("cashbackAuto", on)
            if (on) AlarmScheduler.scheduleCashback(this) else AlarmScheduler.cancelCashback(this)
            Logs.add(this, "farm", if (on) "Автокэшбек включён (ежедневно, 03:00 МСК)" else "Автокэшбек выключен", "")
        }
        findViewById<Button>(R.id.cashbackClaimBtn).setOnClickListener {
            lifecycleScope.launch { Api.cashbackAttempt(this@MainActivity); refreshLogs() }
        }
    }

    // ---------------- Розыгрыши ----------------
    private fun setupRaffles() {
        val list = findViewById<LinearLayout>(R.id.rafflesList)
        for (item in LOTTERIES) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 8, 0, 8)
            val tv = TextView(this)
            tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            tv.text = "${item.name}\n${item.price} · ${periodLabel(item.period)}"
            tv.setTextColor(resources.getColor(R.color.textDim, theme))
            tv.textSize = 12f
            val btn = Button(this)
            btn.text = "Участвовать"
            btn.setOnClickListener {
                lifecycleScope.launch { Api.joinLottery(this@MainActivity, item); refreshLogs() }
            }
            row.addView(tv)
            row.addView(btn)
            list.addView(row)
        }

        val autoSwitch = findViewById<Switch>(R.id.rafflesAutoSwitch)
        autoSwitch.isChecked = TokenStore.getBool("rafflesAuto")
        autoSwitch.setOnCheckedChangeListener { _, on ->
            TokenStore.setBool("rafflesAuto", on)
            Logs.add(this, "raffles", if (on) "Автоучастие включено" else "Автоучастие выключено", "")
            if (on) {
                // Пробуем сразу для всех розыгрышей (как кнопка «Участвовать»), а не ждём
                // целый цикл до первого будильника — иначе выглядит так, будто тумблер
                // ничего не делает.
                lifecycleScope.launch {
                    for (item in LOTTERIES) {
                        Api.joinLottery(this@MainActivity, item)
                        refreshLogs()
                    }
                }
                for (item in LOTTERIES) AlarmScheduler.scheduleLottery(this, item.id, item.cycleMin)
            } else {
                for (item in LOTTERIES) AlarmScheduler.cancelLottery(this, item.id)
            }
        }
    }

    private fun periodLabel(p: String) = when (p) {
        "hour" -> "каждый час"; "day" -> "каждый день"; "week" -> "каждую неделю"
        "month" -> "каждый месяц"; else -> "для олдов"
    }

    // ---------------- Спам ----------------
    private fun setupSpam() {
        val textEdit = findViewById<EditText>(R.id.spamText)
        textEdit.setText(TokenStore.getString("spamText"))
        textEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { TokenStore.setString("spamText", s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val delayEdit = findViewById<EditText>(R.id.spamDelay)
        delayEdit.setText(TokenStore.getInt("spamDelay", 1900).toString())
        delayEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { TokenStore.setInt("spamDelay", it) } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        findViewById<Button>(R.id.spamTeachBtn).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }

        findViewById<Button>(R.id.spamStartBtn).setOnClickListener {
            if (TokenStore.getString("spamText").isBlank()) {
                Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            if (!TokenStore.getBool("spamHasTemplate")) {
                Toast.makeText(this, "Сначала обучите формат отправки", Toast.LENGTH_LONG).show(); return@setOnClickListener
            }
            val intent = Intent(this, SpamForegroundService::class.java).setAction(SpamForegroundService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
        findViewById<Button>(R.id.spamStopBtn).setOnClickListener {
            val intent = Intent(this, SpamForegroundService::class.java).setAction(SpamForegroundService.ACTION_STOP)
            startService(intent)
        }
    }


    // ---------------- Crash — ограниченные автоставки ----------------
    private fun setupAutoBet() {
        val amount = findViewById<EditText>(R.id.betAmount)
        val auto = findViewById<EditText>(R.id.betAuto)
        val rounds = findViewById<EditText>(R.id.betRounds)
        val allIn = findViewById<Switch>(R.id.betAllIn)

        amount.setText(TokenStore.getString("crashAmount", "0.10"))
        auto.setText(TokenStore.getString("crashAutoMultiplier", "1.25"))
        rounds.setText(TokenStore.getInt("crashRounds", 5).toString())
        allIn.isChecked = TokenStore.getBool("crashAllIn")
        amount.isEnabled = !allIn.isChecked

        amount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { TokenStore.setString("crashAmount", s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        auto.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { TokenStore.setString("crashAutoMultiplier", s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        rounds.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { TokenStore.setInt("crashRounds", it) } }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        allIn.setOnCheckedChangeListener { _, on ->
            TokenStore.setBool("crashAllIn", on)
            amount.isEnabled = !on
        }
        findViewById<Button>(R.id.betHalf).setOnClickListener {
            val v = amount.text.toString().toDoubleOrNull() ?: 0.10
            amount.setText(String.format(Locale.US, "%.2f", (v / 2.0).coerceAtLeast(0.01)))
        }
        findViewById<Button>(R.id.betDouble).setOnClickListener {
            val v = amount.text.toString().toDoubleOrNull() ?: 0.10
            amount.setText(String.format(Locale.US, "%.2f", (v * 2.0).coerceAtLeast(0.01)))
        }
        findViewById<Button>(R.id.betStart).setOnClickListener {
            val stake = amount.text.toString().toDoubleOrNull() ?: 0.0
            val multiplier = auto.text.toString().toDoubleOrNull() ?: 0.0
            val count = rounds.text.toString().toIntOrNull() ?: 0
            if (!allIn.isChecked && stake <= 0.0) {
                Toast.makeText(this, "Укажите сумму ставки больше нуля", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (multiplier < 1.0 || !multiplier.isFinite()) {
                Toast.makeText(this, "Автовывод должен быть не меньше 1.00", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (count !in 1..1000) {
                Toast.makeText(this, "Количество раундов: от 1 до 1000", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!TokenStore.isApiTokenValid()) {
                Toast.makeText(this, "Сначала войдите на cs2a.run во вкладке «Статус»", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            TokenStore.setInt("crashRoundsTotal", count)
            TokenStore.setInt("crashRoundsDone", 0)
            TokenStore.setString("crashLastRoundId", "")
            TokenStore.setBool("crashAutoRunning", true)
            TokenStore.setString("crashStatus", "Подключаюсь к cs2a.run…")
            val intent = Intent(this, ru.cs2a.helper.crash.CrashMonitorService::class.java)
                .setAction(ru.cs2a.helper.crash.CrashMonitorService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
        findViewById<Button>(R.id.betStop).setOnClickListener {
            TokenStore.setBool("crashAutoRunning", false)
            val intent = Intent(this, ru.cs2a.helper.crash.CrashMonitorService::class.java)
                .setAction(ru.cs2a.helper.crash.CrashMonitorService.ACTION_STOP)
            startService(intent)
            TokenStore.setString("crashStatus", "Остановлено вручную")
        }
    }

    // ---------------- Статус ----------------
    private fun setupStatus() {
        findViewById<Button>(R.id.loginBtn).setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        findViewById<Button>(R.id.logoutBtn).setOnClickListener {
            TokenStore.clearJwt()
            refreshStatus()
            Toast.makeText(this, "Токен удалён", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this, "Уже отключено", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        findViewById<Button>(R.id.updateBtn).setOnClickListener { checkForUpdate(silent = false) }
        findViewById<TextView>(R.id.appVersion).text = "Версия: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        refreshStatus()
    }

    private val expFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private fun refreshStatus() {
        val tv = findViewById<TextView>(R.id.tokenStatus)
        tv.text = if (TokenStore.isApiTokenValid()) {
            "Токен: получен ✅ (действует до ${expFmt.format(Date(TokenStore.jwtExp()))})"
        } else "Токен: не получен — войдите на cs2a.run"
    }

    private fun refreshLogs() {
        findViewById<TextView>(R.id.farmLog).text = Logs.render(this, "farm")
        findViewById<TextView>(R.id.rafflesLog).text = Logs.render(this, "raffles")
        findViewById<TextView>(R.id.spamLog).text = Logs.render(this, "spam")
        findViewById<TextView>(R.id.autoBetStatus).text = TokenStore.getString("crashStatus", "Остановлено") + " · " + TokenStore.getInt("crashRoundsDone", 0) + "/" + TokenStore.getInt("crashRoundsTotal", 0)
        findViewById<TextView>(R.id.autoBetLog).text = Logs.render(this, "crash")
        findViewById<TextView>(R.id.spamTplStatus).text =
            if (TokenStore.getBool("spamHasTemplate")) "Формат записан ✅" else "Формат пока не записан — нажмите «Обучить формат»"
    }

    private fun refreshLoop() {
        refreshStatus(); refreshLogs()
        handler.postDelayed({ refreshLoop() }, 3000)
    }
}
