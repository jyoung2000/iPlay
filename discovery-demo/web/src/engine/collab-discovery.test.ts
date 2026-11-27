/**
 * DISCOVER-BREADTH-001: the credit walk runs inside gather and its songs reach the candidates with
 * collab_* provenance and the collaborator path as `via`. Fake HTTP, no network.
 */
import { describe, expect, it } from 'vitest';
import * as E from './engine';
import { DEFAULT_ENGINE } from './config';
import { Deezer } from './deezer';
import { Http, HostPacer, type HttpPort, type HttpRequest } from './net';
import { Job, Pipeline } from './pipeline';
import { Store, MemoryBackend } from './store';

const enc = (s: string) => new TextEncoder().encode(s);
const dzTrack = (id: number, title: string, lead: { id: number; name: string }, contributors: { id: number; name: string }[]) => ({
  id, title, title_short: title, duration: 170, artist: lead, contributors, album: { id: id * 3, title: `${title} - Single`, cover_medium: 'c' },
  preview: `https://p/${id}.mp3`, readable: true, rank: 500000, link: `https://www.deezer.com/track/${id}`,
});

// Starting artist 100 (the seed). Artist 200 shares a credit with 100 on a song; artist 300 shares a
// credit with 200 on one of 200's songs, so it is a second-hand collaborator of the seed.
const ARTISTS: Record<string, unknown[]> = {
  '100': [dzTrack(1, 'Seed Hit', { id: 100, name: 'Seed' }, [{ id: 100, name: 'Seed' }, { id: 200, name: 'Artist B' }])],
  '200': [
    dzTrack(2, 'Shared With Seed', { id: 200, name: 'Artist B' }, [{ id: 200, name: 'Artist B' }, { id: 300, name: 'Artist C' }]),
    dzTrack(3, 'B Solo', { id: 200, name: 'Artist B' }, [{ id: 200, name: 'Artist B' }]),
  ],
  '300': [dzTrack(4, 'C Track', { id: 300, name: 'Artist C' }, [{ id: 300, name: 'Artist C' }])],
};

async function gatherSeed() {
  const asked: string[] = [];
  const port: HttpPort = {
    async request(req: HttpRequest) {
      asked.push(req.url);
      const u = new URL(req.url);
      const m = /^\/artist\/(\d+)\/top$/.exec(u.pathname);
      if (u.host === 'api.deezer.com' && m) {
        return { status: 200, headers: {}, body: enc(JSON.stringify({ data: ARTISTS[m[1]!] ?? [] })) };
      }
      return { status: 200, headers: {}, body: enc(JSON.stringify({ data: [] })) };
    },
  };
  const http = new Http(port, new HostPacer({}, 0));
  const store = await Store.open(new MemoryBackend());
  const dz = new Deezer(http, store);
  const p = new Pipeline(store, http, dz, null as never, null as never, { search: async () => [] } as never, null, null);
  const seed = { key: 'seed|seed hit', title: 'Seed Hit', artists: ['Seed'], provider: 'local', platform_id: '' };
  const profiles = { [seed.key]: { dz: { artist_ids: { deezer: '100' } }, ytm_vid: null, dz_uncertain: [] } };
  const job = new Job('t');
  job.meter = p.meter({});
  const out = await p.gather(job, [seed], profiles, DEFAULT_ENGINE, false, undefined, new Set(), { colours: [], cursor: 0 });
  return { out, asked };
}

describe('gather runs the verified credit walk (DISCOVER-BREADTH-001)', () => {
  it('asks the seed artist top songs with their credits', async () => {
    const { asked } = await gatherSeed();
    expect(asked.some((u) => u.includes('/artist/100/top'))).toBe(true);
    expect(asked.some((u) => u.includes('/artist/200/top'))).toBe(true);
  });

  it('adds a collaborator song with collab_1 and its path, and a second-hand one with collab_2', async () => {
    const { out } = await gatherSeed();
    const prov = (title: string) => {
      const c = out.cands.find((x) => x['title'] === title);
      return (c?.['provenance'] as E.Provenance[] | undefined)?.map((p) => [p.relation, p.via]) ?? null;
    };
    expect(prov('B Solo')).toEqual([['collab_1', 'Artist B']]);
    expect(prov('C Track')).toEqual([['collab_2', 'Artist B > Artist C']]);
  });

  it('never lists the seed own song as a collaboration hit', async () => {
    const { out } = await gatherSeed();
    const seedHit = out.cands.find((x) => x['title'] === 'Seed Hit');
    const rels = ((seedHit?.['provenance'] as E.Provenance[] | undefined) ?? []).map((p) => p.relation);
    expect(rels.some((r) => r.startsWith('collab_'))).toBe(false);
  });
});
