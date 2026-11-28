package com.nowplaying.discovery.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

// Regression for the run3 car stall: 300 ready songs, shuffle on, repeat off, Next pressed from song 81
// (list index 80). Every press must move until the round is genuinely used up, and every song must be reached.
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

  private fun walk(q: PlayQueue<S>): List<String> {
    val seen = mutableListOf(q.current!!.key)
    var steps = 0
    while (steps < 1000) {
      val i = q.advance(pressed = true) ?: break
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
      val seen = walk(q)
      assertEquals("seed $seed: distinct songs before the first null", 300, seen.toSet().size)
      assertEquals("seed $seed: moves before the first null", 300, seen.size)
    }
  }

  @Test fun shuffleOnBeforeSetReachesEverySongFromSong81() {
    for (seed in 1..50) {
      val q = queue(seed)
      q.shuffle = true
      q.set(items(), 80)
      val seen = walk(q)
      assertEquals("seed $seed: distinct songs before the first null", 300, seen.toSet().size)
      assertEquals("seed $seed: moves before the first null", 300, seen.size)
    }
  }

  private val first80 = (1..80).map { "dhu300_$it" }.toSet()

  // Device evidence: the dry-line flags for songs 1-117 read h=false (song.heard false), so heardBefore alone is
  // not the device state. This variant checks that mechanism on its own.
  @Test fun heardBeforeFirst80StallsAt220() {
    for (seed in 1..50) {
      val q = PlayQueue<S>(keyOf = { it.key }, isPlayable = { true }, random = Random(seed),
        heardBefore = { it.key in first80 }, artistOf = { it.artist }, identityOf = { it.key })
      q.set(items(), 80)
      q.shuffle = true
      val seen = walk(q)
      assertEquals("seed $seed: distinct songs", 220, seen.toSet().size)
      assertEquals("seed $seed: held songs reached", emptyList<String>(), seen.filter { it in first80 })
    }
  }

  // A restored queue whose round already counts songs 1-80 as heard (what a saved Control carries).
  @Test fun restoredControlWithFirst80HeardStallsAt220() {
    val keys = items().map { it.key }
    for (seed in 1..50) {
      val q = queue(seed)
      q.restore(items(), PlayQueue.Control(
        index = 80, repeat = PlayQueue.Repeat.OFF, shuffle = true,
        order = keys, nextRound = keys, heard = first80.toList() + "dhu300_81",
      ))
      val seen = walk(q)
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
      val seen = walk(q)
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
      val rest = walk(b).drop(1)
      assertEquals("seed $seed: distinct songs across save/restore", 300, (first + rest).toSet().size)
    }
  }

  // Real-setup model: run2 walked shuffle-off and wrapped (heard cleared at the wrap), then played 1..81 in order.
  // Run3 turned Shuffle on at song 81 without a new round, so the round's heard set still holds 1..81.
  @Test fun shuffleOnAfterSequentialPassStopsAfter220Distinct() {
    for (seed in 1..50) {
      val q = queue(seed)
      q.set(items(), 0)
      q.shuffle = false
      repeat(80) { q.advance(pressed = true) }
      assertEquals("seed $seed: at song 81", "dhu300_81", q.current!!.key)
      q.shuffle = true
      val seen = walk(q)
      assertEquals("seed $seed: distinct songs incl. 81", 220, (seen).toSet().size)
      assertEquals("seed $seed: dead after the round", null, q.advance(pressed = true))
    }
  }

  @Test fun pageOrderedQueueFromSong81ReachesEverySong() {
    for (seed in 1..50) {
      val q = queue(seed)
      q.playsOnce = true
      q.shuffle = true
      q.set(items(), 80)
      val seen = walk(q)
      assertEquals("seed $seed: distinct songs before the first null", 300, seen.toSet().size)
    }
  }
}
