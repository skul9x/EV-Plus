package com.evcs.favorites

import com.evcs.favorites.data.routing.ApiKeyErrorCode
import com.evcs.favorites.data.routing.ApiKeyGuideProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01: Guide Data Models, Content & Troubleshooting Provider.
 *
 * Validates:
 * 1. 5-step structured walkthrough pagination, sequence, non-empty fields, and valid console URLs.
 * 2. Critical Step 4 security restrictions: "None" application restriction, "Routes API" restriction,
 *    and package identifier 'com.evcs.favorites'.
 * 3. Structured troubleshooting items covering HTTP 400, 403 (Billing, Routes API, Restrictions),
 *    429 (Quota), and Network errors.
 * 4. Exact and substring error-to-troubleshooting mapping for all error messages returned by
 *    RoutingPreferencesManager.validateGoogleApiKey.
 * 5. Free tier information with 10,000 requests/month threshold, $0 budget alert guarantee,
 *    and safety recommendations.
 * 6. Official Google Cloud Console constant deep-links and package identifier.
 */
class ApiKeyGuideProviderTest {

    @Test
    fun testGuideSteps_returnsExactlyFiveOrderedStepsWithValidContent() {
        val steps = ApiKeyGuideProvider.getGuideSteps()
        assertEquals("Guide must contain exactly 5 steps", 5, steps.size)

        for (i in steps.indices) {
            val step = steps[i]
            val expectedStepNumber = i + 1
            assertEquals("Step number must be sequential 1-based", expectedStepNumber, step.stepNumber)
            assertTrue("Step $expectedStepNumber title must not be blank", step.title.isNotBlank())
            assertTrue("Step $expectedStepNumber subtitle must not be blank", step.subtitle.isNotBlank())
            assertTrue("Step $expectedStepNumber instructions must not be blank", step.instructions.isNotBlank())

            // Steps 1 to 4 must have valid action URLs and labels to Google Cloud Console
            if (expectedStepNumber <= 4) {
                assertNotNull("Step $expectedStepNumber must have an action URL", step.actionUrl)
                assertTrue(
                    "Step $expectedStepNumber action URL must be a valid HTTPS Google Cloud URL",
                    step.actionUrl!!.startsWith("https://console.cloud.google.com")
                )
                assertNotNull("Step $expectedStepNumber must have an action button label", step.actionLabel)
                assertTrue("Step $expectedStepNumber action label must not be blank", step.actionLabel!!.isNotBlank())
            }
        }

        // Step 1 validation
        val step1 = steps[0]
        assertTrue("Step 1 must mention Billing", step1.title.contains("thanh toán", ignoreCase = true) || step1.instructions.contains("Billing", ignoreCase = true))
        assertTrue("Step 1 must mention 10.000 free requests", step1.instructions.contains("10.000"))
        assertTrue("Step 1 must mention $0 budget alert", step1.instructions.contains("$0"))
        assertEquals("Step 1 URL must be billing console", ApiKeyGuideProvider.URL_BILLING, step1.actionUrl)

        // Step 2 validation
        val step2 = steps[1]
        assertTrue("Step 2 must mention Routes API", step2.title.contains("Routes API", ignoreCase = true) || step2.instructions.contains("Routes API"))
        assertEquals("Step 2 URL must be Routes API library", ApiKeyGuideProvider.URL_ROUTES_API_LIBRARY, step2.actionUrl)

        // Step 3 validation
        val step3 = steps[2]
        assertTrue("Step 3 must mention API key creation", step3.title.contains("khóa API", ignoreCase = true) || step3.title.contains("API Key", ignoreCase = true))
        assertTrue("Step 3 instructions must reference AIza prefix", step3.instructions.contains("AIza"))
        assertEquals("Step 3 URL must be credentials console", ApiKeyGuideProvider.URL_CREDENTIALS, step3.actionUrl)

        // Step 4 validation (Critical for BYOK)
        val step4 = steps[3]
        assertTrue("Step 4 must explain API Restrictions to Routes API", step4.instructions.contains("Routes API") && step4.instructions.contains("Restrict key"))
        assertTrue(
            "Step 4 must instruct selecting 'None' for Application Restrictions",
            step4.instructions.contains("None") && step4.instructions.contains("Không giới hạn")
        )
        assertTrue(
            "Step 4 must explain why SHA-1 / Android apps restriction causes 403",
            step4.instructions.contains("403") && step4.instructions.contains("SHA-1")
        )
        assertEquals("Step 4 copyable value must be package name", ApiKeyGuideProvider.PACKAGE_NAME, step4.copyableValue)
        assertEquals("Step 4 copyable value must match com.evcs.favorites", "com.evcs.favorites", step4.copyableValue)

        // Step 5 validation
        val step5 = steps[4]
        assertTrue("Step 5 instructions must mention testing connection", step5.instructions.contains("Kiểm tra kết nối"))
        assertTrue("Step 5 instructions must mention saving settings", step5.instructions.contains("Lưu cài đặt"))
    }

    @Test
    fun testTroubleshootingItems_coversAllPrimaryFailureCategories() {
        val items = ApiKeyGuideProvider.getTroubleshootingItems()
        assertEquals("Must provide exactly 6 primary troubleshooting categories", 6, items.size)

        val coveredCodes = items.map { it.errorCode }.toSet()
        val allExpectedCodes = ApiKeyErrorCode.values().toSet()
        assertEquals("All ApiKeyErrorCode enum values must be represented", allExpectedCodes, coveredCodes)

        items.forEach { item ->
            assertTrue("Troubleshooting item title must not be blank", item.title.isNotBlank())
            assertTrue("Troubleshooting item cause must not be blank", item.cause.isNotBlank())
            assertTrue("Troubleshooting item solution must not be blank", item.solution.isNotBlank())
            assertTrue("Troubleshooting item related step must be between 1 and 5", item.relatedStepNumber in 1..5)
            if (item.remediationUrl != null) {
                assertTrue(
                    "Remediation URL must be a valid HTTPS Google Cloud Console URL",
                    item.remediationUrl!!.startsWith("https://console.cloud.google.com")
                )
            }
        }
    }

    @Test
    fun testTroubleshootingForError_matchesAllRoutingPreferencesManagerErrorMessages() {
        // HTTP 400: Invalid key format
        val invalidKeyMsg = "Khóa API Google không hợp lệ. Vui lòng kiểm tra lại ký tự khóa."
        val itemInvalid = ApiKeyGuideProvider.getTroubleshootingForError(invalidKeyMsg)
        assertNotNull(itemInvalid)
        assertEquals(ApiKeyErrorCode.INVALID_KEY, itemInvalid!!.errorCode)
        assertEquals(ApiKeyGuideProvider.URL_CREDENTIALS, itemInvalid.remediationUrl)
        assertEquals(3, itemInvalid.relatedStepNumber)

        // HTTP 403: Billing disabled
        val billingMsg = "Dự án Google Cloud chưa kích hoạt thanh toán (Billing)."
        val itemBilling = ApiKeyGuideProvider.getTroubleshootingForError(billingMsg)
        assertNotNull(itemBilling)
        assertEquals(ApiKeyErrorCode.BILLING_DISABLED, itemBilling!!.errorCode)
        assertEquals(ApiKeyGuideProvider.URL_BILLING, itemBilling.remediationUrl)
        assertEquals(1, itemBilling.relatedStepNumber)

        // HTTP 403: Routes API not enabled
        val apiDisabledMsg = "Chưa kích hoạt 'Routes API' trên dự án Google Cloud của bạn."
        val itemApiDisabled = ApiKeyGuideProvider.getTroubleshootingForError(apiDisabledMsg)
        assertNotNull(itemApiDisabled)
        assertEquals(ApiKeyErrorCode.API_NOT_ENABLED, itemApiDisabled!!.errorCode)
        assertEquals(ApiKeyGuideProvider.URL_ROUTES_API_LIBRARY, itemApiDisabled.remediationUrl)
        assertEquals(2, itemApiDisabled.relatedStepNumber)

        // HTTP 403: Restriction error (Android SHA-1 or IP restrictions)
        val restrictionMsg = "Khóa API bị giới hạn ứng dụng hoặc IP. Vui lòng kiểm tra cài đặt hạn chế trên Google Cloud."
        val itemRestriction = ApiKeyGuideProvider.getTroubleshootingForError(restrictionMsg)
        assertNotNull(itemRestriction)
        assertEquals(ApiKeyErrorCode.RESTRICTION_ERROR, itemRestriction!!.errorCode)
        assertEquals(ApiKeyGuideProvider.URL_CREDENTIALS, itemRestriction.remediationUrl)
        assertEquals(4, itemRestriction.relatedStepNumber)

        // HTTP 429: Quota exceeded
        val quotaMsg = "Vượt quá hạn ngạch yêu cầu của Google Cloud API."
        val itemQuota = ApiKeyGuideProvider.getTroubleshootingForError(quotaMsg)
        assertNotNull(itemQuota)
        assertEquals(ApiKeyErrorCode.QUOTA_EXCEEDED, itemQuota!!.errorCode)
        assertEquals(ApiKeyGuideProvider.URL_QUOTAS, itemQuota.remediationUrl)
        assertEquals(1, itemQuota.relatedStepNumber)

        // Generic / Network error
        val networkMsg = "Lỗi kết nối Google Cloud Routes API (HTTP 500)."
        val itemNetwork = ApiKeyGuideProvider.getTroubleshootingForError(networkMsg)
        assertNotNull(itemNetwork)
        assertEquals(ApiKeyErrorCode.NETWORK_ERROR, itemNetwork!!.errorCode)
        assertNull(itemNetwork.remediationUrl)
        assertEquals(5, itemNetwork.relatedStepNumber)

        // Socket exception description
        val socketMsg = "java.net.UnknownHostException: Unable to resolve host 'routes.googleapis.com'"
        val itemSocket = ApiKeyGuideProvider.getTroubleshootingForError(socketMsg)
        assertNotNull(itemSocket)
        assertEquals(ApiKeyErrorCode.NETWORK_ERROR, itemSocket!!.errorCode)

        // Blank message returns null
        assertNull(ApiKeyGuideProvider.getTroubleshootingForError(""))
        assertNull(ApiKeyGuideProvider.getTroubleshootingForError("   "))
    }

    @Test
    fun testFreeTierInfo_specifiesAccurateThresholdsAndSafetyMeasures() {
        val freeTier = ApiKeyGuideProvider.getFreeTierInfo()

        assertEquals("Free tier threshold must be 10,000 monthly requests", 10_000, freeTier.monthlyFreeRequests)
        assertEquals("SKU name must be Routes API Essentials", "Routes API Essentials", freeTier.skuName)
        assertTrue("Quota summary must mention 10.000", freeTier.quotaSummary.contains("10.000"))
        assertTrue("Cost guarantee must explain $0 budget alert", freeTier.costGuaranteeDescription.contains("$0"))
        assertTrue("Safety recommendations must have at least 4 items", freeTier.safetyRecommendations.size >= 4)

        val allRecommendations = freeTier.safetyRecommendations.joinToString(" ")
        assertTrue("Must advise restricting key to Routes API", allRecommendations.contains("Routes API"))
        assertTrue("Must advise setting Application Restrictions to None for mobile BYOK", allRecommendations.contains("None"))
        assertTrue("Must advise setting $0 Budget Alert", allRecommendations.contains("$0"))
    }

    @Test
    fun testConstants_validPackageAndGoogleCloudUrls() {
        assertEquals("Package name must match com.evcs.favorites", "com.evcs.favorites", ApiKeyGuideProvider.PACKAGE_NAME)
        assertTrue("URL_CONSOLE_HOME must be HTTPS", ApiKeyGuideProvider.URL_CONSOLE_HOME.startsWith("https://console.cloud.google.com"))
        assertTrue("URL_BILLING must point to billing", ApiKeyGuideProvider.URL_BILLING == "https://console.cloud.google.com/billing")
        assertTrue("URL_ROUTES_API_LIBRARY must point to routes library", ApiKeyGuideProvider.URL_ROUTES_API_LIBRARY == "https://console.cloud.google.com/apis/library/routes.googleapis.com")
        assertTrue("URL_CREDENTIALS must point to credentials", ApiKeyGuideProvider.URL_CREDENTIALS == "https://console.cloud.google.com/apis/credentials")
        assertTrue("URL_QUOTAS must point to quotas", ApiKeyGuideProvider.URL_QUOTAS == "https://console.cloud.google.com/iam-admin/quotas")
    }
}
