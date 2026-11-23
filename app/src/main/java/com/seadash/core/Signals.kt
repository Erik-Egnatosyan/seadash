package com.seadash.core

/**
 * Live signals shown on the dashboard. Each signal tries its sources in order and uses the first one
 * that answers without an exception. FID values differ between firmware builds, so FIDs are
 * referenced by symbol name and resolved against BYDAutoFeatureIds at runtime.
 *
 * Source names and encodings follow the public DiCar research (MIT): [D4] = seen on DiLink 4.0,
 * [D5] = encoding confirmed on DiLink 5. Everything here is read-only.
 */
object Signals {

    sealed interface Src {
        /** Named device method, e.g. BYDAutoSpeedDevice.getCurrentSpeed(). */
        class Getter(val cls: String, val method: String, vararg val args: Int) : Src
        /** BYDAutoDeviceManager.getInt/getDouble(dev, fid). */
        class Fid(val dev: Int, val symbol: String, val isFloat: Boolean = false) : Src
    }

    class Signal(val key: String, val sources: List<Src>)

    const val PKG = "android.hardware.bydauto"
    const val FEATURE_IDS = "$PKG.BYDAutoFeatureIds"
    const val DEVICE_MANAGER = "$PKG.BYDAutoDeviceManager"

    const val AC = "$PKG.ac.BYDAutoAcDevice"
    const val BODYWORK = "$PKG.bodywork.BYDAutoBodyworkDevice"
    const val ENGINE = "$PKG.engine.BYDAutoEngineDevice"
    const val ENERGY = "$PKG.energy.BYDAutoEnergyDevice"
    const val GEARBOX = "$PKG.gearbox.BYDAutoGearboxDevice"
    const val SPEED = "$PKG.speed.BYDAutoSpeedDevice"
    const val STATISTIC = "$PKG.statistic.BYDAutoStatisticDevice"
    const val TYRE = "$PKG.tyre.BYDAutoTyreDevice"

    /** autoservice device ids per BYDAutoFeatureIds nested group (a group may need more than one try). */
    val DEV_BY_GROUP: Map<String, List<Int>> = mapOf(
        "ac" to listOf(1000),
        "bodywork" to listOf(1001),
        "light" to listOf(1004),
        "power" to listOf(1005),
        "energy" to listOf(1006),
        "instrument" to listOf(1007),
        "pm2p5" to listOf(1008),
        "charging" to listOf(1009),
        "gearbox" to listOf(1011),
        "engine" to listOf(1012),
        "speed" to listOf(1013),
        "statistic" to listOf(1014),
        "tyre" to listOf(1016),
        "motor" to listOf(1020),
        "setting" to listOf(1023),
        "radar" to listOf(1025),
        "ota" to listOf(1032, 1001), // 12V voltage lives under Ota but answers on device 1001 [D5]
        "doorlock" to listOf(1041),
        "safetybelt" to listOf(1042),
        "sensor" to listOf(1043),
    )

    /** Device classes always included in the report, even when the framework jars cannot be scanned. */
    val KNOWN_CLASSES = listOf(
        AC, BODYWORK, ENGINE, ENERGY, GEARBOX, SPEED, STATISTIC, TYRE,
        "$PKG.light.BYDAutoLightDevice",
        "$PKG.power.BYDAutoPowerDevice",
        "$PKG.instrument.BYDAutoInstrumentDevice",
        "$PKG.charging.BYDAutoChargingDevice",
        "$PKG.motor.BYDAutoMotorDevice",
        "$PKG.setting.BYDAutoSettingDevice",
        "$PKG.radar.BYDAutoRadarDevice",
        "$PKG.ota.BYDAutoOtaDevice",
        "$PKG.doorlock.BYDAutoDoorLockDevice",
        "$PKG.safetybelt.BYDAutoSafetyBeltDevice",
        "$PKG.sensor.BYDAutoSensorDevice",
        "$PKG.pm2p5.BYDAutoPM2p5Device",
        FEATURE_IDS,
        DEVICE_MANAGER,
        "$PKG.BYDAutoConstants",
        "$PKG.AbsBYDAutoDevice",
    )

    private fun g(cls: String, method: String, vararg args: Int) = Src.Getter(cls, method, *args)
    private fun f(dev: Int, symbol: String, isFloat: Boolean = false) = Src.Fid(dev, symbol, isFloat)
    private fun s(key: String, vararg src: Src) = Signal(key, src.toList())

    val LIVE: List<Signal> = listOf(
        s("speed", g(SPEED, "getCurrentSpeed"), f(1013, "Speed.SPEED_AUTO_SPEED", true)),
        s("gear", g(GEARBOX, "getGearboxAutoModeType"), f(1011, "Gearbox.GEARBOX_AUTO_MODE_TYPE")),
        s("soc", g(STATISTIC, "getElecPercentageValue"), f(1014, "Statistic.STATISTIC_ELEC_PERCENTAGE", true)),
        s("range", g(STATISTIC, "getElecDrivingRangeValue")),
        s("power", f(1012, "Engine.ENGINE_POWER"), g(ENGINE, "getEnginePower")),
        s("drive_mode", g(ENERGY, "getOperationMode"), f(1006, "Energy.ENERGY_OPERATION_MODE")),
        s("accel", g(SPEED, "getAccelerateDeepness"), f(1013, "Speed.SPEED_ACCELERATOR_S")),
        s("brake", g(SPEED, "getBrakeDeepness"), f(1013, "Speed.SPEED_BRAKE_S")),
        s("bat_t_max", f(1014, "Statistic.STATISTIC_HIGHEST_BATTERY_TEMP")),
        s("bat_t_min", f(1014, "Statistic.STATISTIC_LOWEST_BATTERY_TEMP")),
        s("cell_v_max", f(1014, "Statistic.STATISTIC_HIGHEST_BATTERY_VOLTAGE")),
        s("cell_v_min", f(1014, "Statistic.STATISTIC_LOWEST_BATTERY_VOLTAGE")),
        s("hv_v", f(1009, "Charging.CHARGING_CHARGE_BATTERY_VOLT")),
        s("hv_a", f(1009, "Charging.CHARGING_CHARGE_CURRENT", true)),
        s("v12", f(1001, "Ota.OTA_BATTERY_POWER_VOLTAGE", true)),
        s("gun", f(1009, "Charging.CHARGING_GUN_CONNECT_STATE")),
        s("chg_state", f(1009, "Charging.CHARGING_BATTERRY_DEVICE_STATE")),
        s("inst_cons", g(STATISTIC, "getInstantElecConValue")),
        s("trip_cons", g(STATISTIC, "getLastElecConPHMValue")),
        s("out_t", g(AC, "getTemprature", 4), f(1000, "Ac.AC_TEMP_OUT")),
        s("in_t", f(1000, "Ac.AC_TEMP_INSIDE")),
        s("odo", g(STATISTIC, "getTotalMileageValue"), f(1014, "Statistic.STATISTIC_TOTAL_MILEAGE")),
        s("tyre_fl", g(TYRE, "getTyrePressureValue", 1), f(1016, "Tyre.TYRE_PRESSURE_VALUE_LEFT_FRONT")),
        s("tyre_fr", g(TYRE, "getTyrePressureValue", 2), f(1016, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_FRONT")),
        s("tyre_rl", g(TYRE, "getTyrePressureValue", 3), f(1016, "Tyre.TYRE_PRESSURE_VALUE_LEFT_REAR")),
        s("tyre_rr", g(TYRE, "getTyrePressureValue", 4), f(1016, "Tyre.TYRE_PRESSURE_VALUE_RIGHT_REAR")),
        s("door_fl", g(BODYWORK, "getDoorState", 1)),
        s("door_fr", g(BODYWORK, "getDoorState", 2)),
        s("door_rl", g(BODYWORK, "getDoorState", 3)),
        s("door_rr", g(BODYWORK, "getDoorState", 4)),
        s("hood", g(BODYWORK, "getDoorState", 5)),
        s("trunk", g(BODYWORK, "getDoorState", 6)),
    )
}
