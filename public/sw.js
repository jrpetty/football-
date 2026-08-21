/* Gaffer service worker — offline app shell via same-origin runtime caching.
   Cross-origin requests (Anthropic API, YouTube, sample videos) are left
   untouched so they always hit the network. */
const CACHE = 'gaffer-v1'

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET') return
  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return // never cache cross-origin

  // The predictor is a separate app published alongside this one under
  // /predictor/. Its whole point is that the data changes every week, and this
  // worker's stale-while-revalidate would serve a returning visitor last
  // week's predictions. Leave it entirely to the network.
  if (url.pathname.includes('/predictor/')) return

  event.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const cached = await cache.match(req)
      if (cached) {
        // Stale-while-revalidate: serve cache, refresh in the background.
        fetch(req).then((res) => { if (res && res.ok) cache.put(req, res.clone()) }).catch(() => {})
        return cached
      }
      try {
        const res = await fetch(req)
        if (res && res.ok) cache.put(req, res.clone())
        return res
      } catch {
        if (req.mode === 'navigate') {
          const fallback = (await cache.match('./')) || (await cache.match('index.html'))
          if (fallback) return fallback
        }
        return Response.error()
      }
    }),
  )
})
