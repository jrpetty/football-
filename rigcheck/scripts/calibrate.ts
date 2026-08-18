/**
 * Wave 5 — Calibrator.
 *
 * Coordinate-descent over the model's free constants against the fixture set,
 * scoring on the same metrics the gate uses. Writes
 * `data/calibration/constants.json`, which overrides the priors in constants.ts
 * at load, and appends every run to the tuning log so the trajectory is visible.
 *
 * Guard rail: if the fixture set is predominantly recalled rather than measured,
 * this REFUSES to write without --force. Fitting constants to recollection is the
 * over-fitting failure the spec warns about, and it would be indistinguishable
 * from progress in the metrics.
 */
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { COMBINE, CPU_MODEL, CPU_WEIGHTS, GPU_MODEL, RT_MODEL, VRAM_MODEL } from '../src/core/constants.ts';
import { evaluate, type Fixture } from './validate.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const force = process.argv.includes('--force');

const load = <T,>(p: string): T => JSON.parse(readFileSync(join(ROOT, p), 'utf8')) as T;

/** Each knob: where it lives, its current value, and the range to search. */
interface Knob {
  name: string;
  get: () => number;
  set: (v: number) => void;
  candidates: number[];
}

function main() {
  const data = buildEngineData({
    gpus: load('data/catalogue/gpus.json'),
    cpus: load('data/catalogue/cpus.json'),
    games: load('data/catalogue/games.json'),
    references: load('data/catalogue/references.json'),
  } as never);
  const fixtures = load<{ records: Fixture[] }>('data/fixtures/fixtures.json').records;

  const recalled = fixtures.filter((f) => f.provenance === 'model-knowledge').length;
  const advisory = recalled / fixtures.length > 0.5;

  const knobs: Knob[] = [
    {
      name: 'GPU_MODEL.computeWeight',
      get: () => GPU_MODEL.computeWeight,
      set: (v) => { GPU_MODEL.computeWeight = v; },
      candidates: [0.55, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85],
    },
    {
      name: 'GPU_MODEL.sublinearExponent',
      get: () => GPU_MODEL.sublinearExponent,
      set: (v) => { GPU_MODEL.sublinearExponent = v; },
      candidates: [0.75, 0.8, 0.85, 0.9, 0.95, 1.0],
    },
    {
      name: 'CPU_MODEL.threadExponent',
      get: () => CPU_MODEL.threadExponent,
      set: (v) => { CPU_MODEL.threadExponent = v; },
      candidates: [0.15, 0.2, 0.25, 0.3, 0.35, 0.4],
    },
    {
      name: 'COMBINE.p',
      get: () => COMBINE.p,
      set: (v) => {
        COMBINE.p = v;
        // Keep the per-resolution ladder anchored on the fitted central value.
        COMBINE.pByResolution['1080p'] = v * 0.91;
        COMBINE.pByResolution['1440p'] = v;
        COMBINE.pByResolution['3440x1440'] = v * 1.03;
        COMBINE.pByResolution['2160p'] = v * 1.12;
      },
      candidates: [4, 5, 5.5, 6, 6.58, 7, 8, 10],
    },
    {
      name: 'RT_MODEL.onVsOff',
      get: () => RT_MODEL.onVsOff,
      set: (v) => { RT_MODEL.onVsOff = v; },
      candidates: [0.4, 0.45, 0.5, 0.55, 0.6, 0.65, 0.7],
    },
    {
      name: 'CPU_WEIGHTS.sim-cpu.cacheEndowment',
      get: () => CPU_WEIGHTS['sim-cpu'].cacheEndowment,
      set: (v) => { CPU_WEIGHTS['sim-cpu'].cacheEndowment = v; },
      candidates: [0.2, 0.3, 0.42, 0.55, 0.7, 0.85],
    },
    {
      name: 'VRAM_MODEL.cliff[2].avgMultiplier',
      get: () => VRAM_MODEL.cliff[2].avgMultiplier,
      set: (v) => { VRAM_MODEL.cliff[2].avgMultiplier = v; },
      candidates: [0.5, 0.56, 0.62, 0.7, 0.78],
    },
  ];

  /**
   * Objective: median APE dominates, with p90 as a tail guard and a rank term.
   * Rank correlation is included because the product sells ordering, so a fit
   * that improves absolute error while scrambling ranks is a worse model.
   */
  const score = () => {
    const m = evaluate(fixtures, data).metrics;
    return m.medianAPE + 0.35 * m.p90APE + 0.5 * (1 - m.spearman);
  };

  const before = evaluate(fixtures, data).metrics;
  let best = score();
  console.log(`start   objective ${best.toFixed(4)}  medianAPE ${(before.medianAPE * 100).toFixed(1)}%  p90 ${(before.p90APE * 100).toFixed(1)}%  rho ${before.spearman.toFixed(3)}`);

  const history: string[] = [];
  // Two passes of coordinate descent: enough to settle with this few knobs, and
  // cheap enough to re-run on every fixture import.
  for (let pass = 1; pass <= 2; pass++) {
    for (const k of knobs) {
      const original = k.get();
      let bestVal = original;
      for (const c of k.candidates) {
        k.set(c);
        const s = score();
        if (s < best - 1e-9) {
          best = s;
          bestVal = c;
        }
      }
      k.set(bestVal);
      if (bestVal !== original) {
        const line = `pass ${pass}: ${k.name} ${original} -> ${bestVal} (objective ${best.toFixed(4)})`;
        console.log(`  ${line}`);
        history.push(line);
      }
    }
  }

  const after = evaluate(fixtures, data).metrics;
  console.log(`end     objective ${best.toFixed(4)}  medianAPE ${(after.medianAPE * 100).toFixed(1)}%  p90 ${(after.p90APE * 100).toFixed(1)}%  rho ${after.spearman.toFixed(3)}`);

  const fitted = {
    generatedAt: new Date().toISOString(),
    fixtureCount: fixtures.length,
    fixtureProvenance: advisory ? 'predominantly model-knowledge (recalled)' : 'measured',
    objective: best,
    metricsBefore: before,
    metricsAfter: after,
    constants: {
      GPU_MODEL: { computeWeight: GPU_MODEL.computeWeight, sublinearExponent: GPU_MODEL.sublinearExponent },
      CPU_MODEL: { threadExponent: CPU_MODEL.threadExponent },
      COMBINE: { p: COMBINE.p, pByResolution: COMBINE.pByResolution },
      RT_MODEL: { onVsOff: RT_MODEL.onVsOff },
      CPU_WEIGHTS,
      VRAM_MODEL: { cliff: VRAM_MODEL.cliff },
    },
  };

  mkdirSync(join(ROOT, 'data/calibration'), { recursive: true });
  const outPath = join(ROOT, 'data/calibration/constants.json');

  if (advisory && !force) {
    const proposed = join(ROOT, 'data/calibration/proposed-constants.json');
    writeFileSync(proposed, JSON.stringify(fitted, null, 2));
    console.log(
      `\nREFUSING TO WRITE ${outPath.replace(ROOT, '')}\n` +
        `${recalled}/${fixtures.length} fixtures carry provenance "model-knowledge" — recalled figures,\n` +
        'not measurements. Fitting the model to recollection is exactly the over-fitting\n' +
        'failure the spec warns about, and the metrics would not reveal it.\n' +
        'The fit was written to data/calibration/proposed-constants.json for inspection.\n' +
        'Import measured data via data/manual/, or pass --force if you accept the risk.',
    );
  } else {
    writeFileSync(outPath, JSON.stringify(fitted, null, 2));
    console.log(`\nWrote ${outPath.replace(ROOT, '')}${force && advisory ? ' (--force over recalled fixtures)' : ''}`);
  }

  mkdirSync(join(ROOT, 'data/validation'), { recursive: true });
  appendFileSync(
    join(ROOT, 'data/validation/tuning-log.md'),
    `\n## calibration ${new Date().toISOString()}\n` +
      `- fixtures: ${fixtures.length} (${advisory ? 'ADVISORY — recalled' : 'measured'})\n` +
      `- objective ${before.medianAPE.toFixed(4)} -> ${after.medianAPE.toFixed(4)} median APE\n` +
      (history.length ? history.map((h) => `- ${h}\n`).join('') : '- no knob improved the objective\n') +
      `- written: ${advisory && !force ? 'NO (proposed only)' : 'yes'}\n`,
  );
}

main();
