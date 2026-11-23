package com.seadash.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Reads vehicle data through the head unit's android.hardware.bydauto framework by reflection.
 *
 * The same code runs in two places:
 * - in the helper process started over local ADB as the shell user, where autoservice accepts the calls;
 * - inside the app itself as a fallback, which works only on firmware without signature checks.
 *
 * Read-only by design: it never calls setters and skips every FID whose name ends with _SET.
 */
class VehicleReader(base: Context) {

    private val ctx: Context = PermissiveContext(base)

    private val manager: Any? by lazy {
        runCatching {
            Class.forName(Signals.DEVICE_MANAGER).getMethod("getInstance", Context::class.java).invoke(null, ctx)
        }.getOrNull()
    }

    private val devices = HashMap<String, Any?>()
    private val methods = HashMap<String, Method?>()
    private val fids = HashMap<String, Int?>()

    // ------------------------------------------------------------------
    // Live values
    // ------------------------------------------------------------------

    /**
     * One line with every live signal: entries separated by TAB, each "key=value@g" / "key=value@f"
     * (named getter / FID), "key=-" when no source exists, or "key=!error" when every source failed.
     */
    fun live(): String = Signals.LIVE.joinToString("\t") { sig -> "${sig.key}=${readSignal(sig)}" }

    private fun readSignal(sig: Signals.Signal): String {
        var lastError: String? = null
        for (src in sig.sources) {
            try {
                when (src) {
                    is Signals.Src.Getter -> {
                        val v = callGetter(src.cls, src.method, src.args) ?: continue
                        return render(v) + "@g"
                    }
                    is Signals.Src.Fid -> {
                        val fid = resolveFid(src.symbol) ?: continue
                        val v: Any = if (src.isFloat) managerGet("getDouble", src.dev, fid) else managerGet("getInt", src.dev, fid)
                        return render(v) + "@f"
                    }
                }
            } catch (t: Throwable) {
                lastError = describe(t)
            }
        }
        return if (lastError != null) "!" + clean(lastError) else "-"
    }

    // ------------------------------------------------------------------
    // Full report
    // ------------------------------------------------------------------

    /** Writes a full diagnostic report line by line. Long-running: tens of seconds on a real head unit. */
    fun dump(out: (String) -> Unit) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        out("=== SeaDash vehicle report ===")
        out("time        : $time")
        out("process uid : ${android.os.Process.myUid()}")
        out("model       : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, board ${Build.BOARD})")
        out("android     : ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        out("display     : ${Build.DISPLAY}")
        out("incremental : ${Build.VERSION.INCREMENTAL}")
        out("fingerprint : ${Build.FINGERPRINT}")
        out("manager     : ${if (manager != null) "BYDAutoDeviceManager OK" else "BYDAutoDeviceManager NOT available"}")
        out("")

        section(out, "LIVE SIGNALS") {
            Signals.LIVE.forEach { sig -> out("  ${sig.key} = ${readSignal(sig)}") }
        }
        section(out, "ADB / DEBUG SETTINGS") {
            listOf(
                "settings get global adb_enabled",
                "settings get global adb_wifi_enabled",
                "settings get global development_settings_enabled",
                "getprop service.adb.tcp.port",
                "getprop persist.adb.tcp.port",
            ).forEach { cmd -> out("  $cmd -> ${shell(cmd).trim().ifEmpty { "(empty)" }}") }
        }
        section(out, "SYSTEM PROPERTIES (identifiers redacted)") {
            shell("getprop").lineSequence().filter { it.isNotBlank() }.forEach { out("  " + redact(it)) }
        }
        section(out, "BYD / CAR PACKAGES") {
            shell("pm list packages").lineSequence()
                .filter { CAR_HINT.containsMatchIn(it) }.sorted().forEach { out("  $it") }
        }
        section(out, "BYD / CAR SYSTEM SERVICES") {
            shell("service list").lineSequence()
                .filter { CAR_HINT.containsMatchIn(it) }.forEach { out("  ${it.trim()}") }
        }

        val scanned = scanFrameworkJars(out)
        val classNames = (scanned + Signals.KNOWN_CLASSES)
            .filter { it.startsWith(Signals.PKG + ".") && '$' !in it }
            .toSortedSet()

        section(out, "FEATURE IDS (FID values read through BYDAutoDeviceManager)") { dumpFeatureIds(out) }

        section(out, "BYDAUTO CLASSES (${classNames.size}, ${scanned.size} found by scan)") {
            classNames.forEach { name ->
                try {
                    dumpClass(name, out)
                } catch (t: Throwable) {
                    out("## $name  !! ${describe(t)}")
                }
            }
        }
        out("=== end of report ===")
    }

    private inline fun section(out: (String) -> Unit, title: String, block: () -> Unit) {
        out("")
        out("##### $title")
        try {
            block()
        } catch (t: Throwable) {
            out("  !! section failed: ${describe(t)}")
        }
    }

    private fun dumpFeatureIds(out: (String) -> Unit) {
        val root = try {
            Class.forName(Signals.FEATURE_IDS)
        } catch (t: Throwable) {
            out("  BYDAutoFeatureIds not available: ${describe(t)}"); return
        }
        if (manager == null) out("  (manager unavailable: names and FID numbers only, no values)")
        val groups = root.declaredClasses.sortedBy { it.simpleName }
        out("  groups: ${groups.joinToString { it.simpleName }}")
        for (group in groups) {
            val devs = Signals.DEV_BY_GROUP[group.simpleName.lowercase(Locale.US)]
            val fields = group.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && (it.type == Int::class.javaPrimitiveType || it.type == Integer::class.java) }
                .sortedBy { it.name }
            out("")
            out("  [${group.simpleName}] dev=${devs?.joinToString("/") ?: "unknown"} fields=${fields.size}")
            for (field in fields) {
                val fid = runCatching { (field.get(null) as Number).toInt() }.getOrNull() ?: continue
                val hex = "0x" + Integer.toHexString(fid).uppercase(Locale.US)
                if (field.name.endsWith("_SET")) {
                    out("    ${field.name} = $hex  (write id, not read)")
                    continue
                }
                if (devs == null || manager == null) {
                    out("    ${field.name} = $hex")
                    continue
                }
                val values = devs.joinToString("  ") { dev ->
                    val i = runCatching { render(managerGet("getInt", dev, fid)) }.getOrElse { "!" + describe(it) }
                    val d = runCatching { render(managerGet("getDouble", dev, fid)) }.getOrElse { "!" + describe(it) }
                    (if (devs.size > 1) "dev$dev " else "") + "int=$i dbl=$d"
                }
                out("    ${field.name} = $hex  ${clean(values)}")
            }
        }
    }

    private fun dumpClass(name: String, out: (String) -> Unit) {
        val cls = try {
            Class.forName(name)
        } catch (t: Throwable) {
            out("## $name  (class missing: ${t.javaClass.simpleName})"); return
        }
        out("")
        out("## $name")
        cls.superclass?.takeIf { it != Any::class.java }?.let { out("  extends ${it.name}") }

        val constants = cls.declaredFields.filter {
            Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && (it.type.isPrimitive || it.type == String::class.java)
        }
        if (constants.isNotEmpty()) {
            out("  constants:")
            constants.sortedBy { it.name }.forEach { f -> out("    ${f.name} = ${runCatching { f.get(null) }.getOrElse { "?" }}") }
        }

        val publicMethods = cls.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.sortedBy { it.name }
        if (publicMethods.isNotEmpty()) {
            out("  methods:")
            publicMethods.forEach { m ->
                val static = if (Modifier.isStatic(m.modifiers)) "static " else ""
                out("    $static${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})")
            }
        }

        val hasGetInstance = cls.methods.any { it.name == "getInstance" && Modifier.isStatic(it.modifiers) }
        if (!hasGetInstance) return
        val device = device(name)
        if (device == null) {
            out("  values: (getInstance failed)"); return
        }
        out("  values:")
        val getters = cls.methods.filter { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType != Void.TYPE &&
                GETTER_PREFIXES.any { m.name.startsWith(it) } && m.name !in SKIP_GETTERS &&
                (m.parameterTypes.isEmpty() || (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType))
        }.sortedBy { it.name }
        for (m in getters) {
            if (m.parameterTypes.isEmpty()) {
                out("    ${m.name}() = ${invokeSafe(m, device)}")
            } else {
                // Area/zone style getters (doors 1..6, tyres 1..4, temperature zones ...).
                val row = (1..6).joinToString("  ") { a -> "[$a]=${invokeSafe(m, device, a)}" }
                out("    ${m.name}(int) $row")
            }
        }
    }

    private fun invokeSafe(m: Method, target: Any, vararg args: Any): String =
        try {
            clean(render(m.invoke(target, *args)))
        } catch (t: Throwable) {
            "!" + clean(describe(t))
        }

    // ------------------------------------------------------------------
    // Framework jar scan: find android.hardware.bydauto classes without the deprecated DexFile API.
    // ------------------------------------------------------------------

    private fun scanFrameworkJars(out: (String) -> Unit): Set<String> {
        val jars = linkedSetOf<String>()
        System.getenv("BOOTCLASSPATH")?.split(':')?.filter { it.isNotBlank() }?.let(jars::addAll)
        listOf("/system/framework", "/system_ext/framework", "/vendor/framework").forEach { dir ->
            File(dir).listFiles { f -> f.name.endsWith(".jar") }?.forEach { jars += it.absolutePath }
        }
        val found = sortedSetOf<String>()
        out("")
        out("##### FRAMEWORK SCAN (${jars.size} jars)")
        var dexTotal = 0
        for (path in jars) {
            try {
                val file = File(path)
                if (!file.canRead()) continue
                var dexCount = 0
                val hits = HashSet<String>()
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().filter { it.name.matches(DEX_ENTRY) }.forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        dexCount++
                        DexTypeScanner.scan(bytes, DESCRIPTOR_PREFIX).matches
                            .mapTo(hits) { it.removePrefix("L").removeSuffix(";").replace('/', '.') }
                    }
                }
                dexTotal += dexCount
                if (dexCount > 0) out("  $path dex=$dexCount bydauto=${hits.size}")
                found += hits
            } catch (t: Throwable) {
                out("  $path !! ${t.javaClass.simpleName}")
            }
        }
        if (dexTotal == 0) out("  (no dex in framework jars: boot image is precompiled, using the known class list)")
        return found
    }

    // ------------------------------------------------------------------
    // Reflection plumbing
    // ------------------------------------------------------------------

    private fun managerGet(name: String, dev: Int, fid: Int): Any {
        val mgr = manager ?: throw IllegalStateException("BYDAutoDeviceManager unavailable")
        val m = findMethod(mgr.javaClass, name, 2) ?: throw NoSuchMethodException("manager.$name")
        return m.invoke(mgr, *box(m, intArrayOf(dev, fid))) ?: throw IllegalStateException("null")
    }

    private fun callGetter(cls: String, method: String, args: IntArray): Any? {
        val dev = device(cls) ?: return null
        val m = findMethod(dev.javaClass, method, args.size) ?: return null
        return m.invoke(dev, *box(m, args))
    }

    private fun device(cls: String): Any? = devices.getOrPut(cls) {
        runCatching { Class.forName(cls).getMethod("getInstance", Context::class.java).invoke(null, ctx) }.getOrNull()
    }

    private fun resolveFid(symbol: String): Int? = fids.getOrPut(symbol) {
        val group = symbol.substringBefore('.', "")
        val field = symbol.substringAfter('.')
        staticInt("${Signals.FEATURE_IDS}\$$group", field) ?: staticInt(Signals.FEATURE_IDS, field)
    }

    private fun staticInt(cls: String, field: String): Int? =
        runCatching { (Class.forName(cls).getField(field).get(null) as? Number)?.toInt() }.getOrNull()

    private fun findMethod(cls: Class<*>, name: String, argc: Int): Method? {
        val key = "${cls.name}#$name/$argc"
        if (methods.containsKey(key)) return methods[key]
        val found = cls.methods
            .filter { it.name == name && it.parameterTypes.size == argc && it.parameterTypes.all { p -> p.isPrimitive || p == Integer::class.java } }
            .sortedByDescending { it.parameterTypes.count { p -> p == Int::class.javaPrimitiveType } }
            .firstOrNull()
        methods[key] = found
        return found
    }

    private fun box(m: Method, args: IntArray): Array<Any> = Array(args.size) { i ->
        when (m.parameterTypes[i]) {
            Long::class.javaPrimitiveType -> args[i].toLong()
            Double::class.javaPrimitiveType -> args[i].toDouble()
            Float::class.javaPrimitiveType -> args[i].toFloat()
            Boolean::class.javaPrimitiveType -> args[i] != 0
            Short::class.javaPrimitiveType -> args[i].toShort()
            Byte::class.javaPrimitiveType -> args[i].toByte()
            else -> args[i]
        }
    }

    private fun describe(t: Throwable): String {
        val root = if (t is InvocationTargetException) t.targetException ?: t else t
        return "${root.javaClass.simpleName}: ${root.message ?: ""}".trim()
    }

    /** In-process permission pre-checks for BYDAUTO_* pass; the real check happens in autoservice. */
    private class PermissiveContext(base: Context) : ContextWrapper(base) {
        override fun checkCallingOrSelfPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfPermission(permission)

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission, pid, uid)

        override fun checkSelfPermission(permission: String): Int =
            if (isByd(permission)) PackageManager.PERMISSION_GRANTED else super.checkSelfPermission(permission)

        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
            if (!isByd(permission)) super.enforceCallingOrSelfPermission(permission, message)
        }

        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
            if (!isByd(permission)) super.enforcePermission(permission, pid, uid, message)
        }

        private fun isByd(p: String?) = p?.startsWith("android.permission.BYDAUTO_") == true
    }

    companion object {
        private const val DESCRIPTOR_PREFIX = "Landroid/hardware/bydauto/"
        private val DEX_ENTRY = Regex("classes\\d*\\.dex")
        private val GETTER_PREFIXES = listOf("get", "is", "has")
        private val SKIP_GETTERS = setOf("getClass", "getInstance", "hashCode", "toString")
        private val CAR_HINT = Regex("byd|bydauto|autoservice|\\.ts\\.car|dilink|\\.car\\.|vehicle", RegexOption.IGNORE_CASE)
        private val SENSITIVE = Regex("serial|vin|imei|meid|iccid|imsi|mac|bt\\.addr|wifi\\.addr|phone|msisdn|account|uuid|android_id|cpuid", RegexOption.IGNORE_CASE)

        fun render(value: Any?): String = when (value) {
            null -> "null"
            is IntArray -> value.contentToString()
            is DoubleArray -> value.contentToString()
            is FloatArray -> value.contentToString()
            is LongArray -> value.contentToString()
            is ShortArray -> value.contentToString()
            is BooleanArray -> value.contentToString()
            is ByteArray -> value.contentToString()
            is Array<*> -> value.contentDeepToString()
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }.let { if (it.length > 300) it.take(300) + "…" else it }

        /** Keeps the line protocol intact: no tabs or newlines inside a value. */
        fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        /** "[ro.serialno]: [ABC123]" -> "[ro.serialno]: [<redacted>]" for identifier-like keys. */
        fun redact(line: String): String {
            val key = line.substringBefore("]:", "")
            return if (key.isNotEmpty() && SENSITIVE.containsMatchIn(key)) "$key]: [<redacted>]" else line
        }

        /** Runs a shell command and returns stdout (empty on failure). Waits at most 10 s. */
        fun shell(cmd: String): String = try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val text = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().use { text.append(it.readText()) } } }
            reader.start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroy()
            reader.join(2000)
            text.toString()
        } catch (t: Throwable) {
            "(failed: ${t.javaClass.simpleName})"
        }
    }
}
