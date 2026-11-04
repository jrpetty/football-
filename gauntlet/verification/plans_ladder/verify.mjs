// Independent re-proof of every minimum in reasoning.plans-ladder ("Shortest Plans: Ladder").
//
// Reads ONLY the prompt text (pegs, disks, goal peg), then runs a forward breadth-first search from that start
// position until the goal is reached (the Python generator searched backwards from the goal over every position
// with numpy; no code is shared). Before the levels it self-tests on the full-tower minima for four pegs, which are
// known exactly (Frame-Stewart numbers, proven optimal by Bousch 2014).
//
// Usage: node verify.mjs ../../tests/reasoning/plans-ladder.json   (or cases.json)
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

const WORDS = { four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12, thirteen: 13 };

/** The puzzle stated in a prompt: pegs (bottom to top), disk count, goal peg index. */
export function parseHanoi(prompt) {
  const pegs = [...prompt.matchAll(/^- Peg ([A-Z]): (.*)$/gm)];
  const names = pegs.map((m) => m[1]);
  const stacks = pegs.map((m) => (/^empty\.$/.test(m[2]) ? [] : [...m[2].matchAll(/disk (\d+)/g)].map((x) => Number(x[1]))));
  const n = WORDS[prompt.match(/There are four pegs, A, B, C and D, and (\w+) disks/)[1]];
  const goal = names.indexOf(prompt.match(/get all \w+ disks onto peg ([A-Z])\?/)[1]);
  const all = stacks.flat().sort((a, b) => a - b);
  if (names.join('') !== 'ABCD' || all.length !== n || all.some((d, i) => d !== i + 1)) throw new Error('bad position');
  for (const s of stacks) for (let i = 1; i < s.length; i++) if (s[i] > s[i - 1]) throw new Error('a disk on a smaller disk');
  return { n, stacks, goal };
}

/** Fewest moves from `stacks` to every disk on peg `goal` (forward BFS over packed positions). */
export function shortest(n, stacks, goal) {
  // position = for each disk (size 1..n), its peg in two bits; disk k uses bits 2(k-1), 2(k-1)+1
  let start = 0;
  stacks.forEach((s, p) => s.forEach((d) => { start += p * 4 ** (d - 1); }));
  let target = 0;
  for (let d = 1; d <= n; d++) target += goal * 4 ** (d - 1);
  const size = 4 ** n;
  const seen = new Uint8Array(size);
  let frontier = new Int32Array(1);
  frontier[0] = start;
  seen[start] = 1;
  const pow = Array.from({ length: n }, (_, k) => 4 ** k);
  let next = new Int32Array(1 << 16);
  for (let depth = 0; ; depth++) {
    let m = 0;
    for (let i = 0; i < frontier.length; i++) {
      const s = frontier[i];
      if (s === target) return depth;
      // top disk of each peg: the smallest disk sitting on it
      const top = [n, n, n, n];
      for (let k = n - 1; k >= 0; k--) top[Math.floor(s / pow[k]) % 4] = k;
      for (let a = 0; a < 4; a++) {
        const d = top[a];
        if (d === n) continue;
        for (let b = 0; b < 4; b++) {
          if (b === a || top[b] < d) continue;
          const t = s + (b - a) * pow[d];
          if (seen[t]) continue;
          seen[t] = 1;
          if (m === next.length) { const g = new Int32Array(next.length * 2); g.set(next); next = g; }
          next[m++] = t;
        }
      }
    }
    if (m === 0) return null;
    frontier = next.slice(0, m);
  }
}

export const FRAME_STEWART = [0, 1, 3, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97];

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  for (let n = 1; n <= 10; n++) {
    const tower = Array.from({ length: n }, (_, i) => n - i);
    const d = shortest(n, [tower, [], [], []], 3);
    if (d !== FRAME_STEWART[n]) { console.log(`self-test FAILED for ${n} disks: ${d}`); process.exit(1); }
  }
  console.log('self-test: full-tower minima for 1..10 disks equal the proven four-peg values');
  const data = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const cases = Array.isArray(data) ? data : data.cases;
  let bad = 0;
  for (const c of cases) {
    const t0 = Date.now();
    const prompt = c.prompt ?? null;
    const z = prompt ? parseHanoi(prompt) : { n: c.disks, stacks: c.start, goal: 3 };
    const d = shortest(z.n, z.stacks, z.goal);
    const key = c.expected ?? c.minimum;
    if (d !== key) bad++;
    console.log(c.id ?? `L${String(c.level).padStart(2, '0')}`, d === key ? 'OK' : 'MISMATCH', `${z.n} disks, minimum ${d} (key ${key})`, `${Date.now() - t0}ms`);
  }
  process.exit(bad ? 1 : 0);
}
