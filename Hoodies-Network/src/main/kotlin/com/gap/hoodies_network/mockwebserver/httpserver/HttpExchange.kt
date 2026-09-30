package com.sun.net.httpserver

import com.gap.hoodies_network.utils.Generated
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI

/**
 * Source-compatible replacement for the `com.sun.net.httpserver.HttpExchange` class that used to
 * be provided by the bundled `http-2.2.1.jar`.
 *
 * Only the request/response members used by the MockWebServer are provided; `getHttpContext()`,
 * `getPrincipal()` and `setStreams()` from the JDK API are intentionally omitted.
 */
@Generated
abstract class HttpExchange protected constructor() : AutoCloseable {
    abstract val requestHeaders: Headers
    abstract val responseHeaders: Headers
    abstract val requestURI: URI
    abstract val requestMethod: String
    abstract val requestBody: InputStream
    abstract val responseBody: OutputStream
    abstract val remoteAddress: InetSocketAddress
    abstract val localAddress: InetSocketAddress
    abstract val protocol: String

    /**
     * The status code passed to [sendResponseHeaders], or -1 if it has not been called yet
     */
    abstract val responseCode: Int

    /**
     * Starts the response with [rCode]. [responseLength] > 0 sends a fixed-length body of exactly
     * that many bytes, 0 sends a chunked body of arbitrary length and -1 sends no body.
     */
    @Throws(IOException::class)
    abstract fun sendResponseHeaders(rCode: Int, responseLength: Long)

    abstract fun getAttribute(name: String): Any?

    abstract fun setAttribute(name: String, value: Any?)

    abstract override fun close()
}
