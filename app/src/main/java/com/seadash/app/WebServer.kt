package com.seadash.app

import android.util.Log
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny read-only web page so a phone on the same Wi-Fi can see live values and download reports:
 *   http://<head-unit-ip>:<port>/
 * Routes: "/" page, "/live.txt" raw live line, "/r/<file>" view report, "/d/<file>" download report.
 */
class WebServer(
    private val reports: Reports,
    private val snapshot: () -> Snapshot,
) {

    class Snapshot(val status: String, val rawLive: String, val tiles: List<LiveFormat.Tile>)

    @Volatile var port: Int = 0
        private set
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        for (p in PORTS) {
            try {
                server = ServerSocket(p); port = p; break
            } catch (e: Exception) {
                Log.w(TAG, "port $p busy")
            }
        }
        val s = server ?: return
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (e: Exception) { break }
                Thread({ runCatching { handle(client) }.onFailure { Log.w(TAG, "request failed", it) } }, "web-client").apply { isDaemon = true }.start()
            }
        }, "web-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { server?.close() }; server = null; port = 0
    }

    private fun handle(socket: Socket): Unit = socket.use { sock ->
        sock.soTimeout = 10_000
        val requestLine = runCatching { sock.getInputStream().bufferedReader().readLine() }.getOrNull() ?: return
        val path = URLDecoder.decode(requestLine.split(' ').getOrNull(1) ?: "/", "UTF-8").substringBefore('?')
        val out = BufferedOutputStream(sock.getOutputStream())
        when {
            path == "/" -> send(out, 200, "text/html; charset=utf-8", page().toByteArray())
            path == "/live.txt" -> send(out, 200, "text/plain; charset=utf-8", snapshot().rawLive.replace('\t', '\n').toByteArray())
            path.startsWith("/r/") || path.startsWith("/d/") -> {
                val file = reports.byName(path.substring(3))
                if (file == null) send(out, 404, "text/plain; charset=utf-8", "not found".toByteArray())
                else send(out, 200, "text/plain; charset=utf-8", file.readBytes(),
                    if (path.startsWith("/d/")) "attachment; filename=\"${file.name}\"" else null)
            }
            else -> send(out, 404, "text/plain; charset=utf-8", "not found".toByteArray())
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, disposition: String? = null) {
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            if (disposition != null) append("Content-Disposition: $disposition\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray()); out.write(body); out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(): String {
        val snap = snapshot()
        val rows = snap.tiles.joinToString("") { t ->
            "<tr class='${if (t.ok) "" else "off"}'><td>${esc(t.label)}</td><td><b>${esc(t.text)}</b></td><td class='raw'>${esc(t.raw)}</td></tr>"
        }
        val files = reports.list().joinToString("") { f ->
            "<li><a href='/r/${f.name}'>${f.name}</a> · <a href='/d/${f.name}'>скачать</a> · ${f.length() / 1024} КБ</li>"
        }.ifEmpty { "<li>Отчётов пока нет. Нажмите «Полный отчёт» на экране машины.</li>" }
        return """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="3">
<title>SeaDash</title><style>
body{font-family:system-ui,sans-serif;background:#0b1220;color:#e2e8f0;margin:0;padding:16px}
h1{font-size:20px;margin:0 0 8px}h2{font-size:16px;margin:20px 0 8px}
.st{color:#94a3b8;font-size:13px;margin-bottom:12px}a{color:#38bdf8}
table{border-collapse:collapse;width:100%;font-size:14px}td{padding:6px 8px;border-bottom:1px solid #1e293b}
.raw{color:#64748b;font-size:12px}.off td{color:#475569}li{margin:6px 0;font-size:14px}
</style></head><body><h1>SeaDash</h1><div class="st">${esc(snap.status)}</div>
<h2>Отчёты</h2><ul>$files</ul><h2>Данные сейчас</h2><table>$rows</table></body></html>"""
    }

    companion object {
        private const val TAG = "WebServer"
        private val PORTS = listOf(8080, 8081, 8088, 8888)

        /** IPv4 addresses of the head unit's network interfaces (Wi-Fi client or hotspot). */
        fun localIps(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${it.hostAddress} (${nif.name})" } }
        }.getOrDefault(emptyList())
    }
}
