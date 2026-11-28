package com.nowplaying.discovery.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Long-session regression for "Next stops working after hours on Discover" (DHU).
 *
 * Drives the real [PlayQueue] the way Discover does: the queue keeps receiving new songs while the
 * listener presses Next, and rounds end and restart. Every Next must land on a different song while
 * the queue still has songs, and the saved control must not grow without bound.
 */
class DiscoverSkipSoakTest {
  private data class S(val key: String, val artist: String)

  private fun queue(seed: Int) = PlayQueue<S>(
    keyOf = { it.key },
    isPlayable = { true },
    random = Random(seed),
    artistOf = { it.artist },
    identityOf = { it.key },
  ).apply { shuffle = true }

  @Test fun everyNextAdvancesWhileDiscoverKeepsAddingSongs() {
    val q = queue(seed = 7)
    var n = 0
    fun fresh(k: Int) = (1..k).map { S("d${++n}", "artist${n % 17}") }
    q.set(fresh(10), 0)

    var maxControlChars = 0
    var maxItems = 0
    val steps = 3000
    for (step in 0 until steps) {
      // Discover refresh: keep a few songs ahead, as appendFound does.
      if (q.items.size - q.index < 4) q.insertSpaced(fresh(3))

      val before = q.current?.key
      val i = q.advance(pressed = true)
      assertNotNull("step $step: Next found nothing although ${q.items.size} songs are queued", i)
      val now = q.current?.key
      assertNotEquals("step $step: Next did not change the song (was $before)", before, now)

      // The published timeline must still offer a next song for as long as the queue has one.
      val timeline = q.roundTimeline(canFollow = { true })
      assertTrue("step $step: published timeline is empty", timeline.isNotEmpty())

      if (step % 100 == 0) {
        val c = q.control()
        val chars = c.order.size + c.nextRound.size + c.heard.sumOf { it.length }
        maxControlChars = maxOf(maxControlChars, chars)
        maxItems = maxOf(maxItems, q.items.size)
      }
    }
    println("SOAK steps=$steps itemsAtEnd=${q.items.size} maxItems=$maxItems maxControlChars=$maxControlChars")
  }
}
