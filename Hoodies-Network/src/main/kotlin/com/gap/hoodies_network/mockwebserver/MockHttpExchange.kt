package com.gap.hoodies_network.mockwebserver

import com.gap.hoodies_network.utils.Generated
import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpExchange
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [HttpExchange] backed by a request received by mockwebserver3.
 * The response is buffered and converted into a [MockResponse] once the handler returns.
 */
@Generated
internal class MockHttpExchange(
    override val requestMethod: String,
    override val requestURI: URI,
    override val protocol: String,
    override val requestHeaders: Headers,
    requestBody: ByteArray,
    override val remoteAddress: InetSocketAddress,
    override val localAddress: InetSocketAddress,
) : HttpExchange() {
    override val requestBody: InputStream = ByteArrayInputStream(requestBody)
    override val responseHeaders = Headers()

    private val responseBuffer = ByteArrayOutputStream()
    override val responseBody: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            checkCapacity(1)
            responseBuffer.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkCapacity(len)
            responseBuffer.write(b, off, len)
        }
    }

    override var responseCode: Int = -1
        private set

    private var responseLength: Long = -1
    private val attributes = HashMap<String, Any?>()

    override fun sendResponseHeaders(rCode: Int, responseLength: Long) {
        if (responseCode != -1) throw IOException("headers already sent")
        responseCode = rCode
        this.responseLength = responseLength
    }

    private fun checkCapacity(len: Int) {
        if (responseLength > 0 && responseBuffer.size() + len > responseLength) {
            throw IOException("too many bytes to write to stream")
        }
    }

    override fun getAttribute(name: String): Any? = attributes[name]

    override fun setAttribute(name: String, value: Any?) {
        attributes[name] = value
    }

    override fun close() {
        requestBody.close()
        responseBuffer.close()
    }

    /**
     * Returns the response written by the handler, or null if [sendResponseHeaders] was never called.
     * A fixed-length response with fewer bytes than declared closes the connection.
     */
    fun toMockResponse(): MockResponse? {
        if (responseCode == -1) return null
        if (responseLength > 0 && requestMethod != "HEAD" && responseBuffer.size().toLong() != responseLength) {
            return MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
        }

        val builder = MockResponse.Builder()
            .status("HTTP/1.1 $responseCode${reasonPhrase(responseCode)}")
            .addHeaderLenient("Date", httpDate())
        for ((name, values) in responseHeaders) {
            for (value in values) builder.addHeaderLenient(name, value)
        }

        val body = Buffer().write(responseBuffer.toByteArray())
        when {
            responseLength == -1L || requestMethod == "HEAD" -> builder.setHeader("Content-Length", 0)
            responseLength == 0L -> builder.chunkedBody(body, CHUNK_SIZE)
            else -> builder.body(body)
        }
        return builder.build()
    }

    private fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

    private fun reasonPhrase(code: Int): String = when (code) {
        100 -> " Continue"
        200 -> " OK"
        201 -> " Created"
        202 -> " Accepted"
        203 -> " Non-Authoritative Information"
        204 -> " No Content"
        205 -> " Reset Content"
        206 -> " Partial Content"
        300 -> " Multiple Choices"
        301 -> " Moved Permanently"
        302 -> " Temporary Redirect"
        303 -> " See Other"
        304 -> " Not Modified"
        305 -> " Use Proxy"
        400 -> " Bad Request"
        401 -> " Unauthorized"
        402 -> " Payment Required"
        403 -> " Forbidden"
        404 -> " Not Found"
        405 -> " Method Not Allowed"
        406 -> " Not Acceptable"
        407 -> " Proxy Authentication Required"
        408 -> " Request Time-Out"
        409 -> " Conflict"
        410 -> " Gone"
        411 -> " Length Required"
        412 -> " Precondition Failed"
        413 -> " Request Entity Too Large"
        414 -> " Request-URI Too Large"
        415 -> " Unsupported Media Type"
        500 -> " Internal Server Error"
        501 -> " Not Implemented"
        502 -> " Bad Gateway"
        503 -> " Service Unavailable"
        504 -> " Gateway Timeout"
        505 -> " HTTP Version Not Supported"
        else -> ""
    }

    private companion object {
        const val CHUNK_SIZE = 16 * 1024
    }
}
