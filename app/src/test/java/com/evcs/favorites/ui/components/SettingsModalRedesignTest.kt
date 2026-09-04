package com.evcs.favorites.ui.components

import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Comprehensive single file-based test for Phase 03:
 * Settings UI Overhaul, Min/Max Labels & Debug Log Viewer.
 *
 * Verifies:
 * 1. Default routing settings prioritize OSRM without requiring Google Key.
 * 2. CustomFilterFormState validation messages consistently use "Min" and "Max" nomenclature.
 * 3. Debug Log actions (Share text, Clipboard text generation, and Clear log dispatch).
 * 4. Log formatting for charging forecast and error states.
 * 5. Elimination of custom vertical scrollbar indicator while retaining smooth vertical scroll.
 */
class SettingsModalRedesignTest {

    @Before
    fun setUp() {
        AppDebugLogger.clear()
    }

    @Test
    fun testDefaultRoutingSettings_defaultsCleanlyToOsrmWithoutGoogleKey() {
        val defaultSettings = RoutingSettings()
        assertEquals(RoutingEngineMode.OSRM_ONLY, defaultSettings.preferredEngine)
        assertEquals("", defaultSettings.googleApiKey)
        assertTrue(defaultSettings.autoFallbackEnabled)
        assertTrue(defaultSettings.googleApiKey.isBlank())
    }

    @Test
    fun testCustomFilterValidationLogic_usesConsistentMinMaxNomenclature() {
        val state = CustomFilterFormState()

        // 1. Min > Max validation
        state.onMinKwChanged("180")
        state.onMaxKwChanged("60")
        assertFalse(state.isValid)
        assertEquals("Min không được lớn hơn Max", state.errorMessage)

        // 2. Min boundary checks (> 500 or <= 0)
        state.onMinKwChanged("501")
        state.onMaxKwChanged("600")
        assertFalse(state.isValid)
        assertEquals("Min phải từ 1 đến 500 kW", state.errorMessage)

        // 3. Max boundary checks (> 500 or <= 0)
        state.onMinKwChanged("60")
        state.onMaxKwChanged("550")
        assertFalse(state.isValid)
        assertEquals("Max phải từ 1 đến 500 kW", state.errorMessage)

        state.clearMinKw()
        state.onMaxKwChanged("0")
        assertFalse(state.isValid)
        assertEquals("Max phải từ 1 đến 500 kW", state.errorMessage)

        // 4. Empty text fields in CUSTOM_RANGE
        state.clearMinKw()
        state.clearMaxKw()
        assertFalse(state.isValid)
        assertEquals("Vui lòng nhập Min hoặc Max", state.errorMessage)

        // 5. Valid range accepts and creates config
        state.onMinKwChanged("30")
        state.onMaxKwChanged("120")
        assertTrue(state.isValid)
        assertNull(state.errorMessage)
        val config = state.buildConfig()
        assertNotNull(config)
        assertEquals(30, config?.minKw)
        assertEquals(120, config?.maxKw)
    }

    @Test
    fun testDebugLogActions_shareCopyFormat_andClearDispatch() {
        // Initially empty
        assertEquals("Chưa có nhật ký hoạt động mạng.", AppDebugLogger.getFormattedLogText())
        assertTrue(AppDebugLogger.getLogs().isEmpty())
        assertTrue(AppDebugLogger.logsFlow.value.isEmpty())

        // Add network and search log entries
        AppDebugLogger.log(
            tag = DebugLogTag.SEARCH,
            level = DebugLogLevel.SUCCESS,
            message = "Tìm kiếm trạm sạc xung quanh thành công",
            endpointUrl = "https://api.evcs.vn/v1/stations/station-123",
            method = "GET",
            statusCode = 200,
            latencyMs = 145
        )

        AppDebugLogger.log(
            tag = DebugLogTag.NETWORK,
            level = DebugLogLevel.ERROR,
            message = "Lỗi kết nối máy chủ",
            endpointUrl = "https://api.evcs.vn/v1/stations/nearby",
            method = "POST",
            statusCode = 502,
            latencyMs = 2100,
            requestSnippet = "{\"lat\": 10.77, \"lng\": 106.69}",
            responseSnippet = "Bad Gateway",
            errorDetails = "java.net.SocketTimeoutException: timeout"
        )

        // Verify log count in flow
        assertEquals(2, AppDebugLogger.logsFlow.value.size)
        assertEquals(2, AppDebugLogger.getLogs().size)

        // Verify formatted log text suitable for Share Intent and Clipboard copying
        val formattedText = AppDebugLogger.getFormattedLogText()
        assertTrue(formattedText.contains("=== EVCS DEBUG LOGS ==="))
        assertTrue(formattedText.contains("Tổng số mục: 2"))
        assertTrue(formattedText.contains("Tìm kiếm trạm sạc xung quanh thành công"))
        assertTrue(formattedText.contains("Endpoint: GET https://api.evcs.vn/v1/stations/station-123 (HTTP 200, 145ms)"))
        assertTrue(formattedText.contains("Endpoint: POST https://api.evcs.vn/v1/stations/nearby (HTTP 502, 2100ms)"))
        assertTrue(formattedText.contains("java.net.SocketTimeoutException: timeout"))

        // Verify Clear Log dispatch
        AppDebugLogger.clear()
        assertTrue(AppDebugLogger.getLogs().isEmpty())
        assertTrue(AppDebugLogger.logsFlow.value.isEmpty())
        assertEquals("Chưa có nhật ký hoạt động mạng.", AppDebugLogger.getFormattedLogText())
    }

    @Test
    fun testLogViewerEntryFormatting_handlesNetworkAndErrorStates() {
        val successEntry = com.evcs.favorites.data.logging.DebugLogEntry(
            tag = DebugLogTag.SEARCH,
            level = DebugLogLevel.SUCCESS,
            message = "Tìm kiếm trạm thành công",
            endpointUrl = "https://api.evcs.vn/stations/detail",
            statusCode = 200,
            latencyMs = 88
        )

        assertEquals(DebugLogTag.SEARCH, successEntry.tag)
        assertEquals(DebugLogLevel.SUCCESS, successEntry.level)
        assertEquals("Tìm kiếm trạm thành công", successEntry.message)

        val errorEntry = com.evcs.favorites.data.logging.DebugLogEntry(
            tag = DebugLogTag.NETWORK,
            level = DebugLogLevel.ERROR,
            message = "Kết nối thất bại",
            statusCode = 500,
            errorDetails = "HTTP 500 Internal Server Error"
        )

        assertEquals(DebugLogTag.NETWORK, errorEntry.tag)
        assertEquals(DebugLogLevel.ERROR, errorEntry.level)
        assertEquals("HTTP 500 Internal Server Error", errorEntry.errorDetails)
    }

    @Test
    fun testRoutingSettingsModalSource_hasNoCustomScrollbarIndicatorModifier() {
        val modalSourceFile = File("src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt")
        val altPathFile = File("app/src/main/java/com/evcs/favorites/ui/components/RoutingSettingsModal.kt")
        val targetFile = if (modalSourceFile.exists()) modalSourceFile else altPathFile

        assertTrue("RoutingSettingsModal source file must exist", targetFile.exists())
        val content = targetFile.readText()

        // Verify removal of .verticalScrollbar modifier
        assertFalse(
            "RoutingSettingsModal must NOT contain verticalScrollbar indicator modifier",
            content.contains(".verticalScrollbar(")
        )

        // Verify retention of verticalScroll
        assertTrue(
            "RoutingSettingsModal must continue to support smooth scrolling via verticalScroll",
            content.contains(".verticalScroll(scrollState)")
        )

        // Verify DebugLogViewerCard is embedded
        assertTrue(
            "RoutingSettingsModal must embed DebugLogViewerCard",
            content.contains("DebugLogViewerCard()")
        )

        // Verify removal of Google Key elements
        assertFalse(
            "RoutingSettingsModal must not contain GoogleApiKeyGuideModal",
            content.contains("GoogleApiKeyGuideModal")
        )
        assertFalse(
            "RoutingSettingsModal must not contain KeyValidationState",
            content.contains("KeyValidationState")
        )
    }
}
