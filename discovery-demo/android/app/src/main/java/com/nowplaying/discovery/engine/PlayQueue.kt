package com.nowplaying.discovery.engine

import kotlin.random.Random
import java.text.Normalizer

/**
 * The service's queue: the songs it was asked to play, which one is current, and what comes next
 * under repeat and shuffle. Pure Kotlin so the rules are tested on the JVM.
 *
 * Automatic choices only use [isPlayable] songs (their complete audio is on the phone).
 * Explicit selection can choose an unready song, which the service waits for.
 *
 * Shuffle plays every ready song once per round. Heard keys survive library pushes, metadata
 * refreshes, reordering and explicit selection; newly ready songs get a turn before repeats.
 * The following round is drawn in advance so peeks are stable and non-consuming for the mixer.
 *
 * SHUFFLE-STAY-001 (owner, 2026-11-24): "the song should stay in the queue but the song shouldnt be
 * played again if other songs in the queue havent been played once". A played song is never removed
 * from [items]: it only waits out the round. Repeat semantics are unchanged (repeat off waits at the
 * end of a play-once round; only repeat all opens a fresh one).
 */
class PlayQueue<T>(
  private val keyOf: (T) -> String,
  private val isPlayable: (T) -> Boolean,
  private val random: Random = Random.Default,
  /**
   * SHUFFLE-ONCE-001 (owner, 2026-11-24: "shuffle should only play a song once before finding a new song"): a song the
   * page marks as heard before (the now playing queue's songs finished or skipped in any session). Under shuffle such a
   * song is held back for the first round. AA-SHUFFLE-ONCE-001 (owner, 2026-11-25: "Shuffle on android auto replayed
   * songs that were already played in the queue"): every shuffled list, page-fed or car-fed, plays each song once; with
   * repeat off it then waits (Next included) instead of drawing a new round over songs already played.
   */
  private val heardBefore: (T) -> Boolean = { false },
  /** ARTIST-RUN-001: the artist credit of a song, for the four-in-a-row limit of the now playing queue's shuffle. */
  private val artistOf: (T) -> String = { "" },
  /**
   * AA-SHUFFLE-ONCE-001: the song itself, apart from the id it was chosen under. The same song reaches the
   * queue under different ids - scoped `list|song` from a car browse, plain `song.id` from a page push - and a
   * round must count it once however it arrived, or a library refresh mid-round replays songs already played.
   * [keyOf] still addresses entries for callers (select, remove, media3); this decides what counts as played.
   */
  private val identityOf: (T) -> String = keyOf,
) {
  /** ARTIST-RUN-001: the artists (artistKey) of the last songs made current, oldest first. */
  private val playedArtists = ArrayDeque<String>()
  private var lastPlayedKey: String? = null
  /**
   * SESSION-PLAYED-001 (owner, 2026-11-28): every song made current since this queue began, across rounds. The round's
   * heard set resets each round and the page's heard-before marks are history, so neither says what actually played.
   * A new round, and the fallback fetch, prefer songs outside this set before any repeat.
   */
  private val sessionPlayed = LinkedHashSet<String>() // insertion order, so a bounded save keeps the newest
  private fun fresh(i: Int): Boolean = identityOf(items[i]) !in sessionPlayed
  /** The candidates not yet played this session, or all of them once every one has played. */
  private fun preferFresh(indices: List<Int>): List<Int> = indices.filter { fresh(it) }.ifEmpty { indices }
  private fun played(i: Int) {
    // One song is one play, however many paths (set, select, advance) make it current.
    val key = identityOf(items[i])
    sessionPlayed += key
    if (key == lastPlayedKey) return
    lastPlayedKey = key
    playedArtists.addLast(artistKey(artistOf(items[i])))
    while (playedArtists.size > MAX_ARTIST_RUN) playedArtists.removeFirst()
  }
  /** The artist of the last MAX_ARTIST_RUN songs when they were all by one artist, else "". */
  private fun runArtist(): String {
    if (playedArtists.size < MAX_ARTIST_RUN) return ""
    val a = playedArtists.first()
    return if (a.isNotEmpty() && playedArtists.all { it == a }) a else ""
  }
  /**
   * SHUFFLE-ONCE-001: the list is the now playing queue, whose songs play once under shuffle (the page says so, and a
   * list whose songs carry heard marks is one too, so a queue restored without the page keeps the rule).
   */
  var playsOnce = false
    set(value) {
      if (field == value) return
      field = value
      // WP7-ORDER-001: whose order applies has changed; the round is not restarted.
      if (shuffle) rebuildOrder()
    }
  /** AA-SHUFFLE-ONCE-001: shuffle always plays each song once per round; only repeat all opens another round. */
  private val once: Boolean get() = shuffle
  /**
   * WP7-ORDER-001: the page's now playing queue arrives already shuffled by the page, so the queue plays it in the
   * list's order instead of drawing its own. A saved list (no page saying it plays once) keeps the native shuffle.
   */
  private val listOrdered: Boolean get() = shuffle && playsOnce
  /**
   * SHUFFLE-STAY-001 (owner, 2026-11-24): "the song should stay in the queue but the song shouldnt be played again if
   * other songs in the queue havent been played once". The page's heard-on-record marks hold a song back for the first
   * round only (it comes after the unheard ones), so a later round is not stuck replaying the same few songs.
   */
  private var firstRound = true
  /** SHUFFLE-STAY-001: this song has had its turn in this round, so another song goes first. It stays in [items]. */
  private fun playedThisRound(i: Int): Boolean = identityOf(items[i]) in heard || (firstRound && heardBefore(items[i]))
  /** ONE is the old id for infinite song repeat; ALL survives only for legacy saved states. */
  enum class Repeat { OFF, ONE, ALL, ONCE, TWICE }

  val repeatsSong: Boolean get() = repeat == Repeat.ONE || repeat == Repeat.ONCE || repeat == Repeat.TWICE
  /** A decoder-confirmed end has started one more full play; show the number still owed. */
  fun consumeRepeat(): Repeat {
    repeat = when (repeat) { Repeat.TWICE -> Repeat.ONCE; Repeat.ONCE -> Repeat.OFF; else -> repeat }
    return repeat
  }

  var items: List<T> = emptyList()
    private set
  /** Index into [items] of the current song, or -1 when there is none. */
  var index: Int = -1
    private set
  var repeat: Repeat = Repeat.OFF
    set(value) { if (field != value) { field = value; version++; onRepeatChanged?.invoke() } }
  /** Told of every change, including the ones made here: a count used up, or dropped with its song. */
  var onRepeatChanged: (() -> Unit)? = null
  var shuffle: Boolean = false
    set(value) {
      if (field == value) return
      field = value
      // Toggling the mode is not a new round: songs already played this round stay played.
      rebuildOrder()
    }

  /** Bumped by every change of items, current song, mode or round, so a saver can skip unchanged state. */
  var version: Long = 0
    private set

  /**
   * NATIVE-COLD-STATE-001: the round by key (not index), so a restored queue carries on the same
   * shuffle round instead of starting a new one after the process died.
   */
  data class Control(
    val index: Int,
    val repeat: Repeat,
    val shuffle: Boolean,
    val order: List<String>,
    val nextRound: List<String>,
    val heard: List<String>,
    /** SESSION-PLAYED-001: every song played since the queue began, across rounds, oldest first. Absent in older saves. */
    val played: List<String> = emptyList(),
  )

  /**
   * Counts only, for a stalled Next: the log truncates long item lists, so these say why nothing is open.
   * open = playable and not yet played this round (the set findNext draws from).
   */
  fun stallSummary(): String {
    val playable = items.indices.count { isPlayable(items[it]) }
    val open = items.indices.count { isPlayable(items[it]) && !playedThisRound(it) }
    val inHeard = items.indices.count { identityOf(items[it]) in heard }
    val held = if (firstRound) items.indices.count { heardBefore(items[it]) } else 0
    val inOrder = order.toHashSet().size
    return "items=${items.size} playable=$playable open=$open inHeard=$inHeard heldByHeardBefore=$held " +
      "firstRound=$firstRound orderDistinct=$inOrder nextRound=${nextRound.size} heard=${heard.size}"
  }

  fun control(): Control = Control(
    index, repeat, shuffle,
    order.map { identityOf(items[it]) }, nextRound.map { identityOf(items[it]) }, heard.toList(),
    sessionPlayed.toList(),
  )

  /**
   * Put back [items] with a saved [c]. Saved keys keep their places; a key no longer present is
   * dropped from the order (heard keys stay until the round ends, as in [set]); an item the saved
   * order lacks is appended, drawn at random only under shuffle. Nothing here starts a new round.
   */
  fun restore(items: List<T>, c: Control) {
    this.items = items
    index = if (items.isEmpty()) -1 else c.index.coerceIn(0, items.size - 1)
    repeat = c.repeat
    shuffle = c.shuffle // its rebuild is replaced below by the saved round
    heard.clear()
    version++
    if (items.isEmpty()) { order = emptyList(); nextRound = emptyList(); return }
    val byKey = HashMap<String, Int>()
    items.indices.forEach { byKey.putIfAbsent(identityOf(items[it]), it) }
    fun remap(keys: List<String>): List<Int> {
      val seen = HashSet<Int>()
      val kept = keys.mapNotNull { byKey[it] }.filter { seen.add(it) }
      val rest = items.indices.filter { it !in seen }
      return kept + if (c.shuffle) rest.shuffled(random) else rest
    }
    order = if (c.shuffle) remap(c.order) else items.indices.toList()
    nextRound = if (c.shuffle) remap(c.nextRound) else items.indices.toList()
    heard += c.heard
    current?.let { heard += identityOf(it) }
    // SESSION-PLAYED-001: songs played in earlier rounds stay played after a restart. An older save has no played
    // list, so its round's heard keys are the most that is known.
    sessionPlayed.clear()
    sessionPlayed += c.played
    sessionPlayed += heard
  }

  /**
   * CAR-SKIP-002 (owner, 2026-11-28: "the skip button on android auto does not work after a while").
   *
   * The round as a timeline: the songs already behind the listener, the song playing, then the songs
   * a press can still reach, in the order the round will reach them. Android Auto is a Media3
   * controller and works out for itself whether a next song exists, from the published timeline
   * alone — [androidx.media3.common.BasePlayer.seekToNext] then drops the press before this app ever
   * sees it. Published in the list's own order, a shuffled round whose song happens to sit on the
   * last row therefore looks finished, and the car's Next goes dead while songs are still unplayed.
   *
   * So the published order must BE the play order. A song is "still reachable" by exactly the test
   * [findNext] applies, plus [canFollow] for one that is not on the phone yet but can still be
   * streamed ([advanceFetchable]); everything else sits behind the song playing, where it cannot
   * promise the car a next song this round has no intention of playing.
   */
  fun roundTimeline(canFollow: (T) -> Boolean = { false }, keepNext: Boolean = false): List<Int> {
    val at = index
    val drawn = order.filter { it in items.indices }
    val seen = drawn.toHashSet()
    val all = drawn + items.indices.filter { it !in seen }
    // Same rule as findNext's open list (playedThisRound covers both the round's heard set and the
    // first-round heard-before marks), so the published timeline can never offer a next findNext refuses.
    fun reachable(i: Int) = i != at &&
      !playedThisRound(i) &&
      (isPlayable(items[i]) || canFollow(items[i]))
    val reach = all.filter(::reachable)
    // Songs not yet played this session come first, the order findNext plays them in.
    val ahead = (reach.filter { fresh(it) } + reach.filter { !fresh(it) }).toMutableList()
    // CAR-SKIP-003 (owner, 2026-11-28): "ensure the skip button always works on android auto ... after hours of
    // listening on discover the skip button no longer works". With every song of the round played, nothing is ahead
    // and the car drops Next itself, for good. The app can still answer that press — it finds more songs for the
    // listening mood and plays the first one (AA-NEXT-DRY-001) — so while a caller can answer, one window stays
    // published after the song playing. It changes nothing about what plays automatically ([findNext] is untouched):
    // the window is the song a fresh round would open with, so the car's queue view stays truthful.
    if (keepNext && ahead.isEmpty() && at in items.indices) {
      val window = peekAfterEnd()?.takeIf { it != at }
        ?: all.firstOrNull { it != at && (isPlayable(items[it]) || canFollow(items[it])) }
      if (window != null) ahead += window
    }
    val aheadSet = ahead.toHashSet()
    val behind = all.filter { it != at && it !in aheadSet }
    return if (at in items.indices) behind + at + ahead else behind + ahead
  }

  /** This round's play order (indices into [items]); under shuffle the current song comes first. */
  private var order: List<Int> = emptyList()
  /** The round after this one, drawn in advance. */
  private var nextRound: List<Int> = emptyList()
  // Keep removed keys until the round ends: removing/re-adding a heard song is not a new turn.
  private val heard = mutableSetOf<String>()

  val current: T? get() = items.getOrNull(index)

  fun set(items: List<T>, startIndex: Int) {
    val old = this.items
    val oldKey = current?.let(identityOf)
    val oldOrder = order.map { identityOf(old[it]) }
    val oldNext = nextRound.map { identityOf(old[it]) }
    version++
    this.items = items
    // A different queue starts its own history; the same list keeps what it played across rounds.
    if (items.isEmpty() || old.isEmpty() || !sameList(old, items)) sessionPlayed.clear()
    index = if (items.isEmpty()) -1 else startIndex.coerceIn(0, items.size - 1)
    if (index >= 0) played(index)
    if (oldKey != null && current?.let(identityOf) != oldKey && (repeat == Repeat.ONCE || repeat == Repeat.TWICE)) repeat = Repeat.OFF
    if (items.isEmpty()) {
      heard.clear()
      order = emptyList()
      nextRound = emptyList()
      return
    }
    // A genuinely different list (a car or phone picking another playlist) is a new round.
    // Library pushes, page loads, adds and removals of the same list keep what was heard.
    if (old.isEmpty() || !sameList(old, items)) {
      startRound()
      return
    }
    if (!shuffle || listOrdered) {
      // WP7-ORDER-001: the page's now playing queue is played in the list's order, which the page shuffled itself.
      rebuildOrder()
      return
    }
    // Preserve random order by identity, not position. Only genuinely new keys need a draw.
    val byKey = items.indices.associateBy { identityOf(items[it]) }
    fun remap(keys: List<String>): List<Int> {
      val kept = keys.mapNotNull { byKey[it] }
      val known = keys.toHashSet()
      return kept + items.indices.filter { identityOf(items[it]) !in known }.shuffled(random)
    }
    order = remap(oldOrder)
    nextRound = remap(oldNext)
    // set() also serves page loads and controller choices, not only library pushes.
    if (current?.let(identityOf) != oldKey) {
      val chosen = index
      index = byKey[oldKey] ?: -1
      select(chosen)
    }
  }

  /** Swap in fresh copies of the same songs (same keys, same positions) without disturbing the order or the round. */
  fun refresh(items: List<T>): Boolean {
    if (items.size != this.items.size || items.indices.any { keyOf(items[it]) != keyOf(this.items[it]) }) return false
    this.items = items
    version++
    return true
  }

  /** Jump to a song of the queue by its key. Returns false when it is not in the queue. */
  fun select(key: String): Boolean = select(items.indexOfFirst { keyOf(it) == key })

  /** Explicit replay is allowed, but must not erase what automatic shuffle already played. */
  fun select(i: Int): Boolean {
    if (i !in items.indices) return false
    if (i != index && (repeat == Repeat.ONCE || repeat == Repeat.TWICE)) repeat = Repeat.OFF
    if (shuffle) {
      // Native natural-end and blend handoffs also select the peek. Commit that prepared round,
      // just as advance() would, instead of redrawing the rest after each song.
      val next = findNext(wrap = true)
      if (next?.first == i && next.second) commitRound(i)
    }
    heard += identityOf(items[i])
    index = i
    played(i)
    version++
    return true
  }

  /**
   * CAR-MOOD-001: under shuffle the next songs come from this round's drawn order, not the list's.
   * [roundAhead] names the songs of the round still to come, in play order; [arrangeRound] plays the
   * ones [keys] names in that order, in the same slots. Songs heard this round never move back.
   */
  fun roundAhead(): List<String> {
    if (!shuffle) return emptyList()
    return order.filter { identityOf(items[it]) !in heard && it != index }.map { keyOf(items[it]) }
  }

  fun arrangeRound(keys: List<String>): Boolean {
    if (!shuffle) return false
    val rank = HashMap<String, Int>()
    keys.forEachIndexed { i, k -> rank.putIfAbsent(k, i) }
    val slots = order.indices.filter {
      val e = items[order[it]]
      keyOf(e) in rank && identityOf(e) !in heard && order[it] != index
    }
    if (slots.size < 2) return false
    val sorted = slots.map { order[it] }.sortedBy { rank[keyOf(items[it])] }
    val next = order.toMutableList()
    slots.forEachIndexed { j, s -> next[s] = sorted[j] }
    if (next == order) return false
    order = next
    version++
    return true
  }

  /** On a natural end, repeat this song while extra plays remain; then move to the next. */
  fun peekAfterEnd(): Int? = if (repeatsSong && index >= 0) index else findNext(repeat == Repeat.ALL)?.first

  /** The listener pressed Next: always moves on, including under song repeat. */
  fun peekNextPressed(): Int? = findNext(wrap = true, manual = true)?.first

  /** The previous playable song of this round, or null at its start. */
  fun peekPrevious(): Int? {
    val pos = order.indexOf(index)
    for (p in pos - 1 downTo 0) if (isPlayable(items[order[p]])) return order[p]
    return null
  }

  /** Commit what [peekAfterEnd] ([pressed] false) or [peekNextPressed] ([pressed] true) said. */
  fun advance(pressed: Boolean): Int? {
    if (!pressed && repeatsSong && index >= 0) { consumeRepeat(); return index }
    val (i, wrapped) = findNext(wrap = pressed || repeat == Repeat.ALL, manual = pressed) ?: return null
    if (pressed && i != index && (repeat == Repeat.ONCE || repeat == Repeat.TWICE)) repeat = Repeat.OFF
    if (wrapped) commitRound(i)
    index = i
    played(i)
    version++
    // Tracked in order mode too, so switching shuffle on mid-list keeps those songs played.
    heard += identityOf(items[i])
    return i
  }

  /**
   * AA-NEXT-DRY-001 (owner, 2026-11-26: "after a while the skip/forward button stops working"): [advance] only takes
   * songs whose whole file is on the phone, so once every downloaded song of a shuffled round had played, Next did
   * nothing even with unheard songs (a queue top-up, mood or similar finds) still downloading. This makes the first
   * such song current instead: unheard this round under shuffle, ahead of the current one in order mode. Never a song
   * already played this round. Returns its index, or null when no song [canFetch] is left.
   */
  fun advanceFetchable(canFetch: (T) -> Boolean): Int? {
    if (items.isEmpty()) return null
    val ahead = if (shuffle) order.filter { it != index && !playedThisRound(it) }
      else order.indexOf(index).let { pos -> (pos + 1 until order.size).map { order[it] } }
    val open = ahead.filter { it in items.indices && canFetch(items[it]) }.let { if (shuffle) preferFresh(it) else it }
    val run = if (once) runArtist() else ""
    val i = open.firstOrNull { run.isEmpty() || artistKey(artistOf(items[it])) != run } ?: open.firstOrNull() ?: return null
    if (i != index && (repeat == Repeat.ONCE || repeat == Repeat.TWICE)) repeat = Repeat.OFF
    index = i
    played(i)
    version++
    heard += identityOf(items[i])
    return i
  }

  /**
   * CAR-X-001 (owner, 2026-11-24: "ensure the X button works on android auto"): take the song playing ([key]) out of the
   * queue and make another current: the one Next would play, else the first playable one after it in the list
   * (wrapping), never one [avoid] turns down nor, when the list plays once, one already heard. Returns the new index,
   * or null (nothing changed) when [key] is not the song playing or nothing can follow it.
   */
  fun leave(key: String, avoid: (T) -> Boolean = { false }, canFetch: (T) -> Boolean = { false }): Int? {
    val at = index
    if (at < 0 || keyOf(items[at]) != key) return null
    fun open(i: Int) = i != at && !avoid(items[i]) && !(once && playedThisRound(i))
    fun usable(i: Int) = open(i) && isPlayable(items[i])
    // CAR-X-001 vs AA-SHUFFLE-ONCE-001: X takes the song playing out of the queue, so there must always be a
    // way off it. When a shuffled round has used up its songs, a song already played this round is a better
    // answer than staying on a song the listener just turned down. A page "heard before" mark still holds:
    // those songs the owner asked never to come back in the first round.
    fun again(i: Int) = i != at && !avoid(items[i]) && !(firstRound && heardBefore(items[i]))
    val after = (1 until items.size).map { (at + it) % items.size }.let { list ->
      if (shuffle) list.sortedBy { if (fresh(it)) 0 else 1 } else list
    }
    // With nothing held yet, a song that can still be fetched (it starts once its file is here), as a car pick does.
    val next = findNext(wrap = false)?.first?.takeIf(::usable)
      ?: after.firstOrNull(::usable)
      ?: after.firstOrNull { open(it) && canFetch(items[it]) }
      ?: after.firstOrNull { again(it) && isPlayable(items[it]) }
      ?: after.firstOrNull { again(it) && canFetch(items[it]) }
      ?: return null
    val nextKey = keyOf(items[next])
    val rest = items.filterIndexed { i, _ -> i != at }
    set(rest, rest.indexOfFirst { keyOf(it) == nextKey })
    if (current?.let(keyOf) != nextKey) select(nextKey) else played(index)
    current?.let { heard += identityOf(it) }
    version++
    return index.takeIf { it >= 0 }
  }

  /**
   * WP7-APPEND-001: add songs the engine found itself (the car's Find similar, a discovery top-up). Each one goes to a
   * random slot whose neighbours are other artists, so a top-up never drops two songs by the same artist next to each
   * other; when every slot has that artist beside it, any slot will do. Nothing is placed in front of the song playing,
   * which carries on, and under the queue's own shuffle the new songs take spaced slots in this round's order too.
   */
  fun insertSpaced(extra: List<T>): Boolean {
    if (extra.isEmpty()) return false
    val keep = current?.let(keyOf)
    val list = items.toMutableList()
    val from = (index + 1).coerceAtLeast(0)
    for (e in extra) list.add(spacedSlot(list.map { artistKey(artistOf(it)) }, artistKey(artistOf(e)), from), e)
    val added = extra.mapTo(HashSet(), keyOf)
    set(list, list.indexOfFirst { keyOf(it) == keep }.coerceAtLeast(0))
    if (shuffle && !listOrdered) {
      // The round drew its order before these songs existed: space them inside it, after the song playing.
      val rest = order.filter { keyOf(items[it]) !in added }.toMutableList()
      val at = (rest.indexOf(index) + 1).coerceAtLeast(0)
      for (i in items.indices.filter { keyOf(items[it]) in added }) {
        rest.add(spacedSlot(rest.map { artistKey(artistOf(items[it])) }, artistKey(artistOf(items[i])), at), i)
      }
      order = rest
      nextRound = drawRound(avoidFirst = order.lastOrNull() ?: -1)
      version++
    }
    return true
  }

  /** A random slot of [artists] at [from] or later with [a] beside neither neighbour, else any slot from [from]. */
  private fun spacedSlot(artists: List<String>, a: String, from: Int): Int {
    val slots = (from.coerceIn(0, artists.size)..artists.size).toList()
    val open = slots.filter { artists.getOrNull(it - 1) != a && artists.getOrNull(it) != a }
    return open.ifEmpty { slots }.random(random)
  }

  /** Commit what [peekPrevious] said. */
  fun previous(): Int? {
    val i = peekPrevious() ?: return null
    if (i != index && (repeat == Repeat.ONCE || repeat == Repeat.TWICE)) repeat = Repeat.OFF
    index = i
    version++
    heard += identityOf(items[i])
    // ARTIST-RUN-001: Previous plays a song, so it counts towards the four-in-a-row limit like any other play.
    played(i)
    return i
  }

  /** The next playable index and whether reaching it crosses into the next round. */
  private fun findNext(wrap: Boolean, manual: Boolean = false): Pair<Int, Boolean>? {
    if (shuffle) {
      // ARTIST-RUN-001 (owner, 2026-11-24): the now playing queue never plays one artist more than four times in a row
      // while another artist's song is left to play.
      val run = if (once) runArtist() else ""
      // SHUFFLE-STAY-001: songs already played this round wait, they are not taken out of the queue.
      val open = preferFresh(order.filter { isPlayable(items[it]) && !playedThisRound(it) })
      (open.firstOrNull { run.isEmpty() || artistKey(artistOf(items[it])) != run } ?: open.firstOrNull())?.let { return it to false }
      if (!wrap) return null
      // SHUFFLE-STAY-001 (owner, 2026-11-24): automatic advance waits at the end of an exhausted play-once round, so it
      // does not start itself again. Owner reversal (2026-11-28): an explicit Next press (manual) opens the next round.
      // Repeat all always opens one. The new round never opens with the song just played while another can go first.
      if (once && repeat != Repeat.ALL && !manual) return null
      // Readiness and explicit selection can change which song really closes the round.
      val next = nextRound.filter { it != index && isPlayable(items[it]) && fresh(it) }.firstOrNull()
        ?: nextRound.firstOrNull { it != index && isPlayable(items[it]) }
        ?: nextRound.firstOrNull { isPlayable(items[it]) }
      return next?.let { it to true }
    }
    val pos = order.indexOf(index)
    for (p in pos + 1 until order.size) if (isPlayable(items[order[p]])) return order[p] to false
    if (!wrap) return null
    for (i in nextRound) if (isPlayable(items[i])) return i to true
    return null
  }

  private fun commitRound(first: Int) {
    order = if (shuffle) listOf(first) + nextRound.filter { it != first } else nextRound
    heard.clear()
    // SHUFFLE-STAY-001: the page's heard marks counted for the first round; the next one is over the whole queue.
    firstRound = false
    nextRound = drawRound(avoidFirst = order.lastOrNull() ?: -1)
  }

  private fun startRound() {
    heard.clear()
    firstRound = true
    rebuildOrder()
  }

  /** Recompute the play order for the current mode without forgetting this round's heard songs. */
  private fun rebuildOrder() {
    version++
    val all = items.indices.toList()
    order = when {
      // WP7-ORDER-001: in order mode, and in the page's own now playing queue, the list is the play order.
      !shuffle || listOrdered -> all
      index >= 0 -> listOf(index) + all.filter { it != index }.shuffled(random)
      else -> all.shuffled(random)
    }
    current?.let { heard += identityOf(it) }
    nextRound = drawRound(avoidFirst = order.lastOrNull() ?: -1)
  }

  /** Same list when at least half of the smaller list is shared (edits keep it, a new playlist does not). */
  private fun sameList(old: List<T>, new: List<T>): Boolean {
    val a = old.mapTo(HashSet(), identityOf)
    val b = new.mapTo(HashSet(), identityOf)
    val shared = b.count { it in a }
    return shared * 2 >= minOf(a.size, b.size)
  }

  private fun drawRound(avoidFirst: Int): List<Int> {
    val all = items.indices.toList()
    if (!shuffle) return all
    // WP7-ORDER-001: the page's queue keeps its own order; a saved list is drawn here.
    val r = (if (listOrdered) all else all.shuffled(random)).toMutableList()
    if (r.size > 1 && r.first() == avoidFirst) r.add(r.removeAt(0))
    return r
  }

  companion object {
    /** ARTIST-RUN-001: no more songs than this in a row by one artist (the now playing queue's shuffle). */
    const val MAX_ARTIST_RUN = 4

    // WP7-ARTIST-KEY-001: identity.ts:28's ARTIST_SPLIT and fold(), so the car names the first artist of a credit
    // exactly as the page does. web/src/engine/golden/artist-credits.json is the golden file both sides read
    // (ArtistCreditsGoldenTest here, artist-credits.golden.test.ts on the web side).
    private val SPLIT = Regex(
      "\\s*(?:,|&|\\band\\b|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\bvs\\.?|/|;)\\s*",
      RegexOption.IGNORE_CASE,
    )
    private val MARKS = Regex("\\p{M}+")
    private val PUNCT = Regex("[^\\p{L}\\p{N}_\\s]")
    private val SPACES = Regex("\\s+")

    /** identity.ts's fold(): lower case, no accents, letters and digits only, single spaces. */
    private fun fold(text: String): String {
      var t = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "").lowercase()
      t = t.replace("&", " and ").replace('\u2019', '\'')
      t = PUNCT.replace(t, " ").replace("_", " ")
      return SPACES.replace(t, " ").trim()
    }

    /** The first-named artist of a credit, as one key however it is spelt ("SLAYR feat. X" and "Slayr" match). */
    fun artistKey(credit: String): String =
      credit.split(SPLIT).firstNotNullOfOrNull { fold(it).takeIf(String::isNotEmpty) } ?: ""
  }
}
