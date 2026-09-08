package com.evcs.favorites.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.data.routing.EnergyWaypoint
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.DarkOutline
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.EmeraldPrimaryLight
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusMaintaining
import kotlin.math.roundToInt

/**
 * Energy Corridor Bar component rendering a high-contrast visual trajectory
 * of battery depletion across trip legs and replenishment jumps at scheduled charging stops.
 */
@Composable
fun EnergyCorridorBar(
    energyProfile: List<EnergyWaypoint>,
    totalDistanceKm: Double,
    modifier: Modifier = Modifier
) {
    if (energyProfile.isEmpty() || totalDistanceKm <= 0.0) return

    val startSoc = energyProfile.firstOrNull()?.batteryPercent ?: 100
    val endSoc = energyProfile.lastOrNull()?.batteryPercent ?: 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkCardBackground, RoundedCornerShape(12.dp))
            .border(1.dp, DarkOutline, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(EmeraldPrimary, CircleShape)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "HÀNH LANG NĂNG LƯỢNG (SoC)",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = EmeraldPrimaryLight
                )
            }

            Text(
                text = "$startSoc% ➔ $endSoc%",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = if (endSoc >= 20) EmeraldPrimaryLight else StatusMaintaining
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Visual Canvas Curve
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            val gradientBrush = remember {
                Brush.verticalGradient(
                    colors = listOf(
                        EmeraldPrimary.copy(alpha = 0.35f),
                        Color.Transparent
                    )
                )
            }
            val bufferLineColor = remember { StatusBusy.copy(alpha = 0.4f) }
            val darkCircleBg = remember { Color(0xFF0F172A) }

            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .drawWithCache {
                        val width = size.width
                        val height = size.height
                        val bufferY = height * (1f - (20f / 100f))

                        val points = calculateCorridorPoints(
                            energyProfile = energyProfile,
                            totalDistanceKm = totalDistanceKm,
                            width = width,
                            height = height
                        )

                        val fillPath = if (points.size >= 2) {
                            Path().apply {
                                moveTo(points.first().x, height)
                                for (p in points) {
                                    lineTo(p.x, p.y)
                                }
                                lineTo(points.last().x, height)
                                close()
                            }
                        } else null

                        onDrawBehind {
                            // 1. Draw 20% safe buffer threshold dashed line
                            drawLine(
                                color = bufferLineColor,
                                start = Offset(0f, bufferY),
                                end = Offset(width, bufferY),
                                strokeWidth = 2f
                            )

                            if (points.size >= 2 && fillPath != null) {
                                // 2. Fill area under curve
                                drawPath(
                                    path = fillPath,
                                    brush = gradientBrush
                                )

                                // 3. Draw line segments
                                for (i in 1 until points.size) {
                                    val prev = points[i - 1]
                                    val curr = points[i]
                                    val isJump = curr.isChargingStop

                                    val lineColor = if (isJump) {
                                        EmeraldPrimaryLight
                                    } else {
                                        if (curr.batteryPercent < 20) StatusBusy else EmeraldPrimary
                                    }

                                    drawLine(
                                        color = lineColor,
                                        start = Offset(prev.x, prev.y),
                                        end = Offset(curr.x, curr.y),
                                        strokeWidth = if (isJump) 4f else 3f,
                                        cap = StrokeCap.Round
                                    )
                                }

                                // 4. Draw waypoint markers
                                for (p in points) {
                                    val markerColor = when {
                                        p.isChargingStop -> EmeraldPrimaryLight
                                        p.batteryPercent < 20 -> StatusBusy
                                        else -> EmeraldPrimary
                                    }

                                    drawCircle(
                                        color = darkCircleBg,
                                        radius = 6f,
                                        center = Offset(p.x, p.y)
                                    )
                                    drawCircle(
                                        color = markerColor,
                                        radius = 4f,
                                        center = Offset(p.x, p.y)
                                    )
                                }
                            }
                        }
                    }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Legend & Threshold Indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "0 km (Khởi hành)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "⚡ Sạc nạp pin",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = EmeraldPrimaryLight
            )

            Text(
                text = "${totalDistanceKm.roundToInt()} km (Đích)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Geometric projection of an [EnergyWaypoint] onto the visual Canvas coordinate space.
 */
data class CorridorPointGeometry(
    val x: Float,
    val y: Float,
    val waypoint: EnergyWaypoint,
    val isChargingStop: Boolean,
    val batteryPercent: Int
)

/**
 * Maps route energy waypoints to visual Canvas coordinates.
 * Pure calculation separated from drawing phase to enable zero allocations during canvas draw passes.
 */
fun calculateCorridorPoints(
    energyProfile: List<EnergyWaypoint>,
    totalDistanceKm: Double,
    width: Float,
    height: Float
): List<CorridorPointGeometry> {
    if (energyProfile.isEmpty() || totalDistanceKm <= 0.0 || width <= 0f || height <= 0f) {
        return emptyList()
    }
    return energyProfile.map { wp ->
        val x = ((wp.distanceKm / totalDistanceKm).coerceIn(0.0, 1.0) * width).toFloat()
        val y = height * (1f - (wp.batteryPercent.toFloat() / 100f).coerceIn(0f, 1f))
        CorridorPointGeometry(
            x = x,
            y = y,
            waypoint = wp,
            isChargingStop = wp.isChargingStop,
            batteryPercent = wp.batteryPercent
        )
    }
}
