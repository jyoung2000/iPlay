/**
 * ARTIST-SPREAD-001, end to end: a run with no starting song, over a lopsided catalog, still spreads its picks
 * across the stars, the listening and the taste map — and no artist takes more than maxPerArtist songs.
 */
import { describe, expect, it } from 'vitest';
import * as E from './engine';
import * as G from './genres';
import { coldStarters } from './cold-start';
import { buildMood, moodOrder, moodTags, type MoodCandidate, type MoodSignal } from './mood-queue';

const DAY = 86400;
const now = 1_800_000_000;
const NOW = new Date(Date.UTC(2026, 9, 26));
const NOW_TS = 1_793_000_000.0;
const row = (kind: string, artist: string, daysAgo = 1) => ({ kind, ts: now - daysAgo * DAY, track: { artists: [artist] } });

const seed = { key: 's', title: 's', artists: ['Seed'], tags: [], provenance: [], release: null, bpm: null } as E.Track;
const ctx = (): E.Context => ({
  seeds: [seed],
  vectors: new Map(),
  relatedArtists: new Map(),
  feedback: E.buildFeedback([], NOW_TS),
  impressions: {},
  settings: E.normaliseSettings({ fresh: false, adventurous: 0.5 }),
  now: NOW,
});
const song = (key: string, artist: string): E.Track => ({
  key,
  title: key,
  artists: [artist],
  tags: [G.makeTag('pop', 'Deezer', 'album', 0.9)!],
  provenance: [{ seed: 's', relation: 'radio', rank: 0 }],
  release: null,
  bpm: null,
});

describe('ARTIST-SPREAD-001 end to end over a lopsided catalog', () => {
  it('a cold run with many stars still picks from stars, listening and taste, within the artist cap', () => {
    const stars = ['Star A', 'Star B', 'Star C', 'Star D', 'Star E', 'Star F', 'Star G', 'Star H'];
    const rows = [row('like', 'Summrs'), row('save', 'Nettspend'), row('replay', 'Ken Carson')];
    const colours = [
      { name: 'rage', label: 'Rage', score: 0.9 },
      { name: 'drill', label: 'Drill', score: 0.8 },
    ];
    const starters = coldStarters({ starred: stars, rows, colours, now }, 6);
    expect(starters.length).toBeGreaterThanOrEqual(3);

    // Lopsided: the first starter and an unrelated heavy artist each have 30 songs; everyone else has 3.
    const cands: E.Track[] = [];
    starters.forEach((s, i) => {
      const n = i === 0 ? 30 : 3;
      for (let j = 0; j < n; j++) cands.push(song(`${s.name}-${j}`, s.name));
    });
    for (let j = 0; j < 30; j++) cands.push(song(`heavy-${j}`, 'Heavy Artist'));

    const q = E.recommend(cands, ctx());
    const per: Record<string, number> = {};
    for (const s of q) {
      const p = E.primaryArtist(s.track);
      per[p] = (per[p] ?? 0) + 1;
    }
    const distinct = Object.keys(per);
    // The engine keys artists in lower case; compare the starter names the same way.
    const inQueue = new Set(distinct.map((d) => d.toLowerCase()));

    expect(Math.max(...Object.values(per))).toBeLessThanOrEqual(2);
    expect(distinct.length).toBeGreaterThanOrEqual(5);
    for (const from of ['starred', 'listening', 'taste'] as const) {
      const names = starters.filter((s) => s.from === from).map((s) => s.name.toLowerCase());
      if (!names.length) continue;
      expect(names.some((n) => inQueue.has(n)), `no ${from} artist in the queue`).toBe(true);
    }
  });
});

describe('ARTIST-SPREAD-001 with the live mood on', () => {
  it('the mood reorders the recommend() spread: same songs, same per-artist counts, a rap track leads', () => {
    const RAP = moodTags({ genres: ['Rap'] });
    const JAZZ = moodTags({ genres: ['Smooth jazz'] });
    // Rap A has three times the songs the cap allows, so recommend() must trim it before the mood sees the list.
    const pool: E.Track[] = [];
    const genreOf = new Map<string, typeof RAP>();
    for (const artist of ['Jazz A', 'Jazz B', 'Rap A', 'Rap B', 'Rap C']) {
      const n = artist === 'Rap A' ? 6 : 2;
      for (let j = 0; j < n; j++) {
        const t = song(`${artist}-${j}`, artist);
        pool.push(t);
        genreOf.set(t.key, artist.startsWith('Rap') ? RAP : JAZZ);
      }
    }
    const scored = E.recommend(pool, ctx());
    const countBy = (keys: string[], artistOf: (k: string) => string) => {
      const per: Record<string, number> = {};
      for (const k of keys) per[artistOf(k)] = (per[artistOf(k)] ?? 0) + 1;
      return per;
    };
    const artistByKey = new Map(pool.map((t) => [t.key, t.artists[0]!] as const));
    const before = countBy(scored.map((s) => s.track.key), (k) => artistByKey.get(k)!);
    expect(Math.max(...Object.values(before))).toBeLessThanOrEqual(2);

    const cands: MoodCandidate[] = scored.map((s) => ({
      key: s.track.key,
      tags: genreOf.get(s.track.key)!,
      vector: null,
      artist: artistByKey.get(s.track.key)!,
      score: s.final,
    }));
    const listening: MoodSignal[] = [
      { key: 'r1', kind: 'complete', ts: now - 600, tags: RAP, vector: null },
      { key: 'r2', kind: 'complete', ts: now - 200, tags: RAP, vector: null },
    ];
    const mood = buildMood(listening, now);
    const plan = moodOrder(cands, mood, 0.5);

    expect(mood.leading).toContain('Rap');
    expect([...plan.order].sort()).toEqual(cands.map((c) => c.key).sort());
    const after = countBy(plan.order, (k) => artistByKey.get(k)!);
    expect(after).toEqual(before);
    if (cands[0]!.tags === JAZZ) {
      expect(plan.changed).toBe(true);
      expect(genreOf.get(plan.order[0]!)).toBe(RAP);
    }
  });
});
