package com.veyronmonitor.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Stores running energy totals (in watt-hours) that the app itself
 * accumulates by integrating live power readings over time, since the
 * inverter's cloud data doesn't expose a ready-made "units generated" /
 * "units consumed" figure. Each lifetime counter can be reset
 * independently, like the reset button on a voltage-protector style
 * energy meter. A separate day-by-day history log is kept automatically
 * (not affected by manual resets) so you can see each day's totals.
 */
object EnergyStore {
    private const val PREFS = "veyron_monitor_energy"

    private const val KEY_SOLAR_WH = "solar_wh"
    private const val KEY_GRID_WH = "grid_wh"
    private const val KEY_SOLAR_SINCE = "solar_since"
    private const val KEY_GRID_SINCE = "grid_since"
    private const val KEY_LAST_SAMPLE_AT = "last_sample_at"

    private const val KEY_DAY_KEY = "current_day_key"
    private const val KEY_DAY_SOLAR_WH = "current_day_solar_wh"
    private const val KEY_DAY_GRID_WH = "current_day_grid_wh"
    private const val KEY_HISTORY_JSON = "history_json"
    private const val MAX_HISTORY_DAYS = 60

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    data class EnergyState(
        val solarWh: Double,
        val gridWh: Double,
        val solarSince: Long,
        val gridSince: Long,
        val lastSampleAt: Long,
        val currentDayKey: String,
        val currentDaySolarWh: Double,
        val currentDayGridWh: Double
    )

    data class DayRecord(val dateKey: String, val solarWh: Double, val gridWh: Double)

    fun todayKey(): String = dayFormat.format(System.currentTimeMillis())

    fun load(context: Context): EnergyState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        return EnergyState(
            solarWh = prefs.getFloat(KEY_SOLAR_WH, 0f).toDouble(),
            gridWh = prefs.getFloat(KEY_GRID_WH, 0f).toDouble(),
            solarSince = prefs.getLong(KEY_SOLAR_SINCE, now),
            gridSince = prefs.getLong(KEY_GRID_SINCE, now),
            lastSampleAt = prefs.getLong(KEY_LAST_SAMPLE_AT, 0L),
            currentDayKey = prefs.getString(KEY_DAY_KEY, todayKey()) ?: todayKey(),
            currentDaySolarWh = prefs.getFloat(KEY_DAY_SOLAR_WH, 0f).toDouble(),
            currentDayGridWh = prefs.getFloat(KEY_DAY_GRID_WH, 0f).toDouble()
        )
    }

    fun save(context: Context, state: EnergyState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SOLAR_WH, state.solarWh.toFloat())
            .putFloat(KEY_GRID_WH, state.gridWh.toFloat())
            .putLong(KEY_SOLAR_SINCE, state.solarSince)
            .putLong(KEY_GRID_SINCE, state.gridSince)
            .putLong(KEY_LAST_SAMPLE_AT, state.lastSampleAt)
            .putString(KEY_DAY_KEY, state.currentDayKey)
            .putFloat(KEY_DAY_SOLAR_WH, state.currentDaySolarWh.toFloat())
            .putFloat(KEY_DAY_GRID_WH, state.currentDayGridWh.toFloat())
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

    /**
     * Adds one sample's worth of energy (watts * hours) to both the
     * lifetime totals and today's running total. If the calendar day has
     * changed since the last sample, yesterday's totals are archived into
     * the history log first and today's counter starts fresh at zero.
     */
    fun addSample(context: Context, current: EnergyState, solarWatts: Double, gridWatts: Double, elapsedHours: Double): EnergyState {
        val key = todayKey()
        var state = current

        if (state.currentDayKey != key) {
            // Day rolled over -- archive the completed day, then reset today's counters.
            appendHistory(context, DayRecord(state.currentDayKey, state.currentDaySolarWh, state.currentDayGridWh))
            state = state.copy(currentDayKey = key, currentDaySolarWh = 0.0, currentDayGridWh = 0.0)
        }

        val solarWh = solarWatts * elapsedHours
        val gridWh = gridWatts * elapsedHours
        state = state.copy(
            solarWh = state.solarWh + solarWh,
            gridWh = state.gridWh + gridWh,
            currentDaySolarWh = state.currentDaySolarWh + solarWh,
            currentDayGridWh = state.currentDayGridWh + gridWh
        )
        save(context, state)
        return state
    }

    private fun appendHistory(context: Context, record: DayRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_HISTORY_JSON, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val entry = JSONObject()
            .put("date", record.dateKey)
            .put("solarWh", record.solarWh)
            .put("gridWh", record.gridWh)
        arr.put(entry)

        // Keep only the most recent MAX_HISTORY_DAYS entries.
        val trimmed = if (arr.length() > MAX_HISTORY_DAYS) {
            JSONArray((arr.length() - MAX_HISTORY_DAYS until arr.length()).map { arr.getJSONObject(it) })
        } else arr

        prefs.edit().putString(KEY_HISTORY_JSON, trimmed.toString()).apply()
    }

    /** Full history, newest first, including today-so-far as the first entry. */
    fun getHistory(context: Context, state: EnergyState): List<DayRecord> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_HISTORY_JSON, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        val past = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DayRecord(o.getString("date"), o.optDouble("solarWh", 0.0), o.optDouble("gridWh", 0.0))
        }
        val today = DayRecord(state.currentDayKey, state.currentDaySolarWh, state.currentDayGridWh)
        return (past + today).sortedByDescending { it.dateKey }
    }
}
