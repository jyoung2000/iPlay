const nativeBridgeResponse = () => new Response(JSON.stringify({ native: false }), {
  status: 200,
  headers: { 'content-type': 'application/json' },
});

function bridgeAware(fetchImpl: typeof fetch): typeof fetch {
  return (input, init) => {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    try {
      if (new URL(raw, 'http://localhost:3000').pathname === '/radio-native') {
        return Promise.resolve(nativeBridgeResponse());
      }
    } catch { /* let fetch report malformed non-bridge URLs */ }
    return fetchImpl(input, init);
  };
}

const holder = globalThis as typeof globalThis & { __airwaveOriginalFetch?: typeof fetch };
const originalFetch = holder.__airwaveOriginalFetch ?? globalThis.fetch;
holder.__airwaveOriginalFetch = originalFetch;
const testFetch = bridgeAware(originalFetch);
globalThis.fetch = testFetch;
if (typeof window !== 'undefined') window.fetch = testFetch;
