/**
 * DISCOVER-BREADTH-001/002: select() keeps a share of the batch for distinct new artists who fit the mood and have
 * one positive size, genre or sound signal; changes nothing when the breadth option is absent.
 */
import { describe, expect, it } from 'vitest';
import * as E from './engine';
import { DEFAULT_ENGINE } from './config';
import { breadthFor, breadthShare, familiarArtists, BREADTH_MIN_TASTE } from './breadth';
import { collabSeedBudget, COLLAB_RUN_BUDGET, COLLAB_FETCH_BUDGET } from './collab-walk';

const fb0 = { artist: {} as Record<string, number>, likedArtists: new Set<string>() };
const BR = { familiar: new Set<string>(), share: 0.4, minTaste: BREADTH_MIN_TASTE };

type Ev = { taste?: number | null; size?: number | null; genre?: number | null; sonic?: number | null };

function sc(key: string, artist: string, final: number, ev: Ev = {}): E.Scored {
  return {
    track: { key, title: key, artists: [artist], tags: [] } as unknown as E.Scored['track'],
    final, affinity: final, coverage: 1, bestSeed: null, perSeed: {}, weightsUsed: {},
    factors: {
      taste: ev.taste ?? null, size: ev.size ?? null, genreFit: ev.genre ?? null, sonicFit: ev.sonic ?? null,
    },
    tier: 'strong', exposurePenalty: 0, reasons: [], uncertainty: [], newRelease: false,
  };
}

function ctx(extra: Partial<E.Context> = {}, queueSize = 10): E.Context {
  return {
    seeds: [], vectors: new Map(), relatedArtists: new Map(),
    feedback: { track: {}, artist: {}, excluded: new Set(), hidden: new Set(), likedArtists: new Set(), events: 0 },
    impressions: {}, settings: { adventurous: 0.5, fresh: false, vocals: 'any' }, now: new Date(0),
    cfg: { ...DEFAULT_ENGINE, queueSize }, ...extra,
  };
}

/** Five starred artists (familiar) with two strong songs each, plus six new artists scoring lower. */
function pool(newEv: Ev = { taste: 0.7, size: 0.9 }): E.Scored[] {
  const out: E.Scored[] = [];
  for (let i = 0; i < 5; i++) for (let j = 0; j < 2; j++) out.push(sc(`f${i}-${j}`, `Star ${i}`, 0.9 - i * 0.01 - j * 0.001, { taste: 0.8, size: 0.9, genre: 0.5, sonic: 0.6 }));
  for (let i = 0; i < 6; i++) out.push(sc(`n${i}`, `New ${i}`, 0.5 - i * 0.01, newEv));
  return out;
}
const FAMILIAR = familiarArtists([0, 1, 2, 3, 4].map((i) => ({ artists: [`Star ${i}`] })), fb0);
const artistOf = (s: E.Scored) => E.primaryArtist(s.track);
const newArtistsIn = (xs: E.Scored[]) => new Set(xs.map(artistOf).filter((a) => !FAMILIAR.has(a)));
const newCount = (xs: E.Scored[]) => newArtistsIn(xs).size;
const withBreadth = () => ctx({ breadth: { ...BR, familiar: FAMILIAR } });

describe('select keeps room for new artists that fit the mood (DISCOVER-BREADTH-001)', () => {
  it('without breadth, the starred artists fill the batch on raw score (golden path unchanged)', () => {
    expect(newCount(E.select(pool(), ctx()))).toBe(0);
  });

  it('with breadth, about the share of the batch goes to distinct new artists', () => {
    const chosen = E.select(pool(), withBreadth());
    expect(chosen.length).toBe(10);
    expect(newCount(chosen)).toBe(4);
    expect(chosen.some((s) => FAMILIAR.has(artistOf(s)))).toBe(true);
  });

  it('an off-mood new artist never takes a reserved slot, even at the same size', () => {
    const chosen = E.select(pool({ taste: 0.2, size: 0.9 }), withBreadth());
    expect(newCount(chosen)).toBe(0);
    expect(chosen.length).toBe(10);
  });

  it('an on-mood new artist too far in size, with no genre or sound match, gets no reserved slot', () => {
    const chosen = E.select(pool({ taste: 0.7, size: 0.1 }), withBreadth());
    expect(newCount(chosen)).toBe(0);
  });

  it('a new artist with no evidence at all gets no reserved slot, and the batch still fills', () => {
    const chosen = E.select(pool({}), withBreadth());
    expect(newCount(chosen)).toBe(0);
    expect(chosen.length).toBe(10);
  });

  it('a related genre with a strong sound fit qualifies', () => {
    expect(newCount(E.select(pool({ taste: 0.7, genre: 0.4, sonic: 0.9 }), withBreadth()))).toBe(4);
  });

  it('a same-genre song that sounds wrong for the mood is kept out of reserved slots', () => {
    expect(newCount(E.select(pool({ taste: 0.7, genre: 0.9, sonic: 0.1 }), withBreadth()))).toBe(0);
  });

  it('a close size alone qualifies, and an unknown size does not block a strong sound', () => {
    expect(newCount(E.select(pool({ size: 0.9 }), withBreadth()))).toBe(4);
    expect(newCount(E.select(pool({ size: null, sonic: 0.8 }), withBreadth()))).toBe(4);
  });

  it('gives no reserved slot to a new artist the taste reading says is off-mood, even with close size and sound', () => {
    expect(newCount(E.select(pool({ taste: 0.2, size: 0.9, sonic: 0.9 }), withBreadth()))).toBe(0);
  });

  it('treats no taste reading as fit, so a new listener still gets new artists', () => {
    expect(newCount(E.select(pool({ taste: null, size: 0.9 }), withBreadth()))).toBe(4);
  });

  it('counts a second song by the same new artist once and keeps the per-artist cap', () => {
    const xs = pool();
    for (let j = 0; j < 4; j++) xs.push(sc(`n0-extra${j}`, 'New 0', 0.6 - j * 0.01, { taste: 0.7, size: 0.9 }));
    const chosen = E.select(xs, withBreadth());
    const per: Record<string, number> = {};
    for (const s of chosen) per[artistOf(s)] = (per[artistOf(s)] ?? 0) + 1;
    expect(Math.max(...Object.values(per))).toBeLessThanOrEqual(DEFAULT_ENGINE.maxPerArtist);
    expect(newCount(chosen)).toBeGreaterThanOrEqual(4);
  });

  it('fills the rest as before when there are too few qualifying new artists', () => {
    const xs = pool().filter((s) => !s.track.key.startsWith('n') || s.track.key === 'n0');
    const chosen = E.select(xs, withBreadth());
    expect(chosen.length).toBe(10);
    expect(newCount(chosen)).toBe(1);
  });

  it('skips a new artist pushed down for being like a removed song', () => {
    const xs = pool().map((s) => (s.track.key.startsWith('n') ? { ...s, removedNear: true } : s));
    expect(newCount(E.select(xs, withBreadth()))).toBe(0);
  });

  it('batch: the reserved picks are distinct new artists with a demonstrable sound or size fit, first in order', () => {
    const xs = pool({ taste: 0.7, size: 0.8 });
    xs.push(sc('sonic-hit', 'Sonic A', 0.3, { taste: 0.7, sonic: 0.95 }));
    const chosen = E.select(xs, withBreadth());
    const reserved = chosen.slice(0, 4).filter((s) => !FAMILIAR.has(artistOf(s)));
    expect(new Set(reserved.map(artistOf)).size).toBe(reserved.length);
    expect(reserved.every((s) => (s.factors['size'] ?? 0) >= 0.6 || (s.factors['sonicFit'] ?? 0) >= 0.5)).toBe(true);
  });

  it('is deterministic', () => {
    const c = withBreadth();
    expect(E.select(pool(), c).map((s) => s.track.key)).toEqual(E.select(pool(), c).map((s) => s.track.key));
  });
});

describe('the sound and size evidence reaches the ranker (DISCOVER-BREADTH-002)', () => {
  const tr = (key: string, artist: string): E.Track =>
    ({ key, title: key, artists: [artist], tags: [], provenance: [], release: null, bpm: null }) as unknown as E.Track;

  it('a close sound gets a sonicFit, a far one gets 0, and no vector gets none', () => {
    const seed = tr('seed', 'Seed A');
    const vectors = new Map<string, Float64Array>([
      ['seed', Float64Array.from([1, 0])],
      ['close', Float64Array.from([0.9, 0.436])],
      ['far', Float64Array.from([0, 1])],
    ]);
    const c = ctx({ seeds: [seed] as never, vectors });
    expect(E.scoreOne(tr('close', 'X'), c).factors['sonicFit']).toBeCloseTo(1, 2);
    expect(E.scoreOne(tr('far', 'Y'), c).factors['sonicFit']).toBeCloseTo(0, 2);
    expect(E.scoreOne(tr('none', 'Z'), c).factors['sonicFit']).toBeUndefined();
  });

  it('an unknown artist size writes no size factor, and a known one does', () => {
    const seed = tr('seed', 'Seed A');
    const unknown = E.scoreOne(tr('a', 'Ken'), ctx({ seeds: [seed] as never, artistFit: () => null }));
    expect(unknown.factors['size']).toBeUndefined();
    const known = E.scoreOne(tr('a', 'Ken'), ctx({ seeds: [seed] as never, artistFit: () => 0.3 }));
    expect(known.factors['size']).toBe(0.3);
  });
});

describe('breadth settings', () => {
  it('grows with the Adventurous slider', () => {
    expect(breadthShare(0)).toBeCloseTo(0.25);
    expect(breadthShare(0.5)).toBeCloseTo(0.4);
    expect(breadthShare(1)).toBeCloseTo(0.55);
    expect(breadthShare(Number.NaN)).toBeCloseTo(0.4);
  });

  it('counts starting-song artists and artists the listening leans toward as familiar, not disliked ones', () => {
    const f = familiarArtists([{ artists: ['Ken Carson feat. Destroy Lonely'] }], { artist: { 'liked one': 2, 'skipped one': -1 }, likedArtists: new Set(['liked one']) });
    expect(f.has(E.primaryArtist({ artists: ['Ken Carson'] }))).toBe(true);
    expect(f.has(E.primaryArtist({ artists: ['Destroy Lonely'] }))).toBe(true);
    expect(f.has('liked one')).toBe(true);
    expect(f.has('skipped one')).toBe(false);
    const g = familiarArtists([], { artist: { 'Ken Carson': 2 }, likedArtists: new Set(['Playboi Carti']) });
    expect(g.has(E.primaryArtist({ artists: ['ken carson'] }))).toBe(true);
    expect(g.has(E.primaryArtist({ artists: ['PLAYBOI CARTI'] }))).toBe(true);
    expect(breadthFor([], fb0, 0.5).minTaste).toBe(BREADTH_MIN_TASTE);
  });
});

describe('the collaborator budget is shared fairly across starting songs', () => {
  it('gives every seed a slice instead of letting the first stars use it all', () => {
    expect(collabSeedBudget(1)).toBe(COLLAB_FETCH_BUDGET);
    expect(collabSeedBudget(3)).toBe(10);
    expect(collabSeedBudget(10)).toBe(3);
    expect(collabSeedBudget(40)).toBe(2);
    expect(collabSeedBudget(10) * 10).toBeLessThanOrEqual(COLLAB_RUN_BUDGET);
  });

  it('ranks a third-hand collaborator and names the hop honestly', () => {
    expect(E.RELATION['collab_3']![2]).toBe(true);
    expect(E.RELATION['collab_3']![1]).toMatch(/three/);
    expect(E.RELATION['collab_1']![0]).toBeGreaterThan(E.RELATION['collab_2']![0]);
    expect(E.RELATION['collab_2']![0]).toBeGreaterThan(E.RELATION['collab_3']![0]);
  });
});
