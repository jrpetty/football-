// Cross-check on random command streams: the reference, the second JavaScript implementation (alt.js) and the
// Python oracle must agree on every reply, at every level.
//   node fuzz.mjs [streamsPerLevel=400]
import { rng, load, oracle, randomStream, same } from './lib.mjs';
import fs from 'node:fs';

const per = Number(process.argv[2] || 400);
const impls = [['ref', load('./ref.js')]];
if (fs.existsSync(new URL('./alt.js', import.meta.url))) impls.push(['alt', load('./alt.js')]);
let bad = 0, total = 0;
for (let level = 1; level <= 10; level++) {
  const R = rng(9000 + level);
  const streams = [];
  for (let i = 0; i < per; i++) streams.push(randomStream(R, level, { n: R.int(5, 90), big: R.chance(0.2) }));
  const want = oracle(streams);
  for (let i = 0; i < streams.length; i++) {
    total++;
    for (const [name, f] of impls) {
      const got = f(streams[i]);
      if (!same(got, want[i])) {
        bad++;
        if (bad <= 5) {
          const j = got.findIndex((x, k) => x !== want[i][k]);
          console.log(`L${level} stream ${i} ${name}: first difference at command ${j} ${JSON.stringify(streams[i][j])}: got ${JSON.stringify(got[j])}, oracle ${JSON.stringify(want[i][j])}`);
          console.log(JSON.stringify(streams[i].slice(0, j + 1)));
        }
      }
    }
  }
  console.log(`level ${level}: ${per} streams checked`);
}
console.log(bad ? `${bad} DISAGREEMENTS` : `all ${total} streams agree (${impls.map((x) => x[0]).join(', ')} and the Python oracle)`);
process.exit(bad ? 1 : 0);
