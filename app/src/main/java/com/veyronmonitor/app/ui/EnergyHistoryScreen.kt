package com.veyronmonitor.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.data.EnergyStore.DayRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun prettyDate(dateKey: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
    SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(parsed ?: Date())
} catch (_: Exception) {
    dateKey
}

private fun kwh(wh: Double): String = "%.2f".format(wh / 1000.0)

@Composable
private fun DailyBarChart(records: List<DayRecord>) {
    if (records.isEmpty()) return
    val maxKwh = (records.maxOf { maxOf(it.solarWh, it.gridWh) } / 1000.0).coerceAtLeast(0.1)
    val solarColor = Color(0xFFFACC15)
    val gridColor = Color(0xFF3B82F6)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(10.dp).background(solarColor, shape = androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("Solar", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.size(10.dp).background(gridColor, shape = androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("Grid", style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            records.forEach { record ->
                val solarKwh = record.solarWh / 1000.0
                val gridKwh = record.gridWh / 1000.0
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(0.7f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val barHeight = (solarKwh / maxKwh).toFloat().coerceIn(0f, 1f) * size.height
                            drawRoundRect(
                                color = solarColor,
                                topLeft = Offset(0f, size.height - barHeight),
                                size = Size(size.width, barHeight),
                                cornerRadius = CornerRadius(4f, 4f)
                            )
                        }
                        Canvas(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            val barHeight = (gridKwh / maxKwh).toFloat().coerceIn(0f, 1f) * size.height
                            drawRoundRect(
                                color = gridColor,
                                topLeft = Offset(0f, size.height - barHeight),
                                size = Size(size.width, barHeight),
                                cornerRadius = CornerRadius(4f, 4f)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        prettyDate(record.dateKey).take(3),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyHistoryScreen(
    records: List<DayRecord>,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daily History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Abhi koi history nahi -- kal se din-ba-din data yahan nazar aana shuru ho jaye ga.")
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                DailyBarChart(records.take(7).reversed())
                HorizontalDivider()
            }
            items(records) { record ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        prettyDate(record.dateKey),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SolarPower, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${kwh(record.solarWh)} kWh", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.width(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ElectricalServices, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("${kwh(record.gridWh)} kWh", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
