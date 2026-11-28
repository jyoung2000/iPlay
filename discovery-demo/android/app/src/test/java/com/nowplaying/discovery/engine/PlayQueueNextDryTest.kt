package com.nowplaying.discovery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * AA-NEXT-DRY-001 (owner, 2026-11-26): "On android auto after a while the skip/forward button stops working". [advance]
 * only takes downloaded songs, so a shuffled round whose downloaded songs had all played left Next dead even with
 * unheard songs (a queue top-up's) still downloading. [PlayQueue.advanceFetchable] answers that press with one of them.
 */
class PlayQueueNextDryTest {
  private data class S(val key: String, val ready: Boolean = true, val link: Boolean = true)
  private fun q(vararg s: S, shuffle: Boolean, seed: Int = 5) =
    PlayQueue<S>({ it.key }, { it.ready }, Random(seed)).apply { this.shuffle = shuffle; set(s.toList(), 0) }

  @Test fun shuffledNextReachesAnUnheardSongStillDownloadingOnceTheDownloadedOnesHavePlayed() {
    for (seed in 0 until 100) {
      val queue = q(S("a"), S("b"), S("t1", ready = false), S("t2", ready = false), shuffle = true, seed = seed)
      val heard = linkedSetOf(queue.current!!.key)
      while (true) { val i = queue.advance(pressed = false) ?: break; heard += queue.items[i].key }
      assertEquals("seed $seed", setOf("a", "b"), heard)
      // Before the fix this was where the car's Next stopped working.
      val i = queue.advanceFetchable { it.link }
      assertNotNull("seed $seed: Next must reach a downloading song", i)
      val first = queue.items[i!!].key
      assertTrue("seed $seed: $first", first == "t1" || first == "t2")
      val j = queue.advanceFetchable { it.link }
      assertNotNull("seed $seed: and the other one after it", j)
      assertEquals("seed $seed", setOf("t1", "t2"), setOf(first, queue.items[j!!].key))
      assertNull("seed $seed: never a song already played this round", queue.advanceFetchable { it.link })
    }
  }

  @Test fun aSongWithNoWayToFetchItIsNeverChosen() {
    val queue = q(S("a"), S("x", ready = false, link = false), shuffle = true)
    queue.advance(pressed = true)
    assertNull(queue.advanceFetchable { it.link })
    assertEquals("a", queue.current!!.key)
  }

  @Test fun inOrderNextMovesOnToTheNextSongStillDownloadingNotBack() {
    val queue = q(S("a"), S("b", ready = false), S("c", ready = false), shuffle = false)
    assertNull(queue.advance(pressed = false))
    assertEquals(1, queue.advanceFetchable { it.link })
    assertEquals(2, queue.advanceFetchable { it.link })
    assertNull("nothing ahead of the last song", queue.advanceFetchable { it.link })
  }

  @Test fun songsAppendedAfterTheRoundRanDryAreReachedWithoutANewRound() {
    val queue = q(S("a"), S("b"), shuffle = true)
    queue.advance(pressed = true)
    assertNull(queue.advance(pressed = false))
    assertNull(queue.advanceFetchable { it.link })
    queue.insertSpaced(listOf(S("n1", ready = false)))
    val i = queue.advance(pressed = false) ?: queue.advanceFetchable { it.link }
    assertEquals("n1", queue.items[i!!].key)
  }
}
