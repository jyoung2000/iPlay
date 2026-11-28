package com.nowplaying.discovery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * SHUFFLE-STAY-001 / CAR-SKIP-002 regression: a shuffled queue that is saved and put back (the
 * service restarts, or the car reconnects) must not replay songs already played in that round while
 * other songs of the queue are still unplayed.
 *
 * The saved control carries the round's order, the next round and the heard set. This test drives
 * the real save/restore pair: play part of a round, snapshot with [PlayQueue.control], restore into a
 * fresh queue with a different random seed, then advance until the round is exhausted.
 */
class ShuffleRestoreNoReplayTest {
  private data class S(val key: String, val artist: String = "a")

  private fun queue(seed: Int) = PlayQueue<S>(
    keyOf = { it.key },
    isPlayable = { true },
    random = Random(seed),
    artistOf = { it.artist },
    identityOf = { it.key },
  ).apply { shuffle = true }

  private fun items(n: Int) = (1..n).map { S("s$it", artist = "a${it % 3}") }

  @Test fun restoredRoundDoesNotReplaySongsAlreadyPlayed() {
    for (seed in 1..40) {
      for (playedBeforeSave in 0..5) {
        val list = items(8)
        val a = queue(seed)
        a.set(list, 0)
        val played = mutableListOf(a.current!!.key)
        repeat(playedBeforeSave) {
          val i = a.advance(pressed = true) ?: return@repeat
          played += list[i].key
        }
        val saved = a.control()

        // A fresh queue with a different random source, as after a process restart.
        val b = queue(seed + 1000)
        b.restore(list, saved)
        // The page-side current song is the one the saved queue was on.
        val rest = mutableListOf<String>()
        repeat(list.size * 2) {
          val i = b.advance(pressed = true) ?: return@repeat
          val key = list[i].key
          if (key in played) {
            // A replay is only legal once every song has had its turn in this round.
            val unplayed = list.map { it.key }.filter { it !in played }
            assertTrue(
              "seed $seed, $playedBeforeSave played before save: replayed $key with unplayed $unplayed",
              unplayed.isEmpty(),
            )
          }
          played += key
          rest += key
        }
        // Everything in the queue has been played once by the end.
        assertEquals(
          "seed $seed, $playedBeforeSave played before save: every song plays once",
          list.map { it.key }.toSet(),
          played.toSet(),
        )
      }
    }
  }
}
