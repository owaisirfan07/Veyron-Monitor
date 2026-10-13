package com.veyronmonitor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyronmonitor.app.data.EnergyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun units(wh: Double): String = when {
    wh >= 100_000 -> "%.0f".format(wh / 1000)
    wh >= 10_000 -> "%.1f".format(wh / 1000)
    else -> "%.2f".format(wh / 1000)
}

private fun rupees(v: Double): String = "Rs " + "%,d".format(v.toLong())

private val keyFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun dayKeyOffset(days: Int): String {
    val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -days)
    return keyFmt.format(c.time)
}

private fun pretty(key: String, pattern: String): String = try {
    SimpleDateFormat(pattern, Locale.US).format(keyFmt.parse(key)!!)
} catch (_: Exception) { key }

private fun friendlyDay(key: String): String = when (key) {
    dayKeyOffset(0) -> "Aaj"
    dayKeyOffset(1) -> "Kal"
    else -> pretty(key, "EEE d MMM")
}

/** Solar units only: today, the day's curve, daily bars, week/month, lifetime meter, day list. */
@Composable
fun UnitsScreen(
    energy: EnergyStore.EnergyState,
    history: List<EnergyStore.DayRecord>,
    sampleDays: List<String>,
    samplesFor: (String) -> List<EnergyStore.Sample>,
    tariff: Double,
    onTariffChange: (Double) -> Unit,
    backgroundEnabled: Boolean,
    onBackgroundToggle: (Boolean) -> Unit,
    batteryRestricted: Boolean,
    onFixBattery: () -> Unit,
    onResetSolar: () -> Unit,
    onOpenReports: () -> Unit = {}
) {
    var showTariff by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var selectedDay by remember { mutableStateOf(energy.dayKey) }
    var range by remember { mutableIntStateOf(7) }
    var showAllDays by remember { mutableStateOf(false) }

    val byKey = remember(history) { history.associateBy { it.dateKey } }
    val yesterday = byKey[dayKeyOffset(1)]

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solar units", style = MaterialTheme.typography.headlineMedium)
                    Text(pretty(energy.dayKey, "EEEE, d MMMM yyyy"), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = onOpenReports) {
                    Icon(Icons.Filled.Insights, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reports")
                }
            }
        }

        item {
            // yesterday's solar up to this same time of day, for a fair comparison
            val ySoFar = remember(energy.lastReadingTs) {
                val now = Calendar.getInstance()
                val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                val s = samplesFor(dayKeyOffset(1)).filter { it.minuteOfDay <= nowMin }
                if (s.size < 3) null else s.zipWithNext { a, b ->
                    val gap = b.minuteOfDay - a.minuteOfDay
                    if (gap in 1..25) (a.solarW + b.solarW) / 2.0 * gap / 60.0 else 0.0
                }.sum()
            }
            TodayHero(energy, yesterday, ySoFar, tariff, onEditTariff = { showTariff = true })
        }

        // ---------------- today's solar curve ----------------
        item {
            SectionCard(title = "Solar graph", icon = Icons.Filled.ShowChart) {
                val days = (sampleDays + energy.dayKey).distinct().sortedDescending()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(days) { d -> FilterChip(selected = d == selectedDay, onClick = { selectedDay = d }, label = { Text(friendlyDay(d)) }) }
                }
                Spacer(Modifier.height(8.dp))
                val samples = remember(selectedDay, energy.lastReadingTs) { samplesFor(selectedDay) }
                val dayRec = byKey[selectedDay]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Watts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (dayRec != null) Text("${units(dayRec.solarWh)} units", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
                PowerCurveChart(samples = samples, axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    gridLineColor = MaterialTheme.colorScheme.outlineVariant, solarOnly = true)
                if (samples.isEmpty()) {
                    Text("Is din ka graph abhi nahi hai - readings aate hi yahan curve banta jayega.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // ---------------- daily bars ----------------
        item {
            SectionCard(title = "Roz ke units", icon = Icons.Filled.BarChart) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = range == 7, onClick = { range = 7 }, label = { Text("7 din") })
                    FilterChip(selected = range == 30, onClick = { range = 30 }, label = { Text("30 din") })
                }
                Spacer(Modifier.height(10.dp))
                val window = (range - 1 downTo 0).map { dayKeyOffset(it) }
                val values = window.map { (byKey[it]?.solarWh ?: 0.0) / 1000 }
                val best = values.indices.maxByOrNull { values[it] } ?: -1
                EstimateBarsChart(
                    values = values,
                    labels = window.map { if (range == 7) pretty(it, "EEE") else pretty(it, "d") },
                    color = SolarColor,
                    axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    gridLineColor = MaterialTheme.colorScheme.outlineVariant,
                    highlightIndex = best
                )
                Spacer(Modifier.height(8.dp))
                val withData = values.filter { it > 0 }
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("${"%.1f".format(values.sum())} units", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("kul $range din mein", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (withData.isNotEmpty()) "${"%.1f".format(withData.average())} units" else "--",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("roz ka average", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (best >= 0 && values[best] > 0) "${"%.1f".format(values[best])} units" else "--",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (best >= 0 && values[best] > 0) "behtareen: ${friendlyDay(window[best])}" else "behtareen din",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---------------- week / month ----------------
        item {
            val week = (0..6).mapNotNull { byKey[dayKeyOffset(it)] }
            val month = history.filter { it.dateKey.startsWith(energy.dayKey.take(7)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile(Modifier.weight(1f), "Is hafte", week.sumOf { it.solarWh }, tariff)
                SummaryTile(Modifier.weight(1f), pretty(energy.dayKey, "MMMM"), month.sumOf { it.solarWh }, tariff)
            }
        }

        // ---------------- lifetime solar meter ----------------
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(SolarColor.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.SolarPower, null, tint = SolarDeep, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Kul solar units", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("since " + SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(energy.solarSince)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(units(energy.solarWh), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Filled.RestartAlt, "Reset", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ---------------- background counting ----------------
        item {
            SectionCard(title = "Background counting", icon = Icons.Filled.Sync) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App band ho tab bhi units ginein", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(if (backgroundEnabled) "Har 1.5 minute reading li jaati hai" else "Band hai - units sirf app khuli ho tab ginenge",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = backgroundEnabled, onCheckedChange = onBackgroundToggle)
                }
                if (backgroundEnabled && batteryRestricted) {
                    Spacer(Modifier.height(10.dp))
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatteryAlert, null, tint = SolarDeep)
                            Spacer(Modifier.width(10.dp))
                            Text("Phone battery-saver app ko band kar sakta hai. \"Allow\" karein taa ke units miss na hon.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = onFixBattery) { Text("Allow") }
                        }
                    }
                }
            }
        }

        // ---------------- day by day ----------------
        item {
            Text("DIN BA DIN", style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
        val list = if (showAllDays) history else history.take(10)
        items(list, key = { it.dateKey }) { r -> DayRow(r, tariff) }
        if (history.size > 10) {
            item {
                TextButton(onClick = { showAllDays = !showAllDays }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAllDays) "Kam dikhayein" else "Saare ${history.size} din dikhayein")
                }
            }
        }
    }

    if (showTariff) {
        var text by remember { mutableStateOf(if (tariff % 1.0 == 0.0) tariff.toInt().toString() else tariff.toString()) }
        AlertDialog(
            onDismissRequest = { showTariff = false },
            title = { Text("Bijli ka rate") },
            text = {
                Column {
                    Text("Aapke KE bill mein 1 unit kitne ka parta hai (tax mila kar)? Isi se bachat ka andaza lagta hai.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Rs per unit") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(onClick = { text.toDoubleOrNull()?.takeIf { it > 0 }?.let(onTariffChange); showTariff = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showTariff = false }) { Text("Cancel") } }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Kul solar units reset?") },
            text = { Text("Sirf yeh total 0 se shuru hoga. Roz ka record aur graph waise hi rahenge.") },
            confirmButton = { TextButton(onClick = { onResetSolar(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TodayHero(energy: EnergyStore.EnergyState, yesterday: EnergyStore.DayRecord?, yesterdaySoFarWh: Double?, tariff: Double, onEditTariff: () -> Unit) {
    val dark = MaterialTheme.colorScheme.background.red < 0.3f
    val gradient = if (dark) Brush.linearGradient(listOf(Color(0xFF3A2A06), Color(0xFF1A2540)))
                   else Brush.linearGradient(listOf(Color(0xFFFFF1C7), Color(0xFFFFFFFF)))
    val onHero = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(gradient)
            .border(1.dp, SolarColor.copy(alpha = 0.35f), MaterialTheme.shapes.large).padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(SolarColor.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.WbSunny, null, tint = SolarDeep, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Aaj solar se bane", style = MaterialTheme.typography.titleMedium, color = onHero)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(units(energy.daySolarWh), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = onHero, lineHeight = 52.sp)
                Spacer(Modifier.width(8.dp))
                Text("units", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onEditTariff).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("≈ ${rupees(energy.daySolarWh / 1000 * tariff)} ki bachat", style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold, color = BatteryColor)
                Spacer(Modifier.width(6.dp))
                Text("(Rs ${"%.0f".format(tariff)}/unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.Edit, "Rate badlein", Modifier.size(14.dp).padding(start = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(")", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroStat(Modifier.weight(1f), "Peak solar", if (energy.dayPeakSolarW > 0) "${energy.dayPeakSolarW.toInt()} W" else "--",
                    if (energy.dayPeakAt > 0) SimpleDateFormat("h:mm a", Locale.US).format(Date(energy.dayPeakAt)) else null)
                HeroStat(Modifier.weight(1f), "Kal poora din", yesterday?.let { "${units(it.solarWh)} units" } ?: "--", null)
                val diff = yesterdaySoFarWh?.takeIf { it > 100 }?.let { (energy.daySolarWh - it) / it * 100 }
                HeroStat(Modifier.weight(1f), "Kal isi waqt se", diff?.let { (if (it >= 0) "+" else "") + "%.0f%%".format(it) } ?: "--",
                    yesterdaySoFarWh?.let { "kal: ${units(it)}" })
            }
            if (energy.dayEstWh >= 50) {
                Spacer(Modifier.height(12.dp))
                Text("Is mein ${units(energy.dayEstWh)} units andaza hain - beech mein kuch readings nahi aayi thi.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (energy.dayMissedMin >= 60) {
                Spacer(Modifier.height(6.dp))
                val hrs = energy.dayMissedMin / 60; val mins = energy.dayMissedMin % 60
                Text("Aaj ${if (hrs > 0) "$hrs ghante " else ""}$mins min ka data nahi mila - asal units thore zyada ho sakte hain.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HeroStat(modifier: Modifier, label: String, value: String, sub: String?) {
    Column(modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
        .padding(horizontal = 10.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SummaryTile(modifier: Modifier, title: String, solarWh: Double, tariff: Double) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text(title.uppercase(), style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(units(solarWh), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(4.dp))
                Text("units", style = MaterialTheme.typography.labelMedium, color = SolarDeep, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text("Bachat ${rupees(solarWh / 1000 * tariff)}", style = MaterialTheme.typography.labelMedium,
                color = BatteryColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DayRow(r: EnergyStore.DayRecord, tariff: Double) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(friendlyDay(r.dateKey), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(pretty(r.dateKey, "d MMM yyyy") + (if (r.peakSolarW > 0) "  •  peak ${r.peakSolarW.toInt()} W" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${units(r.solarWh)} units", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(rupees(r.solarWh / 1000 * tariff) + " bacha", style = MaterialTheme.typography.labelSmall, color = BatteryColor)
            }
        }
    }
}
