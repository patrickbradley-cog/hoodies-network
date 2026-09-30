package com.gap.hoodies_network.request

import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.core.Response
import java.io.UnsupportedEncodingException
import java.net.CookieManager
import java.net.URLEncoder

/**
 * [StringRequest] whose body is [requestBody] encoded as `application/x-www-form-urlencoded`
 * (`key1=value1&key2=value2`, UTF-8 percent-encoded).
 *
 * @param url absolute URL of the request.
 * @param method HTTP method, one of [Request.Method].
 * @param requestBody form fields, or `null` for an empty body.
 * @param responseListener listener receiving the parsed response, or `null`.
 * @param errorListener listener receiving errors, or `null`.
 * @param encryptedCache cache used for this request.
 * @param cookieManager cookie manager, or `null`.
 */
class FormUrlEncodedRequest(
    url: String,
    method: String,
    requestBody: Map<String, String>?,
    responseListener: Response.ResponseListener?,
    errorListener: Response.ErrorListener?,
    encryptedCache: EncryptedCache,
    cookieManager: CookieManager?
) : StringRequest(
    url,
    method,
    convertToParameterizedString(requestBody),
    responseListener,
    errorListener,
    encryptedCache,
    cookieManager
) {
    /** Form encoding helpers of [FormUrlEncodedRequest]. */
    companion object {
        @Throws(UnsupportedEncodingException::class)
        private fun convertToParameterizedString(requestBody: Map<String, String>?): String =
            requestBody?.entries?.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            } ?: ""
    }

}
