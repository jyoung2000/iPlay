package com.nowplaying.discovery

/** Avoid surfacing a dead directory entry as a song-play error; only skip an immediate 404. */
internal object RadioAutoSkip {
  const val MAX_ATTEMPTS = 3

  fun shouldAdvance(error: Throwable, decodedFrames: Long, attempts: Int): Boolean =
    decodedFrames == 0L && attempts in 0 until MAX_ATTEMPTS && error.message == "Station answered HTTP 404"
}
