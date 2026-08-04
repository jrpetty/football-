// Folds the built bundle into one self-contained HTML file.
//
// The game has no external assets — every texture is generated procedurally at
// load time — so once the script and stylesheet are inlined the result is a
// single file you can double-click, email, or drop on a USB stick and it runs
// offline with no server and no install.

import { readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist-single')
const out = join(root, 'open-pitch.html')

if (!existsSync(dist)) {
  console.error('dist-single/ not found — run the vite build first.')
  process.exit(1)
}

let html = readFileSync(join(dist, 'index.html'), 'utf8')
const js = readFileSync(join(dist, 'bundle.js'), 'utf8')
const cssPath = join(dist, 'bundle.css')
const css = existsSync(cssPath) ? readFileSync(cssPath, 'utf8') : ''

// Swap the <script src> / <link rel=stylesheet> tags for their contents.
html = html.replace(
  /<script[^>]*src="[^"]*bundle\.js"[^>]*><\/script>/,
  () => `<script>\n${js}\n</script>`,
)
html = html.replace(
  /<link[^>]*href="[^"]*bundle\.css"[^>]*>/,
  () => `<style>\n${css}\n</style>`,
)

// The favicon is the one remaining external reference — fold it in as a data
// URI so the file has nothing at all to fetch.
const iconPath = join(root, 'public', 'icon.svg')
if (existsSync(iconPath)) {
  const icon = readFileSync(iconPath, 'utf8')
  const dataUri = `data:image/svg+xml,${encodeURIComponent(icon)}`
  html = html.replace(/href="[^"]*icon\.svg"/g, `href="${dataUri}"`)
}

if (html.includes('bundle.js')) {
  console.error('Failed to inline the script — the build output changed shape.')
  process.exit(1)
}

writeFileSync(out, html)
rmSync(dist, { recursive: true, force: true })

const kb = (Buffer.byteLength(html) / 1024).toFixed(0)
console.log(`Wrote open-pitch.html (${kb} KB) — open it in any browser to play.`)
