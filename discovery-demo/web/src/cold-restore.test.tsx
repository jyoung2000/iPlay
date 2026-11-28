// @vitest-environment happy-dom
/**
 * COLD-RESTORE-001 (owner, 2026-11-28: "make the app show the restored song at cold start"). After the app process
 * restarts, the service puts the saved queue back paused and the car already shows that song. The page asks the
 * engine (`hello`) as it mounts, before its own queue has loaded, so the song could not be found and the phone said
 * "Nothing playing" while the car showed a song. The page must show the restored song, paused, once it can - and it
 * must only SHOW it: no load, no play, nothing counted as a new listen.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { App } from './App';
import { api, type PlaylistSummary, type RecItem, type RecResult, type Track } from './api';

vi.mock('./api', async (orig) => {
  const actual = await orig<typeof import('./api')>();
  return { ...actual, api: { ...actual.api,
    seeds: vi.fn().mockResolvedValue({ seeds: [] }), settings: vi.fn().mockResolvedValue(null),
    feedbackState: vi.fn(), feedback: vi.fn().mockResolvedValue({ recorded: true }),
    last: vi.fn(), playlists: vi.fn(), impressions: vi.fn().mockResolvedValue({ recorded: 0 }),
    rerank: vi.fn(), mood: vi.fn(), health: vi.fn(() => new Promise(() => {})), status: vi.fn(() => new Promise(() => {})),
    carContext: vi.fn(() => new Promise(() => {})),
  }};
});

const track = (key: string, title: string) => ({ key, title, artists: ['Test artist'], provider: 'deezer', platform_id: key, tags: [] } as unknown as Track);
const item = (key: string, title: string) => ({ track: track(key, title), rank: 1, reasons: [], playback: { open: [], preview: null, embed: null }, characteristics: { genres: [] } } as unknown as RecItem);
const result = { items: [item('a', 'Alpha song'), item('b', 'Bravo song')], count: 2, asked: 2, phase: 'final', sources: {}, transfer: {} } as unknown as RecResult;

type Cmd = { op: string; [k: string]: unknown };
let sent: Cmd[] = [];
let snapshot: Record<string, unknown> = {};
const restored = (over: Record<string, unknown> = {}) => ({
  rev: 1, id: 'b', mediaId: 'b', playing: false, playWhenReady: false, buffering: false, waiting: false, ended: false,
  error: null, positionMs: 61000, durationMs: 180000, blending: null, pending: [], ...over,
});
const player = () => document.querySelector('.np');
const tick = (ms = 20) => new Promise((r) => setTimeout(r, ms));

beforeEach(() => {
  vi.clearAllMocks();
  sent = [];
  snapshot = restored();
  // The engine answers `hello` at once, as the service does: the queue it restored, paused.
  (window as unknown as { AirwaveNative: unknown }).AirwaveNative = {
    engine: (json: string) => {
      const cmd = JSON.parse(json) as Cmd;
      sent.push(cmd);
      if (cmd.op === 'hello') setTimeout(() => (window as unknown as { __awEngine: (j: string) => void }).__awEngine(JSON.stringify(snapshot)), 0);
    },
  };
  vi.mocked(api.feedbackState).mockResolvedValue({ state: {}, events: 0, taste: null } as never);
  vi.mocked(api.playlists).mockResolvedValue({ lists: [] } as never);
});
afterEach(() => {
  cleanup();
  delete (window as unknown as { AirwaveNative?: unknown }).AirwaveNative;
});

describe('cold start with a queue the engine restored (COLD-RESTORE-001)', () => {
  it('shows the restored song once the queue loads, even though the engine answered before it did', async () => {
    let release: (r: RecResult) => void = () => undefined;
    vi.mocked(api.last).mockReturnValue(new Promise<RecResult>((r) => { release = r; }));
    render(<App />);
    await waitFor(() => expect(sent.some((c) => c.op === 'hello')).toBe(true));
    await tick();
    // The engine's answer is in, but the queue is not: nothing to show yet.
    expect(player()?.textContent).toMatch(/Nothing playing/);

    release(result);
    await screen.findAllByText('Alpha song');
    await waitFor(() => expect(player()?.textContent ?? '').not.toMatch(/Nothing playing/));
    expect(player()?.textContent).toContain('Bravo song');
  });

  it('only shows it: the engine is never told to load or play, so the car stays paused where it was', async () => {
    let release: (r: RecResult) => void = () => undefined;
    vi.mocked(api.last).mockReturnValue(new Promise<RecResult>((r) => { release = r; }));
    render(<App />);
    await waitFor(() => expect(sent.some((c) => c.op === 'hello')).toBe(true));
    await tick();
    release(result);
    await waitFor(() => expect(player()?.textContent).toContain('Bravo song'));
    // Shown paused: the transport offers Play, not Pause.
    expect(screen.getByRole('button', { name: 'Play' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();
    await tick(60);
    expect(sent.filter((c) => c.op === 'load' || c.op === 'play')).toEqual([]);
    // A song put back is not a song chosen: it never asks the service for a car context either.
    expect(api.carContext).not.toHaveBeenCalled();
  });

  it('also shows it when the queue is already there as the engine answers', async () => {
    vi.mocked(api.last).mockResolvedValue(result);
    render(<App />);
    await waitFor(() => expect(player()?.textContent).toContain('Bravo song'));
    await tick(60);
    expect(sent.filter((c) => c.op === 'load' || c.op === 'play')).toEqual([]);
  });

  it('restores a song from a playlist by its scoped media id, paused, without a load', async () => {
    // The engine's id is the bare song key; its media id carries the playlist the song was playing from.
    snapshot = restored({ id: 'z', mediaId: 'playlist/L1/track/z' });
    const mix = { id: 'L1', name: 'Mix', created: 0, count: 1, unplayed: 1, discovery_ready: true,
      items: [{ key: 'z', track: track('z', 'Zulu song') }] } as unknown as PlaylistSummary;
    vi.mocked(api.playlists).mockResolvedValue({ lists: [mix] } as never);
    vi.mocked(api.last).mockResolvedValue(result);
    render(<App />);
    await waitFor(() => expect(player()?.textContent).toContain('Zulu song'));
    expect(screen.getByRole('button', { name: 'Play' })).toBeTruthy();
    await tick(60);
    expect(sent.filter((c) => c.op === 'load' || c.op === 'play')).toEqual([]);
  });
});
