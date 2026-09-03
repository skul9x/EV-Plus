package com.evcs.favorites.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.DarkOutline
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.EmeraldPrimaryDark
import com.evcs.favorites.ui.theme.StatusOffline
import kotlinx.coroutines.launch

/**
 * State of the Google API Key validation probe.
 */
sealed class KeyValidationState {
    object Idle : KeyValidationState()
    object Validating : KeyValidationState()
    object Valid : KeyValidationState()
    data class Invalid(val message: String) : KeyValidationState()
}

/**
 * Safely opens an external URL using [Intent.ACTION_VIEW] with activity flags,
 * gracefully catching exceptions when no browser is available.
 */
private fun safeOpenUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        // Fallback gracefully if no browser or activity can handle the URI
    }
}

/**
 * Material 3 [ModalBottomSheet] providing BYOK configuration, routing mode selection,
 * active API key connection testing, interactive guide dialog entry banner, and contextual remediation deep-links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutingSettingsModal(
    settings: RoutingSettings,
    onSaveSettings: (RoutingSettings) -> Unit,
    onValidateKey: suspend (String) -> Result<Boolean>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    var apiKey by remember(settings.googleApiKey) { mutableStateOf(settings.googleApiKey) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var selectedEngine by remember(settings.preferredEngine) { mutableStateOf(settings.preferredEngine) }
    var autoFallback by remember(settings.autoFallbackEnabled) { mutableStateOf(settings.autoFallbackEnabled) }
    var validationState by remember { mutableStateOf<KeyValidationState>(KeyValidationState.Idle) }

    var showGuideModal by remember { mutableStateOf(false) }
    var guideInitialError by remember { mutableStateOf<String?>(null) }

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
                .verticalScroll(rememberScrollState())
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
                        text = "Cài đặt Lộ trình & BYOK",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tự cấp Google Maps API Key cá nhân",
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

            // 1. Google Maps API Key Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Google Maps API Key",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = EmeraldPrimary
                )

                TextButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) {
                            apiKey = RoutingSettingsHelper.sanitizeApiKey(clipText)
                            validationState = KeyValidationState.Idle
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Dán",
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Dán",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = RoutingSettingsHelper.sanitizeApiKey(it)
                    if (validationState !is KeyValidationState.Idle) {
                        validationState = KeyValidationState.Idle
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("AIzaSy...") },
                singleLine = true,
                visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (apiKey.isNotEmpty()) {
                            IconButton(onClick = {
                                apiKey = ""
                                validationState = KeyValidationState.Idle
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Xóa khóa",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                            Icon(
                                imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isApiKeyVisible) "Ẩn API Key" else "Hiện API Key"
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EmeraldPrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Connection Test Button & Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            validationState = KeyValidationState.Validating
                            val result = onValidateKey(apiKey)
                            validationState = if (result.isSuccess) {
                                KeyValidationState.Valid
                            } else {
                                val err = result.exceptionOrNull()?.message ?: "Lỗi xác thực khóa"
                                KeyValidationState.Invalid(err)
                            }
                        }
                    },
                    enabled = apiKey.isNotBlank() && validationState !is KeyValidationState.Validating,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (validationState is KeyValidationState.Validating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = EmeraldPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(text = "Kiểm tra kết nối")
                }

                when (validationState) {
                    is KeyValidationState.Valid -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "✅ Hợp lệ",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldPrimary
                            )
                        }
                    }
                    is KeyValidationState.Invalid -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = StatusOffline,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Lỗi kết nối",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = StatusOffline
                            )
                        }
                    }
                    else -> {}
                }
            }

            // Error details & smart remediation actions if invalid
            if (validationState is KeyValidationState.Invalid) {
                val errMessage = (validationState as KeyValidationState.Invalid).message
                val remediation = RoutingSettingsHelper.resolveRemediationForError(errMessage)

                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errMessage,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (remediation != null) {
                                Button(
                                    onClick = { safeOpenUrl(context, remediation.url) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onError
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = remediation.label,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onError,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    guideInitialError = errMessage
                                    showGuideModal = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lightbulb,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = EmeraldPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Xem hướng dẫn khắc phục",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = EmeraldPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actionable Guide Entry Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        guideInitialError = null
                        showGuideModal = true
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = EmeraldContainerDark.copy(alpha = 0.35f)
                ),
                border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
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
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hướng dẫn từng bước lấy API Key",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Miễn phí 10.000 lượt/tháng • 5 bước đơn giản",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmeraldPrimary
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Mở hướng dẫn",
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Routing Engine Selector Section
            Text(
                text = "Phương thức tính lộ trình",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = EmeraldPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(modifier = Modifier.selectableGroup()) {
                val modes = listOf(
                    Triple(
                        RoutingEngineMode.AUTO,
                        "Tự động (Khuyên dùng)",
                        "Sử dụng Google Maps nếu có API Key, tự động chuyển sang OSRM nếu lỗi/hết quota."
                    ),
                    Triple(
                        RoutingEngineMode.GOOGLE_ONLY,
                        "Chỉ Google Maps",
                        "Bắt buộc dữ liệu giao thông trực tiếp từ Google; không chuyển dự phòng."
                    ),
                    Triple(
                        RoutingEngineMode.OSRM_ONLY,
                        "Chỉ OSRM (Miễn phí)",
                        "Dùng lộ trình đường bộ mở 100% miễn phí; không tốn quota Google."
                    ),
                    Triple(
                        RoutingEngineMode.HAVERSINE_ONLY,
                        "Chỉ Đường chim bay",
                        "Khoảng cách đường thẳng offline 0ms; tiết kiệm pin và dữ liệu tối đa."
                    )
                )

                modes.forEach { (mode, title, desc) ->
                    val isSelected = selectedEngine == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(
                                selected = isSelected,
                                onClick = { selectedEngine = mode },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = EmeraldPrimary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (isSelected) EmeraldPrimary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Auto-Fallback Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { autoFallback = !autoFallback }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tự động chuyển dự phòng (Auto-Fallback)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tự động chuyển tầng tiếp theo khi xảy ra sự cố mạng hoặc lỗi quota.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = autoFallback,
                    onCheckedChange = { autoFallback = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = EmeraldPrimary,
                        checkedTrackColor = EmeraldContainerDark
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. Action Buttons (Save & Dismiss)
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
                        val newSettings = RoutingSettingsHelper.buildUpdatedSettings(
                            current = settings,
                            apiKey = apiKey,
                            engine = selectedEngine,
                            autoFallback = autoFallback
                        )
                        onSaveSettings(newSettings)
                        onDismiss()
                    },
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

    // Google Maps API Key Guide Dialog overlay
    if (showGuideModal) {
        GoogleApiKeyGuideModal(
            onDismissRequest = { showGuideModal = false },
            initialErrorMessage = guideInitialError,
            onOpenUrl = { url ->
                safeOpenUrl(context, url)
            }
        )
    }
}
