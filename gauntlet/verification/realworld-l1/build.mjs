// Builds the six Business Ladder Level-1 ("Assist") tests into tests/work/ from fixed seeds.
//   node verification/realworld-l1/build.mjs          (writes the files)
//   node verification/realworld-l1/build.mjs --check  (fails if any committed file differs)
// test/realworld-l1.test.ts imports buildAll() and checks the committed JSON is byte-identical.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { testJson, tok } from './lib.mjs';
import * as invoice from './invoice-read.mjs';
import * as ticket from './ticket-tag.mjs';
import * as cv from './cv-read.mjs';
import * as email from './email-actions.mjs';
import * as sheet from './sheet-lookup.mjs';
import * as delivery from './delivery-note.mjs';

export const MODULES = [invoice, ticket, cv, email, sheet, delivery];
const HERE = dirname(fileURLToPath(import.meta.url));
export const OUT_DIR = join(HERE, '..', '..', 'tests', 'work');

/** { relative file name → exact JSON text } */
export function buildAll() {
  const files = {};
  for (const m of MODULES) {
    const cases = m.generate().map((g) => g.case);
    const ids = new Set(cases.map((c) => c.id));
    if (ids.size !== cases.length) throw new Error(`${m.META.id}: duplicate case ids`);
    for (const c of cases) if (JSON.stringify(c.expected).length > 0 && c.prompt.includes(JSON.stringify(c.expected))) throw new Error(`${m.META.id}/${c.id}: key appears in prompt`);
    files[`${m.META.id.split('.')[1]}.json`] = testJson(m.META, cases);
  }
  return files;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const files = buildAll();
  const check = process.argv.includes('--check');
  mkdirSync(OUT_DIR, { recursive: true });
  let bad = 0;
  for (const [name, text] of Object.entries(files)) {
    const path = join(OUT_DIR, name);
    const obj = JSON.parse(text);
    const toks = obj.cases.map((c) => tok(c.prompt));
    console.log(`${obj.id}: ${obj.cases.length} cases, prompt tokens ~${Math.min(...toks)}–${Math.max(...toks)} (mean ${obj.estimate.inputTokens})`);
    if (check) {
      let cur = '';
      try {
        cur = readFileSync(path, 'utf8');
      } catch {}
      if (cur !== text) {
        console.error(`  DIFFERS: ${path}`);
        bad++;
      }
    } else writeFileSync(path, text);
  }
  if (bad) process.exit(1);
}
