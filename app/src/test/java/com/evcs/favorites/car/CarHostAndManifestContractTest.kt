package com.evcs.favorites.car

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.car.app.validation.HostValidator
import com.evcs.favorites.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 01 Single Verification Test:
 * Verifies HostValidator release instantiation, safe developer sideloading fallback,
 * AndroidManifest automotive metadata compliance, and monochrome head-unit notification icon.
 */
class CarHostAndManifestContractTest {

    private fun findProjectRoot(): File {
        var current: File? = File(System.getProperty("user.dir") ?: ".")
        while (current != null) {
            if (File(current, "app/src/main/AndroidManifest.xml").exists()) {
                return current
            }
            current = current.parentFile
        }
        return File(".")
    }

    @Test
    fun testHostValidatorDebugMode() {
        val fakeDebugContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = ApplicationInfo.FLAG_DEBUGGABLE
                }
            }
        }

        assertTrue(CarServiceConfig.isAppDebuggable(fakeDebugContext))
        val debugValidator = CarServiceConfig.createHostValidator(fakeDebugContext, isDebuggable = true)
        assertSame(
            "Debuggable builds must use ALLOW_ALL_HOSTS_VALIDATOR for DHU compatibility",
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR,
            debugValidator
        )
    }

    @Test
    fun testHostValidatorReleaseModeWithoutCrash() {
        val sampleEntries = arrayOf(
            "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf,com.google.android.projection.gearhead",
            "70811a3eacfd2e83e18da9bfede52df16ce91f2e69a44d21f18ab66991130771,com.google.android.projection.gearhead",
            "c241ffbc8e287c4e9a4ad19632ba1b1351ad361d5177b7d7b29859bd2b7fc631,com.google.android.apps.automotive.templates.host"
        )

        val mockResources = object : Resources(null, null, null) {
            override fun getStringArray(id: Int): Array<String> {
                return sampleEntries
            }
        }

        val releaseContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = 0 // Non-debuggable
                }
            }

            override fun getResources(): Resources = mockResources
            override fun getPackageManager(): PackageManager? = null
        }

        assertFalse(CarServiceConfig.isAppDebuggable(releaseContext))

        // Direct builder validation with properly formatted entries
        val directValidator = HostValidator.Builder(releaseContext)
            .addAllowedHosts(R.array.car_hosts_allowlist)
            .build()
        assertNotNull("Direct HostValidator instantiation must succeed", directValidator)

        // Configuration helper validation
        val createdValidator = CarServiceConfig.createHostValidator(releaseContext, isDebuggable = false)
        assertNotNull("createHostValidator in release mode must return a valid validator", createdValidator)
    }

    @Test
    fun testHostValidatorSafeFallbackOnResourceFailure() {
        val failingContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = 0
                }
            }

            override fun getResources(): Resources {
                throw IllegalStateException("Simulated corrupt resource table during sideload")
            }
        }

        // Must NOT throw; must safely fall back per zero-crash policy
        val fallbackValidator = CarServiceConfig.createHostValidator(failingContext, isDebuggable = false)
        assertSame(
            "Host validator must safely fall back to ALLOW_ALL_HOSTS_VALIDATOR on resource failure",
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR,
            fallbackValidator
        )
    }

    @Test
    fun testManifestAutomotiveContractAndMonochromeSmallIcon() {
        val root = findProjectRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())

        val result = AndroidAutoContractValidator.validateManifestFile(manifestFile)
        assertTrue(
            "AndroidManifest.xml must satisfy all automotive contracts: ${result.errors.joinToString()}",
            result.isValid
        )

        // Verify SmallIcon specifically references drawable and not mipmap
        val manifestXml = manifestFile.readText()
        assertTrue(
            "Manifest must declare SmallIcon pointing to @drawable/ic_car_notification",
            manifestXml.contains("android:resource=\"@drawable/ic_car_notification\"")
        )
        assertFalse(
            "Manifest SmallIcon must NOT point to adaptive @mipmap/",
            manifestXml.contains("name=\"com.google.android.gms.car.notification.SmallIcon\"\n            android:resource=\"@mipmap/")
        )

        // Verify automotive descriptor metadata
        assertTrue(
            "Manifest must reference automotive_app_desc",
            manifestXml.contains("android:resource=\"@xml/automotive_app_desc\"")
        )
    }

    @Test
    fun testMonochromeNotificationIconDrawable() {
        val root = findProjectRoot()
        val iconFile = File(root, "app/src/main/res/drawable/ic_car_notification.xml")
        assertTrue("ic_car_notification.xml must exist in res/drawable", iconFile.exists())

        val result = AndroidAutoContractValidator.validateNotificationIconFile(iconFile)
        assertTrue(
            "ic_car_notification.xml must be an automotive-compliant monochrome vector: ${result.errors.joinToString()}",
            result.isValid
        )

        val xmlContent = iconFile.readText()
        assertTrue("Icon must define 24dp width", xmlContent.contains("android:width=\"24dp\""))
        assertTrue("Icon must define 24dp height", xmlContent.contains("android:height=\"24dp\""))
        assertTrue("Icon must use white fill for monochrome compliance", xmlContent.contains("#FFFFFFFF"))
    }

    @Test
    fun testCarHostsAllowlistEntriesValidity() {
        val root = findProjectRoot()
        val hostsFile = File(root, "app/src/main/res/values/car_hosts.xml")
        assertTrue("car_hosts.xml must exist", hostsFile.exists())

        val result = AndroidAutoContractValidator.validateCarHostsFile(hostsFile)
        assertTrue(
            "car_hosts.xml must contain valid <digest>,<package_name> entries: ${result.errors.joinToString()}",
            result.isValid
        )

        val content = hostsFile.readText()
        assertTrue("Allowlist must include Gearhead package", content.contains("com.google.android.projection.gearhead"))
        assertTrue("Allowlist must include Automotive template host", content.contains("com.google.android.apps.automotive.templates.host"))
    }

    @Test
    fun testContractValidatorRejections() {
        // 1. Invalid host entries
        val missingDigest = AndroidAutoContractValidator.validateHostAllowlistEntry("com.google.android.projection.gearhead")
        assertFalse("Entry missing comma and digest must be rejected", missingDigest.isValid)

        val badDigest = AndroidAutoContractValidator.validateHostAllowlistEntry("not_a_digest,com.google.android.projection.gearhead")
        assertFalse("Entry with invalid SHA-256 hex length must be rejected", badDigest.isValid)

        val badPackage = AndroidAutoContractValidator.validateHostAllowlistEntry("fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf,123bad_package")
        assertFalse("Entry with invalid package name must be rejected", badPackage.isValid)

        val validEntry = AndroidAutoContractValidator.validateHostAllowlistEntry(
            "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf,com.google.android.projection.gearhead"
        )
        assertTrue("Valid digest,package entry must be accepted", validEntry.isValid)

        // 2. Invalid manifest smallIcon (mipmap)
        val manifestWithMipmap = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />
                <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
                <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
                <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
                <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
                <application>
                    <meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc" />
                    <meta-data android:name="com.google.android.gms.car.notification.SmallIcon" android:resource="@mipmap/ic_launcher" />
                    <service android:name=".car.EvPlusCarAppService" android:exported="true">
                        <intent-filter>
                            <action android:name="androidx.car.app.CarAppService" />
                            <category android:name="androidx.car.app.category.POI" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
        """.trimIndent()
        val mipmapResult = AndroidAutoContractValidator.validateManifestXml(manifestWithMipmap)
        assertFalse("Manifest with @mipmap SmallIcon must be rejected", mipmapResult.isValid)
        assertTrue(mipmapResult.errors.any { it.contains("monochrome @drawable/") })

        // 3. Non-monochrome vector notification icon
        val multiColorVector = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp"
                android:height="24dp"
                android:viewportWidth="24"
                android:viewportHeight="24">
                <path android:fillColor="#FF0000" android:pathData="M0,0h24v24z" />
            </vector>
        """.trimIndent()
        val colorResult = AndroidAutoContractValidator.validateNotificationIconXml(multiColorVector)
        assertFalse("Non-monochrome notification vector must be rejected", colorResult.isValid)
    }
}
