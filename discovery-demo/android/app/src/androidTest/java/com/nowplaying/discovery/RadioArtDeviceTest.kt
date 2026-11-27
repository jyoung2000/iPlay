package com.nowplaying.discovery

import android.graphics.BitmapFactory
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** RADIO-ART-CAR-001: a station row carries its icon as embedded PNG bytes, never a URI for the car to fetch. */
@RunWith(AndroidJUnit4::class)
class RadioArtDeviceTest {
  private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun station(image: String) = RadioCatalog.normalize(JSONObject()
    .put("stationuuid", "art-test-1").put("name", "Art Test FM")
    .put("url_resolved", "https://stream.example/live.mp3").put("favicon", image))!!

  private fun assertBundledIcon(md: MediaMetadata) {
    assertNull("no URI for the car to fetch", md.artworkUri)
    assertEquals(MediaMetadata.PICTURE_TYPE_FRONT_COVER, md.artworkDataType)
    assertArrayEquals(RadioArt.bytes(ctx), md.artworkData)
  }

  @Test fun aStationWithARemoteFaviconGetsTheBundledIconOnItsRow() {
    assertBundledIcon(RadioCatalog.item(ctx, station("https://favicon.example/logo.png")).mediaMetadata)
  }

  @Test fun aStationWithNoFaviconGetsTheSameBundledIcon() {
    assertBundledIcon(RadioCatalog.item(ctx, station("")).mediaMetadata)
  }

  @Test fun theEmbeddedIconDecodesToA128PixelPng() {
    val bytes = RadioArt.bytes(ctx)
    assertTrue("not a PNG", bytes.size > 8 && bytes[1] == 'P'.code.toByte())
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    assertNotNull("bitmap decode failed", bmp)
    assertEquals(128, bmp!!.width); assertEquals(128, bmp.height)
  }
}
