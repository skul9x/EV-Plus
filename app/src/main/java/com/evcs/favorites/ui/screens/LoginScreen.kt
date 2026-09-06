package com.evcs.favorites.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Email
import com.evcs.favorites.ui.theme.AppIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.ui.state.FavoritesUiState
import com.evcs.favorites.ui.theme.ElectricCyan
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.EmeraldPrimaryLight
import com.evcs.favorites.ui.theme.StatusOffline
import com.evcs.favorites.ui.theme.StatusOfflineContainer

/**
 * Modern, high-performance Login screen supporting Email OTP authentication.
 */
@Composable
fun LoginScreen(
    uiState: FavoritesUiState,
    initialEmail: String,
    onRequestOtp: (String) -> Unit,
    onVerifyOtp: (String) -> Unit,
    onBackToEmail: () -> Unit,
    modifier: Modifier = Modifier
) {
    var emailInput by remember(initialEmail) { mutableStateOf(initialEmail) }
    var otpInput by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    var stepOtpActive by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is FavoritesUiState.RequestingOtp || uiState is FavoritesUiState.VerifyingOtp) {
            stepOtpActive = true
        } else if (uiState is FavoritesUiState.LoggedOut) {
            stepOtpActive = false
        }
    }

    val isStepOtp = stepOtpActive || uiState is FavoritesUiState.RequestingOtp || uiState is FavoritesUiState.VerifyingOtp
    val isLoading = uiState is FavoritesUiState.VerifyingOtp

    Surface(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Logo with glowing gradient ring
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                EmeraldContainerDark,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .border(2.dp, EmeraldPrimary.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = AppIcons.Bolt,
                    contentDescription = "EVCS Logo",
                    tint = EmeraldPrimary,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // App Title & Tagline
            Text(
                text = "EVCS Trạm Sạc",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (isStepOtp) "Nhập mã 6 chữ số đã gửi qua email" else "Đăng nhập để xem và dẫn đường tới trạm sạc yêu thích",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Error Banner
            AnimatedVisibility(
                visible = uiState is FavoritesUiState.Error,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (uiState is FavoritesUiState.Error) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(StatusOfflineContainer)
                            .border(1.dp, StatusOffline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.ErrorOutline,
                            contentDescription = null,
                            tint = StatusOffline,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = StatusOffline,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (!isStepOtp) {
                // STEP 1: Email Input Form
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = { emailInput = it },
                    label = { Text("Địa chỉ Email") },
                    placeholder = { Text("vd: name@gmail.com") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (emailInput.isNotEmpty()) {
                            IconButton(onClick = { emailInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Xóa email",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (emailInput.isNotBlank()) onRequestOtp(emailInput)
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (emailInput.isNotBlank()) onRequestOtp(emailInput)
                    },
                    enabled = emailInput.isNotBlank() && emailInput.contains("@"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        disabledContainerColor = EmeraldPrimary.copy(alpha = 0.35f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = "Gửi mã xác thực OTP",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                // STEP 2: OTP Verification Form
                // Show recipient email badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = emailInput,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = EmeraldPrimaryLight
                        )
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedTextField(
                    value = otpInput,
                    onValueChange = { input ->
                        // Limit to 6 numeric characters
                        if (input.length <= 6 && input.all { it.isDigit() }) {
                            otpInput = input
                            if (input.length == 6) {
                                focusManager.clearFocus()
                                onVerifyOtp(input)
                            }
                        }
                    },
                    label = { Text("Mã OTP (6 chữ số)") },
                    placeholder = { Text("• • • • • •") },
                    leadingIcon = {
                        Icon(
                            imageVector = AppIcons.Key,
                            contentDescription = null,
                            tint = ElectricCyan
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            if (otpInput.length == 6) onVerifyOtp(otpInput)
                        }
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ElectricCyan,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        if (otpInput.length == 6) onVerifyOtp(otpInput)
                    },
                    enabled = otpInput.length == 6 && !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        disabledContainerColor = EmeraldPrimary.copy(alpha = 0.35f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Đang xác thực...",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    } else {
                        Text(
                            text = "Xác nhận & Đăng nhập",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = AppIcons.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBackToEmail) {
                        Text(
                            text = "Đổi email",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    TextButton(onClick = { onRequestOtp(emailInput) }) {
                        Text(
                            text = "Gửi lại mã OTP",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = EmeraldPrimary
                            )
                        )
                    }
                }
            }
        }
    }
}
