package com.evcs.favorites.data.routing

/**
 * Categorized error types for Google Cloud Maps and Routes API validation.
 */
enum class ApiKeyErrorCode {
    INVALID_KEY,
    BILLING_DISABLED,
    API_NOT_ENABLED,
    RESTRICTION_ERROR,
    QUOTA_EXCEEDED,
    NETWORK_ERROR
}

/**
 * Data model representing a structured walkthrough step in the API Key creation guide.
 *
 * @property stepNumber 1-based sequential step index (1..5)
 * @property title Concise step title
 * @property subtitle Short category or location description (e.g., "Google Cloud Console")
 * @property instructions Detailed action instructions
 * @property tips Important tips, gotchas, or safety callouts
 * @property actionUrl Direct HTTPS link to the relevant Google Cloud Console page
 * @property actionLabel Action button text (e.g., "Mở Google Cloud Console")
 * @property copyableValue String preset for one-tap clipboard copying (e.g., package name or project name)
 */
data class ApiKeyGuideStep(
    val stepNumber: Int,
    val title: String,
    val subtitle: String,
    val instructions: String,
    val tips: String? = null,
    val actionUrl: String? = null,
    val actionLabel: String? = null,
    val copyableValue: String? = null
)

/**
 * Structured troubleshooting item mapping an error category to actionable remediation steps.
 *
 * @property errorCode Error category enum
 * @property title Human-readable summary title
 * @property cause Technical cause explanation
 * @property solution Actionable fix guidance
 * @property remediationUrl Direct console link to resolve the issue (if applicable)
 * @property relatedStepNumber 1-based guide step number most relevant to fixing this issue
 */
data class ApiKeyTroubleshootingItem(
    val errorCode: ApiKeyErrorCode,
    val title: String,
    val cause: String,
    val solution: String,
    val remediationUrl: String? = null,
    val relatedStepNumber: Int = 1
)

/**
 * Information detailing Google Maps Platform free tier usage, limits, and safety recommendations.
 *
 * @property monthlyFreeRequests Number of free requests per month (10,000 under Routes API Essentials)
 * @property skuName Google Cloud SKU name
 * @property quotaSummary Explanation of monthly quota allowance
 * @property costGuaranteeDescription Explanation of how budget alerts prevent unexpected charges ($0 alert)
 * @property safetyRecommendations List of actionable tips to avoid charges or quota issues
 */
data class FreeTierInfo(
    val monthlyFreeRequests: Int = 10_000,
    val skuName: String = "Routes API Essentials",
    val quotaSummary: String,
    val costGuaranteeDescription: String,
    val safetyRecommendations: List<String> = emptyList()
)
