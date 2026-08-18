/**
 * Wave 5 — Calibrator.
 *
 * Fits the per-game references (via the shared low-capacity fitter in
 * scripts/fitting.ts) on ALL fixtures, starting from the pristine seed. This is
 * the artifact that ships in data/catalogue/references.json.
 *
 * The honest generalisation estimate for this procedure comes from
 * scripts/validate.ts, which re-runs the same fitter inside grouped 5-fold
 * cross-validation and gates on the pooled out-of-fold metrics.
 *
 * Global constants are NOT fitted here. They are hand-pinned priors in
 * src/core/constants.ts, chosen against robust cross-generation part
 * equivalences; earlier knob-fitting versions of this script chased recalled
 * fixtures into physically wrong values (GPU scaling driven to linear, and a
 * high-end inflation that made a 4090 4.6x a 3060). data/calibration/
 * constants.json stays empty until a measured fixture corpus exists.
 */
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { evaluate, type Fixture } from './validate.ts';
import { fitReferences } from './fitting.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = <T,>(p: string): T => JSON.parse(readFileSync(join(ROOT, p), 'utf8')) as T;

function main() {
  const seed = load<{ records: never[]; provenance?: unknown }>('data/catalogue/references.seed.json');
  const data = buildEngineData({
    gpus: load('data/catalogue/gpus.json'),
    cpus: load('data/catalogue/cpus.json'),
    games: load('data/catalogue/games.json'),
    references: seed,
  } as never);
  const fixtures = load<{ records: Fixture[] }>('data/fixtures/fixtures.json').records;

  const recalled = fixtures.filter((f) => f.provenance === 'model-knowledge').length;

  const before = evaluate(fixtures, data).metrics;
  const { rescaled } = fitReferences(fixtures, data);
  const after = evaluate(fixtures, data).metrics;

  console.log(`fit on all ${fixtures.length} fixtures (in-sample; the gate's number comes from validate's cross-validation)`);
  console.log(`in-sample medianAPE ${(before.medianAPE * 100).toFixed(1)}% -> ${(after.medianAPE * 100).toFixed(1)}%, p90 ${(before.p90APE * 100).toFixed(1)}% -> ${(after.p90APE * 100).toFixed(1)}%`);
  console.log(`${rescaled.length} game reference(s) rescaled`);

  writeFileSync(
    join(ROOT, 'data/catalogue/references.json'),
    JSON.stringify(
      {
        generatedAt: new Date().toISOString(),
        note: 'Seed references fitted by scripts/calibrate.ts (scale-only, shrunk) against the full fixture set. Provenance unchanged: model-knowledge until measured data replaces it. The generalisation estimate for this procedure is validate.ts cross-validation, not the in-sample figures.',
        provenance: (seed as { provenance?: unknown }).provenance,
        count: data.references.size,
        records: [...data.references.values()],
      },
      null,
      2,
    ),
  );

  mkdirSync(join(ROOT, 'data/validation'), { recursive: true });
  appendFileSync(
    join(ROOT, 'data/validation/tuning-log.md'),
    `\n## calibration ${new Date().toISOString()}\n` +
      `- fixtures: ${fixtures.length} (${recalled} recalled)\n` +
      `- in-sample medianAPE ${(before.medianAPE * 100).toFixed(1)}% -> ${(after.medianAPE * 100).toFixed(1)}%\n` +
      rescaled.map((r) => `- ref ${r.gameId} gpuBound x${r.gpuScale} cpuBound x${r.cpuScale} (${r.n} fixtures)\n`).join('') +
      `- global constants: frozen priors (see src/core/constants.ts); constants.json untouched\n`,
  );
  console.log('references.json written; tuning log appended');
}

main();
