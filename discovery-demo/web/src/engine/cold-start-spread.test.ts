/**
 * ARTIST-SPREAD-001: a run with no starting song draws from every source it has. Stars lead, but a long list
 * of stars must not leave the listening and taste sources without a turn.
 */
import { describe, expect, it } from 'vitest';
import { coldReason, coldStarters } from './cold-start';

const DAY = 86400;
const now = 1_800_000_000;
const row = (kind: string, artist: string, daysAgo = 1) => ({ kind, ts: now - daysAgo * DAY, track: { artists: [artist] } });
const colours = [
  { name: 'rage', label: 'Rage', score: 0.9 },
  { name: 'drill', label: 'Drill', score: 0.8 },
];
const stars = ['Star One', 'Star Two', 'Star Three', 'Star Four', 'Star Five', 'Star Six', 'Star Seven', 'Star Eight'];
const listened = [row('like', 'Summrs'), row('save', 'Nettspend'), row('replay', 'Ken Carson')];

describe('ARTIST-SPREAD-001: every source gets a turn', () => {
  it('eight stars still leave room for listening and taste in a six-artist start', () => {
    const starters = coldStarters({ starred: stars, rows: listened, colours, now }, 6);
    expect(starters).toHaveLength(6);
    expect(starters.some((s) => s.from === 'listening')).toBe(true);
    expect(starters.some((s) => s.from === 'taste')).toBe(true);
  });

  it('stars lead, then listening, then taste, one turn each per lap', () => {
    const starters = coldStarters({ starred: stars, rows: listened, colours, now }, 6);
    expect(starters.map((s) => s.from)).toEqual(['starred', 'listening', 'taste', 'starred', 'listening', 'taste']);
  });

  it('with no stars, listening and taste share the start', () => {
    const starters = coldStarters({ starred: [], rows: listened, colours, now }, 6);
    expect(starters.some((s) => s.from === 'listening')).toBe(true);
    expect(starters.some((s) => s.from === 'taste')).toBe(true);
    expect(starters.some((s) => s.from === 'starred')).toBe(false);
  });

  it('never repeats a name, even when a star is also heard or a colour names a star', () => {
    const starters = coldStarters(
      { starred: ['Summrs', 'Star One'], rows: listened, colours, now },
      6,
    );
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
});
