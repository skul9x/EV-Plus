package com.evcs.favorites.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.evcs.favorites.data.routing.ApiKeyGuideStep
import com.evcs.favorites.data.routing.ApiKeyTroubleshootingItem
import com.evcs.favorites.data.routing.FreeTierInfo
import com.evcs.favorites.ui.theme.DarkBackground
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.DarkOnSurface
import com.evcs.favorites.ui.theme.DarkOnSurfaceVariant
import com.evcs.favorites.ui.theme.DarkOutline
import com.evcs.favorites.ui.theme.DarkSurface
import com.evcs.favorites.ui.theme.DarkSurfaceVariant
import com.evcs.favorites.ui.theme.DistancePillBg
import com.evcs.favorites.ui.theme.DistancePillBorder
import com.evcs.favorites.ui.theme.DistancePillText
import com.evcs.favorites.ui.theme.ElectricCyan
import com.evcs.favorites.ui.theme.ElectricCyanContainerDark
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldOnContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusMaintaining
import kotlinx.coroutines.delay

/**
 * Interactive Guide Dialog presenting a 5-step walkthrough to obtain and safely configure
 * a personal Google Maps Platform API key (Routes API Essentials BYOK).
 *
 * Implemented via [Dialog] with [DialogProperties(usePlatformDefaultWidth = false)] and rounded
 * card styling to cleanly overlay [RoutingSettingsModal] without scrim or nested gesture conflicts.
 */
@Composable
fun GoogleApiKeyGuideModal(
    onDismissRequest: () -> Unit,
    initialErrorMessage: String? = null,
    onOpenUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val presenter = remember {
        val p = ApiKeyGuidePresenter()
        if (!initialErrorMessage.isNullOrBlank()) {
            p.navigateForError(initialErrorMessage)
        }
        p
    }

    var uiState by remember { mutableStateOf(presenter.state) }

    fun updateState() {
        uiState = presenter.state
    }

    // Auto-clear copy feedback after 2.5 seconds
    LaunchedEffect(uiState.copiedValueFeedback) {
        if (uiState.copiedValueFeedback != null) {
            delay(2500)
            presenter.clearCopyFeedback()
            updateState()
        }
    }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, value)
        clipboard?.setPrimaryClip(clip)
        presenter.onCopiedToClipboard("Đã sao chép: $value")
        updateState()
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = DarkBackground),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    // Header Bar
                    GuideHeader(
                        onClose = onDismissRequest,
                        currentStepIndex = uiState.currentStepIndex,
                        totalSteps = uiState.totalSteps
                    )

                    // Scrollable Body Content
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        // Free Tier Badge & Guarantee Note
                        FreeTierSafetyCard(freeTierInfo = uiState.freeTierInfo)

                        Spacer(modifier = Modifier.height(16.dp))

                        // Step Indicator Pills / Tabs
                        StepProgressIndicator(
                            steps = uiState.steps,
                            currentStepIndex = uiState.currentStepIndex,
                            onStepSelected = { idx ->
                                presenter.selectStep(idx)
                                updateState()
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Step Main Card
                        StepContentCard(
                            step = uiState.currentStep,
                            onOpenUrl = onOpenUrl,
                            onCopy = { label, value -> copyToClipboard(label, value) }
                        )

                        // Copied Feedback Banner
                        AnimatedVisibility(visible = uiState.copiedValueFeedback != null) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DistancePillBg)
                                    .border(1.dp, DistancePillBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusAvailable,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = uiState.copiedValueFeedback ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DistancePillText,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Troubleshooting FAQ Accordion Section
                        TroubleshootingFaqSection(
                            items = uiState.troubleshootingItems,
                            expandedIndexes = uiState.expandedFaqIndexes,
                            onToggle = { idx ->
                                presenter.toggleFaq(idx)
                                updateState()
                            },
                            onOpenRemediation = onOpenUrl
                        )

                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    // Bottom Navigation Actions
                    HorizontalDivider(color = DarkOutline)
                    GuideBottomNavigation(
                        canGoBack = uiState.canGoBack,
                        canGoForward = uiState.canGoForward,
                        isLastStep = uiState.isLastStep,
                        onBack = {
                            presenter.previousStep()
                            updateState()
                        },
                        onNext = {
                            presenter.nextStep()
                            updateState()
                        },
                        onDone = onDismissRequest
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideHeader(
    onClose: () -> Unit,
    currentStepIndex: Int,
    totalSteps: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(EmeraldPrimary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "BƯỚC ${currentStepIndex + 1} TRÊN $totalSteps",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Hướng dẫn lấy Google Maps API Key",
                style = MaterialTheme.typography.titleMedium,
                color = DarkOnSurface,
                fontWeight = FontWeight.Bold
            )
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(DarkSurfaceVariant)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Đóng hướng dẫn",
                tint = DarkOnSurfaceVariant
            )
        }
    }
    HorizontalDivider(color = DarkOutline)
}

@Composable
private fun FreeTierSafetyCard(freeTierInfo: FreeTierInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = EmeraldContainerDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(EmeraldPrimary)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Miễn phí 10.000 lượt/tháng",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Black,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = freeTierInfo.skuName,
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldOnContainerDark,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = freeTierInfo.quotaSummary,
                style = MaterialTheme.typography.bodySmall,
                color = DarkOnSurface,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = EmeraldPrimary,
                    modifier = Modifier
                        .size(15.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = freeTierInfo.costGuaranteeDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkOnSurfaceVariant,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun StepProgressIndicator(
    steps: List<ApiKeyGuideStep>,
    currentStepIndex: Int,
    onStepSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, _ ->
            val isCurrent = index == currentStepIndex
            val isPassed = index < currentStepIndex

            val bg = when {
                isCurrent -> EmeraldPrimary
                isPassed -> EmeraldContainerDark
                else -> DarkSurfaceVariant
            }
            val contentColor = when {
                isCurrent -> Color.Black
                isPassed -> EmeraldOnContainerDark
                else -> DarkOnSurfaceVariant
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .border(
                        1.dp,
                        if (isCurrent) EmeraldPrimary else DarkOutline,
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onStepSelected(index) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isPassed) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepContentCard(
    step: ApiKeyGuideStep,
    onOpenUrl: (String) -> Unit,
    onCopy: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Step subtitle & category
            Text(
                text = step.subtitle.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = ElectricCyan,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Step Title
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleMedium,
                color = DarkOnSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Step Instructions
            Text(
                text = step.instructions,
                style = MaterialTheme.typography.bodyMedium,
                color = DarkOnSurface,
                lineHeight = 22.sp
            )

            // Step Tip Callout if present
            if (!step.tips.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkCardBackground)
                        .border(1.dp, DarkOutline, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.Lightbulb,
                            contentDescription = null,
                            tint = StatusMaintaining,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Lưu ý quan trọng:",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusMaintaining,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = step.tips,
                                style = MaterialTheme.typography.bodySmall,
                                color = DarkOnSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // Action Buttons (Open Link / Copy Value)
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Open console URL button
                if (!step.actionUrl.isNullOrBlank()) {
                    Button(
                        onClick = { onOpenUrl(step.actionUrl) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyanContainerDark,
                            contentColor = ElectricCyan
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = step.actionLabel ?: "Mở Google Console",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Copy value button (e.g. Package name or project name)
                if (!step.copyableValue.isNullOrBlank()) {
                    val isStep4 = step.stepNumber == 4
                    val copyBtnLabel = if (isStep4) "Sao chép Package Name" else "Sao chép"

                    OutlinedButton(
                        onClick = { onCopy("Package Name", step.copyableValue) },
                        modifier = Modifier
                            .then(if (step.actionUrl.isNullOrBlank()) Modifier.weight(1f) else Modifier)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkOnSurface),
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = copyBtnLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TroubleshootingFaqSection(
    items: List<ApiKeyTroubleshootingItem>,
    expandedIndexes: Set<Int>,
    onToggle: (Int) -> Unit,
    onOpenRemediation: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Câu hỏi thường gặp & Khắc phục lỗi (HTTP 400, 403, 429)",
                style = MaterialTheme.typography.titleSmall,
                color = DarkOnSurface,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        items.forEachIndexed { index, item ->
            val isExpanded = expandedIndexes.contains(index)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .animateContentSize(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCardBackground),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isExpanded) ElectricCyan.copy(alpha = 0.5f) else DarkOutline
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(index) }
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isExpanded) ElectricCyan else DarkOnSurface,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Thu gọn" else "Mở rộng",
                            tint = DarkOnSurfaceVariant
                        )
                    }

                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = DarkOutline.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(10.dp))

                        // Cause
                        Text(
                            text = "Nguyên nhân:",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusMaintaining,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.cause,
                            style = MaterialTheme.typography.bodySmall,
                            color = DarkOnSurfaceVariant,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Solution
                        Text(
                            text = "Cách khắc phục:",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.solution,
                            style = MaterialTheme.typography.bodySmall,
                            color = DarkOnSurface,
                            lineHeight = 18.sp
                        )

                        // Remediation button if URL available
                        if (!item.remediationUrl.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { onOpenRemediation(item.remediationUrl) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 44.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyanContainerDark,
                                    contentColor = ElectricCyan
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Mở trang khắc phục sự cố",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideBottomNavigation(
    canGoBack: Boolean,
    canGoForward: Boolean,
    isLastStep: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back Button
        if (canGoBack) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkOnSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkOutline)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Quay lại",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }

        // Next / Done Button
        if (isLastStep) {
            Button(
                onClick = onDone,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color.Black
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Hoàn tất hướng dẫn",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Button(
                onClick = onNext,
                enabled = canGoForward,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = "Bước tiếp theo",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
