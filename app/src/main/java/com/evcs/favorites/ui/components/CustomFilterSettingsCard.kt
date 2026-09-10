package com.evcs.favorites.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.domain.model.CustomFilterConfig
import com.evcs.favorites.domain.model.CustomFilterMode
import com.evcs.favorites.domain.model.QuickChipOption
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.StatusOffline

/**
 * Pure helper providing numeric stepping logic and bounds clamping for custom kW filtering.
 */
object CustomFilterHelper {
    const val MIN_KW = 1
    const val MAX_KW = 500
    const val DEFAULT_KW_STEP = 10

    /**
     * Increments or decrements [currentKw] by [delta], clamping safely within [min] and [max].
     * If [currentKw] is null, positive delta starts from 0 + delta, while negative delta clamps to [min].
     */
    fun stepKw(
        currentKw: Int?,
        delta: Int,
        min: Int = MIN_KW,
        max: Int = MAX_KW
    ): Int {
        val base = currentKw ?: if (delta > 0) 0 else (min + DEFAULT_KW_STEP)
        val candidate = base + delta
        return candidate.coerceIn(min, max)
    }
}

/**
 * Top-level delegation function for convenient stepper testing and invocation.
 */
fun stepKw(
    currentKw: Int?,
    delta: Int,
    min: Int = CustomFilterHelper.MIN_KW,
    max: Int = CustomFilterHelper.MAX_KW
): Int = CustomFilterHelper.stepKw(currentKw, delta, min, max)

/**
 * State holder managing Custom Filter configuration, mutual exclusion between
 * quick chips and manual min/max inputs, real-time numeric validation, steppers, and live preview generation.
 */
class CustomFilterFormState(
    val initialConfig: CustomFilterConfig? = null
) {
    var mode by mutableStateOf(initialConfig?.mode ?: CustomFilterMode.QUICK_CHIP)
    var selectedChip by mutableStateOf<QuickChipOption?>(
        if (initialConfig?.mode == CustomFilterMode.CUSTOM_RANGE) null
        else initialConfig?.quickChip ?: QuickChipOption.ALL
    )
    var minKwText by mutableStateOf(initialConfig?.minKw?.toString() ?: "")
    var maxKwText by mutableStateOf(initialConfig?.maxKw?.toString() ?: "")

    var lastCommittedConfig by mutableStateOf<CustomFilterConfig?>(
        initialConfig ?: CustomFilterConfig(mode = CustomFilterMode.QUICK_CHIP, quickChip = QuickChipOption.ALL)
    )

    val minKw: Int?
        get() = minKwText.toIntOrNull()

    val maxKw: Int?
        get() = maxKwText.toIntOrNull()

    val isDirty: Boolean
        get() {
            val current = buildConfig() ?: return false
            val base = lastCommittedConfig ?: CustomFilterConfig(
                mode = CustomFilterMode.QUICK_CHIP,
                quickChip = QuickChipOption.ALL
            )
            return current != base
        }

    fun stepMinKw(delta: Int = CustomFilterHelper.DEFAULT_KW_STEP) {
        val next = CustomFilterHelper.stepKw(minKw, delta)
        minKwText = next.toString()
        selectedChip = null
        mode = CustomFilterMode.CUSTOM_RANGE
    }

    fun stepMaxKw(delta: Int = CustomFilterHelper.DEFAULT_KW_STEP) {
        val next = CustomFilterHelper.stepKw(maxKw, delta)
        maxKwText = next.toString()
        selectedChip = null
        mode = CustomFilterMode.CUSTOM_RANGE
    }

    fun applyCustomRange(): CustomFilterConfig? {
        if (!isValid || mode != CustomFilterMode.CUSTOM_RANGE) return null
        val config = buildConfig()
        if (config != null) {
            lastCommittedConfig = config
        }
        return config
    }

    fun selectQuickChip(chip: QuickChipOption): CustomFilterConfig {
        mode = CustomFilterMode.QUICK_CHIP
        selectedChip = chip
        minKwText = ""
        maxKwText = ""
        val config = CustomFilterConfig(
            mode = CustomFilterMode.QUICK_CHIP,
            quickChip = chip
        )
        lastCommittedConfig = config
        return config
    }

    fun onMinKwChanged(text: String) {
        val filtered = text.filter { it.isDigit() }
        minKwText = filtered
        selectedChip = null
        mode = CustomFilterMode.CUSTOM_RANGE
    }

    fun onMaxKwChanged(text: String) {
        val filtered = text.filter { it.isDigit() }
        maxKwText = filtered
        selectedChip = null
        mode = CustomFilterMode.CUSTOM_RANGE
    }

    fun clearMinKw() {
        minKwText = ""
    }

    fun clearMaxKw() {
        maxKwText = ""
    }

    val errorMessage: String?
        get() {
            if (mode == CustomFilterMode.QUICK_CHIP) return null
            val min = minKw
            val max = maxKw
            if (minKwText.isNotEmpty() && (min == null || min !in 1..500)) {
                return "Min phải từ 1 đến 500 kW"
            }
            if (maxKwText.isNotEmpty() && (max == null || max !in 1..500)) {
                return "Max phải từ 1 đến 500 kW"
            }
            if (min != null && max != null && min > max) {
                return "Min không được lớn hơn Max"
            }
            if (minKwText.isEmpty() && maxKwText.isEmpty()) {
                return "Vui lòng nhập Min hoặc Max"
            }
            return null
        }

    val isValid: Boolean
        get() {
            return when (mode) {
                CustomFilterMode.QUICK_CHIP -> selectedChip != null
                CustomFilterMode.CUSTOM_RANGE -> {
                    val min = minKw
                    val max = maxKw
                    if (min == null && max == null) return false
                    if (min != null && min !in 1..500) return false
                    if (max != null && max !in 1..500) return false
                    if (min != null && max != null && min > max) return false
                    true
                }
            }
        }

    val livePreview: String
        get() {
            return when (mode) {
                CustomFilterMode.QUICK_CHIP -> {
                    when (selectedChip ?: QuickChipOption.ALL) {
                        QuickChipOption.ALL -> "👉 Đang lọc: Tất cả các trạm có cổng trống"
                        QuickChipOption.AC -> "👉 Đang lọc: Cổng AC (11kW, 22kW) còn trống"
                        QuickChipOption.DC_LE_30KW -> "👉 Đang lọc: Cổng DC ≤ 30kW còn trống"
                        QuickChipOption.DC_BETWEEN_30_60KW -> "👉 Đang lọc: Cổng DC từ 30kW - 60kW còn trống"
                        QuickChipOption.DC_GE_60KW -> "👉 Đang lọc: Cổng DC ≥ 60kW còn trống"
                        QuickChipOption.DC_GE_120KW -> "👉 Đang lọc: Cổng DC ≥ 120kW còn trống"
                    }
                }
                CustomFilterMode.CUSTOM_RANGE -> {
                    val min = minKw
                    val max = maxKw
                    if (min != null && max != null) {
                        "👉 Đang lọc: Cổng từ $min kW đến $max kW còn trống"
                    } else if (min != null) {
                        "👉 Đang lọc: Cổng công suất ≥ $min kW còn trống"
                    } else if (max != null) {
                        "👉 Đang lọc: Cổng công suất ≤ $max kW còn trống"
                    } else {
                        "👉 Đang lọc: Tất cả các trạm có cổng trống"
                    }
                }
            }
        }

    fun buildConfig(): CustomFilterConfig? {
        if (!isValid) return null
        return when (mode) {
            CustomFilterMode.QUICK_CHIP -> CustomFilterConfig(
                mode = CustomFilterMode.QUICK_CHIP,
                quickChip = selectedChip ?: QuickChipOption.ALL
            )
            CustomFilterMode.CUSTOM_RANGE -> CustomFilterConfig(
                mode = CustomFilterMode.CUSTOM_RANGE,
                minKw = minKw,
                maxKw = maxKw
            )
        }
    }
}

@Composable
fun rememberCustomFilterFormState(
    initialConfig: CustomFilterConfig? = null
): CustomFilterFormState = remember(initialConfig) {
    CustomFilterFormState(initialConfig)
}

/**
 * Custom filter configuration card providing:
 * - Header with description
 * - Quick chip selector buttons with instant auto-save
 * - Min kW & Max kW number inputs with +/- 10kW steppers and 1-tap clear buttons
 * - Inline "Áp dụng" button enabling when custom range is valid and dirty
 * - Real-time Live Preview pill
 * - Inline validation warning on invalid range
 * - Dedicated Save button
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomFilterSettingsCard(
    state: CustomFilterFormState,
    onSaveCustomFilter: (CustomFilterConfig) -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
    showQuickChips: Boolean = false,
    onAutoSave: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val maxFocusRequester = remember { FocusRequester() }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCardBackground
        ),
        border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(EmeraldContainerDark)
                ) {
                    Icon(
                        imageVector = AppIcons.Tune,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Bộ lọc tùy chỉnh (Custom Filter)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tùy chỉnh khoảng công suất kW mong muốn",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showQuickChips) {
                Spacer(modifier = Modifier.height(16.dp))

                // 1. Quick Chips Header
                Text(
                    text = "Chọn nhanh loại cổng / công suất:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Chips Wrap Grid with instant auto-save
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickChipOption.values().forEach { chip ->
                        val isSelected = state.mode == CustomFilterMode.QUICK_CHIP && state.selectedChip == chip
                        val chipBg = if (isSelected) EmeraldPrimary else EmeraldContainerDark.copy(alpha = 0.35f)
                        val chipTextColor = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                        val chipBorderColor = if (isSelected) EmeraldPrimary else EmeraldPrimary.copy(alpha = 0.2f)

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(chipBg)
                                .border(BorderStroke(1.dp, chipBorderColor), RoundedCornerShape(20.dp))
                                .clickable {
                                    val config = state.selectQuickChip(chip)
                                    onSaveCustomFilter(config)
                                    onAutoSave?.invoke()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = chip.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = chipTextColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Manual Range Inputs with +/- 10kW Stepper Buttons
            Text(
                text = if (isLandscape) "Khoảng công suất tùy chỉnh (Chạm +/- 10kW):" else if (!showQuickChips) "Khoảng công suất tùy chỉnh (+/- 10kW):" else "Hoặc chỉnh khoảng công suất tùy chỉnh (+/- 10kW):",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isLandscape) {
                // Automotive Stepper Row without soft keyboard text fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Min Stepper Box
                    AutomotiveStepperBox(
                        title = "Công suất Min",
                        currentKw = state.minKw,
                        onDecrement = { state.stepMinKw(-CustomFilterHelper.DEFAULT_KW_STEP) },
                        onIncrement = { state.stepMinKw(CustomFilterHelper.DEFAULT_KW_STEP) },
                        onClear = { state.clearMinKw() },
                        modifier = Modifier.weight(1f)
                    )

                    // Max Stepper Box
                    AutomotiveStepperBox(
                        title = "Công suất Max",
                        currentKw = state.maxKw,
                        onDecrement = { state.stepMaxKw(-CustomFilterHelper.DEFAULT_KW_STEP) },
                        onIncrement = { state.stepMaxKw(CustomFilterHelper.DEFAULT_KW_STEP) },
                        onClear = { state.clearMaxKw() },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                // Min kW Column
                Column(modifier = Modifier.weight(1f)) {
                    val isMinError = state.mode == CustomFilterMode.CUSTOM_RANGE &&
                            state.minKwText.isNotEmpty() &&
                            (state.minKw == null || state.minKw !in 1..500 || (state.minKw != null && state.maxKw != null && state.minKw!! > state.maxKw!!))

                    OutlinedTextField(
                        value = state.minKwText,
                        onValueChange = { state.onMinKwChanged(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Min") },
                        placeholder = { Text("vd: 30") },
                        suffix = {
                            Text(
                                text = "kW",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldPrimary
                            )
                        },
                        singleLine = true,
                        isError = isMinError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { maxFocusRequester.requestFocus() }
                        ),
                        trailingIcon = {
                            if (state.minKwText.isNotEmpty()) {
                                IconButton(onClick = { state.clearMinKw() }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Xóa Min",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Min Steppers (-10, +10)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { state.stepMinKw(-CustomFilterHelper.DEFAULT_KW_STEP) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = EmeraldContainerDark.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("-10", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }

                        FilledTonalButton(
                            onClick = { state.stepMinKw(CustomFilterHelper.DEFAULT_KW_STEP) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = EmeraldContainerDark.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("+10", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Max kW Column
                Column(modifier = Modifier.weight(1f)) {
                    val isMaxError = state.mode == CustomFilterMode.CUSTOM_RANGE &&
                            state.maxKwText.isNotEmpty() &&
                            (state.maxKw == null || state.maxKw !in 1..500 || (state.minKw != null && state.maxKw != null && state.minKw!! > state.maxKw!!))

                    OutlinedTextField(
                        value = state.maxKwText,
                        onValueChange = { state.onMaxKwChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(maxFocusRequester),
                        label = { Text("Max") },
                        placeholder = { Text("vd: 150") },
                        suffix = {
                            Text(
                                text = "kW",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldPrimary
                            )
                        },
                        singleLine = true,
                        isError = isMaxError,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        trailingIcon = {
                            if (state.maxKwText.isNotEmpty()) {
                                IconButton(onClick = { state.clearMaxKw() }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Xóa Max",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldPrimary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Max Steppers (-10, +10)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { state.stepMaxKw(-CustomFilterHelper.DEFAULT_KW_STEP) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = EmeraldContainerDark.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("-10", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }

                        FilledTonalButton(
                            onClick = { state.stepMaxKw(CustomFilterHelper.DEFAULT_KW_STEP) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = EmeraldContainerDark.copy(alpha = 0.5f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text("+10", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

            // Compact Inline "Áp dụng" Button
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val config = state.applyCustomRange()
                        if (config != null) {
                            focusManager.clearFocus()
                            onSaveCustomFilter(config)
                            onAutoSave?.invoke()
                        }
                    },
                    enabled = state.isValid && state.mode == CustomFilterMode.CUSTOM_RANGE && state.isDirty,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldPrimary,
                        contentColor = MaterialTheme.colorScheme.surface,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Áp dụng",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Inline Validation Warning
            AnimatedVisibility(
                visible = state.errorMessage != null &&
                        state.mode == CustomFilterMode.CUSTOM_RANGE &&
                        (state.minKwText.isNotEmpty() || state.maxKwText.isNotEmpty())
            ) {
                state.errorMessage?.let { msg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = AppIcons.Error,
                            contentDescription = null,
                            tint = StatusOffline,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = StatusOffline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. User-Friendly Live Preview Row
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = EmeraldContainerDark.copy(alpha = 0.35f)
                ),
                border = BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.livePreview,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 20.sp
                        ),
                        color = EmeraldPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. Save Custom Filter Button
            Button(
                onClick = {
                    val config = state.buildConfig()
                    if (config != null) {
                        focusManager.clearFocus()
                        state.lastCommittedConfig = config
                        onSaveCustomFilter(config)
                        onAutoSave?.invoke()
                    }
                },
                enabled = state.isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Icon(
                    imageVector = AppIcons.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Lưu bộ lọc tùy chỉnh",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Automotive stepper box designed specifically for landscape/car usage:
 * - Touch targets >= 56dp for driving safety
 * - High contrast kW display box
 * - No virtual keyboard / OutlinedTextField required
 * - Quick reset/clear icon
 */
@Composable
fun AutomotiveStepperBox(
    title: String,
    currentKw: Int?,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (currentKw != null) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Xóa $title",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(28.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Value Display Pill
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(EmeraldContainerDark.copy(alpha = 0.35f))
                    .border(BorderStroke(1.dp, EmeraldPrimary.copy(alpha = 0.3f)), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = if (currentKw != null) "$currentKw kW" else "-- kW",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (currentKw != null) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Large Touch Stepper Buttons (>= 56dp height for automotive touch target)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onDecrement,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = EmeraldContainerDark.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "-10",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                FilledTonalButton(
                    onClick = onIncrement,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = EmeraldContainerDark.copy(alpha = 0.6f),
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "+10",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
