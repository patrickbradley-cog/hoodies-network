package com.gap.hoodies_network.request

import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Response
import java.io.File
import java.net.CookieManager

/**
 * Base class of `multipart/form-data` requests that upload [files].
 *
 * @param url absolute URL of the request; must not be `null`.
 * @param method HTTP method, one of [Request.Method]; must not be `null`.
 * @param files files sent as individual parts.
 * @param multipartBoundary boundary separating the parts.
 * @param responseListener listener receiving the parsed response, or `null`.
 * @param errorListener listener receiving errors, or `null`.
 * @param cookieManager cookie manager, or `null`.
 * @param T the type of parsed response which the request expects.
 */
abstract class FileRequest<T>(
    url: String?, method: String?, files: List<File>, multipartBoundary: String,
    private val responseListener: Response.ResponseListener?,
    errorListener: Response.ErrorListener?, cookieManager: CookieManager?
) :
    Request<T>(url!!, method!!, files, multipartBoundary, errorListener, cookieManager) {

    /** Parses the upload response; see [Request.parseNetworkResponse]. */
    @Throws(HoodiesNetworkError::class)
    abstract override fun parseNetworkResponse(response: Response<Any>?): Response<Any>?

    /** Passes [response] to the response listener, if one is set. */
    override fun deliverResponse(response: Response<Any>?) {
        responseListener?.onResponse(response)
    }
}
