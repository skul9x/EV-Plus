package com.evcs.favorites

import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingSettings
import com.evcs.favorites.ui.components.RoutingSettingsHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Comprehensive single file-based test for Phase 03: Routing Settings Modal UX,
 * Sanitization, Error Remediation Deep-Links, and Settings Updating.
 *
 * Verifies:
 * 1. sanitizeApiKey correctly strips leading/trailing spaces, newlines, carriage returns, and tabs.
 * 2. Validation failure with HTTP 403 Billing generates the Billing console URI.
 * 3. Validation failure with HTTP 403 Routes API generates the Routes API Library URI.
 * 4. Validation failure with HTTP 403 Restrictions generates the Credentials URI.
 * 5. Validation failure with HTTP 429 Quota generates the Quotas URI.
 * 6. buildUpdatedSettings properly sanitizes the API key, updates preferredEngine and autoFallbackEnabled,
 *    and preserves custom OSRM URLs.
 */
class RoutingSettingsModalUxTest {

    @Test
    fun testSanitizeApiKey_stripsSpacesNewlinesTabsAndCarriageReturns() {
        // Leading/trailing spaces
        val keyWithSpaces = "   AIzaSyD_TestKey123   "
        assertEquals("AIzaSyD_TestKey123", RoutingSettingsHelper.sanitizeApiKey(keyWithSpaces))

        // Newlines and carriage returns
        val keyWithNewlines = "\r\n\nAIzaSyD_TestKey123\r\n"
        assertEquals("AIzaSyD_TestKey123", RoutingSettingsHelper.sanitizeApiKey(keyWithNewlines))

        // Tabs
        val keyWithTabs = "\t\tAIzaSyD_TestKey123\t"
        assertEquals("AIzaSyD_TestKey123", RoutingSettingsHelper.sanitizeApiKey(keyWithTabs))

        // Mixed combo of whitespace, newlines, tabs, and carriage returns
        val complexKey = " \r\n\t  AIzaSyD_ComplexKey456 \t\r\n "
        assertEquals("AIzaSyD_ComplexKey456", RoutingSettingsHelper.sanitizeApiKey(complexKey))

        // Empty and whitespace-only strings
        assertEquals("", RoutingSettingsHelper.sanitizeApiKey(""))
        assertEquals("", RoutingSettingsHelper.sanitizeApiKey("   \r\n\t  "))
    }

    @Test
    fun testResolveRemediationForError_http403Billing_generatesBillingConsoleUri() {
        val testCases = listOf(
            "HTTP 403 Billing",
            "HTTP 403 Billing: Billing not enabled for this project",
            "Dự án Google Cloud chưa kích hoạt thanh toán (Billing).",
            "Billing must be linked to use Google Cloud APIs"
        )

        for (errMsg in testCases) {
            val remediation = RoutingSettingsHelper.resolveRemediationForError(errMsg)
            assertNotNull("Remediation must not be null for billing error: $errMsg", remediation)
            assertEquals(
                "Billing error must generate https://console.cloud.google.com/billing",
                "https://console.cloud.google.com/billing",
                remediation!!.url
            )
            assertEquals("Kích hoạt Billing ngay", remediation.label)
            assertEquals(0, remediation.guideStepIndex)
        }
    }

    @Test
    fun testResolveRemediationForError_http403RoutesApi_generatesRoutesApiLibraryUri() {
        val testCases = listOf(
            "HTTP 403 Routes API",
            "HTTP 403: Routes API has not been used in project before or it is disabled.",
            "Chưa kích hoạt 'Routes API' trên dự án Google Cloud của bạn.",
            "service_disabled: Routes API not enabled"
        )

        for (errMsg in testCases) {
            val remediation = RoutingSettingsHelper.resolveRemediationForError(errMsg)
            assertNotNull("Remediation must not be null for Routes API error: $errMsg", remediation)
            assertEquals(
                "Routes API error must generate https://console.cloud.google.com/apis/library/routes.googleapis.com",
                "https://console.cloud.google.com/apis/library/routes.googleapis.com",
                remediation!!.url
            )
            assertEquals("Bật Routes API", remediation.label)
            assertEquals(1, remediation.guideStepIndex)
        }
    }

    @Test
    fun testResolveRemediationForError_http403Restrictions_generatesCredentialsUri() {
        val testCases = listOf(
            "HTTP 403 Restrictions",
            "HTTP 403 Restriction: API keys with Android app restrictions are not supported",
            "Khóa API bị giới hạn ứng dụng hoặc IP. Vui lòng kiểm tra cài đặt hạn chế trên Google Cloud.",
            "API key restriction error on credentials"
        )

        for (errMsg in testCases) {
            val remediation = RoutingSettingsHelper.resolveRemediationForError(errMsg)
            assertNotNull("Remediation must not be null for restriction error: $errMsg", remediation)
            assertEquals(
                "Restrictions error must generate https://console.cloud.google.com/apis/credentials",
                "https://console.cloud.google.com/apis/credentials",
                remediation!!.url
            )
            assertEquals("Kiểm tra giới hạn khóa", remediation.label)
            assertEquals(3, remediation.guideStepIndex)
        }
    }

    @Test
    fun testResolveRemediationForError_http429Quota_generatesQuotasUri() {
        val testCases = listOf(
            "HTTP 429 Quota",
            "HTTP 429: Quota exceeded for project",
            "Vượt quá hạn ngạch yêu cầu của Google Cloud API.",
            "Rate limit and quota check required"
        )

        for (errMsg in testCases) {
            val remediation = RoutingSettingsHelper.resolveRemediationForError(errMsg)
            assertNotNull("Remediation must not be null for quota error: $errMsg", remediation)
            assertEquals(
                "Quota error must generate https://console.cloud.google.com/apis/api/routes.googleapis.com/quotas",
                "https://console.cloud.google.com/apis/api/routes.googleapis.com/quotas",
                remediation!!.url
            )
            assertEquals("Kiểm tra hạn ngạch", remediation.label)
        }
    }

    @Test
    fun testResolveRemediationForError_unhandledErrorsAndBlank_returnsNull() {
        assertNull(RoutingSettingsHelper.resolveRemediationForError(""))
        assertNull(RoutingSettingsHelper.resolveRemediationForError("   "))
        assertNull(RoutingSettingsHelper.resolveRemediationForError("Lỗi kết nối mạng timeout (Network Timeout)"))
    }

    @Test
    fun testBuildUpdatedSettings_sanitizesKeyAndUpdatesSettingsPreservingCustomOsrmUrl() {
        val initialSettings = RoutingSettings(
            googleApiKey = "AIzaSyOldKey",
            preferredEngine = RoutingEngineMode.AUTO,
            autoFallbackEnabled = true,
            customOsrmServerUrl = "https://custom.osrm.server/route/v1"
        )

        val uncleanedKey = "\r\n  AIzaSyNewSanitizedKey789 \t\n"
        val updated = RoutingSettingsHelper.buildUpdatedSettings(
            current = initialSettings,
            apiKey = uncleanedKey,
            engine = RoutingEngineMode.GOOGLE_ONLY,
            autoFallback = false
        )

        assertEquals("AIzaSyNewSanitizedKey789", updated.googleApiKey)
        assertEquals(RoutingEngineMode.GOOGLE_ONLY, updated.preferredEngine)
        assertEquals(false, updated.autoFallbackEnabled)
        assertEquals(
            "Custom OSRM URL must be preserved intact",
            "https://custom.osrm.server/route/v1",
            updated.customOsrmServerUrl
        )
    }
}
