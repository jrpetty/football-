// Official overhead map images: finding the map image on its official page
// (only the page's own sites, the map's image first), fetching it with limits,
// keeping it on disk (PNG only, map ids only), and the pack's pages.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { allowedUrl, fetchOverhead, imageCandidates, overheadPage } from '../electron/overhead.ts'
import { Storage } from '../electron/storage.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const PAGE = 'https://www.callofduty.com/guides/modernwarfare4/multiplayer-maps/launch/cachette'
const NAMES = ['cachette', 'Cachette', 'Cashette']

test('the map image on its official page comes first; logos, other sites and plain http are left out', () => {
  const html = `
    <html><head>
      <meta property="og:image" content="https://www.callofduty.com/content/dam/share/mw4-social.jpg">
      <link rel="icon" href="/favicon.png">
    </head><body>
      <img src="/content/dam/atvi/logo.png" alt="Call of Duty logo">
      <img src="https://cdn.example.com/cachette-tacmap.png" alt="Cachette">
      <img src="http://www.callofduty.com/content/dam/cachette-http.png" alt="Cachette">
      <picture><source srcset="/content/dam/mw4/maps/cachette_overhead_1024.webp 1024w, /content/dam/mw4/maps/cachette_overhead_2048.webp 2048w"></picture>
      <img data-src="/content/dam/mw4/gallery/screenshot-03.jpg" alt="Gallery">
      <script>window.__DATA__ = {"tacMap":"https:\\/\\/www.callofduty.com\\/content\\/dam\\/mw4\\/maps\\/cachette-tac-map.png"}</script>
    </body></html>`
  const c = imageCandidates(html, PAGE, NAMES)
  assert.ok(c.length >= 3, c.join('\n'))
  // The images named after the map first (each is then checked for looking like a tactical map).
  const top = c.slice(0, 2).join(' ')
  assert.match(top, /cachette-tac-map\.png/)
  assert.match(top, /cachette_overhead_2048\.webp/)
  for (const u of c) {
    assert.ok(u.startsWith('https://www.callofduty.com/'), u)
    assert.doesNotMatch(u, /logo|favicon|example\.com|cachette-http/)
  }
})

test('only https pages and images on the guide’s own sites are fetched', () => {
  assert.ok(allowedUrl('https://www.callofduty.com/x.png'))
  assert.ok(allowedUrl('https://mw4camos.com/maps'))
  assert.equal(allowedUrl('http://www.callofduty.com/x.png'), null)
  assert.equal(allowedUrl('https://callofduty.com.evil.example/x.png'), null)
  assert.equal(allowedUrl('https://evilcallofduty.com/x.png'), null)
  assert.equal(allowedUrl('not a url'), null)
})

test('fetching: the page, then its likely images; non-images and other sites are skipped', async () => {
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...new Array(100).fill(1)])
  const asked: string[] = []
  const fake = (async (input: string | URL | Request) => {
    const url = String(input)
    asked.push(url)
    if (url === PAGE) return new Response(`<img src="/content/dam/cachette-map.png" alt="Cachette"><img src="/content/dam/cachette-gallery.png" alt="Cachette gallery">`, { status: 200, headers: { 'content-type': 'text/html' } })
    if (url.endsWith('cachette-map.png')) return new Response(png, { status: 200, headers: { 'content-type': 'image/png' } })
    if (url.endsWith('cachette-gallery.png')) return new Response('<html>not an image</html>', { status: 200, headers: { 'content-type': 'text/html' } })
    return new Response('', { status: 404 })
  }) as typeof fetch
  const page = overheadPage(builtInProfile('cod-mw4'), 'cachette')!
  assert.equal(page.url.toString(), PAGE)
  const r = await fetchOverhead(page, fake)
  assert.ok(r.ok)
  assert.equal(r.candidates.length, 1)
  assert.match(r.candidates[0].url, /cachette-map\.png$/)
  assert.deepEqual([...r.candidates[0].bytes.slice(0, 4)], [0x89, 0x50, 0x4e, 0x47])
  assert.ok(asked.every((u) => u.startsWith('https://www.callofduty.com/')))
  // A page that does not load says what to do instead.
  const down = await fetchOverhead(page, (async () => new Response('', { status: 503 })) as typeof fetch)
  assert.ok(!down.ok && /import it/.test(down.error))
})

test('kept on disk as PNG only, under map ids only', async () => {
  const s = new Storage(await mkdtemp(join(tmpdir(), 'sightline-')))
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...new Array(200).fill(7)])
  assert.equal(await s.saveOverhead('cod-mw4', 'cachette', png), true)
  assert.equal(await s.saveOverhead('cod-mw4', '../escape', png), false)
  assert.equal(await s.saveOverhead('cod-mw4', 'coal', new Uint8Array(300).fill(1)), false)
  assert.equal(await s.saveOverhead('../x', 'coal', png), false)
  const list = await s.listOverheads('cod-mw4')
  assert.deepEqual(
    list.map((o) => [o.mapId, o.bytes]),
    [['cachette', png.byteLength]],
  )
  assert.deepEqual([...(await s.loadOverhead('cod-mw4', 'cachette'))!], [...png])
  assert.equal(await s.loadOverhead('cod-mw4', '../cachette'), null)
  await s.deleteOverhead('cod-mw4', 'cachette')
  assert.deepEqual(await s.listOverheads('cod-mw4'), [])
})

test('the MW4 pack names the official guide page of each launch map and a community page for the rest', () => {
  const maps = builtInProfile('cod-mw4').knowledge.maps ?? []
  const official = maps.filter((m) => m.overhead?.source === 'official')
  assert.ok(official.length >= 12)
  for (const m of official) assert.ok(allowedUrl(m.overhead!.page)?.hostname.endsWith('callofduty.com'), m.id)
  for (const m of maps.filter((x) => x.overhead)) assert.ok(allowedUrl(m.overhead!.page), m.id)
  // Kill Block's only picture is a screenshot, not an overhead map.
  assert.equal(maps.find((m) => m.id === 'kill-block')?.overhead, undefined)
})

test('an imported file is matched to its map by name (longest name wins), else asked', async () => {
  const { mapForFile } = await import('../src/ui/overhead.ts')
  const maps = builtInProfile('cod-mw4').knowledge.maps ?? []
  assert.equal(mapForFile('cachette.webp', maps), 'cachette')
  assert.equal(mapForFile('MW4 Reactor U92 tac map.png', maps), 'reactor-u92')
  assert.equal(mapForFile('reactor-u92.png', maps), 'reactor-u92')
  assert.equal(mapForFile('Imjin_Farmland (2).jpg', maps), 'imjin-farmland')
  assert.equal(mapForFile('Cashette overhead.png', maps), 'cachette')
  assert.equal(mapForFile('screenshot-2026-10-27.png', maps), null)
})
