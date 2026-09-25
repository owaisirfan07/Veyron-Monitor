package com.veyronmonitor.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SolarPower
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val SolarColor = Color(0xFFFACC15)
private val BatteryColor = Color(0xFF22C55E)
private val DischargeColor = Color(0xFFF59E0B)
private val GridColor = Color(0xFF3B82F6)
private val HouseColor = Color(0xFF9CA3AF)
private val IdleColor = Color(0xFF475569)

private const val ACTIVE_THRESHOLD_W = 10.0

/**
 * Home-energy flow diagram: Solar feeds into a central inverter, which in
 * turn charges/draws from the Battery, imports from the Grid, and powers
 * the House -- each connection only animates (flowing dashes) when real
 * power is actually moving along it right now.
 */
@Composable
fun EnergyFlowDiagram(
    solarWatts: Double,
    batteryPercent: Int?,
    batteryChargingWatts: Double,
    batteryDischargingWatts: Double,
    gridWatts: Double,
    houseWatts: Double
) {
    val infiniteTransition = rememberInfiniteTransition(label = "flow")
    val dashPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -48f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "dashPhase"
    )

    val solarActive = solarWatts > ACTIVE_THRESHOLD_W
    val chargingActive = batteryChargingWatts > ACTIVE_THRESHOLD_W
    val dischargingActive = batteryDischargingWatts > ACTIVE_THRESHOLD_W
    val gridActive = gridWatts > ACTIVE_THRESHOLD_W
    val houseActive = houseWatts > ACTIVE_THRESHOLD_W

    val density = LocalDensity.current
    val diagramHeight = 230.dp

    Box(modifier = Modifier.fillMaxWidth().height(diagramHeight)) {
        // ---- Connecting lines, drawn first so icons sit on top ----
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val solarPos = Offset(w * 0.5f, h * 0.10f)
            val centerPos = Offset(w * 0.5f, h * 0.46f)
            val batteryPos = Offset(w * 0.16f, h * 0.92f)
            val gridPos = Offset(w * 0.5f, h * 0.92f)
            val housePos = Offset(w * 0.84f, h * 0.92f)
            val strokeWidth = with(density) { 3.5.dp.toPx() }

            fun flow(from: Offset, to: Offset, active: Boolean, color: Color) {
                drawLine(
                    color = if (active) color else IdleColor.copy(alpha = 0.35f),
                    start = from,
                    end = to,
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                    pathEffect = if (active) {
                        PathEffect.dashPathEffect(floatArrayOf(16f, 12f), dashPhase)
                    } else {
                        PathEffect.dashPathEffect(floatArrayOf(16f, 12f), 0f)
                    }
                )
            }

            flow(solarPos, centerPos, solarActive, SolarColor)
            // Battery: charging flows inverter->battery, discharging flows battery->inverter.
            // Only one direction is normally active at a time.
            flow(centerPos, batteryPos, chargingActive, BatteryColor)
            flow(batteryPos, centerPos, dischargingActive && !chargingActive, DischargeColor)
            flow(gridPos, centerPos, gridActive, GridColor)
            flow(centerPos, housePos, houseActive, HouseColor)
        }

        // ---- Solar node ----
        FlowNode(
            modifier = Modifier.align(Alignment.TopCenter),
            icon = Icons.Filled.SolarPower,
            color = SolarColor,
            label = "Solar",
            value = "${solarWatts.toInt()} W"
        )

        // ---- Center inverter dot ----
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -(diagramHeight * 0.04f))
                .size(14.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )

        // ---- Battery / Grid / House row ----
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BatteryNode(
                percent = batteryPercent,
                isCharging = chargingActive,
                watts = if (chargingActive) batteryChargingWatts else batteryDischargingWatts
            )
            FlowNode(
                icon = Icons.Filled.ElectricalServices,
                color = GridColor,
                label = "Grid",
                value = "${gridWatts.toInt()} W"
            )
            FlowNode(
                icon = Icons.Filled.Home,
                color = HouseColor,
                label = "House",
                value = "${houseWatts.toInt()} W"
            )
        }
    }
}

@Composable
private fun FlowNode(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    color: Color,
    label: String,
    value: String
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BatteryNode(percent: Int?, isCharging: Boolean, watts: Double) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { (percent ?: 0) / 100f },
                modifier = Modifier.fillMaxSize(),
                color = BatteryColor,
                trackColor = BatteryColor.copy(alpha = 0.15f),
                strokeWidth = 4.dp
            )
            Icon(Icons.Filled.BatteryChargingFull, contentDescription = "Battery", tint = BatteryColor, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (percent != null) "$percent%" else "--",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (watts > ACTIVE_THRESHOLD_W) "${watts.toInt()} W" else "Idle",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
