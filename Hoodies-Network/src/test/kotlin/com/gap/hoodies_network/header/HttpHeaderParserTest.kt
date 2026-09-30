package com.gap.hoodies_network.header

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class HttpHeaderParserTest {

    @Test
    fun defaultsToUtf8WithoutContentType() {
        assertEquals(StandardCharsets.UTF_8, HttpHeaderParser.parseCharset(emptyMap()))
    }

    @Test
    fun readsCharsetParameterFromContentType() {
        val headers = mapOf("Content-Type" to "text/html; charset=ISO-8859-1")
        assertEquals(StandardCharsets.ISO_8859_1, HttpHeaderParser.parseCharset(headers))
    }

    @Test
    fun fallsBackToProvidedDefaultWhenCharsetMissing() {
        val headers = mapOf("Content-Type" to "application/json")
        assertEquals(StandardCharsets.US_ASCII, HttpHeaderParser.parseCharset(headers, StandardCharsets.US_ASCII))
    }

    @Test
    fun ignoresMalformedParameters() {
        val headers = mapOf("Content-Type" to "text/plain; charset; foo=bar")
        assertEquals(StandardCharsets.UTF_8, HttpHeaderParser.parseCharset(headers))
    }
}
