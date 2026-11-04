// Dev helper: node try.mjs <module> [caseId] — per-case stats (after salt re-rolls), and the prompt + key for one case.
import { tok } from './lib.mjs';
import { buildWorld } from './build.mjs';
const mod = await import(`./${process.argv[2]}.mjs`);
for (const spec of mod.SPECS) {
  const { world, sol, salt } = buildWorld(mod, spec);
  const text = mod.render(world);
  console.log(spec.id, spec.tier, tok(text), 'tok', 'salt', salt, mod.summary(world, sol));
  if (process.argv[3] === spec.id) {
    console.log(text);
    console.log(JSON.stringify(sol, null, 1));
  }
}
