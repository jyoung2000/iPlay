package com.nowplaying.discovery

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RadioArtTest {
  private val pkg = "com.nowplaying.discovery"

  @Test fun theFallbackIsAPackagedDrawableNeverARemoteUrl() {
    val art = RadioArt.artString(pkg)
    assertEquals("android.resource://com.nowplaying.discovery/drawable/car_radio_art", art)
    assertFalse(art.startsWith("http"))
  }

  @Test fun theBundledFallbackIsARealPngNotVectorXml() {
    val png = File("src/main/res/drawable-nodpi/${RadioArt.DRAWABLE}.png")
    assertTrue("missing ${png.absolutePath}", png.isFile)
    val head = png.inputStream().use { it.readNBytes(8) }
    assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10), head)
    assertFalse("no vector XML copy alongside", File("src/main/res/drawable/${RadioArt.DRAWABLE}.xml").exists())
  }

  @Test fun aMatchedSongsHttpsCoverIsKept() {
    assertEquals("https://cdn.example/a.jpg", RadioArt.nowPlayingArt(pkg, "https://cdn.example/a.jpg"))
    assertEquals("https://cdn.example/a.jpg", RadioArt.nowPlayingArt(pkg, "  https://cdn.example/a.jpg "))
  }

  @Test fun anythingElseGetsTheBundledIcon() {
    val fallback = RadioArt.artString(pkg)
    for (bad in listOf(null, "", "   ", "null", "http://cdn.example/a.jpg", "https://" + "a".repeat(5000), "content://x/y", "file:///sdcard/a.jpg")) {
      assertEquals("for [$bad]", fallback, RadioArt.nowPlayingArt(pkg, bad))
    }
  }

  @Test fun anEmptyOrInsecureTrackCoverFallsBackToTheSongsCover() {
    assertEquals("https://cdn.example/song.jpg", RadioArt.nowPlayingArt(pkg, "", "https://cdn.example/song.jpg"))
    assertEquals("https://cdn.example/song.jpg", RadioArt.nowPlayingArt(pkg, "http://cdn.example/track.jpg", "https://cdn.example/song.jpg"))
    assertEquals("https://cdn.example/track.jpg", RadioArt.nowPlayingArt(pkg, "https://cdn.example/track.jpg", "https://cdn.example/song.jpg"))
  }
}
