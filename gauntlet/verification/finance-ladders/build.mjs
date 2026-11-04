// Builds the two JavaScript finance ladders from fixed seeds:
//   node build.mjs            write tests/work/{invoices-ladder,payroll-ladder}.json and scripted-business.json
//   node build.mjs --check    exit 1 unless every file is byte-identical to a fresh build
// (The expense and reconciliation ladders are Python: python3 build.py.)
//
// Each level is accepted only when its self-check is empty (planted intent = rules engine, every planted rule
// changes the answer when ignored, every family the level does not print changes nothing). If a seed fails, the
// next salt is tried; the salt is recorded in the level's notes, so the build stays fully deterministic.

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { serializeTest, testPath, writeOrCheck, tok } from '../realworld-business/lib.mjs';
import { buildWorld } from '../realworld-business/build.mjs';
import * as inv from '../realworld-business/invoices.mjs';
import * as pay from '../realworld-business/payroll.mjs';
import * as invL from './invoices.mjs';
import * as payL from './payroll.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

const LADDER_NOTE =
  'Ladder: ten frozen levels (L01 easiest, L10 hardest), each strictly bigger than the one below and stacking more rule families of the same policy. A level is passed at 90% of its points; the headline is the highest level passed reliably.';

const LADDERS = [
  { L: invL, base: inv, describe: (w, s) => `Rules in play: the basics + ${w.spec.families.length} families (${w.spec.families.join(', ')}). ${inv.summary(w, s)}.` },
  { L: payL, base: pay, describe: (w, s) => `Rules in play: the basics + ${w.spec.families.length} families (${w.spec.families.join(', ')}). ${pay.summary(w, s)}.` },
];

/** Rough output estimate: the answer JSON plus room to work (thinking models write far more than the answer). */
const outEstimate = (sol) => Math.min(56000, 4000 + 8 * tok(JSON.stringify(sol)));

export function buildAll() {
  const files = [];
  const scripted = {};
  for (const { L, base, describe } of LADDERS) {
    const meta = L.META;
    const cases = [];
    const outs = [];
    scripted[meta.id] = {};
    for (const spec of L.SPECS) {
      const { world, sol, salt } = buildWorld(L.mod, spec);
      const prompt = base.render(world);
      const notes = `[level ${spec.level}] ${describe(world, sol)} Key = the rules engine of ${meta.engine}; ${meta.check}${salt ? ` (generator salt ${salt})` : ''} ${LADDER_NOTE}`;
      const c = { id: spec.id, prompt, expected: sol, notes };
      c.scorer = base.caseScorer(world, meta.scorer);
      cases.push(c);
      outs.push(outEstimate(sol));
      scripted[meta.id][spec.id] = L.scripted(world, sol);
    }
    const est = Math.round(cases.reduce((s, c) => s + tok(c.prompt), 0) / cases.length);
    const out = Math.round(outs.reduce((a, b) => a + b, 0) / outs.length / 100) * 100;
    const m = { ...meta, version: '1.0.0', category: 'work', estimate: { inputTokens: est, outputTokens: out } };
    delete m.engine;
    delete m.check;
    let text = serializeTest(m, cases);
    // serializeTest keeps the business pack's field order; the ladders also carry a time limit and a retry cap.
    const obj = JSON.parse(text);
    if (meta.timeLimitSec) text = text.replace(/\n  "estimate": /, `\n  "timeLimitSec": ${meta.timeLimitSec},\n  "maxRetries": 1,\n  "estimate": `);
    void obj;
    JSON.parse(text);
    files.push({ file: testPath(meta.id), text });
  }
  files.push({ file: path.join(HERE, 'scripted-business.json'), text: JSON.stringify(scripted) + '\n' });
  return files;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.join(HERE, 'build.mjs')) {
  const check = process.argv.includes('--check');
  let ok = true;
  for (const f of buildAll()) {
    const same = writeOrCheck(f.file, f.text, check);
    if (!same) ok = false;
    console.log(`${check ? (same ? 'same   ' : 'DIFFERS') : 'wrote  '} ${path.relative(path.join(HERE, '..', '..'), f.file)} (${(f.text.length / 1024).toFixed(0)} KB)`);
  }
  if (!ok) process.exit(1);
}
