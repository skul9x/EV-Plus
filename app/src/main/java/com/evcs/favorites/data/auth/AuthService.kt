package com.evcs.favorites.data.auth

import android.content.Context
import com.evcs.favorites.domain.model.AuthState
import com.evcs.favorites.domain.model.AuthUser
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.firebase.auth.AuthCredential
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface contract for authentication operations in EV-Plus.
 * Supports anonymous guest sign-in, Google 1-tap sign-in via AndroidX Credential Manager,
 * credential linking, and lifecycle management.
 */
interface AuthService {
    /**
     * Reactive stream of current authentication state.
     */
    val authState: StateFlow<AuthState>

    /**
     * Currently authenticated user, or null if unauthenticated.
     */
    val currentUser: AuthUser?

    /**
     * Signs in anonymously to establish a frictionless local guest session.
     */
    suspend fun signInAnonymously(): Result<AuthUser>

    /**
     * Constructs a [GetGoogleIdOption] for Credential Manager with default or specified server client ID.
     */
    fun buildGoogleIdOption(serverClientId: String = DEFAULT_WEB_CLIENT_ID): GetGoogleIdOption

    /**
     * Initiates Google Sign-In using AndroidX Credential Manager.
     * Must be called with an Activity [Context].
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser>

    /**
     * Completes authentication using a Firebase [AuthCredential].
     * If the current user is anonymous, attempts linking first to preserve local favorites.
     * Falls back gracefully to sign-in on account collision.
     */
    suspend fun signInWithGoogleCredential(authCredential: AuthCredential): Result<AuthUser>

    /**
     * Signs out the user, clears Credential Manager state, and resets to unauthenticated.
     */
    suspend fun signOut(activityContext: Context? = null): Result<Unit>

    companion object {
        const val DEFAULT_WEB_CLIENT_ID = "49442747133-giv98l3ik6b124kdr6o9t257sntgp8c1.apps.googleusercontent.com"
    }
}
