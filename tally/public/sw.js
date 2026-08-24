/* ---------------------------------------------------------------------------
   Service worker.

   The job is narrow: make the app open when there is no signal. A cellar bar
   with one bar of reception is the normal case, not the edge case, and an app
   that shows a browser error at midnight is worse than the paper it replaced.

   The nightly figures are not in here — they live in IndexedDB, which does not
   need a network at all. This only caches the app itself.

   Vite fingerprints the built filenames, so there is no fixed list to precache
   beyond the entry point; everything else is cached the first time it is
   fetched. Fingerprinted assets are immutable, so cache-first is safe for them,
   while the page itself is fetched fresh when possible so an update is picked
   up the next time the pub has signal.
   --------------------------------------------------------------------------- */

const VERSION = 'tally-v1'
const SHELL = `${VERSION}-shell`

const ENTRY = new URL('./', self.registration.scope).pathname
const INDEX = new URL('./index.html', self.registration.scope).pathname

/** Fetched on demand rather than bundled: several megabytes of on-device OCR. */
const CACHEABLE_HOSTS = ['cdn.jsdelivr.net', 'unpkg.com']

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(SHELL)
      .then((cache) => cache.addAll([ENTRY, INDEX]).catch(() => cache.add(ENTRY)))
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => !k.startsWith(VERSION)).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

async function cacheFirst(request) {
  const cached = await caches.match(request)
  if (cached) return cached
  const response = await fetch(request)
  if (response.ok || response.type === 'opaque') {
    const cache = await caches.open(SHELL)
    await cache.put(request, response.clone())
  }
  return response
}

async function networkFirst(request, fallbackPath) {
  try {
    const response = await fetch(request)
    if (response.ok) {
      const cache = await caches.open(SHELL)
      await cache.put(fallbackPath ?? request, response.clone())
    }
    return response
  } catch (err) {
    const cached = await caches.match(fallbackPath ?? request)
    if (cached) return cached
    throw err
  }
}

self.addEventListener('fetch', (event) => {
  const { request } = event
  if (request.method !== 'GET') return

  const url = new URL(request.url)

  // Never touch the API: a receipt reading is a POST anyway, and a cached
  // reply would be a wrong number presented as a fresh one.
  if (url.hostname.endsWith('anthropic.com')) return

  // The page itself: fresh when possible, cached when not.
  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request, INDEX))
    return
  }

  if (url.origin === self.location.origin) {
    // Fingerprinted build output never changes under the same name.
    if (/\.[0-9a-f]{8,}\./i.test(url.pathname)) {
      event.respondWith(cacheFirst(request))
      return
    }
    event.respondWith(networkFirst(request))
    return
  }

  if (CACHEABLE_HOSTS.includes(url.hostname)) {
    event.respondWith(cacheFirst(request))
  }
})
