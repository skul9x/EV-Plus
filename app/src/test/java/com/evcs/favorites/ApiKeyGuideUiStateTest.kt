package com.evcs.favorites

import com.evcs.favorites.data.routing.ApiKeyErrorCode
import com.evcs.favorites.data.routing.ApiKeyGuideProvider
import com.evcs.favorites.ui.components.ApiKeyGuidePresenter
import com.evcs.favorites.ui.components.ApiKeyGuideUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive single file-based test for Phase 02: Interactive Guide UI Component & Presenter.
 *
 * Verifies:
 * 1. Initial UI state starts at Step 1 (index 0) with canGoBack == false and canGoForward == true.
 * 2. Calling nextStep() increments step index up to max step (index 4) where canGoForward == false.
 * 3. Calling previousStep() decrements step index down to 0 where canGoBack == false.
 * 4. selectStep(index) clamps to valid bounds (0..4) and accurately updates current step content.
 * 5. Step 4 exposes copyable package name ('com.evcs.favorites') and explicit restrictions guidance.
 * 6. FAQ accordion expansion state toggling and item selection.
 * 7. Clipboard feedback events update and clear properly.
 * 8. navigateForError and initialStepForError map all error categories to their designated steps and FAQ items.
 */
class ApiKeyGuideUiStateTest {

    @Test
    fun testInitialState_startsAtStepOneWithCorrectNavigationFlags() {
        val presenter = ApiKeyGuidePresenter()
        val state = presenter.state

        assertEquals("Initial step index must be 0 (Step 1)", 0, state.currentStepIndex)
        assertEquals("Total steps must be 5", 5, state.totalSteps)
        assertEquals("Current step number must be 1", 1, state.currentStep.stepNumber)
        assertFalse("canGoBack must be false at initial step", state.canGoBack)
        assertTrue("canGoForward must be true at initial step", state.canGoForward)
        assertTrue("isFirstStep must be true at initial step", state.isFirstStep)
        assertFalse("isLastStep must be false at initial step", state.isLastStep)
        assertTrue("expandedFaqIndexes must initially be empty", state.expandedFaqIndexes.isEmpty())
        assertNull("copiedValueFeedback must initially be null", state.copiedValueFeedback)
        assertEquals(10_000, state.freeTierInfo.monthlyFreeRequests)
    }

    @Test
    fun testNavigation_nextAndPreviousStepTransitionsAndBoundaries() {
        val presenter = ApiKeyGuidePresenter()

        // Calling previousStep at index 0 does nothing
        presenter.previousStep()
        assertEquals(0, presenter.state.currentStepIndex)
        assertFalse(presenter.state.canGoBack)

        // Step through to step 5 (index 4)
        for (i in 1..4) {
            assertTrue("Should be able to go forward at index ${i - 1}", presenter.state.canGoForward)
            presenter.nextStep()
            assertEquals("Current index should be $i", i, presenter.state.currentStepIndex)
            assertEquals("Step number should be ${i + 1}", i + 1, presenter.state.currentStep.stepNumber)
            assertTrue("canGoBack should be true after advancing", presenter.state.canGoBack)
        }

        // Now at last step (index 4)
        assertEquals(4, presenter.state.currentStepIndex)
        assertTrue("isLastStep should be true", presenter.state.isLastStep)
        assertFalse("canGoForward should be false at last step", presenter.state.canGoForward)

        // Calling nextStep at last step does nothing
        presenter.nextStep()
        assertEquals(4, presenter.state.currentStepIndex)

        // Step back through to step 1 (index 0)
        for (i in 3 downTo 0) {
            assertTrue("Should be able to go back at index ${i + 1}", presenter.state.canGoBack)
            presenter.previousStep()
            assertEquals("Current index should be $i", i, presenter.state.currentStepIndex)
        }

        assertEquals(0, presenter.state.currentStepIndex)
        assertFalse(presenter.state.canGoBack)
        assertTrue(presenter.state.canGoForward)
    }

    @Test
    fun testSelectStep_clampsWithinBoundsAndUpdatesCurrentStepContent() {
        val presenter = ApiKeyGuidePresenter()

        // Jump to Step 3 (index 2)
        presenter.selectStep(2)
        assertEquals(2, presenter.state.currentStepIndex)
        assertEquals(3, presenter.state.currentStep.stepNumber)
        assertEquals(ApiKeyGuideProvider.URL_CREDENTIALS, presenter.state.currentStep.actionUrl)

        // Negative index clamps to 0
        presenter.selectStep(-5)
        assertEquals(0, presenter.state.currentStepIndex)
        assertEquals(1, presenter.state.currentStep.stepNumber)

        // Overflow index clamps to totalSteps - 1 (4)
        presenter.selectStep(99)
        assertEquals(4, presenter.state.currentStepIndex)
        assertEquals(5, presenter.state.currentStep.stepNumber)
    }

    @Test
    fun testStepFour_exposesPackageNameAndSecurityInstructions() {
        val presenter = ApiKeyGuidePresenter()
        presenter.selectStep(3) // Index 3 is Step 4

        val step4 = presenter.state.currentStep
        assertEquals(4, step4.stepNumber)
        assertEquals("com.evcs.favorites", step4.copyableValue)
        assertEquals(ApiKeyGuideProvider.PACKAGE_NAME, step4.copyableValue)
        assertTrue("Step 4 instructions must mention 'None'", step4.instructions.contains("None"))
        assertTrue("Step 4 instructions must mention 'Routes API'", step4.instructions.contains("Routes API"))
        assertTrue("Step 4 instructions must mention HTTP 403", step4.instructions.contains("403"))
    }

    @Test
    fun testFaqAccordion_togglesExpansionState() {
        val presenter = ApiKeyGuidePresenter()
        assertTrue(presenter.state.expandedFaqIndexes.isEmpty())

        // Expand item 0
        presenter.toggleFaq(0)
        assertTrue(presenter.state.expandedFaqIndexes.contains(0))
        assertEquals(1, presenter.state.expandedFaqIndexes.size)

        // Expand item 2
        presenter.toggleFaq(2)
        assertTrue(presenter.state.expandedFaqIndexes.contains(0))
        assertTrue(presenter.state.expandedFaqIndexes.contains(2))
        assertEquals(2, presenter.state.expandedFaqIndexes.size)

        // Collapse item 0
        presenter.toggleFaq(0)
        assertFalse(presenter.state.expandedFaqIndexes.contains(0))
        assertTrue(presenter.state.expandedFaqIndexes.contains(2))
        assertEquals(1, presenter.state.expandedFaqIndexes.size)
    }

    @Test
    fun testClipboardFeedback_handlesSetAndClear() {
        val presenter = ApiKeyGuidePresenter()
        assertNull(presenter.state.copiedValueFeedback)

        presenter.onCopiedToClipboard("Đã sao chép: com.evcs.favorites")
        assertEquals("Đã sao chép: com.evcs.favorites", presenter.state.copiedValueFeedback)

        presenter.clearCopyFeedback()
        assertNull(presenter.state.copiedValueFeedback)

        // Changing step clears feedback
        presenter.onCopiedToClipboard("Đã sao chép")
        presenter.nextStep()
        assertNull(presenter.state.copiedValueFeedback)
    }

    @Test
    fun testErrorNavigation_mapsErrorsToCorrectStepAndExpandsFaq() {
        // Billing Error -> Step 1 (Index 0)
        val p1 = ApiKeyGuidePresenter()
        val itemBilling = p1.navigateForError("Dự án Google Cloud chưa kích hoạt thanh toán (Billing)")
        assertNotNull(itemBilling)
        assertEquals(ApiKeyErrorCode.BILLING_DISABLED, itemBilling!!.errorCode)
        assertEquals(0, p1.state.currentStepIndex)
        val billingFaqIndex = p1.state.troubleshootingItems.indexOfFirst { it.errorCode == ApiKeyErrorCode.BILLING_DISABLED }
        assertTrue(p1.state.expandedFaqIndexes.contains(billingFaqIndex))

        // API Disabled Error -> Step 2 (Index 1)
        val p2 = ApiKeyGuidePresenter()
        val itemApi = p2.navigateForError("Chưa kích hoạt 'Routes API' trên dự án Google Cloud của bạn")
        assertNotNull(itemApi)
        assertEquals(ApiKeyErrorCode.API_NOT_ENABLED, itemApi!!.errorCode)
        assertEquals(1, p2.state.currentStepIndex)
        val apiFaqIndex = p2.state.troubleshootingItems.indexOfFirst { it.errorCode == ApiKeyErrorCode.API_NOT_ENABLED }
        assertTrue(p2.state.expandedFaqIndexes.contains(apiFaqIndex))

        // Invalid Key -> Step 3 (Index 2)
        val p3 = ApiKeyGuidePresenter()
        val itemKey = p3.navigateForError("Khóa API Google không hợp lệ")
        assertNotNull(itemKey)
        assertEquals(ApiKeyErrorCode.INVALID_KEY, itemKey!!.errorCode)
        assertEquals(2, p3.state.currentStepIndex)

        // Restrictions Error -> Step 4 (Index 3)
        val p4 = ApiKeyGuidePresenter()
        val itemRest = p4.navigateForError("Khóa API bị giới hạn ứng dụng hoặc IP")
        assertNotNull(itemRest)
        assertEquals(ApiKeyErrorCode.RESTRICTION_ERROR, itemRest!!.errorCode)
        assertEquals(3, p4.state.currentStepIndex)
        val restFaqIndex = p4.state.troubleshootingItems.indexOfFirst { it.errorCode == ApiKeyErrorCode.RESTRICTION_ERROR }
        assertTrue(p4.state.expandedFaqIndexes.contains(restFaqIndex))

        // Network Error -> Step 5 (Index 4)
        val p5 = ApiKeyGuidePresenter()
        val itemNet = p5.navigateForError("Lỗi kết nối mạng hoặc máy chủ")
        assertNotNull(itemNet)
        assertEquals(ApiKeyErrorCode.NETWORK_ERROR, itemNet!!.errorCode)
        assertEquals(4, p5.state.currentStepIndex)

        // Unknown / Blank error returns null without modifying state
        val p6 = ApiKeyGuidePresenter()
        val itemNull = p6.navigateForError("")
        assertNull(itemNull)
        assertEquals(0, p6.state.currentStepIndex)
    }

    @Test
    fun testInitialStepForError_staticHelperResolvesExpectedIndices() {
        assertEquals(0, ApiKeyGuidePresenter.initialStepForError("Dự án Google Cloud chưa kích hoạt thanh toán"))
        assertEquals(1, ApiKeyGuidePresenter.initialStepForError("Chưa kích hoạt 'Routes API'"))
        assertEquals(2, ApiKeyGuidePresenter.initialStepForError("Khóa API Google không hợp lệ"))
        assertEquals(3, ApiKeyGuidePresenter.initialStepForError("Khóa API bị giới hạn ứng dụng hoặc IP"))
        assertEquals(4, ApiKeyGuidePresenter.initialStepForError("Lỗi kết nối mạng timeout"))
        assertEquals(0, ApiKeyGuidePresenter.initialStepForError(""))
    }
}
