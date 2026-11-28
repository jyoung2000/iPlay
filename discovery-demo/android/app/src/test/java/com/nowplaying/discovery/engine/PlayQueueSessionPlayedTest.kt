package com.nowplaying.discovery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * SESSION-PLAYED-001 (owner, 2026-11-28: Next at the end of a shuffled round opens a new round): a song that has
 * played this session must not come back while another queue song has never played, including songs added mid-round.
 * The property oracle is ShuffleNoReplayPropertyTest; these pin the concrete cases it found.
 */
class PlayQueueSessionPlayedTest {
  private fun queue(seed: Int) = PlayQueue<String>(
    keyOf = { it },
    isPlayable = { true },
    random = Random(seed),
  ).apply { shuffle = true }

  @Test fun aSongAddedMidRoundPlaysBeforeAnyRepeat() {
    for (seed in 1..60) {
      val q = queue(seed)
      var items = listOf("a", "b", "c")
      q.set(items, 0)
      val played = mutableListOf(q.current!!)
      q.advance(pressed = true)?.let { played += items[it] }
      items = items + "t"
      q.set(items, items.indexOf(q.current!!))
      var presses = 0
      while ("t" !in played) {
        assertTrue("seed $seed: t never came up, played $played", presses++ < 10)
        val i = q.advance(pressed = true)
        assertNotNull("seed $seed: Next found nothing with t unplayed", i)
        val k = items[i!!]
        assertTrue("seed $seed: replayed $k before t, played $played", k !in played)
        played += k
      }
    }
  }

  @Test fun aSongAddedAfterAFullRoundPlaysBeforeAnyRepeat() {
    for (seed in 1..60) {
      val q = queue(seed)
      var items = listOf("a", "b")
      q.set(items, 0)
      q.advance(pressed = true) // b; the round is now used up
      q.advance(pressed = true) // Next opens a new round: a repeat is allowed here, every song has played
      items = items + "t"
      q.set(items, items.indexOf(q.current!!))
      val next = q.advance(pressed = true)
      assertNotNull("seed $seed: Next found nothing", next)
      assertEquals("seed $seed: the new song plays before a repeat", "t", items[next!!])
    }
  }

  @Test fun songsPlayedInEarlierRoundsStayPlayedAfterARestart() {
    for (seed in 1..60) {
      val q = queue(seed)
      var items = listOf("a", "b")
      q.set(items, 0)
      q.advance(pressed = true) // b; the round is used up
      q.advance(pressed = true) // Next opens a new round: a and b have both played
      val saved = q.control()
      assertTrue("seed $seed: the save names every song played, got ${saved.played}", saved.played.containsAll(items))
      // The process dies; a new queue is restored from the save, then a song is added.
      val back = queue(seed + 1000)
      items = items + "t"
      back.restore(items, saved)
      val next = back.advance(pressed = true)
      assertNotNull("seed $seed: Next found nothing", next)
      assertEquals("seed $seed: the new song plays before a repeat after a restart", "t", items[next!!])
    }
  }

  @Test fun anOlderSaveWithoutAPlayedListStillRestores() {
    val q = queue(1)
    q.restore(listOf("a", "b", "c"), PlayQueue.Control(0, PlayQueue.Repeat.OFF, true, listOf("a", "b", "c"), listOf("a", "b", "c"), listOf("a")))
    assertEquals("a", q.current)
    assertEquals(listOf("a"), q.control().played)
  }

  @Test fun aNewSessionForgetsEarlierPlaysButTheCurrentSongCountsAsPlayed() {
    for (seed in 1..60) {
      val q = queue(seed)
      q.set(listOf("a", "b", "c"), 0)
      q.advance(pressed = true)
      q.advance(pressed = true)
      val before = q.current!!
      q.newSession()
      assertEquals("seed $seed: the song now current is the only one played this session", listOf(before), q.control().played)
      assertEquals("seed $seed: and the only one heard this round", listOf(before), q.control().heard)
      assertEquals("seed $seed: the current song does not change", before, q.current)
    }
  }

  @Test fun automaticAdvanceStopsAtTheEndOfAShuffledRoundButNextOpensTheNextOne() {
    for (seed in 1..60) {
      val q = queue(seed)
      q.set(listOf("a", "b", "c"), 0)
      repeat(2) { assertNotNull("seed $seed: a song of the round", q.advance(pressed = false)) }
      assertNull("seed $seed: automatic advance waits at the end of the round", q.advance(pressed = false))
      assertNotNull("seed $seed: Next opens the next round", q.advance(pressed = true))
    }
  }
}
