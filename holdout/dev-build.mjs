// Builds a dev harness page (src/dev/<name>.js) into the scratchpad for screenshots.
import { build } from 'esbuild'
import { writeFileSync, readFileSync } from 'node:fs'
const [entry, out] = process.argv.slice(2)
const res = await build({ entryPoints: [entry], bundle: true, format: 'esm', write: false, target: 'es2022', logLevel: 'warning' })
const js = res.outputFiles[0].text.replace(/<\/script/gi, '<\\/script')
const css = readFileSync('src/style.css', 'utf8')
writeFileSync(out, `<!doctype html><html><head><meta charset="utf-8"><style>${css} body{margin:0}</style></head><body><canvas id="c"></canvas><div id="labels"></div><div id="hud"></div><script type="module">${js}</script></body></html>`)
console.log('wrote', out)
