import { describe, expect, it } from 'vitest';
import { LocalApi } from './local-api';
import { MemoryBackend, Store } from './store';
import type { HttpPort } from './net';

const port = { request: async () => { throw new Error('Defaults tests must not contact providers'); } } as HttpPort;
async function open() {
  const backend = new MemoryBackend(); const store = await Store.open(backend);
  return { backend, store, api: new LocalApi({ store, port, encoder: null }) };
}

describe('fresh-install mood defaults (0.20.25)', () => {
  it('ships mood on, mood leads on, run cap 8 and adventurous 1.0', async () => {
    const { api } = await open();
    expect((await api.handle('GET', '/api/settings')).body).toMatchObject({ mood_queue: true, mood_lead: true, genre_run_limit: 8, adventurous: 1 });
  });
  it('keeps explicitly saved values over the defaults, including opt-outs', async () => {
    const { api } = await open();
    expect((await api.handle('PUT', '/api/settings', { mood_queue: false, mood_lead: false, genre_run_limit: 3, adventurous: 0.5 })).status).toBe(200);
    expect((await api.handle('GET', '/api/settings')).body).toMatchObject({ mood_queue: false, mood_lead: false, genre_run_limit: 3, adventurous: 0.5 });
  });
  it('falls back to 8 for an invalid run limit', async () => {
    const { api } = await open();
    expect((await api.handle('PUT', '/api/settings', { genre_run_limit: 7 })).status).toBe(200);
    expect((await api.handle('GET', '/api/settings')).body).toMatchObject({ genre_run_limit: 8 });
  });
});
