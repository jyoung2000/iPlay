// @vitest-environment happy-dom
/**
 * PLAYLIST-ROOM-001 (owner, 2026-11-28): "ensure the playlist ui and ux is tall enough and works and has
 * similar ui and ux to the discover now playing song queue".
 *
 * Measured in the running app on a 412 x 839 phone, an open playlist put its songs at y=694: ONE row on
 * screen, under 645 px of header (the list's action bar, its Sound card and its availability card). The
 * rows themselves were already the Discover queue's shape, so what was wrong was the room above them.
 *
 * The songs now come FIRST in the list's own section, and the two context cards (the list's sound, and what
 * is downloaded) follow them. Both are still there, and still for the open list; they just no longer stand
 * between the listener and the songs. happy-dom has no layout, so the contract asserted here is document
 * ORDER, which is what decides the offset; the pixel offset is re-measured on the device.
 *
 * PLAYLIST-PLAYED-001 (same message): a playlist on a phone never showed which songs had been played, while
 * the Discover queue does. The per-song Played / Not played marker now stays at every width.
 */
import { afterEach, expect, it, vi } from 'vitest';
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import type * as Api from './api';
import { SavedView } from './SavedView';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const listsCss = readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'saved-lists.css'), 'utf8');

const mixFor = vi.fn();
vi.mock('./api', async (orig) => {
  const real = await orig<typeof Api>();
  return { ...real, api: { ...real.api, playlistMix: (id: string) => mixFor(id) } };
});

Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
let root: Root | undefined;
afterEach(async () => {
  if (root) await act(() => root!.unmount());
  root = undefined;
  document.body.innerHTML = '';
  localStorage.clear();
  mixFor.mockReset();
});

const track = (n: number) => ({ key: `k${n}`, title: `Song ${n}`, artist: `Artist ${n}`, artists: [`Artist ${n}`], provider: 'deezer', platform_id: `${n}`, tags: [] });
const list = {
  id: 'L1', name: 'Late night', count: 2, unplayed: 1, discovery_ready: false,
  items: [{ key: 'k1', played: true }, { key: 'k2', played: false }],
};
const taste = {
  events: 2, positive: 2, skips: 0,
  genres: [{ name: 'r&b', score: 2, plays: 2, skips: 0, saves: 2, likes: 0, sound: 0 }],
  artists: [], audio: {}, top: { genre: 'r&b', artist: null }, mood: {}, era: null,
};

async function openList() {
  const el = document.createElement('div');
  document.body.append(el);
  root = createRoot(el);
  await act(() =>
    root!.render(
      <SavedView
        saved={[{ track: track(1), ts: 1 }, { track: track(2), ts: 2 }] as never}
        lists={[list] as never}
        onLists={() => undefined}
        onPreview={() => undefined}
        onRemoveSaved={() => undefined}
      />,
    ),
  );
  const chip = [...document.querySelectorAll('button')].find((b) => b.textContent?.includes('Late night'));
  await act(async () => chip!.click());
  await act(async () => undefined);
}

/** Index of a node among the Saved view's own blocks, so "before" and "after" compare without layout. */
function orderOf(sel: string): number {
  const view = document.querySelector('section[aria-labelledby="saved-h"]');
  const el = document.querySelector(sel);
  if (!view || !el) return -1;
  return [...view.children].findIndex((child) => child === el || child.contains(el));
}

it('puts an open list\u2019s songs above its context cards, so the songs get the screen', { timeout: 20000 }, async () => {
  mixFor.mockResolvedValue({ id: 'L1', name: 'Late night', taste, songs: 2, placed: 2 });
  await openList();
  const songs = orderOf('ul.tracks--saved');
  const sound = orderOf('section.sound');
  const availability = orderOf('.list-availability, .list-download');
  expect(songs, 'the song list should be in the Saved section').toBeGreaterThan(-1);
  expect(sound, 'the list\u2019s Sound card should still be rendered').toBeGreaterThan(-1);
  expect(songs, 'songs must come before the Sound card').toBeLessThan(sound);
  if (availability > -1) expect(songs, 'songs must come before the availability card').toBeLessThan(availability);
});

it('still shows the open list\u2019s own sound and its song count', { timeout: 20000 }, async () => {
  mixFor.mockResolvedValue({ id: 'L1', name: 'Late night', taste, songs: 2, placed: 2 });
  await openList();
  expect(mixFor).toHaveBeenCalledWith('L1');
  const card = document.querySelector('section.sound[aria-label="Sound of \u201cLate night\u201d"]');
  expect(card).toBeTruthy();
  expect(card!.textContent).toContain("This list's mix");
});

it('shows every song\u2019s played state in a list, as the Discover queue does', { timeout: 20000 }, async () => {
  mixFor.mockResolvedValue({ id: 'L1', name: 'Late night', taste, songs: 2, placed: 2 });
  await openList();
  const marks = [...document.querySelectorAll('ul.tracks--saved .track__played')].map((m) => m.textContent?.trim());
  expect(marks).toEqual(['Played', 'Not played']);
});

it('never hides the played marker on a narrow phone', () => {
  // The old rule dropped it below 480px, so a phone playlist showed no play state at all.
  const hidden = /\.tracks--saved \.track__played \{[^}]*display:\s*none/.test(listsCss);
  expect(hidden, 'the per-song played marker must not be display:none at any width').toBe(false);
});

it('gives the banner\u2019s Bring back button a 44px target', () => {
  expect(listsCss).toMatch(/\.banner--list > \.push \{[^}]*min-height: 44px;/);
});
