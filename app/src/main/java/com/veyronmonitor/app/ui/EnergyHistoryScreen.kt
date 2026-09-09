package com.veyronmonitor.app.ui

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
