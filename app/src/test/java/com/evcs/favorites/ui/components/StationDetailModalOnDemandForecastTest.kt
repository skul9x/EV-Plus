package com.evcs.favorites.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive verification test for Phase 01: Presentation & WebView On-Demand Forecast Restoration.
 *
 * Requirements verified:
 * 1. FORECAST_OVERLAP_FIX_CSS contains .amd-hasmore .amd-item and padding-right: 115px !important.
 * 2. FORECAST_OVERLAP_FIX_CSS does NOT contain display: none !important (unsuppresses forecast ticker).
 * 3. FORECAST_OVERLAP_FIX_SCRIPT contains valid JavaScript syntax that creates and appends <style> to document.head.
 * 4. isEvcsDomain strictly filters and permits only genuine EVCS domains and rejects untrusted or phishing URLs.
 */
class StationDetailModalOnDemandForecastTest {

    // =========================================================================
    // 1 & 2. Forecast CSS unsuppresses ticker while maintaining responsive padding
    // =========================================================================
    @Test
    fun forecastOverlapFixCssUnsuppressesTickerAndAppliesResponsivePadding() {
        // Must target responsive container class
        assertTrue(
            "FORECAST_OVERLAP_FIX_CSS must target .amd-hasmore .amd-item",
            FORECAST_OVERLAP_FIX_CSS.contains(".amd-hasmore .amd-item")
        )

        // Must apply 115px padding-right to avoid occlusion by [Xem thêm] button
        assertTrue(
            "FORECAST_OVERLAP_FIX_CSS must include padding-right: 115px !important",
            FORECAST_OVERLAP_FIX_CSS.contains("padding-right: 115px !important")
        )

        // Must NOT suppress forecast ticker or more button
        assertFalse(
            "FORECAST_OVERLAP_FIX_CSS must not contain display: none !important",
            FORECAST_OVERLAP_FIX_CSS.contains("display: none !important")
        )
        assertFalse(
            "FORECAST_OVERLAP_FIX_CSS must not contain display: none",
            FORECAST_OVERLAP_FIX_CSS.contains("display: none")
        )
    }

    // =========================================================================
    // 3. JavaScript injection script structure and CSS embedding
    // =========================================================================
    @Test
    fun forecastOverlapFixScriptInjectsStyleElementToHead() {
        // Must be an IIFE (Immediately Invoked Function Expression)
        assertTrue(
            "FORECAST_OVERLAP_FIX_SCRIPT must start with IIFE",
            FORECAST_OVERLAP_FIX_SCRIPT.trim().startsWith("(function()")
        )
        assertTrue(
            "FORECAST_OVERLAP_FIX_SCRIPT must end with IIFE invocation",
            FORECAST_OVERLAP_FIX_SCRIPT.trim().endsWith("})();")
        )

        // Must create style element and append to document.head
        assertTrue(
            "FORECAST_OVERLAP_FIX_SCRIPT must create style element",
            FORECAST_OVERLAP_FIX_SCRIPT.contains("document.createElement('style')")
        )
        assertTrue(
            "FORECAST_OVERLAP_FIX_SCRIPT must set type to text/css",
            FORECAST_OVERLAP_FIX_SCRIPT.contains("style.type = 'text/css'")
        )
        assertTrue(
            "FORECAST_OVERLAP_FIX_SCRIPT must append to document.head",
            FORECAST_OVERLAP_FIX_SCRIPT.contains("document.head.appendChild(style)")
        )

        // Must embed the actual CSS content
        assertTrue(
            "FORECAST_OVERLAP_FIX_SCRIPT must embed FORECAST_OVERLAP_FIX_CSS",
            FORECAST_OVERLAP_FIX_SCRIPT.contains(FORECAST_OVERLAP_FIX_CSS)
        )
    }

    // =========================================================================
    // 4. Domain boundary check: isEvcsDomain strictly validates EVCS endpoints
    // =========================================================================
    @Test
    fun isEvcsDomainStrictlyValidatesGenuineEvcsDomains() {
        // Trusted EVCS endpoints must pass
        assertTrue("Root evcs.vn must be accepted", isEvcsDomain("https://evcs.vn"))
        assertTrue("Subdomain api.evcs.vn must be accepted", isEvcsDomain("https://api.evcs.vn/v1/stations"))
        assertTrue("HTTP evcs.vn must be accepted", isEvcsDomain("http://evcs.vn/map"))
        assertTrue("Deep path on evcs.vn must be accepted", isEvcsDomain("https://evcs.vn/tram-sac/123/detail"))
        assertTrue("Uppercase domain EVCS.VN must be accepted", isEvcsDomain("https://MAP.EVCS.VN/details"))
        assertTrue("Multi-level subdomain must be accepted", isEvcsDomain("https://stage.api.evcs.vn"))

        // Untrusted, phishing, or malformed URLs must be rejected
        assertFalse("Null URL must be rejected", isEvcsDomain(null))
        assertFalse("Empty URL must be rejected", isEvcsDomain(""))
        assertFalse("Blank URL must be rejected", isEvcsDomain("   "))
        assertFalse("Phishing domain with evcs.vn in prefix must be rejected", isEvcsDomain("https://evil-evcs.vn.attacker.com"))
        assertFalse("Phishing domain with evcs.vn in path of attacker domain must be rejected", isEvcsDomain("https://attacker.com/evcs.vn"))
        assertFalse("Domain spoofing prefix must be rejected", isEvcsDomain("https://fake-evcs.vn"))
        assertFalse("External domain must be rejected", isEvcsDomain("https://google.com"))
        assertFalse("JavaScript scheme must be rejected", isEvcsDomain("javascript:alert(1)"))
        assertFalse("Malformed URL with invalid scheme must be rejected", isEvcsDomain("not a valid url evcs.vn"))
    }
}
