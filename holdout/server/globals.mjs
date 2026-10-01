// What the game code expects from a browser, for running camps in Node.
globalThis.window = globalThis
globalThis.location ??= { search: '', hash: '', protocol: 'node:', host: '' }
const mem = new Map()
globalThis.localStorage = { getItem: (k) => (mem.has(k) ? mem.get(k) : null), setItem: (k, v) => mem.set(k, String(v)), removeItem: (k) => mem.delete(k) }
globalThis.sessionStorage = globalThis.localStorage
globalThis.requestAnimationFrame = (f) => setTimeout(() => f(performance.now()), 16)
