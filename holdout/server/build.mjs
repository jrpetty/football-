// Build what the server ships: the game page and the camp worker.
import { build } from 'esbuild'
import { execFileSync } from 'node:child_process'
import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))
mkdirSync(path.join(here, 'public'), { recursive: true })
execFileSync(process.execPath, [path.join(here, '..', 'build.mjs'), '--out', path.join(here, 'public', 'index.html')], { stdio: 'inherit' })
await build({
  entryPoints: [path.join(here, 'camp.mjs')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  target: 'node20',
  outfile: path.join(here, 'dist', 'camp.mjs'),
  alias: { peerjs: path.join(here, 'peer-stub.mjs') },
  logLevel: 'warning',
})
console.log('Built server/dist/camp.mjs')
