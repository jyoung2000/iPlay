package com.nowplaying.discovery

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/**
 * CAR-SKIP-002 (owner, 2026-11-28): "the skip button on android auto does not work after a while".
 *
 * Media3 decides whether a Next press even reaches this app: [androidx.media3.common.BasePlayer.seekToNext]
 * is final and drops the press unless the published timeline offers a next window. The timeline is
 * published in list order, so when the song playing is the LAST row of the queue the press is
 * dropped — while shuffle still has unplayed songs earlier in the list. That is the "after a while":
 * it starts the moment a shuffled round lands on the last row.
 *
 * These tests go through a real [MediaController], the same transport Android Auto uses, with no
 * page attached (the phone app is paused in the car, so the native queue decides).
 */
@RunWith(AndroidJUnit4::class)
class CarShuffleSkipTest {
  private val ctx: Context get() = ApplicationProvider.getApplicationContext()
  private val instr get() = InstrumentationRegistry.getInstrumentation()
  private var controller: MediaController? = null

  private fun <T> await(call: () -> ListenableFuture<T>): T {
    val out = AtomicReference<T?>()
    val ex = AtomicReference<Throwable?>()
    val latch = CountDownLatch(1)
    instr.runOnMainSync {
      val f = call()
      f.addListener({
        try { out.set(f.get()) } catch (t: Throwable) { ex.set(t) } finally { latch.countDown() }
      }, Runnable::run)
    }
    if (!latch.await(15, TimeUnit.SECONDS)) throw AssertionError("session call timed out")
    ex.get()?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return out.get() as T
  }

  private fun <T> on(read: (MediaController) -> T): T {
    val out = AtomicReference<T?>()
    instr.runOnMainSync { out.set(read(controller!!)) }
    @Suppress("UNCHECKED_CAST")
    return out.get() as T
  }

  private val links = (1..4).map { "https://music.youtube.com/watch?v=carskip2q$it" }

  private fun seed() {
    val songs = (1..4).joinToString(",") { i ->
      """{"id":"q$i","title":"Song $i","artist":"A$i","durationMs":${180000 + i},"ready":true,"source":"${links[i - 1]}"}"""
    }
    MediaHub.apply(JSONObject("""{"library":{"songs":[$songs],"playlists":[]}}"""))
  }

  /** A real finished-download WAV, keyed as the downloader keys it, so skips have real destinations. */
  private fun storeTone(link: String, hz: Double) {
    val rate = 24000
    val frames = rate * 60
    val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (f in 0 until frames) pcm.putShort((0.1 * sin(2 * PI * hz / rate * f) * 32767).toInt().toShort())
    val name = MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) } + ".wav"
    val file = File(File(ctx.filesDir, "audio-cache").apply { mkdirs() }, name)
    RandomAccessFile(file, "rw").use { out ->
      out.setLength(0)
      val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.capacity())
      out.write(h.array()); out.write(pcm.array())
    }
    ctx.getSharedPreferences("airwave-private-audio", Context.MODE_PRIVATE).edit().putString(link, name).commit()
  }

  @Before fun setUp() {
    links.forEachIndexed { i, link -> storeTone(link, 300.0 + i * 90) }
    seed()
    val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
    controller = await { MediaController.Builder(ctx, token).buildAsync() }
    instr.runOnMainSync {
      controller!!.stop(); controller!!.clearMediaItems()
      controller!!.shuffleModeEnabled = false; controller!!.repeatMode = Player.REPEAT_MODE_OFF
    }
  }

  @After fun tearDown() {
    instr.runOnMainSync {
      controller?.shuffleModeEnabled = false; controller?.repeatMode = Player.REPEAT_MODE_OFF
      controller?.release()
    }
    controller = null
    val prefs = ctx.getSharedPreferences("airwave-private-audio", Context.MODE_PRIVATE)
    links.forEach { link ->
      prefs.getString(link, null)?.let { File(File(ctx.filesDir, "audio-cache"), it).delete() }
      prefs.edit().remove(link).commit()
    }
  }

  private fun carTaps(mediaId: String) {
    instr.runOnMainSync {
      controller!!.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
      controller!!.prepare()
    }
    instr.waitForIdleSync()
  }

  /** The current song once it has held still for a second after any change. */
  private fun settled(changedFrom: String? = null): String {
    val until = System.currentTimeMillis() + 8000
    var last = on { it.currentMediaItem?.mediaId ?: "none" }
    var since = System.currentTimeMillis()
    while (System.currentTimeMillis() < until) {
      Thread.sleep(100)
      val now = on { it.currentMediaItem?.mediaId ?: "none" }
      if (now != last) { last = now; since = System.currentTimeMillis() }
      if ((changedFrom == null || last != changedFrom) && System.currentTimeMillis() - since >= 1000) return last
    }
    return last
  }

  /** What Android Auto actually sends when the driver taps Next: Player.seekToNext(). */
  private fun carPressesNext() {
    instr.runOnMainSync { controller!!.seekToNext() }
    instr.waitForIdleSync()
  }

  /**
   * CAR-SKIP-002. The song playing is the last row of the queue and shuffle has three unplayed songs
   * earlier in the list. A Next press must reach one of them.
   */
  @Test fun nextWorksWhenTheShuffledRoundLandsOnTheLastRowOfTheQueue() {
    carTaps("q4")
    instr.runOnMainSync { controller!!.pause(); controller!!.shuffleModeEnabled = true }
    assertEquals("the tap should leave the whole queue to skip through", 4, on { it.mediaItemCount })
    assertEquals("q4", settled())
    // The published order is the round's play order, so q4 need not sit at list index 3 here; what
    // matters to Android Auto is that the published row for the playing song is q4 itself.
    assertEquals("the published row for the playing song is q4", "q4", on {
      it.getMediaItemAt(it.currentMediaItemIndex).mediaId.substringBefore('#')
    })
    assertTrue(
      "Android Auto drops the press unless a next window is offered, and three songs are unplayed",
      on { it.hasNextMediaItem() },
    )
    carPressesNext()
    val landed = settled(changedFrom = "q4")
    assertTrue("Next did nothing from the last row of a shuffled queue (landed on $landed)", landed != "q4")
    assertTrue("Next must land on an unplayed song, not $landed", landed in setOf("q1", "q2", "q3"))
  }

  /**
   * CAR-SKIP-002: the whole shuffled round over the car transport, pressing Next from wherever the
   * round puts the song. Every song must be reachable by pressing Next, none twice.
   */
  @Test fun everySongOfAShuffledQueueIsReachableByPressingNext() {
    carTaps("q4")
    instr.runOnMainSync { controller!!.pause(); controller!!.shuffleModeEnabled = true }
    val seen = mutableListOf(settled())
    repeat(3) {
      val before = seen.last()
      carPressesNext()
      val now = settled(changedFrom = before)
      assertTrue("Next stopped working at $before after reaching $seen", now != before)
      assertTrue("shuffle replayed $now; already played: $seen", now !in seen)
      seen += now
    }
    assertEquals("each song once per round", setOf("q1", "q2", "q3", "q4"), seen.toSet())
  }
}
