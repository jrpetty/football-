// Filler that ends with the exact sign-off must still score 0 on every system-prompt case (positive checks catch it).
import fs from 'node:fs';
import { checkConstraints } from '../../src/scoring/constraints.ts';
const t = JSON.parse(fs.readFileSync('../../tests/instruction/system-prompt-adherence.json', 'utf8'));
const words = ['the', 'model', 'result', 'answer', 'benchmark', 'quickly', 'random', 'value', 'system', 'data', 'story', 'river', 'light'];
let worst = 0;
for (const c of t.cases) {
  for (let k = 0; k < 5; k++) {
    const text = Array.from({ length: 20 + 7 * k }, (_, i) => words[(i * 7 + k) % words.length]).join(' ') + '.\nPip | Lumen & Loom';
    const allOk = checkConstraints(text, c.expected).every((x) => x.passed);
    if (allOk) { worst = 1; console.log('FILLER PASSES', c.id); }
  }
}
console.log(worst ? 'problem' : 'signed-off filler scores 0 on every case');
