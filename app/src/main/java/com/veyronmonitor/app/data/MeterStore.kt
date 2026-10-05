package com.veyronmonitor.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * KE meter readings, entered by hand once a month (like the meter reader
 * does on the 9th). The difference between two readings is the real number
 * of grid units used - the same number KE bills.
 *
 * The inverter can't see the electricity the house uses while it runs
 * straight off the grid (bypass, work mode "L"), so the monthly units are
 * spread over the days by how many hours each day the house was on grid
 * bypass. Those per-day numbers are an ESTIMATE and are shown as such.
 */
object MeterStore {
    private const val PREFS = "veyron_monitor_meter"
    private const val K_READINGS = "readings"
    private const val K_WARN = "warn_units"
    private const val K_LIMIT = "limit_units"
    const val READING_DAY = 9
    const val READING_HOUR = 11
    private const val DAY_MS = 86_400_000L

    data class Reading(val at: Long, val value: Double)

    data class DayEstimate(val dateKey: String, val units: Double, val estimated: Boolean)

    data class Period(
        val from: Reading,
        val to: Reading,
        val units: Double,
        val days: Double,
        /** True when there wasn't enough app data and the units were split evenly. */
        val evenSplit: Boolean,
        val daily: List<DayEstimate>
    ) {
        val perDay: Double get() = if (days > 0) units / days else 0.0
    }

    data class Current(
        val since: Reading,
        val nextReadingAt: Long,
        val soFar: Double,
        val projected: Double,
        val elapsedDays: Double,
        val totalDays: Double,
        val hasBasis: Boolean,
        val daily: List<DayEstimate>
    )

    private val keyFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- readings ----------

    fun readings(context: Context): List<Reading> {
        val arr = try { JSONArray(prefs(context).getString(K_READINGS, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Reading(o.getLong("at"), o.getDouble("value"))
        }.sortedBy { it.at }
    }

    private fun save(context: Context, list: List<Reading>) {
        val arr = JSONArray()
        list.sortedBy { it.at }.forEach { arr.put(JSONObject().put("at", it.at).put("value", it.value)) }
        prefs(context).edit().putString(K_READINGS, arr.toString()).apply()
    }

    /** Returns an error message (Roman Urdu) if the reading doesn't make sense, else null. */
    fun add(context: Context, at: Long, value: Double): String? {
        val list = readings(context).filter { keyFmt.format(it.at) != keyFmt.format(at) }
        val before = list.lastOrNull { it.at < at }
        val after = list.firstOrNull { it.at > at }
        if (before != null && value < before.value) return "Reading pichli reading (${fmt(before.value)}) se kam nahi ho sakti."
        if (after != null && value > after.value) return "Reading agli reading (${fmt(after.value)}) se zyada nahi ho sakti."
        save(context, list + Reading(at, value))
        return null
    }

    fun remove(context: Context, r: Reading) = save(context, readings(context).filter { it != r })

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

    // ---------- limits ----------

    fun warnAt(context: Context): Double = prefs(context).getFloat(K_WARN, 180f).toDouble()
    fun limitAt(context: Context): Double = prefs(context).getFloat(K_LIMIT, 200f).toDouble()
    fun setLimits(context: Context, warn: Double, limit: Double) {
        prefs(context).edit().putFloat(K_WARN, warn.toFloat()).putFloat(K_LIMIT, limit.toFloat()).apply()
    }

    // ---------- dates ----------

    /** The next "9th at 11:00" strictly after [after]. */
    fun nextReadingDate(after: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.DAY_OF_MONTH, READING_DAY)
            set(Calendar.HOUR_OF_DAY, READING_HOUR); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // A reading taken a few days early/late still belongs to that month's 9th.
        while (c.timeInMillis <= after + 3 * DAY_MS) c.add(Calendar.MONTH, 1)
        return c.timeInMillis
    }

    /** Day keys after [from]'s date up to and including [to]'s date. */
    private fun daysBetween(from: Long, to: Long): List<String> {
        val out = mutableListOf<String>()
        val c = Calendar.getInstance().apply { timeInMillis = from }
        val end = keyFmt.format(to)
        while (true) {
            c.add(Calendar.DAY_OF_YEAR, 1)
            val k = keyFmt.format(c.time)
            out.add(k)
            if (k >= end || out.size > 400) break
        }
        return out
    }

    // ---------- calculations ----------

    /**
     * Splits [units] over [days]: the grid units the inverter measured itself
     * (charging) stay on their day, the rest is shared by each day's
     * grid-bypass hours. Falls back to an even split if under half the days
     * have tracking data.
     */
    private fun distribute(units: Double, days: List<String>, byKey: Map<String, EnergyStore.DayRecord>): Pair<List<DayEstimate>, Boolean> {
        if (days.isEmpty()) return emptyList<DayEstimate>() to true
        val tracked = days.filter { byKey[it]?.tracked == true }
        if (tracked.size * 2 < days.size) {
            return days.map { DayEstimate(it, units / days.size, true) } to true
        }
        val untracked = days.size - tracked.size
        val evenShare = units / days.size
        val trackedUnits = units - untracked * evenShare
        val measured = tracked.sumOf { (byKey[it]!!.gridWh / 1000.0) }
        val bypassMin = tracked.sumOf { byKey[it]!!.bypassMin }
        val rest = max(0.0, trackedUnits - measured)
        val list = days.map { k ->
            val r = byKey[k]
            if (r == null || !r.tracked) DayEstimate(k, evenShare, true)
            else {
                val share = if (bypassMin > 0) rest * r.bypassMin / bypassMin else rest / tracked.size
                // scale measured part down if it somehow exceeds the meter total
                val m = if (measured > trackedUnits && measured > 0) r.gridWh / 1000.0 * trackedUnits / measured else r.gridWh / 1000.0
                DayEstimate(k, m + share, true)
            }
        }
        return list to false
    }

    fun periods(context: Context): List<Period> {
        val rs = readings(context)
        val byKey = EnergyStore.getHistory(context).associateBy { it.dateKey }
        return rs.zipWithNext { a, b ->
            val units = b.value - a.value
            val (daily, even) = distribute(units, daysBetween(a.at, b.at), byKey)
            Period(a, b, units, (b.at - a.at) / DAY_MS.toDouble(), even, daily)
        }.reversed() // newest first
    }

    /** kWh per hour of grid bypass, learned from the most recent complete period with enough data. */
    private fun bypassRate(periods: List<Period>, byKey: Map<String, EnergyStore.DayRecord>): Double? {
        for (p in periods) {
            val days = p.daily.map { it.dateKey }
            val tracked = days.filter { byKey[it]?.tracked == true }
            if (tracked.size * 2 < days.size) continue
            val hours = tracked.sumOf { byKey[it]!!.bypassMin } / 60.0
            if (hours < 24) continue
            val measured = tracked.sumOf { byKey[it]!!.gridWh / 1000.0 }
            val share = p.units * tracked.size / days.size
            return max(0.0, share - measured) / hours
        }
        return null
    }

    /** The running month since the last reading, with a projection to the next reading day. */
    fun current(context: Context, now: Long = System.currentTimeMillis()): Current? {
        val rs = readings(context)
        val last = rs.lastOrNull() ?: return null
        val periods = periods(context)
        val byKey = EnergyStore.getHistory(context).associateBy { it.dateKey }
        val rate = bypassRate(periods, byKey)
        val fallbackDaily = periods.firstOrNull()?.perDay
        val next = nextReadingDate(last.at)
        val days = if (now > last.at) daysBetween(last.at, now) else emptyList()
        val todayKey = keyFmt.format(now)
        val daily = days.map { k ->
            val r = byKey[k]
            val fraction = if (k == todayKey) {
                val c = Calendar.getInstance().apply { timeInMillis = now }
                (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)) / 1440.0
            } else 1.0
            when {
                r != null && r.tracked && rate != null -> DayEstimate(k, r.gridWh / 1000.0 + r.bypassMin / 60.0 * rate, true)
                fallbackDaily != null -> DayEstimate(k, fallbackDaily * fraction, true)
                else -> DayEstimate(k, 0.0, true)
            }
        }
        val hasBasis = rate != null || fallbackDaily != null
        val soFar = daily.sumOf { it.units }
        val elapsed = max(0.0, (now - last.at) / DAY_MS.toDouble())
        val total = (next - last.at) / DAY_MS.toDouble()
        val projected = when {
            !hasBasis -> 0.0
            elapsed >= 1.0 -> soFar / elapsed * total
            fallbackDaily != null -> fallbackDaily * total
            else -> soFar
        }
        return Current(last, next, soFar, projected, elapsed, total, hasBasis, daily)
    }

    fun bill(units: Double, tariff: Double): Long = (units * tariff).roundToLong()
}
