package com.prerak.mymusic

import org.junit.Assert.assertFalse
import org.junit.Test

class AdRequestTest {
    @Test
    fun allows_coreYouTubeMusicRequests() {
        assertFalse(MainActivity.isAdRequest("https://music.youtube.com/"))
        assertFalse(MainActivity.isAdRequest("https://music.youtube.com/watch?v=abc123"))
    }

    @Test
    fun doesNotThrow_onMalformedUrls() {
        // shouldInterceptRequest runs this on every request; bad input must never throw.
        listOf("", " ", "not a url", "http://[bad", "https://exa mple.com/%%%", "data:text/plain,hi")
            .forEach { MainActivity.isAdRequest(it) }
    }
}
