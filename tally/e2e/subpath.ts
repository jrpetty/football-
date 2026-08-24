// ---------------------------------------------------------------------------
// Does the build actually work where it will be published?
//
// Tally is served from /tally/ on GitHub Pages, not from a site root. Every
// asset reference, the manifest, the icons and the service worker scope have to
// resolve relative to that sub-path — and the failure mode if one does not is
// the worst kind: the build is fine, the tests are green, and the page is blank
// on her phone.
//
// So this serves the real build under a sub-path, watches every request for a
// 404, and drives enough of the app to prove it booted.
//
// Run with `npm run test:subpath` (build first).
// ---------------------------------------------------------------------------

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import { launchChromium } from '../scripts/browser.ts'

const here = dirname(fileURLToPath(import.meta.url))
const dist = join(here, '..', 'dist')
const PREFIX = '/tally/'

if (!existsSync(join(dist, 'index.html'))) {
  console.error('No build found. Run `npm run build` first.')
  process.exit(1)
}

const MIME: Record<string, string> = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.map': 'application/json',
}

const missing: string[] = []

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost')

  // Anything outside the sub-path is a genuine 404, exactly as Pages would
  // serve it — that is the whole point of the exercise.
  if (!url.pathname.startsWith(PREFIX)) {
    missing.push(url.pathname)
    res.writeHead(404).end('outside /tally/')
    return
  }

  const rel = normalize(decodeURIComponent(url.pathname.slice(PREFIX.length))).replace(/^(\.\.[/\\])+/, '')
  let file = join(dist, rel)
  if (rel === '' || rel === '/' || rel === '.') file = join(dist, 'index.html')
  if (!existsSync(file)) {
    missing.push(url.pathname)
    res.writeHead(404).end('not found')
    return
  }
  res.writeHead(200, { 'content-type': MIME[extname(file)] ?? 'application/octet-stream' })
  res.end(await readFile(file))
})

await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
const port = (server.address() as { port: number }).port
const base = `http://127.0.0.1:${port}${PREFIX}`

let failures = 0
let checks = 0
function check(label: string, ok: boolean, detail = ''): void {
  checks++
  console.log(ok ? `  ok    ${label}` : `  FAIL  ${label}${detail ? `\n        ${detail}` : ''}`)
  if (!ok) failures++
}

const browser = await launchChromium()
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
await context.addInitScript(() => {
  try {
    localStorage.setItem('tally.engine', 'off')
  } catch {
    /* ignore */
  }
})
const page = await context.newPage()
const errors: string[] = []
page.on('pageerror', (e) => errors.push(String(e)))
page.on('response', (r) => {
  if (r.status() >= 400) missing.push(`${r.status()} ${new URL(r.url()).pathname}`)
})

try {
  console.log(`\nServed at ${base}`)
  await page.goto(base, { waitUntil: 'networkidle' })

  check('the app boots under a sub-path', (await page.locator('.header h1').count()) === 1)
  check('the stylesheet resolved', await page.evaluate(() => getComputedStyle(document.body).margin === '0px'))
  check('nothing 404d', missing.length === 0, missing.join(', '))

  const manifest = await page.evaluate(async () => {
    const href = document.querySelector<HTMLLinkElement>('link[rel=manifest]')?.href ?? ''
    const res = await fetch(href)
    return { ok: res.ok, href, body: res.ok ? ((await res.json()) as { start_url: string; icons: Array<{ src: string }> }) : null }
  })
  check('the manifest resolves from the sub-path', manifest.ok, manifest.href)
  check('its start_url is relative, so it installs to /tally/', manifest.body?.start_url === './')

  const iconOk = await page.evaluate(async (icons: string[]) => {
    for (const src of icons) {
      const res = await fetch(new URL(src, document.baseURI))
      if (!res.ok) return src
    }
    return ''
  }, (manifest.body?.icons ?? []).map((i) => i.src))
  check('every launcher icon resolves', iconOk === '', `missing ${iconOk}`)

  const sw = await page.evaluate(() => navigator.serviceWorker.getRegistrations().then((r) => r.map((x) => x.scope)))
  check('the service worker registered', sw.length >= 1)
  check('and scoped itself to the sub-path', sw[0]?.endsWith(PREFIX) ?? false, sw.join(', '))

  // Enough of the app to prove it is alive, not merely painted.
  await page.fill('#figure-till', '4212.30')
  await page.fill('#figure-card', '2321.75')
  await page.fill('#figure-cash', '1890.55')
  await page.waitForTimeout(120)
  const verdict = (await page.locator('.verdict .headline').first().innerText()).trim()
  check('it reconciles under the sub-path', verdict === 'Balanced', `got "${verdict}"`)

  await page.click('.verdict-bar .btn-primary')
  await page.waitForSelector('.day-row', { timeout: 5000 })
  check('and saves', (await page.locator('.day-row').count()) === 1)

  check('no script errors', errors.length === 0, errors.join('\n        '))
} finally {
  await browser.close()
  server.close()
}

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exit(failures === 0 ? 0 : 1)
