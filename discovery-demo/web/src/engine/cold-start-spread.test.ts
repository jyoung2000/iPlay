/**
 * ARTIST-SPREAD-001 (owner rule NEXT-FAV-COLD-001: stars first): a run with no starting song takes the listener's
 * stars first, but keeps one listening slot and one taste slot for the other two sources whenever they have an
 * artist the stars do not already cover.
 */
import { describe, expect, it } from 'vitest';
import { coldReason, coldStarters, type ColdStarter } from './cold-start';

const DAY = 86400;
const now = 1_800_000_000;
const row = (kind: string, artist: string, daysAgo = 1) => ({ kind, ts: now - daysAgo * DAY, track: { artists: [artist] } });
const colours = [
  { name: 'rage', label: 'Rage', score: 0.9 },
  { name: 'drill', label: 'Drill', score: 0.8 },
];
const stars = ['Star One', 'Star Two', 'Star Three', 'Star Four', 'Star Five', 'Star Six', 'Star Seven', 'Star Eight'];
const listened = [row('like', 'Summrs'), row('save', 'Nettspend'), row('replay', 'Ken Carson')];
const from = (s: readonly ColdStarter[]) => s.map((x) => x.from);

describe('ARTIST-SPREAD-001: stars first, one reserved slot each for listening and taste', () => {
  it('eight stars with a listening and a taste artist: four stars, then one listening, then one taste', () => {
    const starters = coldStarters({ starred: stars, rows: listened, colours, now }, 6);
    expect(from(starters)).toEqual(['starred', 'starred', 'starred', 'starred', 'listening', 'taste']);
    expect(starters.slice(0, 4).map((s) => s.name)).toEqual(stars.slice(0, 4));
    expect(starters[4]?.name).toBe('Summrs');
  });

  it('with no listening artists, the taste slot is still reserved and the stars take the rest', () => {
    const starters = coldStarters({ starred: stars, rows: [], colours, now }, 6);
    expect(from(starters)).toEqual(['starred', 'starred', 'starred', 'starred', 'starred', 'taste']);
  });

  it('with no taste colours, the listening slot is reserved and the stars take the rest', () => {
    const starters = coldStarters({ starred: stars, rows: listened, colours: [], now }, 6);
    expect(from(starters)).toEqual(['starred', 'starred', 'starred', 'starred', 'starred', 'listening']);
  });

  it('with no stars, listening and taste share the start and nothing is labelled starred', () => {
    const starters = coldStarters({ starred: [], rows: listened, colours, now }, 6);
    expect(starters.some((s) => s.from === 'listening')).toBe(true);
    expect(starters.some((s) => s.from === 'taste')).toBe(true);
    expect(starters.some((s) => s.from === 'starred')).toBe(false);
  });

  it('a listened artist that is already a star does not take the reserved listening slot', () => {
    const starred = ['Summrs', 'Nettspend', 'Ken Carson', 'Star One', 'Star Two', 'Star Three', 'Star Four', 'Star Five'];
    const starters = coldStarters({ starred, rows: listened, colours, now }, 6);
    expect(starters.some((s) => s.from === 'listening')).toBe(false);
    expect(from(starters)).toEqual(['starred', 'starred', 'starred', 'starred', 'starred', 'taste']);
  });

  it('matches case-insensitively: a starred name heard in another case is not repeated or reserved again', () => {
    const starters = coldStarters({ starred: ['summrs', 'Star One'], rows: [row('like', 'Summrs'), row('save', 'Nettspend')], colours: [], now }, 6);
    expect(starters).toEqual([
      { name: 'summrs', from: 'starred' },
      { name: 'Star One', from: 'starred' },
      { name: 'Nettspend', from: 'listening' },
    ]);
  });

  it('thin history: one star, one listened artist and one colour still starts from every source it has', () => {
    const starters = coldStarters({ starred: ['Star One'], rows: [row('like', 'Summrs')], colours: [colours[0]!], now }, 6);
    expect(starters[0]).toEqual({ name: 'Star One', from: 'starred' });
    expect(starters[1]).toEqual({ name: 'Summrs', from: 'listening' });
    expect(starters.some((s) => s.from === 'taste')).toBe(true);
    expect(new Set(starters.map((s) => s.name.toLowerCase())).size).toBe(starters.length);
  });

  it('a target of one keeps the first star and drops the reserved picks', () => {
    expect(from(coldStarters({ starred: stars, rows: listened, colours, now }, 1))).toEqual(['starred']);
  });

  it('a target of two keeps one star and the listening pick, not the taste pick', () => {
    expect(from(coldStarters({ starred: stars, rows: listened, colours, now }, 2))).toEqual(['starred', 'listening']);
  });

  it('never repeats a name, even when a star is also heard or a colour names a star', () => {
    const starters = coldStarters({ starred: ['Summrs', 'Star One'], rows: listened, colours, now }, 6);
    const keys = starters.map((s) => s.name.toLowerCase());
    expect(new Set(keys).size).toBe(keys.length);
  });
});

describe('ARTIST-SPREAD-001: the reason names the sources it really used', () => {
  it('stars plus taste says "your sound", not "your listening"', () => {
    expect(coldReason([{ name: 'Star One', from: 'starred' }, { name: 'Nettspend', from: 'taste' }]))
      .toBe('Started from your starred artists and your sound: Star One and Nettspend.');
  });

  it('all three sources are named', () => {
    expect(coldReason([
      { name: 'Star One', from: 'starred' },
      { name: 'Summrs', from: 'listening' },
      { name: 'Nettspend', from: 'taste' },
    ])).toBe('Started from your starred artists, your listening and your sound: Star One, Summrs and Nettspend.');
  });

  it('names a source used past the first three starters, not only the first three', () => {
    const starters: ColdStarter[] = [
      { name: 'Star One', from: 'starred' },
      { name: 'Star Two', from: 'starred' },
      { name: 'Star Three', from: 'starred' },
      { name: 'Star Four', from: 'starred' },
      { name: 'Summrs', from: 'listening' },
      { name: 'Nettspend', from: 'taste' },
    ];
    expect(coldReason(starters)).toBe(
      'Started from your starred artists, your listening and your sound: Star One, Star Two and Star Three.',
    );
  });
});
