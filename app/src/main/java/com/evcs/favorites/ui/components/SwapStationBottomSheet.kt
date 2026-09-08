package com.evcs.favorites.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.data.routing.EvRouteStop
import com.evcs.favorites.ui.theme.AppIcons
import com.evcs.favorites.ui.theme.DarkCardBackground
import com.evcs.favorites.ui.theme.DarkOutline
import com.evcs.favorites.ui.theme.DarkSurface
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.ui.theme.EmeraldPrimaryLight
import com.evcs.favorites.ui.theme.StatusAvailable
import com.evcs.favorites.ui.theme.StatusBusy

/**
 * Modal Bottom Sheet enabling drivers to swap a scheduled charging stop with
 * an alternative VinFast charging station located within the corridor reach window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapStationBottomSheet(
    stop: EvRouteStop,
    onSelectStation: (Station) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(DarkOutline, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Đổi trạm sạc chặng #${stop.stopIndex}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Trạm hiện tại: ${stop.station.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldPrimaryLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val candidates = stop.alternativeStations

            if (candidates.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Không có trạm VinFast thay thế khả dụng trong hành lang an toàn của chặng này.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "CÁC TRẠM THAY THẾ KHẢ DỤNG (${candidates.size})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(candidates, key = { it.id }) { station ->
                        AlternativeStationCard(
                            station = station,
                            onSelect = {
                                onSelectStation(station)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
fun AlternativeStationCard(
    station: Station,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxWatts = station.powers.maxOfOrNull { it.typeWatts } ?: 0L
    val powerKw = if (maxWatts > 0) maxWatts / 1000 else 60
    val isAvailable = station.totalAvailablePlugs > 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkCardBackground, RoundedCornerShape(12.dp))
            .border(1.dp, DarkOutline, RoundedCornerShape(12.dp))
            .clickable { onSelect() }
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (station.address.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = station.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Power Pill
                Box(
                    modifier = Modifier
                        .background(EmeraldContainerDark, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "⚡ $powerKw kW",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldPrimaryLight
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Plug Status
                Text(
                    text = if (station.totalPlugs > 0) {
                        if (isAvailable) "🟢 Trống ${station.totalAvailablePlugs}/${station.totalPlugs}" else "🟠 Đang kín"
                    } else "⚪ Chưa có dữ liệu",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAvailable) StatusAvailable else StatusBusy
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Button(
            onClick = onSelect,
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldPrimary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Chọn", fontWeight = FontWeight.Bold)
        }
    }
}
