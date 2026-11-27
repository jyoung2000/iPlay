package com.nowplaying.discovery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * CAR-ICON-ART-001: the Android Auto icons that are shown as artwork, as embedded PNG bytes.
 *
 * Android Auto's host cannot decode vector drawables, and it reads artwork in its own process, so a
 * resource URI into jPlay shows the hazard-sign placeholder. Each icon is drawn once and cached as a
 * 128 px PNG. The vectors remain the single artwork source; their embedded PNG bytes are rendered on demand and cached in memory.
 */
object CarArt {
  private const val SIZE = 128
  private val cache = ConcurrentHashMap<Int, ByteArray>()

  fun bytes(context: Context, resId: Int): ByteArray = cache.getOrPut(resId) { render(context, resId) }

  private fun render(context: Context, resId: Int): ByteArray {
    val drawable: Drawable = requireNotNull(ContextCompat.getDrawable(context, resId)) { "missing drawable $resId" }
    val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    try {
      drawable.setBounds(0, 0, SIZE, SIZE)
      drawable.draw(Canvas(bitmap))
      val out = ByteArrayOutputStream()
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG encode failed for $resId" }
      return out.toByteArray()
    } finally {
      bitmap.recycle()
    }
  }
}
