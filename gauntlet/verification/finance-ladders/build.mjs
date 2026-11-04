// Builds the two JavaScript finance ladders from fixed seeds:
//   node build.mjs            write tests/work/{invoices-ladder,payroll-ladder}.json, scripted-business.json and
//                             payroll-inputs.json (the month inputs check_payroll.py recomputes on its own)
//   node build.mjs --check    exit 1 unless every file is byte-identical to a fresh build
// (The expense and reconciliation ladders are Python: python3 build.py.)
//
// A level is accepted only when its checks pass (invoices: planted intent = rules engine, including the pools'
// own simulation; payroll: every planted mistake changes a draft, every rule meant to bite bites). If a seed fails,
// the next salt is tried; the salt is recorded in the level's notes, so the build stays fully deterministic.

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { serializeTest, testPath, writeOrCheck, tok } from '../realworld-business/lib.mjs';
import { buildWorld } from '../realworld-business/build.mjs';
import * as inv from '../realworld-business/invoices.mjs';
import * as invL from './invoices.mjs';
import * as payL from './payroll.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

const LADDER_NOTE =
  'Ladder: ten frozen levels (L01 easiest, L10 hardest), each with deeper chains of dependent items and more rule families than the one below. A level is passed at 90% of its points; the headline is the highest level passed reliably.';

/** Rough output estimate: the answer JSON plus room to work (thinking models write far more than the answer). */
const outEstimate = (sol) => Math.min(56000, 4000 + 8 * tok(JSON.stringify(sol)));

function invoiceCases() {
  const meta = invL.META;
  const cases = [];
  const scripted = {};
  for (const spec of invL.SPECS) {
    const { world, sol, salt } = buildWorld(invL.mod, spec);
    const pools = world.pools ?? [];
    const notes = `[level ${spec.level}] Rules in play: the basics + ${spec.families.length} families (${spec.families.join(', ')}). ${pools.length} pools (${pools.length ? `${spec.intents.find((x) => x.startsWith('pool:')).slice(5)} documents each` : 'none'}). ${inv.summary(world, sol)}. Key = the rules engine of ${meta.engine}; ${meta.check}${salt ? ` (generator salt ${salt})` : ''} ${LADDER_NOTE}`;
    const sc = inv.caseScorer(world, meta.scorer);
    if (pools.length) sc.weights.remaining_received = 4;
    cases.push({ id: spec.id, prompt: inv.render(world), expected: sol, notes, scorer: sc });
    scripted[spec.id] = invL.scripted(world, sol);
    if (pools.length) scripted[spec.id].chain = inv.solve(world, { consumeHeld: true });
    scripted[spec.id].chain_kind = 'held documents taken to use up their pool too (every later document of the pool misjudged from there)';
  }
  return { meta, cases, scripted };
}

function payrollCases() {
  const meta = payL.META;
  const cases = [];
  const scripted = {};
  const dumps = {};
  for (const spec of payL.SPECS) {
    const lv = payL.buildLevel(spec);
    const { sc, ytdW } = payL.scorer(lv, meta.scorer);
    const wrong = Object.values(lv.key.months).map((m) => m.wrong_payslips.length);
    const notes = `[level ${spec.level}] ${spec.months} month${spec.months > 1 ? 's' : ''}, ${lv.staff.length} staff; wrong payslips per month ${wrong.join(' / ')}; planted ${lv.plan.map((p) => `${p.id}:${p.m}`).join(', ')}. Rules in play: the basics + ${spec.families.length} families (${spec.families.join(', ')}). Key = calc() of verification/realworld-business/payroll.mjs month by month along the correct year-to-date chain; second derivation: check_payroll.py recomputes every payslip of every month and the chain independently from payroll-inputs.json.${ytdW ? ` Year-to-date figures ${ytdW} points each.` : ''}${lv.salt ? ` (generator salt ${lv.salt})` : ''} ${LADDER_NOTE}`;
    cases.push({ id: spec.id, prompt: payL.render(lv, ytdW), expected: lv.key, notes, scorer: sc });
    scripted[spec.id] = { careless: payL.careless(lv), slip: payL.slip(lv), slip_kind: 'E01 tax £1 off in the first month (and £1 the other way the month after, as the cumulative basis carries it)', chain: spec.months > 1 ? payL.chainError(lv) : undefined, chain_kind: "each later month worked out from the bureau's year-to-date figures instead of the corrected ones" };
    dumps[spec.id] = payL.inputsDump(lv);
  }
  return { meta, cases, scripted, dumps };
}

function testFile(meta, cases) {
  const est = Math.round(cases.reduce((s, c) => s + tok(c.prompt), 0) / cases.length);
  const out = Math.round(cases.reduce((a, c) => a + outEstimate(c.expected), 0) / cases.length / 100) * 100;
  const m = { ...meta, version: '1.0.0', category: 'work', estimate: { inputTokens: est, outputTokens: out } };
  delete m.engine;
  delete m.check;
  let text = serializeTest(m, cases);
  // serializeTest keeps the business pack's field order; the ladders also carry a time limit and a retry cap.
  if (meta.timeLimitSec) text = text.replace(/\n  "estimate": /, `\n  "timeLimitSec": ${meta.timeLimitSec},\n  "maxRetries": 1,\n  "estimate": `);
  JSON.parse(text);
  return { file: testPath(meta.id), text };
}

export function buildAll() {
  const a = invoiceCases();
  const b = payrollCases();
  return [
    testFile(a.meta, a.cases),
    testFile(b.meta, b.cases),
    { file: path.join(HERE, 'scripted-business.json'), text: JSON.stringify({ [a.meta.id]: a.scripted, [b.meta.id]: b.scripted }) + '\n' },
    { file: path.join(HERE, 'payroll-inputs.json'), text: JSON.stringify(b.dumps) + '\n' },
  ];
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
