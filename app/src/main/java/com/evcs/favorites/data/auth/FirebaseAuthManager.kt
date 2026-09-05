package com.evcs.favorites.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.evcs.favorites.domain.model.AuthState
import com.evcs.favorites.domain.model.AuthUser
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Production implementation of [AuthService] backed by Firebase Authentication and
 * AndroidX Credential Manager with Google ID.
 */
class FirebaseAuthManager(
    private val authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    private val credentialManagerProvider: (Context) -> CredentialManager = { CredentialManager.create(it) },
    private val defaultServerClientId: String = AuthService.DEFAULT_WEB_CLIENT_ID,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AuthService {

    constructor(
        auth: FirebaseAuth,
        credentialManagerProvider: (Context) -> CredentialManager = { CredentialManager.create(it) },
        defaultServerClientId: String = AuthService.DEFAULT_WEB_CLIENT_ID,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        authProvider = { auth },
        credentialManagerProvider = credentialManagerProvider,
        defaultServerClientId = defaultServerClientId,
        dispatcher = dispatcher
    )

    private val auth: FirebaseAuth by lazy { authProvider() }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        runCatching {
            val initialUser = auth.currentUser
            if (initialUser != null) {
                _authState.value = AuthState.Authenticated(initialUser.toAuthUser())
            } else {
                _authState.value = AuthState.Unauthenticated
            }

            auth.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                if (user != null) {
                    _authState.value = AuthState.Authenticated(user.toAuthUser())
                } else {
                    if (_authState.value !is AuthState.Loading) {
                        _authState.value = AuthState.Unauthenticated
                    }
                }
            }
        }.onFailure {
            _authState.value = AuthState.Unauthenticated
        }
    }

    override val currentUser: AuthUser?
        get() = when (val state = _authState.value) {
            is AuthState.Authenticated -> state.user
            else -> runCatching { auth.currentUser?.toAuthUser() }.getOrNull()
        }

    override suspend fun signInAnonymously(): Result<AuthUser> = withContext(dispatcher) {
        try {
            _authState.value = AuthState.Loading
            val result = auth.signInAnonymously().await()
            val user = result.user?.toAuthUser()
                ?: throw IllegalStateException("FirebaseUser is null after anonymous sign-in")
            _authState.value = AuthState.Authenticated(user)
            Result.success(user)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Anonymous sign-in failed")
            Result.failure(e)
        }
    }

    override fun buildGoogleIdOption(serverClientId: String): GetGoogleIdOption {
        return GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
    }

    override suspend fun signInWithGoogle(activityContext: Context): Result<AuthUser> = withContext(dispatcher) {
        try {
            _authState.value = AuthState.Loading
            val googleIdOption = buildGoogleIdOption(defaultServerClientId)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = credentialManagerProvider(activityContext)
            val response = credentialManager.getCredential(activityContext, request)
            val googleIdTokenCredential = parseGoogleCredential(response.credential)
            val authCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
            signInWithGoogleCredential(authCredential)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Google Sign-In failed")
            Result.failure(e)
        }
    }

    override suspend fun signInWithGoogleCredential(authCredential: AuthCredential): Result<AuthUser> = withContext(dispatcher) {
        try {
            _authState.value = AuthState.Loading
            val current = auth.currentUser
            val user = if (current != null && current.isAnonymous) {
                try {
                    val linkResult = current.linkWithCredential(authCredential).await()
                    linkResult.user?.toAuthUser() ?: current.toAuthUser()
                } catch (collision: FirebaseAuthUserCollisionException) {
                    // Google account already exists in Firebase. Fall back gracefully to direct sign-in.
                    val signInResult = auth.signInWithCredential(authCredential).await()
                    signInResult.user?.toAuthUser()
                        ?: throw IllegalStateException("FirebaseUser is null after collision sign-in")
                }
            } else {
                val signInResult = auth.signInWithCredential(authCredential).await()
                signInResult.user?.toAuthUser()
                    ?: throw IllegalStateException("FirebaseUser is null after sign-in")
            }
            _authState.value = AuthState.Authenticated(user)
            Result.success(user)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Credential sign-in failed")
            Result.failure(e)
        }
    }

    override suspend fun signOut(activityContext: Context?): Result<Unit> = withContext(dispatcher) {
        try {
            if (activityContext != null) {
                runCatching {
                    val credentialManager = credentialManagerProvider(activityContext)
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                }
            }
            runCatching { auth.signOut() }
            _authState.value = AuthState.Unauthenticated
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Sign-out failed")
            Result.failure(e)
        }
    }

    companion object {
        /**
         * Validates and parses a Credential Manager [Credential] into a [GoogleIdTokenCredential].
         */
        fun parseGoogleCredential(credential: Credential): GoogleIdTokenCredential {
            if (credential is GoogleIdTokenCredential) {
                return credential
            }
            require(credential is CustomCredential) {
                "Expected CustomCredential, but received: ${credential::class.java.name}"
            }
            require(credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                "Unsupported credential type: ${credential.type}"
            }
            return GoogleIdTokenCredential.createFrom(credential.data)
        }

        /**
         * Extracts the Google ID token string from a Credential Manager [Credential].
         */
        fun extractGoogleIdToken(credential: Credential): String {
            return parseGoogleCredential(credential).idToken
        }
    }
}

/**
 * Extension mapping [FirebaseUser] to domain [AuthUser].
 */
fun FirebaseUser.toAuthUser(): AuthUser {
    return AuthUser(
        uid = uid,
        email = email,
        displayName = displayName,
        photoUrl = photoUrl?.toString(),
        isAnonymous = isAnonymous
    )
}
