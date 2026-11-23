package com.seadash.helper

import android.content.Context
import android.os.Looper
import com.seadash.core.VehicleReader

/**
 * Helper process entry point, started by the app over the head unit's own adbd as the shell user:
 *   CLASSPATH=<apk> exec app_process /system/bin com.seadash.helper.HelperMain
 *
 * The app's own uid is rejected by autoservice (signature permissions); the shell uid is accepted.
 *
 * Line protocol on stdin/stdout (logs never go to stdout):
 *   PING -> "OK"
 *   LIVE -> "L <tab-separated live values>"
 *   DUMP -> many "| <report line>" lines, then "END"
 * Unknown output lines (e.g. stray framework prints) are ignored by the client because of the prefixes.
 */
object HelperMain {

    @JvmStatic
    fun main(args: Array<String>) {
        val out = System.out
        try {
            @Suppress("DEPRECATION")
            runCatching { if (Looper.myLooper() == null) Looper.prepareMainLooper() }

            val reader = VehicleReader(systemContext())
            out.println("READY uid=${android.os.Process.myUid()}")
            out.flush()

            val input = System.`in`.bufferedReader()
            while (true) {
                val line = input.readLine()?.trim() ?: break
                when (line) {
                    "" -> continue
                    "PING" -> out.println("OK")
                    "LIVE" -> out.println("L " + runCatching { reader.live() }.getOrElse { "!" + VehicleReader.clean(it.toString()) })
                    "DUMP" -> {
                        runCatching {
                            reader.dump { l -> out.println("| " + l); out.flush() }
                        }.onFailure { out.println("| !! dump failed: " + VehicleReader.clean(it.toString())) }
                        out.println("END")
                    }
                    else -> out.println("E unknown command")
                }
                out.flush()
            }
        } catch (t: Throwable) {
            out.println("FATAL ${t.javaClass.simpleName}: ${t.message}")
            out.flush()
            t.printStackTrace()
        }
    }

    /** System context via reflection (no hidden-API enforcement under app_process). */
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(thread) as Context
    }
}
