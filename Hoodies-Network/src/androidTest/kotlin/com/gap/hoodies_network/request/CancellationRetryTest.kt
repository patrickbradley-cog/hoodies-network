package com.gap.hoodies_network.request

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gap.hoodies_network.core.Failure
import com.gap.hoodies_network.core.HoodiesNetworkClient
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Success
import com.gap.hoodies_network.interceptor.Interceptor
import com.gap.hoodies_network.mockwebserver.ServerManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CancellationRetryTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun startMockWebServer() {
        ServerManager.setup(context)
    }

    @After
    fun stopServer() {
        ServerManager.stop()
    }

    private fun clientWith(interceptor: Interceptor) = HoodiesNetworkClient.Builder()
        .baseUrl("http://localhost:6969/")
        .addInterceptor(interceptor)
        .build()

    @Test
    fun cancelRequestWithFailureReturnsThatFailure() {
        val reason = HoodiesNetworkError("blocked by interceptor", 499)
        var interceptedUrl: String? = null
        val interceptor = object : Interceptor(context) {
            override fun interceptRequest(identifier: String, cancellableMutableRequest: CancellableMutableRequest) {
                interceptedUrl = cancellableMutableRequest.request.getUrl()
                cancellableMutableRequest.cancelRequest(Failure(reason))
            }
        }

        val result = runBlocking { clientWith(interceptor).getRaw("echo/10") }

        Assert.assertEquals(Failure(reason), result)
        Assert.assertEquals("http://localhost:6969/echo/10", interceptedUrl)
    }

    @Test
    fun errorIsDeliveredWhenInterceptorDoesNotRetry() {
        var interceptedErrors = 0
        val interceptor = object : Interceptor(context) {
            override fun interceptError(
                error: HoodiesNetworkError,
                retryableCancellableMutableRequest: RetryableCancellableMutableRequest,
                autoRetryAttempts: Int
            ) {
                interceptedErrors++
            }
        }

        val result = runBlocking { clientWith(interceptor).getRaw("wants_key") }

        Assert.assertTrue(result is Failure)
        Assert.assertEquals(403, (result as Failure).reason.code)
        Assert.assertEquals(1, interceptedErrors)
    }

    @Test
    fun retryRequestReplacesErrorWithRetriedResult() {
        var interceptedErrors = 0
        val interceptor = object : Interceptor(context) {
            override fun interceptError(
                error: HoodiesNetworkError,
                retryableCancellableMutableRequest: RetryableCancellableMutableRequest,
                autoRetryAttempts: Int
            ) {
                interceptedErrors++
                val request = retryableCancellableMutableRequest.request
                request.setRequestHeaders(request.getHeaders() + ("key" to "20"))
                retryableCancellableMutableRequest.retryRequest()
            }
        }

        val result = runBlocking { clientWith(interceptor).getRaw("wants_key") }

        Assert.assertTrue(result is Success)
        Assert.assertEquals("Success!", (result as Success).value)
        Assert.assertEquals(1, interceptedErrors)
    }
}
