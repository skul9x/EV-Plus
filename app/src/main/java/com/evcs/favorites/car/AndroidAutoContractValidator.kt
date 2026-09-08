package com.evcs.favorites.car

import android.content.Intent
import androidx.car.app.CarContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Result data class representing the outcome of an automotive contract validation check.
 */
data class ContractValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
) {
    companion object {
        fun success(): ContractValidationResult = ContractValidationResult(isValid = true)
        fun failure(vararg errors: String): ContractValidationResult =
            ContractValidationResult(isValid = false, errors = errors.toList())
        fun failure(errors: List<String>): ContractValidationResult =
            ContractValidationResult(isValid = false, errors = errors)
    }
}

/**
 * Runtime and build-time verification helper enforcing Google Automotive App Quality standards,
 * Car App Library projection contracts, and Android 15 developer sideload requirements.
 */
object AndroidAutoContractValidator {

    const val MAX_SCREEN_BACKSTACK_DEPTH = 12
    const val DHU_TCP_PORT = 5277

    const val ACTION_NAVIGATE = CarContext.ACTION_NAVIGATE
    const val ACTION_VIEW = Intent.ACTION_VIEW

    const val CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
    const val CATEGORY_POI = "androidx.car.app.category.POI"
    const val PERMISSION_MAP_TEMPLATES = "androidx.car.app.MAP_TEMPLATES"

    const val SERVICE_CLASS_SIMPLE = ".car.EvPlusCarAppService"
    const val SERVICE_CLASS_FQCN = "com.evcs.favorites.car.EvPlusCarAppService"

    const val DESCRIPTOR_ROOT_TAG = "automotiveApp"
    const val DESCRIPTOR_USES_TAG = "uses"
    const val DESCRIPTOR_TEMPLATE_NAME = "template"

    val REQUIRED_ANDROID_15_PERMISSIONS = listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    )

    val MANDATORY_VOICE_ALERTS = listOf(
        "STATION_FULL",
        "ALTERNATIVE_FOUND",
        "PROXIMITY_REMINDER"
    )

    private val GEO_URI_PATTERN = Pattern.compile("^geo:0,0\\?q=-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?(\\([^)]*\\))?$")
    private val GOOGLE_NAV_URI_PATTERN = Pattern.compile("^google\\.navigation:q=-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?(&mode=d)?$")

    /**
     * Parses an XML string into a DOM Document.
     */
    fun parseXml(xmlContent: String): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        return builder.parse(ByteArrayInputStream(xmlContent.toByteArray(StandardCharsets.UTF_8)))
    }

    /**
     * Parses an XML file into a DOM Document.
     */
    fun parseXmlFile(file: File): Document {
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        return builder.parse(file)
    }

    /**
     * Validates automotive app descriptor XML schema (`automotive_app_desc.xml`).
     * Ensures root element is `<automotiveApp>` and contains `<uses name="template" />`.
     */
    fun validateDescriptorXml(xmlContent: String): ContractValidationResult {
        return try {
            val doc = parseXml(xmlContent)
            validateDescriptorDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Malformed XML: ${e.message}")
        }
    }

    /**
     * Validates automotive app descriptor file.
     */
    fun validateDescriptorFile(file: File): ContractValidationResult {
        return try {
            if (!file.exists()) {
                return ContractValidationResult.failure("Descriptor file not found: ${file.absolutePath}")
            }
            val doc = parseXmlFile(file)
            validateDescriptorDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Failed to validate descriptor file: ${e.message}")
        }
    }

    private fun validateDescriptorDocument(doc: Document): ContractValidationResult {
        val errors = mutableListOf<String>()
        val root = doc.documentElement

        if (root.nodeName != DESCRIPTOR_ROOT_TAG) {
            errors.add("Root element must be <$DESCRIPTOR_ROOT_TAG>, but found <${root.nodeName}>")
        }

        val usesNodes: NodeList = root.getElementsByTagName(DESCRIPTOR_USES_TAG)
        var hasTemplateUse = false
        for (i in 0 until usesNodes.length) {
            val element = usesNodes.item(i) as? Element ?: continue
            if (element.getAttribute("name") == DESCRIPTOR_TEMPLATE_NAME) {
                hasTemplateUse = true
                break
            }
        }

        if (!hasTemplateUse) {
            errors.add("Descriptor missing mandatory <uses name=\"$DESCRIPTOR_TEMPLATE_NAME\" />")
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }

    /**
     * Validates `AndroidManifest.xml` for Google Automotive standards, POI category binding,
     * service export, and Android 15 permissions.
     */
    fun validateManifestXml(xmlContent: String): ContractValidationResult {
        return try {
            val doc = parseXml(xmlContent)
            validateManifestDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Malformed manifest XML: ${e.message}")
        }
    }

    /**
     * Validates manifest file from filesystem.
     */
    fun validateManifestFile(file: File): ContractValidationResult {
        return try {
            if (!file.exists()) {
                return ContractValidationResult.failure("Manifest file not found: ${file.absolutePath}")
            }
            val doc = parseXmlFile(file)
            validateManifestDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Failed to validate manifest file: ${e.message}")
        }
    }

    private fun validateManifestDocument(doc: Document): ContractValidationResult {
        val errors = mutableListOf<String>()
        val root = doc.documentElement

        // 1. Check uses-permission list
        val permissionNodes = root.getElementsByTagName("uses-permission")
        val declaredPermissions = mutableSetOf<String>()
        for (i in 0 until permissionNodes.length) {
            val el = permissionNodes.item(i) as? Element ?: continue
            val name = el.getAttribute("android:name")
            if (name.isNotEmpty()) {
                declaredPermissions.add(name)
            }
        }

        if (!declaredPermissions.contains(PERMISSION_MAP_TEMPLATES)) {
            errors.add("Missing mandatory automotive permission: $PERMISSION_MAP_TEMPLATES")
        }

        for (perm in REQUIRED_ANDROID_15_PERMISSIONS) {
            if (!declaredPermissions.contains(perm)) {
                errors.add("Missing required Android 15 permission: $perm")
            }
        }

        // 2. Check EvPlusCarAppService declaration
        val serviceNodes = root.getElementsByTagName("service")
        var carServiceElement: Element? = null

        for (i in 0 until serviceNodes.length) {
            val serviceEl = serviceNodes.item(i) as? Element ?: continue
            val name = serviceEl.getAttribute("android:name")
            if (name == SERVICE_CLASS_SIMPLE || name == SERVICE_CLASS_FQCN || name.endsWith("EvPlusCarAppService")) {
                carServiceElement = serviceEl
                break
            }
        }

        if (carServiceElement == null) {
            errors.add("Service $SERVICE_CLASS_SIMPLE is not declared in AndroidManifest.xml")
        } else {
            val exported = carServiceElement.getAttribute("android:exported")
            if (exported != "true") {
                errors.add("CarAppService must declare android:exported=\"true\" for automotive host binding")
            }

            // Check intent filters
            val intentFilters = carServiceElement.getElementsByTagName("intent-filter")
            var hasCarAppAction = false
            var hasPoiCategory = false

            for (i in 0 until intentFilters.length) {
                val filterEl = intentFilters.item(i) as? Element ?: continue
                val actionNodes = filterEl.getElementsByTagName("action")
                for (j in 0 until actionNodes.length) {
                    val actionEl = actionNodes.item(j) as? Element ?: continue
                    if (actionEl.getAttribute("android:name") == CAR_APP_SERVICE_ACTION) {
                        hasCarAppAction = true
                    }
                }

                val categoryNodes = filterEl.getElementsByTagName("category")
                for (j in 0 until categoryNodes.length) {
                    val catEl = categoryNodes.item(j) as? Element ?: continue
                    if (catEl.getAttribute("android:name") == CATEGORY_POI) {
                        hasPoiCategory = true
                    }
                }
            }

            if (!hasCarAppAction) {
                errors.add("CarAppService missing intent-filter action: $CAR_APP_SERVICE_ACTION")
            }
            if (!hasPoiCategory) {
                errors.add("CarAppService missing intent-filter category: $CATEGORY_POI")
            }
        }

        // 3. Check application meta-data
        val metaDataNodes = root.getElementsByTagName("meta-data")
        var hasAutomotiveAppDesc = false

        for (i in 0 until metaDataNodes.length) {
            val metaEl = metaDataNodes.item(i) as? Element ?: continue
            if (metaEl.getAttribute("android:name") == "com.google.android.gms.car.application") {
                val res = metaEl.getAttribute("android:resource")
                if (res == "@xml/automotive_app_desc") {
                    hasAutomotiveAppDesc = true
                }
            }
        }

        if (!hasAutomotiveAppDesc) {
            errors.add("Missing meta-data com.google.android.gms.car.application pointing to @xml/automotive_app_desc")
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }

    /**
     * Validates that screen backstack depth is compliant with Google Car App Library
     * navigation depth quota (maximum 12 screens).
     */
    fun validateScreenBackstackDepth(currentDepth: Int): ContractValidationResult {
        return when {
            currentDepth < 1 -> ContractValidationResult.failure(
                "Backstack depth must be >= 1 (current: $currentDepth)"
            )
            currentDepth > MAX_SCREEN_BACKSTACK_DEPTH -> ContractValidationResult.failure(
                "Backstack depth exceeded Google Car App Library quota of $MAX_SCREEN_BACKSTACK_DEPTH screens (current: $currentDepth)"
            )
            else -> ContractValidationResult.success()
        }
    }

    /**
     * Validates head unit turn-by-turn navigation intent.
     * Must use [CarContext.ACTION_NAVIGATE] and a valid `geo:` URI schema.
     */
    fun validateCarNavigationIntent(action: String?, uriString: String?): ContractValidationResult {
        val errors = mutableListOf<String>()

        if (action != ACTION_NAVIGATE) {
            errors.add("Car navigation action must be '$ACTION_NAVIGATE', but found: '$action'")
        }

        if (uriString.isNullOrBlank()) {
            errors.add("Navigation URI string cannot be null or empty")
        } else {
            if (!uriString.startsWith("geo:")) {
                errors.add("Car navigation URI schema must start with 'geo:', but found: '$uriString'")
            }
            if (!GEO_URI_PATTERN.matcher(uriString).matches()) {
                errors.add("Car navigation URI does not match standard 'geo:0,0?q=lat,lng(Label)' pattern: '$uriString'")
            }
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }

    /**
     * Overload for Android [Intent].
     */
    fun validateCarNavigationIntent(intent: Intent): ContractValidationResult {
        return validateCarNavigationIntent(intent.action, intent.dataString)
    }

    /**
     * Validates mobile fallback navigation intent.
     * Must use [Intent.ACTION_VIEW] and a valid `google.navigation:` URI schema.
     */
    fun validateFallbackNavigationIntent(action: String?, uriString: String?): ContractValidationResult {
        val errors = mutableListOf<String>()

        if (action != ACTION_VIEW) {
            errors.add("Fallback navigation action must be '$ACTION_VIEW', but found: '$action'")
        }

        if (uriString.isNullOrBlank()) {
            errors.add("Fallback URI string cannot be null or empty")
        } else {
            if (!uriString.startsWith("google.navigation:")) {
                errors.add("Fallback URI schema must start with 'google.navigation:', but found: '$uriString'")
            }
            if (!GOOGLE_NAV_URI_PATTERN.matcher(uriString).matches()) {
                errors.add("Fallback URI does not match 'google.navigation:q=lat,lng&mode=d' pattern: '$uriString'")
            }
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }

    /**
     * Overload for Android [Intent].
     */
    fun validateFallbackNavigationIntent(intent: Intent): ContractValidationResult {
        return validateFallbackNavigationIntent(intent.action, intent.dataString)
    }

    /**
     * Validates Desktop Head Unit (DHU) communication port protocol.
     */
    fun validateDhuPort(port: Int): ContractValidationResult {
        return if (port == DHU_TCP_PORT) {
            ContractValidationResult.success()
        } else {
            ContractValidationResult.failure(
                "Invalid DHU simulation port: $port. Standard Android Auto DHU port is $DHU_TCP_PORT"
            )
        }
    }

    /**
     * Validates ADB port forwarding command for DHU server communication.
     */
    fun validateDhuPortForwardingCommand(command: String): ContractValidationResult {
        val normalized = command.trim()
        val expected = "adb forward tcp:$DHU_TCP_PORT tcp:$DHU_TCP_PORT"
        return if (normalized == expected || normalized.contains("tcp:$DHU_TCP_PORT tcp:$DHU_TCP_PORT")) {
            ContractValidationResult.success()
        } else {
            ContractValidationResult.failure(
                "Command does not properly configure DHU port forwarding. Expected: '$expected', found: '$command'"
            )
        }
    }

    /**
     * Validates Android 15 permissions compliance.
     */
    fun validateAndroid15Permissions(permissions: Collection<String>): ContractValidationResult {
        val missing = REQUIRED_ANDROID_15_PERMISSIONS.filter { !permissions.contains(it) }
        return if (missing.isEmpty()) {
            ContractValidationResult.success()
        } else {
            ContractValidationResult.failure(
                "Missing required Android 15 permissions: ${missing.joinToString()}"
            )
        }
    }

    /**
     * Validates Voice TTS alert policies and Audio Ducking integration flags.
     */
    fun validateVoiceAndDuckingContract(
        duckingSupported: Boolean,
        supportedAlertTypes: Collection<String>
    ): ContractValidationResult {
        val errors = mutableListOf<String>()

        if (!duckingSupported) {
            errors.add("Audio Ducking (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) must be supported for automotive voice alerts")
        }

        for (alert in MANDATORY_VOICE_ALERTS) {
            if (!supportedAlertTypes.contains(alert)) {
                errors.add("Mandatory voice alert type missing: $alert")
            }
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }
}
