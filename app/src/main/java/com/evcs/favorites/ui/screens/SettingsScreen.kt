package com.evcs.favorites.ui.screens

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evcs.favorites.data.preferences.FocusModePreferences
import com.evcs.favorites.data.preferences.OrientationPreferences
import com.evcs.favorites.data.preferences.StartupOrientation
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.focus.FocusModeForegroundService
import com.evcs.favorites.ui.components.AboutAppCard
import com.evcs.favorites.ui.components.AboutAppInfo
import com.evcs.favorites.ui.components.AutoSaveFeedbackState
import com.evcs.favorites.ui.components.CustomFilterFormState
import com.evcs.favorites.ui.components.CustomFilterSettingsCard
import com.evcs.favorites.ui.components.FocusModeVoiceAlertCard
import com.evcs.favorites.ui.components.ResetDefaultsConfirmationDialog
import com.evcs.favorites.ui.components.SettingsAutoSaveBadge
import com.evcs.favorites.ui.components.SettingsDefaultsHelper
import com.evcs.favorites.ui.components.StartupOrientationCard
import com.evcs.favorites.ui.components.rememberCustomFilterFormState
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.util.OrientationHelper

/**
 * Layout specifications and automotive dimensions for SettingsScreen.
 */
object SettingsScreenDefaults {
    val SIDEBAR_WIDTH_DP: Dp = 260.dp
    val MIN_TOUCH_TARGET_DP: Dp = 56.dp
}

/**
 * Supported functional categories for automotive Master-Detail navigation.
 */
enum class SettingsCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    DISPLAY("DISPLAY", "Hiển thị & Xe", "Hướng màn hình & giao diện", AppIcons.ScreenRotation),
    VOICE("VOICE", "Giọng nói & Lái xe", "Cảnh báo chỉ đường & âm thanh", AppIcons.VolumeUp),
    FILTER("FILTER", "Bộ lọc công suất", "Công suất sạc trạm (kW)", AppIcons.Tune),
    ABOUT("ABOUT", "Thông tin ứng dụng", "Phiên bản, tác giả & bản quyền", Icons.Default.Info)
}

/**
 * Standalone commercial settings screen supporting adaptive layouts:
 * - Portrait: Fullscreen Scaffold with TopAppBar, Reset icon, and single-column scroll container.
 * - Landscape: Automotive 16:9 / 21:9 Master-Detail split layout with 260dp Sidebar and Right Canvas.
 */
@Composable
fun SettingsScreen(
    isLandscape: Boolean = false,
    orientationPreferences: OrientationPreferences? = null,
    focusModePreferences: FocusModePreferences? = null,
    customFilterConfig: CustomFilterConfig? = null,
    onSaveCustomFilter: ((CustomFilterConfig) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoSaveFeedbackState = remember { AutoSaveFeedbackState() }

    val orientationPrefs = remember(orientationPreferences, context) {
        orientationPreferences ?: OrientationPreferences.create(context)
    }
    val focusPrefs = remember(focusModePreferences, context) {
        focusModePreferences ?: FocusModePreferences.create(context)
    }

    val currentOrientation by orientationPrefs.startupOrientationFlow.collectAsState()
    val voiceAlertEnabled by focusPrefs.voiceAlertEnabledFlow.collectAsState()

    val customFilterState = rememberCustomFilterFormState(customFilterConfig)

    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.DISPLAY) }

    val onAutoSave: () -> Unit = {
        autoSaveFeedbackState.trigger(scope)
    }

    val handleResetToDefaults: () -> Unit = {
        SettingsDefaultsHelper.resetToDefaults(
            orientationPrefs = orientationPrefs,
            focusPrefs = focusPrefs,
            onSaveCustomFilter = onSaveCustomFilter,
            onAutoSaveTriggered = onAutoSave
        )
        (context as? Activity)?.requestedOrientation =
            OrientationHelper.toActivityInfoOrientation(SettingsDefaultsHelper.DEFAULT_ORIENTATION)
        FocusModeForegroundService.setAudioMuted(context, !SettingsDefaultsHelper.DEFAULT_VOICE_ALERT_ENABLED)
        customFilterState.selectQuickChip(QuickChipOption.ALL)
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (isLandscape) {
            SettingsScreenLandscape(
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it },
                currentOrientation = currentOrientation,
                onOrientationSelected = { selected ->
                    orientationPrefs.setStartupOrientation(selected)
                    (context as? Activity)?.requestedOrientation =
                        OrientationHelper.toActivityInfoOrientation(selected)
                },
                voiceAlertEnabled = voiceAlertEnabled,
                onVoiceAlertChanged = { enabled ->
                    focusPrefs.setVoiceAlertEnabled(enabled)
                    FocusModeForegroundService.setAudioMuted(context, !enabled)
                },
                customFilterState = customFilterState,
                onSaveCustomFilter = onSaveCustomFilter,
                onResetClick = { showResetConfirmation = true },
                onAutoSave = onAutoSave,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SettingsScreenPortrait(
                currentOrientation = currentOrientation,
                onOrientationSelected = { selected ->
                    orientationPrefs.setStartupOrientation(selected)
                    (context as? Activity)?.requestedOrientation =
                        OrientationHelper.toActivityInfoOrientation(selected)
                },
                voiceAlertEnabled = voiceAlertEnabled,
                onVoiceAlertChanged = { enabled ->
                    focusPrefs.setVoiceAlertEnabled(enabled)
                    FocusModeForegroundService.setAudioMuted(context, !enabled)
                },
                customFilterState = customFilterState,
                onSaveCustomFilter = onSaveCustomFilter,
                onResetClick = { showResetConfirmation = true },
                onAutoSave = onAutoSave,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Floating auto-save notification pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            SettingsAutoSaveBadge(visible = autoSaveFeedbackState.isVisible)
        }

        // Reset defaults confirmation modal dialog
        if (showResetConfirmation) {
            ResetDefaultsConfirmationDialog(
                onConfirm = {
                    handleResetToDefaults()
                    showResetConfirmation = false
                },
                onDismiss = {
                    showResetConfirmation = false
                }
            )
        }
    }
}

/**
 * Single-column vertical scroll container for Portrait mobile layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenPortrait(
    currentOrientation: StartupOrientation,
    onOrientationSelected: (StartupOrientation) -> Unit,
    voiceAlertEnabled: Boolean,
    onVoiceAlertChanged: (Boolean) -> Unit,
    customFilterState: CustomFilterFormState,
    onSaveCustomFilter: ((CustomFilterConfig) -> Unit)?,
    onResetClick: () -> Unit,
    onAutoSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Cài đặt",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    IconButton(onClick = onResetClick) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Đặt lại mặc định",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Display & Vehicle Orientation
            StartupOrientationCard(
                currentOrientation = currentOrientation,
                onOrientationSelected = onOrientationSelected,
                onAutoSave = onAutoSave
            )

            // 2. Voice Guidance & Driving Alerts
            FocusModeVoiceAlertCard(
                isEnabled = voiceAlertEnabled,
                onCheckedChange = onVoiceAlertChanged,
                onAutoSave = onAutoSave
            )

            // 3. Power Filter
            CustomFilterSettingsCard(
                state = customFilterState,
                onSaveCustomFilter = { config ->
                    onSaveCustomFilter?.invoke(config)
                },
                isLandscape = false,
                showQuickChips = false,
                onAutoSave = onAutoSave
            )

            // 4. Commercial Branding, Version & Copyright
            AboutAppCard()

            // Persistent Copyright 2026 Footer
            SettingsCopyrightFooter()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Automotive 16:9 / 21:9 Master-Detail split layout for Landscape mode.
 */
@Composable
fun SettingsScreenLandscape(
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit,
    currentOrientation: StartupOrientation,
    onOrientationSelected: (StartupOrientation) -> Unit,
    voiceAlertEnabled: Boolean,
    onVoiceAlertChanged: (Boolean) -> Unit,
    customFilterState: CustomFilterFormState,
    onSaveCustomFilter: ((CustomFilterConfig) -> Unit)?,
    onResetClick: () -> Unit,
    onAutoSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Left Master Sidebar (260dp width)
        val sidebarScrollState = rememberScrollState()
        Surface(
            modifier = Modifier
                .width(SettingsScreenDefaults.SIDEBAR_WIDTH_DP)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(sidebarScrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
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
                        Text(
                            text = "Cài đặt",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 4 Category Selector Tiles
                    SettingsCategory.entries.forEach { category ->
                        val isSelected = category == selectedCategory
                        SettingsCategoryTile(
                            category = category,
                            isSelected = isSelected,
                            onClick = { onCategorySelected(category) }
                        )
                    }
                }

                // Anchored Reset Defaults Button in Sidebar Footer
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedButton(
                        onClick = onResetClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = SettingsScreenDefaults.MIN_TOUCH_TARGET_DP),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Đặt lại mặc định",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }

        // Right Detail Content Canvas with smooth vertical scrolling
        val detailScrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(detailScrollState)
                .imePadding()
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                when (selectedCategory) {
                    SettingsCategory.DISPLAY -> {
                        StartupOrientationCard(
                            currentOrientation = currentOrientation,
                            onOrientationSelected = onOrientationSelected,
                            onAutoSave = onAutoSave
                        )
                    }
                    SettingsCategory.VOICE -> {
                        FocusModeVoiceAlertCard(
                            isEnabled = voiceAlertEnabled,
                            onCheckedChange = onVoiceAlertChanged,
                            onAutoSave = onAutoSave
                        )
                    }
                    SettingsCategory.FILTER -> {
                        CustomFilterSettingsCard(
                            state = customFilterState,
                            onSaveCustomFilter = { config ->
                                onSaveCustomFilter?.invoke(config)
                            },
                            isLandscape = true,
                            showQuickChips = false,
                            onAutoSave = onAutoSave
                        )
                    }
                    SettingsCategory.ABOUT -> {
                        AboutAppCard()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = AboutAppInfo.COPYRIGHT,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Automotive touch tile for navigating settings categories with >= 56dp touch container.
 */
@Composable
fun SettingsCategoryTile(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) EmeraldPrimary.copy(alpha = 0.15f) else Color.Transparent,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsScreenDefaults.MIN_TOUCH_TARGET_DP)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) EmeraldPrimary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = category.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Persistent understated commercial copyright footer.
 * Displays: © 2026 Nguyễn Duy Trường
 */
@Composable
fun SettingsCopyrightFooter(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = AboutAppInfo.COPYRIGHT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}
