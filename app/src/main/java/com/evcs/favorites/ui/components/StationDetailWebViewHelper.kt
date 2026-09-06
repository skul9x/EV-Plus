package com.evcs.favorites.ui.components

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Collections
import java.util.WeakHashMap

/**
 * Abstraction representing individual operations in the deterministic WebView disposal pipeline.
 * Facilitates strict unit testing on JVM test runners where Android platform View hierarchies
 * and native WebCore libraries are not present.
 */
interface WebViewLifecycleTarget {
    fun stopLoading()
    fun loadBlankUrl()
    fun clearHistory()
    fun removeAllViews()
    fun detachFromParent(): Boolean
    fun destroy()
}

/**
 * Standard implementation delegating directly to a real Android [WebView] and its parent [ViewGroup].
 */
class AndroidWebViewLifecycleTarget(
    private val webView: WebView
) : WebViewLifecycleTarget {

    override fun stopLoading() {
        webView.stopLoading()
    }

    override fun loadBlankUrl() {
        webView.loadUrl("about:blank")
    }

    override fun clearHistory() {
        webView.clearHistory()
    }

    override fun removeAllViews() {
        webView.removeAllViews()
    }

    override fun detachFromParent(): Boolean {
        val parent = webView.parent as? ViewGroup
        return if (parent != null) {
            parent.removeView(webView)
            true
        } else {
            false
        }
    }

    override fun destroy() {
        webView.destroy()
    }
}

/**
 * Centralized WebView lifecycle controller responsible for deterministic cleanup,
 * view hierarchy detachment, Chromium renderer crash interception, and native memory leak prevention.
 */
object StationDetailWebViewHelper {

    enum class CleanupStep {
        STOP_LOADING,
        LOAD_BLANK_URL,
        CLEAR_HISTORY,
        REMOVE_ALL_VIEWS,
        DETACH_FROM_PARENT,
        DESTROY
    }

    /**
     * Test hook / observer to inspect executed lifecycle cleanup steps in chronological order.
     */
    var stepObserver: ((CleanupStep) -> Unit)? = null

    /**
     * Test hook factory allowing unit tests to substitute mock or recording targets for WebViews.
     */
    var targetFactory: ((WebView) -> WebViewLifecycleTarget)? = null

    /**
     * Set of already disposed targets or WebViews to guarantee idempotency.
     */
    private val cleanedUpTargets = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Any, Boolean>()))

    /**
     * Returns true if the target instance has already undergone the cleanup pipeline.
     */
    fun isCleanedUp(target: Any?): Boolean {
        if (target == null) return false
        return cleanedUpTargets.contains(target)
    }

    /**
     * Resets internal tracking and test hooks.
     */
    fun resetForTesting() {
        cleanedUpTargets.clear()
        stepObserver = null
        targetFactory = null
    }

    /**
     * Executes deterministic WebView cleanup pipeline:
     * 1. webView.stopLoading()
     * 2. webView.loadUrl("about:blank")
     * 3. webView.clearHistory()
     * 4. webView.removeAllViews()
     * 5. Detach from parent view hierarchy: (webView.parent as? ViewGroup)?.removeView(webView)
     * 6. webView.destroy()
     *
     * Idempotent: Subsequent calls for the same instance return false and safely no-op without exceptions.
     */
    fun cleanUpWebView(webView: WebView?): Boolean {
        if (webView == null) return false
        if (cleanedUpTargets.contains(webView)) {
            return false
        }
        val target = targetFactory?.invoke(webView) ?: AndroidWebViewLifecycleTarget(webView)
        val result = executeCleanup(target)
        if (result) {
            cleanedUpTargets.add(webView)
        }
        return result
    }

    /**
     * Overload for direct execution against a [WebViewLifecycleTarget] (e.g. in JVM unit tests).
     */
    fun cleanUpWebView(target: WebViewLifecycleTarget?): Boolean {
        if (target == null) return false
        if (cleanedUpTargets.contains(target)) {
            return false
        }
        val result = executeCleanup(target)
        if (result) {
            cleanedUpTargets.add(target)
        }
        return result
    }

    private fun executeCleanup(target: WebViewLifecycleTarget): Boolean {
        return try {
            // Step 1: Halt network transfers and pending JavaScript
            target.stopLoading()
            stepObserver?.invoke(CleanupStep.STOP_LOADING)

            // Step 2: Unload current DOM by navigating to about:blank
            target.loadBlankUrl()
            stepObserver?.invoke(CleanupStep.LOAD_BLANK_URL)

            // Step 3: Clear navigation stack
            target.clearHistory()
            stepObserver?.invoke(CleanupStep.CLEAR_HISTORY)

            // Step 4: Discard child views
            target.removeAllViews()
            stepObserver?.invoke(CleanupStep.REMOVE_ALL_VIEWS)

            // Step 5: Sever references from parent view hierarchy
            target.detachFromParent()
            stepObserver?.invoke(CleanupStep.DETACH_FROM_PARENT)

            // Step 6: Release native C++ WebCore / Chromium allocations
            target.destroy()
            stepObserver?.invoke(CleanupStep.DESTROY)

            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Creates a hardened [WebViewClient] with Chromium renderer crash handling via [onRenderProcessGone].
     * Destroys the dead WebView cleanly and returns true to prevent host application termination.
     */
    fun createSafeWebViewClient(
        onPageStartedAction: ((view: WebView?, url: String?, favicon: Bitmap?) -> Unit)? = null,
        onPageFinishedAction: ((view: WebView?, url: String?) -> Unit)? = null,
        shouldOverrideUrlLoadingAction: ((view: WebView?, request: WebResourceRequest?) -> Boolean)? = null,
        onRendererCrashAction: ((view: WebView?) -> Unit)? = null
    ): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onPageStartedAction?.invoke(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onPageFinishedAction?.invoke(view, url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return shouldOverrideUrlLoadingAction?.invoke(view, request) ?: false
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                cleanUpWebView(view)
                onRendererCrashAction?.invoke(view)
                return true
            }
        }
    }
}
