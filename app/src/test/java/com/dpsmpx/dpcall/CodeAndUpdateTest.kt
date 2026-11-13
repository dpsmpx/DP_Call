package com.dpsmpx.dpcall

import com.dpsmpx.dpcall.domain.IdentityGenerator
import com.dpsmpx.dpcall.update.UpdateChecker
import com.dpsmpx.dpcall.util.CodeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeAndUpdateTest {

    // ------------------------------------------------------- код в тексте

    @Test
    fun codeIsFoundInsideASharedMessage() {
        val code = IdentityGenerator.generate().code
        val message = "Мой код DP Call:\n$code\n\nDP Call — звонки: https://github.com/dpsmpx/DP_Call/releases/latest"
        assertEquals(code, CodeValidator.extract(message))
        assertEquals(code, CodeValidator.extract("  ${code.lowercase()}  "))
        assertEquals(code, CodeValidator.extract(code.chunked(13).joinToString(" ")))
    }

    @Test
    fun textWithoutACodeGivesNothing() {
        assertNull(CodeValidator.extract(""))
        assertNull(CodeValidator.extract("just some words"))
        assertNull(CodeValidator.extract("A".repeat(52)))
        // Код, к которому приклеены лишние символы, — не код.
        assertNull(CodeValidator.extract("x" + IdentityGenerator.generate().code))
    }

    // ---------------------------------------------------------- обновления

    @Test
    fun versionsAreComparedNumerically() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.1"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("v2.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.2", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.1.9", "1.2.0"))
        // Промежуточная сборка считается своей базовой версией.
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0-dev"))
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2.0-dev"))
        // Непонятные номера обновлением не считаются.
        assertFalse(UpdateChecker.isNewer("latest", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0-rc1", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0", ""))
    }

    @Test
    fun releaseIsParsedAndPointsAtItsApk() {
        val body = """
            {"tag_name":"v1.3.0","draft":false,"prerelease":false,"html_url":"https://github.com/dpsmpx/DP_Call/releases/tag/v1.3.0",
             "assets":[{"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/dpsmpx/DP_Call/releases/download/v1.3.0/SHA256SUMS.txt"},
                       {"name":"DP_Call-v1.3.0.apk","browser_download_url":"https://github.com/dpsmpx/DP_Call/releases/download/v1.3.0/DP_Call-v1.3.0.apk"}]}
        """.trimIndent()
        val update = UpdateChecker.parseRelease(body)!!
        assertEquals("1.3.0", update.version)
        assertEquals("https://github.com/dpsmpx/DP_Call/releases/download/v1.3.0/DP_Call-v1.3.0.apk", update.url)
    }

    @Test
    fun foreignDownloadLinksAreNotOffered() {
        val body = """{"tag_name":"v1.3.0","assets":[{"browser_download_url":"https://evil.example/DP_Call.apk"}]}"""
        assertEquals(UpdateChecker.RELEASES_PAGE, UpdateChecker.parseRelease(body)!!.url)
    }

    @Test
    fun preReleasesAndJunkAreIgnored() {
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v1.3.0","prerelease":true,"assets":[]}"""))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v1.3.0","draft":true}"""))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"nightly"}"""))
        assertNull(UpdateChecker.parseRelease("""{"message":"API rate limit exceeded"}"""))
        assertNull(UpdateChecker.parseRelease("not json"))
    }
}
