package com.gap.hoodies_network.sample

import android.app.Application
import android.graphics.Bitmap
import android.widget.ImageView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gap.hoodies_network.cache.configuration.CacheConfiguration
import com.gap.hoodies_network.cache.configuration.CacheDisabled
import com.gap.hoodies_network.cache.configuration.CacheEnabled
import com.gap.hoodies_network.core.Failure
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.Result
import com.gap.hoodies_network.core.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.UUID

sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Loaded<T>(val value: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class CacheSource { NETWORK, CACHE }

data class CacheResult(val reading: ClockReading, val source: CacheSource, val serverHits: Int)

class SampleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SampleApplication
    private val client = app.client
    private val interceptedClient = app.interceptedClient
    val interceptor: SampleInterceptor = app.interceptor

    /** Unique per process so each launch starts with an empty cache entry. */
    private val clockPath = "clock/${UUID.randomUUID()}"

    /** Serialises /clock calls so the hit-counter comparison only sees this request's hit. */
    private val clockMutex = Mutex()

    private val _get = MutableStateFlow<UiState<Greeting>>(UiState.Idle)
    val get: StateFlow<UiState<Greeting>> = _get.asStateFlow()

    private val _post = MutableStateFlow<UiState<EchoResponse>>(UiState.Idle)
    val post: StateFlow<UiState<EchoResponse>> = _post.asStateFlow()

    private val _image = MutableStateFlow<UiState<Bitmap>>(UiState.Idle)
    val image: StateFlow<UiState<Bitmap>> = _image.asStateFlow()

    private val _cache = MutableStateFlow<UiState<CacheResult>>(UiState.Idle)
    val cache: StateFlow<UiState<CacheResult>> = _cache.asStateFlow()

    private val _secure = MutableStateFlow<UiState<SecureStatus>>(UiState.Idle)
    val secure: StateFlow<UiState<SecureStatus>> = _secure.asStateFlow()

    fun fetchGreeting() = launchInto(_get) {
        client.get<Greeting>("greeting")
    }

    fun postNote(title: String, body: String) = launchInto(_post) {
        client.post<Note, EchoResponse>("echo", Note(title, body))
    }

    fun fetchImage() = launchInto(_image) {
        when (val result = client.getImage(
            api = "image",
            maxWidth = 0,
            maxHeight = 0,
            scaleType = ImageView.ScaleType.CENTER_INSIDE,
            config = Bitmap.Config.ARGB_8888
        )) {
            is Success -> result.value?.let { Success(it) }
                ?: Failure(HoodiesNetworkError("Image could not be decoded", 0))
            is Failure -> result
        }
    }

    fun fetchClock(useCache: Boolean) {
        val config: CacheConfiguration = if (useCache) {
            CacheEnabled(staleDataThreshold = Duration.ofMinutes(5), applicationContext = app)
        } else {
            CacheDisabled()
        }
        launchInto(_cache) {
            clockMutex.withLock {
                val hitsBefore = app.mockServer.clockHits
                when (val result = client.get<ClockReading>(clockPath, cacheConfiguration = config)) {
                    is Success -> {
                        val hitsAfter = app.mockServer.clockHits
                        val source = if (hitsAfter == hitsBefore) CacheSource.CACHE else CacheSource.NETWORK
                        Success(CacheResult(result.value, source, hitsAfter))
                    }
                    is Failure -> result
                }
            }
        }
    }

    fun setAttachToken(attach: Boolean) {
        interceptor.setAttachToken(attach)
    }

    fun callSecure() {
        interceptor.clearEvents()
        launchInto(_secure) {
            interceptedClient.get<SecureStatus>("secure")
        }
    }

    private fun <T> launchInto(
        state: MutableStateFlow<UiState<T>>,
        request: suspend () -> Result<T, HoodiesNetworkError>
    ) {
        state.value = UiState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { request() }
            state.value = when (result) {
                is Success -> UiState.Loaded(result.value)
                is Failure -> UiState.Error("HTTP ${result.reason.code}: ${result.reason.message}")
            }
        }
    }
}
