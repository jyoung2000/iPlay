package com.nowplaying.discovery

import android.content.Context
import android.net.Uri

/**
 * RADIO-ART-CAR-001: the artwork Android Auto gets for a radio station.
 *
 * A station's own logo is a remote favicon. Android Auto's host fetches artwork itself and cannot read
 * those, so each row drew the hazard-sign placeholder. Every station and every unidentified broadcast
 * now uses one app-owned PNG. A matched song keeps its own https cover, which the car loads.
 */
object RadioArt {
  const val DRAWABLE = "car_radio_art"

  /** The string form, so JVM tests can check it without android.net.Uri. */
  fun artString(packageName: String): String = "android.resource://$packageName/drawable/$DRAWABLE"

  fun uri(packageName: String): Uri = Uri.parse(artString(packageName))

  @Volatile private var cached: ByteArray? = null

  /**
   * The PNG bytes, embedded in each station row. Android Auto's host reads artwork in its own process,
   * so a resource URI pointing into jPlay is not something it is guaranteed to open.
   */
  fun bytes(context: Context): ByteArray = cached ?: context.resources.openRawResource(R.drawable.car_radio_art)
    .use { it.readBytes() }.also { cached = it }

  /** A cover the car can load: an https URL of sane length. Anything else is not a cover. */
  fun usableCover(url: String?): String? =
    url?.trim()?.takeIf { it.length in 9..4096 && it.lowercase().startsWith("https://") }

  /** First usable cover wins, in the order given; otherwise the bundled icon. */
  fun nowPlayingArt(packageName: String, vararg candidates: String?): String =
    candidates.firstNotNullOfOrNull { usableCover(it) } ?: artString(packageName)
}
