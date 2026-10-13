package com.gap.hoodies_network.request

import com.gap.hoodies_network.connection.queue.RequestQueue

/**
 * [CancellableMutableRequest] passed to `Interceptor.interceptError` that can also re-send the
 * failed [request], e.g. after refreshing an auth header.
 *
 * @param request the request that failed.
 */
class RetryableCancellableMutableRequest(
    request: Request<Any>
) : CancellableMutableRequest(request) {
    /**
     * Re-enqueues [request] on the shared [RequestQueue]. The error that triggered the interceptor
     * is then not delivered to the caller; the caller receives the result of the retried request.
     */
    fun retryRequest() {
        RequestQueue.instance?.enqueue(request.markRetrying())
    }
}
