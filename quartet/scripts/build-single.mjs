// Inlines the Vite build (CSS + JS) into one self-contained HTML file that can
// be opened straight from disk. Run after `vite build`. Writes dist/quartet.html
// and a copy at the repository root.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const dist = 'dist'
const assets = join(dist, 'assets')
let html = readFileSync(join(dist, 'index.html'), 'utf8')
const files = readdirSync(assets)
const cssFile = files.find((f) => f.endsWith('.css'))
const jsFiles = files.filter((f) => f.endsWith('.js'))
if (jsFiles.length !== 1) throw new Error(`expected one JS bundle, found ${jsFiles.join(', ')}`)
const css = cssFile ? readFileSync(join(assets, cssFile), 'utf8') : ''
const js = readFileSync(join(assets, jsFiles[0]), 'utf8').replace(/<\/script>/g, '<\\/script>')
const icon = readFileSync(join(dist, 'icon.svg'), 'utf8')

// function replacers so `$` sequences in the bundle are inserted literally
html = html.replace(/\s*<link[^>]*rel="modulepreload"[^>]*>/g, () => '')
html = html.replace(/<link[^>]*rel="stylesheet"[^>]*crossorigin[^>]*>|<link[^>]*rel="stylesheet"[^>]*href="\.\/assets[^>]*>/g, () => `<style>${css}</style>`)
html = html.replace(/<link rel="icon"[^>]*>/, () => `<link rel="icon" type="image/svg+xml" href="data:image/svg+xml,${encodeURIComponent(icon)}" />`)
html = html.replace(/<script[^>]*src="[^"]*"[^>]*><\/script>/g, () => `<script type="module">${js}</script>`)

writeFileSync(join(dist, 'quartet.html'), html)
writeFileSync(join('..', 'quartet.html'), html)
console.log(`Wrote dist/quartet.html and ../quartet.html (${(Buffer.byteLength(html) / 1024).toFixed(0)} KB, self-contained)`)
