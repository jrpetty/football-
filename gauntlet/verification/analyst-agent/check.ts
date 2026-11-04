/**
 * The Analyst: verification.
 *
 *   node verification/analyst-agent/check.ts            # check every seed, print the spread
 *   node verification/analyst-agent/check.ts --write    # also rewrite worlds.json (the generator fingerprint)
 *   V=careless node verification/analyst-agent/check.ts # print one player's per-question marks
 *
 * For every seed it regenerates the company and proves:
 *  - every answer key is computed twice and agrees: by the JavaScript solver over the raw rows and by the reference
 *    SQL run through the sandboxed worker on the stored tables (and re-solving from the stored tables gives the same);
 *  - each trap is live: the careless reading (no de-duplication, staff counted, the 90-day window one day long,
 *    repeats counted by row) changes the answer to every question it touches; the best-selling category has no tie;
 *    the database has no column that could answer the TV-advert question;
 *  - reference ≥ 95, gullible ≤ 20, hasty clearly below the reference and careless clearly below that, doing
 *    nothing and the Random Baseline 0;
 *  - the generated world is byte-identical to the fingerprint in worlds.json.
 */
import { program } from '../../src/programs/analyst-agent.ts';
import { generateAnalyst, rawFromTables, solve, type QuestionId } from '../../src/programs/lib/analyst/world.ts';
import { carelessScript, gullibleScript, hastyScript, REFERENCE_SQL, referenceScript } from '../../src/programs/lib/analyst/players.ts';
import { runOnDatabase } from '../../src/scoring/sql.ts';
import { mockBaselineResponder } from '../../test/helpers/fake-model.ts';
import { createRng } from '../../src/core/rng.ts';
import { contentHash, canonicalJson } from '../../src/core/hash.ts';
import { assert, checkFingerprints, loadTest, playScript, printMarks, scripted } from '../workworld-common.ts';

const test = loadTest('analyst-agent');
const fingerprints: Record<string, string> = {};
const rows: string[] = [];
for (const seed of test.seeds) {
  const spec = generateAnalyst(createRng(seed), seed);
  fingerprints[`${test.id}:${seed}`] = contentHash(spec);
  const db = { type: 'sql' as const, dialect: 'sqlite' as const, schema: spec.schema, databases: [{ id: 'live', tables: spec.tables }] };
  for (const [id, sql] of Object.entries(REFERENCE_SQL) as Array<[QuestionId, string | null]>) {
    if (!sql) continue;
    const out = await runOnDatabase(db, db.databases[0]!, sql, []);
    assert(out.ref && !out.ref.error, `${seed} ${id}: reference SQL runs (${out.ref?.error})`);
    const want = spec.answers[id];
    const got: Record<string, unknown> = id === 'q4' ? Object.fromEntries(out.ref!.rows.map((r) => [r[0], r[1]])) : Object.fromEntries(out.ref!.columns.map((c, i) => [c, out.ref!.rows[0]![i]]));
    assert(out.ref!.rows.length === (id === 'q4' ? spec.regions.length : 1), `${seed} ${id}: one row (got ${out.ref!.rows.length})`);
    for (const [k, v] of Object.entries(want)) {
      const g = got[k];
      const ok = typeof v === 'number' ? typeof g === 'number' && Math.abs(g - v) < 0.0051 : g === v;
      assert(ok, `${seed} ${id}.${k}: SQL ${g} vs solver ${v}`);
    }
  }
  const re = solve(rawFromTables(spec.tables), { dedupe: true, excludeStaff: true, completedOnly: true, activeFrom: '2026-01-31', firstOrderByRef: true });
  assert(canonicalJson(re) === canonicalJson(spec.answers), `${seed}: re-solving the stored tables gives the same answers`);
  for (const id of ['q1', 'q2', 'q4', 'q5', 'q6', 'q9'] as QuestionId[]) assert(canonicalJson(spec.careless[id]) !== canonicalJson(spec.answers[id]), `${seed}: the careless reading changes ${id}`);
  const units = new Map<string, number>();
  assert(spec.answers.dq.duplicate_orders === spec.dq.duplicates && spec.answers.dq.date === spec.dq.date, `${seed}: the data problem is the planted one`);
  void units;
  assert(!/source|campaign|advert|utm|referr|channel_source|acquisition/i.test(spec.schema.replace(/channel TEXT NOT NULL,\s+-- web, app or marketplace/, '')), `${seed}: no column answers the TV-advert question`);
  // q3 has no tie: recompute per-category April units
  {
    const out = await runOnDatabase(db, db.databases[0]!, REFERENCE_SQL.q3!.replace('LIMIT 1', 'LIMIT 2'), []);
    const r = out.ref!.rows;
    assert(r.length === 2 && r[0]![1] !== r[1]![1], `${seed}: the top category is unique (${JSON.stringify(r)})`);
  }

  const score: Record<string, number> = {};
  const detail: Record<string, unknown> = {};
  for (const [name, r] of [
    ['reference', scripted(referenceScript(spec))],
    ['hasty', scripted(hastyScript(spec))],
    ['careless', scripted(carelessScript(spec))],
    ['gullible', scripted(gullibleScript(spec))],
    ['do-nothing', scripted(['ACTION: FINISH'])],
    ['random', mockBaselineResponder()],
  ] as const) {
    const { result } = await playScript(program, seed, test.config, r);
    score[name] = Math.round(result.score * 1000) / 10;
    detail[name] = result.detail;
    if (process.env.V === name) printMarks(`${test.id} ${seed} ${name}`, result.detail);
  }
  const tasksOf = (n: string) => (detail[n] as { tasks: Array<{ id: string; outcome: string }> }).tasks;
  assert(tasksOf('reference').every((x) => x.outcome === 'done'), `${seed}: reference answers everything (${JSON.stringify(tasksOf('reference').filter((x) => x.outcome !== 'done'))})`);
  assert(score.reference! >= 95, `${seed}: reference ${score.reference}`);
  assert(score.gullible! <= 20, `${seed}: gullible ${score.gullible}`);
  assert(score['do-nothing'] === 0 && score.random === 0, `${seed}: floor ${score['do-nothing']} / ${score.random}`);
  assert(score.careless! < score.hasty! - 15 && score.hasty! < score.reference! - 15, `${seed}: careless ${score.careless} < hasty ${score.hasty} < reference`);
  const c = Object.fromEntries(tasksOf('careless').map((x) => [x.id, x.outcome]));
  assert(c.q8 === 'wrongly' && c.dq === 'not', `${seed}: a made-up TV share is wrongly; no data-problem report`);
  const levels = spec.questions.map((q) => q.level);
  rows.push(`| ${test.id} | ${seed} | ${spec.company.name} | ${spec.questions.length} (${['easy', 'medium', 'hard'].map((l) => levels.filter((x) => x === l).length).join('/')}) | ${referenceScript(spec).length} | ${score.reference} | ${score.hasty} | ${score.careless} | ${score.gullible} | ${score['do-nothing']} | ${score.random} |`);
}

checkFingerprints(new URL('worlds.json', import.meta.url), fingerprints);
console.log('| test | seed | company | questions (easy/medium/hard) | par | reference | hasty | careless | gullible | do-nothing | random |');
console.log('|---|---:|---|---|---:|---:|---:|---:|---:|---:|---:|');
for (const r of rows) console.log(r);
console.log('\nAll checks passed.');
