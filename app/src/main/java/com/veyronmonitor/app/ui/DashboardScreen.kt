package com.veyronmonitor.app.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.model.ConnectionStatus
import org.json.JSONObject

data class Metric(val label: String, val value: String, val icon: ImageVector)

/** Friendly label for the inverter's PI30-style single-letter work mode code. */
private fun workModeLabel(code: String?): String = when (code?.uppercase()) {
    "B" -> "Battery mode"
    "L" -> "Line (grid) mode"
    "F" -> "Fault"
    "P" -> "Power on"
    "S" -> "Standby"
    "H" -> "Hybrid mode"
    null, "", "NULL" -> "--"
    else -> code
}

private fun JSONObject.numOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun fmt(value: Double?, unit: String, decimals: Int = 1): String =
    if (value == null) "--" else "%.${decimals}f%s".format(value, unit)

@Composable
fun DashboardScreen(
    deviceName: String,
    connectionStatus: ConnectionStatus,
    minutesSinceFresh: Int,
    data: JSONObject?,
    lastUpdatedText: String,
    isRefreshing: Boolean,
    errorMessage: String?,
    todaySolarWh: Double,
    todayGridWh: Double,
    todayLoadWh: Double,
    onRefresh: () -> Unit,
    onOpenUnits: () -> Unit,
    onLogout: () -> Unit
) {
    var showRawData by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val (dotColor, statusText) = when (connectionStatus) {
        ConnectionStatus.LIVE -> BatteryColor to "Live"
        ConnectionStatus.RECENT -> SolarDeep to "${minutesSinceFresh} min purana"
        ConnectionStatus.OFFLINE -> IdleColor to "Offline"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---------- Header ----------
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Veyron II", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(deviceName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(50), color = dotColor.copy(alpha = 0.15f)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                            Spacer(Modifier.width(6.dp))
                            Text(statusText, style = MaterialTheme.typography.labelMedium, color = dotColor)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("Update: $lastUpdatedText", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRefresh) {
                if (isRefreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, "Refresh")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Raw data") },
                        leadingIcon = { Icon(Icons.Filled.DataObject, null) },
                        enabled = data != null,
                        onClick = { menuOpen = false; showRawData = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Logout") },
                        leadingIcon = { Icon(Icons.Filled.Logout, null) },
                        onClick = { menuOpen = false; onLogout() }
                    )
                }
            }
        }

        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        if (data == null) {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        if (connectionStatus != ConnectionStatus.LIVE) {
            val context = LocalContext.current
            val message = if (connectionStatus == ConnectionStatus.RECENT)
                "Inverter ka WiFi slow hai - yeh $minutesSinceFresh minute purana data hai."
            else
                "Inverter offline hai - nayi reading nahi aa rahi, purana data dikh raha hai."
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, null, tint = SolarDeep, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        try { context.startActivity(Intent(AndroidSettings.Panel.ACTION_WIFI)) }
                        catch (_: Exception) { context.startActivity(Intent(AndroidSettings.ACTION_WIFI_SETTINGS)) }
                    }) { Text("WiFi") }
                }
            }
        }

        // ---------- Flow card ----------
        val batteryPct = data.numOrNull("batteryCapacity")
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("LIVE POWER", style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(workModeLabel(data.optString("workMode", "")), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                EnergyFlowDiagram(
                    solarWatts = data.numOrNull("pvInputPower1") ?: 0.0,
                    batteryPercent = batteryPct?.toInt(),
                    batteryChargingWatts = data.numOrNull("batteryChargingPower") ?: 0.0,
                    batteryDischargingWatts = data.numOrNull("batteryDischargingPower") ?: 0.0,
                    gridWatts = data.numOrNull("gridPowerInputActiveTotal") ?: 0.0,
                    houseWatts = data.numOrNull("acOutputActivePowerTotal") ?: 0.0
                )
            }
        }

        // ---------- Today's units strip ----------
        Card(
            onClick = onOpenUnits,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AAJ KE UNITS", style = SectionCaption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("Graph dekhein", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    UnitStat(Modifier.weight(1f), "Solar", todaySolarWh, SolarDeep, Icons.Filled.WbSunny)
                    UnitStat(Modifier.weight(1f), "Grid", todayGridWh, GridColor, Icons.Filled.ElectricalServices)
                    UnitStat(Modifier.weight(1f), "Ghar", todayLoadWh, LoadColor, Icons.Filled.Home)
                }
            }
        }

        // ---------- Detail sections ----------
        val solarMetrics = listOf(
            Metric("PV power", fmt(data.numOrNull("pvInputPower1"), " W", 0), Icons.Filled.SolarPower),
            Metric("PV voltage", fmt(data.numOrNull("pvInputVoltage1"), " V"), Icons.Filled.WbSunny),
            Metric("PV current", fmt(data.numOrNull("currentInput1"), " A"), Icons.Filled.ElectricBolt)
        )
        val batteryMetrics = listOf(
            Metric("Charge", fmt(batteryPct, "%", 0), Icons.Filled.BatteryStd),
            Metric("Voltage", fmt(data.numOrNull("batteryVoltage"), " V"), Icons.Filled.Bolt),
            Metric("Charging", fmt(data.numOrNull("chargingCurrent"), " A"), Icons.Filled.BatteryChargingFull),
            Metric("Discharging", fmt(data.numOrNull("dischargingCurrent"), " A"), Icons.Filled.BatteryAlert)
        )
        val gridMetrics = listOf(
            Metric("Grid voltage", fmt(data.numOrNull("gridVoltageR"), " V"), Icons.Filled.ElectricalServices),
            Metric("Grid frequency", fmt(data.numOrNull("gridFrequency"), " Hz"), Icons.Filled.GraphicEq),
            Metric("Output voltage", fmt(data.numOrNull("acOutputVoltageR"), " V"), Icons.Filled.Outlet),
            Metric("Output frequency", fmt(data.numOrNull("acOutputFrequency"), " Hz"), Icons.Filled.GraphicEq),
            Metric("Load", fmt(data.numOrNull("acOutputLoadTotal"), "%", 0), Icons.Filled.Speed),
            Metric("Active power", fmt(data.numOrNull("acOutputActivePowerTotal"), " W", 0), Icons.Filled.Power),
            Metric("Apparent power", fmt(data.numOrNull("acOutputApparentPowerTotal"), " VA", 0), Icons.Filled.PowerInput)
        )
        val tempMetrics = listOf(
            Metric("Inside", fmt(data.numOrNull("innerTemperature"), "°C", 0), Icons.Filled.Thermostat),
            Metric("Max", fmt(data.numOrNull("maxTemperature"), "°C", 0), Icons.Filled.DeviceThermostat)
        )

        MetricSection("Solar panels", SolarDeep, solarMetrics)
        MetricSection("Battery", BatteryColor, batteryMetrics)
        MetricSection("Grid & output", GridColor, gridMetrics)
        MetricSection("Temperature", DischargeColor, tempMetrics)
        Spacer(Modifier.height(20.dp))
    }

    if (showRawData && data != null) {
        AlertDialog(
            onDismissRequest = { showRawData = false },
            title = { Text("Raw data") },
            text = { LazyColumnRawData(data, data.keys().asSequence().sorted().toList()) },
            confirmButton = { TextButton(onClick = { showRawData = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun UnitStat(modifier: Modifier, label: String, wh: Double, color: Color, icon: ImageVector) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(units(wh), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LazyColumnRawData(data: JSONObject, keys: List<String>) {
    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
        lazyColumnItems(keys) { key ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(key, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(data.opt(key)?.toString() ?: "--", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricSection(title: String, accent: Color, metrics: List<Metric>) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) {
            Box(Modifier.size(width = 4.dp, height = 16.dp).clip(RoundedCornerShape(50)).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m -> MetricCard(Modifier.weight(1f), m, accent) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier, metric: Metric, accent: Color) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center) {
                Icon(metric.icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(metric.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(metric.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
