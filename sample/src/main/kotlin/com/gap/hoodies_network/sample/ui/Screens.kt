package com.gap.hoodies_network.sample.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gap.hoodies_network.sample.CacheSource
import com.gap.hoodies_network.sample.SampleViewModel
import com.gap.hoodies_network.sample.UiState

@Composable
private fun ScreenColumn(description: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(description, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun <T> StateView(state: UiState<T>, tag: String, loaded: @Composable (T) -> Unit) {
    when (state) {
        UiState.Idle -> Text("Not requested yet", modifier = Modifier.testTag("${tag}_idle"))
        UiState.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("${tag}_loading"))
        is UiState.Error -> Text(
            state.message,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("${tag}_error")
        )
        is UiState.Loaded -> Column(modifier = Modifier.testTag("${tag}_result")) { loaded(state.value) }
    }
}

@Composable
fun GetScreen(viewModel: SampleViewModel) {
    val state by viewModel.get.collectAsStateWithLifecycle()
    ScreenColumn("client.get<Greeting>(\"greeting\") parses the JSON response into a data class.") {
        Button(onClick = viewModel::fetchGreeting, modifier = Modifier.testTag("get_button")) {
            Text("Send GET /greeting")
        }
        StateView(state, "get") { greeting ->
            Text(greeting.message, modifier = Modifier.testTag("get_message"))
            Text("Request #${greeting.requestNumber}", modifier = Modifier.testTag("get_request_number"))
        }
    }
}

@Composable
fun PostScreen(viewModel: SampleViewModel) {
    val state by viewModel.post.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("Hello") }
    var body by rememberSaveable { mutableStateOf("Posted from the Compose sample") }
    ScreenColumn("client.post<Note, EchoResponse>(\"echo\", note) serialises the body with Gson; the server echoes it back.") {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("post_title")
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Body") },
            modifier = Modifier.fillMaxWidth().testTag("post_body")
        )
        Button(onClick = { viewModel.postNote(title, body) }, modifier = Modifier.testTag("post_button")) {
            Text("Send POST /echo")
        }
        StateView(state, "post") { echo ->
            Text("Method: ${echo.method}", modifier = Modifier.testTag("post_method"))
            Text("Title: ${echo.received.title}", modifier = Modifier.testTag("post_echo_title"))
            Text("Body: ${echo.received.body}", modifier = Modifier.testTag("post_echo_body"))
        }
    }
}

@Composable
fun ImageScreen(viewModel: SampleViewModel) {
    val state by viewModel.image.collectAsStateWithLifecycle()
    ScreenColumn("client.getImage(\"image\", …) decodes the response into a Bitmap.") {
        Button(onClick = viewModel::fetchImage, modifier = Modifier.testTag("image_button")) {
            Text("Load GET /image")
        }
        StateView(state, "image") { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Image served by the mock server",
                modifier = Modifier.size(192.dp).testTag("image_bitmap")
            )
            Text("${bitmap.width} x ${bitmap.height} px", modifier = Modifier.testTag("image_size"))
        }
    }
}

@Composable
fun CacheScreen(viewModel: SampleViewModel) {
    val state by viewModel.cache.collectAsStateWithLifecycle()
    ScreenColumn(
        "CacheEnabled(staleDataThreshold = 5 min) serves repeat GETs from the local cache; " +
            "CacheDisabled() always goes to the server."
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.fetchClock(useCache = true) }, modifier = Modifier.testTag("cache_fetch_cached")) {
                Text("GET with cache")
            }
            OutlinedButton(onClick = { viewModel.fetchClock(useCache = false) }, modifier = Modifier.testTag("cache_fetch_network")) {
                Text("GET without cache")
            }
        }
        StateView(state, "cache") { result ->
            val source = when (result.source) {
                CacheSource.CACHE -> "cache"
                CacheSource.NETWORK -> "network"
            }
            Text("Served from: $source", modifier = Modifier.testTag("cache_source"))
            Text("Response hit #${result.reading.hit}", modifier = Modifier.testTag("cache_hit"))
            Text("Server hits so far: ${result.serverHits}", modifier = Modifier.testTag("cache_server_hits"))
            Text("Generated at ${result.reading.generatedAt}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun InterceptorScreen(viewModel: SampleViewModel) {
    val state by viewModel.secure.collectAsStateWithLifecycle()
    val events by viewModel.interceptor.events.collectAsStateWithLifecycle()
    var attachToken by rememberSaveable { mutableStateOf(viewModel.interceptor.attachToken) }
    ScreenColumn("An Interceptor adds the auth header in interceptRequest and logs every stage.") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = attachToken,
                onCheckedChange = {
                    attachToken = it
                    viewModel.setAttachToken(it)
                },
                modifier = Modifier.testTag("interceptor_toggle")
            )
            Text("Attach token header")
        }
        Button(onClick = viewModel::callSecure, modifier = Modifier.testTag("interceptor_button")) {
            Text("Send GET /secure")
        }
        StateView(state, "interceptor") { status ->
            Text("Status: ${status.status}", modifier = Modifier.testTag("interceptor_status"))
        }
        Text("Interceptor log", style = MaterialTheme.typography.titleSmall)
        Column(modifier = Modifier.testTag("interceptor_log")) {
            events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
