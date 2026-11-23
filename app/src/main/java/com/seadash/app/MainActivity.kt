package com.seadash.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.seadash.core.VehicleReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Diagnostic dashboard: live vehicle values, a full report for analysis, and a Wi-Fi page for downloading reports.
 * Read-only: nothing here sends commands to the vehicle.
 */
class MainActivity : Activity() {

    private lateinit var worker: ScheduledExecutorService
    private lateinit var link: AdbLink
    private lateinit var reports: Reports
    private lateinit var web: WebServer
    private var inProcess: VehicleReader? = null

    // Worker-thread state
    @Volatile private var dumping = false
    @Volatile private var forceConnect = true
    private var lastConnectAttempt = 0L
    private var portOpen = false
    private var source = "—"
    private var lastError = ""

    // Shared with the web server
    @Volatile private var lastRaw = ""
    @Volatile private var lastTiles: List<LiveFormat.Tile> = emptyList()
    @Volatile private var statusText = "запуск…"

    private lateinit var statusView: TextView
    private lateinit var webView: TextView
    private lateinit var logView: TextView
    private lateinit var dumpButton: Button
    private val tileViews = HashMap<String, Triple<TextView, TextView, View>>()
    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = AdbLink(this)
        reports = Reports(this)
        web = WebServer(reports) { WebServer.Snapshot(statusText, lastRaw, lastTiles) }
        setContentView(buildUi())
        web.start()
        log("SeaDash 0.1 запущен. Приложение только читает данные и ничего не отправляет в машину.")
        worker = Executors.newSingleThreadScheduledExecutor()
        worker.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { log("ошибка цикла: $it") } }, 0, 1, TimeUnit.SECONDS)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        web.stop()
        Thread { link.reset() }.start()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Worker thread
    // ------------------------------------------------------------------

    private fun tick() {
        if (dumping) return
        portOpen = link.isPortOpen()
        var line: String? = null

        val now = System.currentTimeMillis()
        val shouldTry = link.connected || (portOpen && (forceConnect || now - lastConnectAttempt > 15_000))
        if (shouldTry) {
            try {
                if (!link.connected) {
                    forceConnect = false
                    lastConnectAttempt = now
                    log("Подключаюсь к ADB 127.0.0.1:5555… Если на экране появится «Разрешить отладку?» — нажмите «Разрешить» и отметьте «Всегда».")
                    link.connect()
                    log("Помощник запущен: ${link.helperInfo}")
                }
                line = link.live()
                source = "ADB-помощник"
                lastError = ""
            } catch (e: Exception) {
                lastError = e.message.orEmpty()
                log("ADB недоступен: $lastError")
            }
        }
        if (line == null) {
            line = try {
                (inProcess ?: VehicleReader(applicationContext).also { inProcess = it }).live()
            } catch (t: Throwable) {
                "!" + t
            }
            source = "внутри приложения (без ADB)"
        }

        val tiles = LiveFormat.tiles(LiveFormat.parse(line))
        lastRaw = line
        lastTiles = tiles
        val okCount = tiles.count { it.ok }
        statusText = buildString {
            append("ADB-порт 5555: ").append(if (portOpen) "открыт" else "закрыт")
            append(" · помощник: ").append(if (link.connected) "работает (${link.helperInfo.removePrefix("READY ")})" else "нет")
            append(" · источник: ").append(source)
            append(" · сигналов с данными: ").append(okCount).append(" из ").append(tiles.size)
            if (!portOpen) append("\nADB выключен на магнитоле — без него BYD обычно не отдаёт данные. Включите ADB / беспроводную отладку и нажмите «Подключить ADB».")
            if (lastError.isNotEmpty()) append("\nПоследняя ошибка: ").append(lastError.take(160))
        }
        runOnUiThread { render(tiles) }
    }

    private fun runReport() {
        if (dumping) return
        dumping = true
        runOnUiThread { dumpButton.isEnabled = false; dumpButton.text = "Отчёт собирается…" }
        log("Собираю полный отчёт. Это может занять до минуты, не выключайте экран.")
        try {
            val file = reports.newFile()
            var lines = 0
            file.bufferedWriter().use { w ->
                val write: (String) -> Unit = { l -> w.write(l); w.newLine(); lines++ }
                write("SeaDash 0.1 report")
                write("adb port open : ${link.isPortOpen()}")
                write("helper        : ${if (link.connected) link.helperInfo else "not connected"}")
                write("last error    : $lastError")
                write("ips           : ${WebServer.localIps().joinToString()}")
                write("")
                var viaAdb = false
                if (link.connected || link.isPortOpen()) {
                    try {
                        write(">>> collected through the ADB helper (shell uid)")
                        link.dump(write)
                        viaAdb = true
                    } catch (e: Exception) {
                        write("!! ADB report failed: ${e.message}")
                        log("Отчёт через ADB прервался: ${e.message}")
                    }
                }
                if (!viaAdb) {
                    write(">>> collected inside the app (no ADB): vehicle values are usually denied here")
                    (inProcess ?: VehicleReader(applicationContext).also { inProcess = it }).dump(write)
                }
            }
            val copy = reports.publish(file)
            log("Отчёт готов: ${file.name}, $lines строк." + (copy?.let { " Копия: $it" } ?: ""))
            log("Скачать с телефона: ${webUrls().firstOrNull() ?: "подключите магнитолу к Wi-Fi"}")
        } catch (t: Throwable) {
            log("Не удалось собрать отчёт: $t")
        } finally {
            dumping = false
            runOnUiThread { dumpButton.isEnabled = true; dumpButton.text = "Полный отчёт" }
        }
    }

    private fun webUrls(): List<String> =
        if (web.port == 0) emptyList() else WebServer.localIps().map { "http://${it.substringBefore(' ')}:${web.port}" }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun log(msg: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread {
            if (logLines.lastOrNull()?.endsWith(msg) == true) return@runOnUiThread // avoid repeating the same error every second
            logLines.addLast("$stamp  $msg")
            while (logLines.size > 14) logLines.removeFirst()
            if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
        }
    }

    private fun render(tiles: List<LiveFormat.Tile>) {
        statusView.text = statusText
        val urls = webUrls()
        webView.text = if (urls.isEmpty()) "Отчёты по Wi-Fi: магнитола не подключена к сети (подключите её к точке доступа телефона)."
        else "Отчёты и данные с телефона (та же Wi-Fi сеть): " + urls.joinToString("  ·  ")
        for (t in tiles) {
            val (value, raw, box) = tileViews[t.key] ?: continue
            value.text = t.text
            value.setTextColor(if (t.ok) TEXT else MUTED)
            raw.text = t.raw
            box.alpha = if (t.ok) 1f else 0.55f
        }
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(px(20), px(16), px(20), px(12))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "SeaDash"; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(ACCENT)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button("Подключить ADB") {
            forceConnect = true
            worker.execute { link.reset() }
            log("Переподключение…")
        })
        dumpButton = button("Полный отчёт") { worker.execute { runReport() } }
        header.addView(dumpButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(12) })
        root.addView(header)

        statusView = TextView(this).apply { textSize = 14f; setTextColor(MUTED); setPadding(0, px(8), 0, 0) }
        root.addView(statusView)
        webView = TextView(this).apply { textSize = 14f; setTextColor(ACCENT); setPadding(0, px(4), 0, px(10)) }
        root.addView(webView)

        val widthDp = resources.displayMetrics.widthPixels / dp
        val grid = GridLayout(this).apply { columnCount = (widthDp / 210).toInt().coerceIn(2, 6) }
        for ((key, label) in LiveFormat.KEYS) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(14), px(10), px(14), px(10))
                background = GradientDrawable().apply { setColor(CARD); cornerRadius = 14 * dp }
            }
            box.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(MUTED) })
            val value = TextView(this).apply { text = "—"; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(TEXT); maxLines = 1 }
            val raw = TextView(this).apply { textSize = 11f; setTextColor(DIM); maxLines = 2 }
            box.addView(value); box.addView(raw)
            grid.addView(box, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(px(5), px(5), px(5), px(5))
            })
            tileViews[key] = Triple(value, raw, box)
        }
        root.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        logView = TextView(this).apply {
            textSize = 12f; setTextColor(DIM); typeface = Typeface.MONOSPACE
            setPadding(0, px(8), 0, 0); maxLines = 8
        }
        root.addView(logView)
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(TEXT)
        background = GradientDrawable().apply { setColor(BUTTON); cornerRadius = 12 * resources.displayMetrics.density }
        val p = (12 * resources.displayMetrics.density).toInt()
        setPadding(p * 2, p, p * 2, p)
        setOnClickListener { onClick() }
    }

    private companion object {
        val BG = Color.parseColor("#0B1220")
        val CARD = Color.parseColor("#111A2E")
        val BUTTON = Color.parseColor("#1E3A5F")
        val TEXT = Color.parseColor("#F1F5F9")
        val MUTED = Color.parseColor("#94A3B8")
        val DIM = Color.parseColor("#64748B")
        val ACCENT = Color.parseColor("#38BDF8")
    }
}
