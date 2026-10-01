// Bundles the game (including three.js) into one self-contained HTML file
// that runs offline straight from disk.
//   node build.mjs            -> ../holdout.html (full document)
//   node build.mjs --out FILE --fragment  -> page body only (for hosts that add their own <head>)
import { build } from 'esbuild'
import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const fragment = args.includes('--fragment')
const outArg = args.indexOf('--out')
const out = outArg >= 0 ? resolve(args[outArg + 1]) : join(here, '..', 'holdout.html')

const res = await build({
  entryPoints: [join(here, 'src', 'main.js')],
  bundle: true,
  format: 'iife',
  minify: true,
  target: 'es2020',
  write: false,
  legalComments: 'none',
  logLevel: 'warning',
})
// Guard against a literal closing script tag inside the bundle.
const js = res.outputFiles[0].text.replace(/<\/script/gi, '<\\/script')
const css = readFileSync(join(here, 'src', 'style.css'), 'utf8')
let body = readFileSync(join(here, 'src', 'shell.html'), 'utf8')
body = body.replace('/*__CSS__*/', () => css).replace('/*__JS__*/', () => js)

const html = fragment
  ? body
  : `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no">
<meta name="theme-color" content="#121411">
${body.split('<canvas')[0].trim()}
</head>
<body>
<canvas${body.split('<canvas')[1]}
</body>
</html>
`
writeFileSync(out, html)
console.log(`Wrote ${out} (${(Buffer.byteLength(html) / 1024).toFixed(0)} KB)`)
