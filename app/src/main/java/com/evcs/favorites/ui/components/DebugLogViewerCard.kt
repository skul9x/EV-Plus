package com.evcs.favorites.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import com.evcs.favorites.ui.theme.AppIcons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogEntry
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.DarkOnSurfaceVariant
import com.evcs.favorites.ui.theme.DarkOutline
import com.evcs.favorites.ui.theme.ElectricCyan
import com.evcs.favorites.ui.theme.ElectricCyanContainerDark
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.EmeraldPrimaryLight
import com.evcs.favorites.ui.theme.StatusMaintaining
import com.evcs.favorites.ui.theme.StatusOffline
import com.evcs.favorites.ui.theme.StatusOfflineContainer
import com.evcs.favorites.ui.theme.UltraPurple
import com.evcs.favorites.ui.theme.UltraPurpleContainerDark

/**
 * Diagnostic debug log viewer card providing:
 * - Header with Terminal icon and entries count badge
 * - 3 Action buttons: Chia sẻ (Share via ACTION_SEND), Sao chép (Copy to Clipboard), Xóa log (Clear logs)
 * - Scrollable terminal-style list bounded with max height (safe from nested verticalScroll crash)
 * - Color-coded status pills per tag and log level
 * - Prominent highlight for charging forecast events
 * - Expandable technical inspection per entry (URL, latency, request/response snippets, stack traces)
 */
@Composable
fun DebugLogViewerCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val logs by AppDebugLogger.logsFlow.collectAsStateWithLifecycle()
    var expandedEntryIds by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) {
        AppDebugLogger.flush()
    }

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
            // Header Bar
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
                        imageVector = AppIcons.Terminal,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Nhật ký gỡ lỗi (Debug Log)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(EmeraldContainerDark)
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${logs.size}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldPrimary
                            )
                        }
                    }
                    Text(
                        text = "Ghi nhận phản hồi API và độ trễ mạng",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Chia sẻ | Sao chép | Xóa log
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Chia sẻ (Share)
                OutlinedButton(
                    onClick = {
                        val formatted = AppDebugLogger.getFormattedLogText()
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_TEXT, formatted)
                            type = "text/plain"
                        }
                        val chooser = Intent.createChooser(sendIntent, "Chia sẻ nhật ký gỡ lỗi").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(chooser)
                        } catch (_: Exception) {
                            Toast.makeText(context, "Không tìm thấy ứng dụng chia sẻ", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Chia sẻ",
                        modifier = Modifier.size(15.dp),
                        tint = EmeraldPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Chia sẻ",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = EmeraldPrimary
                    )
                }

                // Sao chép (Copy)
                OutlinedButton(
                    onClick = {
                        val formatted = AppDebugLogger.getFormattedLogText()
                        clipboardManager.setText(AnnotatedString(formatted))
                        Toast.makeText(context, "Đã sao chép nhật ký", Toast.LENGTH_SHORT).show()
                    },
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.ContentCopy,
                        contentDescription = "Sao chép",
                        modifier = Modifier.size(15.dp),
                        tint = EmeraldPrimary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Sao chép",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = EmeraldPrimary
                    )
                }

                // Xóa log (Clear)
                OutlinedButton(
                    onClick = {
                        AppDebugLogger.clear()
                    },
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = StatusOffline
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.DeleteOutline,
                        contentDescription = "Xóa log",
                        modifier = Modifier.size(15.dp),
                        tint = StatusOffline
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Xóa log",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = StatusOffline
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Live Log Viewer (Bounded max height prevents nested scroll measurement crash)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0D1424))
                    .border(BorderStroke(1.dp, DarkOutline), RoundedCornerShape(10.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Chưa có nhật ký hoạt động mạng",
                            style = MaterialTheme.typography.bodySmall,
                            color = DarkOnSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = logs,
                            key = { it.id }
                        ) { entry ->
                            DebugLogEntryRow(
                                entry = entry,
                                isExpanded = expandedEntryIds.contains(entry.id),
                                onToggle = {
                                    expandedEntryIds = if (expandedEntryIds.contains(entry.id)) {
                                        expandedEntryIds - entry.id
                                    } else {
                                        expandedEntryIds + entry.id
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Single debug log row with tag pill, timestamp, message, prominent forecast badge, and expandable inspection details.
 */
@Composable
private fun DebugLogEntryRow(
    entry: DebugLogEntry,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    val (tagTextColor, tagBgColor) = resolveTagColors(entry.tag, entry.level)
    val levelColor = resolveLevelColor(entry.level)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF131D31))
            .border(
                BorderStroke(
                    0.5.dp,
                    if (entry.level == DebugLogLevel.ERROR) StatusOffline.copy(alpha = 0.4f) else DarkOutline.copy(alpha = 0.5f)
                ),
                RoundedCornerShape(6.dp)
            )
            .clickable { onToggle() }
            .padding(8.dp)
    ) {
        // Tag, Level, Timestamp, and Expand Icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tag Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(tagBgColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = entry.tag.name,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = tagTextColor
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Level Pill
            Text(
                text = entry.level.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = levelColor
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Timestamp
            Text(
                text = entry.timestamp,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = DarkOnSurfaceVariant
            )

            if (entry.latencyMs != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${entry.latencyMs}ms",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = EmeraldPrimaryLight
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) "Thu gọn" else "Mở rộng",
                tint = DarkOnSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Main Message
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        // Expandable Technical Details
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF090D18))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!entry.endpointUrl.isNullOrBlank()) {
                    val methodStr = entry.method ?: "GET"
                    val statusStr = entry.statusCode?.toString() ?: "-"
                    Text(
                        text = "Endpoint: $methodStr ${entry.endpointUrl} (HTTP $statusStr)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = ElectricCyan
                    )
                }

                if (!entry.requestSnippet.isNullOrBlank()) {
                    Text(
                        text = "Request: ${entry.requestSnippet}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = DarkOnSurfaceVariant
                    )
                }

                if (!entry.responseSnippet.isNullOrBlank()) {
                    Text(
                        text = "Response: ${entry.responseSnippet}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = DarkOnSurfaceVariant
                    )
                }

                if (!entry.errorDetails.isNullOrBlank()) {
                    Text(
                        text = "Chi tiết lỗi: ${entry.errorDetails}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = StatusOffline
                    )
                }
            }
        }
    }
}

private fun resolveTagColors(tag: DebugLogTag, level: DebugLogLevel): Pair<Color, Color> {
    if (level == DebugLogLevel.ERROR) {
        return StatusOffline to StatusOfflineContainer
    }
    return when (tag) {
        DebugLogTag.NETWORK -> ElectricCyan to ElectricCyanContainerDark
        DebugLogTag.SEARCH -> UltraPurple to UltraPurpleContainerDark
        DebugLogTag.ROUTING -> EmeraldPrimaryLight to EmeraldContainerDark
        DebugLogTag.FAVORITES -> Color(0xFFF59E0B) to Color(0x26F59E0B)
        DebugLogTag.FOCUS_MODE -> Color(0xFF38BDF8) to Color(0x2638BDF8)
    }
}

private fun resolveLevelColor(level: DebugLogLevel): Color {
    return when (level) {
        DebugLogLevel.INFO -> DarkOnSurfaceVariant
        DebugLogLevel.SUCCESS -> EmeraldPrimary
        DebugLogLevel.WARN -> StatusMaintaining
        DebugLogLevel.ERROR -> StatusOffline
    }
}
