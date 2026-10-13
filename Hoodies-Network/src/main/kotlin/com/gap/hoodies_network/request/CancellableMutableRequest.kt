package com.gap.hoodies_network.request

import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Result

/**
 * Handle given to interceptors that lets them mutate or cancel a [Request] before it is sent.
 *
 * @property request the request being intercepted; headers and body may be changed in place.
 */
open class CancellableMutableRequest(
    val request: Request<Any>
) {
    /**
     * Cancels [request]: it is not sent and [result] is returned to the caller instead.
     *
     * @param result value (typically [com.gap.hoodies_network.core.Success] or
     * [com.gap.hoodies_network.core.Failure]) returned in place of the network result.
     */
    fun cancelRequest(result: Result<*, HoodiesNetworkError>) {
        request.cancel(result)
    }
}
