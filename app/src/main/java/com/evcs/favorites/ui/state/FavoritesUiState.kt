package com.evcs.favorites.ui.state

import androidx.compose.runtime.Immutable
import com.evcs.favorites.data.model.Station

/**
 * Sealed interface representing all possible UI states for the EVCS Favorites screen and Login flow.
 */
sealed interface FavoritesUiState {

    /**
     * User is logged out. UI displays email input login form.
     */
    data object LoggedOut : FavoritesUiState

    /**
     * User has submitted email to request OTP and/or OTP has been sent.
     */
    data object RequestingOtp : FavoritesUiState

    /**
     * OTP verification is in progress.
     */
    data object VerifyingOtp : FavoritesUiState

    /**
     * Favorites list data is loading from the network.
     */
    data object Loading : FavoritesUiState

    /**
     * Favorites list successfully fetched and enriched with real-time plug metrics.
     *
     * @param stations List of domain [Station] models, sorted nearest-first if GPS is available.
     * @param isRefreshing True when pull-to-refresh is updating data in the background.
     */
    @Immutable
    data class Success(
        val stations: List<Station>,
        val isRefreshing: Boolean = false,
        val selectedStationForDetail: Station? = null
    ) : FavoritesUiState

    /**
     * An error occurred during auth or data fetching.
     *
     * @param message Human-readable error message.
     */
    data class Error(
        val message: String
    ) : FavoritesUiState
}
