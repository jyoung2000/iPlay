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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * CAR-SKIP-003: leaves a 300-song Discover-shaped queue PLAYING on the device and cleans nothing up, so the Desktop
 * Head Unit can then be driven by hand (or by a harness) against a real queue. Not a verification in itself: the
 * assertions here only confirm the queue it leaves behind is the one the DHU run needs.
 *
 * Paired cleanup lives in [dhuCleanUpThreeHundredSongs], run after the DHU session.
 */
@RunWith(AndroidJUnit4::class)
class CarDhuSeed300Test {
  private val ctx: Context get() = ApplicationProvider.getApplicationContext()
  private val instr get() = InstrumentationRegistry.getInstrumentation()
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

  private fun id(i: Int) = "dhu300_$i"
  private fun link(i: Int) = "https://music.youtube.com/watch?v=dhu300q$i"
  private fun toneName() =
    MessageDigest.getInstance("SHA-256").digest("dhu300-shared".toByteArray()).joinToString("") { "%02x".format(it) } + ".wav"

  /** One real 120 s WAV shared by every song, so nothing auto-advances while the head unit is being driven. */
  private fun storeTones() {
    val rate = 24000
    val frames = rate * 120
    val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (f in 0 until frames) pcm.putShort((0.08 * kotlin.math.sin(2 * Math.PI * 440.0 / rate * f) * 32767).toInt().toShort())
    val file = File(File(ctx.filesDir, "audio-cache").apply { mkdirs() }, toneName())
    RandomAccessFile(file, "rw").use { out ->
      out.setLength(0)
      val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcm.capacity())
      out.write(h.array()); out.write(pcm.array())
    }
    val prefs = ctx.getSharedPreferences("airwave-private-audio", Context.MODE_PRIVATE).edit()
    for (i in 1..count) prefs.putString(link(i), toneName())
    prefs.commit()
  }

  @Test fun dhuSeedThreeHundredSongsPlaying() {
    storeTones()
    val songs = (1..count).joinToString(",") { i ->
      """{"id":"${id(i)}","title":"DHU $i","artist":"Artist ${i % 12}","durationMs":120000,"ready":true,"source":"${link(i)}"}"""
    }
    MediaHub.apply(JSONObject("""{"library":{"songs":[$songs],"playlists":[]}}"""))
    val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
    val controller = await { MediaController.Builder(ctx, token).buildAsync() }
    instr.runOnMainSync {
      controller.stop(); controller.clearMediaItems()
      controller.setMediaItem(MediaItem.Builder().setMediaId(id(1)).build())
      controller.prepare()
      controller.shuffleModeEnabled = true
      controller.repeatMode = Player.REPEAT_MODE_OFF
      controller.play()
    }
    Thread.sleep(3000)
    val n = AtomicReference(0)
    val playing = AtomicReference("")
    instr.runOnMainSync {
      n.set(controller.mediaItemCount)
      playing.set(controller.currentMediaItem?.mediaId ?: "none")
    }
    assertEquals("the DHU run needs all $count songs queued", count, n.get())
    assertTrue("it should be playing a seeded song, not ${playing.get()}", playing.get().startsWith("dhu300_"))
    instr.runOnMainSync { controller.release() }
  }

  @Test fun dhuShuffleOn() {
    val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
    val controller = await { MediaController.Builder(ctx, token).buildAsync() }
    instr.runOnMainSync { controller.shuffleModeEnabled = true }
    // Long enough for the engine to write its native state, so the setting survives this process ending.
    Thread.sleep(10000)
    val on = AtomicReference(false)
    instr.runOnMainSync { on.set(controller.shuffleModeEnabled) }
    assertTrue("the session should report shuffle on", on.get())
    instr.runOnMainSync { controller.release() }
  }

  @Test fun dhuCleanUpThreeHundredSongs() {
    val prefs = ctx.getSharedPreferences("airwave-private-audio", Context.MODE_PRIVATE)
    File(File(ctx.filesDir, "audio-cache"), toneName()).delete()
    val edit = prefs.edit()
    for (i in 1..count) edit.remove(link(i))
    edit.commit()
  }
}
