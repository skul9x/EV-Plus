package com.evcs.favorites.ui.screens

import com.evcs.favorites.ui.components.AboutAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 02 Comprehensive Verification Test:
 * EV+ Commercial Branding & Author Attribution Contract Test.
 *
 * Verifies:
 * 1. `AboutAppInfo.APP_NAME` is standardized to "EV+".
 * 2. `AboutAppInfo.COPYRIGHT` equals "© 2026 Nguyễn Duy Trường".
 * 3. `AboutAppInfo.AUTHOR` equals "Nguyễn Duy Trường".
 * 4. `AboutAppInfo.APP_VERSION` is preserved as "1.0" and contact email as "skul9x@gmail.com".
 * 5. `AboutAppCard.kt` displays primary author attribution with "Tác giả" subtitle.
 * 6. Eliminates redundant duplicate author name and legacy copyright phrase in the author row.
 */
class EVPlusCommercialBrandingTest {

    @Test
    fun testEVPlusCommercialBrandingAndAuthorCard() {
        // 1. Constants verification
        assertEquals("EV+", AboutAppInfo.APP_NAME)
        assertEquals("© 2026 Nguyễn Duy Trường", AboutAppInfo.COPYRIGHT)
        assertEquals("Nguyễn Duy Trường", AboutAppInfo.AUTHOR)
        assertEquals("1.0", AboutAppInfo.APP_VERSION)
        assertEquals("skul9x@gmail.com", AboutAppInfo.CONTACT_EMAIL)

        // 2. File verification for AboutAppCard.kt
        val rootDir = File(".").canonicalFile
        val baseDir = if (File(rootDir, "app").exists()) File(rootDir, "app") else rootDir
        val aboutCardFile = File(baseDir, "src/main/java/com/evcs/favorites/ui/components/AboutAppCard.kt")
        assertTrue("AboutAppCard.kt must exist", aboutCardFile.exists())
        val cardCode = aboutCardFile.readText()

        // Verify branding and author row contents
        assertTrue("AboutAppCard must reference AboutAppInfo.APP_NAME", cardCode.contains("AboutAppInfo.APP_NAME"))
        assertTrue("AboutAppCard must reference AboutAppInfo.AUTHOR", cardCode.contains("AboutAppInfo.AUTHOR"))
        assertTrue("AboutAppCard must display 'Tác giả' subtitle", cardCode.contains("\"Tác giả\""))

        // Verify redundant repeats are eliminated from author row
        assertFalse(
            "Legacy 'Tác giả: \${AboutAppInfo.AUTHOR}' must be eliminated",
            cardCode.contains("Tác giả: \${AboutAppInfo.AUTHOR}")
        )
        assertFalse(
            "Legacy copyright phrase 'Nguyễn Duy Trường Copyright 2026' must not be present",
            cardCode.contains("Nguyễn Duy Trường Copyright 2026")
        )

        // Verify typography hierarchy in author section
        val authorSection = cardCode.substringAfter("// Author Row")
            .substringBefore("// Support Email Row")
        assertTrue("Author section must display AUTHOR", authorSection.contains("AboutAppInfo.AUTHOR"))
        assertTrue("Author section must use bodyMedium for author name", authorSection.contains("MaterialTheme.typography.bodyMedium"))
        assertTrue("Author section must display 'Tác giả' subtitle", authorSection.contains("\"Tác giả\""))
        assertTrue("Author section must use bodySmall for subtitle", authorSection.contains("MaterialTheme.typography.bodySmall"))
    }
}
