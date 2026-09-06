package com.evcs.favorites.ui.components

import com.evcs.favorites.data.routing.RoutingEngineMode
import com.evcs.favorites.data.routing.RoutingSettings

/**
 * Data class representing an actionable remediation step for Google Cloud API errors.
 *
 * @property label User-facing button label for the action.
 * @property url Direct Google Cloud Console deep link.
 * @property guideStepIndex 0-based index of corresponding guide step in [GoogleApiKeyGuideModal].
 */
data class RemediationAction(
    val label: String,
    val url: String,
    val guideStepIndex: Int = 0
)

/**
 * Pure Kotlin utility for sanitizing API keys, mapping API error messages to actionable
 * remediation links, and creating updated [RoutingSettings] objects.
 * Designed without Android framework dependencies to ensure 100% JVM unit testability.
 */
object RoutingSettingsHelper {

    const val URL_BILLING: String = "https://console.cloud.google.com/billing"
    const val URL_ROUTES_API: String = "https://console.cloud.google.com/apis/library/routes.googleapis.com"
    const val URL_CREDENTIALS: String = "https://console.cloud.google.com/apis/credentials"
    const val URL_QUOTAS: String = "https://console.cloud.google.com/apis/api/routes.googleapis.com/quotas"

    /**
     * Sanitizes raw API key input by stripping leading/trailing whitespace, newlines (\n),
     * carriage returns (\r), and tab characters (\t).
     */
    fun sanitizeApiKey(rawKey: String): String {
        return rawKey
            .replace("\r", "")
            .replace("\n", "")
            .replace("\t", "")
            .trim()
    }

    /**
     * Resolves an actionable [RemediationAction] containing a direct Google Cloud Console URL
     * based on the given error message or HTTP status pattern.
     *
     * @param errorMessage The error message or HTTP status indicator.
     * @return [RemediationAction] if an actionable remediation path is found, null otherwise.
     */
    fun resolveRemediationForError(errorMessage: String): RemediationAction? {
        if (errorMessage.isBlank()) return null
        val lower = errorMessage.lowercase()

        return when {
            lower.contains("billing") -> RemediationAction(
                label = "Kích hoạt Billing ngay",
                url = URL_BILLING,
                guideStepIndex = 0
            )

            lower.contains("routes") ||
            lower.contains("service_disabled") ||
            lower.contains("not enabled") ||
            lower.contains("has not been used") -> RemediationAction(
                label = "Bật Routes API",
                url = URL_ROUTES_API,
                guideStepIndex = 1
            )

            lower.contains("restriction") ||
            lower.contains("giới hạn") ||
            lower.contains("hạn chế") ||
            lower.contains("blocked") -> RemediationAction(
                label = "Kiểm tra giới hạn khóa",
                url = URL_CREDENTIALS,
                guideStepIndex = 3
            )

            lower.contains("quota") ||
            lower.contains("hạn ngạch") ||
            lower.contains("429") -> RemediationAction(
                label = "Kiểm tra hạn ngạch",
                url = URL_QUOTAS,
                guideStepIndex = 0
            )

            lower.contains("invalid") ||
            lower.contains("không hợp lệ") ||
            lower.contains("400") -> RemediationAction(
                label = "Kiểm tra khóa API",
                url = URL_CREDENTIALS,
                guideStepIndex = 2
            )

            else -> null
        }
    }

    /**
     * Builds an updated [RoutingSettings] instance applying [sanitizeApiKey] on the provided key,
     * updating [preferredEngine] and [autoFallbackEnabled], while preserving custom OSRM endpoints.
     */
    fun buildUpdatedSettings(
        current: RoutingSettings,
        apiKey: String,
        engine: RoutingEngineMode,
        autoFallback: Boolean
    ): RoutingSettings {
        return current.copy(
            googleApiKey = sanitizeApiKey(apiKey),
            preferredEngine = engine,
            autoFallbackEnabled = autoFallback
        )
    }
}
