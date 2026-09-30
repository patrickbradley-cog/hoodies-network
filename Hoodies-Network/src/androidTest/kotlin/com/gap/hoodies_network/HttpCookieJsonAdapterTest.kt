package com.gap.hoodies_network

import com.gap.hoodies_network.cookies.persistentstorage.HttpCookieJsonAdapter
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
