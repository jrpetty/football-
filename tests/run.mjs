// Bundles the TypeScript tests and runs them on Node — no browser needed, since
// the simulation is deliberately free of DOM dependencies.
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const out = mkdtempSync(join(tmpdir(), 'open-pitch-tests-'))
let failed = 0

for (const f of readdirSync(here).filter((f) => f.endsWith('.test.ts'))) {
  const bundle = join(out, f.replace(/\.ts$/, '.mjs'))
  execFileSync('npx', ['esbuild', join(here, f), '--bundle', '--platform=node', '--format=esm', `--outfile=${bundle}`, '--log-level=warning'], { stdio: 'inherit' })
  console.log(`\n── ${f} ──`)
  const res = execFileSync('node', [bundle], { encoding: 'utf8' })
  process.stdout.write(res)
  if (res.includes('FAIL')) failed++
}
process.exit(failed ? 1 : 0)
