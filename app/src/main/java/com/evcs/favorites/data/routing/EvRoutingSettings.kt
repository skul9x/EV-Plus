package com.evcs.favorites.data.routing

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Configuration and vehicle profile specifications for EV smart routing.
 *
 * @property vehicleSafeRangeKm Usable full safe range of the vehicle in kilometers (100 - 500 km, default 200).
 * @property startBatteryPercent State of Charge (%) at the beginning of the trip (10 - 100%, default 100%).
 * @property arrivalBufferSocPercent Safety reserve buffer SoC (%) upon reaching intermediate or final destinations (5 - 25%, default 10%).
 * @property targetChargingSocPercent Maximum recommended SoC (%) for DC fast charging sessions (70 - 95%, default 85%).
 * @property safetyDurationBufferEnabled Whether traffic and charging delay safety padding is applied to trip durations.
 * @property safetyDurationBufferRatio Ratio added to duration when safety buffer is enabled (default 0.25f = +25%).
 * @property minChargerPowerKw Minimum preferred charger power in kW (20.0 - 250.0 kW, default 60.0).
 */
@Serializable
data class EvRoutingSettings(
    val vehicleSafeRangeKm: Int = DEFAULT_VEHICLE_SAFE_RANGE_KM,
    val startBatteryPercent: Int = DEFAULT_START_BATTERY_PERCENT,
    val arrivalBufferSocPercent: Int = DEFAULT_ARRIVAL_BUFFER_SOC_PERCENT,
    val targetChargingSocPercent: Int = DEFAULT_TARGET_CHARGING_SOC_PERCENT,
    val safetyDurationBufferEnabled: Boolean = DEFAULT_SAFETY_DURATION_BUFFER_ENABLED,
    val safetyDurationBufferRatio: Float = DEFAULT_SAFETY_DURATION_BUFFER_RATIO,
    val minChargerPowerKw: Double = DEFAULT_MIN_CHARGER_POWER_KW
) {

    /**
     * Calculates the gross usable range in kilometers for a given SoC percentage.
     * Formula: vehicleSafeRangeKm * (socPercent / 100.0)
     */
    fun calculateUsableRangeKm(socPercent: Int = startBatteryPercent): Double {
        return vehicleSafeRangeKm * (socPercent / 100.0)
    }

    /**
     * Calculates the effective usable range in kilometers before dipping into the arrival reserve buffer.
     * Formula: vehicleSafeRangeKm * ((socPercent - arrivalBufferSocPercent).coerceAtLeast(0) / 100.0)
     */
    fun calculateEffectiveRangeBeforeReserveKm(socPercent: Int = startBatteryPercent): Double {
        return vehicleSafeRangeKm * ((socPercent - arrivalBufferSocPercent).coerceAtLeast(0) / 100.0)
    }

    /**
     * Applies safety duration padding to estimated driving minutes if enabled.
     * Formula: if (safetyDurationBufferEnabled) (rawMinutes * (1f + safetyDurationBufferRatio)).roundToInt() else rawMinutes
     */
    fun applyDurationBuffer(rawMinutes: Int): Int {
        return if (safetyDurationBufferEnabled) {
            (rawMinutes * (1f + safetyDurationBufferRatio)).roundToInt()
        } else {
            rawMinutes
        }
    }

    /**
     * Returns a sanitized copy of this configuration clamped to valid boundary limits.
     */
    fun sanitized(): EvRoutingSettings {
        return copy(
            vehicleSafeRangeKm = vehicleSafeRangeKm.coerceIn(MIN_VEHICLE_SAFE_RANGE_KM, MAX_VEHICLE_SAFE_RANGE_KM),
            startBatteryPercent = startBatteryPercent.coerceIn(MIN_START_BATTERY_PERCENT, MAX_START_BATTERY_PERCENT),
            arrivalBufferSocPercent = arrivalBufferSocPercent.coerceIn(MIN_ARRIVAL_BUFFER_SOC_PERCENT, MAX_ARRIVAL_BUFFER_SOC_PERCENT),
            targetChargingSocPercent = targetChargingSocPercent.coerceIn(MIN_TARGET_CHARGING_SOC_PERCENT, MAX_TARGET_CHARGING_SOC_PERCENT),
            safetyDurationBufferRatio = safetyDurationBufferRatio.coerceIn(MIN_SAFETY_DURATION_BUFFER_RATIO, MAX_SAFETY_DURATION_BUFFER_RATIO),
            minChargerPowerKw = minChargerPowerKw.coerceIn(MIN_CHARGER_POWER_KW, MAX_CHARGER_POWER_KW)
        )
    }

    /**
     * Alias for [sanitized].
     */
    fun sanitize(): EvRoutingSettings = sanitized()

    companion object {
        const val MIN_VEHICLE_SAFE_RANGE_KM = 100
        const val MAX_VEHICLE_SAFE_RANGE_KM = 500
        const val DEFAULT_VEHICLE_SAFE_RANGE_KM = 200

        const val MIN_START_BATTERY_PERCENT = 10
        const val MAX_START_BATTERY_PERCENT = 100
        const val DEFAULT_START_BATTERY_PERCENT = 100

        const val MIN_ARRIVAL_BUFFER_SOC_PERCENT = 5
        const val MAX_ARRIVAL_BUFFER_SOC_PERCENT = 25
        const val DEFAULT_ARRIVAL_BUFFER_SOC_PERCENT = 10

        const val MIN_TARGET_CHARGING_SOC_PERCENT = 70
        const val MAX_TARGET_CHARGING_SOC_PERCENT = 95
        const val DEFAULT_TARGET_CHARGING_SOC_PERCENT = 85

        const val DEFAULT_SAFETY_DURATION_BUFFER_ENABLED = true
        const val MIN_SAFETY_DURATION_BUFFER_RATIO = 0.0f
        const val MAX_SAFETY_DURATION_BUFFER_RATIO = 1.0f
        const val DEFAULT_SAFETY_DURATION_BUFFER_RATIO = 0.25f

        const val MIN_CHARGER_POWER_KW = 20.0
        const val MAX_CHARGER_POWER_KW = 250.0
        const val DEFAULT_MIN_CHARGER_POWER_KW = 60.0

        // Predefined power presets matching Vietnamese charging infrastructure
        const val PRESET_POWER_STANDARD = 30.0
        const val PRESET_POWER_FAST = 60.0
        const val PRESET_POWER_ULTRA = 150.0
        const val PRESET_POWER_SUPER = 250.0

        // Additional convenient aliases
        const val MIN_SAFE_RANGE_KM = MIN_VEHICLE_SAFE_RANGE_KM
        const val MAX_SAFE_RANGE_KM = MAX_VEHICLE_SAFE_RANGE_KM
        const val DEFAULT_SAFE_RANGE_KM = DEFAULT_VEHICLE_SAFE_RANGE_KM

        /**
         * Creates a new [EvRoutingSettings] instance with values clamped within boundary limits.
         */
        fun createSanitized(
            vehicleSafeRangeKm: Int = DEFAULT_VEHICLE_SAFE_RANGE_KM,
            startBatteryPercent: Int = DEFAULT_START_BATTERY_PERCENT,
            arrivalBufferSocPercent: Int = DEFAULT_ARRIVAL_BUFFER_SOC_PERCENT,
            targetChargingSocPercent: Int = DEFAULT_TARGET_CHARGING_SOC_PERCENT,
            safetyDurationBufferEnabled: Boolean = DEFAULT_SAFETY_DURATION_BUFFER_ENABLED,
            safetyDurationBufferRatio: Float = DEFAULT_SAFETY_DURATION_BUFFER_RATIO,
            minChargerPowerKw: Double = DEFAULT_MIN_CHARGER_POWER_KW
        ): EvRoutingSettings {
            return EvRoutingSettings(
                vehicleSafeRangeKm = vehicleSafeRangeKm,
                startBatteryPercent = startBatteryPercent,
                arrivalBufferSocPercent = arrivalBufferSocPercent,
                targetChargingSocPercent = targetChargingSocPercent,
                safetyDurationBufferEnabled = safetyDurationBufferEnabled,
                safetyDurationBufferRatio = safetyDurationBufferRatio,
                minChargerPowerKw = minChargerPowerKw
            ).sanitized()
        }

        fun sanitize(settings: EvRoutingSettings): EvRoutingSettings = settings.sanitized()
    }
}
