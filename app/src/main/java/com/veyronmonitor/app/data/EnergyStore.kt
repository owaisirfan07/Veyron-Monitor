package com.veyronmonitor.app.data

import android.content.Context

/**
 * Stores running energy totals (in watt-hours) that the app itself
 * accumulates by integrating live power readings over time, since the
 * inverter's cloud data doesn't expose a ready-made "units generated" /
 * "units consumed" figure. Each counter can be reset independently, like
 * the reset button on a voltage-protector style energy meter.
 */
object EnergyStore {
    private const val PREFS = "veyron_monitor_energy"

    private const val KEY_SOLAR_WH = "solar_wh"
    private const val KEY_GRID_WH = "grid_wh"
    private const val KEY_SOLAR_SINCE = "solar_since"
    private const val KEY_GRID_SINCE = "grid_since"
    private const val KEY_LAST_SAMPLE_AT = "last_sample_at"

    data class EnergyState(
        val solarWh: Double,
        val gridWh: Double,
        val solarSince: Long,
        val gridSince: Long,
        val lastSampleAt: Long
    )

    fun load(context: Context): EnergyState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return EnergyState(
            solarWh = prefs.getFloat(KEY_SOLAR_WH, 0f).toDouble(),
            gridWh = prefs.getFloat(KEY_GRID_WH, 0f).toDouble(),
            solarSince = prefs.getLong(KEY_SOLAR_SINCE, now),
            gridSince = prefs.getLong(KEY_GRID_SINCE, now),
            lastSampleAt = prefs.getLong(KEY_LAST_SAMPLE_AT, 0L)
        )
    }

    fun save(context: Context, state: EnergyState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SOLAR_WH, state.solarWh.toFloat())
            .putFloat(KEY_GRID_WH, state.gridWh.toFloat())
            .putLong(KEY_SOLAR_SINCE, state.solarSince)
            .putLong(KEY_GRID_SINCE, state.gridSince)
            .putLong(KEY_LAST_SAMPLE_AT, state.lastSampleAt)
            .apply()
    }

    fun resetSolar(context: Context, current: EnergyState): EnergyState {
        val updated = current.copy(solarWh = 0.0, solarSince = System.currentTimeMillis())
        save(context, updated)
        return updated
    }

    fun resetGrid(context: Context, current: EnergyState): EnergyState {
        val updated = current.copy(gridWh = 0.0, gridSince = System.currentTimeMillis())
        save(context, updated)
        return updated
    }
}
