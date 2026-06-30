// Inlines the Vite build (CSS + JS) into one self-contained HTML file that can
// be opened directly from disk (file://) with no server. Run after `vite build`.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const dist = 'dist'
const assets = join(dist, 'assets')
let html = readFileSync(join(dist, 'index.html'), 'utf8')

const files = readdirSync(assets)
const cssFile = files.find((f) => f.endsWith('.css'))
const jsFile = files.find((f) => f.endsWith('.js'))

const css = cssFile ? readFileSync(join(assets, cssFile), 'utf8') : ''
// Guard against an accidental </script> sequence inside string literals.
const js = (jsFile ? readFileSync(join(assets, jsFile), 'utf8') : '').replace(/<\/script>/g, '<\\/script>')

// NOTE: use function replacers so `$` sequences in the bundle (e.g. `$&`, `$'`)
// are inserted literally rather than interpreted as replacement patterns.
// Drop modulepreload hints (the code is inlined, nothing to preload).
html = html.replace(/\s*<link[^>]*rel="modulepreload"[^>]*>/g, () => '')
// Replace the stylesheet <link> with an inline <style>.
html = html.replace(/<link[^>]*rel="stylesheet"[^>]*>/g, () => `<style>${css}</style>`)
// Replace the module <script src> with the inlined module code.
html = html.replace(/<script[^>]*src="[^"]*"[^>]*><\/script>/g, () => `<script type="module">${js}</script>`)

const out = join(dist, 'gaffer.html')
writeFileSync(out, html)
writeFileSync('gaffer.html', html) // also drop a copy at the repo root
const kb = (Buffer.byteLength(html) / 1024).toFixed(0)
console.log(`Wrote ${out} and ./gaffer.html (${kb} KB, fully self-contained)`)
