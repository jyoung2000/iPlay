/**
 * COLOR-DISCOVERY-001: the listener's colours as a source of new music.
 *
 * Every other gather source hangs off a starting song, and those pools are finite - ask twice and
 * the second answer is the first one. The colours are not finite: each strong colour is a cell in
 * the Taste Picker, each cell names real reference artists, and a cursor walks further through them
 * (and deeper into each artist's catalogue) on every run, so there is always something new to ask
 * for. What the listener skips away from is never asked for, and a listener with no colours yet asks
 * nothing, because inventing a genre for someone is worse than finding nothing.
 */
import { CATALOG } from './taste-catalog';
import { genreIdentity } from './genre-identity';
import { SUBS } from './genre-table';
import { FAMILIES } from './genre-table';
import { fold } from './identity';

/** The relation stamped on a song a colour found. Exploration, like the genre chart: not relatedness. */
export const TASTE_RELATION = 'taste_colour';

/** A colour has to be meant before it is acted on; a single stray play is not a taste. */
export const COLOUR_FLOOR = 0.15;

/**
 * Asks per run. Six searches is a handful of kilobytes against the day's allowance, and it is
 * enough for the queue to keep growing on colours alone when every seed source is spent.
 */
export const TASTE_ASKS_PER_RUN = 6;

export interface Colour {
  name: string;
  label: string;
  score: number;
}

export interface TasteAsk {
  /**
   * `artist` searches a named reference artist of that cell. `genre` is a real genre lookup (Deezer's
   * genre id, then its chart), never a text search: GENRE-WORD-001 below says why.
   */
  kind: 'artist' | 'genre';
  /** What to ask the services for. */
  term: string;
  /** How deep into that term's results to read; the same term twice never returns the same page. */
  page: number;
  /** The colour this came from, for the reason shown to the listener. */
  genre: string;
  label: string;
  /** Where the next run should carry on from. */
  nextCursor: number;
}

/** Reference artists for a colour, from the picker cell it names. [] when the catalogue has no cell. */
export function colourArtists(key: string): string[] {
  const sub = SUBS.find((s) => s.key === key);
  if (!sub) return [];
  for (const fam of CATALOG) {
    const cell = fam.subs.find((s) => s.name === sub.name);
    if (cell) return cell.artists;
  }
  return [];
}

/**
 * GENRE-WORD-001 (owner, 2026-11-27): "the discover queue randomly plays a song whose title is the genre".
 *
 * The cause was asking a music service for a colour by its NAME. A catalogue search matches words in titles
 * and artist names, not the genre of the music: asking Deezer for "Hip Hop" returns songs called "Hip-Hop",
 * "best Hip Hop" returns "Best Hip-Hop" by a library account, and "Rage" returns Rage Against the Machine,
 * a band whose name contains the word. None of that is the listener's mood.
 *
 * So a colour is never spelled into a search box. It is asked for in one of two honest ways: by a reference
 * artist of its picker cell, or by the service's own genre id. Anything else says no and asks for nothing.
 */
export function genreLookupName(c: Colour): string {
  const fam = FAMILIES.find((f) => f.slug === c.name);
  return (fam?.name ?? c.label ?? c.name).replace(/\s*\/\s*/g, ' ').trim();
}

/**
 * GENRE-WORD-001: the service's own genre names for a colour, best first. Our family names are not always
 * what Deezer calls them ("Soul/Jazz/Funk" is its "Soul & Funk"), and a colour that resolves to nothing
 * would quietly stop asking altogether - the failure that hides rather than shouts.
 */
const DEEZER_GENRES: Record<string, string[]> = {
  rock: ['Rock', 'Alternative'],
  'hip-hop': ['Rap/Hip Hop'],
  pop: ['Pop'],
  indie: ['Alternative', 'Rock'],
  'soul-jazz-funk': ['Soul & Funk', 'Jazz'],
  electronic: ['Electro', 'Dance'],
  'ambient-chill': ['Chill Out/Trip-Hop/Lounge', 'Electro'],
  'rnb-afro-latin': ['R&B', 'Latin Music', 'African Music'],
};

/** The names to try against the service's genre list for a colour, best first. */
export function deezerGenreTerms(colour: string, fallback: string): string[] {
  const named = DEEZER_GENRES[colour];
  return named && named.length ? named : [fallback];
}

/** The words of a genre name, folded, for the echo guard. "Hip-Hop" and "hip hop" are the same words. */
function genreWords(term: string): string[] {
  return fold(term).replace(/[^a-z0-9]+/g, ' ').trim().split(' ').filter((w) => w && !FILLER.has(w));
}
const FILLER = new Set(['best', 'new', 'top', 'the', 'a', 'of', 'music', 'songs', 'mix', 'playlist', '2024', '2025', '2026']);

/**
 * GENRE-WORD-001: true when a hit is only an echo of the genre word, not music in that genre - the song is
 * called "Hip-Hop", or "Best Hip Hop Mix", or the act is named after the word. These are dropped at the
 * source, so no run can put one in the queue however the ask was phrased.
 */
export function genreEcho(title: string, artists: readonly string[], term: string): boolean {
  const words = genreWords(term);
  if (!words.length) return false;
  const hay = (t: string) => {
    const got = new Set(genreWords(t));
    return words.every((w) => got.has(w));
  };
  if (hay(title)) return true;
  return artists.some((a) => hay(a));
}

/**
 * Up to `want` things to ask the music services for, built from the listener's colours.
 *
 * `cursor` is carried in the store and advances every run, so run two asks for what run one did not.
 * The strongest colour gets the most asks, in proportion to how strongly the listening says it.
 */
export function tasteAsks(colours: Colour[], cursor: number, want: number): TasteAsk[] {
  const liked = colours.filter((c) => c.score >= COLOUR_FLOOR).sort((a, b) => b.score - a.score).slice(0, 6);
  if (!liked.length || want <= 0) return [];
  const total = liked.reduce((a, c) => a + c.score, 0);
  // Shares in proportion to the colour, but every colour that made the floor gets at least one ask.
  const shares = liked.map((c) => Math.max(1, Math.round((c.score / total) * want)));
  const asks: TasteAsk[] = [];
  const step = Math.max(0, Math.trunc(cursor));
  for (let i = 0; i < liked.length && asks.length < want; i++) {
    const c = liked[i] as Colour;
    const artists = colourArtists(c.name);
    // A colour the picker names artists for is asked by those artists; one it does not (every family
    // colour, e.g. "Hip-hop") is asked for by the service's own genre id. Never by its name as text.
    const pool: { kind: 'artist' | 'genre'; term: string }[] = artists.length
      ? artists.map((a) => ({ kind: 'artist' as const, term: a }))
      : [{ kind: 'genre' as const, term: genreLookupName(c) }];
    if (!pool.length) continue;
    for (let k = 0; k < (shares[i] as number) && asks.length < want; k++) {
      // Every colour reads its pool from a different place, so one run's asks are spread across it.
      const at = step * want + i * 3 + k;
      const pick = pool[at % pool.length] as { kind: 'artist' | 'genre'; term: string };
      // Once the pool has been walked through, the same terms are read a page deeper instead. A
      // single-term pool (a colour asked by genre id) has nothing to walk, so it simply reads one
      // page deeper per run - otherwise its first run would start several pages in and find nothing.
      const page = pool.length > 1 ? Math.trunc(at / pool.length) : step;
      // The colour is named the way its bubble names it (TAGS-CANON-001), so the reason shown to the
      // listener reads "Your sound leans Soul", not a service's own spelling.
      const label = genreIdentity(c.label || c.name)?.label ?? genreIdentity(c.name)?.label ?? c.label ?? c.name;
      asks.push({ ...pick, page, genre: c.name, label, nextCursor: step + 1 });
    }
  }
  // A term is asked once per run, however the shares landed.
  const seen = new Set<string>();
  return asks.filter((a) => {
    const id = `${a.term}#${a.page}`;
    return seen.has(id) ? false : (seen.add(id), true);
  });
}

/** The words shown to the listener for a colour-found song. */
export function tasteAskReason(label: string): string {
  return `Your sound leans ${label}, so the services were asked for more of it`;
}

export interface TasteWideAsk {
  /** A reference artist of one of the listener's colours, as the starting point. */
  term: string;
  /** 0 = that artist's own neighbours; deeper walks a step further out each time round. */
  depth: number;
  genre: string;
  label: string;
}

/**
 * One widening ask per run. Three reference artists per colour read a page deeper every run would
 * fade: the depth grows but the breadth does not. So each run also follows one of those artists out
 * to the artists the service puts beside them, which is how a colour keeps finding music nobody
 * named in the catalogue.
 */
export function tasteWideAsk(colours: Colour[], cursor: number): TasteWideAsk | null {
  const liked = colours.filter((c) => c.score >= COLOUR_FLOOR).sort((a, b) => b.score - a.score).slice(0, 6);
  if (!liked.length) return null;
  const step = Math.max(0, Math.trunc(cursor));
  // The colours take turns, strongest first, so one run never spends the whole widening budget on one.
  const c = liked[step % liked.length] as Colour;
  // Widening starts from a real artist. A genre word here used to be searched as artist:"Hip Hop",
  // which found a band whose NAME holds the word and then followed its neighbours (GENRE-WORD-001).
  const pool = colourArtists(c.name);
  if (!pool.length) return null;
  const round = Math.trunc(step / liked.length);
  const term = pool[round % pool.length] as string;
  const label = genreIdentity(c.label || c.name)?.label ?? genreIdentity(c.name)?.label ?? c.label ?? c.name;
  return { term, depth: Math.trunc(round / pool.length), genre: c.name, label };
}
