// Validate one or more game-profile JSON files: `npm run profile:check -- path.json`.

import { readFile } from 'node:fs/promises'
import { validateProfile } from '../src/core/profile/schema.ts'

const paths = process.argv.slice(2)
if (paths.length === 0) {
  console.error('Usage: npm run profile:check -- <profile.json> [...]')
  process.exit(2)
}
let failed = false
for (const path of paths) {
  let raw: unknown
  try {
    raw = JSON.parse(await readFile(path, 'utf8'))
  } catch (err) {
    console.error(`✗ ${path}: not readable JSON (${String(err)})`)
    failed = true
    continue
  }
  const r = validateProfile(raw)
  if (r.ok) console.log(`✓ ${path}: ${r.profile.name} v${r.profile.version} (${r.profile.signals.length} signals, ${r.profile.events.length} events, ${r.profile.episodes.length} episodes)`)
  else {
    failed = true
    console.error(`✗ ${path}:\n  ${r.errors.join('\n  ')}`)
  }
}
process.exit(failed ? 1 : 0)
