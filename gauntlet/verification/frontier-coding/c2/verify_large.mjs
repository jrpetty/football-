// Slow: verify the large c2 cases with slab.py (Python Fractions). Run in the background.
import fs from 'node:fs';
import { python } from '../lib.mjs';
const spec = JSON.parse(fs.readFileSync(new URL('./tests.json', import.meta.url), 'utf8'));
const labels = JSON.parse(fs.readFileSync(new URL('./labels.json', import.meta.url), 'utf8'));
let bad = 0;
labels.forEach((l, i) => {
  if (!l.startsWith('large') && !l.startsWith('medium')) return;
  const t0 = Date.now();
  const [r] = python('./c2/slab.py', [spec.tests[i].args]);
  const ok = r === spec.tests[i].expected; if (!ok) bad++;
  console.log(ok ? 'OK ' : 'BAD', l, r, `${((Date.now() - t0) / 1000).toFixed(0)}s`);
});
console.log(bad ? `${bad} MISMATCHES` : 'all large/scaled cases confirmed by slab.py');
