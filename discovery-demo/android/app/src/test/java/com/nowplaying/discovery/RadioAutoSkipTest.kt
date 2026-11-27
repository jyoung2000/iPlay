package com.nowplaying.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioAutoSkipTest {
  @Test fun anImmediate404CanAdvanceToAnotherStation() {
    assertTrue(RadioAutoSkip.shouldAdvance(IllegalStateException("Station answered HTTP 404"), 0, 0))
  }

  @Test fun itDoesNotSkipAfterAnyAudioWasDecoded() {
    assertFalse(RadioAutoSkip.shouldAdvance(IllegalStateException("Station answered HTTP 404"), 1, 0))
  }

  @Test fun itDoesNotSkipNetworkOrDecoderErrors() {
    assertFalse(RadioAutoSkip.shouldAdvance(IllegalStateException("Station answered HTTP 503"), 0, 0))
    assertFalse(RadioAutoSkip.shouldAdvance(IllegalStateException("Unknown host"), 0, 0))
  }

  @Test fun itStopsAfterThreeAutomaticAdvances() {
    assertTrue(RadioAutoSkip.shouldAdvance(IllegalStateException("Station answered HTTP 404"), 0, 2))
    assertFalse(RadioAutoSkip.shouldAdvance(IllegalStateException("Station answered HTTP 404"), 0, 3))
  }
}
