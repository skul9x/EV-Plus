package com.evcs.favorites.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.EmeraldPrimary

/**
 * Pure data model representing the Intent specification for opening system overlay settings.
 */
data class OverlaySettingsIntentSpec(
    val action: String,
    val packageUriString: String
)

/**
 * Decoupled helper providing pure Kotlin logic, intent builders, and strings for
 * Focus Mode overlay permission onboarding and validation.
 */
object FocusModePermissionDialogHelper {

    const val TITLE = "Kích hoạt Chế độ Focus Mode"
    const val DESCRIPTION = "Để hiển thị trạng thái cổng sạc theo thời gian thực đè lên Google Maps khi đang lái xe, ứng dụng cần quyền 'Hiển thị trên các ứng dụng khác'.\n\nNếu bạn không muốn cấp quyền, Focus Mode vẫn hoạt động bình thường qua Thanh thông báo (cần quyền thông báo trên Android 13+)."
    const val BTN_GRANT_PERMISSION = "Cấp quyền (Cửa sổ nổi)"
    const val BTN_NOTIFICATION_FALLBACK = "Dùng thông báo (Không cần quyền)"
    const val ACTION_MANAGE_OVERLAY_PERMISSION = "android.settings.action.MANAGE_OVERLAY_PERMISSION"

    /**
     * Checks whether the application currently holds notification permission.
     * On Android 13+ (API 33+), checks Manifest.permission.POST_NOTIFICATIONS.
     * On older Android versions, checks if system notifications are enabled.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    /**
     * Builds intent specification for launching Android overlay permissions screen.
     */
    fun buildOverlaySettingsIntentSpec(packageName: String): OverlaySettingsIntentSpec {
        return OverlaySettingsIntentSpec(
            action = ACTION_MANAGE_OVERLAY_PERMISSION,
            packageUriString = "package:$packageName"
        )
    }

    /**
     * Checks whether the application currently has permission to draw system overlays.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Settings.canDrawOverlays(context)
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
    }

    /**
     * Opens system overlay permission settings targeting this application.
     */
    fun openOverlaySettings(context: Context) {
        val spec = buildOverlaySettingsIntentSpec(context.packageName)
        try {
            val intent = Intent(spec.action, Uri.parse(spec.packageUriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(spec.action).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
    }
}

/**
 * Material3 Alert Dialog shown when the user first triggers Focus Mode without overlay permissions.
 *
 * Offers two seamless options:
 * 1. "Cấp quyền (Cửa sổ nổi)": Opens system settings to grant overlay permission.
 * 2. "Dùng thông báo (Không cần quyền)": Directly starts Focus Mode in notification fallback mode.
 */
@Composable
fun FocusModePermissionDialog(
    onGrantOverlayPermission: () -> Unit,
    onUseNotificationFallback: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = AppIcons.Bolt,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = FocusModePermissionDialogHelper.TITLE,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = FocusModePermissionDialogHelper.DESCRIPTION,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        lineHeight = 20.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onGrantOverlayPermission,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = FocusModePermissionDialogHelper.BTN_GRANT_PERMISSION,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onUseNotificationFallback,
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = FocusModePermissionDialogHelper.BTN_NOTIFICATION_FALLBACK,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        },
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.padding(16.dp)
    )
}
