package com.nowplaying.discovery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * SHUFFLE-STAY-001 (owner, 2026-11-24, restated 2026-11-28: "the app is replaying songs that have
 * already been played once when other songs in the queue haven't been played at all").
 *
 * The rule is a single invariant, so it is tested as one over randomised sequences of everything
 * that really happens to the queue while a car plays it: automatic advances, library pushes, mood
 * re-ranks, top-ups that append found songs, songs taken out with X, and fresh copies of the same
 * songs as their files land. A song may not play twice while another song of the queue has never
 * played, whatever order those arrive in.
 *
 * These run on the JVM against the real queue, with no page attached, which is the Android Auto
 * case: [com.nowplaying.discovery.MainActivity.live] is false while the phone app is paused, so the
 * native queue alone decides what follows.
 */
class ShuffleNoReplayPropertyTest {
  private data class S(val key: String, val heardOnRecord: Boolean = false, val artist: String = "a")

  private fun queue(seed: Int, playsOnce: Boolean) =
    PlayQueue<S>(
      keyOf = { it.key },
      isPlayable = { true },
      random = Random(seed),
      heardBefore = { it.heardOnRecord },
      artistOf = { it.artist },
      identityOf = { it.key },
    ).apply { shuffle = true; this.playsOnce = playsOnce }

  /** Every song that has actually been made current, which is what "already played" means. */
  private class Played {
    val keys = mutableListOf<String>()
    fun add(k: String?) { if (k != null && k !in keys) keys += k }
  }

  /**
   * The invariant. [played] holds every song already made current; a song may only become current
   * again once every other playable song in the list has had its turn.
   */
  private fun check(where: String, items: List<S>, played: Played, landed: String?) {
    if (landed == null) return
    val unplayed = items.map { it.key }.filter { it !in played.keys }
    if (landed in played.keys) {
      assertTrue(
        "$where: replayed $landed while never played: $unplayed (played: ${played.keys})",
        unplayed.isEmpty(),
      )
    }
  }

  @Test fun aTopUpThatAppendsFoundSongsNeverReopensSongsAlreadyPlayed() {
    for (seed in 1..60) {
      val start = (1..4).map { S("s$it") }
      val q = queue(seed, playsOnce = false)
      q.set(start, 0)
      val played = Played()
      played.add(q.current?.key)
      var items = start
      // Play two, then the service finds more for the mood and appends them, as a dry queue does.
      repeat(2) {
        val i = q.advance(pressed = true)
        check("seed $seed advance", items, played, i?.let { items[it].key })
        played.add(i?.let { items[it].key })
      }
      items = items + (5..7).map { S("f$it") }
      q.set(items, items.indexOfFirst { it.key == q.current?.key }.coerceAtLeast(0))
      // Everything still unplayed must get its turn before anything comes back.
      repeat(items.size + 2) {
        val i = q.advance(pressed = true) ?: return@repeat
        check("seed $seed after top-up", items, played, items[i].key)
        played.add(items[i].key)
      }
      assertEquals(
        "seed $seed: every song of the topped-up queue plays once",
        items.map { it.key }.toSet(),
        played.keys.toSet(),
      )
    }
  }

  @Test fun songsOnRecordAsHeardDoNotReplayWhileTheQueueHoldsUnplayedSongs() {
    for (seed in 1..60) {
      // The page marks songs heard in earlier sessions; the car keeps playing the same queue.
      val items = listOf(S("old1", heardOnRecord = true), S("old2", heardOnRecord = true), S("new1"), S("new2"))
      val q = queue(seed, playsOnce = false)
      q.set(items, 2)
      val played = Played()
      played.add(q.current?.key)
      repeat(items.size + 3) {
        val i = q.advance(pressed = true) ?: return@repeat
        check("seed $seed record", items, played, items[i].key)
        played.add(items[i].key)
      }
    }
  }

  @Test fun aMoodRerankOrLibraryPushMidRoundDoesNotReopenPlayedSongs() {
    for (seed in 1..60) {
      val items = (1..6).map { S("s$it") }
      val q = queue(seed, playsOnce = false)
      q.set(items, 0)
      val played = Played()
      played.add(q.current?.key)
      repeat(3) {
        val i = q.advance(pressed = true)
        check("seed $seed pre-push", items, played, i?.let { items[it].key })
        played.add(i?.let { items[it].key })
      }
      // A mood re-rank pushes the same songs in a different order, as the page does while driving.
      val reordered = items.shuffled(Random(seed * 31))
      q.set(reordered, reordered.indexOfFirst { it.key == q.current?.key }.coerceAtLeast(0))
      repeat(items.size + 2) {
        val i = q.advance(pressed = true) ?: return@repeat
        check("seed $seed post-push", reordered, played, reordered[i].key)
        played.add(reordered[i].key)
      }
      assertEquals("seed $seed: each song once across the re-rank", items.map { it.key }.toSet(), played.keys.toSet())
    }
  }

  /** The randomised sweep: advances mixed with the pushes, top-ups and removals a drive produces. */
  @Test fun noReplayWhileAnyQueueSongHasNeverPlayed() {
    for (seed in 1..200) {
      val rnd = Random(seed)
      val playsOnce = seed % 2 == 0
      var items = (1..5).map { S("s$it", heardOnRecord = seed % 5 == 0 && it == 1) }
      val q = queue(seed, playsOnce)
      q.set(items, 0)
      val played = Played()
      played.add(q.current?.key)
      var made = 0
      repeat(40) { step ->
        when (rnd.nextInt(10)) {
          0, 1, 2, 3, 4, 5 -> {
            val i = q.advance(pressed = rnd.nextBoolean())
            check("seed $seed step $step advance", items, played, i?.let { items[it].key })
            played.add(i?.let { items[it].key })
          }
          6 -> { // a top-up appends found songs
            val add = (1..(1 + rnd.nextInt(3))).map { S("t${seed}_${made++}") }
            items = items + add
            q.set(items, items.indexOfFirst { it.key == q.current?.key }.coerceAtLeast(0))
          }
          7 -> { // fresh copies of the same songs (files landing)
            items = items.map { it.copy() }
            q.refresh(items)
          }
          8 -> { // the page pushes the same list in a new order
            items = items.shuffled(rnd)
            q.set(items, items.indexOfFirst { it.key == q.current?.key }.coerceAtLeast(0))
          }
          else -> { // X: the song playing leaves the queue
            val cur = q.current?.key
            if (items.size > 2 && cur != null) {
              val landed = q.leave(cur)
              items = items.filter { it.key != cur }
              check("seed $seed step $step leave", items, played, landed?.let { q.current?.key })
              played.add(q.current?.key)
              q.set(items, items.indexOfFirst { it.key == q.current?.key }.coerceAtLeast(0))
            }
          }
        }
      }
    }
  }

  /**
   * SHUFFLE-STAY-001: a press with unplayed songs left must find one. A car Next that answers
   * nothing while the queue still holds songs never played is the "skip stops working" report.
   */
  @Test fun nextAlwaysFindsAnUnplayedSongWhileOneIsLeft() {
    for (seed in 1..60) {
      val items = (1..5).map { S("s$it") }
      val q = queue(seed, playsOnce = false)
      q.set(items, 0)
      val played = mutableSetOf(q.current!!.key)
      while (played.size < items.size) {
        val i = q.advance(pressed = true)
        assertNotNull("seed $seed: Next found nothing with ${items.size - played.size} never played", i)
        played += items[i!!].key
      }
    }
  }
}
