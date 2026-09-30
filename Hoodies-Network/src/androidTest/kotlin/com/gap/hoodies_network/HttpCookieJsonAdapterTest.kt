package com.gap.hoodies_network

import com.gap.hoodies_network.cookies.persistentstorage.HttpCookieJsonAdapter
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpCookie

/**
 * Regression test for the API 35 hidden-API failure: reflective field dumps of
 * java.net.HttpCookie silently lose name/value, so EncryptedDaoWrapperForCookies
 * serializes cookies explicitly via HttpCookieJsonAdapter.
 */
class HttpCookieJsonAdapterTest {

    private val gson = GsonBuilder()
        .registerTypeAdapter(HttpCookie::class.java, HttpCookieJsonAdapter)
        .create()

    @Test
    fun cookieRoundTripPreservesFields() {
        val cookie = HttpCookie("sessionId", "abc123").apply {
            version = 0
            domain = "localhost"
            path = "/api"
            portlist = "6969"
            maxAge = 3600
            secure = true
            isHttpOnly = true
            comment = "a comment"
            commentURL = "http://example.com/about"
            discard = true
        }

        val restored = gson.fromJson(gson.toJson(cookie), HttpCookie::class.java)

        assertEquals("sessionId", restored.name)
        assertEquals("abc123", restored.value)
        assertEquals(0, restored.version)
        assertEquals("localhost", restored.domain)
        assertEquals("/api", restored.path)
        assertEquals("6969", restored.portlist)
        assertEquals(3600, restored.maxAge)
        assertTrue(restored.secure)
        assertTrue(restored.isHttpOnly)
        assertEquals("a comment", restored.comment)
        assertEquals("http://example.com/about", restored.commentURL)
        assertTrue(restored.discard)
    }

    @Test
    fun decodesLegacyReflectiveDumpFormat() {
        // Rows written by the pre-fix code are a raw reflective field dump; the
        // adapter must still decode them (notably the "toDiscard" key).
        val legacyJson = """
            {"commentURL":"http://example.com","domain":"localhost","header":"k=v; Path=/",
             "httpOnly":true,"maxAge":-1,"name":"legacy","path":"/","portlist":null,
             "secure":false,"toDiscard":true,"value":"lv","version":0,"whenCreated":1700000000000}
        """.trimIndent()

        val restored = gson.fromJson(legacyJson, HttpCookie::class.java)

        assertEquals("legacy", restored.name)
        assertEquals("lv", restored.value)
        assertEquals("localhost", restored.domain)
        assertEquals("/", restored.path)
        assertEquals(-1, restored.maxAge)
        assertEquals(0, restored.version)
        assertFalse(restored.secure)
        assertTrue(restored.isHttpOnly)
        assertTrue(restored.discard)
    }

    @Test
    fun namelessRowsFromBrokenApi35DumpsDecodeToNull() {
        // Pre-fix API 35 builds persisted rows containing only these two fields;
        // they cannot be recovered and must not crash the whole store read.
        val brokenJson = """{"httpOnly":false,"whenCreated":1700000000000}"""

        assertNull(gson.fromJson(brokenJson, HttpCookie::class.java))
    }

    @Test
    fun decodePreservesElapsedExpiry() {
        // A cookie persisted 50s ago with maxAge=100 must decode with ~50s left,
        // not a fresh 100s (the reflective dump preserved whenCreated; rows from
        // this adapter carry savedAt, legacy rows carry whenCreated).
        val fiftySecondsAgo = System.currentTimeMillis() - 50_000
        val newFormatJson = """{"name":"k","value":"v","maxAge":100,"savedAt":$fiftySecondsAgo}"""
        val legacyJson = """{"name":"k","value":"v","maxAge":100,"whenCreated":$fiftySecondsAgo}"""

        val fromNew = gson.fromJson(newFormatJson, HttpCookie::class.java)
        val fromLegacy = gson.fromJson(legacyJson, HttpCookie::class.java)

        assertTrue(fromNew.maxAge in 1..51)
        assertTrue(fromLegacy.maxAge in 1..51)
    }

    @Test
    fun serializedRowCarriesCreationTimestamp() {
        // Whether via the reflective whenCreated read or the savedAt fallback,
        // every row must carry a creation timestamp or expiry can't be preserved.
        val obj = gson.fromJson(
            gson.toJson(HttpCookie("k", "v").apply { maxAge = 3600 }),
            com.google.gson.JsonObject::class.java
        )
        assertTrue(obj.has("whenCreated") || obj.has("savedAt"))
    }

    @Test
    fun expiredCookieStaysExpired() {
        val past = System.currentTimeMillis() - 600_000
        val json = """{"name":"k","value":"v","maxAge":60,"whenCreated":$past}"""

        val restored = gson.fromJson(json, HttpCookie::class.java)

        assertEquals(0, restored.maxAge)
        assertTrue(restored.hasExpired())
    }
}
