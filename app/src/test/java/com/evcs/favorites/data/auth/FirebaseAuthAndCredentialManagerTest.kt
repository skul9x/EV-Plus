package com.evcs.favorites.data.auth

import android.os.Bundle
import androidx.credentials.CustomCredential
import com.evcs.favorites.domain.model.AuthState
import com.evcs.favorites.domain.model.AuthUser
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Comprehensive test suite verifying Firebase Auth & AndroidX Credential Manager integration.
 * Covers anonymous guest sessions, Google ID option configuration, credential response parsing,
 * seamless anonymous account upgrading, collision fallback handling, model mapping, and sign-out lifecycle.
 */
class FirebaseAuthAndCredentialManagerTest {

    private val fakeAuthService = FakeAuthService()

    @Test
    fun testAnonymousAuthenticationInitializationAssignsUniqueGuestId() = runTest {
        // 1. Initial state is unauthenticated
        assertEquals(AuthState.Unauthenticated, fakeAuthService.authState.value)
        assertNull(fakeAuthService.currentUser)

        // 2. Sign in as anonymous guest
        val result1 = fakeAuthService.signInAnonymously()
        assertTrue("Anonymous sign-in should succeed", result1.isSuccess)
        val guest1 = result1.getOrThrow()

        assertTrue("User should be marked anonymous", guest1.isAnonymous)
        assertTrue("UID should start with guest prefix", guest1.uid.startsWith("guest_"))
        assertNull("Anonymous user should have null email", guest1.email)
        assertNull("Anonymous user should have null displayName", guest1.displayName)
        assertNull("Anonymous user should have null photoUrl", guest1.photoUrl)

        // Verify reactive state is Authenticated with guest user
        val currentState = fakeAuthService.authState.value
        assertTrue("State should be Authenticated", currentState is AuthState.Authenticated)
        assertEquals(guest1, (currentState as AuthState.Authenticated).user)
        assertEquals(guest1, fakeAuthService.currentUser)

        // 3. Second anonymous sign-in generates a distinct unique guest ID
        val fakeAuthService2 = FakeAuthService()
        val result2 = fakeAuthService2.signInAnonymously()
        val guest2 = result2.getOrThrow()
        assertFalse("Separate anonymous sessions should have unique guest UIDs", guest1.uid == guest2.uid)
    }

    @Test
    fun testBuildGoogleIdOptionCorrectlyConfiguresWebClientIdAndSettings() {
        val expectedClientId = "49442747133-giv98l3ik6b124kdr6o9t257sntgp8c1.apps.googleusercontent.com"

        // Test with default web client ID from companion object
        val defaultOption = fakeAuthService.buildGoogleIdOption()
        assertEquals(expectedClientId, defaultOption.serverClientId)
        assertFalse("Filter by authorized accounts must be false for 1-tap", defaultOption.filterByAuthorizedAccounts)
        assertFalse("Auto select must be false to prompt user chooser UI", defaultOption.autoSelectEnabled)

        // Test with explicit custom client ID
        val customClientId = "custom-server-client-id.apps.googleusercontent.com"
        val customOption = fakeAuthService.buildGoogleIdOption(customClientId)
        assertEquals(customClientId, customOption.serverClientId)
        assertFalse(customOption.filterByAuthorizedAccounts)
        assertFalse(customOption.autoSelectEnabled)
    }

    @Test
    fun testGoogleIdCredentialParsingExtractsTokenAndAccountDetails() {
        val expectedEmail = "driver@evplus.com"
        val expectedIdToken = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig"
        val expectedDisplayName = "EV Driver"

        // Build a GoogleIdTokenCredential
        val credential = GoogleIdTokenCredential.Builder()
            .setId(expectedEmail)
            .setIdToken(expectedIdToken)
            .setDisplayName(expectedDisplayName)
            .build()

        // Validate parseGoogleCredential parses GoogleIdTokenCredential
        val parsed = FirebaseAuthManager.parseGoogleCredential(credential)
        assertEquals(expectedEmail, parsed.id)
        assertEquals(expectedIdToken, parsed.idToken)
        assertEquals(expectedDisplayName, parsed.displayName)

        // Validate extractGoogleIdToken helper
        val extractedToken = FirebaseAuthManager.extractGoogleIdToken(credential)
        assertEquals(expectedIdToken, extractedToken)

        // Validate unsupported credential type throws IllegalArgumentException
        val unsupportedCredential = CustomCredential("unsupported.credential.type", Bundle())
        try {
            FirebaseAuthManager.parseGoogleCredential(unsupportedCredential)
            fail("Should throw IllegalArgumentException on unsupported credential type")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsupported credential type") == true)
        }
    }

    @Test
    fun testCredentialExchangeUpgradesAnonymousUserPreservingUid() = runTest {
        // Step 1: Establish anonymous session
        val initialGuestUid = "guest_persisted_favorites_101"
        fakeAuthService.nextGuestUid = initialGuestUid
        val anonResult = fakeAuthService.signInAnonymously()
        val anonUser = anonResult.getOrThrow()
        assertEquals(initialGuestUid, anonUser.uid)
        assertTrue(anonUser.isAnonymous)

        // Step 2: User performs Google Sign-In (no collision)
        fakeAuthService.simulateCollision = false
        fakeAuthService.simulatedGoogleUser = AuthUser(
            uid = "unused_google_uid_because_linked",
            email = "driver.smith@gmail.com",
            displayName = "Driver Smith",
            photoUrl = "https://lh3.googleusercontent.com/a/avatar123",
            isAnonymous = false
        )

        val upgradeResult = fakeAuthService.signInWithGoogle(android.content.ContextWrapper(null))
        assertTrue("Google Sign-In should succeed", upgradeResult.isSuccess)
        val upgradedUser = upgradeResult.getOrThrow()

        // Verify UID is PRESERVED (no data loss for local favorites)
        assertEquals(
            "Local guest UID must be preserved upon linking with Google account",
            initialGuestUid,
            upgradedUser.uid
        )
        assertFalse("Upgraded account must no longer be anonymous", upgradedUser.isAnonymous)
        assertEquals("driver.smith@gmail.com", upgradedUser.email)
        assertEquals("Driver Smith", upgradedUser.displayName)
        assertEquals("https://lh3.googleusercontent.com/a/avatar123", upgradedUser.photoUrl)

        // Verify state is updated
        val state = fakeAuthService.authState.value
        assertTrue(state is AuthState.Authenticated)
        assertEquals(upgradedUser, (state as AuthState.Authenticated).user)
    }

    @Test
    fun testCollisionFallbackSwitchesToExistingGoogleAccount() = runTest {
        // Step 1: User is currently an anonymous guest
        fakeAuthService.nextGuestUid = "guest_to_be_replaced_by_existing_account"
        fakeAuthService.signInAnonymously()
        assertEquals("guest_to_be_replaced_by_existing_account", fakeAuthService.currentUser?.uid)

        // Step 2: Google Sign-in collides with an existing Firebase user (account already exists)
        val existingGoogleUid = "existing_firebase_google_uid_555"
        fakeAuthService.simulateCollision = true
        fakeAuthService.simulatedGoogleUser = AuthUser(
            uid = existingGoogleUid,
            email = "veteran.driver@gmail.com",
            displayName = "Veteran Driver",
            photoUrl = "https://lh3.googleusercontent.com/a/avatar555",
            isAnonymous = false
        )

        val collisionResult = fakeAuthService.signInWithGoogle(android.content.ContextWrapper(null))
        assertTrue("Sign-in fallback should succeed", collisionResult.isSuccess)
        val finalUser = collisionResult.getOrThrow()

        // UID should switch to the existing Google account UID
        assertEquals(
            "Account collision fallback must switch to existing Firebase user UID",
            existingGoogleUid,
            finalUser.uid
        )
        assertFalse(finalUser.isAnonymous)
        assertEquals("veteran.driver@gmail.com", finalUser.email)
        assertEquals("Veteran Driver", finalUser.displayName)
        assertEquals("https://lh3.googleusercontent.com/a/avatar555", finalUser.photoUrl)
    }

    @Test
    fun testAuthUserModelAndStateHierarchyMapping() {
        val user = AuthUser(
            uid = "ev_uid_007",
            email = "james.bond@evplus.com",
            displayName = "James Bond",
            photoUrl = "https://evplus.com/agents/007.png",
            isAnonymous = false
        )

        assertEquals("ev_uid_007", user.uid)
        assertEquals("james.bond@evplus.com", user.email)
        assertEquals("James Bond", user.displayName)
        assertEquals("https://evplus.com/agents/007.png", user.photoUrl)
        assertFalse(user.isAnonymous)

        // Test AuthState transitions
        val idle: AuthState = AuthState.Idle
        val loading: AuthState = AuthState.Loading
        val authenticated: AuthState = AuthState.Authenticated(user)
        val unauthenticated: AuthState = AuthState.Unauthenticated
        val error: AuthState = AuthState.Error("Invalid credential token")

        assertTrue(idle is AuthState.Idle)
        assertTrue(loading is AuthState.Loading)
        assertTrue(authenticated is AuthState.Authenticated)
        assertEquals(user, (authenticated as AuthState.Authenticated).user)
        assertTrue(unauthenticated is AuthState.Unauthenticated)
        assertTrue(error is AuthState.Error)
        assertEquals("Invalid credential token", (error as AuthState.Error).message)
    }

    @Test
    fun testSignOutResetsStateFlowToUnauthenticatedCleanly() = runTest {
        // Authenticate user first
        val user = AuthUser(
            uid = "driver_123",
            email = "driver@evplus.com",
            displayName = "Driver 123",
            isAnonymous = false
        )
        fakeAuthService.setAuthenticatedUser(user)
        assertEquals(user, fakeAuthService.currentUser)
        assertTrue(fakeAuthService.authState.value is AuthState.Authenticated)

        // Sign out
        val signOutResult = fakeAuthService.signOut()
        assertTrue("Sign out should succeed", signOutResult.isSuccess)
        assertEquals(AuthState.Unauthenticated, fakeAuthService.authState.value)
        assertNull("currentUser should be null after sign out", fakeAuthService.currentUser)
    }
}
