package com.veyronmonitor.app.data

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Built-in assistant. Works offline from the app's own data (only the
 * "why is solar low" answer also checks the weather). It understands simple
 * Roman Urdu / English questions by keywords and always labels estimates.
 */
object SolarAssistant {

    val suggestions = listOf(
        "Aaj kitne units bane?",
        "Battery kitni der chalegi?",
        "Battery raat ko kitni chalegi?",
        "Solar kam kyun hai?",
        "Is mahine kitni bachat hui?",
        "Kal kitne units bane?",
        "Abhi kya chal raha hai?"
    )

    private fun has(q: String, vararg words: String) = words.any { q.contains(it) }
    private fun u(wh: Double) = "%.2f".format(wh / 1000)

    /** Blocking (may hit the network for weather) - call from IO. */
    fun answer(c: Context, question: String, live: JSONObject?): String {
        val q = question.lowercase()
        val e = EnergyStore.load(c)
        return when {
            has(q, "kyun", "kyon", "why", "kam kyu", "low") && has(q, "solar", "production", "units", "kam", "low") ->
                Insights.solarDiagnosis(c, live, Insights.cloudCover())

            has(q, "raat", "night", "tonight", "subah tak") && has(q, "battery", "chal") -> tonight(c, live)

            has(q, "battery", "betri", "chalegi", "chale gi", "backup", "runtime") -> batteryNow(c, live)

            has(q, "bachat", "saving", "bill", "paise", "rs ") -> savings(c)

            has(q, "kal", "yesterday") -> {
                val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val key = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(y.time)
                val r = EnergyStore.getHistory(c).firstOrNull { it.dateKey == key }
                if (r == null) "Kal ka record app mein nahi hai."
                else "Kal (${key.takeLast(5)}) solar se ${u(r.solarWh)} units bane" + (if (r.peakSolarW > 0) "\n• Sab se zyada: ${r.peakSolarW.toInt()} W" else "") +
                    (if (r.estWh > 50) "\n\n${u(r.estWh)} units andaza hain (beech mein kuch readings nahi aayi thi)." else "") +
                    (if (r.missedMin > 60) "\n\n${Insights.hm(r.missedMin / 60.0)} ka data nahi mila - asal units thore zyada ho sakte hain." else "")
            }

            has(q, "mahin", "month", "is mahine") -> {
                val prefix = e.dayKey.take(7)
                val m = EnergyStore.getHistory(c).filter { it.dateKey.startsWith(prefix) }
                "Is mahine ab tak solar se ${u(m.sumOf { it.solarWh })} units bane" +
                    (if (m.isNotEmpty()) " (roz ka average ${u(m.sumOf { it.solarWh } / m.size)} units)." else ".")
            }

            has(q, "aaj", "today", "generate", "bane", "bani", "units", "unit") ->
                "Aaj ab tak solar se ${u(e.daySolarWh)} units bane." +
                    (if (e.dayPeakSolarW > 0) "\n• Sab se zyada solar: ${e.dayPeakSolarW.toInt()} W (${Insights.clock(e.dayPeakAt)})" else "") +
                    (if (e.dayEstWh > 50) "\n\nIs mein ${u(e.dayEstWh)} units andaza hain (beech mein readings nahi aayi thi)." else "") +
                    (if (e.dayMissedMin >= 60) "\n\n${Insights.hm(e.dayMissedMin / 60.0)} ka data nahi mila - asal units thore zyada ho sakte hain." else "")

            has(q, "abhi", "now", "status", "kya chal") -> status(live)

            else -> "Ye sawal main abhi nahi samjha. Main in cheezon ka jawab de sakta hoon:\n" +
                suggestions.joinToString("\n") { "• $it" }
        }
    }

    private fun JSONObject.n(k: String) = optDouble(k, 0.0).takeIf { !it.isNaN() } ?: 0.0

    private fun status(live: JSONObject?): String {
        live ?: return "Abhi inverter ka data nahi aaya."
        val mode = when (live.optString("workMode").uppercase()) { "B" -> "Battery mode"; "L" -> "Grid (line) mode"; "F" -> "Fault"; else -> live.optString("workMode") }
        return "Abhi:\n• Solar: ${live.n("pvInputPower1").toInt()} W\n• Battery: ${live.n("batteryCapacity").toInt()}%" +
            (if (live.n("batteryChargingPower") > 20) " (charge ho rahi ${live.n("batteryChargingPower").toInt()} W)" else if (live.n("batteryDischargingPower") > 20) " (use ho rahi ${live.n("batteryDischargingPower").toInt()} W)" else "") +
            "\n• Ghar ka load: ${live.n("acOutputActivePowerTotal").toInt()} W\n• Grid: ${live.n("gridVoltageR").toInt()} V\n• Mode: $mode"
    }

    private fun batteryNow(c: Context, live: JSONObject?): String {
        val r = Insights.runtime(c, live) ?: return "Abhi inverter ka data nahi aaya."
        val b = BatteryConfig.get(c)
        return buildString {
            append("Battery ${r.soc}% hai (${b.capacityWh.toInt()} Wh, ${b.cutoffPct}% tak use).\n")
            when (r.state) {
                "discharging" -> {
                    append("Abhi ${r.drainW?.toInt()} W use ho rahi hai - is raftar se ~${Insights.hm(r.hoursLeft ?: 0.0)} aur chalegi")
                    r.emptyAt?.let { append(" (~${Insights.whenText(it)} tak)") }
                    append(".")
                }
                "charging" -> {
                    append("Abhi charge ho rahi hai - full hone mein ~${Insights.hm(r.hoursToFull ?: 0.0)}.")
                    r.hoursLeft?.let { append("\nAgar abhi bijli jaye to aam load par ~${Insights.hm(it)} chalegi.") }
                }
                else -> r.hoursLeft?.let { append("Agar abhi bijli jaye to aam load (${r.drainW?.toInt()} W) par ~${Insights.hm(it)} chalegi.") }
                    ?: append("Abhi battery use nahi ho rahi, aur app ke paas abhi tak aam load ka record nahi.")
            }
            append("\n\nYe andaza hai (${r.basis}). Load badlega to waqt bhi badlega.")
        }
    }

    private fun tonight(c: Context, live: JSONObject?): String {
        live ?: return "Abhi inverter ka data nahi aaya."
        val b = BatteryConfig.get(c)
        val e = EnergyStore.load(c)
        val nightW = e.avgDischargeW.takeIf { it > 20 } ?: return "Raat ke load ka record abhi nahi bana - ek raat battery par chalne ke baad bata sakunga."
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0
        var soc = live.n("batteryCapacity")
        val chg = live.n("batteryChargingPower")
        // expected charge by sunset (~6 PM) if it's still daytime
        if (hour < 18 && chg > 20) soc = minOf(100.0, soc + chg * (18 - hour) / b.capacityWh * 100 * 0.9)
        val startHour = max(hour, 18.0)
        val availWh = b.capacityWh * max(0.0, soc - b.cutoffPct) / 100.0
        val hours = availWh / nightW
        val endHour = startHour + hours
        val nightLeft = (24 + 6.5) - startHour
        val end = ((endHour % 24) * 60).roundToInt()
        val endText = "%d:%02d %s".format(((end / 60) % 12).let { if (it == 0) 12 else it }, end % 60, if ((end / 60) % 24 < 12) "AM" else "PM")
        return buildString {
            append("Andaza: shaam tak battery ~${soc.roundToInt()}% hogi. Raat ka aam load ~${nightW.toInt()} W hai.\n")
            append("Is par battery ~${Insights.hm(hours)} chalegi")
            if (hours < nightLeft) append(" - yani ~$endText tak. Us ke baad grid par chalega.")
            else append(" - subah tak aaram se chal jayegi.")
            append("\n\nYe andaza hai. Raat ko AC ya zyada load chalaya to kam chalegi.")
        }
    }

    private fun savings(c: Context): String {
        val s = Insights.savings(c)
        return "Is mahine (${s.fromLabel} se) solar ne ${"%.1f".format(s.solarUnits)} units banaye.\n" +
            "Rs ${"%.0f".format(s.ratePerUnit)}/unit ke hisaab se ~Rs ${"%,d".format(s.saved)} ki bachat.\n\n" +
            "Ye andaza hai - rate Units tab mein badal sakte hain."
    }
}
