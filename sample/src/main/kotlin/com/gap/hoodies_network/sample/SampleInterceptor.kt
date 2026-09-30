package com.gap.hoodies_network.sample

import android.content.Context
import com.gap.hoodies_network.core.Failure
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Result
import com.gap.hoodies_network.core.Success
import com.gap.hoodies_network.interceptor.Interceptor
import com.gap.hoodies_network.request.CancellableMutableRequest
import com.gap.hoodies_network.request.Request
import com.gap.hoodies_network.request.RetryableCancellableMutableRequest
import com.gap.hoodies_network.sample.server.SampleMockServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Records every interceptor stage and, when [attachToken] is on, adds the auth header the
 * mock `/secure` endpoint expects.
 */
class SampleInterceptor(context: Context) : Interceptor(context) {

    private val _attachToken = MutableStateFlow(true)
    val attachToken: StateFlow<Boolean> = _attachToken.asStateFlow()

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()

    fun setAttachToken(attach: Boolean) {
        _attachToken.value = attach
    }

    fun clearEvents() {
        _events.value = emptyList()
    }

    private fun record(event: String) {
        _events.update { it + event }
    }

    override fun interceptNetwork(isOnline: Boolean, cancellableMutableRequest: CancellableMutableRequest) {
        record("interceptNetwork: online=$isOnline")
    }

    override fun interceptRequest(identifier: String, cancellableMutableRequest: CancellableMutableRequest) {
        if (attachToken.value) {
            val headers = HashMap(cancellableMutableRequest.request.getHeaders())
            headers[SampleMockServer.TOKEN_HEADER] = SampleMockServer.TOKEN_VALUE
            cancellableMutableRequest.request.setRequestHeaders(headers)
            record("interceptRequest: added ${SampleMockServer.TOKEN_HEADER}")
        } else {
            record("interceptRequest: no token")
        }
    }

    override fun interceptError(
        error: HoodiesNetworkError,
        retryableCancellableMutableRequest: RetryableCancellableMutableRequest,
        autoRetryAttempts: Int
    ) {
        record("interceptError: HTTP ${error.code}")
    }

    override fun interceptResponse(result: Result<*, HoodiesNetworkError>, request: Request<Any>?) {
        val outcome = when (result) {
            is Success -> "success"
            is Failure -> "failure"
        }
        record("interceptResponse: $outcome")
    }
}
