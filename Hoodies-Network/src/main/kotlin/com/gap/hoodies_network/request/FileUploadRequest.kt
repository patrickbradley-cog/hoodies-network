package com.gap.hoodies_network.request

import android.util.Log
import com.gap.hoodies_network.core.HoodiesNetworkClient
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.JSON_ERROR_CODE
import com.gap.hoodies_network.core.NULL_POINTER_ERROR_CODE
import com.gap.hoodies_network.core.Response
import com.gap.hoodies_network.core.UNSUPPORTED_ENCODING_ERROR_CODE
import com.gap.hoodies_network.header.HttpHeaderParser
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.UnsupportedEncodingException
import java.net.CookieManager

/**
 * `multipart/form-data` upload whose response body is parsed as a [JSONObject].
 *
 * @param url absolute URL of the request; must not be `null`.
 * @param files files sent as individual parts.
 * @param multipartBoundary boundary separating the parts.
 * @param method HTTP method of the upload.
 * @param responseListener listener receiving the parsed response, or `null`.
 * @param errorListener listener receiving errors, or `null`.
 * @param cookieManager cookie manager, or `null`.
 */
class FileUploadRequest(
    url: String?,
    files: List<File>,
    multipartBoundary: String,
    method: HoodiesNetworkClient.HttpMethod,
    responseListener: Response.ResponseListener? = null,
    errorListener: Response.ErrorListener?,
    cookieManager: CookieManager?
) : FileRequest<File?>(
    url,
    method.value,
    files,
    multipartBoundary,
    responseListener,
    errorListener,
    cookieManager
) {

    /**
     * Decodes the response body (charset from `Content-Type`, defaulting to [PROTOCOL_CHARSET])
     * into a [JSONObject] and stores it with [Response.setResultResponse].
     *
     * @throws HoodiesNetworkError with [UNSUPPORTED_ENCODING_ERROR_CODE], [JSON_ERROR_CODE] or
     * [NULL_POINTER_ERROR_CODE].
     */
    @Throws(HoodiesNetworkError::class)
    override fun parseNetworkResponse(response: Response<Any>?): Response<Any>? {
        return try {
            val jsonString = response?.getData()?.let { data ->
                Response.toHeaderMap(response.getAllHeaders())
                    ?.let { headers -> HttpHeaderParser.parseCharset(headers, charset(PROTOCOL_CHARSET)) }
                    ?.let { charset -> String(data, charset) }
            }
            response?.setResultResponse(JSONObject(jsonString!!))
            response
        } catch (e: UnsupportedEncodingException) {
            Log.e("parseNetworkResponse", e.toString())
            throw HoodiesNetworkError(e.message, UNSUPPORTED_ENCODING_ERROR_CODE)
        } catch (e: JSONException) {
            Log.e("parseNetworkResponse", e.toString())
            throw HoodiesNetworkError(e.message, JSON_ERROR_CODE)
        } catch (e: NullPointerException) {
            Log.e("parseNetworkResponse", e.toString())
            throw HoodiesNetworkError(e.message, NULL_POINTER_ERROR_CODE)
        }
    }

    /** Constants of [FileUploadRequest]. */
    companion object {
        /** Charset used to decode the response when it does not declare one. */
        const val PROTOCOL_CHARSET = "utf-8"
    }

}
