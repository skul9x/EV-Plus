package com.evcs.favorites.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import com.evcs.favorites.ui.theme.AppIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.data.model.PowerPort
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.util.DebounceHelper
import com.evcs.favorites.data.repository.EvcsRepository
import com.evcs.favorites.data.routing.DrivingMetrics
import com.evcs.favorites.data.routing.RoutingEngineType
import com.evcs.favorites.data.routing.TrafficCondition
import com.evcs.favorites.domain.location.formattedDistance
import com.evcs.favorites.ui.theme.AutomotiveDimens
import com.evcs.favorites.ui.theme.CAR_BUTTON_HEIGHT
import com.evcs.favorites.ui.theme.CAR_CARD_MIN_HEIGHT
import com.evcs.favorites.ui.theme.CAR_HERO_METRIC_TEXT_SIZE
import com.evcs.favorites.ui.theme.DarkOutline
import com.evcs.favorites.ui.theme.DarkOnSurfaceVariant
import com.evcs.favorites.ui.theme.DistancePillBg
import com.evcs.favorites.ui.theme.DistancePillBorder
import com.evcs.favorites.ui.theme.DistancePillText
import com.evcs.favorites.ui.theme.ElectricCyan
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusAvailableContainer
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusBusyContainer
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusMaintainingContainer
import com.evcs.favorites.ui.theme.StatusOffline
import com.evcs.favorites.ui.theme.StatusOfflineContainer
import com.evcs.favorites.ui.theme.UltraPurple
import java.util.Locale


/**
 * Individual charging station card displaying real-time power metrics,
 * live slot availability, distance pill, and 1-tap navigation button.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun StationCard(
    station: Station,
    onNavigateClick: (Station) -> Unit,
    onRemoveFavoriteClick: ((Station) -> Unit)? = null,
    onFavoriteClick: ((Station) -> Unit)? = null,
    isFavorite: Boolean = false,
    isToggleInProgress: Boolean = false,
    onStationClick: (Station) -> Unit = {},
    isSelected: Boolean = false,
    isCarMode: Boolean = false,
    isCompact: Boolean = false,
    filterDcOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AutomotiveDimens.CAR_CARD_MIN_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable { onStationClick(station) },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected && isCompact) EmeraldPrimary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 2.dp)
    ) {
        if (isCompact) {
            CompactStationCardContent(station = station, filterDcOnly = filterDcOnly)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
            // In car / landscape mode, display prominent 24sp Bold Hero Metric
            if (isCarMode) {
                val heroMetricText = remember(station.depotStatus, station.totalAvailablePlugs, station.totalPlugs) {
                    StationCardHelper.formatHeroMetric(
                        totalAvailablePlugs = station.totalAvailablePlugs,
                        totalPlugs = station.totalPlugs,
                        depotStatus = station.depotStatus
                    )
                }
                Text(
                    text = heroMetricText,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = StationCardHelper.HERO_METRIC_FONT_SIZE,
                        fontWeight = StationCardHelper.HERO_METRIC_FONT_WEIGHT
                    ),
                    color = if (station.totalAvailablePlugs > 0) EmeraldPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // Header Row: Station Name + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .basicMarquee(
                            iterations = 2,
                            delayMillis = 2000,
                            velocity = 30.dp
                        )
                )

                Spacer(modifier = Modifier.width(8.dp))

                StatusBadge(
                    depotStatus = station.depotStatus,
                    totalAvailablePlugs = station.totalAvailablePlugs,
                    totalPlugs = station.totalPlugs
                )
            }

            val powerDistribution = remember(station.powers, station.connectors, filterDcOnly) {
                StationCardHelper.formatPowerDistributionSummary(station.powers, station.connectors, filterDcOnly)
            }
            if (powerDistribution.isNotEmpty()) {
                val onSurface = MaterialTheme.colorScheme.onSurface
                val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                val powerSummaryAnnotated = remember(powerDistribution, onSurface, dividerColor) {
                    StationCardHelper.buildPowerDistributionAnnotatedString(
                        distribution = powerDistribution,
                        powerColor = onSurface,
                        countColor = EmeraldPrimary,
                        separatorColor = dividerColor
                    )
                }
                Text(
                    text = powerSummaryAnnotated,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 4.dp)
                        .basicMarquee(
                            iterations = 2,
                            delayMillis = 2000,
                            velocity = 30.dp
                        )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sub-header FlowRow: Rich Journey Badge + Parking / Working Time
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val journeyBadgeInfo = remember(station.drivingMetrics, station.distanceKm) {
                    formatJourneyBadge(station.drivingMetrics, station.distanceKm)
                }
                if (journeyBadgeInfo.text.isNotBlank()) {
                    JourneyBadge(badgeInfo = journeyBadgeInfo)
                }

                val workingTimeText = remember(station.isFreeParking, station.workingTimeDescription) {
                    if (station.isFreeParking) "Mở ${station.workingTimeDescription} • Miễn phí gửi xe"
                    else "Mở ${station.workingTimeDescription} • Gửi xe có phí"
                }

                // Working time & Parking pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = workingTimeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Address Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = station.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Connector chips (e.g. live availability "✧ 120kW: trống 1/4" or baseline tags "✧ 30kW")
            val displayPowers = remember(station.powers, station.connectors) {
                if (station.powers.isNotEmpty()) {
                    station.powers
                } else if (station.connectors.isNotBlank()) {
                    EvcsRepository.parseConnectorsToPowers(station.connectors)
                } else {
                    emptyList()
                }
            }

            if (displayPowers.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    displayPowers.forEach { powerPort ->
                        WattageChip(powerPort = powerPort)
                    }
                }
            } else if (station.connectors.isNotBlank()) {
                val fallbackConnectorsText = remember(station.connectors) {
                    "Cổng sạc: ${station.connectors}"
                }
                // Fallback basic connectors list
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Bolt,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = fallbackConnectorsText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Row: 1-Tap "Chỉ đường" Button + Heart Favorite or Trash Action
            val navDebounce = remember { DebounceHelper(1000L) }
            val buttonHeight = StationCardHelper.resolveButtonHeight(isCarMode)
            val iconButtonSize = if (isCarMode) AutomotiveDimens.CAR_BUTTON_HEIGHT else 48.dp

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { navDebounce.runIfAllowed { onNavigateClick(station) } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(buttonHeight)
                ) {
                    Icon(
                        imageVector = AppIcons.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(if (isCarMode) 22.dp else 18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Chỉ đường",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCarMode) 16.sp else 14.sp
                        )
                    )
                }

                if (onFavoriteClick != null) {
                    Spacer(modifier = Modifier.width(8.dp))

                    val heartState = remember(isFavorite) {
                        NearbyUiHelper.resolveFavoriteIconState(isFavorite)
                    }
                    IconButton(
                        onClick = { if (!isToggleInProgress) onFavoriteClick(station) },
                        enabled = !isToggleInProgress,
                        modifier = Modifier
                            .size(iconButtonSize)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isToggleInProgress) 0.25f else 0.5f))
                    ) {
                        Icon(
                            imageVector = heartState.icon,
                            contentDescription = heartState.contentDescription,
                            tint = if (isToggleInProgress) heartState.tintColor.copy(alpha = 0.4f) else heartState.tintColor,
                            modifier = Modifier.size(if (isCarMode) 28.dp else 24.dp)
                        )
                    }
                } else if (onRemoveFavoriteClick != null) {
                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { if (!isToggleInProgress) onRemoveFavoriteClick(station) },
                        enabled = !isToggleInProgress,
                        modifier = Modifier
                            .size(iconButtonSize)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (isToggleInProgress) 0.25f else 0.5f))
                    ) {
                        Icon(
                            imageVector = AppIcons.DeleteOutline,
                            contentDescription = "Xóa yêu thích",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isToggleInProgress) 0.3f else 0.7f),
                            modifier = Modifier.size(if (isCarMode) 24.dp else 20.dp)
                        )
                    }
                }
            }
        }
    }
}
}

/**
 * Compact horizontal Station Card content designed for Automotive Landscape Master List.
 * Highlights station name, journey ETA/distance, and live available plug status pill.
 * Height is constrained (~76dp) to allow 3-4 stations simultaneously visible on automotive displays.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactStationCardContent(
    station: Station,
    filterDcOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = true),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.basicMarquee(
                    iterations = 2,
                    delayMillis = 2000,
                    velocity = 30.dp
                )
            )

            val powerDistribution = remember(station.powers, station.connectors, filterDcOnly) {
                StationCardHelper.formatPowerDistributionSummary(station.powers, station.connectors, filterDcOnly)
            }
            if (powerDistribution.isNotEmpty()) {
                val onSurface = MaterialTheme.colorScheme.onSurface
                val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                val powerSummaryAnnotated = remember(powerDistribution, onSurface, dividerColor) {
                    StationCardHelper.buildPowerDistributionAnnotatedString(
                        distribution = powerDistribution,
                        powerColor = onSurface,
                        countColor = EmeraldPrimary,
                        separatorColor = dividerColor
                    )
                }
                Text(
                    text = powerSummaryAnnotated,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.basicMarquee(
                        iterations = 2,
                        delayMillis = 2000,
                        velocity = 30.dp
                    )
                )
            }

            val journeyBadgeInfo = remember(station.drivingMetrics, station.distanceKm) {
                formatJourneyBadge(station.drivingMetrics, station.distanceKm)
            }
            val subtitleText = when {
                journeyBadgeInfo.text.isNotBlank() -> journeyBadgeInfo.text
                station.distanceKm != null -> String.format(Locale.US, "%.1f km", station.distanceKm)
                else -> "VinFast EVCS"
            }

            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp
                ),
                color = if (journeyBadgeInfo.text.isNotBlank() && journeyBadgeInfo.contentColor != DarkOnSurfaceVariant) {
                    journeyBadgeInfo.contentColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        CompactAvailabilityBadge(station = station)
    }
}

/**
 * Compact pill badge indicating plug availability for automotive master list.
 */
@Composable
fun CompactAvailabilityBadge(
    station: Station,
    modifier: Modifier = Modifier
) {
    val (label, bg, textCol) = when {
        station.depotStatus.equals("Maintaining", ignoreCase = true) -> Triple("BẢO TRÌ", StatusMaintainingContainer, StatusMaintaining)
        station.depotStatus.equals("OutOfService", ignoreCase = true) -> Triple("TẠM DỪNG", StatusOfflineContainer, StatusOffline)
        station.totalPlugs > 0 && station.totalAvailablePlugs == 0 -> Triple("0/${station.totalPlugs} TRỐNG", StatusBusyContainer, StatusBusy)
        station.totalPlugs > 0 -> Triple("${station.totalAvailablePlugs}/${station.totalPlugs} TRỐNG", StatusAvailableContainer, StatusAvailable)
        station.totalAvailablePlugs > 0 -> Triple("${station.totalAvailablePlugs} TRỐNG", StatusAvailableContainer, StatusAvailable)
        else -> Triple("SẴN SÀNG", StatusAvailableContainer, StatusAvailable)
    }

    Surface(
        modifier = modifier.wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, textCol.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(textCol)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                color = textCol,
                maxLines = 1
            )
        }
    }
}

/**
 * Visual model describing the ETA / distance journey badge for a station.
 */
@Immutable
data class JourneyBadgeInfo(
    val text: String,
    val contentColor: Color,
    val containerColor: Color,
    val borderColor: Color = contentColor.copy(alpha = 0.5f),
    val tier: RoutingEngineType = RoutingEngineType.HAVERSINE,
    val trafficCondition: TrafficCondition? = null
) {
    val textColor: Color get() = contentColor
    val backgroundColor: Color get() = containerColor
}

@Immutable
private data class TrafficBadgeDetails(
    val label: String,
    val color: Color,
    val containerColor: Color,
    val condition: TrafficCondition
)

/**
 * Resolves presentation details for the journey badge based on driving metrics and distance.
 *
 * Tier 1 (Google Routes with Live Traffic):
 *   - Text format: `🚗 {minutes} phút • {distanceKm} km • {Traffic Label}`
 *   - 🟢 `Thông thoáng` (`#10B981`): Delay ratio R < 1.15
 *   - 🟡 `Kẹt xe vừa` (`#F59E0B`): Delay ratio 1.15 <= R < 1.35
 *   - 🔴 `Ùn tắc` (`#EF4444`): Delay ratio R >= 1.35
 *
 * Tier 2 (OSRM Table Service):
 *   - Text format: `🚗 {minutes} phút • {distanceKm} km • Đường bộ`
 *   - Neutral cyan/blue styling (`#06B6D4`)
 *
 * Tier 3 (Haversine Baseline):
 *   - Text format: `⚡ {distanceKm} km • Đường thẳng`
 *   - Subtle neutral styling
 */
fun formatJourneyBadge(
    metrics: DrivingMetrics?,
    distanceKm: Double?
): JourneyBadgeInfo {
    if (metrics == null && distanceKm == null) {
        return JourneyBadgeInfo(
            text = "",
            contentColor = DarkOnSurfaceVariant,
            containerColor = Color(0x1A94A3B8),
            borderColor = DarkOutline.copy(alpha = 0.3f),
            tier = RoutingEngineType.HAVERSINE,
            trafficCondition = null
        )
    }

    val engine = metrics?.engineUsed ?: RoutingEngineType.HAVERSINE

    return when (engine) {
        RoutingEngineType.GOOGLE -> {
            val minutes = if (metrics!!.durationSeconds <= 0L) 0L else maxOf(1L, (metrics.durationSeconds + 30) / 60)
            val distKm = if (metrics.distanceMeters > 0L) {
                metrics.distanceMeters / 1000.0
            } else {
                distanceKm ?: 0.0
            }
            val distStr = String.format(Locale.US, "%.1f", distKm)

            val details = when {
                metrics.staticDurationSeconds != null && metrics.staticDurationSeconds > 0L -> {
                    val ratio = metrics.durationSeconds.toDouble() / metrics.staticDurationSeconds.toDouble()
                    when {
                        ratio >= 1.35 -> TrafficBadgeDetails("Ùn tắc", Color(0xFFEF4444), Color(0x26EF4444), TrafficCondition.HEAVY_CONGESTION)
                        ratio >= 1.15 -> TrafficBadgeDetails("Kẹt xe vừa", Color(0xFFF59E0B), Color(0x26F59E0B), TrafficCondition.MODERATE_CONGESTION)
                        else -> TrafficBadgeDetails("Thông thoáng", Color(0xFF10B981), Color(0x2610B981), TrafficCondition.FREE_FLOW)
                    }
                }
                metrics.trafficCondition == TrafficCondition.HEAVY_CONGESTION -> {
                    TrafficBadgeDetails("Ùn tắc", Color(0xFFEF4444), Color(0x26EF4444), TrafficCondition.HEAVY_CONGESTION)
                }
                metrics.trafficCondition == TrafficCondition.MODERATE_CONGESTION -> {
                    TrafficBadgeDetails("Kẹt xe vừa", Color(0xFFF59E0B), Color(0x26F59E0B), TrafficCondition.MODERATE_CONGESTION)
                }
                metrics.trafficCondition == TrafficCondition.FREE_FLOW -> {
                    TrafficBadgeDetails("Thông thoáng", Color(0xFF10B981), Color(0x2610B981), TrafficCondition.FREE_FLOW)
                }
                else -> {
                    val computed = TrafficCondition.computeCondition(
                        durationSeconds = metrics.durationSeconds,
                        staticDurationSeconds = metrics.staticDurationSeconds,
                        distanceMeters = metrics.distanceMeters
                    )
                    when (computed) {
                        TrafficCondition.HEAVY_CONGESTION -> TrafficBadgeDetails("Ùn tắc", Color(0xFFEF4444), Color(0x26EF4444), computed)
                        TrafficCondition.MODERATE_CONGESTION -> TrafficBadgeDetails("Kẹt xe vừa", Color(0xFFF59E0B), Color(0x26F59E0B), computed)
                        else -> TrafficBadgeDetails("Thông thoáng", Color(0xFF10B981), Color(0x2610B981), TrafficCondition.FREE_FLOW)
                    }
                }
            }

            JourneyBadgeInfo(
                text = "🚗 $minutes phút • $distStr km • ${details.label}",
                contentColor = details.color,
                containerColor = details.containerColor,
                borderColor = details.color.copy(alpha = 0.6f),
                tier = RoutingEngineType.GOOGLE,
                trafficCondition = details.condition
            )
        }

        RoutingEngineType.OSRM -> {
            val minutes = if (metrics!!.durationSeconds <= 0L) 0L else maxOf(1L, (metrics.durationSeconds + 30) / 60)
            val distKm = if (metrics.distanceMeters > 0L) {
                metrics.distanceMeters / 1000.0
            } else {
                distanceKm ?: 0.0
            }
            val distStr = String.format(Locale.US, "%.1f", distKm)

            val osrmColor = Color(0xFF06B6D4) // ElectricCyan
            val osrmContainer = Color(0x2606B6D4)

            JourneyBadgeInfo(
                text = "🚗 $minutes phút • $distStr km • Đường bộ",
                contentColor = osrmColor,
                containerColor = osrmContainer,
                borderColor = osrmColor.copy(alpha = 0.6f),
                tier = RoutingEngineType.OSRM,
                trafficCondition = null
            )
        }

        RoutingEngineType.HAVERSINE -> {
            val distKm = distanceKm ?: (metrics?.distanceMeters?.div(1000.0) ?: 0.0)
            val distStr = String.format(Locale.US, "%.1f", distKm)

            val haversineColor = Color(0xFF94A3B8)
            val haversineContainer = Color(0x1A94A3B8)

            JourneyBadgeInfo(
                text = "⚡ $distStr km • Đường thẳng",
                contentColor = haversineColor,
                containerColor = haversineContainer,
                borderColor = haversineColor.copy(alpha = 0.35f),
                tier = RoutingEngineType.HAVERSINE,
                trafficCondition = null
            )
        }
    }
}

/**
 * Rich journey badge displaying driving duration (ETA), distance, and traffic condition or fallback straight line.
 */
@Composable
fun JourneyBadge(
    badgeInfo: JourneyBadgeInfo,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(badgeInfo.containerColor)
            .border(1.dp, badgeInfo.borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = badgeInfo.text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = badgeInfo.contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Distance pill showing e.g. "⚡ 1.4 km" or "⚡ 850 m".
 */
@Composable
fun DistanceBadge(
    distanceText: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DistancePillBg)
            .border(1.dp, DistancePillBorder.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = AppIcons.Bolt,
            contentDescription = null,
            tint = DistancePillText,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = distanceText,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = DistancePillText
        )
    }
}

/**
 * Real-time connector wattage chip with plug availability color-coding.
 * Shows live telemetry ("✧ 120kW: trống 1/4") or baseline specification ("✧ 30kW").
 */
@Composable
fun WattageChip(
    powerPort: PowerPort,
    modifier: Modifier = Modifier
) {
    val hasLive = powerPort.hasLiveTelemetry
    val isAvailable = hasLive && powerPort.availablePlugs > 0
    val isFull = hasLive && powerPort.availablePlugs == 0

    val (badgeBg, badgeBorder, badgeText) = when {
        !hasLive -> Triple(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        isAvailable -> Triple(
            StatusAvailableContainer,
            StatusAvailable.copy(alpha = 0.7f),
            StatusAvailable
        )
        isFull -> Triple(
            StatusBusyContainer,
            StatusBusy.copy(alpha = 0.7f),
            StatusBusy
        )
        else -> Triple(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(badgeBg)
            .border(1.dp, badgeBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "✧",
            color = if (powerPort.typeWatts >= 120000L) UltraPurple else badgeText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = powerPort.chipDisplayString,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium
            ),
            color = badgeText
        )
    }
}

/**
 * Resolved representation of status badge appearance and semantic meaning.
 */
@Immutable
data class StatusBadgeModel(
    val label: String,
    val dotColor: Color,
    val containerColor: Color
)

/**
 * Resolves status badge information:
 * - "Bảo trì" when depotStatus is Maintaining
 * - "Tạm dừng" when depotStatus is OutOfService
 * - "Hoạt động" or "Đã lưu" when totalPlugs == 0 (never "Hết cổng" if unverified)
 * - "Hết cổng" when live totalPlugs > 0 and totalAvailablePlugs == 0 (Red #EF4444)
 * - "Hoạt động" when totalAvailablePlugs > 0 (Green #10B981)
 */
fun resolveStatusBadge(
    depotStatus: String,
    totalAvailablePlugs: Int,
    totalPlugs: Int = 0
): StatusBadgeModel {
    val (statusLabel, dotColor, containerColor) = when {
        depotStatus.equals("Maintaining", ignoreCase = true) -> Triple(
            "Bảo trì",
            StatusMaintaining,
            StatusMaintainingContainer
        )
        depotStatus.equals("OutOfService", ignoreCase = true) -> Triple(
            "Tạm dừng",
            StatusOffline,
            StatusOfflineContainer
        )
        totalPlugs == 0 -> {
            if (depotStatus.equals("Normal", ignoreCase = true)) {
                Triple(
                    "Hoạt động",
                    StatusAvailable,
                    StatusAvailableContainer
                )
            } else {
                Triple(
                    "Đã lưu",
                    ElectricCyan,
                    Color(0x2606B6D4)
                )
            }
        }
        totalPlugs > 0 && totalAvailablePlugs == 0 -> {
            Triple(
                "Hết cổng",
                StatusBusy,
                StatusBusyContainer
            )
        }
        totalAvailablePlugs > 0 || depotStatus.equals("Normal", ignoreCase = true) -> Triple(
            "Hoạt động",
            StatusAvailable,
            StatusAvailableContainer
        )
        else -> Triple(
            "Đã lưu",
            ElectricCyan,
            Color(0x2606B6D4)
        )
    }
    return StatusBadgeModel(statusLabel, dotColor, containerColor)
}

/**
 * Status badge indicating station readiness (Available, Maintaining, OutOfService, Saved, Sắp trống).
 * Uses AnimatedContent for smooth crossfade transitions between states.
 */
@Composable
fun StatusBadge(
    depotStatus: String,
    totalAvailablePlugs: Int,
    totalPlugs: Int = 0,
    modifier: Modifier = Modifier
) {
    val badge = remember(depotStatus, totalAvailablePlugs, totalPlugs) {
        resolveStatusBadge(depotStatus, totalAvailablePlugs, totalPlugs)
    }

    AnimatedContent(
        targetState = badge,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "StatusBadgeCrossfade",
        modifier = modifier
    ) { targetBadge ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(targetBadge.containerColor)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(targetBadge.dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = targetBadge.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = targetBadge.dotColor
            )
        }
    }
}
