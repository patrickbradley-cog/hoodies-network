package com.gap.hoodies_network.sample.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gap.hoodies_network.sample.SampleViewModel

enum class SampleScreen(val title: String, val tag: String) {
    GET("GET", "get"),
    POST("POST", "post"),
    IMAGE("Image", "image"),
    CACHE("Cache", "cache"),
    INTERCEPTOR("Interceptor", "interceptor"),
}

@Composable
fun SampleApp(viewModel: SampleViewModel = viewModel()) {
    var selected by rememberSaveable { mutableStateOf(SampleScreen.GET) }
    MaterialTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                PrimaryScrollableTabRow(selectedTabIndex = selected.ordinal) {
                    SampleScreen.entries.forEach { screen ->
                        Tab(
                            selected = screen == selected,
                            onClick = { selected = screen },
                            text = { Text(screen.title) },
                            modifier = Modifier.testTag("tab_${screen.tag}")
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .testTag("screen_${selected.tag}")
                ) {
                    when (selected) {
                        SampleScreen.GET -> GetScreen(viewModel)
                        SampleScreen.POST -> PostScreen(viewModel)
                        SampleScreen.IMAGE -> ImageScreen(viewModel)
                        SampleScreen.CACHE -> CacheScreen(viewModel)
                        SampleScreen.INTERCEPTOR -> InterceptorScreen(viewModel)
                    }
                }
            }
        }
    }
}
