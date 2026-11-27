package com.nowplaying.discovery

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** CAR-ICON-ART-001: every car icon used as artwork is an embedded 128 px PNG with visible pixels. */
@RunWith(AndroidJUnit4::class)
class CarArtDeviceTest {
  private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

  private val icons = listOf(
    R.drawable.car_dislike, R.drawable.car_star_add, R.drawable.car_on_phone,
    R.drawable.car_type_all, R.drawable.car_type_artist, R.drawable.car_type_singles,
    R.drawable.car_type_albums, R.drawable.car_type_features, R.drawable.car_type_lyrics,
    R.drawable.car_type_identify, R.drawable.car_radio, R.drawable.car_filter, R.drawable.car_search,
    R.drawable.car_place, R.drawable.car_language, R.drawable.car_alphabet, R.drawable.car_numbers, R.drawable.car_genre,
    R.drawable.car_playlist, R.drawable.car_queue, R.drawable.car_equalizer, R.drawable.car_settings, R.drawable.car_sparkles,
    R.drawable.car_info, R.drawable.car_more,
  )

  @Test fun everyCarIconIsAPng128WithVisiblePixels() {
    for (id in icons) {
      val bytes = CarArt.bytes(ctx, id)
      assertTrue("resource $id is not a PNG", bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte())
      val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      assertNotNull("resource $id did not decode", bmp)
      assertEquals(128, bmp!!.width); assertEquals(128, bmp.height)
      val opaque = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        .count { (it ushr 24) != 0 }
      assertTrue("resource $id rendered blank", opaque > 0)
    }
  }

  @Test fun theRenderIsCachedPerIcon() {
    assertSame(CarArt.bytes(ctx, R.drawable.car_star_add), CarArt.bytes(ctx, R.drawable.car_star_add))
  }
}
