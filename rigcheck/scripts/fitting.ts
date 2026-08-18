/**
 * Per-game reference fitting, shared by the calibrator (final all-data fit that
 * ships) and the validator (in-fold fits for cross-validation, so the gate
 * measures the PROCEDURE rather than a particular fit).
 *
 * Deliberately low-capacity: two bounded scale factors per game (gpuBound and
 * cpuBound), shrunk toward 1 by n/(n+4). Earlier, higher-capacity versions —
 * wider grids, per-game exponents, refitting between knob passes — pushed the
 * train/holdout gap past the 8% overfit tripwire. Structural constants stay in
 * src/core/constants.ts as hand-pinned priors; they are not fitted here.
 */
import type { EngineData, GameReference } from '../src/core/engine.ts';
import { evaluate, type Fixture } from './validate.ts';

const SCALES = [0.75, 0.82, 0.9, 0.95, 1.0, 1.05, 1.11, 1.2, 1.3];

export interface RefFitResult {
  rescaled: { gameId: string; gpuScale: number; cpuScale: number; n: number }[];
}

/** Mutates `data.references` in place; returns what moved. */
export function fitReferences(trainFixtures: Fixture[], data: EngineData): RefFitResult {
  const byGame = new Map<string, Fixture[]>();
  for (const f of trainFixtures) {
    const arr = byGame.get(f.gameId) ?? [];
    arr.push(f);
    byGame.set(f.gameId, arr);
  }

  const rescaled: RefFitResult['rescaled'] = [];

  for (const [gameId, fs] of byGame) {
    if (fs.length < 2) continue;
    const ref = data.references.get(gameId);
    if (!ref) continue;

    const gameScore = () => {
      const m = evaluate(fs, data).metrics;
      return m.medianAPE + 0.35 * m.p90APE;
    };
    const baseGpu = { ...ref.gpuBound };
    const baseCpu = ref.cpuBound;
    const shrink = fs.length / (fs.length + 4);

    let bestS = 1.0;
    let bestVal = gameScore();
    for (const s of SCALES) {
      for (const res of Object.keys(baseGpu) as (keyof typeof baseGpu)[]) {
        ref.gpuBound[res] = baseGpu[res]! * s;
      }
      const v = gameScore();
      if (v < bestVal - 1e-9) { bestVal = v; bestS = s; }
    }
    const appliedS = Math.exp(Math.log(bestS) * shrink);
    for (const res of Object.keys(baseGpu) as (keyof typeof baseGpu)[]) {
      ref.gpuBound[res] = Number((baseGpu[res]! * appliedS).toFixed(2));
    }

    let bestC = 1.0;
    for (const s of SCALES) {
      ref.cpuBound = baseCpu * s;
      const v = gameScore();
      if (v < bestVal - 1e-9) { bestVal = v; bestC = s; }
    }
    const appliedC = Math.exp(Math.log(bestC) * shrink);
    ref.cpuBound = Number((baseCpu * appliedC).toFixed(1));

    if (appliedS !== 1.0 || appliedC !== 1.0) {
      rescaled.push({ gameId, gpuScale: Number(appliedS.toFixed(3)), cpuScale: Number(appliedC.toFixed(3)), n: fs.length });
    }
  }
  return { rescaled };
}

/** Deep-clone a reference map so an in-fold fit cannot touch the seed. */
export function cloneReferences(refs: Map<string, GameReference>): Map<string, GameReference> {
  return new Map(
    [...refs.entries()].map(([k, v]) => [k, { ...v, gpuBound: { ...v.gpuBound } }]),
  );
}
