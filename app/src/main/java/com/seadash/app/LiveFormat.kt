package com.seadash.app

import java.util.Locale

/**
 * Turns the raw live line from [com.seadash.core.VehicleReader.live] into labelled, human-readable tiles.
 * The raw value is always kept next to the converted one so wrong scales are easy to spot.
 */
object LiveFormat {

    class Raw(val value: String?, val source: String?, val error: String?)

    class Tile(val key: String, val label: String, val text: String, val raw: String, val ok: Boolean)

    fun parse(line: String): Map<String, Raw> = line.split('\t').mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) return@mapNotNull null
        val key = entry.substring(0, eq)
        val v = entry.substring(eq + 1)
        key to when {
            v == "-" -> Raw(null, null, null)
            v.startsWith("!") -> Raw(null, null, v.substring(1))
            else -> Raw(v.substringBeforeLast('@'), v.substringAfterLast('@', ""), null)
        }
    }.toMap()

    private class Def(val key: String, val label: String, val fmt: (Double, String?) -> String)

    private val GEAR = mapOf(1 to "P", 2 to "R", 3 to "N", 4 to "D", 5 to "M", 6 to "S")
    private val DRIVE_MODE = mapOf(1 to "Эко", 2 to "Спорт", 3 to "Норма", 4 to "Снег", 5 to "Грязь", 6 to "Песок")
    private val GUN = mapOf(1 to "не подключён", 2 to "AC", 3 to "DC", 4 to "AC/DC", 5 to "V2L")
    private val CHARGE = mapOf(
        0 to "не заряжается", 1 to "заряжается", 2 to "заряд окончен", 3 to "разряд", 4 to "остановлен",
        9 to "по расписанию", 11 to "таймаут", 12 to "разряд окончен", 13 to "пауза", 15 to "не заряжается",
    )

    private fun num(v: Double, digits: Int = 0) = String.format(Locale.US, "%.${digits}f", v)
    private fun mapped(m: Map<Int, String>) = { v: Double, _: String? -> m[v.toInt()] ?: "код ${v.toInt()}" }
    private fun door(v: Double, @Suppress("UNUSED_PARAMETER") s: String?) = when (v.toInt()) { 0 -> "закрыта"; 1 -> "ОТКРЫТА"; else -> "код ${v.toInt()}" }
    private fun tyre(v: Double, @Suppress("UNUSED_PARAMETER") s: String?) = "${num(v)} кПа · ${num(v / 100.0, 2)} бар"

    private val DEFS = listOf(
        Def("speed", "Скорость") { v, _ -> "${num(v)} км/ч" },
        Def("gear", "Передача", mapped(GEAR)),
        Def("soc", "Заряд батареи") { v, _ -> "${num(v, 1)} %" },
        Def("range", "Запас хода") { v, _ -> "${num(v)} км" },
        Def("power", "Мощность") { v, _ -> "${num(v, 1)} кВт" },
        Def("drive_mode", "Режим", mapped(DRIVE_MODE)),
        Def("accel", "Педаль газа") { v, _ -> "${num(v)} %" },
        Def("brake", "Педаль тормоза") { v, _ -> "${num(v)} %" },
        Def("bat_t_max", "Т° батареи макс") { v, _ -> "${num(v - 40)} °C" },
        Def("bat_t_min", "Т° батареи мин") { v, _ -> "${num(v - 40)} °C" },
        Def("cell_v_max", "Ячейка макс") { v, _ -> "${num(v / 1000.0, 3)} В" },
        Def("cell_v_min", "Ячейка мин") { v, _ -> "${num(v / 1000.0, 3)} В" },
        Def("hv_v", "Напряжение ВВБ") { v, _ -> "${num(v)} В" },
        Def("hv_a", "Ток ВВБ") { v, _ -> "${num(v, 1)} А" },
        Def("v12", "АКБ 12 В") { v, _ -> "${num(v, 2)} В" },
        Def("gun", "Зарядный кабель", mapped(GUN)),
        Def("chg_state", "Зарядка", mapped(CHARGE)),
        Def("inst_cons", "Расход сейчас") { v, _ -> "${num(v, 1)} кВт·ч/100" },
        Def("trip_cons", "Расход средний") { v, _ -> "${num(v, 1)} кВт·ч/100" },
        Def("out_t", "Т° снаружи") { v, _ -> "${num(v)} °C" },
        Def("in_t", "Т° в салоне") { v, _ -> "${num(v)} °C" },
        Def("odo", "Пробег") { v, src -> if (src == "f") "${num(v / 10.0)} км" else "${num(v)} км" },
        Def("tyre_fl", "Шина ПЛ", ::tyre),
        Def("tyre_fr", "Шина ПП", ::tyre),
        Def("tyre_rl", "Шина ЗЛ", ::tyre),
        Def("tyre_rr", "Шина ЗП", ::tyre),
        Def("door_fl", "Дверь ПЛ", ::door),
        Def("door_fr", "Дверь ПП", ::door),
        Def("door_rl", "Дверь ЗЛ", ::door),
        Def("door_rr", "Дверь ЗП", ::door),
        Def("hood", "Капот", ::door),
        Def("trunk", "Багажник", ::door),
    )

    val KEYS: List<Pair<String, String>> = DEFS.map { it.key to it.label }

    fun tiles(values: Map<String, Raw>): List<Tile> = DEFS.map { d ->
        val r = values[d.key]
        when {
            r == null -> Tile(d.key, d.label, "—", "нет данных", false)
            r.error != null -> Tile(d.key, d.label, "ошибка", r.error.take(80), false)
            r.value == null -> Tile(d.key, d.label, "—", "не поддерживается", false)
            else -> {
                val n = r.value.toDoubleOrNull()
                val src = if (r.source == "f") "FID" else "getter"
                if (n == null) Tile(d.key, d.label, r.value.take(24), "raw ${r.value.take(60)} · $src", true)
                else Tile(d.key, d.label, runCatching { d.fmt(n, r.source) }.getOrElse { r.value }, "raw ${r.value} · $src", true)
            }
        }
    }
}
