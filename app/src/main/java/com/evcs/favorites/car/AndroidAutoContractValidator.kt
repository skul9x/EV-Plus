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

    const val NOTIFICATION_SMALL_ICON_META = "com.google.android.gms.car.notification.SmallIcon"
    const val CAR_APPLICATION_META = "com.google.android.gms.car.application"
    const val CAR_APPLICATION_DESC_RESOURCE = "@xml/automotive_app_desc"
    const val NOTIFICATION_DRAWABLE_PREFIX = "@drawable/"

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

    private val GEO_URI_PATTERN = Pattern.compile("^geo:0,0\\?q=-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?(\\([^()]*\\))?$")
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
        var hasSmallIcon = false
        var smallIconValid = false
        var smallIconValue = ""

        for (i in 0 until metaDataNodes.length) {
            val metaEl = metaDataNodes.item(i) as? Element ?: continue
            val metaName = metaEl.getAttribute("android:name")
            if (metaName == CAR_APPLICATION_META) {
                val res = metaEl.getAttribute("android:resource")
                if (res == CAR_APPLICATION_DESC_RESOURCE) {
                    hasAutomotiveAppDesc = true
                }
            } else if (metaName == NOTIFICATION_SMALL_ICON_META) {
                hasSmallIcon = true
                val res = metaEl.getAttribute("android:resource")
                smallIconValue = res
                if (res.startsWith(NOTIFICATION_DRAWABLE_PREFIX) && !res.startsWith("@mipmap/")) {
                    smallIconValid = true
                }
            }
        }

        if (!hasAutomotiveAppDesc) {
            errors.add("Missing meta-data $CAR_APPLICATION_META pointing to $CAR_APPLICATION_DESC_RESOURCE")
        }
        if (!hasSmallIcon) {
            errors.add("Missing meta-data $NOTIFICATION_SMALL_ICON_META in AndroidManifest.xml")
        } else if (!smallIconValid) {
            errors.add(
                "Notification SmallIcon must reference a monochrome @drawable/ resource, but found: '$smallIconValue'"
            )
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
            val queryCoord = uriString.substringAfter("q=").substringBefore("(")
            val coords = queryCoord.split(",")
            if (coords.size == 2) {
                val lat = coords[0].toDoubleOrNull()
                val lon = coords[1].toDoubleOrNull()
                if (lat == 0.0 && lon == 0.0) {
                    errors.add("Invalid destination coordinates: (0.0, 0.0) is not a navigable location")
                }
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

    private val SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$")
    private val PACKAGE_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /**
     * Validates a single host allowlist entry for Android Auto HostValidator.
     * Android Auto HostValidator.Builder.addAllowedHosts requires "<sha256_digest>,<package_name>".
     */
    fun validateHostAllowlistEntry(entry: String): ContractValidationResult {
        val trimmed = entry.trim()
        val parts = trimmed.split(",").map { it.trim() }
        if (parts.size != 2) {
            return ContractValidationResult.failure(
                "Invalid allowed host entry format (must be '<digest>,<package_name>'): '$entry'"
            )
        }

        val digest = parts[0]
        val packageName = parts[1]
        val errors = mutableListOf<String>()

        if (!SHA256_PATTERN.matcher(digest).matches()) {
            errors.add("Invalid SHA-256 digest in host allowlist entry: '$digest' (must be 64 hexadecimal characters)")
        }

        if (!PACKAGE_NAME_PATTERN.matcher(packageName).matches()) {
            errors.add("Invalid package name in host allowlist entry: '$packageName'")
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }

    /**
     * Validates car_hosts.xml content.
     */
    fun validateCarHostsXml(xmlContent: String): ContractValidationResult {
        return try {
            val doc = parseXml(xmlContent)
            validateCarHostsDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Malformed car hosts XML: ${e.message}")
        }
    }

    /**
     * Validates car_hosts.xml file.
     */
    fun validateCarHostsFile(file: File): ContractValidationResult {
        return try {
            if (!file.exists()) {
                return ContractValidationResult.failure("Car hosts file not found: ${file.absolutePath}")
            }
            val doc = parseXmlFile(file)
            validateCarHostsDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Failed to validate car hosts file: ${e.message}")
        }
    }

    private fun validateCarHostsDocument(doc: Document): ContractValidationResult {
        val errors = mutableListOf<String>()
        val root = doc.documentElement
        if (root.nodeName != "resources") {
            errors.add("Root element must be <resources>, but found <${root.nodeName}>")
        }

        val arrayNodes = root.getElementsByTagName("string-array")
        var foundAllowlist = false
        val items = mutableListOf<String>()

        for (i in 0 until arrayNodes.length) {
            val el = arrayNodes.item(i) as? Element ?: continue
            if (el.getAttribute("name") == "car_hosts_allowlist") {
                foundAllowlist = true
                val itemNodes = el.getElementsByTagName("item")
                for (j in 0 until itemNodes.length) {
                    val item = itemNodes.item(j) as? Element ?: continue
                    items.add(item.textContent.trim())
                }
            }
        }

        if (!foundAllowlist) {
            errors.add("Missing mandatory <string-array name=\"car_hosts_allowlist\"> in car_hosts.xml")
        } else if (items.isEmpty()) {
            errors.add("car_hosts_allowlist cannot be empty")
        } else {
            for (item in items) {
                val itemResult = validateHostAllowlistEntry(item)
                if (!itemResult.isValid) {
                    errors.addAll(itemResult.errors)
                }
            }
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }

    /**
     * Validates automotive notification vector drawable (ic_car_notification.xml).
     * Enforces monochrome requirements (white on transparent, vector drawable).
     */
    fun validateNotificationIconXml(xmlContent: String): ContractValidationResult {
        return try {
            val doc = parseXml(xmlContent)
            validateNotificationIconDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Malformed notification icon XML: ${e.message}")
        }
    }

    /**
     * Validates automotive notification vector drawable file.
     */
    fun validateNotificationIconFile(file: File): ContractValidationResult {
        return try {
            if (!file.exists()) {
                return ContractValidationResult.failure("Notification icon file not found: ${file.absolutePath}")
            }
            val doc = parseXmlFile(file)
            validateNotificationIconDocument(doc)
        } catch (e: Exception) {
            ContractValidationResult.failure("Failed to validate notification icon file: ${e.message}")
        }
    }

    private fun validateNotificationIconDocument(doc: Document): ContractValidationResult {
        val errors = mutableListOf<String>()
        val root = doc.documentElement

        if (root.nodeName != "vector") {
            errors.add("Notification icon root element must be <vector>, but found <${root.nodeName}>")
        }

        val width = root.getAttribute("android:width")
        val height = root.getAttribute("android:height")
        if (width.isEmpty() || height.isEmpty()) {
            errors.add("Notification icon vector must specify android:width and android:height")
        }

        // Validate paths are monochrome (white or transparent)
        val pathNodes = root.getElementsByTagName("path")
        if (pathNodes.length == 0) {
            errors.add("Notification icon vector must contain at least one <path> element")
        }

        val allowedMonochromeColors = setOf(
            "#ffffff",
            "#ffffffff",
            "#fff",
            "@android:color/white",
            "#00000000",
            "@android:color/transparent"
        )

        for (i in 0 until pathNodes.length) {
            val pathEl = pathNodes.item(i) as? Element ?: continue
            val fill = pathEl.getAttribute("android:fillColor").trim().lowercase()
            if (fill.isNotEmpty() && !allowedMonochromeColors.contains(fill)) {
                errors.add("Notification icon path has non-monochrome fillColor: '$fill'. Head-unit icons must be monochrome white.")
            }
            val stroke = pathEl.getAttribute("android:strokeColor").trim().lowercase()
            if (stroke.isNotEmpty() && !allowedMonochromeColors.contains(stroke)) {
                errors.add("Notification icon path has non-monochrome strokeColor: '$stroke'. Head-unit icons must be monochrome white.")
            }
        }

        return if (errors.isEmpty()) ContractValidationResult.success() else ContractValidationResult.failure(errors)
    }
}
