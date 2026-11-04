// Independent re-check of every Exact Maths: Ladder key (method 2).
//
//   node verify.mjs
//
// Reads tests/math/exact-ladder.json, parses each board back OUT OF THE PROMPT TEXT the models see (so a key can
// never drift from its prompt), and recomputes the winning probability with exact BigInt rationals by a different
// algorithm from snakes.py: a backward recursion from the finish in which every square's value is an affine function
// of the unknown values at the snake tails, followed by a small Gauss-Jordan elimination for those unknowns.
// It also self-tests on a board small enough to check by hand.
import fs from 'node:fs';
import { parseBoard, winProbability } from './solver.mjs';

// ── self-tests on boards small enough to check by hand ───────────────────────────────────────────────
{
  const check = (board, want, why) => { const p = winProbability(board); if (`${p.p}/${p.q}` !== want) throw new Error(`self-test failed (${why}): ${p.p}/${p.q}`); };
  // 0..3, coin, trap 1: from 2 both spins finish (P2 = 1), so P0 = (trap + P2)/2 = 1/2.
  check({ N: 3, d: 2, ladders: new Map(), snakes: new Map(), traps: new Set([1]) }, '1/2', 'no loop');
  // 0..4, coin, trap 2, snake 3 -> 0: P1 = (trap + P0)/2, P0 = (P1 + trap)/2 = P0/4, so P0 = 0 (this board can only be lost).
  check({ N: 4, d: 2, ladders: new Map(), snakes: new Map([[3, 0]]), traps: new Set([2]) }, '0/1', 'losing loop');
  // 0..4, coin, ladder 1 -> 2, snake 3 -> 0: P2 = (P0 + 1)/2 and P0 = (P2 + P2)/2 = P2, so P0 = 1.
  check({ N: 4, d: 2, ladders: new Map([[1, 2]]), snakes: new Map([[3, 0]]), traps: new Set() }, '1/1', 'loop that always wins');
  // 0..5, coin, snake 4 -> 1, trap 2: P3 = (P1 + 1)/2 and P1 = (trap + P3)/2 = P3/2, so P3 = 2/3, P1 = 1/3, P0 = (P1 + trap)/2 = 1/6.
  check({ N: 5, d: 2, ladders: new Map(), snakes: new Map([[4, 1]]), traps: new Set([2]) }, '1/6', 'loop with a trap');
}

const test = JSON.parse(fs.readFileSync(new URL('../../tests/math/exact-ladder.json', import.meta.url), 'utf8'));
let bad = 0;
for (const c of test.cases) {
  const p = winProbability(parseBoard(c.prompt));
  const got = `${p.p}/${p.q}`;
  const ok = got === c.expected;
  if (!ok) bad++;
  console.log(`${c.id} ${ok ? 'ok ' : 'MISMATCH'} ${got}${ok ? '' : ` (key ${c.expected})`}`);
}
console.log(bad ? `${bad} MISMATCHES` : `all ${test.cases.length} keys re-verified from the prompt text`);
process.exit(bad ? 1 : 0);
