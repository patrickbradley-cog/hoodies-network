package com.gap.hoodies_network.interceptor

import android.content.Context
import java.io.IOException

/**
 * Implement this interface to encrypt requests and responses.
 * If you do not want to perform any encryption for a particular case, just return the input ByteArray
 *
 * This is application-layer payload encryption on top of the transport. It does not replace TLS and does not
 * authenticate the server; use HTTPS and the app's Network Security Config for that.
 * Implementations are responsible for their own key management and for using an authenticated cipher mode.
 */
interface EncryptionDecryptionInterceptor {
    val context: Context

    /**
     * @param requestBodyOrUrlQueryParamKeyValue -  Request body encryption works in 2 ways:
     * 1. GET params (in URL, urlQueryEncoded, etc): This function is called to encrypt every key and value
     * 2. Parameters in body (other requests): This function is called once to encrypt the entire body before the request is sent
     */
    @Throws(IOException::class)
    fun encryptRequest(requestBodyOrUrlQueryParamKeyValue: ByteArray) : ByteArray

    /**
     * @param additionalHeaderValue - Called to encrypt every value for additional headers
     */
    fun encryptAdditionalHeaders(additionalHeaderValue: ByteArray) : ByteArray

    /**
     * @param response - The raw response from the server
     * This is called before any response parsing to decrypt any encrypted response
     */
    fun decryptResponse(response: ByteArray) : ByteArray
}
