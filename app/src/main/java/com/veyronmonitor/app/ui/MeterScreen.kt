package com.veyronmonitor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.veyronmonitor.app.data.MeterStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val WarnColor = Color(0xFFF59E0B)
private val DangerColor = Color(0xFFEF4444)

private fun d(ms: Long, p: String) = SimpleDateFormat(p, Locale.US).format(Date(ms))
private fun num(v: Double) = if (v % 1.0 == 0.0) "%,d".format(v.toLong()) else "%,.1f".format(v)
private fun u(v: Double) = if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
private fun rs(v: Long) = "Rs " + "%,d".format(v)

@Composable
fun MeterScreen(
    readings: List<MeterStore.Reading>,
    periods: List<MeterStore.Period>,
    current: MeterStore.Current?,
    tariff: Double,
    warnAt: Double,
    limitAt: Double,
    onAddReading: (Long, Double) -> String?,
    onRemoveReading: (MeterStore.Reading) -> Unit,
    onSetLimits: (Double, Double) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var showLimits by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MeterStore.Reading?>(null) }
    var selected by remember(periods) { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("KE Meter", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Har mahine ${MeterStore.READING_DAY} tareekh, 11 baje reminder aayega",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (readings.isNotEmpty()) {
                    FilledTonalButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Reading")
                    }
                }
            }
        }

        // ---------- first-time setup ----------
        if (readings.isEmpty()) {
            item {
                SectionCard("Shuru karein", Icons.Filled.ReceiptLong) {
                    Text(
                        "Apna pichla KE bill uthayein. Us par \"Previous reading\" aur \"Present reading\" likhi hoti hai. " +
                            "Dono tareekh ke saath daal dein - app wahan se hisaab shuru kar degi.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showSetup = true }, modifier = Modifier.fillMaxWidth()) { Text("Bill ki readings daalein") }
                    TextButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Sirf aaj ki meter reading daalein") }
                }
            }
        }

        // ---------- this month ----------
        if (current != null) {
            item { CurrentCard(current, tariff, warnAt, limitAt, onEditLimits = { showLimits = true }) }
        }

        // ---------- completed months ----------
        if (periods.isNotEmpty()) {
            item {
                val p = periods[selected.coerceIn(0, periods.lastIndex)]
                SectionCard("${d(p.from.at, "d MMM")} – ${d(p.to.at, "d MMM yyyy")}", Icons.Filled.CalendarMonth) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(u(p.units), fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
                        Spacer(Modifier.width(6.dp))
                        Text("units", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(rs(MeterStore.bill(p.units, tariff)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("bill ka andaza", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "${num(p.from.value)} → ${num(p.to.value)}  •  ${"%.0f".format(p.days)} din  •  ~${"%.1f".format(p.perDay)} units roz",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LimitLine(p.units, warnAt, limitAt)
                    Spacer(Modifier.height(14.dp))
                    DailyBlock(p.daily, p.evenSplit)
                }
            }
            if (periods.size > 1) {
                item {
                    Text("PICHLE MAHINE", style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
                items(periods.size) { i ->
                    val p = periods[i]
                    val isSel = i == selected
                    Surface(
                        onClick = { selected = i },
                        color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d(p.to.at, "MMMM yyyy"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("${d(p.from.at, "d MMM")} – ${d(p.to.at, "d MMM")}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${u(p.units)} units", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                    color = limitColor(p.units, warnAt, limitAt) ?: MaterialTheme.colorScheme.onSurface)
                                Text(rs(MeterStore.bill(p.units, tariff)), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ---------- readings list ----------
        if (readings.isNotEmpty()) {
            item {
                SectionCard("Meter readings", Icons.Filled.Speed) {
                    readings.reversed().forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(num(r.value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(d(r.at, "EEE, d MMM yyyy"), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { deleteTarget = r }) {
                                Icon(Icons.Filled.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        ReadingDialog(
            title = "Meter reading",
            hint = "Meter par jo total likha hai (kWh) wo daalein.",
            onDismiss = { showAdd = false },
            fields = listOf("Reading"),
            onSave = { values ->
                val (at, v) = values[0]
                onAddReading(at, v).also { if (it == null) showAdd = false }
            }
        )
    }
    if (showSetup) {
        ReadingDialog(
            title = "Bill ki readings",
            hint = "Bill par likhi Previous aur Present reading, aur dono ki tareekh.",
            onDismiss = { showSetup = false },
            fields = listOf("Previous reading", "Present reading"),
            defaultDaysBack = listOf(60, 30),
            onSave = { values ->
                val (a, va) = values[0]
                val (b, vb) = values[1]
                when {
                    b <= a -> "Present reading ki tareekh Previous se baad ki honi chahiye."
                    vb < va -> "Present reading Previous se kam nahi ho sakti."
                    else -> (onAddReading(a, va) ?: onAddReading(b, vb)).also { if (it == null) showSetup = false }
                }
            }
        )
    }
    if (showLimits) {
        var w by remember { mutableStateOf(warnAt.toInt().toString()) }
        var l by remember { mutableStateOf(limitAt.toInt().toString()) }
        AlertDialog(
            onDismissRequest = { showLimits = false },
            title = { Text("Units ki hadd") },
            text = {
                Column {
                    Text("Mahine ka andaza in se upar jaane lage to app warning degi.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(w, { w = it.filter(Char::isDigit) }, label = { Text("Dhyan dein (units)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(l, { l = it.filter(Char::isDigit) }, label = { Text("Hadd (units)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val wv = w.toDoubleOrNull(); val lv = l.toDoubleOrNull()
                    if (wv != null && lv != null && wv in 1.0..lv) { onSetLimits(wv, lv); showLimits = false }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showLimits = false }) { Text("Cancel") } }
        )
    }
    deleteTarget?.let { r ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Reading delete karein?") },
            text = { Text("${num(r.value)} (${d(r.at, "d MMM yyyy")}) hata di jayegi.") },
            confirmButton = { TextButton(onClick = { onRemoveReading(r); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

private fun limitColor(units: Double, warnAt: Double, limitAt: Double): Color? = when {
    units >= limitAt -> DangerColor
    units >= warnAt -> WarnColor
    else -> null
}

@Composable
private fun LimitLine(units: Double, warnAt: Double, limitAt: Double) {
    val c = limitColor(units, warnAt, limitAt) ?: return
    Spacer(Modifier.height(6.dp))
    Text(
        if (units >= limitAt) "${limitAt.toInt()} units ki hadd cross ho gayi" else "${warnAt.toInt()} units se upar - dhyan dein",
        style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun CurrentCard(c: MeterStore.Current, tariff: Double, warnAt: Double, limitAt: Double, onEditLimits: () -> Unit) {
    val state = limitColor(c.projected, warnAt, limitAt)
    val accent = state ?: BatteryColor
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("IS MAHINE  •  ${d(c.since.at, "d MMM")} se".uppercase(), style = SectionCaption,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            if (!c.hasBasis) {
                Text(
                    "Andaze ke liye kam az kam ek poora mahina chahiye. Agli reading ${d(c.nextReadingAt, "d MMM")} ko daalein, " +
                        "phir app har roz ka hisaab aur mahine ka andaza dikhayegi.",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("~${"%.0f".format(c.projected)}", fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp, color = accent)
                Spacer(Modifier.width(6.dp))
                Text("units par mahina khatam hoga", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 7.dp))
            }
            Text(
                "Ab tak ~${u(c.soFar)} units  •  ${"%.0f".format(c.elapsedDays)} / ${"%.0f".format(c.totalDays)} din  •  bill ~${rs(MeterStore.bill(c.projected, tariff))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            // progress towards the limit, with a marker at the warning level
            val max = limitAt * 1.15
            BoxWithConstraints(Modifier.fillMaxWidth().height(12.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant))
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth((c.projected / max).toFloat().coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.35f))
                )
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth((c.soFar / max).toFloat().coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(50)).background(accent)
                )
                listOf(warnAt to WarnColor, limitAt to DangerColor).forEach { (v, col) ->
                    Box(Modifier.offset(x = maxWidth * (v / max).toFloat() - 1.dp).width(2.dp).fillMaxHeight().background(col))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = onEditLimits), verticalAlignment = Alignment.CenterVertically) {
                Text("Hadd: ${warnAt.toInt()} (dhyan)  •  ${limitAt.toInt()} (rate barhta hai)",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Edit, "Edit", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state != null) {
                Spacer(Modifier.height(12.dp))
                Surface(color = state.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, null, tint = state)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (c.projected >= limitAt)
                                "Is raftar se ${limitAt.toInt()} units cross ho jayenge - KE ka rate barh jayega. Baqi din grid ka load kam rakhein."
                            else "Mahina ${warnAt.toInt()} units se upar ja raha hai - ${limitAt.toInt()} ki hadd ke qareeb hain.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            if (c.daily.size >= 2) {
                Spacer(Modifier.height(16.dp))
                DailyBlock(c.daily, evenSplit = false)
            }
            Spacer(Modifier.height(8.dp))
            Text("Agli reading: ${d(c.nextReadingAt, "EEE, d MMM")} subah 11 baje", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DailyBlock(daily: List<MeterStore.DayEstimate>, evenSplit: Boolean) {
    if (daily.isEmpty()) return
    val values = daily.map { it.units }
    val maxIdx = values.indices.maxByOrNull { values[it] } ?: -1
    Text("Roz ke units (andaza)", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
    EstimateBarsChart(
        values = values,
        labels = daily.map { k -> k.dateKey.takeLast(2).trimStart('0') },
        color = GridColor,
        axisColor = MaterialTheme.colorScheme.onSurfaceVariant,
        gridLineColor = MaterialTheme.colorScheme.outlineVariant,
        highlightIndex = if (evenSplit) -1 else maxIdx
    )
    Spacer(Modifier.height(4.dp))
    if (evenSplit) {
        Text("Is mahine app ka data kam tha, is liye units barabar baante gaye hain.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (maxIdx >= 0) {
        val top = daily[maxIdx]
        val date = try {
            SimpleDateFormat("EEE d MMM", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(top.dateKey)!!)
        } catch (_: Exception) { top.dateKey }
        Text("Sab se zyada: $date (~${"%.1f".format(top.units)} units). Andaza grid par guzre ghanton se lagaya gaya hai.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Dialog with one or more (date + reading) rows. onSave returns an error message or null. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingDialog(
    title: String,
    hint: String,
    fields: List<String>,
    defaultDaysBack: List<Int> = listOf(0),
    onDismiss: () -> Unit,
    onSave: (List<Pair<Long, Double>>) -> String?
) {
    val texts = remember { mutableStateListOf(*Array(fields.size) { "" }) }
    val dates = remember {
        mutableStateListOf(*Array(fields.size) { i ->
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -(defaultDaysBack.getOrElse(i) { 0 }))
                set(Calendar.HOUR_OF_DAY, MeterStore.READING_HOUR); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        })
    }
    var picking by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                fields.forEachIndexed { i, label ->
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = texts[i], onValueChange = { v -> texts[i] = v.filter { it.isDigit() || it == '.' } },
                        label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { picking = i }) {
                        Icon(Icons.Filled.Event, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(d(dates[i], "EEE, d MMM yyyy"))
                    }
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val vals = texts.map { it.toDoubleOrNull() }
                error = if (vals.any { it == null || it <= 0 }) "Sahi reading daalein."
                else onSave(dates.zip(vals.map { it!! }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (picking >= 0) {
        val i = picking
        // DatePicker works in UTC midnight; convert to/from local 11:00.
        val local = Calendar.getInstance().apply { timeInMillis = dates[i] }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = utc.timeInMillis)
        DatePickerDialog(
            onDismissRequest = { picking = -1 },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { sel ->
                        val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                        dates[i] = Calendar.getInstance().apply {
                            set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), MeterStore.READING_HOUR, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    }
                    picking = -1
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = -1 }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}
