/**
 * DISCOVER-BREADTH-001 (owner, 2026-11-26): more new artists in the listening mood, not the same starred few.
 *
 * Collaborators come only from Deezer's own track credits: an artist is a collaborator when a track of
 * theirs credits them with the artist being walked from. A related-artist link is never called a collaboration.
 * Depth-0 is the starting artist; each step out takes a bounded number of new credited artists, each artist
 * is visited once (cycles stop), and every song found records the path it came by, so the app can say
 * "through a collaborator of X" honestly.
 */
import type { Track } from './track';

export interface Credit { id: string; name: string }
export interface CreditedTrack { track: Track; credits: Credit[] }
export interface CollabHit { track: Track; hop: number; via: string }

/** New credited artists taken off an artist at depth 0, 1, 2 (so the walk reaches three steps out). */
export const COLLAB_FANOUT: readonly number[] = [4, 2, 1];
/** Artist catalogues read in one run, across every depth: one big artist's network cannot take the whole run. */
export const COLLAB_FETCH_BUDGET = 12;
/** Songs one artist contributes to a run at most. */
export const COLLAB_HITS_PER_ARTIST = 5;
/** Artist catalogues read across ALL starting songs in one run, so many starred artists cannot multiply the cost. */
export const COLLAB_RUN_BUDGET = 30;
/** Each starting song's fair slice of the run budget, so the first stars cannot use it all up. */
export function collabSeedBudget(seeds: number): number {
  return Math.min(COLLAB_FETCH_BUDGET, Math.max(2, Math.floor(COLLAB_RUN_BUDGET / Math.max(1, seeds))));
}

interface Node { id: string; name: string; path: string[]; depth: number }

/**
 * Walks the credit graph breadth-first from `seed`. `fetchTop` returns an artist's top songs with their
 * Deezer credits. Songs by a collaborator (depth >= 1) that do not credit the seed are the hits.
 */
export async function walkCollaborators(
  seed: { id: string; name: string },
  fetchTop: (artistId: string) => Promise<CreditedTrack[]>,
  opts: { fanout?: readonly number[]; budget?: number; check?: () => void; shared?: { left: number } } = {},
): Promise<CollabHit[]> {
  const fanout = opts.fanout ?? COLLAB_FANOUT;
  const budget = opts.budget ?? COLLAB_FETCH_BUDGET;
  const visited = new Set<string>([seed.id]);
  const hitKeys = new Set<string>();
  const hits: CollabHit[] = [];
  let queue: Node[] = [{ id: seed.id, name: seed.name, path: [], depth: 0 }];
  let fetches = 0;
  while (queue.length) {
    const next: Node[] = [];
    for (const node of queue) {
      if (fetches >= budget) return hits;
      if (opts.shared && opts.shared.left <= 0) return hits;
      if (opts.shared) opts.shared.left -= 1;
      fetches += 1;
      opts.check?.();
      const tracks = await fetchTop(node.id);
      const onIt = (ct: CreditedTrack) => ct.track.artist_ids?.['deezer'] === node.id || ct.credits.some((c) => c.id === node.id);
      const seedOn = (ct: CreditedTrack) => ct.track.artist_ids?.['deezer'] === seed.id || ct.credits.some((c) => c.id === seed.id);
      if (node.depth > 0) {
        let taken = 0;
        for (const ct of tracks) {
          if (taken >= COLLAB_HITS_PER_ARTIST) break;
          if (!onIt(ct) || seedOn(ct) || hitKeys.has(ct.track.key)) continue;
          hitKeys.add(ct.track.key);
          hits.push({ track: ct.track, hop: node.depth, via: node.path.join(' > ') });
          taken += 1;
        }
      }
      if (node.depth < fanout.length) {
        const cap = fanout[node.depth] ?? 0;
        let added = 0;
        for (const ct of tracks) {
          if (!onIt(ct)) continue;
          for (const c of ct.credits) {
            if (added >= cap) break;
            if (!c.id || !c.name || visited.has(c.id)) continue;
            visited.add(c.id);
            next.push({ id: c.id, name: c.name, path: [...node.path, c.name], depth: node.depth + 1 });
            added += 1;
          }
        }
      }
    }
    queue = next;
  }
  return hits;
}
