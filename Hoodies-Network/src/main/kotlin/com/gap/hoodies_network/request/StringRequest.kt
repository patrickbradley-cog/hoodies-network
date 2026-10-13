package com.gap.hoodies_network.request

import android.util.Log
import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.NULL_POINTER_ERROR_CODE
import com.gap.hoodies_network.core.Response
import com.gap.hoodies_network.core.UNSUPPORTED_ENCODING_ERROR_CODE
import com.gap.hoodies_network.header.HttpHeaderParser
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.net.CookieManager

/**
 * Request whose response body is decoded as a [String] using the charset from its
 * `Content-Type` header.
 *
 * @param url absolute URL of the request.
 * @param method HTTP method, one of [Request.Method].
 * @param requestBody body sent to the server.
 * @param responseListener listener receiving the parsed response, or `null`.
 * @param errorListener listener receiving errors, or `null`.
 * @param encryptedCache cache used for this request.
 * @param cookieManager cookie manager, or `null`.
 */
open class StringRequest(
    url: String, method: String, requestBody: String,
    private val responseListener: Response.ResponseListener?,
    errorListener: Response.ErrorListener?,
    encryptedCache: EncryptedCache,
    cookieManager: CookieManager?
) : Request<String?>(url, method, requestBody, errorListener, encryptedCache, cookieManager) {

    /**
     * Creates a request without listeners whose body is [jsonRequest] serialized with
     * [JSONObject.toString] (`"null"` when [jsonRequest] is `null`).
     */
    constructor(
        url: String,
        method: String,
        jsonRequest: JSONObject? = null,
        encryptedCache: EncryptedCache,
        cookieManager: CookieManager?
    ) : this(
        url,
        method,
        jsonRequest,
        null,
        null,
        encryptedCache,
        cookieManager
    )

    /** Creates a request with listeners and a `"null"` body. */
    constructor(
        url: String,
        method: String,
        responseListener: Response.ResponseListener?,
        errorListener: Response.ErrorListener?,
        encryptedCache: EncryptedCache,
        cookieManager: CookieManager?
    ) : this(
        url,
        method,
        null as JSONObject?,
        responseListener,
        errorListener,
        encryptedCache,
        cookieManager
    )

    /**
     * Creates a request with listeners whose body is [requestBody] serialized with
     * [JSONObject.toString] (`"null"` when [requestBody] is `null`).
     */
    constructor(
        url: String,
        method: String,
        requestBody: JSONObject?,
        responseListener: Response.ResponseListener?,
        errorListener: Response.ErrorListener?,
        encryptedCache: EncryptedCache,
        cookieManager: CookieManager?
    ) : this(
        url,
        method,
        requestBody.toString(),
        responseListener,
        errorListener,
        encryptedCache,
        cookieManager
    )

    /**
     * Decodes the response body as a string and stores it with [Response.setResultResponse].
     *
     * @throws HoodiesNetworkError with [UNSUPPORTED_ENCODING_ERROR_CODE] or [NULL_POINTER_ERROR_CODE].
     */
    @Throws(HoodiesNetworkError::class)
    override fun parseNetworkResponse(response: Response<Any>?): Response<Any>? {
        return try {
            val parsedResponse = response?.getData()?.let { data ->
                Response.toHeaderMap(response.getAllHeaders())
                    ?.let { headers -> HttpHeaderParser.parseCharset(headers) }
                    ?.let { charset -> String(data, charset) }
            }.toString()
            response?.setResultResponse(parsedResponse)
            response
        } catch (e: UnsupportedEncodingException) {
            Log.e("parseNetworkResponse", e.toString())
            throw HoodiesNetworkError(e.message, UNSUPPORTED_ENCODING_ERROR_CODE)
        } catch (e: NullPointerException) {
            Log.e("parseNetworkResponse", e.toString())
            throw HoodiesNetworkError(e.message, NULL_POINTER_ERROR_CODE)
        }
    }

    /** Passes [response] to the response listener, if one is set. */
    override fun deliverResponse(response: Response<Any>?) {
        responseListener?.onResponse(response)
    }
}
