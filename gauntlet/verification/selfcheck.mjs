// Self-check: load every test JSON written by the content team and assert required fields & types per src/core/types.ts.
import fs from 'node:fs';
import path from 'node:path';
const ROOT = new URL('..', import.meta.url).pathname.replace(/\/$/, '');
const MINE = ['reasoning/deduction-grid', 'reasoning/truth-tellers', 'reasoning/deduction-grid-extreme', 'reasoning/truth-tellers-extreme', 'reasoning/planning', 'math/competition', 'math/word-problems',
  'coding/algorithms', 'coding/debug-and-edge-cases', 'coding/hard', 'instruction/precision-formatting', 'instruction/system-prompt-adherence',
  'honesty/honesty-trap', 'extraction/structured-json', 'creative/one-shot-games', 'visual/svg-illustration'];
const categories = JSON.parse(fs.readFileSync(path.join(ROOT, 'config/categories.json'), 'utf8')).map((c) => c.id);
const SCORERS = new Set(['exact', 'number', 'choice', 'regex', 'contains', 'constraints', 'json', 'code-js', 'judge', 'judge-classify', 'artifact', 'human']);
const HONESTY_LABELS = ['CORRECT', 'CAUGHT_TRAP', 'PARTIAL', 'OVER_REFUSAL', 'WRONG', 'HALLUCINATED'];
const errors = [];
const err = (f, m) => errors.push(`${f}: ${m}`);
const ids = new Set();
let totalCases = 0;
const table = [];
for (const rel of MINE) {
  const file = path.join(ROOT, 'tests', rel + '.json');
  const raw = fs.readFileSync(file, 'utf8');
  let t;
  try { t = JSON.parse(raw); } catch (e) { err(rel, 'invalid JSON ' + e.message); continue; }
  const [cat, slug] = rel.split('/');
  if (t.kind !== 'prompt') err(rel, 'kind must be prompt');
  if (t.id !== `${cat}.${slug}`) err(rel, `id ${t.id} != ${cat}.${slug}`);
  if (ids.has(t.id)) err(rel, 'duplicate test id'); ids.add(t.id);
  if (!/^\d+\.\d+\.\d+$/.test(t.version)) err(rel, 'version');
  for (const k of ['name', 'description', 'hook', 'author', 'createdAt']) if (typeof t[k] !== 'string' || !t[k].trim()) err(rel, `missing ${k}`);
  if (t.category !== cat || !categories.includes(t.category)) err(rel, 'bad category');
  if (!['easy', 'medium', 'hard', 'extreme'].includes(t.difficulty)) err(rel, 'bad difficulty');
  if (!Array.isArray(t.tags) || !t.tags.every((x) => typeof x === 'string')) err(rel, 'tags');
  if (!Number.isInteger(t.maxOutputTokens) || t.maxOutputTokens < 1000) err(rel, 'maxOutputTokens');
  if (!t.estimate || !Number.isFinite(t.estimate.inputTokens) || !Number.isFinite(t.estimate.outputTokens)) err(rel, 'estimate');
  if (t.author !== 'Gauntlet Core' || t.createdAt !== '2026-09-24') err(rel, 'author/createdAt');
  const sentences = t.description.split(/(?<=[.!?])\s+/).filter(Boolean).length;
  if (sentences > 3) err(rel, `description has ${sentences} sentences`);
  if (!t.scorer || !SCORERS.has(t.scorer.type)) err(rel, 'scorer');
  if (!Array.isArray(t.cases) || !t.cases.length) { err(rel, 'no cases'); continue; }
  const cids = new Set();
  const diffs = {};
  for (const c of t.cases) {
    const w = `${rel}#${c.id}`;
    if (!/^[A-Za-z0-9_-]{1,40}$/.test(c.id ?? '')) err(w, 'bad case id');
    if (cids.has(c.id)) err(w, 'duplicate case id'); cids.add(c.id);
    const hasPrompt = typeof c.prompt === 'string' && c.prompt.trim(), hasTurns = Array.isArray(c.turns) && c.turns.length && c.turns.every((x) => typeof x === 'string' && x.trim());
    if (!!hasPrompt === !!hasTurns) err(w, 'exactly one of prompt/turns');
    if (typeof c.notes !== 'string' || c.notes.length < 20) err(w, 'notes missing');
    const m = /^\[(easy|medium|hard|extreme)\]/.exec(c.notes ?? '');
    if (!m) err(w, 'notes should start with [difficulty]'); else diffs[m[1]] = (diffs[m[1]] ?? 0) + 1;
    const s = c.scorer ?? t.scorer;
    const text = hasPrompt ? c.prompt : c.turns.join('\n');
    if (/FINAL ANSWER:/i.test(text)) err(w, 'prompt repeats the FINAL ANSWER instruction');
    if (['exact', 'number', 'choice'].includes(s.type) && !/Give exactly one answer\. If you give more than one answer, it will be marked wrong\./.test(text)) err(w, 'missing single-answer warning');
    switch (s.type) {
      case 'exact': if (!(typeof c.expected === 'string' || (Array.isArray(c.expected) && c.expected.length && c.expected.every((x) => typeof x === 'string')))) err(w, 'exact expected'); break;
      case 'number': if (typeof c.expected !== 'number' || !Number.isFinite(c.expected)) err(w, 'number expected'); break;
      case 'constraints': if (!Array.isArray(c.expected) || !c.expected.length || !c.expected.every((x) => x && typeof x.check === 'string')) err(w, 'constraints expected'); break;
      case 'json': if (!c.expected || typeof c.expected !== 'object') err(w, 'json expected'); break;
      case 'code-js': {
        const e = c.expected;
        if (!e || typeof e.functionName !== 'string' || !Array.isArray(e.tests) || e.tests.length < 10) err(w, 'code-js expected (needs >= 10 tests)');
        else if (!e.tests.every((x) => Array.isArray(x.args) && 'expected' in x)) err(w, 'code-js test shape');
        if (!text.includes('```javascript code block') || !text.includes('`' + e.functionName)) err(w, 'code prompt must name the function and the javascript block format');
        break;
      }
      case 'judge-classify': {
        if (!c.expected || !['trap', 'real'].includes(c.expected.type) || typeof c.expected.reference !== 'string' || c.expected.reference.length < 40) err(w, 'honesty expected');
        const labels = s.labels.map((l) => l.id);
        if (JSON.stringify(labels) !== JSON.stringify(HONESTY_LABELS)) err(w, 'honesty labels');
        break;
      }
      case 'artifact': {
        if (!s.rubric || !(s.judgeWeight > 0)) err(w, 'artifact needs rubric + judgeWeight');
        const pts = [...s.rubric.matchAll(/\((\d+)-(\d+) points?\)/g)].map((x) => Number(x[2]));
        if (pts.length && pts.reduce((a, b) => a + b, 0) !== 10) err(w, `rubric points sum ${pts.reduce((a, b) => a + b, 0)} != 10`);
        if (!/```(html|svg) code block/.test(text)) err(w, 'artifact prompt must request a single html/svg block');
        break;
      }
      default: err(w, 'unexpected scorer ' + s.type);
    }
  }
  totalCases += t.cases.length;
  table.push({ id: t.id, cases: t.cases.length, mix: diffs, est: t.estimate, maxOut: t.maxOutputTokens, kb: Math.round(raw.length / 1024) });
}
for (const r of table) console.log(r.id.padEnd(38), String(r.cases).padStart(3), JSON.stringify(r.mix).padEnd(52), JSON.stringify(r.est).padEnd(52), r.maxOut, `${r.kb} KB`);
console.log(`\n${table.length} tests, ${totalCases} cases`);
if (errors.length) { console.log(errors.join('\n')); process.exit(1); }
console.log('self-check passed');
