// ---------------------------------------------------------------------------
// Tally at an address.
//
// The other suites open the built files. This one starts the server that the
// container runs — the same serve.mjs, the same headers — and checks the
// things that are only true of a website: that the page and the worker are
// revalidated so an update arrives, that the fingerprinted assets are held for
// a year, that the manifest is served as a manifest so a phone will install
// it, that the service worker takes and the app opens with the network cut,
// and that no request can climb out of the built site.
// ---------------------------------------------------------------------------

import { spawn } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { existsSync } from 'node:fs'
import { launchChromium } from '../scripts/browser.ts'

const here = dirname(fileURLToPath(import.meta.url))
const app = join(here, '..')
if (!existsSync(join(app, 'dist', 'index.html'))) {
  console.error('No build found. Run `npm run build` first.')
  process.exit(1)
}

const PORT = 8123
const base = `http://127.0.0.1:${PORT}/`
const server = spawn(process.execPath, [join(app, 'serve.mjs')], {
  cwd: app,
  env: { ...process.env, PORT: String(PORT) },
  stdio: ['ignore', 'pipe', 'pipe'],
})
const said: string[] = []
server.stdout.on('data', (d) => said.push(String(d)))
server.stderr.on('data', (d) => said.push(String(d)))

/** Wait for it to answer rather than guessing how long it takes to start. */
async function ready(): Promise<boolean> {
  for (let i = 0; i < 50; i++) {
    try {
      const res = await fetch(base)
      if (res.ok) return true
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 100))
  }
  return false
}

let failures = 0
let checks = 0
function check(label: string, ok: boolean, detail = ''): void {
  checks++
  if (ok) console.log(`  ok    ${label}`)
  else {
    failures++
    console.log(`  FAIL  ${label}${detail ? `\n        ${detail}` : ''}`)
  }
}

try {
  check('the server starts', await ready(), said.join(''))

  console.log('\nWhat it sends back')
  const page = await fetch(base)
  check('the page is html', (page.headers.get('content-type') ?? '').startsWith('text/html'))
  check(
    'and is revalidated every time, so a new version arrives',
    page.headers.get('cache-control') === 'no-cache',
    page.headers.get('cache-control') ?? '',
  )
  check('it refuses to be framed', page.headers.get('x-frame-options') === 'DENY')
  check('and its types cannot be sniffed', page.headers.get('x-content-type-options') === 'nosniff')

  const worker = await fetch(new URL('sw.js', base))
  check('the service worker is javascript', (worker.headers.get('content-type') ?? '').startsWith('text/javascript'))
  check('and is never held on to', worker.headers.get('cache-control') === 'no-cache')

  const manifest = await fetch(new URL('manifest.webmanifest', base))
  check(
    'the manifest is a manifest, which is what lets a phone install it',
    (manifest.headers.get('content-type') ?? '').startsWith('application/manifest+json'),
    manifest.headers.get('content-type') ?? '',
  )

  const html = await page.text()
  const asset = /\/assets\/[^"']+\.js/.exec(html)?.[0]
  check('the page names a fingerprinted script', !!asset, html.slice(0, 200))
  if (asset) {
    const built = await fetch(new URL(asset.slice(1), base))
    check(
      'which is held for a year, because its name changes when it does',
      (built.headers.get('cache-control') ?? '').includes('immutable'),
      built.headers.get('cache-control') ?? '',
    )
  }

  console.log('\nWhat it refuses')
  const climb = await fetch(new URL('../../etc/passwd', base))
  check('a request cannot climb out of the site', (await climb.text()).includes('<!doctype html>'))
  const posted = await fetch(base, { method: 'POST' })
  check('and nothing can be posted to it', posted.status === 405)

  console.log('\nOn a phone')
  const browser = await launchChromium()
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
    const errors: string[] = []
    const tab = await context.newPage()
    tab.on('pageerror', (err) => errors.push(String(err)))
    await tab.goto(base, { waitUntil: 'networkidle' })
    await tab.waitForTimeout(1200)
    check('the app opens', (await tab.locator('.main').count()) === 1)
    await tab.click('button:has-text("Cellar")')
    await tab.waitForTimeout(900)
    const cellar = await tab.locator('.main').innerText()
    check('with the cellar counted in', /1056 pints/.test(cellar), cellar.slice(0, 120))

    check(
      'the service worker takes',
      await tab.evaluate(() => navigator.serviceWorker.ready.then(() => true).catch(() => false)),
    )
    await context.setOffline(true)
    await tab.reload({ waitUntil: 'domcontentloaded' })
    check(
      'so it opens with the network cut, which is the cellar',
      await tab
        .waitForSelector('.main', { timeout: 10_000 })
        .then(() => true)
        .catch(() => false),
    )
    await context.setOffline(false)
    check('nothing threw along the way', errors.length === 0, errors.join('; '))
    await context.close()
  } finally {
    await browser.close()
  }
} finally {
  server.kill()
}

console.log(`\n${checks - failures}/${checks} checks passed`)
process.exit(failures === 0 ? 0 : 1)
