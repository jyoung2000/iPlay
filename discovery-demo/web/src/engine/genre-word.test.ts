/**
 * GENRE-WORD-001 (owner, 2026-11-27): "the discover queue randomly plays a song whose title is the genre".
 *
 * The fixtures in the first block are the real answers the live Deezer search gave on 2026-11-27 for the
 * terms the colour asks used to send. They are what the listener was hearing.
 */
import { describe, expect, it } from 'vitest';
import * as E from './engine';
import { DEFAULT_ENGINE } from './config';
import { genreEcho, genreLookupName, deezerGenreTerms, tasteAsks, tasteWideAsk, COLOUR_FLOOR } from './taste-gather';
import { FAMILIES } from './genre-table';
import * as G from './genres';
import { fold } from './identity';

describe('a genre word is never searched as text (GENRE-WORD-001)', () => {
  // Live Deezer, 2026-11-27, q="Hip Hop" / "best Hip Hop" / "Rage" / "Soul new".
  const REAL: [string, string, string][] = [
    ['Hip Hop', 'Hip-Hop', 'Lil Wayne'],
    ['Hip Hop', 'Hip-Hop', 'Dead Prez'],
    ['Hip Hop', 'Hip Hop', 'Mos Def'],
    ['Hip Hop', 'Hip-Hop (Radio Version)', 'Dead Prez'],
    ['best Hip Hop', 'Best Hip-Hop', 'CoffeeMusic'],
    ['Rage', 'Killing In The Name', 'Rage Against the Machine'],
    ['Rage', 'Bulls On Parade', 'Rage Against the Machine'],
  ];

  it.each(REAL)('drops what %s returned: "%s" by %s', (term, title, artist) => {
    expect(genreEcho(title, [artist], term)).toBe(true);
  });

  it('keeps real music in the genre', () => {
    expect(genreEcho('Carnival', ['Playboi Carti'], 'Hip Hop')).toBe(false);
    expect(genreEcho('Sleep Now In the Fire', ['Nine Inch Nails'], 'Rage')).toBe(false);
    expect(genreEcho('HipHopHead', ['Someone'], 'Hip Hop')).toBe(false); // one word, not the genre
  });

  it('catches the word however it is spelled or padded', () => {
    for (const t of ['hip hop', 'HIP-HOP', 'Hip Hop Mix', 'Best Hip Hop 2026', 'Top Hip Hop Songs']) {
      expect(genreEcho(t, ['Whoever'], 'Hip-hop')).toBe(true);
    }
  });

  it('an empty or unknown term never drops anything', () => {
    expect(genreEcho('Hip-Hop', ['Lil Wayne'], '')).toBe(false);
  });

  it('a colour with reference artists asks by artist; one without asks by genre id, never by words', () => {
    const asks = tasteAsks([{ name: 'hip-hop', label: 'Hip-hop', score: 0.9 }], 0, 4);
    expect(asks.length).toBeGreaterThan(0);
    expect(asks.every((a) => a.kind === 'genre')).toBe(true);
    expect(asks.every((a) => !/best |new$| 2026/.test(a.term))).toBe(true);
    const sub = tasteAsks([{ name: 'trap', label: 'Trap', score: 0.9 }], 0, 4);
    expect(sub.every((a) => a.kind === 'artist')).toBe(true);
  });

  it('never phrases a genre as an artist for the widening ask', () => {
    expect(tasteWideAsk([{ name: 'hip-hop', label: 'Hip-hop', score: 0.9 }], 0)).toBeNull();
    const real = tasteWideAsk([{ name: 'trap', label: 'Trap', score: 0.9 }], 0);
    expect(real).not.toBeNull();
    expect(genreEcho('x', [real!.term], 'Trap')).toBe(false);
  });

  it('a colour below the floor asks nothing at all', () => {
    expect(tasteAsks([{ name: 'hip-hop', label: 'Hip-hop', score: COLOUR_FLOOR - 0.01 }], 0, 4)).toEqual([]);
  });

  it('every family colour resolves to a real genre id, so no colour quietly stops asking', () => {
    // Deezer's own genre list, GET https://api.deezer.com/genre, read 2026-11-27.
    const LIVE: [string, number][] = [
      ['Pop', 132], ['Rap/Hip Hop', 116], ['Rock', 152], ['Dance', 113], ['R&B', 165],
      ['Alternative', 85], ['Electro', 106], ['Folk', 466], ['Reggae', 144], ['Jazz', 129],
      ['Classical', 98], ['Films/Games', 173], ['Metal', 464], ['Soul & Funk', 169],
      ['African Music', 2], ['Asian Music', 2092], ['Blues', 153], ['Brazilian Music', 2000],
      ['Indian Music', 2082], ['Kids', 95], ['Latin Music', 197], ['Chill Out/Trip-Hop/Lounge', 84],
      ['Reggaeton', 122], ['Christian', 186],
    ];
    const ids: Record<string, number> = {};
    for (const [name, id] of LIVE) {
      const canon = G.normalize(name);
      if (canon && !(canon in ids)) ids[canon] = id;
      if (!(fold(name) in ids)) ids[fold(name)] = id;
    }
    const unresolved: string[] = [];
    for (const f of FAMILIES as { slug: string; name: string }[]) {
      const fallback = genreLookupName({ name: f.slug, label: f.name, score: 1 });
      const hit = deezerGenreTerms(f.slug, fallback).some((n) => ids[G.normalize(n) ?? ''] ?? ids[fold(n)]);
      if (!hit) unresolved.push(f.slug);
    }
    expect(unresolved).toEqual([]);
  });

  it('names the family for a genre lookup', () => {
    expect(genreLookupName({ name: 'hip-hop', label: 'Hip-hop', score: 1 })).toBe('Hip-hop');
    expect(genreLookupName({ name: 'soul-jazz-funk', label: 'Soul', score: 1 })).toBe('Soul Jazz Funk');
  });
});

describe('an exploration pick must show it fits the mood (GENRE-WORD-001)', () => {
  const sc = (key: string, tier: string, final: number, f: Record<string, number | null>): E.Scored =>
    ({
      track: { key, title: key, artists: [key], tags: [] } as unknown as E.Scored['track'],
      final, affinity: final, coverage: 1, bestSeed: null, perSeed: {}, weightsUsed: {},
      factors: { taste: null, sonicFit: null, genreFit: null, ...f },
      tier, exposurePenalty: 0, reasons: [], uncertainty: [], newRelease: false,
    });

  const ctx = (moodGate: boolean, queueSize = 4): E.Context => ({
    seeds: [], vectors: new Map(), relatedArtists: new Map(),
    feedback: { track: {}, artist: {}, excluded: new Set(), hidden: new Set(), likedArtists: new Set(), events: 0 },
    impressions: {}, settings: { adventurous: 0.5, fresh: false, vocals: 'any' }, now: new Date(0),
    cfg: { ...DEFAULT_ENGINE, queueSize }, moodGate,
  });

  const pool = () => [
    sc('related-a', 'strong', 0.9, { taste: 0.8 }),
    sc('related-b', 'related', 0.8, { taste: 0.7 }),
    sc('expl-blank', 'experimental', 0.85, {}),            // the genre-word song: nothing says it fits
    sc('expl-onmood', 'experimental', 0.5, { sonicFit: 0.8 }),
  ];

  it('holds back an exploration pick with no mood evidence, and keeps the on-mood one', () => {
    const chosen = E.select(pool(), ctx(true, 3)).map((s) => s.track.key);
    expect(chosen).toContain('expl-onmood');
    expect(chosen).not.toContain('expl-blank');
  });

  it('without the gate it is picked, which is the behaviour being fixed', () => {
    expect(E.select(pool(), ctx(false, 3)).map((s) => s.track.key)).toContain('expl-blank');
  });

  it('a held-back pick still fills a short batch rather than leaving the queue empty', () => {
    const only = [sc('expl-blank', 'experimental', 0.85, {}), sc('expl-blank2', 'experimental', 0.8, {})];
    expect(E.select(only, ctx(true, 2)).length).toBe(2);
  });

  it('a known off-mood taste or a wrong sound is held back even with a genre match', () => {
    const xs = [sc('offmood', 'experimental', 0.9, { taste: 0.1, genreFit: 0.9 }), sc('wrongsound', 'experimental', 0.9, { sonicFit: 0.05, genreFit: 0.9 })];
    const keys = E.select([...pool(), ...xs], ctx(true, 6)).map((s) => s.track.key);
    expect(keys.indexOf('offmood')).toBeGreaterThan(keys.indexOf('expl-onmood'));
    expect(keys.indexOf('wrongsound')).toBeGreaterThan(keys.indexOf('expl-onmood'));
  });

  it('a related-tier pick is never gated: this only touches exploration', () => {
    const xs = [sc('related-blank', 'related', 0.95, {})];
    expect(E.select([...xs, ...pool()], ctx(true, 2)).map((s) => s.track.key)).toContain('related-blank');
  });
});
