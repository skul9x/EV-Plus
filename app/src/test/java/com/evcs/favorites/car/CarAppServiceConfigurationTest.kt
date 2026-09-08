package com.evcs.favorites.car

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.car.app.validation.HostValidator
import com.evcs.favorites.di.DefaultAppContainer
import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Single verification test for Phase 01:
 * - Verifies automotive descriptor XML (automotive_app_desc.xml) contains <uses name="template" />.
 * - Verifies AndroidManifest.xml declarations: MAP_TEMPLATES permission, optional automotive features,
 *   metadata (minCarApiLevel=1, com.google.android.gms.car.application, SmallIcon), and EvPlusCarAppService
 *   with CarAppService action and POI category filter.
 * - Verifies CarServiceConfig host validation policy (ALLOW_ALL_HOSTS_VALIDATOR for debuggable DHU)
 *   and API level compatibility constraints.
 * - Verifies EvPlusCarAppService session instantiation and EvPlusCarSession lifecycle.
 * - Verifies AppContainer singletons (SessionManager, EvcsApiClient, EvcsRepository) for the car subsystem.
 */
class CarAppServiceConfigurationTest {

    @Before
    fun setUp() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
    }

    @After
    fun tearDown() {
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

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
    fun testAutomotiveAppDescriptor() {
        val root = findProjectRoot()
        val descFile = File(root, "app/src/main/res/xml/automotive_app_desc.xml")
        val rootElement = parseXml(descFile)

        assertEquals("automotiveApp", rootElement.nodeName)
        val usesNodes = rootElement.getElementsByTagName("uses")
        assertTrue("Expected at least one <uses> element in automotive_app_desc.xml", usesNodes.length >= 1)

        var hasTemplate = false
        for (i in 0 until usesNodes.length) {
            val element = usesNodes.item(i) as Element
            if (element.getAttribute("name") == "template") {
                hasTemplate = true
                break
            }
        }
        assertTrue("<automotiveApp> must contain <uses name=\"template\" />", hasTemplate)
    }

    @Test
    fun testAndroidManifestDeclarations() {
        val root = findProjectRoot()
        val manifestFile = File(root, "app/src/main/AndroidManifest.xml")
        val rootElement = parseXml(manifestFile)

        // 1. Check <uses-permission android:name="androidx.car.app.MAP_TEMPLATES" />
        val permissions = rootElement.getElementsByTagName("uses-permission")
        var hasMapTemplatesPermission = false
        for (i in 0 until permissions.length) {
            val el = permissions.item(i) as Element
            if (el.getAttribute("android:name") == CarServiceConfig.PERMISSION_MAP_TEMPLATES) {
                hasMapTemplatesPermission = true
                break
            }
        }
        assertTrue("Manifest must declare ${CarServiceConfig.PERMISSION_MAP_TEMPLATES}", hasMapTemplatesPermission)

        // 2. Check automotive uses-feature declarations
        val features = rootElement.getElementsByTagName("uses-feature")
        var hasAutomotiveFeature = false
        var hasTemplatesHostFeature = false
        for (i in 0 until features.length) {
            val el = features.item(i) as Element
            val name = el.getAttribute("android:name")
            val req = el.getAttribute("android:required")
            if (name == "android.hardware.type.automotive" && req == "false") {
                hasAutomotiveFeature = true
            }
            if (name == "android.software.car.templates_host" && req == "false") {
                hasTemplatesHostFeature = true
            }
        }
        assertTrue("Manifest must declare optional android.hardware.type.automotive feature", hasAutomotiveFeature)
        assertTrue("Manifest must declare optional android.software.car.templates_host feature", hasTemplatesHostFeature)

        // 3. Check <application> meta-data
        val metaDataNodes = rootElement.getElementsByTagName("meta-data")
        var hasMinCarApiLevel = false
        var hasCarAppDesc = false
        var hasSmallIcon = false

        for (i in 0 until metaDataNodes.length) {
            val el = metaDataNodes.item(i) as Element
            val name = el.getAttribute("android:name")
            when (name) {
                "androidx.car.app.minCarApiLevel" -> {
                    assertEquals("1", el.getAttribute("android:value"))
                    hasMinCarApiLevel = true
                }
                "com.google.android.gms.car.application" -> {
                    assertEquals("@xml/automotive_app_desc", el.getAttribute("android:resource"))
                    hasCarAppDesc = true
                }
                "com.google.android.gms.car.notification.SmallIcon" -> {
                    assertEquals("@mipmap/ic_launcher", el.getAttribute("android:resource"))
                    hasSmallIcon = true
                }
            }
        }
        assertTrue("Manifest must contain minCarApiLevel = 1", hasMinCarApiLevel)
        assertTrue("Manifest must contain automotive descriptor resource", hasCarAppDesc)
        assertTrue("Manifest must contain automotive SmallIcon resource", hasSmallIcon)

        // 4. Check EvPlusCarAppService declaration
        val serviceNodes = rootElement.getElementsByTagName("service")
        var foundCarService = false
        for (i in 0 until serviceNodes.length) {
            val el = serviceNodes.item(i) as Element
            if (el.getAttribute("android:name") == ".car.EvPlusCarAppService") {
                assertEquals("true", el.getAttribute("android:exported"))
                val actionNodes = el.getElementsByTagName("action")
                var hasAction = false
                for (j in 0 until actionNodes.length) {
                    val a = actionNodes.item(j) as Element
                    if (a.getAttribute("android:name") == CarServiceConfig.CAR_APP_SERVICE_ACTION) {
                        hasAction = true
                    }
                }
                assertTrue("EvPlusCarAppService must handle action ${CarServiceConfig.CAR_APP_SERVICE_ACTION}", hasAction)

                val categoryNodes = el.getElementsByTagName("category")
                var hasPoiCategory = false
                for (j in 0 until categoryNodes.length) {
                    val c = categoryNodes.item(j) as Element
                    if (c.getAttribute("android:name") == CarServiceConfig.CATEGORY_POI) {
                        hasPoiCategory = true
                    }
                }
                assertTrue("EvPlusCarAppService must specify category ${CarServiceConfig.CATEGORY_POI}", hasPoiCategory)
                foundCarService = true
                break
            }
        }
        assertTrue("Manifest must declare .car.EvPlusCarAppService", foundCarService)
    }

    @Test
    fun testCarServiceConfigAndHostValidation() {
        assertEquals(1, CarServiceConfig.MIN_CAR_API_LEVEL)
        assertEquals("androidx.car.app.CarAppService", CarServiceConfig.CAR_APP_SERVICE_ACTION)
        assertEquals("androidx.car.app.category.POI", CarServiceConfig.CATEGORY_POI)
        assertEquals("androidx.car.app.MAP_TEMPLATES", CarServiceConfig.PERMISSION_MAP_TEMPLATES)
        assertEquals("automotive_app_desc", CarServiceConfig.DESCRIPTOR_RESOURCE_NAME)
        assertEquals("com.google.android.projection.gearhead", CarServiceConfig.GEARHEAD_PACKAGE)

        assertTrue(CarServiceConfig.isCarApiLevelSupported(1))
        assertTrue(CarServiceConfig.isCarApiLevelSupported(5))
        assertFalse(CarServiceConfig.isCarApiLevelSupported(0))

        val fakeContext = object : ContextWrapper(null) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo().apply {
                    flags = ApplicationInfo.FLAG_DEBUGGABLE
                }
            }
        }

        assertTrue(CarServiceConfig.isAppDebuggable(fakeContext))
        val debugValidator = CarServiceConfig.createHostValidator(fakeContext, isDebuggable = true)
        assertSame(HostValidator.ALLOW_ALL_HOSTS_VALIDATOR, debugValidator)
    }

    @Test
    fun testEvPlusCarSessionAndServiceLifecycle() {
        val service = EvPlusCarAppService()
        val session = service.onCreateSession()
        assertNotNull(session)
        assertTrue(session is EvPlusCarSession)
    }

    @Test
    fun testAppContainerExposesCarSubsystemDependencies() {
        val container = DefaultAppContainer(null)
        assertNotNull(container.sessionManager)
        assertNotNull(container.evcsApiClient)
        assertNotNull(container.evcsRepository)
    }
}
