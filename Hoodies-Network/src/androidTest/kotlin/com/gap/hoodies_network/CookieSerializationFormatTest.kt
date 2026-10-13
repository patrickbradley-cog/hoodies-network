package com.gap.hoodies_network

import android.os.Build
import com.gap.hoodies_network.serialization.Serializers
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpCookie

/**
 * Pins the JSON that cookie persistence stores for a [HttpCookie] (encrypted `EncryptedCookie.cookie` column).
 * Goldens were captured on-device with Gson 2.8.8; the format differs per platform because Gson serializes
 * the platform's reflectively visible fields.
 */
class CookieSerializationFormatTest {

    private val fullGoldenLegacy =
        """{"comment":"session","commentURL":"http://example.com/c","domain":".example.com","httpOnly":true,"maxAge":3600,"name":"sid","path":"/app","portlist":"80,443","secure":true,"toDiscard":false,"value":"abc123","version":1,"whenCreated":1790794163483}"""
    private val minimalGoldenLegacy =
        """{"httpOnly":false,"maxAge":-1,"name":"gapCookieName","secure":false,"toDiscard":false,"value":"gapCookieValue","version":0,"whenCreated":1790794163496}"""
    private val fullGoldenApi35 = """{"httpOnly":true,"whenCreated":1790794162804}"""
    private val minimalGoldenApi35 = """{"httpOnly":false,"whenCreated":1790794162818}"""

    private val goldens: Map<Int, Pair<String, String>> = mapOf(
        28 to (fullGoldenLegacy to minimalGoldenLegacy),
        30 to (fullGoldenLegacy to minimalGoldenLegacy),
        35 to (fullGoldenApi35 to minimalGoldenApi35)
    )

    private fun golden(): Pair<String, String> {
        val golden = goldens[Build.VERSION.SDK_INT]
        assertNotNull("No Gson 2.8.8 cookie golden captured for API ${Build.VERSION.SDK_INT}", golden)
        return golden!!
    }

    private fun fullCookie() = HttpCookie("sid", "abc123").apply {
        domain = ".example.com"
        path = "/app"
        maxAge = 3600
        secure = true
        isHttpOnly = true
        version = 1
        comment = "session"
        commentURL = "http://example.com/c"
        portlist = "80,443"
        discard = false
    }

    private fun minimalCookie() = HttpCookie("gapCookieName", "gapCookieValue").apply { version = 0 }

    private fun withCreationTimeOf(golden: String, json: String): String {
        val created = Regex("\"whenCreated\":(\\d+)")
        val goldenCreated = created.find(golden)!!.groupValues[1]
        assertTrue("whenCreated missing from $json", created.containsMatchIn(json))
        return json.replace(created, "\"whenCreated\":$goldenCreated")
    }

    @Test
    fun gsonWritesCookiesInGoldenFormat() {
        val (full, minimal) = golden()
        assertEquals(full, withCreationTimeOf(full, Gson().toJson(fullCookie())))
        assertEquals(minimal, withCreationTimeOf(minimal, Gson().toJson(minimalCookie())))
    }

    @Test
    fun defaultSerializerWritesCookiesInGoldenFormat() {
        val (full, minimal) = golden()
        assertEquals(full, withCreationTimeOf(full, Serializers.default.toJson(fullCookie())))
        assertEquals(minimal, withCreationTimeOf(minimal, Serializers.default.toJson(minimalCookie())))
    }

    @Test
    fun storedGoldenCookiesReadBackUnchanged() {
        val (full, minimal) = golden()
        for (json in listOf(full, minimal)) {
            val viaGson = Gson().fromJson(json, HttpCookie::class.java)
            val viaSerializer = Serializers.default.fromJson<HttpCookie>(json, HttpCookie::class.java)!!
            assertEquals(json, Gson().toJson(viaGson))
            assertEquals(json, Serializers.default.toJson(viaSerializer))
        }
        val cookie = Gson().fromJson(full, HttpCookie::class.java)
        assertTrue(cookie.isHttpOnly)
        if (full == fullGoldenLegacy) {
            assertEquals("sid", cookie.name)
            assertEquals("abc123", cookie.value)
            assertEquals(".example.com", cookie.domain)
            assertEquals("/app", cookie.path)
            assertEquals(3600L, cookie.maxAge)
            assertTrue(cookie.secure)
            assertEquals(1, cookie.version)
            assertEquals("80,443", cookie.portlist)
        }
    }
}
