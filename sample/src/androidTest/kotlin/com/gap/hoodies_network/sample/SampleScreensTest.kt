package com.gap.hoodies_network.sample

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gap.hoodies_network.sample.server.SampleMockServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleScreensTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun openTab(tag: String) {
        composeRule.onNodeWithTag("tab_$tag").performScrollTo().performClick()
        composeRule.onNodeWithTag("tab_$tag").assertIsSelected()
        composeRule.onNodeWithTag("screen_$tag").assertIsDisplayed()
    }

    private fun awaitTag(tag: String, timeoutMillis: Long = 15_000): SemanticsNodeInteraction {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onNodeWithTag(tag, useUnmergedTree = true)
    }

    private fun awaitText(tag: String, text: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodes(hasTestTag(tag) and hasText(text), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitLogLine(line: String, timeoutMillis: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodes(hasText(line), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun textOf(tag: String): String =
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun allScreensAreReachableFromTheTabRow() {
        listOf("get", "post", "image", "cache", "interceptor").forEach { tag ->
            openTab(tag)
            awaitTag("${tag}_idle").assertIsDisplayed()
        }
    }

    @Test
    fun getScreenShowsTypedResponse() {
        openTab("get")
        composeRule.onNodeWithTag("get_button").performClick()
        awaitTag("get_result")
        composeRule.onNodeWithTag("get_message", useUnmergedTree = true)
            .assertTextEquals("Hello from the Hoodies mock server")
        assertTrue(textOf("get_request_number").matches(Regex("Request #\\d+")))
    }

    @Test
    fun postScreenEchoesTheSerializedBody() {
        openTab("post")
        composeRule.onNodeWithTag("post_title").performTextReplacement("Compose test")
        composeRule.onNodeWithTag("post_body").performTextReplacement("Body from SampleScreensTest")
        composeRule.onNodeWithTag("post_button").performClick()
        awaitTag("post_result")
        composeRule.onNodeWithTag("post_method", useUnmergedTree = true).assertTextEquals("Method: POST")
        composeRule.onNodeWithTag("post_echo_title", useUnmergedTree = true).assertTextEquals("Title: Compose test")
        composeRule.onNodeWithTag("post_echo_body", useUnmergedTree = true)
            .assertTextEquals("Body: Body from SampleScreensTest")
    }

    @Test
    fun imageScreenDecodesTheBitmap() {
        openTab("image")
        composeRule.onNodeWithTag("image_button").performClick()
        awaitTag("image_bitmap").assertIsDisplayed()
        val size = SampleMockServer.IMAGE_SIZE
        composeRule.onNodeWithTag("image_size", useUnmergedTree = true).assertTextEquals("$size x $size px")
    }

    @Test
    fun cacheScreenServesRepeatRequestsFromCache() {
        openTab("cache")

        composeRule.onNodeWithTag("cache_fetch_cached").performClick()
        awaitText("cache_source", "Served from: network")
        var lastNetworkHit = textOf("cache_hit")

        // The library persists responses asynchronously, so a repeat request can still miss
        // until the write lands; keep asking (bounded) until the cached copy is served.
        var servedFromCache = false
        for (attempt in 1..20) {
            composeRule.onNodeWithTag("cache_fetch_cached").performClick()
            awaitTag("cache_result")
            if (textOf("cache_source") == "Served from: cache") {
                servedFromCache = true
                break
            }
            lastNetworkHit = textOf("cache_hit")
            Thread.sleep(250)
        }
        assertTrue("repeat GET with CacheEnabled was never served from cache", servedFromCache)
        assertEquals(lastNetworkHit, textOf("cache_hit"))
        val serverHitsWhileCached = serverHits()
        assertEquals("Response hit #$serverHitsWhileCached", textOf("cache_hit"))

        composeRule.onNodeWithTag("cache_fetch_network").performClick()
        awaitText("cache_source", "Served from: network")
        assertEquals(serverHitsWhileCached + 1, serverHits())
        assertEquals("Response hit #${serverHitsWhileCached + 1}", textOf("cache_hit"))
    }

    private fun serverHits(): Int = textOf("cache_server_hits").substringAfterLast(' ').toInt()

    @Test
    fun interceptorScreenAddsHeaderAndLogsStages() {
        openTab("interceptor")
        composeRule.onNodeWithTag("interceptor_toggle").assertIsOn()
        composeRule.onNodeWithTag("interceptor_button").performClick()
        awaitText("interceptor_status", "Status: authorized")
        awaitLogLine("interceptResponse: success")
        composeRule.onNode(hasText("interceptRequest: added ${SampleMockServer.TOKEN_HEADER}"), useUnmergedTree = true)
            .assertIsDisplayed()

        composeRule.onNodeWithTag("interceptor_toggle").performClick()
        composeRule.onNodeWithTag("interceptor_toggle").assertIsOff()
        composeRule.onNodeWithTag("interceptor_button").performClick()
        awaitTag("interceptor_error")
        composeRule.onNodeWithTag("interceptor_error", useUnmergedTree = true)
            .assert(hasText("HTTP 401", substring = true))
        awaitLogLine("interceptError: HTTP 401")
        composeRule.onNode(hasText("interceptRequest: no token"), useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag("interceptor_toggle").performClick()
    }
}
