// ---------------------------------------------------------------------------
// The web server, for running Tally at an address rather than off a file.
//
// No dependencies on purpose. A static site of a dozen files does not need a
// framework, and a server with nothing in it is a server with nothing in it to
// go wrong at midnight while somebody is counting a drawer.
//
// It reads the built site into memory once at startup, gzips what is worth
// gzipping, and serves it. The whole site is under two megabytes, so holding
// it costs less than the machinery of not holding it.
//
// The cache headers are the part that matters. This is installed to a phone's
// home screen, so the page and the service worker are revalidated every time —
// otherwise a new version never reaches her — while Vite's fingerprinted
// assets are held for a year, because their names change when their contents
// do and a stale one is impossible.
// ---------------------------------------------------------------------------

import { createServer } from 'node:http'
import { readdir, readFile } from 'node:fs/promises'
import { gzipSync } from 'node:zlib'
import { extname, join, posix, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(fileURLToPath(import.meta.url), '..', process.env.TALLY_ROOT ?? 'dist')
const PORT = Number(process.env.PORT ?? 8080)

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
}

/** Worth compressing: text. A woff2 or a png is already compressed. */
const COMPRESS = new Set(['.html', '.js', '.css', '.json', '.webmanifest', '.svg', '.txt', '.map'])

/** Revalidated every time, so an update actually arrives. */
const ALWAYS_FRESH = new Set(['/', '/index.html', '/sw.js', '/manifest.webmanifest'])

async function* walk(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) yield* walk(full)
    else yield full
  }
}

const files = new Map()
for await (const full of walk(ROOT)) {
  const url = '/' + relative(ROOT, full).split(/[\\/]/).join('/')
  const ext = extname(full)
  const body = await readFile(full)
  files.set(url, {
    body,
    gzip: COMPRESS.has(ext) && body.length > 1024 ? gzipSync(body, { level: 9 }) : null,
    type: TYPES[ext] ?? 'application/octet-stream',
    // Fingerprinted by the build: the name changes when the contents do.
    immutable: url.startsWith('/assets/') && !ALWAYS_FRESH.has(url),
  })
}
if (!files.has('/index.html')) {
  console.error(`Nothing to serve: no index.html under ${ROOT}. Run npm run build first.`)
  process.exit(1)
}

const server = createServer((req, res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { allow: 'GET, HEAD' }).end()
    return
  }
  // Resolved before anything is looked up, so no request can climb out of the
  // built site by asking for one.
  const asked = posix.normalize(decodeURIComponent(new URL(req.url ?? '/', 'http://localhost').pathname))
  const path = asked === '/' ? '/index.html' : asked
  // One page and no routing, so anything unrecognised is still the app. That
  // is what makes a home-screen launch work whatever path it was saved at.
  const file = files.get(path) ?? files.get('/index.html')

  const wantsGzip = /\bgzip\b/.test(req.headers['accept-encoding'] ?? '')
  const body = wantsGzip && file.gzip ? file.gzip : file.body
  res.writeHead(200, {
    'content-type': file.type,
    'content-length': body.length,
    'cache-control': file.immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
    ...(body === file.gzip ? { 'content-encoding': 'gzip', vary: 'Accept-Encoding' } : {}),
    // A year of takings lives in this origin's storage, so it says plainly
    // that nothing else may frame it or sniff its types.
    'x-content-type-options': 'nosniff',
    'x-frame-options': 'DENY',
    'referrer-policy': 'no-referrer',
  })
  res.end(req.method === 'HEAD' ? undefined : body)
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Tally on :${PORT} — ${files.size} files`)
})
