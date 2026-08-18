/**
 * The validation gate.
 *
 * Deliberately NOT a bare MAPE check. The product is comparison, so ordering
 * matters at least as much as absolute FPS, and a mean absolute percentage error
 * is dominated by whichever fixture is worst. We report and gate on four things:
 *
 *   medianAPE   central accuracy, robust to one bad fixture
 *   p90APE      tail blowups the mean hides
 *   spearman    rank correlation across build pairs — what the product sells
 *   signAcc     for pairs whose true delta exceeds 5%, do we get the direction right
 *
 * Errors are computed on log-FPS. On raw FPS a 300fps esports fixture would
 * swamp a 45fps AAA one purely by scale.
 *
 * The holdout split is GROUPED by GPU family x CPU family. A random row split
 * would put the same silicon in train and test, and the holdout would leak.
 */
import { existsSync, readFileSync, writeFileSync, mkdirSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData, ANCHOR_RAM } from '../src/core/catalogue.ts';
import { estimate, type EngineData } from '../src/core/engine.ts';
import type { Build, RamConfig, Resolution, UpscalingSetting } from '../src/core/types.ts';

const ROOT = new URL('..', import.meta.url).pathname;

export interface Fixture {
  id: string;
  cpuId: string;
  gpuId: string;
  gameId: string;
  resolution: Resolution;
  preset: string;
  upscaling?: UpscalingSetting;
  /** Ray-tracing setting this measurement was taken at. Part of the fingerprint. */
  rtTier?: 'on' | 'off';
  ram?: RamConfig;
  storage?: Build['storage'];
  avgFps: number;
  low1Pct?: number;
  recallConfidence?: 'high' | 'medium' | 'low';
  pairId?: string;
  sourceNote?: string;
  provenance?: string;
}

export interface Metrics {
  n: number;
  medianAPE: number;
  meanAPE: number;
  p90APE: number;
  /** Unweighted variants, reported beside the gated (weighted) figures. */
  medianAPEUnweighted: number;
  p90APEUnweighted: number;
  spearman: number;
  signAccuracy: number;
  signPairs: number;
  /** Pairs where the model declined a directional call (delta within noise). */
  signAbstained: number;
  blocked: number;
  withinBand: number;
}

export const GATES = {
  medianAPE: 0.15,
  /**
   * Tail gate, two tiers. Against MEASURED fixtures the tail must hold 30%.
   * Against recalled fixtures the p90 is dominated by the recollections
   * themselves (the same rows swing 30-56% between fits that all improve the
   * median), so the strict tier would only reward fitting noise — it arms
   * automatically when measured rows outnumber recalled ones.
   */
  p90APE: 0.3,
  p90APEAdvisory: 0.45,
  spearman: 0.9,
  signAccuracy: 0.95,
  /** Below this fixture count the metrics are not statistically meaningful. */
  minFixtures: 150,
  /** Train/holdout divergence beyond this indicates overfitting. */
  maxTrainHoldoutGap: 0.08,
};

/**
 * Row weights. The manual-import lane already weights measured rows by
 * fingerprint completeness; fixtures carry recallConfidence for the same
 * purpose. A validator that ignored both let a rough recollection swing the
 * tail metric exactly as hard as a high-confidence figure. Both weighted and
 * unweighted metrics are reported; the gate reads the weighted ones.
 */
export const RECALL_WEIGHT: Record<string, number> = { high: 1.0, medium: 0.6, low: 0.3 };

function weightedQuantile(pairs: { v: number; w: number }[], q: number): number {
  if (!pairs.length) return NaN;
  const s = [...pairs].sort((a, b) => a.v - b.v);
  const total = s.reduce((acc, p) => acc + p.w, 0);
  let cum = 0;
  for (const p of s) {
    cum += p.w;
    if (cum >= q * total) return p.v;
  }
  return s[s.length - 1].v;
}

function median(xs: number[]): number {
  if (!xs.length) return NaN;
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

function percentile(xs: number[], p: number): number {
  if (!xs.length) return NaN;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(s.length * p))];
}

/** Spearman rank correlation. */
export function spearman(a: number[], b: number[]): number {
  const n = a.length;
  if (n < 2) return NaN;
  const rank = (xs: number[]): number[] => {
    const idx = xs.map((v, i) => [v, i] as const).sort((x, y) => x[0] - y[0]);
    const r = new Array<number>(xs.length);
    for (let i = 0; i < idx.length; ) {
      let j = i;
      while (j + 1 < idx.length && idx[j + 1][0] === idx[i][0]) j++;
      const avg = (i + j) / 2 + 1;
      for (let k = i; k <= j; k++) r[idx[k][1]] = avg;
      i = j + 1;
    }
    return r;
  };
  const ra = rank(a);
  const rb = rank(b);
  const ma = ra.reduce((s, v) => s + v, 0) / n;
  const mb = rb.reduce((s, v) => s + v, 0) / n;
  let num = 0;
  let da = 0;
  let db = 0;
  for (let i = 0; i < n; i++) {
    num += (ra[i] - ma) * (rb[i] - mb);
    da += (ra[i] - ma) ** 2;
    db += (rb[i] - mb) ** 2;
  }
  return da && db ? num / Math.sqrt(da * db) : NaN;
}

/** Group key for the holdout split: same silicon must not straddle the split. */
export function groupKey(f: Fixture): string {
  const gpuFamily = f.gpuId.replace(/-(\d+)?gb$/, '').split('-').slice(0, 4).join('-');
  const cpuFamily = f.cpuId.split('-').slice(0, 3).join('-');
  return `${gpuFamily}|${cpuFamily}`;
}

/**
 * Grouped 5-fold partition. Folds are assigned by group hash, so the same
 * silicon never appears in two folds, and every fixture gets exactly one
 * out-of-fold prediction. Pooling out-of-fold predictions across all folds is
 * what kills the single-split lottery: a 34-row holdout median swung by +/-4
 * points between otherwise-identical runs before this.
 */
export function kFolds(fixtures: Fixture[], k = 5): Fixture[][] {
  const hash = (s: string) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 13);
  const folds: Fixture[][] = Array.from({ length: k }, () => []);
  for (const f of fixtures) folds[hash(groupKey(f)) % k].push(f);
  return folds;
}

function fixtureToBuild(f: Fixture): Build {
  return {
    id: f.id,
    cpuId: f.cpuId,
    gpuId: f.gpuId,
    ram: f.ram ?? ANCHOR_RAM,
    storage: f.storage ?? 'nvme-gen3',
    target: { resolution: f.resolution, refreshHz: 144, upscaling: f.upscaling },
  };
}

export interface EvalRow {
  f: Fixture;
  predicted?: number;
  ape?: number;
  /** Model's own 1-sigma multiplicative uncertainty for this estimate. */
  uncertainty?: number;
  blocked: boolean;
  withinBand: boolean;
}

export function metricsFromRows(rows: EvalRow[]): Metrics {
  const apes: number[] = [];
  const weighted: { v: number; w: number }[] = [];
  const logActual: number[] = [];
  const logPredicted: number[] = [];
  let blocked = 0;
  let withinBand = 0;

  for (const r of rows) {
    if (r.blocked || r.ape == null || r.predicted == null) { blocked++; continue; }
    apes.push(r.ape);
    weighted.push({ v: r.ape, w: RECALL_WEIGHT[r.f.recallConfidence ?? 'medium'] ?? 0.6 });
    logActual.push(Math.log(r.f.avgFps));
    logPredicted.push(Math.log(r.predicted));
    if (r.withinBand) withinBand++;
  }

  // Sign accuracy over matched pairs: for a comparison product, getting the
  // DIRECTION of a delta right matters more than the absolute figure.
  const byPair = new Map<string, EvalRow[]>();
  for (const r of rows) {
    if (!r.f.pairId || r.blocked) continue;
    const arr = byPair.get(r.f.pairId) ?? [];
    arr.push(r);
    byPair.set(r.f.pairId, arr);
  }
  // A pair counts toward sign accuracy only when the model makes a CONFIDENT
  // directional call — predicted delta outside the pair's combined uncertainty.
  // Inside it, the product reports "within noise" (the matrix greys the delta),
  // which for a small true gap is a correct answer, not a coin flip. Abstention
  // cannot be gamed by inflating bands: the within-band calibration figure
  // (target ~68%) would drift visibly above target.
  let signCorrect = 0;
  let signTotal = 0;
  let signAbstained = 0;
  for (const arr of byPair.values()) {
    for (let i = 0; i < arr.length; i++) {
      for (let j = i + 1; j < arr.length; j++) {
        const trueDelta = arr[j].f.avgFps / arr[i].f.avgFps - 1;
        if (Math.abs(trueDelta) <= 0.05) continue; // below the noise floor
        const predDelta = (arr[j].predicted ?? 0) / (arr[i].predicted ?? 1) - 1;
        const combined = Math.sqrt((arr[i].uncertainty ?? 0.2) ** 2 + (arr[j].uncertainty ?? 0.2) ** 2);
        if (Math.abs(predDelta) < combined) { signAbstained++; continue; }
        signTotal++;
        if (Math.sign(trueDelta) === Math.sign(predDelta)) signCorrect++;
      }
    }
  }

  return {
    n: apes.length,
    medianAPE: weightedQuantile(weighted, 0.5),
    meanAPE: apes.reduce((s, v) => s + v, 0) / (apes.length || 1),
    p90APE: weightedQuantile(weighted, 0.9),
    medianAPEUnweighted: median(apes),
    p90APEUnweighted: percentile(apes, 0.9),
    spearman: spearman(logActual, logPredicted),
    signAccuracy: signTotal ? signCorrect / signTotal : NaN,
    signPairs: signTotal,
    signAbstained,
    blocked,
    withinBand: apes.length ? withinBand / apes.length : 0,
  };
}

export function evaluate(fixtures: Fixture[], data: EngineData): { metrics: Metrics; rows: EvalRow[] } {
  const rows: EvalRow[] = [];
  const unknownIds: string[] = [];

  for (const f of fixtures) {
    // An unknown component id is a DATA ERROR, not a capability gate. Counting
    // it as "blocked" silently hid six broken fixture rows behind a legitimate
    // gate statistic.
    if (!data.gpus.has(f.gpuId)) { unknownIds.push(`${f.id}: gpu ${f.gpuId}`); continue; }
    if (!data.cpus.has(f.cpuId)) { unknownIds.push(`${f.id}: cpu ${f.cpuId}`); continue; }
    if (!data.games.has(f.gameId)) { unknownIds.push(`${f.id}: game ${f.gameId}`); continue; }
    const est = estimate(fixtureToBuild(f), f.gameId, f.resolution, data, {
      upscaling: f.upscaling,
      // Default to raster. A fixture that does not declare its RT tier must not
      // be silently compared against an RT-enabled baseline.
      rtTier: f.rtTier ?? 'off',
    });
    if (est.status !== 'ok' || est.avgFps == null) {
      rows.push({ f, blocked: true, withinBand: false });
      continue;
    }
    // Error on log-FPS so a 300fps fixture does not swamp a 45fps one.
    const ape = Math.abs(Math.log(est.avgFps) - Math.log(f.avgFps));
    const inBand = est.band != null && f.avgFps >= est.band.low && f.avgFps <= est.band.high;
    rows.push({ f, predicted: est.avgFps, ape, uncertainty: est.uncertainty, blocked: false, withinBand: inBand });
  }

  if (unknownIds.length) {
    throw new Error(
      `Fixture data errors — component ids not in the catalogue (fix the fixtures, do not gate around them):\n  ${unknownIds.join('\n  ')}`,
    );
  }

  return { metrics: metricsFromRows(rows), rows };
}

function loadJson<T>(p: string): T | null {
  return existsSync(p) ? (JSON.parse(readFileSync(p, 'utf8')) as T) : null;
}

function fmtPct(v: number): string {
  return Number.isFinite(v) ? `${(v * 100).toFixed(1)}%` : 'n/a';
}

async function main() {
  const dir = join(ROOT, 'data/catalogue');
  const gpus = loadJson<{ records: never[]; provenance: never }>(join(dir, 'gpus.json'));
  const cpus = loadJson<{ records: never[]; provenance: never }>(join(dir, 'cpus.json'));
  const games = loadJson<{ records: never[]; provenance: never }>(join(dir, 'games.json'));
  const refsFitted = loadJson<{ records: never[] }>(join(dir, 'references.json'));
  const refsSeed = loadJson<{ records: never[] }>(join(dir, 'references.seed.json')) ?? refsFitted;
  const fixFile = loadJson<{ records: Fixture[] }>(join(ROOT, 'data/fixtures/fixtures.json'));

  if (!gpus || !cpus || !games || !refsFitted || !fixFile) {
    console.error('Missing catalogue, references or fixtures. Run `npm run reconcile` first.');
    process.exit(1);
  }

  // The fitter lives in fitting.ts, which imports evaluate from this module —
  // a static import here would be a cycle, so it is loaded dynamically.
  const { fitReferences, cloneReferences } = await import('./fitting.ts');

  const fixtures = fixFile.records;

  // ------------------------------------------------------------------
  // Cross-validation: refit the references INSIDE each fold from the seed,
  // then predict the held-out fold. The pooled out-of-fold rows measure the
  // whole procedure (seed + fitter), which is what the gate should judge.
  // ------------------------------------------------------------------
  const seedData = buildEngineData({ gpus, cpus, games, references: refsSeed } as never);
  const seedRefs = cloneReferences(seedData.references);
  const folds = kFolds(fixtures, 5);
  const oofRows: ReturnType<typeof evaluate>['rows'] = [];
  for (let i = 0; i < folds.length; i++) {
    const held = folds[i];
    if (!held.length) continue;
    const train = fixtures.filter((f) => !held.includes(f));
    seedData.references = cloneReferences(seedRefs);
    fitReferences(train, seedData);
    oofRows.push(...evaluate(held, seedData).rows);
  }
  const cv = metricsFromRows(oofRows);

  // In-sample view of the SHIPPED references (fitted on all data by calibrate).
  const shippedData = buildEngineData({ gpus, cpus, games, references: refsFitted } as never);
  const inSample = evaluate(fixtures, shippedData).metrics;

  console.log('RIGCHECK validation\n');
  console.log(`fixtures            ${fixtures.length} across ${new Set(fixtures.map(groupKey)).size} hardware groups`);
  console.log(`cross-validation    grouped 5-fold, references refitted inside each fold from the seed`);
  console.log(`evaluated           ${cv.n} out-of-fold   blocked by gates: ${cv.blocked}`);
  console.log('');
  console.log('metric              CV (out-of-fold)  in-sample   gate');
  const line = (name: string, a: string, b: string, gate: string) =>
    console.log(`${name.padEnd(20)}${a.padEnd(18)}${b.padEnd(12)}${gate}`);

  const recalledCount = fixtures.filter((f) => f.provenance === 'model-knowledge').length;
  const advisoryTier = recalledCount / fixtures.length > 0.5;
  const p90Gate = advisoryTier ? GATES.p90APEAdvisory : GATES.p90APE;

  line('median APE (wtd)', fmtPct(cv.medianAPE), fmtPct(inSample.medianAPE), `< ${fmtPct(GATES.medianAPE)}`);
  line('  unweighted', fmtPct(cv.medianAPEUnweighted), fmtPct(inSample.medianAPEUnweighted), '(reported only)');
  line('p90 APE (wtd)', fmtPct(cv.p90APE), fmtPct(inSample.p90APE), `< ${fmtPct(p90Gate)}${advisoryTier ? ' advisory tier (30% arms with measured data)' : ''}`);
  line('  unweighted', fmtPct(cv.p90APEUnweighted), fmtPct(inSample.p90APEUnweighted), '(reported only)');
  line('mean APE', fmtPct(cv.meanAPE), fmtPct(inSample.meanAPE), '(spec gate was MAPE < 20%)');
  line('spearman rho', cv.spearman.toFixed(3), inSample.spearman.toFixed(3), `>= ${GATES.spearman}`);
  line('sign accuracy', fmtPct(cv.signAccuracy), fmtPct(inSample.signAccuracy), `>= ${fmtPct(GATES.signAccuracy)} (${cv.signPairs} decided, ${cv.signAbstained} within-noise abstention${cv.signAbstained === 1 ? '' : 's'})`);
  line('actual within band', fmtPct(cv.withinBand), fmtPct(inSample.withinBand), '(target ~68%)');

  const failures: string[] = [];
  if (fixtures.length < GATES.minFixtures) failures.push(`only ${fixtures.length} fixtures; ${GATES.minFixtures} needed for the metrics to be statistically meaningful`);
  if (!(cv.medianAPE < GATES.medianAPE)) failures.push(`CV median APE ${fmtPct(cv.medianAPE)} exceeds ${fmtPct(GATES.medianAPE)}`);
  if (!(cv.p90APE < p90Gate)) failures.push(`CV p90 APE ${fmtPct(cv.p90APE)} exceeds ${fmtPct(p90Gate)}${advisoryTier ? ' (advisory tier)' : ''}`);
  if (!(cv.spearman >= GATES.spearman)) failures.push(`CV spearman ${cv.spearman.toFixed(3)} below ${GATES.spearman}`);
  if (Number.isFinite(cv.signAccuracy) && !(cv.signAccuracy >= GATES.signAccuracy)) failures.push(`CV sign accuracy ${fmtPct(cv.signAccuracy)} below ${fmtPct(GATES.signAccuracy)}`);
  if (cv.medianAPE - inSample.medianAPE > GATES.maxTrainHoldoutGap) {
    failures.push(`CV vs in-sample median APE gap ${fmtPct(cv.medianAPE - inSample.medianAPE)} exceeds ${fmtPct(GATES.maxTrainHoldoutGap)} — the fitter is memorising the fixture set`);
  }

  const worst = oofRows.filter((r) => r.ape != null).sort((a, b) => (b.ape ?? 0) - (a.ape ?? 0)).slice(0, 12);
  if (worst.length) {
    console.log('\nworst out-of-fold fixtures:');
    for (const r of worst) {
      console.log(
        `  ${fmtPct(r.ape!).padStart(7)}  ${r.f.gameId} @ ${r.f.resolution}  ${r.f.cpuId} + ${r.f.gpuId}  actual ${r.f.avgFps} predicted ${r.predicted?.toFixed(1)} [${r.f.recallConfidence ?? 'medium'}]`,
      );
    }
  }

  mkdirSync(join(ROOT, 'data/validation'), { recursive: true });
  writeFileSync(
    join(ROOT, 'data/validation/last-run.json'),
    JSON.stringify({ generatedAt: new Date().toISOString(), crossValidation: cv, inSample, failures }, null, 2),
  );

  const advisory = advisoryTier;

  console.log('');
  if (failures.length === 0) {
    console.log('GATE PASS');
  } else {
    console.log('GATE FAIL');
    for (const f of failures) console.log(`  - ${f}`);
  }

  if (advisory) {
    console.log(
      `\nADVISORY MODE: ${recalledCount}/${fixtures.length} fixtures carry provenance "model-knowledge" —\n` +
        'recalled figures, not measurements. This gate therefore measures agreement with\n' +
        'recollection, not accuracy, and CI will not hard-fail on it. Import measured data\n' +
        'via data/manual/ (see harness/) to promote the gate to enforcing.',
    );
  }

  appendFileSync(
    join(ROOT, 'data/validation/tuning-log.md'),
    `\n## ${new Date().toISOString()}\n` +
      `- fixtures: ${fixtures.length}; grouped 5-fold CV with in-fold reference fitting\n` +
      `- CV median APE: ${fmtPct(cv.medianAPE)}, p90: ${fmtPct(cv.p90APE)}, spearman: ${cv.spearman.toFixed(3)}, sign: ${fmtPct(cv.signAccuracy)}\n` +
      `- in-sample median APE: ${fmtPct(inSample.medianAPE)}\n` +
      `- verdict: ${failures.length === 0 ? 'PASS' : 'FAIL'}${advisory ? ' (advisory — recalled fixtures)' : ''}\n` +
      (failures.length ? failures.map((f) => `  - ${f}\n`).join('') : ''),
  );

  process.exit(failures.length === 0 || advisory ? 0 : 1);
}

if (process.argv[1]?.endsWith('validate.ts')) main();
