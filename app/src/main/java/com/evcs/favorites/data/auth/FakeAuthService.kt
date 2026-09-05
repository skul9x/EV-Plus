package com.evcs.favorites.data.auth

import android.content.Context
import com.evcs.favorites.domain.model.AuthState
import com.evcs.favorites.domain.model.AuthUser
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.firebase.auth.AuthCredential
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Deterministic in-memory implementation of [AuthService] for JVM unit testing and Compose previews.
 */
class FakeAuthService(
    initialState: AuthState = AuthState.Unauthenticated
) : AuthService {

    private val _authState = MutableStateFlow<AuthState>(initialState)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    override val currentUser: AuthUser?
        get() = (_authState.value as? AuthState.Authenticated)?.user

    var shouldFail: Boolean = false
    var failureException: Exception = RuntimeException("Simulated authentication error")
    var simulateCollision: Boolean = false
    var nextGuestUid: String? = null
    var simulatedGoogleUser: AuthUser = AuthUser(
        uid = "google_user_999",
        email = "test@example.com",
        displayName = "Test Google User",
        photoUrl = "https://example.com/photo.jpg",
        isAnonymous = false
    )

    override suspend fun signInAnonymously(): Result<AuthUser> {
        if (shouldFail) {
            val error = AuthState.Error(failureException.message ?: "Anonymous sign-in failed")
            _authState.value = error
            return Result.failure(failureException)
        }
        _authState.value = AuthState.Loading
        val uid = nextGuestUid ?: "guest_${UUID.randomUUID()}"
        val guest = AuthUser(
            uid = uid,
            email = null,
            displayName = null,
            photoUrl = null,
            isAnonymous = true
        )
        _authState.value = AuthState.Authenticated(guest)
        return Result.success(guest)
    }

    override fun buildGoogleIdOption(serverClientId: String): GetGoogleIdOption {
        return GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
    }

    override suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser> {
        return performGoogleSignIn()
    }

    override suspend fun signInWithGoogleCredential(authCredential: AuthCredential): Result<AuthUser> {
        return performGoogleSignIn()
    }

    private fun performGoogleSignIn(): Result<AuthUser> {
        if (shouldFail) {
            val error = AuthState.Error(failureException.message ?: "Google sign-in failed")
            _authState.value = error
            return Result.failure(failureException)
        }
        val current = currentUser
        _authState.value = AuthState.Loading
        val resultUser = if (current != null && current.isAnonymous) {
            if (simulateCollision) {
                // Collided with an existing Google account -> switch to the existing Google user's account ID
                simulatedGoogleUser
            } else {
                // Link with credential -> upgrade anonymous user preserving their existing local UID
                current.copy(
                    email = simulatedGoogleUser.email,
                    displayName = simulatedGoogleUser.displayName,
                    photoUrl = simulatedGoogleUser.photoUrl,
                    isAnonymous = false
                )
            }
        } else {
            simulatedGoogleUser
        }

        _authState.value = AuthState.Authenticated(resultUser)
        return Result.success(resultUser)
    }

    override suspend fun signOut(activityContext: Context?): Result<Unit> {
        if (shouldFail) {
            val error = AuthState.Error(failureException.message ?: "Sign out failed")
            _authState.value = error
            return Result.failure(failureException)
        }
        _authState.value = AuthState.Unauthenticated
        return Result.success(Unit)
    }

    fun setAuthenticatedUser(user: AuthUser) {
        _authState.value = AuthState.Authenticated(user)
    }

    fun emitState(state: AuthState) {
        _authState.value = state
    }
}
