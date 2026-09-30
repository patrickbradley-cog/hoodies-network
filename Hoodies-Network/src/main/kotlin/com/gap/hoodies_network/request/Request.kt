package com.gap.hoodies_network.request

import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Response
import com.gap.hoodies_network.core.Result
import java.io.File
import java.net.CookieManager
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections.emptyMap

/**
 * Base class of every network request executed by Hoodies-Network.
 *
 * A request carries the target URL, HTTP method, body (plain string or multipart files), headers,
 * the [EncryptedCache] used for this call and an optional [CookieManager]. Subclasses decide how a
 * raw [Response] is parsed ([parseNetworkResponse]) and delivered ([deliverResponse]).
 *
 * Interceptors can cancel a request through [CancellableMutableRequest] or re-enqueue it through
 * [RetryableCancellableMutableRequest]; that state is published with volatile writes so that it is
 * visible to the thread that dispatches the request.
 *
 * @param T the type of parsed response which the request expects.
 */
abstract class Request<T> : Comparable<Request<T>?> {

    private val method: String
    private val url: String

    /** Request body sent to the server; an empty string when the request has no body. */
    var requestBody: String

    /** Cache used to read and store the result of this request. */
    var cache: EncryptedCache = EncryptedCache()

    /** Cookie manager applied to this request, or `null` when cookies are not handled. */
    var cookieManager: CookieManager? = null

    /** Files sent as `multipart/form-data`, or `null` for non-multipart requests. */
    var files: List<File>? = null

    /** Boundary separating the parts of a `multipart/form-data` body. */
    var multipartBoundary: String = ""

    private var headers: Map<String, String?> = emptyMap()

    /** Listener notified by [deliverError], or `null` when errors are not reported. */
    var errorListener: Response.ErrorListener? = null

    @Volatile
    internal var requestIsCancelled = false

    @Volatile
    internal var retryingRequest = false

    @Volatile
    internal var cancellationResult: Result<*, HoodiesNetworkError>? = null

    /**
     * Marks this request as cancelled and records [result] as the value returned to the caller
     * instead of executing the request. [result] is published before the cancelled flag so any
     * thread observing the flag also observes the result.
     */
    internal fun cancel(result: Result<*, HoodiesNetworkError>) {
        cancellationResult = result
        requestIsCancelled = true
    }

    /** Flags this request as being retried by an interceptor and returns it for re-enqueueing. */
    internal fun markRetrying(): Request<T> {
        retryingRequest = true
        return this
    }

    /**
     * Parses the raw network [response] into the form expected by [deliverResponse].
     *
     * @param response raw response returned by the network layer, or `null`.
     * @return the parsed response, or `null` when there is nothing to deliver.
     * @throws HoodiesNetworkError when the response cannot be parsed.
     */
    @Throws(HoodiesNetworkError::class)
    abstract fun parseNetworkResponse(response: Response<Any>?): Response<Any>?

    /**
     * Delivers a parsed [response] to the listener of this request.
     *
     * @param response the value returned by [parseNetworkResponse].
     */
    abstract fun deliverResponse(response: Response<Any>?)

    /**
     * Delivers [hoodiesNetworkError] to [errorListener], if one is set.
     *
     * @return [Unit] when a listener was notified, `null` otherwise.
     */
    fun deliverError(hoodiesNetworkError: HoodiesNetworkError) =
        errorListener?.onErrorResponse(hoodiesNetworkError)

    /** Supported request methods. */
    interface Method {
        /** HTTP method names accepted by [Request]. */
        companion object {
            /** HTTP `GET`. */
            const val GET = "GET"

            /** HTTP `POST`. */
            const val POST = "POST"

            /** HTTP `PUT`. */
            const val PUT = "PUT"

            /** HTTP `DELETE`. */
            const val DELETE = "DELETE"

            /** HTTP `HEAD`. */
            const val HEAD = "HEAD"

            /** HTTP `OPTIONS`. */
            const val OPTIONS = "OPTIONS"

            /** HTTP `TRACE`. */
            const val TRACE = "TRACE"

            /** HTTP `PATCH`. */
            const val PATCH = "PATCH"
        }
    }

    /**
     * Creates a request without a body or error listener.
     *
     * @param url absolute URL of the request.
     * @param method HTTP method, one of [Method].
     * @param encryptedCache cache used for this request.
     * @param cookieManager cookie manager, or `null`.
     */
    constructor(
        url: String,
        method: String,
        encryptedCache: EncryptedCache,
        cookieManager: CookieManager?
    ) {
        this.url = url
        this.method = method
        this.requestBody = ""
        this.cache = encryptedCache
        this.cookieManager = cookieManager
    }

    /**
     * Creates a request with a string body.
     *
     * @param url absolute URL of the request.
     * @param method HTTP method, one of [Method].
     * @param requestBody body sent to the server.
     * @param errorListener listener notified by [deliverError], or `null`.
     * @param encryptedCache cache used for this request.
     * @param cookieManager cookie manager, or `null`.
     */
    constructor(
        url: String,
        method: String,
        requestBody: String,
        errorListener: Response.ErrorListener?,
        encryptedCache: EncryptedCache,
        cookieManager: CookieManager?
    ) {
        this.url = url
        this.method = method
        this.errorListener = errorListener
        this.requestBody = requestBody
        this.cache = encryptedCache
        this.cookieManager = cookieManager
    }

    /**
     * Creates a `multipart/form-data` request uploading [files].
     *
     * @param url absolute URL of the request.
     * @param method HTTP method, one of [Method].
     * @param files files sent as individual parts.
     * @param multipartBoundary boundary separating the parts.
     * @param errorListener listener notified by [deliverError], or `null`.
     * @param cookieManager cookie manager, or `null`.
     */
    constructor(
        url: String,
        method: String,
        files: List<File>,
        multipartBoundary: String,
        errorListener: Response.ErrorListener?,
        cookieManager: CookieManager?
    ) {
        this.url = url
        this.method = method
        this.requestBody = ""
        this.multipartBoundary = multipartBoundary
        this.files = files
        this.errorListener = errorListener
        this.cookieManager = cookieManager
    }

    /** Returns the HTTP method of this request, one of the values in [Method]. */
    open fun getMethod(): String {
        return this.method
    }

    /** Returns the absolute URL of this request. */
    open fun getUrl(): String {
        return this.url
    }

    /** Returns the request body; same as [requestBody]. */
    fun getBody(): String {
        return this.requestBody
    }

    /** Returns the multipart body built from [files]; see [postMultipartFormData]. */
    fun getFile(): ArrayList<ByteArray> {
        return postMultipartFormData(files)
    }

    /** Returns the headers sent with this request. */
    open fun getHeaders(): Map<String, String?> {
        return this.headers
    }

    /** Replaces the headers sent with this request. */
    fun setRequestHeaders(headers: Map<String, String?>) {
        this.headers = headers
    }

    /** Requests have no relative priority: every comparison returns `0`. */
    override fun compareTo(other: Request<T>?): Int {
        return 0
    }

    /**
     * Encodes [data] as the chunks of a `multipart/form-data` body delimited by [multipartBoundary].
     * Each file becomes a part named `file<index>` carrying its file name and probed content type.
     *
     * @param data files to encode, or `null`.
     * @return the body chunks in write order; a single empty array when [data] is `null`.
     */
    fun postMultipartFormData(data: List<File>?): ArrayList<ByteArray> {
        val byteArrays = ArrayList<ByteArray>()
        val separator =
            "--$multipartBoundary\r\nContent-Disposition: multipart/form-data; name=".toByteArray(
                StandardCharsets.UTF_8
            )

        data ?: return arrayListOf(ByteArray(0))

        for (file in data) {
            byteArrays.add(separator)

            val path = Paths.get(file.toURI())
            val mimeType = Files.probeContentType(path)
            byteArrays.add(
                "file${data.indexOf(file)}; filename=\"${path.fileName}\"\r\nContent-Type: $mimeType\r\n\r\n".toByteArray(
                    StandardCharsets.UTF_8
                )
            )
            byteArrays.add(Files.readAllBytes(path))
            byteArrays.add("\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        byteArrays.add("--$multipartBoundary--".toByteArray(StandardCharsets.UTF_8))

        return byteArrays
    }

}
