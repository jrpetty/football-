// Independent checker for reasoning.truth-ladder ("Knights & Knaves: Ladder").
//
// Reads ONLY the prompt text: the type definitions, the stated spy/alternator counts and every statement are parsed
// back from English into logic (no code shared with the Python/CP-SAT generator). It then counts EVERY consistent
// assignment of types by exhaustive backtracking search: islanders are given a type one at a time, and every
// statement is evaluated in three-valued logic (true / false / not yet known), so a branch is abandoned only when a
// knight has said something already false, a knave something already true, an alternator two equal truth values in a
// row, or the stated counts can no longer be met. Nothing is ever pruned that could still be consistent, so the
// count of complete assignments that survive is exact. A level passes when exactly one survives and it equals the key.
//
// Usage: node verify.mjs ladder_cases.json   (or the published test JSON)
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

const UNITS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve', 'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const NUM = Object.fromEntries(UNITS.map((w, i) => [w, i]));
NUM.none = 0;
NUM.twenty = 20;
for (let i = 1; i < 10; i++) NUM[`twenty-${UNITS[i]}`] = 20 + i;
const SING = { knight: 'knight', knave: 'knave', spy: 'spy', alternator: 'alternator' };
const PLUR = { knights: 'knight', knaves: 'knave', spies: 'spy', alternators: 'alternator' };
const ORD = { first: 0, second: 1, third: 2 };
const TYPES = ['knight', 'knave', 'spy', 'alternator'];

// three-valued helpers: true, false or null (not yet known)
const NOT = (a) => (a === null ? null : !a);
const OR = (a, b) => (a === true || b === true ? true : a === null || b === null ? null : false);
const AND = (a, b) => (a === false || b === false ? false : a === null || b === null ? null : true);
const IFF = (a, b) => (a === null || b === null ? null : a === b);
function countTest(known, unknown, op, k) {
  if (op === 'exactly') return known > k || known + unknown < k ? false : unknown === 0 ? known === k : null;
  if (op === 'at least') return known >= k ? true : known + unknown < k ? false : null;
  return known + unknown <= k ? true : known > k ? false : null; // at most
}

function listOf(s, speaker) {
  return s.split(/, | and /).map((x) => x.trim()).map((p) => (p === 'me' ? speaker : p));
}

function makeAtom(a, speaker, names, S, deps) {
  a = a.trim().replace(/\.$/, '');
  const low = a[0].toLowerCase() + a.slice(1);
  let m;
  const nm = (x) => (x === 'I' || x === 'i' ? speaker : x[0].toUpperCase() + x.slice(1));
  const chk = (p) => {
    if (!names.includes(p)) throw new Error(`bad name ${p} in ${a}`);
    deps.add(p);
    return p;
  };
  const count = (L, t) => (w) => {
    let known = 0;
    let unknown = 0;
    for (const x of L) {
      if (w[x] === undefined) unknown++;
      else if (w[x] === t) known++;
    }
    return [known, unknown];
  };
  if ((m = low.match(/^(\w+) would say that (.+)$/))) {
    const x = chk(nm(m[1]));
    const inner = makeAtom(m[2], speaker, names, S, deps);
    return (w) => (w[x] === undefined ? null : w[x] === 'knight' ? inner(w) : w[x] === 'knave' ? NOT(inner(w)) : false);
  }
  if ((m = low.match(/^i am (not )?an? (\w+)$/))) {
    const neg = !!m[1];
    const t = SING[m[2]];
    deps.add(speaker);
    return (w) => (w[speaker] === undefined ? null : (w[speaker] === t) !== neg);
  }
  if ((m = low.match(/^(\w+) is (not )?an? (\w+)$/))) {
    const p = chk(nm(m[1]));
    const neg = !!m[2];
    const t = SING[m[3]];
    if (!t) throw new Error(a);
    return (w) => (w[p] === undefined ? null : (w[p] === t) !== neg);
  }
  if ((m = low.match(/^(\w+) and (\w+) are (the same type|different types)$/))) {
    const p = chk(nm(m[1]));
    const q = chk(nm(m[2]));
    const same = m[3] === 'the same type';
    return (w) => (w[p] === undefined || w[q] === undefined ? null : (w[p] === w[q]) === same);
  }
  if ((m = low.match(/^none of us is an? (\w+)$/))) {
    const c = count(names, SING[m[1]]);
    names.forEach((x) => deps.add(x));
    return (w) => countTest(...c(w), 'exactly', 0);
  }
  if ((m = low.match(/^(exactly|at least|at most) (\S+) of us (?:is an? (\w+)|are (\w+))$/))) {
    const op = m[1];
    const k = NUM[m[2]];
    if (k === undefined) throw new Error(a);
    const c = count(names, m[3] ? SING[m[3]] : PLUR[m[4]]);
    names.forEach((x) => deps.add(x));
    return (w) => countTest(...c(w), op, k);
  }
  if ((m = low.match(/^among (.+), none is an? (\w+)$/))) {
    const c = count(listOf(m[1], speaker).map(chk), SING[m[2]]);
    return (w) => countTest(...c(w), 'exactly', 0);
  }
  if ((m = low.match(/^among (.+), (exactly|at least|at most) (\S+) (?:is an? (\w+)|are (\w+))$/))) {
    const L = listOf(m[1], speaker).map(chk);
    const op = m[2];
    const k = NUM[m[3]];
    const c = count(L, m[4] ? SING[m[4]] : PLUR[m[5]]);
    return (w) => countTest(...c(w), op, k);
  }
  if ((m = low.match(/^the number of (\w+) among (.+) is (odd|even)$/))) {
    const t = PLUR[m[1]];
    const L = listOf(m[2], speaker).map(chk);
    const odd = m[3] === 'odd';
    const c = count(L, t);
    return (w) => {
      const [known, unknown] = c(w);
      return unknown ? null : (known % 2 === 1) === odd;
    };
  }
  if ((m = low.match(/^all of (\w+)'s statements are (true|false)$/))) {
    const who = chk(nm(m[1]));
    const val = m[2] === 'true';
    return (w) => S[who].map((f) => f(w)).reduce((acc, v) => AND(acc, v === null ? null : v === val), true);
  }
  if ((m = low.match(/^exactly (\w+) of (\w+)'s statements (?:is|are) true$/))) {
    const k = NUM[m[1]];
    const who = chk(nm(m[2]));
    return (w) => {
      const vs = S[who].map((f) => f(w));
      return countTest(vs.filter((v) => v === true).length, vs.filter((v) => v === null).length, 'exactly', k);
    };
  }
  if ((m = low.match(/^(my|\w+'s) (?:(first|second|third) )?statement is (true|false)$/))) {
    const who = m[1] === 'my' ? speaker : chk(nm(m[1].replace(/'s$/, '')));
    const idx = m[2] ? ORD[m[2]] : 0;
    const val = m[3] === 'true';
    return (w) => {
      const v = S[who][idx](w);
      return v === null ? null : v === val;
    };
  }
  throw new Error('unparsed: ' + a);
}

function makeStatement(s, speaker, names, S, deps) {
  s = s.trim().replace(/\.$/, '');
  let m;
  const at = (x) => makeAtom(x, speaker, names, S, deps);
  if ((m = s.match(/^If (.+), then (.+)$/))) {
    const A = at(m[1]);
    const B = at(m[2]);
    return (w) => OR(NOT(A(w)), B(w));
  }
  if (s.includes(' if and only if ')) {
    const [x, y] = s.split(' if and only if ');
    const A = at(x);
    const B = at(y);
    return (w) => IFF(A(w), B(w));
  }
  if (s.includes(', or ')) {
    const i = s.indexOf(', or ');
    const A = at(s.slice(0, i));
    const B = at(s.slice(i + 5));
    return (w) => OR(A(w), B(w));
  }
  if (s.includes(', and ')) {
    const i = s.indexOf(', and ');
    const A = at(s.slice(0, i));
    const B = at(s.slice(i + 6));
    return (w) => AND(A(w), B(w));
  }
  return at(s);
}

export function parseRule(rule) {
  if (rule === 'None of them is a spy or an alternator.') return { spies: 0, alternators: 0 };
  const m = rule.match(/^(None of them is a spy|Exactly one of them is a spy|Exactly (\S+) of them are spies), and (none of them is an alternator|exactly one of them is an alternator|exactly (\S+) of them are alternators)\.$/);
  if (!m) throw new Error('unknown global rule: ' + rule);
  const spies = m[1].startsWith('None') ? 0 : m[2] ? NUM[m[2]] : 1;
  const alternators = m[3].startsWith('none') ? 0 : m[4] ? NUM[m[4]] : 1;
  return { spies, alternators };
}

export function parseTruth(prompt) {
  const types = [...prompt.matchAll(/^- An? (\w+): /gm)].map((m) => m[1]);
  const [, nWord, list] = prompt.match(/You meet (\S+) inhabitants: (.+?)\. Each/);
  const names = list.split(/, | and /);
  if (NUM[nWord] !== names.length) throw new Error('inhabitant count');
  const rule = parseRule(prompt.match(/Each of them knows the type of everyone present\. (.+)\n/)[1]);
  const raw = {};
  for (const line of prompt.split('Statements:\n')[1].split('\n\n')[0].split('\n')) {
    let m;
    if ((m = line.match(/^(\w+) says: "(.+)"$/))) raw[m[1]] = [m[2]];
    else if ((m = line.match(/^(\w+) makes \w+ statements, in this order: (.+)$/))) raw[m[1]] = [...m[2].matchAll(/\(\d\) "([^"]+)"/g)].map((x) => x[1]);
    else throw new Error('bad line ' + line);
  }
  const S = {};
  const deps = {};
  for (const n of names) S[n] = [];
  for (const n of names) {
    deps[n] = new Set([n]);
    for (const s of raw[n] ?? []) S[n].push(makeStatement(s, n, names, S, deps[n]));
  }
  return { types, names, rule, S, deps, raw };
}

/** Every consistent assignment (exhaustive search with sound three-valued pruning). */
export function solveTruth(prompt, { limit = 10 } = {}) {
  const { types, names, rule, S, deps } = parseTruth(prompt);
  // Order: repeatedly take the islander most connected to those already placed (fewest open branches early).
  const order = [];
  const left = new Set(names);
  const weight = (x) => names.reduce((acc, y) => acc + (deps[y].has(x) ? 1 : 0) + (deps[x].has(y) ? 1 : 0), 0);
  while (left.size) {
    let best = null;
    let bestScore = -1;
    for (const x of left) {
      const sc = order.reduce((acc, y) => acc + (deps[y].has(x) ? 2 : 0) + (deps[x].has(y) ? 2 : 0), 0) + weight(x) / 100;
      if (sc > bestScore) { bestScore = sc; best = x; }
    }
    order.push(best);
    left.delete(best);
  }
  const w = {};
  const sols = [];
  let nodes = 0;
  const cnt = { spy: 0, alternator: 0 };
  const ok = () => {
    for (const n of names) {
      const t = w[n];
      if (t === undefined || t === 'spy') continue;
      const vals = S[n].map((f) => f(w));
      if (t === 'knight' && vals.some((v) => v === false)) return false;
      if (t === 'knave' && vals.some((v) => v === true)) return false;
      if (t === 'alternator') for (let k = 0; k + 1 < vals.length; k++) if (vals[k] !== null && vals[k] === vals[k + 1]) return false;
    }
    return true;
  };
  const rec = (i) => {
    nodes++;
    if (i === names.length) {
      if (cnt.spy !== rule.spies || cnt.alternator !== rule.alternators) return;
      for (const n of names) for (const f of S[n]) if (f(w) === null) throw new Error('undetermined statement at a leaf');
      sols.push(names.map((n) => `${n}: ${w[n]}`).join(', '));
      return;
    }
    const x = order[i];
    const rest = names.length - i - 1;
    for (const t of types) {
      if (t === 'spy' && cnt.spy >= rule.spies) continue;
      if (t === 'alternator' && cnt.alternator >= rule.alternators) continue;
      if (t in cnt) cnt[t]++;
      w[x] = t;
      if (rule.spies - cnt.spy + (rule.alternators - cnt.alternator) <= rest && ok()) rec(i + 1);
      delete w[x];
      if (t in cnt) cnt[t]--;
      if (sols.length >= limit) return;
    }
  };
  rec(0);
  return { solutions: sols, nodes, types: TYPES.filter((t) => types.includes(t)) };
}

const WORDN = ['zero', 'one', 'two', 'three'];
/** The prompt with one statement of one speaker left out (the rest renumbered), for the minimality re-proof. */
export function dropStatement(prompt, speaker, idx) {
  const [head, rest] = prompt.split('Statements:\n');
  const [block, ...tail] = rest.split('\n\n');
  const lines = block.split('\n').map((line) => {
    const m = line.match(/^(\w+) makes \w+ statements, in this order: (.+)$/);
    if (!m || m[1] !== speaker) return line;
    const st = [...m[2].matchAll(/\(\d\) "([^"]+)"/g)].map((x) => x[1]).filter((_, i) => i !== idx);
    return st.length === 1 ? `${speaker} says: "${st[0]}"` : `${speaker} makes ${WORDN[st.length]} statements, in this order: ${st.map((x, i) => `(${i + 1}) "${x}"`).join(' ')}`;
  });
  return [head + 'Statements:\n' + lines.join('\n'), ...tail].join('\n\n');
}

/** Every statement that nothing else refers to is needed: leaving it out lets another line-up through (or breaks the key). */
export function checkMinimal(prompt, key) {
  const { names, raw } = parseTruth(prompt);
  const all = Object.values(raw).flat().join(' ');
  const redundant = [];
  for (const n of names) {
    if ((raw[n] ?? []).length < 2) continue; // everyone says at least one thing
    if (all.includes(`${n}'s `)) continue; // referred to by number: kept so the numbering stays stable
    for (let i = 0; i < raw[n].length; i++) {
      const sols = solveTruth(dropStatement(prompt, n, i), { limit: 2 }).solutions;
      if (sols.length === 1 && sols[0] === key) redundant.push(`${n} (${i + 1})`);
    }
  }
  return redundant;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const cases = Array.isArray(data) ? data : data.cases;
  let bad = 0;
  for (const c of cases) {
    const t0 = Date.now();
    const { solutions, nodes } = solveTruth(c.prompt);
    const okCase = solutions.length === 1 && solutions[0] === c.expected[0];
    if (!okCase) bad++;
    console.log(c.id, okCase ? 'OK' : 'MISMATCH', `${solutions.length} solution(s)`, `${nodes.toLocaleString()} search nodes`, `${Date.now() - t0}ms`, solutions[0] ?? '');
    if (process.argv.includes('--minimal')) {
      const red = checkMinimal(c.prompt, c.expected[0]);
      if (red.length) bad++;
      console.log(`   minimal: ${red.length ? 'REDUNDANT ' + red.join(', ') : 'every statement that nothing refers to is needed'}`);
    }
  }
  process.exit(bad ? 1 : 0);
}
