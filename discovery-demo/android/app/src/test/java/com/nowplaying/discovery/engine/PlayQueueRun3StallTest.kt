package com.nowplaying.discovery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

// Regression for the run3 car stall: 300 ready songs, shuffle on, repeat off, Next pressed from song 81
// (list index 80). Automatic advance stops at the end of a shuffled round (SHUFFLE-STAY-001). An explicit Next press
// at that point opens a new round (owner reversal, 2026-11-28). Each round must reach every song it is meant to.
class PlayQueueRun3StallTest {
  private data class S(val key: String, val artist: String)

  private fun items() = (1..300).map { S("dhu300_$it", "Artist ${it % 9}") }

  private fun queue(seed: Int) = PlayQueue<S>(
    keyOf = { it.key },
    isPlayable = { true },
    random = Random(seed),
    artistOf = { it.artist },
    identityOf = { it.key },
  )

  // One round: the current song plus every automatic advance until the round is used up.
  private fun round(q: PlayQueue<S>): List<String> {
    val seen = mutableListOf(q.current!!.key)
    var steps = 0
    while (steps < 1000) {
      val i = q.advance(pressed = false) ?: break
      seen += q.items[i].key
      steps++
    }
    return seen
  }

  @Test fun shuffleTurnedOnAfterSetReachesEverySongFromSong81() {
    for (seed in 1..50) {
      val q = queue(seed)
      q.set(items(), 80)
      q.shuffle = true
      val seen = round(q)
      assertEquals("seed $seed: distinct songs in the round", 300, seen.toSet().size)
      assertEquals("seed $seed: moves in the round", 300, seen.size)
    }
  }

  @Test fun shuffleOnBeforeSetReachesEverySongFromSong81() {
    for (seed in 1..50) {
      val q = queue(seed)
      q.shuffle = true
      q.set(items(), 80)
      val seen = round(q)
      assertEquals("seed $seed: distinct songs in the round", 300, seen.toSet().size)
      assertEquals("seed $seed: moves in the round", 300, seen.size)
    }
  }

  private val first80 = (1..80).map { "dhu300_$it" }.toSet()

  // Device evidence: the dry-line flags for songs 1-117 read h=false, so heardBefore alone is not the device state.
  // This variant checks the mechanism on its own.
  @Test fun heardBeforeFirst80RoundStopsAt220ThenNextOpensAFullRound() {
    for (seed in 1..50) {
      val q = PlayQueue<S>(keyOf = { it.key }, isPlayable = { true }, random = Random(seed),
        heardBefore = { it.key in first80 }, artistOf = { it.artist }, identityOf = { it.key })
      q.set(items(), 80)
      q.shuffle = true
      val first = round(q)
      assertEquals("seed $seed: distinct songs in the first round", 220, first.toSet().size)
      assertEquals("seed $seed: held songs wait for the next round", emptyList<String>(), first.filter { it in first80 })
      assertNull("seed $seed: automatic advance stops at the end of the round", q.advance(pressed = false))
      assertNotNull("seed $seed: Next opens a new round", q.advance(pressed = true))
      val second = round(q)
      assertEquals("seed $seed: the new round reaches every song", 300, second.toSet().size)
    }
  }

  // A restored queue whose round already counts songs 1-80 as heard (what a saved Control carries).
  @Test fun restoredControlWithFirst80HeardRoundStopsAt220() {
    val keys = items().map { it.key }
    for (seed in 1..50) {
      val q = queue(seed)
      q.restore(items(), PlayQueue.Control(
        index = 80, repeat = PlayQueue.Repeat.OFF, shuffle = true,
        order = keys, nextRound = keys, heard = first80.toList() + "dhu300_81",
      ))
      val seen = round(q)
      assertEquals("seed $seed: distinct songs after restore", 220, seen.toSet().size)
    }
  }

  // Control: the same restore with nothing heard reaches all 300.
  @Test fun restoredControlWithNothingHeardReachesEverySong() {
    val keys = items().map { it.key }
    for (seed in 1..50) {
      val q = queue(seed)
      q.restore(items(), PlayQueue.Control(
        index = 80, repeat = PlayQueue.Repeat.OFF, shuffle = true,
        order = keys, nextRound = keys, heard = emptyList(),
      ))
      val seen = round(q)
      assertEquals("seed $seed: distinct songs after restore", 300, seen.toSet().size)
    }
  }

  // Save mid-round, restore into a new queue, keep walking: the round must still reach every song.
  @Test fun saveAndRestoreMidRoundStillReachesEverySong() {
    for (seed in 1..50) {
      val a = queue(seed)
      a.set(items(), 80)
      a.shuffle = true
      val first = mutableListOf(a.current!!.key)
      repeat(100) { first += a.items[a.advance(pressed = true)!!].key }
      val b = queue(seed + 1000)
      b.restore(items(), a.control())
      val rest = round(b).drop(1)
      assertEquals("seed $seed: distinct songs across save/restore", 300, (first + rest).toSet().size)
    }
  }

  // Real-setup model: run2 walked shuffle-off and wrapped, then played 1..81 in order. Run3 turned Shuffle on at
  // song 81 without a new round, so the round's heard set still holds 1..81.
  @Test fun shuffleOnAfterSequentialPassRoundStopsAt220ThenNextOpensANewRound() {
    for (seed in 1..50) {
      val q = queue(seed)
      q.set(items(), 0)
      q.shuffle = false
      repeat(80) { q.advance(pressed = true) }
      assertEquals("seed $seed: at song 81", "dhu300_81", q.current!!.key)
      q.shuffle = true
      val seen = round(q)
      assertEquals("seed $seed: distinct songs incl. 81 in the round", 220, seen.toSet().size)
      assertNotNull("seed $seed: Next opens a new round", q.advance(pressed = true))
    }
  }

  @Test fun pageOrderedQueueFromSong81ReachesEverySong() {
    for (seed in 1..50) {
      val q = queue(seed)
      q.playsOnce = true
      q.shuffle = true
      q.set(items(), 80)
      val seen = round(q)
      assertEquals("seed $seed: distinct songs in the round", 300, seen.toSet().size)
    }
  }
}
