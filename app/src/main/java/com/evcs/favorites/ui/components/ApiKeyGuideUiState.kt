package com.evcs.favorites.ui.components

import com.evcs.favorites.data.routing.ApiKeyGuideProvider
import com.evcs.favorites.data.routing.ApiKeyGuideStep
import com.evcs.favorites.data.routing.ApiKeyTroubleshootingItem
import com.evcs.favorites.data.routing.FreeTierInfo

/**
 * Immutable UI State representation for the Google Maps API Key Guide Dialog/Modal.
 *
 * @property currentStepIndex 0-based index of the currently displayed guide step (0..4).
 * @property steps Complete list of 5 guide steps provided by [ApiKeyGuideProvider].
 * @property troubleshootingItems Structured troubleshooting entries for error remediation.
 * @property freeTierInfo Information regarding free usage limits and safety tips.
 * @property expandedFaqIndexes Set of step/FAQ indexes currently expanded in the troubleshooting accordion.
 * @property copiedValueFeedback Optional feedback message when a value is copied to clipboard.
 */
data class ApiKeyGuideUiState(
    val currentStepIndex: Int = 0,
    val steps: List<ApiKeyGuideStep> = ApiKeyGuideProvider.getGuideSteps(),
    val troubleshootingItems: List<ApiKeyTroubleshootingItem> = ApiKeyGuideProvider.getTroubleshootingItems(),
    val freeTierInfo: FreeTierInfo = ApiKeyGuideProvider.getFreeTierInfo(),
    val expandedFaqIndexes: Set<Int> = emptySet(),
    val copiedValueFeedback: String? = null
) {
    val totalSteps: Int get() = steps.size
    val currentStep: ApiKeyGuideStep get() = steps.getOrElse(currentStepIndex) { steps.first() }
    val canGoBack: Boolean get() = currentStepIndex > 0
    val canGoForward: Boolean get() = currentStepIndex < totalSteps - 1
    val isLastStep: Boolean get() = currentStepIndex == totalSteps - 1
    val isFirstStep: Boolean get() = currentStepIndex == 0
}

/**
 * Presenter / state machine logic for navigating and interacting with the Google Maps API Key Guide.
 * Pure Kotlin, enabling 100% JVM unit testability.
 */
class ApiKeyGuidePresenter(
    initialState: ApiKeyGuideUiState = ApiKeyGuideUiState()
) {
    var state: ApiKeyGuideUiState = initialState
        private set

    /**
     * Advance to the next step, clamped to [totalSteps - 1].
     */
    fun nextStep() {
        if (state.canGoForward) {
            state = state.copy(
                currentStepIndex = state.currentStepIndex + 1,
                copiedValueFeedback = null
            )
        }
    }

    /**
     * Return to the previous step, clamped to 0.
     */
    fun previousStep() {
        if (state.canGoBack) {
            state = state.copy(
                currentStepIndex = state.currentStepIndex - 1,
                copiedValueFeedback = null
            )
        }
    }

    /**
     * Jump directly to a specific step index. Clamped to valid step range [0, totalSteps - 1].
     */
    fun selectStep(index: Int) {
        val clampedIndex = index.coerceIn(0, (state.totalSteps - 1).coerceAtLeast(0))
        state = state.copy(
            currentStepIndex = clampedIndex,
            copiedValueFeedback = null
        )
    }

    /**
     * Toggle the expansion state of a troubleshooting FAQ item by its index.
     */
    fun toggleFaq(index: Int) {
        val updated = state.expandedFaqIndexes.toMutableSet()
        if (updated.contains(index)) {
            updated.remove(index)
        } else {
            updated.add(index)
        }
        state = state.copy(expandedFaqIndexes = updated)
    }

    /**
     * Notify that a text value was copied to clipboard, updating visual feedback.
     */
    fun onCopiedToClipboard(label: String = "Đã sao chép vào bộ nhớ tạm") {
        state = state.copy(copiedValueFeedback = label)
    }

    /**
     * Clear copied feedback indicator.
     */
    fun clearCopyFeedback() {
        state = state.copy(copiedValueFeedback = null)
    }

    /**
     * Jump directly to the relevant guide step and expand the relevant FAQ item based on an error message.
     *
     * @param errorMessage The error message or raw code returned by validation.
     * @return The matched [ApiKeyTroubleshootingItem], or null if unhandled.
     */
    fun navigateForError(errorMessage: String): ApiKeyTroubleshootingItem? {
        val item = ApiKeyGuideProvider.getTroubleshootingForError(errorMessage) ?: return null
        
        // Find index of this item in troubleshooting list
        val itemIndex = state.troubleshootingItems.indexOfFirst { it.errorCode == item.errorCode }
        val updatedExpanded = if (itemIndex >= 0) {
            state.expandedFaqIndexes + itemIndex
        } else {
            state.expandedFaqIndexes
        }

        // Guide steps are 1-based in relatedStepNumber, so convert to 0-based
        val targetStepIndex = (item.relatedStepNumber - 1).coerceIn(0, state.totalSteps - 1)

        state = state.copy(
            currentStepIndex = targetStepIndex,
            expandedFaqIndexes = updatedExpanded,
            copiedValueFeedback = null
        )
        return item
    }

    companion object {
        /**
         * Resolves the target step index (0-based) for a given error message.
         */
        fun initialStepForError(errorMessage: String): Int {
            val item = ApiKeyGuideProvider.getTroubleshootingForError(errorMessage)
            return if (item != null) {
                (item.relatedStepNumber - 1).coerceIn(0, 4)
            } else {
                0
            }
        }
    }
}
