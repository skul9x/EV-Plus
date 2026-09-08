package com.evcs.favorites.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcs.favorites.data.preferences.FocusModePreferences
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import android.app.Activity
import androidx.compose.foundation.border
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.evcs.favorites.data.preferences.OrientationPreferences
import com.evcs.favorites.data.preferences.StartupOrientation
import com.evcs.favorites.data.routing.EvRoutingSettings
import com.evcs.favorites.data.routing.RoutingPreferencesManager
import com.evcs.favorites.util.OrientationHelper
import kotlin.math.roundToInt

/**
 * Material 3 [ModalBottomSheet] providing power filter configuration,
 * Focus Mode Voice Announcement toggle, startup orientation setting, in-memory real-time diagnostic log viewer,
 * and settings persistence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSettingsModal(
    settings: RoutingSettings = RoutingSettings(),
    onSaveSettings: ((RoutingSettings) -> Unit)? = null,
    onValidateKey: (suspend (String) -> Result<Boolean>)? = null,
    onDismiss: () -> Unit,
    customFilterConfig: CustomFilterConfig? = null,
    onSaveCustomFilter: ((CustomFilterConfig) -> Unit)? = null,
    focusModePreferences: FocusModePreferences? = null,
    orientationPreferences: OrientationPreferences? = null,
    routingPreferencesManager: RoutingPreferencesManager? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusPrefs = remember(focusModePreferences, context) {
        focusModePreferences ?: FocusModePreferences.create(context)
    }
    val voiceAlertEnabled by focusPrefs.voiceAlertEnabledFlow.collectAsState()

    val orientationPrefs = remember(orientationPreferences, context) {
        orientationPreferences ?: OrientationPreferences.create(context)
    }
    val currentOrientation by orientationPrefs.startupOrientationFlow.collectAsState()

    val routingPrefs = remember(routingPreferencesManager, context) {
        routingPreferencesManager ?: try {
            RoutingPreferencesManager.create(context)
        } catch (_: Throwable) {
            null
        }
    }

    // Two-way reactive synchronization with RoutingPreferencesManager
    val evFromPrefs by (routingPrefs?.evRoutingSettings ?: MutableStateFlow(settings.evSettings)).collectAsState()
    var localEvSettings by remember(settings.evSettings) {
        mutableStateOf(if (routingPrefs != null) evFromPrefs else settings.evSettings)
    }

    LaunchedEffect(evFromPrefs) {
        if (routingPrefs != null) {
            localEvSettings = evFromPrefs
        }
    }

    val onEvChanged: (EvRoutingSettings) -> Unit = { updatedEv ->
        localEvSettings = updatedEv
        routingPrefs?.updateEvRoutingSettings(updatedEv)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val customFilterState = rememberCustomFilterFormState(customFilterConfig)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxHeight(0.92f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(EmeraldContainerDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cài đặt",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Cấu hình hướng hiển thị, cảnh báo và bộ lọc công suất",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Đóng",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 1. Startup Orientation Section (Automotive Display & Android Box)
            StartupOrientationCard(
                currentOrientation = currentOrientation,
                onOrientationSelected = { selectedOrientation ->
                    orientationPrefs.setStartupOrientation(selectedOrientation)
                    (context as? Activity)?.requestedOrientation =
                        OrientationHelper.toActivityInfoOrientation(selectedOrientation)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Focus Mode Voice Alert Section
            FocusModeVoiceAlertCard(
                isEnabled = voiceAlertEnabled,
                onCheckedChange = { enabled ->
                    focusPrefs.setVoiceAlertEnabled(enabled)
                    FocusModeForegroundService.setAudioMuted(context, !enabled)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 3. EV Smart Routing Settings Section
            EvSmartRoutingSettingsCard(
                evSettings = localEvSettings,
                onEvSettingsChanged = onEvChanged
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Custom Filter Section
            CustomFilterSettingsCard(
                state = customFilterState,
                onSaveCustomFilter = { config ->
                    onSaveCustomFilter?.invoke(config)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 5. Debug Log Viewer Section
            DebugLogViewerCard()

            Spacer(modifier = Modifier.height(24.dp))

            // 6. Action Buttons (Save & Dismiss)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "Đóng")
                }

                Button(
                    onClick = {
                        routingPrefs?.updateEvRoutingSettings(localEvSettings)
                        val updatedSettings = settings.copy(evSettings = localEvSettings)
                        onSaveSettings?.invoke(updatedSettings)
                        focusPrefs.setVoiceAlertEnabled(voiceAlertEnabled)
                        orientationPrefs.setStartupOrientation(currentOrientation)
                        if (customFilterState.isValid) {
                            customFilterState.buildConfig()?.let { filterConfig ->
                                onSaveCustomFilter?.invoke(filterConfig)
                            }
                        }
                        onDismiss()
                    },
                    enabled = customFilterState.isValid,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text(
                        text = "Lưu cài đặt",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * High-contrast Material 3 card container for Focus Mode Voice Announcements toggle switch.
 */
@Composable
fun FocusModeVoiceAlertCard(
    isEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = EmeraldContainerDark
        ),
        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEnabled) EmeraldPrimary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
            ) {
                Icon(
                    imageVector = if (isEnabled) AppIcons.VolumeUp else AppIcons.VolumeOff,
                    contentDescription = null,
                    tint = if (isEnabled) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cảnh báo bằng giọng nói (Voice Announcements)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tự động giảm âm lượng nhạc xe (Audio Ducking) và thông báo bằng tiếng Việt khi trạm sạc hết chỗ, gợi ý trạm mới hoặc cách trạm 2km.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Switch(
                checked = isEnabled,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EmeraldPrimary,
                    checkedTrackColor = EmeraldContainerDark,
                    checkedBorderColor = EmeraldPrimary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}

/**
 * High-visibility, automotive-friendly card container for selecting startup screen orientation.
 *
 * Provides 1-tap orientation switching with prominent badge highlighting for automotive in-dash
 * displays / Android Boxes ([StartupOrientation.LANDSCAPE]).
 */
@Composable
fun StartupOrientationCard(
    currentOrientation: StartupOrientation,
    onOrientationSelected: (StartupOrientation) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = EmeraldContainerDark
        ),
        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(EmeraldPrimary.copy(alpha = 0.2f))
                ) {
                    Icon(
                        imageVector = AppIcons.ScreenRotation,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hướng màn hình khởi động",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Cố định hướng ngang cho màn hình ô tô hoặc để hệ thống tự xoay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 3 clear options
            StartupOrientation.entries.forEach { orientation ->
                val isSelected = orientation == currentOrientation
                val isAutomotiveLandscape = orientation == StartupOrientation.LANDSCAPE

                Surface(
                    onClick = { onOrientationSelected(orientation) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        EmeraldPrimary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            EmeraldPrimary
                        } else if (isAutomotiveLandscape) {
                            EmeraldPrimary.copy(alpha = 0.4f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = when (orientation) {
                            StartupOrientation.SYSTEM -> AppIcons.ScreenRotation
                            StartupOrientation.LANDSCAPE -> AppIcons.StayCurrentLandscape
                            StartupOrientation.PORTRAIT -> AppIcons.StayCurrentPortrait
                        }

                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = orientation.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                            )

                            if (orientation.badge != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (isAutomotiveLandscape) EmeraldContainerDark
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .then(
                                            if (isAutomotiveLandscape) {
                                                Modifier.border(
                                                    1.dp,
                                                    EmeraldPrimary.copy(alpha = 0.6f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                            } else Modifier
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = orientation.badge,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isAutomotiveLandscape) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (orientation.description != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = orientation.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        RadioButton(
                            selected = isSelected,
                            onClick = { onOrientationSelected(orientation) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = EmeraldPrimary,
                                unselectedColor = MaterialTheme.colorScheme.outline
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Automotive-styled Material 3 card container for EV vehicle specifications and safety routing buffers.
 * Provides controls for safe range (100-500 km), arrival reserve buffer (5-25%),
 * target charging SoC (70-95%), and +25% delay safety buffer switch.
 */
@Composable
fun EvSmartRoutingSettingsCard(
    evSettings: EvRoutingSettings,
    onEvSettingsChanged: (EvRoutingSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var safeRange by remember(evSettings.vehicleSafeRangeKm) {
        mutableFloatStateOf(evSettings.vehicleSafeRangeKm.toFloat())
    }
    var arrivalBuffer by remember(evSettings.arrivalBufferSocPercent) {
        mutableFloatStateOf(evSettings.arrivalBufferSocPercent.toFloat())
    }
    var targetCharging by remember(evSettings.targetChargingSocPercent) {
        mutableFloatStateOf(evSettings.targetChargingSocPercent.toFloat())
    }

    LaunchedEffect(evSettings.vehicleSafeRangeKm) {
        safeRange = evSettings.vehicleSafeRangeKm.toFloat()
    }
    LaunchedEffect(evSettings.arrivalBufferSocPercent) {
        arrivalBuffer = evSettings.arrivalBufferSocPercent.toFloat()
    }
    LaunchedEffect(evSettings.targetChargingSocPercent) {
        targetCharging = evSettings.targetChargingSocPercent.toFloat()
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = EmeraldContainerDark
        ),
        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.25f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(EmeraldPrimary.copy(alpha = 0.2f))
                ) {
                    Icon(
                        imageVector = AppIcons.EvStation,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cấu hình Lộ trình & Pin Xe EV",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Thông số pin, tầm an toàn và mức sạc mục tiêu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Slider 1: Quãng đường an toàn ở 100% pin (100 - 500 km, default 200 km)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quãng đường an toàn ở 100% pin",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${safeRange.roundToInt()} km",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Khai báo số km thực tế xe đi được an toàn ở 100% pin",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = safeRange,
                    onValueChange = { newValue ->
                        safeRange = newValue
                    },
                    onValueChangeFinished = {
                        onEvSettingsChanged(
                            evSettings.copy(
                                vehicleSafeRangeKm = safeRange.roundToInt().coerceIn(100, 500),
                                arrivalBufferSocPercent = arrivalBuffer.roundToInt().coerceIn(5, 25),
                                targetChargingSocPercent = targetCharging.roundToInt().coerceIn(70, 95)
                            )
                        )
                    },
                    valueRange = 100f..500f,
                    steps = 7,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Slider 2: Mức pin dự phòng tối thiểu khi đến trạm / đích (5% - 25%, default 10%)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mức pin dự phòng tối thiểu khi đến trạm / đích",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${arrivalBuffer.roundToInt()}%",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Ngưỡng pin tối thiểu an toàn để tránh cạn kiệt năng lượng",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = arrivalBuffer,
                    onValueChange = { newValue ->
                        arrivalBuffer = newValue
                    },
                    onValueChangeFinished = {
                        onEvSettingsChanged(
                            evSettings.copy(
                                vehicleSafeRangeKm = safeRange.roundToInt().coerceIn(100, 500),
                                arrivalBufferSocPercent = arrivalBuffer.roundToInt().coerceIn(5, 25),
                                targetChargingSocPercent = targetCharging.roundToInt().coerceIn(70, 95)
                            )
                        )
                    },
                    valueRange = 5f..25f,
                    steps = 3,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Slider 3: Mức pin mục tiêu khi sạc (70% - 95%, default 85%)
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mức pin mục tiêu khi sạc",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${targetCharging.roundToInt()}%",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldPrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Mức pin tối ưu ngắt sạc để tối đa hóa tốc độ sạc nhanh DC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = targetCharging,
                    onValueChange = { newValue ->
                        targetCharging = newValue
                    },
                    onValueChangeFinished = {
                        onEvSettingsChanged(
                            evSettings.copy(
                                vehicleSafeRangeKm = safeRange.roundToInt().coerceIn(100, 500),
                                arrivalBufferSocPercent = arrivalBuffer.roundToInt().coerceIn(5, 25),
                                targetChargingSocPercent = targetCharging.roundToInt().coerceIn(70, 95)
                            )
                        )
                    },
                    valueRange = 70f..95f,
                    steps = 4,
                    colors = SliderDefaults.colors(
                        thumbColor = EmeraldPrimary,
                        activeTrackColor = EmeraldPrimary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            // Switch: Cộng thêm 25% thời gian trễ an toàn khi sạc (default On)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cộng thêm 25% thời gian trễ an toàn khi sạc",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Bù trừ thời gian chờ cắm sạc, giảm tốc độ khi pin nóng",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Switch(
                    checked = evSettings.safetyDurationBufferEnabled,
                    onCheckedChange = { isChecked ->
                        onEvSettingsChanged(
                            evSettings.copy(
                                vehicleSafeRangeKm = safeRange.roundToInt().coerceIn(100, 500),
                                arrivalBufferSocPercent = arrivalBuffer.roundToInt().coerceIn(5, 25),
                                targetChargingSocPercent = targetCharging.roundToInt().coerceIn(70, 95),
                                safetyDurationBufferEnabled = isChecked
                            )
                        )
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmeraldPrimary,
                        checkedTrackColor = EmeraldContainerDark,
                        checkedBorderColor = EmeraldPrimary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }
    }
}


