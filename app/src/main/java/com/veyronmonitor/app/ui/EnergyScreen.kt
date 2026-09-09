package com.veyronmonitor.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSince(timestampMs: Long): String =
    if (timestampMs <= 0L) "--"
    else SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault()).format(Date(timestampMs))

private fun formatUnits(wh: Double): String = "%.2f".format(wh / 1000.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyScreen(
    solarWh: Double,
    gridWh: Double,
    solarSince: Long,
    gridSince: Long,
    onBack: () -> Unit,
    onResetSolar: () -> Unit,
    onResetGrid: () -> Unit,
    onViewHistory: () -> Unit
) {
    var confirmReset by remember { mutableStateOf<String?>(null) } // "solar" | "grid" | null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Energy Units") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "Yeh app khud calculate karti hai (har 15 second ki power reading se). " +
                        "Agar phone band ho ya app band ho, us waqt ka hisaab miss ho sakta hai -- " +
                        "yeh ek estimate hai, official utility meter jaisi 100% exact nahi.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            EnergyCard(
                icon = Icons.Filled.SolarPower,
                label = "Solar Units Generated",
                units = formatUnits(solarWh),
                sinceText = "Since ${formatSince(solarSince)}",
                onReset = { confirmReset = "solar" }
            )

            EnergyCard(
                icon = Icons.Filled.ElectricalServices,
                label = "Grid (K-Electric) Units Used",
                units = formatUnits(gridWh),
                sinceText = "Since ${formatSince(gridSince)}",
                onReset = { confirmReset = "grid" }
            )

            Button(onClick = onViewHistory, modifier = Modifier.fillMaxWidth()) {
                Text("View Daily History")
            }
        }
    }

    if (confirmReset != null) {
        val isSolar = confirmReset == "solar"
        AlertDialog(
            onDismissRequest = { confirmReset = null },
            title = { Text(if (isSolar) "Solar counter reset karein?" else "Grid counter reset karein?") },
            text = { Text("Yeh counter wapas 0 pe chala jaye ga. Yeh sirf app k andar wala hisaab hai, inverter ki koi setting nahi badlegi.") },
            confirmButton = {
                TextButton(onClick = {
                    if (isSolar) onResetSolar() else onResetGrid()
                    confirmReset = null
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EnergyCard(
    icon: ImageVector,
    label: String,
    units: String,
    sinceText: String,
    onReset: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("$units kWh", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(sinceText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onReset) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Reset")
            }
        }
    }
}
