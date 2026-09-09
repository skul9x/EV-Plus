package com.evcs.favorites.config

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.car.app.validation.HostValidator
import com.evcs.favorites.R
import com.evcs.favorites.car.CarServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Verification test for Phase 02:
 * - Verifies local car host allowlist resource array in res/values/car_hosts.xml.
 * - Verifies CarServiceConfig references local R.array.car_hosts_allowlist and eliminates
 *   reference to private androidx.car.app.R.array.hosts_allowlist_sample.
 * - Verifies res/xml/data_extraction_rules.xml structure (cloud-backup and device-transfer exclusions).
 * - Verifies AndroidManifest.xml binds android:dataExtractionRules="@xml/data_extraction_rules".
 */
class DataExtractionAndCarHostConfigTest {

    private fun findProjectRoot(): File {
        var dir: File = File(".").canonicalFile
        while (!File(dir, "app").exists() && dir.parentFile != null) {
            dir = dir.parentFile!!
        }
        return dir
    }

    private fun parseXml(file: File): Element {
        assertTrue("XML file does not exist: ${file.absolutePath}", file.exists())
        val dbf = DocumentBuilderFactory.newInstance()
        val db = dbf.newDocumentBuilder()
        val doc = db.parse(file)
        doc.documentElement.normalize()
        return doc.documentElement
    }

    @Test
    fun testCarHostsAllowlistResource() {
        val root = findProjectRoot()
        val carHostsFile = File(root, "app/src/main/res/values/car_hosts.xml")
        val rootElement = parseXml(carHostsFile)

        assertEquals("resources", rootElement.nodeName)
        val arrayNodes = rootElement.getElementsByTagName("string-array")
        assertTrue("car_hosts.xml must contain at least one string-array", arrayNodes.length >= 1)

        var foundAllowlist = false
        val items = mutableListOf<String>()
        for (i in 0 until arrayNodes.length) {
            val el = arrayNodes.item(i) as Element
            if (el.getAttribute("name") == "car_hosts_allowlist") {
                foundAllowlist = true
                val itemNodes = el.getElementsByTagName("item")
                for (j in 0 until itemNodes.length) {
                    val item = itemNodes.item(j) as Element
                    items.add(item.textContent.trim())
                }
            }
        }

        assertTrue("string-array 'car_hosts_allowlist' must be defined in car_hosts.xml", foundAllowlist)
        assertTrue(
            "car_hosts_allowlist must contain com.google.android.projection.gearhead",
            items.contains("com.google.android.projection.gearhead")
        )

        // Verify compiled R identifier exists
        assertTrue("R.array.car_hosts_allowlist must be a valid resource ID", R.array.car_hosts_allowlist > 0)
    }

    @Test
    fun testCarServiceConfigHostValidatorHardening() {
        val root = findProjectRoot()
        val carConfigFile = File(root, "app/src/main/java/com/evcs/favorites/car/CarServiceConfig.kt")
        assertTrue("CarServiceConfig.kt must exist", carConfigFile.exists())
        val configSource = carConfigFile.readText()

        // Verify private resource reference is completely eradicated
        assertFalse(
            "CarServiceConfig.kt must NOT reference private androidx.car.app.R.array.hosts_allowlist_sample",
            configSource.contains("androidx.car.app.R.array.hosts_allowlist_sample")
        )
        assertTrue(
            "CarServiceConfig.kt must reference local R.array.car_hosts_allowlist",
            configSource.contains("car_hosts_allowlist")
        )

        // Test debuggable host validation behavior
        val fakeDebugContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = ApplicationInfo.FLAG_DEBUGGABLE
                }
            }
        }
        assertTrue(CarServiceConfig.isAppDebuggable(fakeDebugContext))
        val debugValidator = CarServiceConfig.createHostValidator(fakeDebugContext, isDebuggable = true)
        assertSame(HostValidator.ALLOW_ALL_HOSTS_VALIDATOR, debugValidator)
    }

    @Test
    fun testDataExtractionRulesXml() {
        val root = findProjectRoot()
        val dataExtractionFile = File(root, "app/src/main/res/xml/data_extraction_rules.xml")
        val rootElement = parseXml(dataExtractionFile)

        assertEquals("data-extraction-rules", rootElement.nodeName)

        // Verify cloud-backup configuration
        val cloudBackupNodes = rootElement.getElementsByTagName("cloud-backup")
        assertEquals("Must contain exactly one <cloud-backup> element", 1, cloudBackupNodes.length)
        val cloudBackup = cloudBackupNodes.item(0) as Element
        assertEquals("true", cloudBackup.getAttribute("disableIfNoEncryptionCapabilities"))
        val cloudExcludeNodes = cloudBackup.getElementsByTagName("exclude")
        assertTrue("cloud-backup must specify exclude rule", cloudExcludeNodes.length >= 1)
        val cloudExclude = cloudExcludeNodes.item(0) as Element
        assertEquals(".", cloudExclude.getAttribute("path"))

        // Verify device-transfer configuration
        val deviceTransferNodes = rootElement.getElementsByTagName("device-transfer")
        assertEquals("Must contain exactly one <device-transfer> element", 1, deviceTransferNodes.length)
        val deviceTransfer = deviceTransferNodes.item(0) as Element
        val transferExcludeNodes = deviceTransfer.getElementsByTagName("exclude")
        assertTrue("device-transfer must specify exclude rule", transferExcludeNodes.length >= 1)
        val transferExclude = transferExcludeNodes.item(0) as Element
        assertEquals(".", transferExclude.getAttribute("path"))
    }

    @Test
    fun testAndroidManifestDataExtractionBinding() {
        val root = findProjectRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        val rootElement = parseXml(manifestFile)

        val appNodes = rootElement.getElementsByTagName("application")
        assertEquals("Must contain exactly one <application> element", 1, appNodes.length)
        val appElement = appNodes.item(0) as Element

        val dataExtractionRules = appElement.getAttribute("android:dataExtractionRules")
        assertEquals(
            "Application must declare android:dataExtractionRules=\"@xml/data_extraction_rules\"",
            "@xml/data_extraction_rules",
            dataExtractionRules
        )
    }
}
