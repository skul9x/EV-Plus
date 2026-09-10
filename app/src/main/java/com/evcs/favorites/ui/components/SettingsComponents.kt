package com.evcs.favorites.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcs.favorites.data.preferences.FocusModePreferences
import com.evcs.favorites.data.preferences.OrientationPreferences
import com.evcs.favorites.data.preferences.StartupOrientation
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State manager for the auto-save feedback pill.
 */
class AutoSaveFeedbackState {
    var isVisible by mutableStateOf(false)
        private set

    suspend fun show(durationMs: Long = 1800L) {
        isVisible = true
        delay(durationMs)
        isVisible = false
    }

    fun trigger(scope: CoroutineScope, durationMs: Long = 1800L) {
        scope.launch {
            show(durationMs)
        }
    }
}

/**
 * Floating feedback pill with smooth slide/fade animations indicating settings have been auto-saved.
 */
@Composable
fun SettingsAutoSaveBadge(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String = "Đã lưu cài đặt"
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = EmeraldPrimary,
            contentColor = Color.Black,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.Black
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.Black
                )
            }
        }
    }
}

/**
 * Contract and helper functions for resetting all settings to factory defaults.
 */
object SettingsDefaultsHelper {
    val DEFAULT_ORIENTATION = StartupOrientation.SYSTEM
    const val DEFAULT_VOICE_ALERT_ENABLED = true
    val DEFAULT_CUSTOM_FILTER = CustomFilterConfig(
        mode = CustomFilterMode.QUICK_CHIP,
        quickChip = QuickChipOption.ALL
    )

    fun resetToDefaults(
        orientationPrefs: OrientationPreferences?,
        focusPrefs: FocusModePreferences?,
        onSaveCustomFilter: ((CustomFilterConfig) -> Unit)?,
        onAutoSaveTriggered: (() -> Unit)? = null
    ) {
        orientationPrefs?.setStartupOrientation(DEFAULT_ORIENTATION)
        focusPrefs?.setVoiceAlertEnabled(DEFAULT_VOICE_ALERT_ENABLED)
        onSaveCustomFilter?.invoke(DEFAULT_CUSTOM_FILTER)
        onAutoSaveTriggered?.invoke()
    }
}

/**
 * Confirmation dialog shown before resetting settings to defaults.
 */
@Composable
fun ResetDefaultsConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        icon = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "Đặt lại cài đặt mặc định?",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Text(
                text = "Tất cả cài đặt sẽ được đưa về giá trị gốc:\n" +
                        "• Hướng màn hình: Tự xoay theo hệ thống (SYSTEM)\n" +
                        "• Cảnh báo bằng giọng nói: Bật (Voice Alerts ON)\n" +
                        "• Bộ lọc công suất: Tất cả các trạm (ALL)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Đặt lại", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Hủy")
            }
        }
    )
}

/**
 * High-visibility, automotive-friendly card container for selecting startup screen orientation
 * with instant auto-save feedback dispatch.
 */
@Composable
fun StartupOrientationCard(
    currentOrientation: StartupOrientation,
    onOrientationSelected: (StartupOrientation) -> Unit,
    modifier: Modifier = Modifier,
    onAutoSave: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCardBackground
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
                        .background(EmeraldContainerDark)
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

                Surface(
                    onClick = {
                        onOrientationSelected(orientation)
                        onAutoSave?.invoke()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) {
                            EmeraldPrimary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        }
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
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
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (orientation.badge != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = orientation.badge,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            onClick = {
                                onOrientationSelected(orientation)
                                onAutoSave?.invoke()
                            },
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
 * High-contrast Material 3 card container for Focus Mode Voice Announcements toggle switch
 * with instant auto-save feedback dispatch.
 */
@Composable
fun FocusModeVoiceAlertCard(
    isEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onAutoSave: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCardBackground
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
                        if (isEnabled) EmeraldContainerDark
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
                onCheckedChange = { checked ->
                    onCheckedChange(checked)
                    onAutoSave?.invoke()
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
