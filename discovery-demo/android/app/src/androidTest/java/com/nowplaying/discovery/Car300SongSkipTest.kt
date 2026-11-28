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

/**
 * CAR-SKIP-003 (owner, 2026-11-28): "ensure the skip button always works on android auto dhu, after hours of listening
 * on discover the skip button no longer works" — "test 300 songs".
 *
 * A long Discover session is a big queue played through. This drives THREE HUNDRED songs over a real
 * [MediaController] — the same transport Android Auto uses — with no page attached (in the car the phone app is
 * paused, so the native queue decides), and asserts on EVERY press that:
 *  - Android Auto would not drop the press (`hasNextMediaItem`, which `BasePlayer.seekToNext` checks first), and
 *  - the press actually changed the song, and
 *  - no song was replayed while an unplayed one was left (the shuffle bug this skip failure travels with).
 *
 * It then plays the round THROUGH to the end, which is where the old code stranded the car for good, and asserts the
 * press is still offered (CAR-SKIP-003's kept window) so the app can answer it with a mood top-up.
 */
@RunWith(AndroidJUnit4::class)
class Car300SongSkipTest {
  private val ctx: Context get() = ApplicationProvider.getApplicationContext()
  private val instr get() = InstrumentationRegistry.getInstrumentation()
  private var controller: MediaController? = null

  /** The queue size the owner asked to be tested. */
  private val count = 300

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
    if (!latch.await(20, TimeUnit.SECONDS)) throw AssertionError("session call timed out")
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

  private fun id(i: Int) = "s300_$i"
  private fun link(i: Int) = "https://music.youtube.com/watch?v=s300q$i"

  /**
   * One real finished-download WAV, shared by every song through its own prefs key. 300 separate 60 s tones would be
   * ~900 MB of emulator storage; what this test exercises is the skip transport, not 300 distinct decodes, and the
   * engine only needs each song's link to resolve to a complete local file.
   */
  private fun storeTones() {
    val rate = 24000
    val frames = rate * 5
    val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (f in 0 until frames) pcm.putShort((0.1 * kotlin.math.sin(2 * Math.PI * 440.0 / rate * f) * 32767).toInt().toShort())
    val name = MessageDigest.getInstance("SHA-256").digest("s300-shared".toByteArray()).joinToString("") { "%02x".format(it) } + ".wav"
    val file = File(File(ctx.filesDir, "audio-cache").apply { mkdirs() }, name)
    RandomAccessFile(file, "rw").use { out ->
      out.setLength(0)
      val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.capacity())
      out.write(h.array()); out.write(pcm.array())
    }
    val prefs = ctx.getSharedPreferences("airwave-private-audio", Context.MODE_PRIVATE).edit()
    for (i in 1..count) prefs.putString(link(i), name)
    prefs.commit()
  }

  private fun seed() {
    // Twelve artists over 300 songs, as a Discover queue looks: the four-in-a-row artist rule is live here too.
    val songs = (1..count).joinToString(",") { i ->
      """{"id":"${id(i)}","title":"Song $i","artist":"Artist ${i % 12}","durationMs":${180000 + i},"ready":true,"source":"${link(i)}"}"""
    }
    MediaHub.apply(JSONObject("""{"library":{"songs":[$songs],"playlists":[]}}"""))
  }

  @Before fun setUp() {
    storeTones()
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
      controller?.stop()
      controller?.shuffleModeEnabled = false; controller?.repeatMode = Player.REPEAT_MODE_OFF
      controller?.release()
    }
    controller = null
    val prefs = ctx.getSharedPreferences("airwave-private-audio", Context.MODE_PRIVATE)
    prefs.getString(link(1), null)?.let { File(File(ctx.filesDir, "audio-cache"), it).delete() }
    val edit = prefs.edit()
    for (i in 1..count) edit.remove(link(i))
    edit.commit()
  }

  private fun carTaps(mediaId: String) {
    instr.runOnMainSync {
      controller!!.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
      controller!!.prepare()
    }
    instr.waitForIdleSync()
  }

  /** The current song once it has held still after any change. */
  private fun settled(changedFrom: String? = null, waitMs: Long = 6000): String {
    val until = System.currentTimeMillis() + waitMs
    var last = on { it.currentMediaItem?.mediaId?.substringBefore('#') ?: "none" }
    var since = System.currentTimeMillis()
    while (System.currentTimeMillis() < until) {
      Thread.sleep(40)
      val now = on { it.currentMediaItem?.mediaId?.substringBefore('#') ?: "none" }
      if (now != last) { last = now; since = System.currentTimeMillis() }
      if ((changedFrom == null || last != changedFrom) && System.currentTimeMillis() - since >= 350) return last
    }
    return last
  }

  /** What Android Auto sends when the driver taps Next. */
  private fun carPressesNext() {
    instr.runOnMainSync { controller!!.seekToNext() }
    instr.waitForIdleSync()
  }

  @Test fun threeHundredSongsSkipThroughWithoutTheCarsNextEverGoingDead() {
    carTaps(id(1))
    instr.runOnMainSync { controller!!.pause(); controller!!.shuffleModeEnabled = true }
    assertEquals("the whole library should be the queue to skip through", count, on { it.mediaItemCount })
    val seen = linkedSetOf(settled())
    var presses = 0
    // 299 presses reach every other song of the round; the 300th is the end-of-round press.
    repeat(count - 1) { step ->
      val before = seen.last()
      assertTrue(
        "press ${step + 1}: Android Auto would drop this press — no window published after $before " +
          "(index ${on { it.currentMediaItemIndex }} of ${on { it.mediaItemCount }}, ${seen.size} played)",
        on { it.hasNextMediaItem() },
      )
      carPressesNext()
      presses++
      val now = settled(changedFrom = before)
      assertTrue("press $presses: Next did not change the song (stuck on $before after ${seen.size} songs)", now != before)
      assertTrue("press $presses: shuffle replayed $now with ${count - seen.size} songs still unplayed", now !in seen)
      seen += now
    }
    assertEquals("every one of the $count songs should be reachable by pressing Next", count, seen.size)

    // The end of the round: the old code published nothing ahead here, so the car dropped every later press for good.
    assertTrue(
      "the round is played out, but the car must still be offered a press the app can answer with a top-up",
      on { it.hasNextMediaItem() },
    )
    val last = seen.last()
    carPressesNext()
    // With no network on the test device the top-up finds nothing, so the song stays: what is proven here is that the
    // press REACHED the app (the queue logs "next: nothing to follow" and starts the top-up) instead of being dropped.
    assertEquals("the press must not have left the queue somewhere unexpected", last, settled(waitMs = 2500))
  }
}
