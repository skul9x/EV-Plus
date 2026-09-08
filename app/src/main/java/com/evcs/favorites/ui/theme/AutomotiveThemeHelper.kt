package com.evcs.favorites.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Helper providing WCAG 2.1 relative luminance and contrast ratio calculations.
 * Supports both Compose [Color] and raw hex/RGB components for pure JVM testability.
 */
object AutomotiveThemeHelper {

    const val WCAG_AAA_MIN_CONTRAST_RATIO: Double = 7.0
    const val WCAG_AA_MIN_CONTRAST_RATIO: Double = 4.5

    /**
     * WCAG 2.1 relative luminance calculation for a single normalized sRGB component (0.0 to 1.0).
     */
    fun calculateComponentLuminance(c: Double): Double {
        return if (c <= 0.04045) {
            c / 12.92
        } else {
            Math.pow((c + 0.055) / 1.055, 2.4)
        }
    }

    /**
     * Computes the relative luminance of an sRGB color (0.0 to 1.0) from 0-255 channels.
     */
    fun calculateRelativeLuminance(r: Int, g: Int, b: Int): Double {
        val rLum = calculateComponentLuminance(r / 255.0)
        val gLum = calculateComponentLuminance(g / 255.0)
        val bLum = calculateComponentLuminance(b / 255.0)
        return 0.2126 * rLum + 0.7152 * gLum + 0.0722 * bLum
    }

    /**
     * Computes the relative luminance of a color represented by a 0xAARRGGBB hex value.
     */
    fun calculateRelativeLuminance(hexColor: Long): Double {
        val r = ((hexColor shr 16) and 0xFF).toInt()
        val g = ((hexColor shr 8) and 0xFF).toInt()
        val b = (hexColor and 0xFF).toInt()
        return calculateRelativeLuminance(r, g, b)
    }

    /**
     * Computes the relative luminance of a Compose [Color].
     */
    fun calculateRelativeLuminance(color: Color): Double {
        val r = (color.red * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (color.green * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (color.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
        return calculateRelativeLuminance(r, g, b)
    }

    /**
     * Computes the contrast ratio between two sRGB colors according to WCAG 2.1.
     * Contrast Ratio = (L1 + 0.05) / (L2 + 0.05), where L1 >= L2.
     */
    fun calculateContrastRatio(fgHex: Long, bgHex: Long): Double {
        val lum1 = calculateRelativeLuminance(fgHex)
        val lum2 = calculateRelativeLuminance(bgHex)
        val lighter = maxOf(lum1, lum2)
        val darker = minOf(lum1, lum2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun calculateContrastRatio(foreground: Color, background: Color): Double {
        val lum1 = calculateRelativeLuminance(foreground)
        val lum2 = calculateRelativeLuminance(background)
        val lighter = maxOf(lum1, lum2)
        val darker = minOf(lum1, lum2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Verifies whether the contrast ratio meets or exceeds the WCAG AAA requirement (>= 7.0:1).
     */
    fun isWcagAaaCompliant(contrastRatio: Double): Boolean {
        return contrastRatio >= WCAG_AAA_MIN_CONTRAST_RATIO
    }

    fun isWcagAaaCompliant(foreground: Color, background: Color): Boolean {
        return isWcagAaaCompliant(calculateContrastRatio(foreground, background))
    }

    fun isWcagAaaCompliant(fgHex: Long, bgHex: Long): Boolean {
        return isWcagAaaCompliant(calculateContrastRatio(fgHex, bgHex))
    }
}
