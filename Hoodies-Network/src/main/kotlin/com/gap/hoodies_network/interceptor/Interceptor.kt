package com.gap.hoodies_network.interceptor

import android.content.Context
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Result
import com.gap.hoodies_network.request.Request
import com.gap.hoodies_network.request.CancellableMutableRequest
import com.gap.hoodies_network.request.RetryableCancellableMutableRequest

/**
 * Base class for request, network, error and response hooks. Override only the stages you need.
 *
 * Hooks see full URLs, headers and bodies (including cookies and auth headers), so implementations must not
 * log or persist them unredacted.
 *
 * @param context required by [interceptNetwork] implementations that check connectivity
 */
open class Interceptor(val context: Context?) {

    /**
     * Called before a request is sent; may mutate or cancel it.
     */
    open fun interceptRequest(identifier: String, cancellableMutableRequest: CancellableMutableRequest) {
        //Stub
    }

    /**
     * Called when a request fails; may retry it via [retryableCancellableMutableRequest].
     */
    open fun interceptError(error: HoodiesNetworkError, retryableCancellableMutableRequest: RetryableCancellableMutableRequest, autoRetryAttempts: Int) {
        //Stub
    }

    /**
     * Called with the current connectivity state before a request is sent.
     */
    open fun interceptNetwork(isOnline: Boolean, cancellableMutableRequest: CancellableMutableRequest) {
        //Stub
    }

    /**
     * Called with the result of every request.
     */
    open fun interceptResponse(result: Result<*, HoodiesNetworkError>, request: Request<Any>?) {
        //Stub
    }

}
