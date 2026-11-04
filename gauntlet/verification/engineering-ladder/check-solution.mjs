// Checks that a spread solution follows the specification: on random small inputs it must agree with the Python
// brute force (correctness only, no time limit). Slips solutions are expected to disagree somewhere.
//   node check-solution.mjs ./solutions/plain.js [inputsPerLevel=200]
import { rng, load, python, randomExplicit, same } from './lib.mjs';
const [file, per = '200'] = process.argv.slice(2);
const f = load(file);
for (let level = 1; level <= 10; level++) {
  const R = rng(777 + level);
  const inputs = Array.from({ length: Number(per) }, () => randomExplicit(R, level, { n: R.int(1, R.pick([8, 70, 300])), q: R.int(1, 150), band: R.pick([2, 5, 30, 1000]) }));
  const want = python(inputs);
  const bad = inputs.filter((x, i) => !same(f(x), want[i])).length;
  console.log(`L${level}: ${bad ? `${bad}/${inputs.length} DIFFER` : 'all agree'}`);
}
