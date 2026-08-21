// The model core has to run in two places: the Node pipeline and the browser.
// That only stays true if nothing in src/core quietly reaches for a Node API,
// React, or the DOM. This walks the source and fails the moment one does —
// far cheaper than discovering it as a blank page after a deploy.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const CORE = join(HERE, '..', 'src', 'core')

/**
 * Remove comments only.
 *
 * Import checks must run against this rather than a fully stripped source:
 * an import specifier *is* a string literal, so blanking strings would erase
 * the very text being looked for. An earlier version of this file did exactly
 * that and silently passed a deliberately injected `node:fs` import.
 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ')
}

/**
 * Remove comments and string literals.
 *
 * Used for bare-identifier checks like `window.`, where prose in a doc comment
 * ("...over a trailing window.") would otherwise trip the assertion.
 */
function stripNonCode(src: string): string {
  return stripComments(src)
    .replace(/`(?:\\.|[^`\\])*`/g, '``')
    .replace(/'(?:\\.|[^'\\\n])*'/g, "''")
    .replace(/"(?:\\.|[^"\\\n])*"/g, '""')
}

async function coreFiles(): Promise<string[]> {
  const entries = await readdir(CORE, { withFileTypes: true })
  return entries.filter((e) => e.isFile() && e.name.endsWith('.ts')).map((e) => join(CORE, e.name))
}

/** Checks run against comment-stripped source, where specifiers survive. */
const IMPORT_RULES: { pattern: RegExp; why: string }[] = [
  { pattern: /from\s*['"]node:/, why: 'a Node built-in would break the browser build' },
  { pattern: /import\s*\(\s*['"]node:/, why: 'a dynamic Node import would break the browser build' },
  { pattern: /from\s*['"]react/, why: 'React in the core would break the Node pipeline' },
  { pattern: /\brequire\s*\(/, why: 'CommonJS require is not available in the browser bundle' },
]

/** Checks run against fully stripped source, where prose cannot interfere. */
const GLOBAL_RULES: { pattern: RegExp; why: string }[] = [
  { pattern: /\bdocument\s*\./, why: 'the DOM does not exist in Node' },
  { pattern: /\bwindow\s*\./, why: 'the DOM does not exist in Node' },
  { pattern: /\blocalStorage\b/, why: 'browser storage does not exist in Node' },
  { pattern: /\bprocess\s*\.\s*(env|argv|cwd)/, why: 'process is not available in the browser' },
]

test('the model core imports nothing platform-specific', async () => {
  const files = await coreFiles()
  assert.ok(files.length >= 10, 'expected the core to have real content')

  for (const file of files) {
    const raw = await readFile(file, 'utf8')
    const name = file.split('/').pop()
    for (const { pattern, why } of IMPORT_RULES) {
      assert.ok(!pattern.test(stripComments(raw)), `${name} matches ${pattern} — ${why}`)
    }
    for (const { pattern, why } of GLOBAL_RULES) {
      assert.ok(!pattern.test(stripNonCode(raw)), `${name} matches ${pattern} — ${why}`)
    }
  }
})

test('the isolation check actually catches a violation', async () => {
  // A guard that cannot fail is worse than no guard, because it looks like
  // coverage. This proves each rule bites, on a temporary file inside the
  // core directory that is always removed again.
  const probe = join(CORE, '__isolation_probe.ts')
  const violations = [
    `import { readFile } from 'node:fs/promises'\nexport const a = readFile\n`,
    `import { useState } from 'react'\nexport const a = useState\n`,
    `export const a = () => window.location.href\n`,
    `export const a = () => process.env['X']\n`,
  ]
  try {
    for (const source of violations) {
      await writeFile(probe, source, 'utf8')
      const raw = await readFile(probe, 'utf8')
      const caught =
        IMPORT_RULES.some((r) => r.pattern.test(stripComments(raw))) ||
        GLOBAL_RULES.some((r) => r.pattern.test(stripNonCode(raw)))
      assert.ok(caught, `a violation slipped through: ${source.split('\n')[0]}`)
    }
  } finally {
    await writeFile(probe, '', 'utf8')
    const { unlink } = await import('node:fs/promises')
    await unlink(probe).catch(() => {})
  }
})

test('prose in comments does not trip the DOM check', async () => {
  const benign = `/** Recent form measured over a trailing window. */\nexport const a = 1\n`
  assert.ok(!GLOBAL_RULES.some((r) => r.pattern.test(stripNonCode(benign))))
})

test('core relative imports carry explicit .ts extensions', async () => {
  // Node's type stripping requires them; Vite tolerates them. Without this the
  // pipeline fails at import time while the browser build stays green.
  const files = await coreFiles()
  for (const file of files) {
    const src = stripComments(await readFile(file, 'utf8'))
    for (const m of src.matchAll(/from\s+['"](\.[^'"]+)['"]/g)) {
      assert.ok(
        m[1]!.endsWith('.ts'),
        `${file.split('/').pop()} imports "${m[1]}" without a .ts extension`,
      )
    }
  }
})

test('every core module actually loads under Node', async () => {
  const files = await coreFiles()
  for (const file of files) {
    await assert.doesNotReject(() => import(file), `${file.split('/').pop()} failed to import`)
  }
})
