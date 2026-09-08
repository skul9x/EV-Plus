package com.evcs.favorites.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.evcs.favorites.data.locations.AdministrativeDistrict
import com.evcs.favorites.data.routing.DeadZoneWarning
import com.evcs.favorites.data.routing.EvRouteStop
import com.evcs.favorites.data.routing.EvSmartRoutePlan
import com.evcs.favorites.data.routing.RouteSessionData
import com.evcs.favorites.data.routing.StopAvailabilityStatus
import com.evcs.favorites.ui.components.EnergyCorridorBar
import com.evcs.favorites.ui.components.SwapStationBottomSheet
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.DarkOutline
import com.evcs.favorites.ui.theme.DarkSurface
import com.evcs.favorites.ui.theme.DarkSurfaceVariant
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.EmeraldPrimaryLight
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusBusy
import com.evcs.favorites.ui.theme.StatusMaintaining
import kotlin.math.roundToInt

/**
 * Complete Route Planning and EV Navigation screen.
 * Supports EV range input sliders, origin/destination dropdowns with 1-tap GPS [🎯],
 * Dead-Zone alert banner, Energy Corridor Bar, and Vertical Timeline of charging stops with
 * station swap bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteScreen(
    viewModel: RouteViewModel,
    isLandscape: Boolean = false,
    onStartNavigation: ((RouteSessionData) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val onStartNavClick: () -> Unit = {
        val session = viewModel.buildRouteSession()
        if (session != null) {
            if (onStartNavigation != null) {
                onStartNavigation(session)
            } else {
                com.evcs.favorites.car.CarNavigationDispatcher.dispatchRouteNavigation(context, session)
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Left Column: Parameters & Origin/Destination Selectors
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            VehicleConfigurationCard(
                                safeRangeKm = uiState.safeRangeKm,
                                onSafeRangeChanged = { viewModel.onSafeRangeChanged(it) },
                                startBatteryPercent = uiState.startBatteryPercent,
                                onStartBatteryPercentChanged = { viewModel.onStartBatteryPercentChanged(it) }
                            )
                        }

                        item {
                            OriginDestinationCard(
                                uiState = uiState,
                                onOriginProvinceSelected = { viewModel.onOriginProvinceSelected(it) },
                                onOriginDistrictSelected = { viewModel.onOriginDistrictSelected(it) },
                                onDestinationProvinceSelected = { viewModel.onDestinationProvinceSelected(it) },
                                onDestinationDistrictSelected = { viewModel.onDestinationDistrictSelected(it) },
                                onUseCurrentGps = { viewModel.useCurrentGpsLocation() },
                                onSwap = { viewModel.swapOriginAndDestination() }
                            )
                        }

                        item {
                            PlanRouteCtaButton(
                                isLoading = uiState.isLoading,
                                onClick = { viewModel.planRoute() }
                            )
                        }
                    }
                }

                // Right Column: Corridor Results & Stop Timeline
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                ) {
                    RouteResultsSection(
                        uiState = uiState,
                        onSwapStationClick = { stop -> viewModel.selectStopForSwap(stop) },
                        onStartNavigationClick = onStartNavClick
                    )
                }
            }
        } else {
            // Portrait Layout
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    VehicleConfigurationCard(
                        safeRangeKm = uiState.safeRangeKm,
                        onSafeRangeChanged = { viewModel.onSafeRangeChanged(it) },
                        startBatteryPercent = uiState.startBatteryPercent,
                        onStartBatteryPercentChanged = { viewModel.onStartBatteryPercentChanged(it) }
                    )
                }

                item {
                    OriginDestinationCard(
                        uiState = uiState,
                        onOriginProvinceSelected = { viewModel.onOriginProvinceSelected(it) },
                        onOriginDistrictSelected = { viewModel.onOriginDistrictSelected(it) },
                        onDestinationProvinceSelected = { viewModel.onDestinationProvinceSelected(it) },
                        onDestinationDistrictSelected = { viewModel.onDestinationDistrictSelected(it) },
                        onUseCurrentGps = { viewModel.useCurrentGpsLocation() },
                        onSwap = { viewModel.swapOriginAndDestination() }
                    )
                }

                item {
                    PlanRouteCtaButton(
                        isLoading = uiState.isLoading,
                        onClick = { viewModel.planRoute() }
                    )
                }

                item {
                    RouteResultsSection(
                        uiState = uiState,
                        onSwapStationClick = { stop -> viewModel.selectStopForSwap(stop) },
                        onStartNavigationClick = onStartNavClick
                    )
                }
            }
        }

        // Swap Station Modal Bottom Sheet
        uiState.selectedStopForSwap?.let { stop ->
            SwapStationBottomSheet(
                stop = stop,
                onSelectStation = { altStation ->
                    viewModel.swapStation(stop.stopIndex, altStation)
                },
                onDismiss = {
                    viewModel.dismissSwapBottomSheet()
                }
            )
        }
    }
}

/**
 * Card holding vehicle safe range slider and starting SoC slider.
 */
@Composable
fun VehicleConfigurationCard(
    safeRangeKm: Int,
    onSafeRangeChanged: (Int) -> Unit,
    startBatteryPercent: Int,
    onStartBatteryPercentChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var localSafeRange by remember(safeRangeKm) {
        mutableFloatStateOf(safeRangeKm.toFloat())
    }
    var localStartBattery by remember(startBatteryPercent) {
        mutableFloatStateOf(startBatteryPercent.toFloat())
    }

    LaunchedEffect(safeRangeKm) {
        localSafeRange = safeRangeKm.toFloat()
    }
    LaunchedEffect(startBatteryPercent) {
        localStartBattery = startBatteryPercent.toFloat()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "THÔNG SỐ XE ĐIỆN",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = EmeraldPrimaryLight
                )

                Text(
                    text = "${localSafeRange.roundToInt()} km • ${localStartBattery.roundToInt()}% pin",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Slider 1: Safe Range (100 - 500 km, default 200 km)
            Text(
                text = "Tầm vận hành an toàn (100% pin):",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Khai báo số km thực tế xe đi được an toàn ở 100% pin",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = localSafeRange,
                    onValueChange = { localSafeRange = it },
                    onValueChangeFinished = {
                        onSafeRangeChanged(localSafeRange.roundToInt().coerceIn(100, 500))
                    },
                    valueRange = 100f..500f,
                    steps = 7, // Steps of 50 km: 100, 150, 200, 250, 300, 350, 400, 450, 500
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = DarkOutline
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${localSafeRange.roundToInt()} km",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = EmeraldPrimaryLight,
                    modifier = Modifier.width(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Slider 2: Starting SoC (10% - 100%, default 100%)
            Text(
                text = "Mức pin hiện tại lúc khởi hành:",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = localStartBattery,
                    onValueChange = { localStartBattery = it },
                    onValueChangeFinished = {
                        onStartBatteryPercentChanged(localStartBattery.roundToInt().coerceIn(10, 100))
                    },
                    valueRange = 10f..100f,
                    steps = 8, // 10%, 20%, 30%, ..., 100%
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = DarkOutline
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${localStartBattery.roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = EmeraldPrimaryLight,
                    modifier = Modifier.width(64.dp)
                )
            }
        }
    }
}

/**
 * Origin and Destination selection card with Province/District dropdowns,
 * 1-tap GPS location [🎯], and swap button.
 */
@Composable
fun OriginDestinationCard(
    uiState: RouteUiState,
    onOriginProvinceSelected: (String) -> Unit,
    onOriginDistrictSelected: (String) -> Unit,
    onDestinationProvinceSelected: (String) -> Unit,
    onDestinationDistrictSelected: (String) -> Unit,
    onUseCurrentGps: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Origin Row Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(EmeraldPrimary, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ĐIỂM KHỞI HÀNH",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = EmeraldPrimaryLight
                    )
                }

                // 1-tap GPS Button
                OutlinedButton(
                    onClick = onUseCurrentGps,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = EmeraldPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.sizeIn(minHeight = 36.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.MyLocation,
                        contentDescription = "Vị trí hiện tại",
                        modifier = Modifier.size(16.dp),
                        tint = EmeraldPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "🎯 Vị trí hiện tại",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Origin Dropdowns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DropdownSelector(
                    label = "Tỉnh / Thành phố",
                    selectedItem = uiState.originProvince,
                    items = uiState.availableProvinces,
                    onItemSelected = onOriginProvinceSelected,
                    modifier = Modifier.weight(1f)
                )

                DropdownSelector(
                    label = "Quận / Huyện",
                    selectedItem = uiState.originDistrict,
                    items = uiState.originDistrictNames,
                    onItemSelected = onOriginDistrictSelected,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Center Swap Origin <-> Destination Button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onSwap,
                    modifier = Modifier
                        .size(48.dp)
                        .background(DarkSurfaceVariant, CircleShape)
                        .border(1.dp, DarkOutline, CircleShape)
                ) {
                    Icon(
                        imageVector = AppIcons.SwapVert,
                        contentDescription = "Đổi chiều lộ trình",
                        tint = EmeraldPrimaryLight,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Destination Row Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(StatusBusy, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ĐIỂM ĐẾN",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = StatusBusy
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Destination Dropdowns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DropdownSelector(
                    label = "Tỉnh / Thành phố",
                    selectedItem = uiState.destinationProvince,
                    items = uiState.availableProvinces,
                    onItemSelected = onDestinationProvinceSelected,
                    modifier = Modifier.weight(1f)
                )

                DropdownSelector(
                    label = "Quận / Huyện",
                    selectedItem = uiState.destinationDistrict,
                    items = uiState.destinationDistrictNames,
                    onItemSelected = onDestinationDistrictSelected,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Reusable dark-styled dropdown selector for Province and District choices.
 */
@Composable
fun DropdownSelector(
    label: String,
    selectedItem: String,
    items: List<String>,
    onItemSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    val baseTypography = MaterialTheme.typography.bodyMedium
    val selectedStyle = remember(baseTypography) {
        baseTypography.copy(fontWeight = FontWeight.Bold)
    }
    val unselectedStyle = remember(baseTypography) {
        baseTypography.copy(fontWeight = FontWeight.Normal)
    }
    val primaryColor = EmeraldPrimaryLight
    val defaultColor = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface, RoundedCornerShape(8.dp))
                .border(1.dp, DarkOutline, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(min = 48.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedItem.ifBlank { "Chọn..." },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selectedItem.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            scrollState = scrollState,
            modifier = Modifier
                .background(DarkSurface)
                .heightIn(max = 280.dp)
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                key(item) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = item,
                                style = if (isSelected) selectedStyle else unselectedStyle,
                                color = if (isSelected) primaryColor else defaultColor
                            )
                        },
                        onClick = {
                            onItemSelected(item)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * High-visibility "TÌM TRẠM SẠC" primary CTA button.
 */
@Composable
fun PlanRouteCtaButton(
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = !isLoading,
        colors = ButtonDefaults.buttonColors(
            containerColor = EmeraldPrimary,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "ĐANG TÍNH TOÁN LỘ TRÌNH...",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
        } else {
            Icon(
                imageVector = AppIcons.Route,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "TÌM TRẠM SẠC",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

/**
 * High-visibility "BẮT ĐẦU DẪN ĐƯỜNG" primary CTA button for multi-stop EV navigation handoff.
 */
@Composable
fun StartNavigationCtaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = EmeraldPrimary,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
    ) {
        Icon(
            imageVector = AppIcons.NearMe,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "BẮT ĐẦU DẪN ĐƯỜNG",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )
    }
}

/**
 * Route Results section rendering Dead-Zone Warning, Energy Corridor Bar,
 * and the Vertical Timeline of stops.
 */
@Composable
fun RouteResultsSection(
    uiState: RouteUiState,
    onSwapStationClick: (EvRouteStop) -> Unit,
    onStartNavigationClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val plan = uiState.routePlan

    if (uiState.errorMessage != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = StatusBusy.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, StatusBusy.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.ErrorOutline,
                    contentDescription = null,
                    tint = StatusBusy,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = uiState.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
    }

    if (plan != null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Dead-Zone Alert Banner (if gap > safe range)
            plan.deadZoneWarning?.let { warning ->
                DeadZoneAlertCard(warning = warning)
            }

            // 2. Energy Corridor Bar
            EnergyCorridorBar(
                energyProfile = plan.energyProfile,
                totalDistanceKm = plan.totalDistanceKm
            )

            // 3. Vertical Stop Timeline
            RouteTimelineCard(
                plan = plan,
                originLabel = "${uiState.originProvince}, ${uiState.originDistrict}",
                destinationLabel = "${uiState.destinationProvince}, ${uiState.destinationDistrict}",
                startSoc = uiState.startBatteryPercent,
                onSwapStationClick = onSwapStationClick
            )

            // 4. Start Multi-Stop Navigation CTA
            StartNavigationCtaButton(
                onClick = { onStartNavigationClick?.invoke() }
            )
        }
    }
}

/**
 * High-visibility crimson warning card rendered when any route gap exceeds the vehicle's safe range.
 */
@Composable
fun DeadZoneAlertCard(
    warning: DeadZoneWarning,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF450A0A) // Deep crimson
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, StatusBusy)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = AppIcons.Error,
                contentDescription = "Cảnh báo vùng trắng",
                tint = StatusBusy,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "CẢNH BÁO VÙNG TRẮNG TRẠM SẠC",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = StatusBusy
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = warning.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Gợi ý: Sạc 100% trước khi xuất phát, giảm tốc độ chạy xe để tiết kiệm pin hoặc chọn trạm rẽ phụ.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFCA5A5)
                )
            }
        }
    }
}

/**
 * Vertical Timeline Card displaying Origin, intermediate charging stops with live statuses,
 * and Destination with final statistics.
 */
@Composable
fun RouteTimelineCard(
    plan: EvSmartRoutePlan,
    originLabel: String,
    destinationLabel: String,
    startSoc: Int,
    onSwapStationClick: (EvRouteStop) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCardBackground),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header summary
            val drivingHours = (plan.totalDrivingDurationSeconds / 3600).toInt()
            val drivingMinutes = ((plan.totalDrivingDurationSeconds % 3600) / 60).toInt()
            val totalTimeText = if (drivingHours > 0) "${drivingHours}h ${drivingMinutes}p" else "${drivingMinutes}p"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LỘ TRÌNH CHI TIẾT",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = EmeraldPrimaryLight
                )

                Text(
                    text = "${plan.totalDistanceKm.roundToInt()} km • $totalTimeText",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Origin Timeline Node
            TimelineOriginNode(
                originLabel = originLabel,
                startSoc = startSoc
            )

            // 2. Intermediate Charging Stops
            plan.stops.forEach { stop ->
                TimelineConnectorLine()
                TimelineChargingStopNode(
                    stop = stop,
                    onSwapClick = { onSwapStationClick(stop) }
                )
            }

            // 3. Destination Timeline Node
            TimelineConnectorLine()
            TimelineDestinationNode(
                destinationLabel = destinationLabel,
                totalDistanceKm = plan.totalDistanceKm,
                finalSoc = plan.finalBatteryPercent,
                totalChargingStops = plan.chargingStopsCount,
                totalChargingMinutes = plan.totalChargingDurationMinutes
            )
        }
    }
}

@Composable
fun TimelineOriginNode(
    originLabel: String,
    startSoc: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(EmeraldContainerDark, CircleShape)
                .border(2.dp, EmeraldPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column {
            Text(
                text = "Khởi hành: $originLabel",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Mức pin xuất phát: $startSoc% SoC",
                style = MaterialTheme.typography.bodySmall,
                color = EmeraldPrimaryLight
            )
        }
    }
}

@Composable
fun TimelineConnectorLine() {
    Row(modifier = Modifier.padding(start = 11.dp)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(24.dp)
                .background(DarkOutline)
        )
    }
}

@Composable
fun TimelineChargingStopNode(
    stop: EvRouteStop,
    onSwapClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(EmeraldPrimary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${stop.stopIndex}",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF0F172A)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .background(DarkSurface, RoundedCornerShape(10.dp))
                .border(1.dp, DarkOutline, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            // Station Name & Distance
            Text(
                text = stop.station.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "Km ${stop.distanceFromOriginKm.roundToInt()} (+${stop.distanceFromPreviousStopKm.roundToInt()} km từ điểm trước)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Badges Row: Power Pill + Live Plug Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Power Pill
                Box(
                    modifier = Modifier
                        .background(EmeraldContainerDark, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stop.powerDisplayLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldPrimaryLight
                    )
                }

                // Live Plug Status Badge
                val statusColor = when (stop.availabilityStatus) {
                    StopAvailabilityStatus.AVAILABLE -> StatusAvailable
                    StopAvailabilityStatus.STATION_BUSY -> StatusMaintaining
                    StopAvailabilityStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = stop.liveStatusBadge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // SoC info and Charging Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Pin đến: ${stop.arrivalBatteryPercent}% ➔ Sạc lên ${stop.targetBatteryPercent}%",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (stop.arrivalBatteryPercent < 20) StatusMaintaining else EmeraldPrimaryLight
                    )
                    Text(
                        text = "Thời gian sạc dự kiến: ~${stop.estimatedChargingMinutes} phút",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // "Đổi trạm khác" button
                OutlinedButton(
                    onClick = onSwapClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldPrimaryLight),
                    border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.sizeIn(minHeight = 48.dp)
                ) {
                    Text(
                        text = "Đổi trạm khác",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
fun TimelineDestinationNode(
    destinationLabel: String,
    totalDistanceKm: Double,
    finalSoc: Int,
    totalChargingStops: Int,
    totalChargingMinutes: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color(0xFF450A0A), CircleShape)
                .border(2.dp, StatusBusy, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(StatusBusy, CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column {
            Text(
                text = "Đích đến: $destinationLabel",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tổng: ${totalDistanceKm.roundToInt()} km • Pin đến đích: $finalSoc% SoC",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = EmeraldPrimaryLight
            )
            if (totalChargingStops > 0) {
                Text(
                    text = "Gồm $totalChargingStops trạm sạc (~$totalChargingMinutes phút sạc)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "Đi thẳng an toàn không cần sạc giữa đường",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusAvailable
                )
            }
        }
    }
}
