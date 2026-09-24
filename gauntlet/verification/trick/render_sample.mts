// Prints a seeded sample of rendered trick prompts (exactly what a model sees, no keys) for the blind check.
// Usage: node render_sample.mts [count=15] [seed=7]
import { loadTests, renderCase } from '../../src/core/registry.ts';
import { createRng } from '../../src/core/rng.ts';
import type { PromptTest } from '../../src/core/types.ts';

const count = Number(process.argv[2] ?? 15);
const rng = createRng(Number(process.argv[3] ?? 7));
const tests = loadTests()
  .map((t) => t.definition)
  .filter((d): d is PromptTest => d.kind === 'prompt' && d.category === 'trick');
const pool = tests.flatMap((d) => d.cases.map((c) => ({ d, c })));
for (const { d, c } of rng.shuffle(pool).slice(0, count)) {
  const r = renderCase(d, c);
  console.log(`\n===== ${d.id} / ${c.id} =====`);
  if (r.system) console.log(`[system]\n${r.system}\n`);
  console.log(r.turns.join('\n---\n'));
}
