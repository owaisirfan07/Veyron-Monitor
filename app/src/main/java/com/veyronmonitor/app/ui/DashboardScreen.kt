package com.veyronmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

data class Metric(val label: String, val value: String, val icon: ImageVector)

/** Friendly label for the inverter's PI30-style single-letter work mode code. */
private fun workModeLabel(code: String?): String = when (code?.uppercase()) {
    "B" -> "Battery Mode"
    "L" -> "Line (Grid) Mode"
    "F" -> "Fault"
    "P" -> "Power On"
    "S" -> "Standby"
    "H" -> "Hybrid Mode"
    null -> "--"
    else -> code
}

private fun JSONObject.numOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun fmt(value: Double?, unit: String, decimals: Int = 1): String =
    if (value == null) "--" else "%.${decimals}f%s".format(value, unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    deviceName: String,
    isOnline: Boolean,
    data: JSONObject?,
    lastUpdatedText: String,
    isRefreshing: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(deviceName)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (isOnline) Color(0xFF22C55E) else Color(0xFF9CA3AF))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isOnline) "Online" else "Offline",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Parameters")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.Filled.Logout, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            if (isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                "Last updated: $lastUpdatedText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (errorMessage != null) {
                Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (!isOnline) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        "Inverter is offline (WiFi dongle disconnected) -- showing last known data.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            val warningArr = data.optJSONArray("warning")
            val hasWarning = warningArr != null && warningArr.length() > 0
            if (hasWarning) {
                val codes = (0 until warningArr!!.length()).joinToString(", ") { warningArr.optString(it) }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Warning code: $codes", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            val batteryPct = data.numOrNull("batteryCapacity")
            val chargingPower = data.numOrNull("batteryChargingPower") ?: 0.0
            val dischargingPower = data.numOrNull("batteryDischargingPower") ?: 0.0
            val batteryStatusText = when {
                chargingPower > 0 -> "Charging (${chargingPower.toInt()} W)"
                dischargingPower > 0 -> "Discharging (${dischargingPower.toInt()} W)"
                else -> "Idle"
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.padding(20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(
                            Icons.Filled.BatteryChargingFull,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Column {
                            Text(
                                if (batteryPct != null) "${batteryPct.toInt()}%" else "--",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                batteryStatusText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AssistChip(onClick = {}, label = { Text(workModeLabel(data.optString("workMode", null))) })
                }
            }

            val metrics = listOf(
                Metric("PV Power", fmt(data.numOrNull("pvInputPower1"), " W", 0), Icons.Filled.SolarPower),
                Metric("PV Voltage", fmt(data.numOrNull("pvInputVoltage1"), " V"), Icons.Filled.WbSunny),
                Metric("PV Current", fmt(data.numOrNull("currentInput1"), " A"), Icons.Filled.ElectricBolt),
                Metric("Battery Voltage", fmt(data.numOrNull("batteryVoltage"), " V"), Icons.Filled.Bolt),
                Metric("Charging Current", fmt(data.numOrNull("chargingCurrent"), " A"), Icons.Filled.BatteryChargingFull),
                Metric("Discharge Current", fmt(data.numOrNull("dischargingCurrent"), " A"), Icons.Filled.BatteryAlert),
                Metric("Output Voltage", fmt(data.numOrNull("acOutputVoltageR"), " V"), Icons.Filled.Outlet),
                Metric("Output Frequency", fmt(data.numOrNull("acOutputFrequency"), " Hz"), Icons.Filled.GraphicEq),
                Metric("Output Load", fmt(data.numOrNull("acOutputLoadTotal"), "%", 0), Icons.Filled.Speed),
                Metric("Output Active Power", fmt(data.numOrNull("acOutputActivePowerTotal"), " W", 0), Icons.Filled.Power),
                Metric("Output Apparent Power", fmt(data.numOrNull("acOutputApparentPowerTotal"), " VA", 0), Icons.Filled.PowerInput),
                Metric("Grid Voltage", fmt(data.numOrNull("gridVoltageR"), " V"), Icons.Filled.ElectricalServices),
                Metric("Inner Temp", fmt(data.numOrNull("innerTemperature"), "\u00b0C", 0), Icons.Filled.Thermostat),
                Metric("Max Temp", fmt(data.numOrNull("maxTemperature"), "\u00b0C", 0), Icons.Filled.DeviceThermostat)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(metrics) { metric -> MetricCard(metric) }
            }
        }
    }
}

@Composable
private fun MetricCard(metric: Metric) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                metric.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(metric.value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(metric.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
