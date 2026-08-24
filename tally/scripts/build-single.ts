// ---------------------------------------------------------------------------
// Fold the whole app into one HTML file.
//
// For handing Tally to someone who has nowhere to host it yet: one file, opened
// from anywhere, no server, no build step, no assets to keep alongside it.
//
// The output is body content only — no doctype, html, head or body tags —
// because the places this gets embedded supply their own document skeleton.
// ---------------------------------------------------------------------------

import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')
const dist = join(root, 'dist-single')
const assets = join(dist, 'assets')

const files = readdirSync(assets)
const jsName = files.find((f) => f.endsWith('.js'))
const cssName = files.find((f) => f.endsWith('.css'))
if (!jsName || !cssName) throw new Error('Expected one script and one stylesheet in dist-single/assets')

const js = readFileSync(join(assets, jsName), 'utf8')
const css = readFileSync(join(assets, cssName), 'utf8')
const html = readFileSync(join(dist, 'index.html'), 'utf8')

// The product name alone. Where this gets embedded it sits in a list beside
// other pages, and a name identifies where a category label does not.
const title = 'Tally'
void html

// A closing script tag inside the bundle would end the inline block early.
const safeJs = js.replace(/<\/script>/gi, '<\\/script>')

const out = `<title>${title}</title>
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover" />
<meta name="color-scheme" content="dark light" />
<style>
${css}
</style>
<div id="root"></div>
<script type="module">
${safeJs}
</script>
`

const target = join(root, 'tally-single.html')
writeFileSync(target, out)
console.log(`${target}  ${(out.length / 1024).toFixed(0)} kB`)
