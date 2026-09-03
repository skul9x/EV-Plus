package com.evcs.favorites.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.evcs.favorites.data.model.Station
import com.evcs.favorites.ui.theme.EmeraldContainerDark
import com.evcs.favorites.ui.theme.EmeraldPrimary
import com.evcs.favorites.util.StationUrlBuilder
import kotlinx.coroutines.launch

/**
 * In-app interactive Station Detail View hosted inside a Material 3 [ModalBottomSheet].
 * Displays real-time port telemetry, charging density charts, peak stats, and station info
 * matching the official EVCS experience with session cookie injection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationDetailModal(
    station: Station?,
    onDismiss: () -> Unit,
    cookieHeader: String? = null,
    modifier: Modifier = Modifier
) {
    if (station == null) return

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxHeight(0.92f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Bar: Station Name + Address + Dismiss 'X' Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
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
                        imageVector = Icons.Default.EvStation,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = station.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            onDismiss()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Đóng chi tiết trạm",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // WebView Body with Cookie Injection & Loading State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val detailUrl = remember(station.id, station.name) {
                    StationUrlBuilder.buildStationDetailUrl(station)
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            // Prevent gesture conflicts between ModalBottomSheet drag gestures and WebView scrolling
                            isNestedScrollingEnabled = true
                            overScrollMode = WebView.OVER_SCROLL_NEVER

                            settings.apply {
                                @SuppressLint("SetJavaScriptEnabled")
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                cacheMode = WebSettings.LOAD_DEFAULT
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            // Inject authenticated session cookies
                            if (!cookieHeader.isNullOrBlank()) {
                                try {
                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)
                                    cookieHeader.split(";").forEach { cookie ->
                                        val trimmed = cookie.trim()
                                        if (trimmed.isNotEmpty()) {
                                            cookieManager.setCookie("https://evcs.vn", trimmed)
                                        }
                                    }
                                    cookieManager.flush()
                                } catch (_: Exception) {
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean = false
                            }

                            val headers = if (!cookieHeader.isNullOrBlank()) {
                                mapOf("Cookie" to cookieHeader)
                            } else {
                                emptyMap()
                            }

                            loadUrl(detailUrl, headers)
                        }
                    }
                )

                // Smooth loading indicator overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = EmeraldPrimary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Đang tải thông tin chi tiết trạm...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
