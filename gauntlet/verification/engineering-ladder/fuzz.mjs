// Cross-check on random inputs: the reference (ref.js), the Python brute force and the C brute force must agree on
// every input, at every level, for explicit inputs and for small generated ones.
//   node fuzz.mjs [inputsPerLevel=400]
import { rng, load, python, cBrute, randomExplicit, same } from './lib.mjs';
import { opsUpTo, QUERIES } from './spec.mjs';

const per = Number(process.argv[2] || 400);
const ref = load('./ref.js');
// the same reference with blocks of 2 elements instead of 64, so that small inputs exercise a deep tree
const ref2 = load('./ref.js', 'runEngine', (src) => src.replace('const SH = 6,', 'const SH = 1,'));
let bad = 0, total = 0;
for (let level = 1; level <= 10; level++) {
  const R = rng(5150 + level);
  const inputs = [];
  for (let i = 0; i < per; i++) {
    if (level >= 2 && i % 4 === 3) {
      // a small generated input with the level's operations
      const ops = opsUpTo(level);
      const band = R.pick([3, 20, 1000]);
      const mix = ops.map((o) => (QUERIES.has(o) || o === 'hreset' ? [o, R.int(1, 4)] : [o, R.int(1, 4), o === 'div' ? 2 : -band, o === 'div' ? R.pick([2, 3, 7]) : band]));
      inputs.push({ generate: { seed: R.int(1, 2147483646), n: R.int(1, 30), lo: -band, hi: band, q: R.int(1, 60), mix } });
    } else inputs.push(randomExplicit(R, level, { n: R.int(1, R.pick([8, 70, 300])), q: R.int(1, 150), band: R.pick([2, 5, 30, 1000]) }));
  }
  const want = python(inputs);
  inputs.forEach((inp, i) => {
    total++;
    const got = ref(inp);
    const got2 = ref2(inp);
    const c = i % 8 === 0 ? cBrute(inp) : want[i];
    if (!same(got, want[i]) || !same(got2, want[i]) || !same(c, want[i])) {
      bad++;
      if (bad <= 5) console.log(`L${level} input ${i}: ref ${JSON.stringify(got)}, ref(blocks of 2) ${JSON.stringify(got2)}, python ${JSON.stringify(want[i])}, C ${JSON.stringify(c)}\n${JSON.stringify(inp)}`);
    }
  });
  console.log(`level ${level}: ${per} inputs checked`);
}
console.log(bad ? `${bad} DISAGREEMENTS` : `all ${total} inputs agree (ref.js with blocks of 64 and of 2, brute.py; every 8th also brute.c)`);
process.exit(bad ? 1 : 0);
