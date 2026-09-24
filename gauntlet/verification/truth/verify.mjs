// Independent verifier: parse the English prompt text of each truth-teller case, brute-force all worlds.
import fs from 'node:fs';

const NUM = { none: 0, zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7 };
const SING = { knight: 'knight', knave: 'knave', spy: 'spy', alternator: 'alternator' };
const PLUR = { knights: 'knight', knaves: 'knave', spies: 'spy', alternators: 'alternator' };

function parseList(s, speaker) {
  const parts = s.split(/, | and /).map((x) => x.trim());
  return parts.map((p) => (p === 'me' ? speaker : p));
}

function parseAtom(a, speaker, names, stmtsRef) {
  a = a.trim().replace(/\.$/, '');
  a = a[0].toLowerCase() + a.slice(1);
  let m;
  const nm = (x) => (x === 'I' || x === 'i' ? speaker : x[0].toUpperCase() + x.slice(1));
  if ((m = a.match(/^i am (not )?an? (\w+)$/))) { const neg = !!m[1], t = SING[m[2]]; return (w) => (w[speaker] === t) !== neg; }
  if ((m = a.match(/^(\w+) is (not )?an? (\w+)$/))) { const p = nm(m[1]), neg = !!m[2], t = SING[m[3]]; if (!t || !names.includes(p)) throw new Error('bad atom ' + a); return (w) => (w[p] === t) !== neg; }
  if ((m = a.match(/^(\w+) and (\w+) are (the same type|different types)$/))) {
    const p = nm(m[1]), q = nm(m[2]), same = m[3] === 'the same type';
    if (!names.includes(p) || !names.includes(q)) throw new Error('bad names ' + a);
    return (w) => (w[p] === w[q]) === same;
  }
  if ((m = a.match(/^none of us is an? (\w+)$/))) { const t = SING[m[1]]; return (w) => names.filter((x) => w[x] === t).length === 0; }
  if ((m = a.match(/^(exactly|at least|at most) (\w+) of us (?:is an? (\w+)|are (\w+))$/))) {
    const op = m[1], k = NUM[m[2]], t = m[3] ? SING[m[3]] : PLUR[m[4]];
    if (k === undefined || !t) throw new Error('bad count ' + a);
    return (w) => { const c = names.filter((x) => w[x] === t).length; return op === 'exactly' ? c === k : op === 'at least' ? c >= k : c <= k; };
  }
  if ((m = a.match(/^among (.+), none is an? (\w+)$/))) { const L = parseList(m[1], speaker), t = SING[m[2]]; return (w) => L.filter((x) => w[x] === t).length === 0; }
  if ((m = a.match(/^among (.+), (exactly|at least|at most) (\w+) (?:is an? (\w+)|are (\w+))$/))) {
    const L = parseList(m[1], speaker), op = m[2], k = NUM[m[3]], t = m[4] ? SING[m[4]] : PLUR[m[5]];
    for (const x of L) if (!names.includes(x)) throw new Error('bad list ' + a);
    return (w) => { const c = L.filter((x) => w[x] === t).length; return op === 'exactly' ? c === k : op === 'at least' ? c >= k : c <= k; };
  }
  if ((m = a.match(/^(my|\w+'s) (?:(first|second|third) )?statement is (true|false)$/))) {
    const who = m[1] === 'my' ? speaker : nm(m[1].replace(/'s$/, ''));
    const idx = m[2] ? ['first', 'second', 'third'].indexOf(m[2]) : 0;
    const val = m[3] === 'true';
    return (w) => stmtsRef[who][idx](w) === val;
  }
  throw new Error('unparsed atom: ' + a);
}

function parseStmt(s, speaker, names, stmtsRef) {
  s = s.trim().replace(/\.$/, '');
  let m;
  if ((m = s.match(/^If (.+), then (.+)$/))) { const A = parseAtom(m[1], speaker, names, stmtsRef), B = parseAtom(m[2], speaker, names, stmtsRef); return (w) => !A(w) || B(w); }
  if (s.includes(' if and only if ')) { const [x, y] = s.split(' if and only if '); const A = parseAtom(x, speaker, names, stmtsRef), B = parseAtom(y, speaker, names, stmtsRef); return (w) => A(w) === B(w); }
  if (s.includes(', or ')) { const i = s.indexOf(', or '); const A = parseAtom(s.slice(0, i), speaker, names, stmtsRef), B = parseAtom(s.slice(i + 5), speaker, names, stmtsRef); return (w) => A(w) || B(w); }
  if (s.includes(', and ')) { const i = s.indexOf(', and '); const A = parseAtom(s.slice(0, i), speaker, names, stmtsRef), B = parseAtom(s.slice(i + 6), speaker, names, stmtsRef); return (w) => A(w) && B(w); }
  return parseAtom(s, speaker, names, stmtsRef);
}

function verifyPrompt(prompt) {
  const types = [...prompt.matchAll(/^- An? (\w+): /gm)].map((m) => m[1]);
  const meet = prompt.match(/You meet \w+ inhabitants: (.+?)\. Each/);
  const names = meet[1].split(/, | and /);
  let glob = () => true;
  let m;
  if ((m = prompt.match(/Exactly one of them is a spy and exactly one of them is an alternator\./))) glob = (w) => names.filter((x) => w[x] === 'spy').length === 1 && names.filter((x) => w[x] === 'alternator').length === 1;
  else if (/Exactly one of them is a spy\./.test(prompt)) glob = (w) => names.filter((x) => w[x] === 'spy').length === 1;
  else if (/Exactly two of them are spies\./.test(prompt)) glob = (w) => names.filter((x) => w[x] === 'spy').length === 2;
  else if (/At most one of them is a spy/.test(prompt)) glob = (w) => names.filter((x) => w[x] === 'spy').length <= 1;
  else if (!/There may be any number of each type/.test(prompt)) throw new Error('no global rule recognised');
  const stmtsRef = {};
  const block = prompt.split('Statements:\n')[1].split('\n\n')[0].split('\n');
  const raw = {};
  for (const line of block) {
    let mm;
    if ((mm = line.match(/^(\w+) says: "(.+)"$/))) raw[mm[1]] = [mm[2]];
    else if ((mm = line.match(/^(\w+) makes \w+ statements, in this order: (.+)$/))) raw[mm[1]] = [...mm[2].matchAll(/\(\d\) "([^"]+)"/g)].map((x) => x[1]);
    else throw new Error('bad line ' + line);
  }
  for (const n of names) stmtsRef[n] = raw[n].map((s) => parseStmt(s, n, names, stmtsRef));
  const sols = [];
  const rec = (i, w) => {
    if (i === names.length) {
      if (!glob(w)) return;
      for (const n of names) {
        const vals = stmtsRef[n].map((f) => f(w));
        if (w[n] === 'knight' && vals.some((v) => !v)) return;
        if (w[n] === 'knave' && vals.some((v) => v)) return;
        if (w[n] === 'alternator') for (let k = 0; k + 1 < vals.length; k++) if (vals[k] === vals[k + 1]) return;
      }
      sols.push(names.map((n) => `${n}: ${w[n]}`).join(', '));
      return;
    }
    for (const t of types) { w[names[i]] = t; rec(i + 1, w); }
  };
  rec(0, {});
  return sols;
}

const cases = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
let bad = 0;
for (const c of cases) {
  const sols = verifyPrompt(c.prompt);
  const ok = sols.length === 1 && sols[0] === c.expected[0];
  if (!ok) bad++;
  console.log(c.id, ok ? 'OK' : 'MISMATCH', sols.length, sols[0]);
}
process.exit(bad ? 1 : 0);
