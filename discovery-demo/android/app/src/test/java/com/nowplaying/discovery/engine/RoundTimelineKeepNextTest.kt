package com.nowplaying.discovery.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * CAR-SKIP-003 (owner, 2026-11-28): "ensure the skip button always works on android auto dhu, after hours of listening
 * on discover the skip button no longer works".
 *
 * Android Auto is a Media3 controller: it decides for itself whether a Next exists, from the published timeline alone,
 * and `BasePlayer.seekToNext` drops the press before this app ever sees it. CAR-SKIP-002 made the published order the
 * round's play order, which fixed a round whose song sat on the last row. It did not cover the end of the round: once
 * every song of a play-once queue has been played, nothing is ahead, so the car's Next went dead for good — exactly
 * what hours of listening on Discover reaches.
 *
 * The app CAN still answer that press (AA-NEXT-DRY-001: it finds more songs for the listening mood and plays the first
 * one), so the timeline must keep one window ahead whenever a press is answerable. [PlayQueue.roundTimeline]'s
 * `keepNext` does that without touching what plays automatically.
 */
class RoundTimelineKeepNextTest {
  private data class S(val key: String, val ready: Boolean = true)

  /** A Discover queue: shuffled, and each song plays once per round. */
  private fun q(vararg s: S, shuffle: Boolean = true, seed: Int = 7) =
    PlayQueue<S>({ it.key }, { it.ready }, Random(seed)).apply { this.shuffle = shuffle; playsOnce = true; set(s.toList(), 0) }

  /**
   * Press Next until the round has nothing left, as a listener on Discover does, and return the order played.
   * Bounded: a mode that wraps for ever (repeat all, and the list's own order) must fail the test, not hang it.
   */
  private fun playOut(queue: PlayQueue<S>): List<String> {
    val order = mutableListOf(queue.current!!.key)
    repeat(queue.items.size * 3) {
      val i = queue.advance(pressed = false) ?: return order
      order += queue.items[i].key
    }
    throw AssertionError("the round never ran out: $order")
  }

  @Test fun aFullyPlayedRoundStillOffersTheCarAWindowAhead() {
    for (seed in 0 until 50) {
      val queue = q(S("a"), S("b"), S("c"), S("d"), shuffle = true, seed = seed)
      playOut(queue)
      // Nothing follows automatically: that is correct, and it is what starts the mood top-up.
      assertEquals("seed $seed", null, queue.advance(pressed = false))
      val stranded = queue.roundTimeline(keepNext = false)
      assertEquals("seed $seed: the round really is finished", stranded.size - 1, stranded.indexOf(queue.index))
      // Before the fix the car saw this and dropped every later Next press.
      val shown = queue.roundTimeline(keepNext = true)
      val at = shown.indexOf(queue.index)
      assertTrue("seed $seed: the car must still see a song after the one playing", at >= 0 && at < shown.size - 1)
      assertEquals("seed $seed: every song stays published exactly once", queue.items.indices.toSet(), shown.toSet())
      assertEquals("seed $seed", queue.items.size, shown.size)
      assertNotEquals("seed $seed: the window ahead is never the song playing", queue.index, shown[at + 1])
    }
  }

  @Test fun theWindowAheadIsTheSongPlayedLongestAgo() {
    for (seed in 0 until 20) {
      val queue = q(S("a"), S("b"), S("c"), S("d"), shuffle = true, seed = seed)
      val played = playOut(queue)
      val shown = queue.roundTimeline(keepNext = true)
      val ahead = queue.items[shown[shown.indexOf(queue.index) + 1]].key
      // Repeat off owes no next round, so there is no "song the next round opens with" to show. The window is the
      // song this round played longest ago: the one a press is least likely to feel like an immediate replay of.
      assertEquals("seed $seed: the window is the song played longest ago", played.first(), ahead)
      assertNotEquals("seed $seed", played.last(), ahead)
    }
  }

  @Test fun underRepeatAllTheWindowIsTheSongTheNextRoundOpensWith() {
    val queue = q(S("a"), S("b"), S("c"), S("d"), shuffle = true, seed = 3)
    queue.repeat = PlayQueue.Repeat.ALL
    // Repeat all opens a fresh round rather than running out, so every press is answerable from the queue itself.
    // The published view still empties at the round boundary (this round has nothing left ahead), so the window is
    // needed there too — and under repeat all it is honestly the song the next round opens with.
    repeat(queue.items.size * 2) { step ->
      val shown = queue.roundTimeline(keepNext = true)
      val at = shown.indexOf(queue.index)
      assertTrue("step $step: the car must always see a song after the one playing", at < shown.size - 1)
      assertEquals("step $step: the window is the song that really follows", queue.peekAfterEnd(), shown[at + 1])
      assertTrue("step $step: repeat all should always have a song to follow", queue.advance(pressed = true) != null)
    }
  }

  @Test fun keepNextNeverChangesARoundThatStillHasSongsToPlay() {
    for (seed in 0 until 50) {
      val queue = q(S("a"), S("b"), S("c"), S("d"), S("e"), shuffle = true, seed = seed)
      queue.advance(pressed = true)
      assertEquals("seed $seed", queue.roundTimeline(keepNext = false), queue.roundTimeline(keepNext = true))
    }
  }

  @Test fun theListsOwnOrderNeverRunsOutSoTheCarIsNeverStrandedThere() {
    // Shuffle off, the queue wraps to the top of the list instead of ending a round, so a next song always exists
    // and [EnginePlayer.shownOrder] publishes the list exactly as the listener wrote it. Nothing to promise here.
    val queue = q(S("a"), S("b"), S("c"), shuffle = false, seed = 1)
    repeat(queue.items.size * 2) {
      assertTrue("the list's own order should always have a song to follow", queue.advance(pressed = true) != null)
    }
  }

  @Test fun aOneSongQueueCannotOfferAWindowAndMustNotInventOne() {
    val queue = q(S("only"), shuffle = true, seed = 1)
    playOut(queue)
    assertEquals(listOf(0), queue.roundTimeline(keepNext = true))
  }

  @Test fun songsThatCannotBePlayedAtAllAreNeverTheWindowAhead() {
    // Only "a" is on the phone; the rest have no file and no way to fetch one.
    val queue = q(S("a"), S("gone1", ready = false), S("gone2", ready = false), shuffle = true, seed = 2)
    playOut(queue)
    val shown = queue.roundTimeline(keepNext = true)
    assertEquals("nothing playable is left, so no window may be promised", shown.indexOf(queue.index), shown.size - 1)
  }

  @Test fun aSongStillDownloadingCanBeTheWindowAhead() {
    val queue = q(S("a"), S("fetching", ready = false), shuffle = true, seed = 2)
    playOut(queue)
    // canFollow is the engine's "streamable while it still downloads" test (AA-NEXT-DRY-001).
    val shown = queue.roundTimeline(canFollow = { it.key == "fetching" }, keepNext = true)
    val at = shown.indexOf(queue.index)
    assertTrue("a song that can still be streamed is a real window", at < shown.size - 1)
    assertEquals("fetching", queue.items[shown[at + 1]].key)
  }
}
