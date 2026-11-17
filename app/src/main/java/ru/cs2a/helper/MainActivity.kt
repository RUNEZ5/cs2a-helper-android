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
import android.view.Gravity
import android.widget.ScrollView
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.cs2a.helper.alarm.AlarmScheduler
import ru.cs2a.helper.spam.SpamForegroundService
import ru.cs2a.helper.update.UpdateManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.ClipData

class MainActivity : AppCompatActivity() {
    companion object {
        private const val KEY_RISK_WARNING_ACCEPTED = "risk_warning_accepted"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var selectedPromoResultsCategory = "activated"
    private var mainUiActive = false
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Без разрешения на уведомления не будет видно, что служба спама работает", Toast.LENGTH_LONG).show()
    }
    private val panels by lazy {
        mapOf(
            "farm" to findViewById<View>(R.id.panelFarm),
            "raffles" to findViewById<View>(R.id.panelRaffles),
            "spam" to findViewById<View>(R.id.panelSpam),
            "status" to findViewById<View>(R.id.panelStatus),
            "promo" to findViewById<View>(R.id.panelPromo),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TokenStore.init(this)
        verifyLicenseAndContinue()
    }

    override fun onResume() {
        super.onResume()
        if (mainUiActive && TokenStore.getSecret("licenseToken").isBlank()) {
            stopLicensedServices()
            showLicenseScreen("Доступ к приложению отозван. Введите действующий лицензионный ключ.")
        }
    }

    
    private fun continueIntoApp() {
        setContentView(R.layout.activity_main)
        val preferences = getSharedPreferences("app_preferences", MODE_PRIVATE)
        if (preferences.getBoolean(KEY_RISK_WARNING_ACCEPTED, false)) {
            initializeMainUi()
        } else {
            showFirstLaunchDisclaimer()
        }
        scheduleLicenseRecheck()
    }

    private fun verifyLicenseAndContinue() {
        showLicenseScreen("Проверяю лицензию…", loading = true)
        lifecycleScope.launch {
            val token = TokenStore.getSecret("licenseToken")
            if (token.isBlank()) {
                stopLicensedServices()
                showLicenseScreen("Для использования приложения необходим лицензионный ключ.")
                return@launch
            }
            val result = LicenseManager.validate(this@MainActivity, token)
            if (result.valid) {
                TokenStore.setString("licenseLastValidated", System.currentTimeMillis().toString())
                continueIntoApp()
            } else {
                val last = TokenStore.getString("licenseLastValidated").toLongOrNull() ?: 0L
                val grace = 72L * 60L * 60L * 1000L
                if (result.networkError && last > 0L && System.currentTimeMillis() - last < grace) {
                    continueIntoApp()
                } else {
                    if (!result.networkError) {
                        TokenStore.saveSecret("licenseToken", "")
                        TokenStore.setString("licenseLastValidated", "")
                    }
                    stopLicensedServices()
                    showLicenseScreen(
                        if (result.networkError) "Не удалось связаться с сервером. Проверьте интернет и попробуйте снова."
                        else "Лицензия недействительна, истекла или была отозвана. Введите действующий ключ."
                    )
                }
            }
        }
    }

    private fun showLicenseScreen(message: String, loading: Boolean = false) {
        mainUiActive = false
        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 32)
        }
        root.addView(content)
        content.addView(TextView(this).apply {
            text = "CS2A ПОМОЩНИК"
            textSize = 25f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = "АКТИВАЦИЯ ЛИЦЕНЗИИ"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 22)
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = message
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }, LinearLayout.LayoutParams(-1, -2))
        val keyInput = EditText(this).apply {
            hint = "Введите лицензионный ключ"
            isSingleLine = true
            isEnabled = !loading
            setPadding(18, 14, 18, 14)
        }
        content.addView(keyInput, LinearLayout.LayoutParams(-1, -2))
        val activate = Button(this).apply {
            text = if (loading) "ПОДОЖДИТЕ…" else "АКТИВИРОВАТЬ"
            isEnabled = !loading
        }
        content.addView(activate, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
        activate.setOnClickListener {
            val key = keyInput.text.toString().trim()
            if (key.isBlank()) {
                keyInput.error = "Введите ключ"
                return@setOnClickListener
            }
            activate.isEnabled = false
            activate.text = "АКТИВАЦИЯ…"
            lifecycleScope.launch {
                val result = LicenseManager.activate(this@MainActivity, key)
                if (result.valid && !result.token.isNullOrBlank()) {
                    TokenStore.saveSecret("licenseToken", result.token)
                    TokenStore.setString("licenseLastValidated", System.currentTimeMillis().toString())
                    Toast.makeText(this@MainActivity, "Лицензия успешно активирована", Toast.LENGTH_LONG).show()
                    continueIntoApp()
                } else {
                    activate.isEnabled = true
                    activate.text = "АКТИВИРОВАТЬ"
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
        val contact = Button(this).apply {
            text = "НАПИСАТЬ В ЛС КАНАЛА"
            isEnabled = !loading
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/runchikist?direct"))) }
                catch (_: Exception) { Toast.makeText(this@MainActivity, "Свяжитесь с администратором: https://t.me/runchikist?direct", Toast.LENGTH_LONG).show() }
            }
        }
        content.addView(contact, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        setContentView(root)
    }

    private fun stopLicensedServices() {
        TokenStore.setBool("rafflesAuto", false)
        TokenStore.setBool("farmAuto", false)
        TokenStore.setBool("promoAuto", false)
        stopService(Intent(this, SpamForegroundService::class.java))
        stopService(Intent(this, PromoTelegramService::class.java))
        stopService(Intent(this, ActivityStatusService::class.java))
    }

    private fun scheduleLicenseRecheck() {
        handler.postDelayed({
            lifecycleScope.launch {
                val token = TokenStore.getSecret("licenseToken")
                if (token.isBlank()) {
                    stopLicensedServices()
                    showLicenseScreen("Лицензия не найдена. Введите ключ.")
                    return@launch
                }
                val result = LicenseManager.validate(this@MainActivity, token)
                if (result.valid) {
                    TokenStore.setString("licenseLastValidated", System.currentTimeMillis().toString())
                    scheduleLicenseRecheck()
                } else {
                    val last = TokenStore.getString("licenseLastValidated").toLongOrNull() ?: 0L
                    val grace = 72L * 60L * 60L * 1000L
                    if (result.networkError && last > 0L && System.currentTimeMillis() - last < grace) {
                        scheduleLicenseRecheck()
                    } else {
                        if (!result.networkError) {
                            TokenStore.saveSecret("licenseToken", "")
                            TokenStore.setString("licenseLastValidated", "")
                        }
                        stopLicensedServices()
                        showLicenseScreen("Лицензия недействительна или отозвана. Свяжитесь с администратором через личные сообщения канала.")
                    }
                }
            }
        }, 6L * 60L * 60L * 1000L)
    }

    private fun showFirstLaunchDisclaimer() {
        AlertDialog.Builder(this)
            .setTitle("Предупреждение и принятие рисков")
            .setMessage(
                "Это приложение предоставляется «как есть». Использование его функций может привести к " +
                    "ограничению, блокировке или потере доступа к аккаунту, потере средств и другим последствиям. " +
                    "Вы самостоятельно оцениваете риски и несёте ответственность за использование приложения и его функций.\n\n" +
                    "Разработчик не гарантирует отсутствие санкций со стороны сторонних сервисов и не несёт " +
                    "ответственности за блокировки или ограничения аккаунта, а также связанные с этим последствия. " +
                    "Принимая условия, вы подтверждаете, что понимаете риски и не будете предъявлять автору " +
                    "претензии в связи с блокировкой или ограничением аккаунта."
            )
            .setCancelable(false)
            .setPositiveButton("Принимаю риски и ответственность") { _, _ ->
                getSharedPreferences("app_preferences", MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_RISK_WARNING_ACCEPTED, true)
                    .apply()
                initializeMainUi()
            }
            .setNegativeButton("Не принимаю — выйти") { _, _ ->
                finish()
            }
            .show()
    }

    private fun initializeMainUi() {
        mainUiActive = true
        showTab("farm")
        findViewById<Button>(R.id.tabFarm).setOnClickListener { showTab("farm") }
        findViewById<Button>(R.id.tabRaffles).setOnClickListener { showTab("raffles") }
        findViewById<Button>(R.id.tabSpam).setOnClickListener { showTab("spam") }
        findViewById<Button>(R.id.tabStatus).setOnClickListener { showTab("status") }
        findViewById<Button>(R.id.tabPromo).setOnClickListener { showTab("promo") }

        setupFarm()
        setupRaffles()
        setupSpam()
        setupStatus()
        setupPromo()
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

        val page = when (name) {
            "farm" -> "Ферма" to "Ежедневные награды и автоматизация"
            "raffles" -> "Розыгрыши" to "Автоматическое участие и расписание"
            "spam" -> "Чат-активность" to "Управление сообщениями и шаблоном"
            "promo" -> "Промокоды" to "Поиск, проверка и активация"
            "status" -> "Статус и настройки" to "Аккаунт, обслуживание и диагностика"
            else -> "CS2A Helper" to "Ваш помощник"
        }
        findViewById<TextView>(R.id.pageTitle).text = page.first
        findViewById<TextView>(R.id.pageSubtitle).text = page.second

        val tabIds = mapOf(
            "farm" to R.id.tabFarm,
            "raffles" to R.id.tabRaffles,
            "spam" to R.id.tabSpam,
            "promo" to R.id.tabPromo,
            "status" to R.id.tabStatus
        )
        for ((key, id) in tabIds) {
            val tab = findViewById<Button>(id)
            val selected = key == name
            tab.setTextColor(ContextCompat.getColor(this, if (selected) R.color.bg else R.color.textDim))
            tab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, if (selected) R.color.accent else R.color.card)
            )
        }
    }

    private fun setupPromo() {
        val apiId = findViewById<EditText>(R.id.promoApiId)
        val apiHash = findViewById<EditText>(R.id.promoApiHash)
        val phone = findViewById<EditText>(R.id.promoPhone)
        val code = findViewById<EditText>(R.id.promoLoginCode)
        val password = findViewById<EditText>(R.id.promoPassword)
        val mirror = findViewById<Spinner>(R.id.promoMirror)
        val scanDays = findViewById<EditText>(R.id.promoScanDays)
        val activationInterval = findViewById<EditText>(R.id.promoActivationInterval)

        apiId.setText(TokenStore.getString("promoApiId"))
        apiHash.setText(TokenStore.getSecret("promoApiHash"))
        phone.setText(TokenStore.getSecret("promoPhone"))
        mirror.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, PromoActivator.mirrors)
        val savedMirror = TokenStore.getString("promoMirror", "cs2a.run")
        mirror.setSelection(PromoActivator.mirrors.indexOf(savedMirror).coerceAtLeast(0))
        scanDays.setText(TokenStore.getInt("promoScanDays", 7).toString())
        activationInterval.setText(TokenStore.getInt("promoActivationIntervalSec", 1).toString())

        val promoResultTitles = mapOf(
            "activated" to "Активированные промокоды",
            "deposit" to "Не активированы · условия депозита",
            "used" to "Не активированы · уже использованы",
            "invalid" to "Не активированы · неверные или истекшие",
            "other" to "Не активированы · другие причины"
        )
        fun selectPromoResults(category: String) {
            selectedPromoResultsCategory = category
            findViewById<TextView>(R.id.promoResultsTitle).text = promoResultTitles[category] ?: "Результаты"
            findViewById<TextView>(R.id.promoResultsList).text = PromoActivator.historyForCategory(category)
        }
        findViewById<Button>(R.id.promoResultsActivatedBtn).setOnClickListener { selectPromoResults("activated") }
        findViewById<Button>(R.id.promoResultsDepositBtn).setOnClickListener { selectPromoResults("deposit") }
        findViewById<Button>(R.id.promoResultsUsedBtn).setOnClickListener { selectPromoResults("used") }
        findViewById<Button>(R.id.promoResultsInvalidBtn).setOnClickListener { selectPromoResults("invalid") }
        findViewById<Button>(R.id.promoResultsOtherBtn).setOnClickListener { selectPromoResults("other") }
        selectPromoResults(selectedPromoResultsCategory)

        findViewById<Button>(R.id.promoStartBtn).setOnClickListener {
            val days = scanDays.text.toString().trim().toIntOrNull()
            val interval = activationInterval.text.toString().trim().toIntOrNull()
            if (days == null || days !in 1..36500) { Toast.makeText(this, "Период должен быть от 1 до 36500 дней", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            if (interval == null || interval !in 1..86400) { Toast.makeText(this, "Интервал должен быть от 1 до 86400 секунд", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            val id = apiId.text.toString().trim().toIntOrNull()
            val hash = apiHash.text.toString().trim()
            val number = phone.text.toString().trim()
            if (id == null || id <= 0 || hash.isBlank() || number.isBlank()) {
                Toast.makeText(this, "Заполните API ID, API Hash и номер Telegram", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!TokenStore.isApiTokenValid()) {
                Toast.makeText(this, "Сначала войдите на выбранное зеркало CS2A во вкладке «Статус»", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            TokenStore.setString("promoApiId", id.toString())
            TokenStore.saveSecret("promoApiHash", hash)
            TokenStore.saveSecret("promoPhone", number)
            TokenStore.setString("promoMirror", PromoActivator.mirrors[mirror.selectedItemPosition])
            TokenStore.setInt("promoScanDays", days)
            TokenStore.setInt("promoActivationIntervalSec", interval)
            TokenStore.setBool("promoAuto", false)
            TokenStore.setBool("promoScanRequested", false)
            PromoTelegramService.start(this)
            findViewById<TextView>(R.id.promoStatus).text = "Подключаюсь к Telegram…"
        }
        findViewById<Button>(R.id.promoScanBtn).setOnClickListener {
            PromoTelegramService.scanHistory(this)
            findViewById<TextView>(R.id.promoStatus).text = "Проверяю историю @promo_runs…"
        }
        findViewById<Button>(R.id.promoMonitorBtn).setOnClickListener {
            if (!TokenStore.isApiTokenValid()) {
                Toast.makeText(this, "Сначала войдите на выбранное зеркало CS2A во вкладке «Статус»", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            PromoTelegramService.startMonitoring(this)
            findViewById<TextView>(R.id.promoStatus).text = "Запускаю мониторинг @promo_runs…"
        }
        findViewById<Button>(R.id.promoSubmitCodeBtn).setOnClickListener {
            val value = code.text.toString().trim()
            if (value.isBlank()) { Toast.makeText(this, "Введите код из Telegram", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            PromoTelegramService.submitCode(this, value)
            code.text.clear()
        }
        findViewById<Button>(R.id.promoSubmitPasswordBtn).setOnClickListener {
            val value = password.text.toString()
            if (value.isBlank()) { Toast.makeText(this, "Введите пароль 2FA", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            PromoTelegramService.submitPassword(this, value)
            password.text.clear()
        }
        findViewById<Button>(R.id.promoScanStopBtn).setOnClickListener {
            PromoTelegramService.stopHistoryScan(this)
            findViewById<TextView>(R.id.promoStatus).text = "Останавливаю проверку истории…"
        }
        findViewById<Button>(R.id.promoClearHistoryBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить историю промокодов?")
                .setMessage("Будут удалены сохранённые результаты активации для текущего аккаунта. Повторная проверка сможет найти эти коды снова.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ ->
                    if (PromoActivator.clearCurrentAccountHistory()) {
                        Toast.makeText(this, "История использованных промо удалена", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Нет активного токена аккаунта", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
        findViewById<Button>(R.id.promoStopBtn).setOnClickListener {
            PromoTelegramService.stop(this)
            TokenStore.setBool("promoAuto", false)
            findViewById<TextView>(R.id.promoStatus).text = "Остановлено"
            ActivityStatusService.sync(this)
        }
    }


    // ---------------- Ферма ----------------
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

        // Снимаем будильник кэшбека, оставшийся от предыдущих версий.
        AlarmScheduler.cancelCashback(this)
        TokenStore.setBool("cashbackAuto", false)
    }

    // ---------------- Розыгрыши ----------------
    private fun setupRaffles() {
        val list = findViewById<LinearLayout>(R.id.rafflesList)
        list.removeAllViews()

        val periodGroups = listOf(
            "hour" to "За час",
            "day" to "За день",
            "week" to "За неделю",
            "month" to "За месяц",
            "old" to "Для олдов"
        )

        for ((period, title) in periodGroups) {
            val items = LOTTERIES.filter {
                when (period) {
                    "old" -> it.period != "hour" && it.period != "day" &&
                        it.period != "week" && it.period != "month"
                    else -> it.period == period
                }
            }
            if (items.isEmpty()) continue

            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14, 12, 14, 8)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.panel_surface)
            }

            val heading = TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                setPadding(2, 0, 2, 8)
            }
            section.addView(heading)

            for (item in items) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(2, 7, 2, 7)
                }

                val info = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "${item.name}\\n${item.price} · ${periodLabel(item.period)}"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                    textSize = 13f
                }

                val btn = Button(this).apply {
                    text = "Участвовать"
                    setOnClickListener {
                        lifecycleScope.launch {
                            Api.joinLottery(this@MainActivity, item)
                            refreshLogs()
                        }
                    }
                }

                row.addView(info)
                row.addView(btn)
                section.addView(row)
            }

            val sectionParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            list.addView(section, sectionParams)
        }

        val autoSwitch = findViewById<Switch>(R.id.rafflesAutoSwitch)
        autoSwitch.isChecked = TokenStore.getBool("rafflesAuto")
        autoSwitch.setOnCheckedChangeListener { _, on ->
            TokenStore.setBool("rafflesAuto", on)
            Logs.add(this, "raffles", if (on) "Автоучастие включено" else "Автоучастие выключено", "")
            if (on) {
                // Сразу пробуем участвовать во всех розыгрышах.
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
        findViewById<Button>(R.id.exportLogsBtn).setOnClickListener { exportDiagnosticLogs() }
        findViewById<TextView>(R.id.appVersion).text = "Версия: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        refreshStatus()
    }

    private fun exportDiagnosticLogs() {
        Toast.makeText(this, "Готовлю архив логов…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val archive = Logs.exportArchive(this@MainActivity, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        archive
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(contentResolver, "CS2A diagnostics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Сохранить или отправить диагностические логи"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Не удалось подготовить логи: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
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
        findViewById<TextView>(R.id.spamTplStatus).text =
            if (TokenStore.getBool("spamHasTemplate")) "Формат записан ✅" else "Формат пока не записан — нажмите «Обучить формат»"
    }

    private fun refreshLoop() {
        if (!mainUiActive) return
        refreshStatus()
        val visiblePanel = panels.entries.firstOrNull { it.value.visibility == View.VISIBLE }?.key
        when (visiblePanel) {
            "farm" -> findViewById<TextView>(R.id.farmLog).text = Logs.render(this, "farm")
            "raffles" -> findViewById<TextView>(R.id.rafflesLog).text = Logs.render(this, "raffles")
            "spam" -> {
                findViewById<TextView>(R.id.spamLog).text = Logs.render(this, "spam")
                findViewById<TextView>(R.id.spamTplStatus).text =
                    if (TokenStore.getBool("spamHasTemplate")) "Формат записан ✅" else "Формат пока не записан — нажмите «Обучить формат»"
            }
            "promo" -> {
                findViewById<TextView>(R.id.promoStatus).text =
                    TokenStore.getString("promoStatus", "Остановлено").ifBlank { "Остановлено" }
                findViewById<TextView>(R.id.promoLog).text = Logs.render(this, "promo")
                findViewById<TextView>(R.id.promoResultsList).text = PromoActivator.historyForCategory(selectedPromoResultsCategory)
            }
        }

        ActivityStatusService.sync(this)
        handler.postDelayed({ refreshLoop() }, 3000)
    }
}