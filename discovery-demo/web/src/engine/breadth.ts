/**
 * DISCOVER-BREADTH-001 (owner, 2026-11-26): "I only hear music from those artists and it's stale" - discovery should
 * bring a breadth of new artists who fit the listening mood and taste map.
 *
 * Familiar = the starting songs' artists plus every artist the listener's own listening already leans toward.
 * The reserved share grows with the Adventurous slider: 25% at Familiar, 40% at neutral, 55% at Adventurous.
 */
import { artistSet, type FeedbackView } from './engine';
import { splitArtists } from './identity';

/** A known taste fit below this keeps a new artist out of the reserved slots (the "skips lean away" line). */
export const BREADTH_MIN_TASTE = 0.4;

export function breadthShare(adventurous: number): number {
  const a = Math.min(1, Math.max(0, Number.isFinite(adventurous) ? adventurous : 0.5));
  return 0.25 + 0.3 * a;
}

export function familiarArtists(seeds: readonly { artists?: string[] | null }[], fb: Pick<FeedbackView, 'artist' | 'likedArtists'>): Set<string> {
  const out = new Set<string>();
  for (const s of seeds) for (const a of artistSet({ artists: s.artists ?? [] })) out.add(a);
  // Folded the same way as a candidate's primaryArtist, so "Ken Carson" and "ken carson" are one artist.
  for (const [a, v] of Object.entries(fb.artist)) if (v > 0) for (const f of splitArtists(a)) out.add(f);
  for (const a of fb.likedArtists) for (const f of splitArtists(a)) out.add(f);
  return out;
}

export function breadthFor(
  seeds: readonly { artists?: string[] | null }[],
  fb: Pick<FeedbackView, 'artist' | 'likedArtists'>,
  adventurous: number,
): { familiar: Set<string>; share: number; minTaste: number } {
  return { familiar: familiarArtists(seeds, fb), share: breadthShare(adventurous), minTaste: BREADTH_MIN_TASTE };
}
