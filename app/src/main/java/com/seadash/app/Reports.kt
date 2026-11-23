package com.seadash.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores reports in app storage and copies each one to Download/SeaDash for USB / file-manager access. */
class Reports(context: Context) {

    private val app = context.applicationContext
    val dir: File = File(app.filesDir, "reports").apply { mkdirs() }

    fun newFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "seadash_report_$stamp.txt")
    }

    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
        ?.sortedByDescending { it.lastModified() }.orEmpty()

    fun byName(name: String): File? {
        if (!SAFE_NAME.matches(name)) return null
        return File(dir, name).takeIf { it.isFile }
    }

    /** Returns a human-readable location of the public copy, or null if copying is not possible. */
    fun publish(file: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SeaDash")
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            "Download/SeaDash/${file.name}"
        } catch (t: Throwable) {
            null
        }
    }

    private companion object {
        val SAFE_NAME = Regex("[A-Za-z0-9_.-]+\\.txt")
    }
}
