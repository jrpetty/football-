// Independent re-check of every Horizon answer key, written separately from the Python generators.
// It reads the committed test files (tests/horizon/*.json), parses each puzzle back OUT OF THE PROMPT TEXT
// the models see, recomputes the answer with different algorithms, and compares with the stored key.
//
//   node verify.mjs            (from this folder; exits non-zero on any mismatch)
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import vm from 'node:vm';

const ROOT = join(new URL('.', import.meta.url).pathname, '..', '..', 'tests', 'horizon');
const load = (f) => JSON.parse(readFileSync(join(ROOT, f), 'utf8'));
let failures = 0;
const check = (label, ok, info = '') => {
  if (!ok) failures++;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${info ? `  ${info}` : ''}`);
};

// ─── Run It In Your Head: execute the exact program text in a fresh V8 context ──────────────────────
{
  const t = load('mind-runner.json');
  let prev = 0;
  for (const c of t.cases) {
    const src = c.prompt.match(/```javascript\n([\s\S]*?)```/)[1];
    const printed = [];
    let steps = 0;
    vm.runInNewContext(src, { console: { log: (v) => printed.push(v) } }, { timeout: 10000 });
    // work measure: statements executed, counted by instrumenting every `;`-terminated statement line
    const counted = src.replace(/^(\s*)((?:let |const |[a-z]+(?:\[[^\]]*\])? = |\{ const p)[^\n]*;)$/gm, '$1__n++; $2').replace(/^(\s*)(if \()/gm, '$1__n++; $2');
    const ctx = { console: { log: () => {} }, __n: 0 };
    vm.runInNewContext(counted, ctx, { timeout: 10000 });
    steps = ctx.__n;
    const lines = src.trimEnd().split('\n').length;
    check(`mind-runner ${c.id}`, printed.length === 1 && String(printed[0]) === c.expected && steps > prev, `printed ${printed[0]}, ${lines} lines, ${steps} statements executed`);
    prev = steps;
  }
}

// ─── No Calculator: BigInt right-to-left square-and-multiply ─────────────────────────────────────────
{
  const t = load('modpow-ladder.json');
  let prevWork = 0;
  for (const c of t.cases) {
    const get = (k) => BigInt(c.prompt.match(new RegExp(`^${k} = (\\d+)$`, 'm'))[1]);
    let a = get('a') , e = get('e');
    const m = get('m');
    let r = 1n;
    a %= m;
    while (e > 0n) {
      if (e & 1n) r = (r * a) % m;
      a = (a * a) % m;
      e >>= 1n;
    }
    const work = get('e').toString(2).length * m.toString().length ** 2;
    check(`modpow ${c.id}`, r.toString() === c.expected && work > prevWork, `${r}`);
    prevWork = work;
  }
}

// ─── Sliding Ladder: parse the start board, re-prove the minimum, replay the stored plan ─────────────
function parseBoard(text) {
  return text.trim().split('\n').map((l) => l.trim().split(/\s+/).map((x) => (x === '_' ? 0 : Number(x))));
}
function manhattan(b, R, C) {
  let h = 0;
  for (let i = 0; i < b.length; i++) if (b[i]) h += Math.abs(Math.floor((b[i] - 1) / C) - Math.floor(i / C)) + Math.abs(((b[i] - 1) % C) - (i % C));
  return h;
}
function moves(z, R, C) {
  const out = [];
  const r = Math.floor(z / C), c = z % C;
  if (r > 0) out.push(z - C);
  if (r < R - 1) out.push(z + C);
  if (c > 0) out.push(z - 1);
  if (c < C - 1) out.push(z + 1);
  return out;
}
function bidirectionalBfs(start, goal, R, C) {
  // meet-in-the-middle BFS: grows the smaller frontier each round
  const key = (b) => b.join(',');
  if (key(start) === key(goal)) return 0;
  let fa = new Map([[key(start), start]]), fb = new Map([[key(goal), goal]]);
  const da = new Map([[key(start), 0]]), db = new Map([[key(goal), 0]]);
  let depthA = 0, depthB = 0;
  while (fa.size && fb.size) {
    const growA = fa.size <= fb.size;
    const [front, dist, other] = growA ? [fa, da, db] : [fb, db, da];
    const next = new Map();
    let best = Infinity;
    for (const [k, b] of front) {
      const z = b.indexOf(0);
      for (const t of moves(z, R, C)) {
        const nb = b.slice();
        nb[z] = nb[t];
        nb[t] = 0;
        const nk = key(nb);
        if (dist.has(nk)) continue;
        dist.set(nk, dist.get(k) + 1);
        if (other.has(nk)) best = Math.min(best, dist.get(nk) + other.get(nk));
        next.set(nk, nb);
      }
    }
    if (best < Infinity) return best;
    if (growA) { fa = next; depthA++; } else { fb = next; depthB++; }
  }
  return null;
}
// Linear conflict, computed independently of sliding.py: two tiles in their goal row (or column) whose
// order is reversed must spend two extra moves. Counted as (tiles in line) - (longest increasing run).
function conflicts(goals) {
  const tails = [];
  for (const g of goals) {
    let lo = 0, hi = tails.length;
    while (lo < hi) { const m = (lo + hi) >> 1; if (tails[m] < g) lo = m + 1; else hi = m; }
    tails[lo] = g;
  }
  return goals.length - tails.length;
}
function lcHeuristic(b, R, C) {
  let h = manhattan(b, R, C);
  for (let r = 0; r < R; r++) {
    const g = [];
    for (let c = 0; c < C; c++) { const v = b[r * C + c]; if (v && Math.floor((v - 1) / C) === r) g.push((v - 1) % C); }
    h += 2 * conflicts(g);
  }
  for (let c = 0; c < C; c++) {
    const g = [];
    for (let r = 0; r < R; r++) { const v = b[r * C + c]; if (v && (v - 1) % C === c) g.push(Math.floor((v - 1) / C)); }
    h += 2 * conflicts(g);
  }
  return h;
}
function idaStar(start, R, C) {
  const b = start.slice();
  let z = b.indexOf(0);
  let bound = lcHeuristic(b, R, C);
  let found = -1;
  const search = (g, prevZ) => {
    const h = lcHeuristic(b, R, C);
    if (g + h > bound) return g + h;
    if (h === 0) { found = g; return -1; }
    let min = Infinity;
    for (const t of moves(z, R, C)) {
      if (t === prevZ) continue;
      const tile = b[t], oz = z;
      b[z] = tile; b[t] = 0; z = t;
      const res = search(g + 1, oz);
      b[t] = tile; b[oz] = 0; z = oz;
      if (res === -1) return -1;
      if (res < min) min = res;
    }
    return min;
  };
  for (;;) {
    const res = search(0, -1);
    if (res === -1) return found;
    bound = res;
  }
}
{
  const t = load('sliding-ladder.json');
  let prev = 0;
  for (const c of t.cases) {
    const startTxt = c.prompt.match(/Start:\n([\s\S]*?)\n\nGoal:/)[1];
    const goalTxt = c.prompt.match(/Goal:\n([\s\S]*?)\n\nA move/)[1];
    const sb = parseBoard(startTxt), gb = parseBoard(goalTxt);
    const R = sb.length, C = sb[0].length;
    const start = sb.flat(), goal = gb.flat();
    const goalOk = goal.every((v, i) => v === (i === R * C - 1 ? 0 : i + 1));
    const promptOk = JSON.stringify(start) === JSON.stringify(c.expected.start) && R === c.expected.rows && C === c.expected.cols;
    const opt = R * C <= 9 ? bidirectionalBfs(start, goal, R, C) : idaStar(start, R, C);
    // replay the stored optimal plan
    const b = start.slice();
    let legal = true;
    for (const tile of c.expected.plan) {
      const z = b.indexOf(0), p = b.indexOf(tile);
      if (!moves(z, R, C).includes(p)) { legal = false; break; }
      b[z] = tile; b[p] = 0;
    }
    const planOk = legal && b.join() === goal.join() && c.expected.plan.length === c.expected.optimal;
    check(`sliding ${c.id}`, goalOk && promptOk && planOk && opt === c.expected.optimal && opt > prev, `${R}x${C} minimum ${opt}`);
    prev = opt;
  }
}

// ─── Picture Logic: parse clues, line-solve + backtrack, count solutions (stop at 2) ─────────────────
function lineOptions(clue, cells) {
  // For every cell: can it be filled / empty in some arrangement consistent with the known cells?
  // Dynamic programming over (position, run index), forwards and backwards.
  const n = cells.length, k = clue.length;
  const canFill = new Array(n).fill(false), canEmpty = new Array(n).fill(false);
  const memo = new Map();
  const fits = (i, j) => {
    // can cells[i..] be completed with runs j..k-1 ?
    const key = i * 64 + j;
    if (memo.has(key)) return memo.get(key);
    let ok = false;
    if (j === k) {
      ok = true;
      for (let x = i; x < n; x++) if (cells[x] === 1) { ok = false; break; }
    } else {
      // option A: cell i empty
      if (i < n && cells[i] !== 1 && fits(i + 1, j)) ok = true;
      // option B: run j starts at i
      const L = clue[j];
      if (!ok && i + L <= n) {
        let place = true;
        for (let x = i; x < i + L; x++) if (cells[x] === 0) { place = false; break; }
        if (place && i + L < n && cells[i + L] === 1) place = false;
        if (place && fits(Math.min(n, i + L + 1), j + 1)) ok = true;
      }
    }
    memo.set(key, ok);
    return ok;
  };
  if (!fits(0, 0)) return null;
  // walk every reachable state to mark possible cell values
  const seen = new Set();
  const walk = (i, j) => {
    const key = i * 64 + j;
    if (seen.has(key)) return;
    seen.add(key);
    if (j === k) {
      for (let x = i; x < n; x++) canEmpty[x] = true;
      return;
    }
    if (i < n && cells[i] !== 1 && fits(i + 1, j)) {
      canEmpty[i] = true;
      walk(i + 1, j);
    }
    const L = clue[j];
    if (i + L <= n) {
      let place = true;
      for (let x = i; x < i + L; x++) if (cells[x] === 0) { place = false; break; }
      if (place && i + L < n && cells[i + L] === 1) place = false;
      if (place && fits(Math.min(n, i + L + 1), j + 1)) {
        for (let x = i; x < i + L; x++) canFill[x] = true;
        if (i + L < n) canEmpty[i + L] = true;
        walk(Math.min(n, i + L + 1), j + 1);
      }
    }
  };
  walk(0, 0);
  return { canFill, canEmpty };
}
function solveNonogram(rows, cols, cap = 2) {
  const R = rows.length, C = cols.length;
  const solutions = [];
  const propagate = (g) => {
    let changed = true;
    while (changed) {
      changed = false;
      for (let r = 0; r < R; r++) {
        const o = lineOptions(rows[r], g[r]);
        if (!o) return false;
        for (let c = 0; c < C; c++) if (g[r][c] === -1) {
          if (!o.canFill[c]) { g[r][c] = 0; changed = true; } else if (!o.canEmpty[c]) { g[r][c] = 1; changed = true; }
        }
      }
      for (let c = 0; c < C; c++) {
        const col = g.map((row) => row[c]);
        const o = lineOptions(cols[c], col);
        if (!o) return false;
        for (let r = 0; r < R; r++) if (g[r][c] === -1) {
          if (!o.canFill[r]) { g[r][c] = 0; changed = true; } else if (!o.canEmpty[r]) { g[r][c] = 1; changed = true; }
        }
      }
    }
    return true;
  };
  const rec = (g) => {
    if (solutions.length >= cap) return;
    if (!propagate(g)) return;
    let pos = -1;
    for (let r = 0; r < R && pos < 0; r++) for (let c = 0; c < C; c++) if (g[r][c] === -1) { pos = r * C + c; break; }
    if (pos < 0) { solutions.push(g.map((row) => row.map((v) => (v ? '#' : '.')).join(''))); return; }
    for (const v of [1, 0]) {
      const h = g.map((row) => row.slice());
      h[Math.floor(pos / C)][pos % C] = v;
      rec(h);
    }
  };
  rec(Array.from({ length: R }, () => new Array(C).fill(-1)));
  return solutions;
}
{
  const t = load('nonogram-ladder.json');
  let prev = 0;
  for (const c of t.cases) {
    const parse = (label) => [...c.prompt.matchAll(new RegExp(`^${label} (\\d+): ([\\d ]+)$`, 'gm'))].map((m) => m[2].trim().split(/\s+/).map(Number));
    const rows = parse('Row'), cols = parse('Column');
    const sols = solveNonogram(rows, cols);
    const cells = rows.length * cols.length;
    check(`nonogram ${c.id}`, sols.length === 1 && sols[0].join('/') === c.expected.join('/') && cells > prev, `${rows.length}x${cols.length}, ${sols.length} solution(s)`);
    prev = cells;
  }
}

// ─── Count Every Tiling: row-by-row, cell-by-cell "broken profile" count with BigInt ────────────────
// (The generator counts column by column with a depth-first fill of each column; this walks the board in
// reading order, one cell at a time, keeping only which of the next C cells are already covered.)
function countTilings(board) {
  const R = board.length, C = board[0].length, N = R * C;
  const hole = (i) => (i < N && board[Math.floor(i / C)][i % C] === '.' ? 1 : 0);
  const top = 1 << (C - 1);
  let init = 0;
  for (let k = 0; k < C; k++) if (hole(k)) init |= 1 << k;
  let cur = new Map([[init, 1n]]);
  for (let i = 0; i < N; i++) {
    const r = Math.floor(i / C), c = i % C;
    const enter = hole(i + C) ? top : 0;
    const next = new Map();
    const add = (s, w) => next.set(s, (next.get(s) ?? 0n) + w);
    for (const [s, w] of cur) {
      if (s & 1) { add((s >>> 1) | enter, w); continue; }
      if (c + 1 < C && !(s & 2)) add(((s | 2) >>> 1) | enter, w);
      if (r + 1 < R && !hole(i + C)) add((s >>> 1) | top, w);
    }
    cur = next;
  }
  return cur.get(0) ?? 0n;
}
{
  if (countTilings(Array(8).fill('########')) !== 12988816n) throw new Error('tiling counter self-test failed');
  const t = load('tiling-count.json');
  let prev = 0;
  for (const c of t.cases) {
    const board = c.prompt.match(/must\s+stay uncovered\.\n\n([#.\n]+?)\n\n/)[1].split('\n');
    const n = countTilings(board);
    const cells = board.length * board[0].length;
    check(`tilings ${c.id}`, n.toString() === c.expected && cells > prev, `${board.length}x${board[0].length} → ${n}`);
    prev = cells;
  }
}

console.log(failures ? `\n${failures} FAILURE(S)` : '\nAll Horizon keys re-verified independently.');
process.exit(failures ? 1 : 0);
