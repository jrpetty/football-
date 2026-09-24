// Independent checker for reasoning.truth-tellers-extreme: parses the English prompt back into logic
// (no code shared with the Python generator) and brute-forces every assignment of the stated types.
// Usage: node verify.mjs cases.json   (or the published test JSON)
import fs from 'node:fs';

const NUM = { none: 0, zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 };
const SING = { knight: 'knight', knave: 'knave', spy: 'spy', alternator: 'alternator' };
const PLUR = { knights: 'knight', knaves: 'knave', spies: 'spy', alternators: 'alternator' };
const ORD = { first: 0, second: 1, third: 2 };

function listOf(s, speaker) { return s.split(/, | and /).map((x) => x.trim()).map((p) => (p === 'me' ? speaker : p)); }

function atom(a, speaker, names, S) {
  a = a.trim().replace(/\.$/, '');
  const low = a[0].toLowerCase() + a.slice(1);
  let m;
  const nm = (x) => (x === 'I' || x === 'i' ? speaker : x[0].toUpperCase() + x.slice(1));
  const chk = (p) => { if (!names.includes(p)) throw new Error('bad name ' + p + ' in ' + a); return p; };
  if ((m = low.match(/^(\w+) would say that (.+)$/))) {
    const x = chk(nm(m[1])); const inner = atom(m[2], speaker, names, S);
    return (w) => (w[x] === 'knight' && inner(w)) || (w[x] === 'knave' && !inner(w));
  }
  if ((m = low.match(/^i am (not )?an? (\w+)$/))) { const neg = !!m[1], t = SING[m[2]]; return (w) => (w[speaker] === t) !== neg; }
  if ((m = low.match(/^(\w+) is (not )?an? (\w+)$/))) { const p = chk(nm(m[1])), neg = !!m[2], t = SING[m[3]]; if (!t) throw new Error(a); return (w) => (w[p] === t) !== neg; }
  if ((m = low.match(/^(\w+) and (\w+) are (the same type|different types)$/))) { const p = chk(nm(m[1])), q = chk(nm(m[2])), same = m[3] === 'the same type'; return (w) => (w[p] === w[q]) === same; }
  if ((m = low.match(/^none of us is an? (\w+)$/))) { const t = SING[m[1]]; return (w) => names.every((x) => w[x] !== t); }
  if ((m = low.match(/^(exactly|at least|at most) (\w+) of us (?:is an? (\w+)|are (\w+))$/))) {
    const op = m[1], k = NUM[m[2]], t = m[3] ? SING[m[3]] : PLUR[m[4]];
    return (w) => { const c = names.filter((x) => w[x] === t).length; return op === 'exactly' ? c === k : op === 'at least' ? c >= k : c <= k; };
  }
  if ((m = low.match(/^among (.+), none is an? (\w+)$/))) { const L = listOf(m[1], speaker).map(chk), t = SING[m[2]]; return (w) => L.every((x) => w[x] !== t); }
  if ((m = low.match(/^among (.+), (exactly|at least|at most) (\w+) (?:is an? (\w+)|are (\w+))$/))) {
    const L = listOf(m[1], speaker).map(chk), op = m[2], k = NUM[m[3]], t = m[4] ? SING[m[4]] : PLUR[m[5]];
    return (w) => { const c = L.filter((x) => w[x] === t).length; return op === 'exactly' ? c === k : op === 'at least' ? c >= k : c <= k; };
  }
  if ((m = low.match(/^the number of (\w+) among (.+) is (odd|even)$/))) {
    const t = PLUR[m[1]], L = listOf(m[2], speaker).map(chk), odd = m[3] === 'odd';
    return (w) => (L.filter((x) => w[x] === t).length % 2 === 1) === odd;
  }
  if ((m = low.match(/^all of (\w+)'s statements are (true|false)$/))) {
    const who = chk(nm(m[1])), val = m[2] === 'true';
    return (w) => S[who].every((f) => f(w) === val);
  }
  if ((m = low.match(/^exactly (\w+) of (\w+)'s statements (?:is|are) true$/))) {
    const k = NUM[m[1]], who = chk(nm(m[2]));
    return (w) => S[who].filter((f) => f(w)).length === k;
  }
  if ((m = low.match(/^(my|\w+'s) (?:(first|second|third) )?statement is (true|false)$/))) {
    const who = m[1] === 'my' ? speaker : chk(nm(m[1].replace(/'s$/, '')));
    const idx = m[2] ? ORD[m[2]] : 0, val = m[3] === 'true';
    return (w) => S[who][idx](w) === val;
  }
  throw new Error('unparsed: ' + a);
}

function stmt(s, speaker, names, S) {
  s = s.trim().replace(/\.$/, '');
  let m;
  if ((m = s.match(/^If (.+), then (.+)$/))) { const A = atom(m[1], speaker, names, S), B = atom(m[2], speaker, names, S); return (w) => !A(w) || B(w); }
  if (s.includes(' if and only if ')) { const [x, y] = s.split(' if and only if '); const A = atom(x, speaker, names, S), B = atom(y, speaker, names, S); return (w) => A(w) === B(w); }
  if (s.includes(', or ')) { const i = s.indexOf(', or '); const A = atom(s.slice(0, i), speaker, names, S), B = atom(s.slice(i + 5), speaker, names, S); return (w) => A(w) || B(w); }
  if (s.includes(', and ')) { const i = s.indexOf(', and '); const A = atom(s.slice(0, i), speaker, names, S), B = atom(s.slice(i + 6), speaker, names, S); return (w) => A(w) && B(w); }
  return atom(s, speaker, names, S);
}

function verify(prompt) {
  const types = [...prompt.matchAll(/^- An? (\w+): /gm)].map((m) => m[1]);
  const names = prompt.match(/You meet \w+ inhabitants: (.+?)\. Each/)[1].split(/, | and /);
  const cnt = (w, t) => names.filter((x) => w[x] === t).length;
  const rule = prompt.match(/Each of them knows the type of everyone present\. (.+)\n/)[1];
  const RULES = {
    'Exactly one of them is a spy, and exactly one of them is an alternator.': (w) => cnt(w, 'spy') === 1 && cnt(w, 'alternator') === 1,
    'The number of spies among them is odd, and exactly one of them is an alternator.': (w) => cnt(w, 'spy') % 2 === 1 && cnt(w, 'alternator') === 1,
    'Exactly two of them are spies.': (w) => cnt(w, 'spy') === 2,
    'Exactly one of them is a spy, and at most one of them is an alternator.': (w) => cnt(w, 'spy') === 1 && cnt(w, 'alternator') <= 1,
    'At least one of them is an alternator.': (w) => cnt(w, 'alternator') >= 1,
    'At most one of them is a spy, and exactly one of them is an alternator.': (w) => cnt(w, 'spy') <= 1 && cnt(w, 'alternator') === 1,
    'The number of spies among them is even and at most two (so it is zero or two).': (w) => cnt(w, 'spy') % 2 === 0 && cnt(w, 'spy') <= 2,
    'Exactly one of them is a spy, and at most two of them are alternators.': (w) => cnt(w, 'spy') === 1 && cnt(w, 'alternator') <= 2,
    'Exactly two of them are spies, and exactly two of them are alternators.': (w) => cnt(w, 'spy') === 2 && cnt(w, 'alternator') === 2,
    'Two or three of them are alternators.': (w) => cnt(w, 'alternator') >= 2 && cnt(w, 'alternator') <= 3,
  };
  const glob = RULES[rule];
  if (!glob) throw new Error('unknown global rule: ' + rule);
  const raw = {};
  for (const line of prompt.split('Statements:\n')[1].split('\n\n')[0].split('\n')) {
    let m;
    if ((m = line.match(/^(\w+) says: "(.+)"$/))) raw[m[1]] = [m[2]];
    else if ((m = line.match(/^(\w+) makes \w+ statements, in this order: (.+)$/))) raw[m[1]] = [...m[2].matchAll(/\(\d\) "([^"]+)"/g)].map((x) => x[1]);
    else throw new Error('bad line ' + line);
  }
  const S = {};
  for (const n of names) S[n] = [];
  for (const n of names) for (const s of raw[n]) S[n].push(stmt(s, n, names, S)); // closures resolve S lazily at evaluation time
  const sols = [];
  const w = {};
  const rec = (i) => {
    if (i === names.length) {
      if (!glob(w)) return;
      for (const n of names) {
        const vals = S[n].map((f) => f(w));
        if (w[n] === 'knight' && vals.some((v) => !v)) return;
        if (w[n] === 'knave' && vals.some((v) => v)) return;
        if (w[n] === 'alternator') for (let k = 0; k + 1 < vals.length; k++) if (vals[k] === vals[k + 1]) return;
      }
      sols.push(names.map((n) => `${n}: ${w[n]}`).join(', '));
      return;
    }
    for (const t of types) { w[names[i]] = t; rec(i + 1); }
  };
  rec(0);
  return sols;
}

const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const cases = Array.isArray(data) ? data : data.cases;
let bad = 0;
for (const c of cases) {
  const t0 = Date.now();
  const sols = verify(c.prompt);
  const ok = sols.length === 1 && sols[0] === c.expected[0];
  if (!ok) bad++;
  console.log(c.id, ok ? 'OK' : 'MISMATCH', sols.length, sols[0], `${Date.now() - t0}ms`);
}
process.exit(bad ? 1 : 0);
