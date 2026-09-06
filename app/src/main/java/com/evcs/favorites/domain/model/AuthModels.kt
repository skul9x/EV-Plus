package com.evcs.favorites.domain.model

/**
 * Representation of an authenticated or guest user in EV-Plus.
 */
data class AuthUser(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val isAnonymous: Boolean = false
)

/**
 * Reactive state representing current authentication status.
 */
sealed interface AuthState {
    data object Idle : AuthState
    data object Loading : AuthState
    data class Authenticated(val user: AuthUser) : AuthState
    data object Unauthenticated : AuthState
    data class Error(val message: String) : AuthState
}
