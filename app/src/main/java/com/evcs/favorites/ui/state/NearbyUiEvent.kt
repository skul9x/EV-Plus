package com.evcs.favorites.ui.state

/**
 * One-shot UI events emitted by NearbyViewModel for ephemeral actions
 * such as authentication guards, user feedback toasts, or permission prompts.
 */
sealed interface NearbyUiEvent {
    /**
     * Emitted when an unauthenticated user attempts to tap the heart/favorite icon.
     */
    data class ShowLoginRequired(val stationName: String) : NearbyUiEvent

    /**
     * User feedback toast on favorite toggle or operational outcomes.
     */
    data class ShowToast(val message: String) : NearbyUiEvent

    /**
     * Emitted when location runtime permission has not yet been granted.
     */
    data object RequestLocationPermission : NearbyUiEvent
}
