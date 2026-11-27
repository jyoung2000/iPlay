import { describe, expect, it } from 'vitest';
import type { Track } from './track';
import { COLLAB_FETCH_BUDGET, walkCollaborators, type CreditedTrack } from './collab-walk';

const tr = (key: string, lead: string, credits: { id: string; name: string }[] = []): CreditedTrack =>
  ({ track: { key, artist_ids: { deezer: lead } } as unknown as Track, credits });

/** graph: artist id -> that artist's top tracks. Records every fetch so budget and fanout can be asserted. */
function graph(g: Record<string, CreditedTrack[]>) {
  const calls: string[] = [];
  const fetchTop = async (id: string) => { calls.push(id); return g[id] ?? []; };
  return { calls, fetchTop };
}
const SEED = { id: '1', name: 'Seed' };

describe('walkCollaborators (DISCOVER-BREADTH-001)', () => {
  it('finds one, two and three hops out, each with its path', async () => {
    // B is reached through A's credits, C through B's. t2 credits the seed, so it is never a hit.
    const g = graph({
      '1': [tr('t1', '1', [{ id: '10', name: 'A' }])],
      '10': [tr('t2', '10', [{ id: '1', name: 'Seed' }, { id: '20', name: 'B' }]), tr('t3', '10', [{ id: '10', name: 'A' }])],
      '20': [tr('t4', '20', [{ id: '20', name: 'B' }, { id: '30', name: 'C' }])],
      '30': [tr('t5', '30', [{ id: '30', name: 'C' }])],
    });
    const hits = await walkCollaborators(SEED, g.fetchTop);
    expect(hits.map((h) => [h.track.key, h.hop, h.via])).toEqual([
      ['t3', 1, 'A'],
      ['t4', 2, 'A > B'],
      ['t5', 3, 'A > B > C'],
    ]);
  });

  it('never revisits an artist on a cycle, so the walk ends', async () => {
    const g = graph({
      '1': [tr('t1', '1', [{ id: '10', name: 'A' }])],
      '10': [tr('x', '10', [{ id: '10', name: 'A' }, { id: '20', name: 'B' }])],
      '20': [tr('z', '20', [{ id: '20', name: 'B' }, { id: '10', name: 'A' }])],
    });
    const hits = await walkCollaborators(SEED, g.fetchTop);
    expect(hits.map((h) => h.track.key)).toEqual(['x', 'z']);
    expect(g.calls.filter((id) => id === '10').length).toBe(1);
  });

  it('keeps the seed own songs out and counts a duplicate song once', async () => {
    const g = graph({
      '1': [tr('t1', '1', [{ id: '10', name: 'A' }])],
      '10': [tr('own', '10', [{ id: '1', name: 'Seed' }]), tr('d', '10', [{ id: '10', name: 'A' }]), tr('d', '10', [{ id: '10', name: 'A' }])],
    });
    const hits = await walkCollaborators(SEED, g.fetchTop);
    expect(hits.map((h) => h.track.key)).toEqual(['d']);
  });

  it('finds nothing when no track carries a verified credit to the seed', async () => {
    const g = graph({ '1': [tr('t1', '1', [])] });
    expect(await walkCollaborators(SEED, g.fetchTop)).toEqual([]);
    expect(g.calls).toEqual(['1']);
  });

  it('expands only the fanout of new artists, not every credit', async () => {
    const credited = Array.from({ length: 10 }, (_, i) => ({ id: String(11 + i), name: `X${i}` }));
    const g = graph({ '1': [tr('t1', '1', credited)] });
    await walkCollaborators(SEED, g.fetchTop);
    expect(g.calls.length).toBe(1 + 4);
  });

  it('stops at the fetch budget even when the credit graph is endless', async () => {
    const endless = async (id: string) => [tr(`k${id}`, id, [{ id: `${id}0`, name: `N${id}0` }, { id: `${id}1`, name: `N${id}1` }])];
    const calls: string[] = [];
    const fetchTop = async (id: string) => { calls.push(id); return endless(id); };
    await walkCollaborators(SEED, fetchTop);
    expect(calls.length).toBeLessThanOrEqual(COLLAB_FETCH_BUDGET);
    const small: string[] = [];
    await walkCollaborators(SEED, async (id) => { small.push(id); return endless(id); }, { budget: 3 });
    expect(small.length).toBeLessThanOrEqual(3);
  });

  it('caps how many songs one collaborator adds to a run', async () => {
    const mine = Array.from({ length: 8 }, (_, i) => tr(`s${i}`, '10', [{ id: '10', name: 'A' }]));
    const g = graph({ '1': [tr('t1', '1', [{ id: '10', name: 'A' }])], '10': mine });
    const hits = await walkCollaborators(SEED, g.fetchTop);
    expect(hits.length).toBe(5);
    expect(hits.every((h) => h.hop === 1 && h.via === 'A')).toBe(true);
  });

  it('stops when the check throws (cancellation)', async () => {
    const g = graph({ '1': [tr('t1', '1', [{ id: '10', name: 'A' }])] });
    await expect(walkCollaborators(SEED, g.fetchTop, { check: () => { throw new Error('cancelled'); } })).rejects.toThrow('cancelled');
  });
});
