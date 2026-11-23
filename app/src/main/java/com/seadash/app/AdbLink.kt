package com.seadash.app

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.AdbShellPacket
import dadb.AdbShellStream
import dadb.Dadb
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Connection to the head unit's own adbd (127.0.0.1:5555) and the shell-identity helper process.
 * Connection approach adapted from DiCar (MIT, see THIRD_PARTY_DICAR_LICENSE.txt).
 *
 * The first connection makes the head unit show "Allow USB debugging?"; the user must tap Allow
 * and tick "Always allow". Not thread-safe: call from one background thread only.
 */
class AdbLink(context: Context) {

    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val app = context.applicationContext
    private val keyDir = File(app.filesDir, "adb")
    private var dadb: Dadb? = null
    private var shell: AdbShellStream? = null
    private val pending = LineBuffer()

    var helperInfo: String = ""
        private set

    val connected: Boolean get() = shell != null

    fun isPortOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(HOST, PORT), 400); true }
    } catch (e: Exception) {
        false
    }

    fun connect() {
        if (shell != null) return
        try {
            keyDir.mkdirs()
            val priv = File(keyDir, "adbkey")
            val pub = File(keyDir, "adbkey.pub")
            if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
            val conn = dadb ?: Dadb.create(
                HOST, PORT, AdbKeyPair.read(priv, pub),
                connectTimeout = 10_000, socketTimeout = 30_000, keepAlive = true,
            ).also { dadb = it }

            val apk = app.applicationInfo.sourceDir
            val s = conn.openShell("CLASSPATH=$apk exec app_process /system/bin --nice-name=seadash_helper com.seadash.helper.HelperMain")
            shell = s
            val ready = readUntil { it.startsWith("READY") || it.startsWith("FATAL") }
            if (!ready.startsWith("READY")) throw Unavailable("помощник не запустился: $ready")
            helperInfo = ready
            Log.i(TAG, "helper $ready")
        } catch (e: Unavailable) {
            reset(); throw e
        } catch (e: Exception) {
            reset(); throw Unavailable("ADB: ${e.javaClass.simpleName}: ${e.message.orEmpty()}", e)
        }
    }

    fun live(): String = exchange {
        write("LIVE")
        readUntil { it.startsWith("L ") }.removePrefix("L ")
    }

    fun dump(onLine: (String) -> Unit) = exchange {
        write("DUMP")
        while (true) {
            val line = readLine() ?: throw Unavailable("помощник завершился во время отчёта")
            if (line == "END") break
            if (line.startsWith("| ")) onLine(line.substring(2))
        }
    }

    fun reset() {
        runCatching { shell?.close() }; shell = null
        runCatching { dadb?.close() }; dadb = null
        pending.clear()
    }

    private fun <T> exchange(block: () -> T): T {
        if (shell == null) connect()
        return try {
            block()
        } catch (e: Unavailable) {
            reset(); throw e
        } catch (e: Exception) {
            reset(); throw Unavailable("связь с помощником потеряна: ${e.javaClass.simpleName}: ${e.message.orEmpty()}", e)
        }
    }

    private fun write(line: String) {
        (shell ?: throw Unavailable("нет соединения")).write(line + "\n")
    }

    private fun readUntil(match: (String) -> Boolean): String {
        while (true) {
            val line = readLine() ?: throw Unavailable("помощник завершился")
            if (match(line)) return line
            Log.w(TAG, "skipped helper line: $line")
        }
    }

    /** Next stdout line, or null when the helper exited. stderr goes to logcat only. */
    private fun readLine(): String? {
        val s = shell ?: return null
        while (true) {
            pending.next()?.let { return it }
            when (val p = s.read()) {
                is AdbShellPacket.StdOut -> pending.append(p.payload)
                is AdbShellPacket.StdError -> Log.w(TAG, "helper stderr: ${String(p.payload).trim()}")
                is AdbShellPacket.Exit -> return pending.next()
            }
        }
    }

    /** Joins shell v2 stdout frames into lines (frame and line boundaries differ). */
    private class LineBuffer {
        private val buf = ByteArrayOutputStream(1024)
        fun append(b: ByteArray) = buf.write(b, 0, b.size)
        fun clear() = buf.reset()
        fun next(): String? {
            val bytes = buf.toByteArray()
            val nl = bytes.indexOf('\n'.code.toByte())
            if (nl < 0) return null
            val line = String(bytes, 0, nl, Charsets.UTF_8).trimEnd('\r')
            buf.reset()
            if (nl + 1 < bytes.size) buf.write(bytes, nl + 1, bytes.size - nl - 1)
            return line
        }
    }

    private companion object {
        const val TAG = "AdbLink"
        const val HOST = "127.0.0.1"
        const val PORT = 5555
    }
}
