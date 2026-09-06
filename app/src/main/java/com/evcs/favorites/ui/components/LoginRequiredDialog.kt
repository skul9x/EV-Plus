package com.evcs.favorites.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.evcs.favorites.ui.theme.AppIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.evcs.favorites.ui.theme.EmeraldPrimary

/**
 * Material 3 AlertDialog prompted when an unauthenticated user attempts to favorite a station.
 *
 * @param onConfirmLogin Callback when user clicks "Đăng nhập ngay" to navigate to login screen.
 * @param onDismiss Callback when user dismisses the dialog via "Để sau" or outside tap.
 */
@Composable
fun LoginRequiredDialog(
    onConfirmLogin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = AppIcons.AccountCircle,
                contentDescription = null,
                tint = EmeraldPrimary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = NearbyUiHelper.LOGIN_REQUIRED_TITLE,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Text(
                text = NearbyUiHelper.LOGIN_REQUIRED_DESCRIPTION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmLogin,
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = NearbyUiHelper.LOGIN_REQUIRED_CONFIRM,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = NearbyUiHelper.LOGIN_REQUIRED_DISMISS,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    )
}
