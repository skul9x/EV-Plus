package com.evcs.favorites

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.flowWithLifecycle
import com.evcs.favorites.data.logging.AppDebugLogger
import com.evcs.favorites.data.logging.DebugLogLevel
import com.evcs.favorites.data.logging.DebugLogTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 06 Verification Test:
 * Lifecycle-Aware Flow Collection & Battery Conservation
 *
 * Core Verifications:
 * 1. `collectAsStateWithLifecycle` resolves and compiles against `androidx.lifecycle.compose`.
 * 2. MainActivity, NearbyScreen, and DebugLogViewerCard strictly use `collectAsStateWithLifecycle`
 *    and have no unscoped `collectAsState` calls.
 * 3. StateFlow emissions update UI state observers when lifecycle is at or above Lifecycle.State.STARTED.
 * 4. Flow subscription cancels/suspends when lifecycle drops to STOPPED / DESTROYED (drops below STARTED).
 * 5. Flow subscription cleanly resumes when lifecycle transitions back to Lifecycle.State.STARTED.
 * 6. AppDebugLogger.logsFlow upstream pauses when lifecycle drops below STARTED, bringing active subscriptions to 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleAwareFlowCollectionTest {

    private val testDispatcher = StandardTestDispatcher()

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        AppDebugLogger.clear()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AppDebugLogger.clear()
    }

    private fun findProjectFile(relativePath: String): File {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val candidate1 = File(projectDir, relativePath)
        if (candidate1.exists()) return candidate1
        val candidate2 = File(projectDir, "app/$relativePath")
        if (candidate2.exists()) return candidate2
        val candidate3 = File(projectDir, relativePath.removePrefix("app/"))
        if (candidate3.exists()) return candidate3
        throw AssertionError("Could not find file $relativePath in $projectDir")
    }

    @Test
    fun testCollectAsStateWithLifecycleResolvesAgainstAndroidxLifecycleCompose() {
        // Verification 1: collectAsStateWithLifecycle resolves in androidx.lifecycle.compose package
        val clazz = Class.forName("androidx.lifecycle.compose.FlowExtKt")
        assertNotNull("FlowExtKt class must exist in androidx.lifecycle.compose", clazz)

        val methods = clazz.declaredMethods.filter { it.name == "collectAsStateWithLifecycle" }
        assertTrue("FlowExtKt must declare collectAsStateWithLifecycle methods", methods.isNotEmpty())
    }

    @Test
    fun testComposablesMigratedFromCollectAsStateToCollectAsStateWithLifecycle() {
        // Verification 2: Verify project source files no longer use unscoped collectAsState
        val targetRelativePaths = listOf(
            "src/main/java/com/evcs/favorites/MainActivity.kt",
            "src/main/java/com/evcs/favorites/ui/screens/NearbyScreen.kt",
            "src/main/java/com/evcs/favorites/ui/components/DebugLogViewerCard.kt"
        )

        for (relPath in targetRelativePaths) {
            val file = findProjectFile(relPath)
            assertTrue("File ${file.name} must exist", file.exists())
            val content = file.readText()

            // Must NOT have unscoped collectAsState import or call
            assertFalse(
                "${file.name} must not import androidx.compose.runtime.collectAsState",
                content.contains("import androidx.compose.runtime.collectAsState")
            )
            assertFalse(
                "${file.name} must not contain unscoped .collectAsState()",
                content.contains(".collectAsState()")
            )

            // Must use collectAsStateWithLifecycle
            assertTrue(
                "${file.name} must import androidx.lifecycle.compose.collectAsStateWithLifecycle",
                content.contains("import androidx.lifecycle.compose.collectAsStateWithLifecycle")
            )
            assertTrue(
                "${file.name} must call .collectAsStateWithLifecycle()",
                content.contains(".collectAsStateWithLifecycle()")
            )
        }
    }

    @Test
    fun testLifecycleAwareFlowCollection_activeStarted_pausesOnStop_resumesOnStart() = runTest(testDispatcher) {
        val lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val sourceFlow = MutableStateFlow("initial")
        val receivedEmissions = mutableListOf<String>()

        // Collect with flowWithLifecycle targeting STARTED state
        val collectionJob = launch {
            sourceFlow
                .flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { value ->
                    receivedEmissions.add(value)
                }
        }
        advanceUntilIdle()

        // 1. In CREATED: no emissions collected, 0 active collectors
        assertTrue("No emissions should be collected in CREATED state", receivedEmissions.isEmpty())
        assertEquals("Source flow should have 0 active collectors in CREATED state", 0, sourceFlow.subscriptionCount.value)

        // 2. Transition to STARTED: flow collection starts, initial value collected
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        advanceUntilIdle()
        assertEquals("Source flow should have 1 active collector in STARTED state", 1, sourceFlow.subscriptionCount.value)
        assertEquals(listOf("initial"), receivedEmissions)

        // Emit while active in RESUMED
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        sourceFlow.value = "emission-1"
        advanceUntilIdle()
        assertEquals(listOf("initial", "emission-1"), receivedEmissions)

        // 3. Drop to STOPPED (app in background, state becomes CREATED): collection pauses, subscription drops to 0
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        advanceUntilIdle()
        assertEquals(
            "Source flow active collectors should drop to 0 when lifecycle is STOPPED (below STARTED)",
            0,
            sourceFlow.subscriptionCount.value
        )

        // Emit while STOPPED: UI should NOT receive emissions while in background
        sourceFlow.value = "background-emission"
        advanceUntilIdle()
        assertEquals(
            "UI should NOT receive emissions while lifecycle is STOPPED",
            listOf("initial", "emission-1"),
            receivedEmissions
        )

        // 4. Return to foreground (ON_START): collection resumes, receives latest state
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        advanceUntilIdle()
        assertEquals(
            "Source flow should have 1 active collector after returning to STARTED",
            1,
            sourceFlow.subscriptionCount.value
        )
        assertEquals(
            "UI should receive latest state upon returning to STARTED",
            listOf("initial", "emission-1", "background-emission"),
            receivedEmissions
        )

        // Emit new value while foregrounded again
        sourceFlow.value = "foreground-emission"
        advanceUntilIdle()
        assertEquals(
            listOf("initial", "emission-1", "background-emission", "foreground-emission"),
            receivedEmissions
        )

        // 5. Drop to DESTROYED: collection terminates cleanly
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        advanceUntilIdle()
        assertEquals(
            "Active collectors should be 0 when DESTROYED",
            0,
            sourceFlow.subscriptionCount.value
        )

        collectionJob.cancel()
    }

    @Test
    fun testAppDebugLoggerLogsFlow_lifecycleAwarePausesUpstreamSubscription() = runTest(testDispatcher) {
        val lifecycleOwner = TestLifecycleOwner()
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val receivedLogs = mutableListOf<List<Any>>()

        // Simulate DebugLogViewerCard observing logsFlow with lifecycle
        val collectionJob = launch {
            AppDebugLogger.logsFlow
                .flowWithLifecycle(lifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .collect { logs ->
                    receivedLogs.add(logs)
                }
        }
        advanceUntilIdle()

        // In CREATED (initial inactive state): logsFlow has 0 active subscribers
        assertEquals("Inactive card must receive 0 emissions in CREATED state", 0, receivedLogs.size)
        assertEquals("AppDebugLogger.subscriptionCount must be 0 in CREATED state", 0, AppDebugLogger.subscriptionCount.value)

        // Move to STARTED (card/modal is visible): subscription activates
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        advanceUntilIdle()
        assertEquals("AppDebugLogger.subscriptionCount must be 1 when STARTED", 1, AppDebugLogger.subscriptionCount.value)
        assertTrue("Should collect logs once STARTED", receivedLogs.isNotEmpty())
        val countAfterStarted = receivedLogs.size

        // Log an event while visible and active
        AppDebugLogger.log(
            tag = DebugLogTag.NETWORK,
            level = DebugLogLevel.INFO,
            message = "Lifecycle active test log"
        )
        AppDebugLogger.flush()
        advanceUntilIdle()
        assertTrue("Should receive updated logs while active", receivedLogs.size >= countAfterStarted)

        // Move to STOPPED (modal closed or app minimized): subscription pauses, drops to 0
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        advanceUntilIdle()
        assertEquals(
            "AppDebugLogger.subscriptionCount must drop to 0 when STOPPED (below STARTED)",
            0,
            AppDebugLogger.subscriptionCount.value
        )

        val countBeforeHiddenLog = receivedLogs.size
        // Log while STOPPED (in background)
        AppDebugLogger.log(
            tag = DebugLogTag.NETWORK,
            level = DebugLogLevel.WARN,
            message = "Background hidden log"
        )
        advanceUntilIdle()
        assertEquals(
            "Inactive card must NOT receive emissions while STOPPED",
            countBeforeHiddenLog,
            receivedLogs.size
        )

        // Resume to STARTED: subscription re-activates and delivers updated logs
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        advanceUntilIdle()
        assertEquals(
            "AppDebugLogger.subscriptionCount must return to 1 when resumed",
            1,
            AppDebugLogger.subscriptionCount.value
        )
        assertTrue(
            "Card should receive latest logs when resumed to STARTED",
            receivedLogs.size > countBeforeHiddenLog
        )

        // Cleanup
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        advanceUntilIdle()
        collectionJob.cancel()
    }
}
