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
  spearman: number;
  signAccuracy: number;
  signPairs: number;
  blocked: number;
  withinBand: number;
}

export const GATES = {
  medianAPE: 0.15,
  p90APE: 0.3,
  spearman: 0.9,
  signAccuracy: 0.95,
  /** Below this fixture count the metrics are not statistically meaningful. */
  minFixtures: 150,
  /** Train/holdout divergence beyond this indicates overfitting. */
  maxTrainHoldoutGap: 0.08,
};

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

export function evaluate(fixtures: Fixture[], data: EngineData): { metrics: Metrics; rows: { f: Fixture; predicted?: number; ape?: number; blocked: boolean; withinBand: boolean }[] } {
  const rows: { f: Fixture; predicted?: number; ape?: number; blocked: boolean; withinBand: boolean }[] = [];
  const logActual: number[] = [];
  const logPredicted: number[] = [];
  const apes: number[] = [];
  let blocked = 0;
  let withinBand = 0;

  for (const f of fixtures) {
    const est = estimate(fixtureToBuild(f), f.gameId, f.resolution, data, {
      upscaling: f.upscaling,
      // Default to raster. A fixture that does not declare its RT tier must not
      // be silently compared against an RT-enabled baseline.
      rtTier: f.rtTier ?? 'off',
    });
    if (est.status !== 'ok' || est.avgFps == null) {
      blocked++;
      rows.push({ f, blocked: true, withinBand: false });
      continue;
    }
    // Error on log-FPS so a 300fps fixture does not swamp a 45fps one.
    const ape = Math.abs(Math.log(est.avgFps) - Math.log(f.avgFps));
    apes.push(ape);
    logActual.push(Math.log(f.avgFps));
    logPredicted.push(Math.log(est.avgFps));
    const inBand = est.band != null && f.avgFps >= est.band.low && f.avgFps <= est.band.high;
    if (inBand) withinBand++;
    rows.push({ f, predicted: est.avgFps, ape, blocked: false, withinBand: inBand });
  }

  // Sign accuracy over matched pairs: for a comparison product, getting the
  // DIRECTION of a delta right matters more than the absolute figure.
  const byPair = new Map<string, typeof rows>();
  for (const r of rows) {
    if (!r.f.pairId || r.blocked) continue;
    const arr = byPair.get(r.f.pairId) ?? [];
    arr.push(r);
    byPair.set(r.f.pairId, arr);
  }
  let signCorrect = 0;
  let signTotal = 0;
  for (const arr of byPair.values()) {
    for (let i = 0; i < arr.length; i++) {
      for (let j = i + 1; j < arr.length; j++) {
        const trueDelta = arr[j].f.avgFps / arr[i].f.avgFps - 1;
        if (Math.abs(trueDelta) <= 0.05) continue; // below the noise floor
        const predDelta = (arr[j].predicted ?? 0) / (arr[i].predicted ?? 1) - 1;
        signTotal++;
        if (Math.sign(trueDelta) === Math.sign(predDelta)) signCorrect++;
      }
    }
  }

  return {
    metrics: {
      n: apes.length,
      medianAPE: median(apes),
      meanAPE: apes.reduce((s, v) => s + v, 0) / (apes.length || 1),
      p90APE: percentile(apes, 0.9),
      spearman: spearman(logActual, logPredicted),
      signAccuracy: signTotal ? signCorrect / signTotal : NaN,
      signPairs: signTotal,
      blocked,
      withinBand: apes.length ? withinBand / apes.length : 0,
    },
    rows,
  };
}

function loadJson<T>(p: string): T | null {
  return existsSync(p) ? (JSON.parse(readFileSync(p, 'utf8')) as T) : null;
}

function fmtPct(v: number): string {
  return Number.isFinite(v) ? `${(v * 100).toFixed(1)}%` : 'n/a';
}

function main() {
  const dir = join(ROOT, 'data/catalogue');
  const gpus = loadJson<{ records: never[]; provenance: never }>(join(dir, 'gpus.json'));
  const cpus = loadJson<{ records: never[]; provenance: never }>(join(dir, 'cpus.json'));
  const games = loadJson<{ records: never[]; provenance: never }>(join(dir, 'games.json'));
  const refs = loadJson<{ records: never[] }>(join(ROOT, 'data/catalogue/references.json'));
  const fixFile = loadJson<{ records: Fixture[] }>(join(ROOT, 'data/fixtures/fixtures.json'));

  if (!gpus || !cpus || !games || !refs || !fixFile) {
    console.error('Missing catalogue, references or fixtures. Run `npm run reconcile` first.');
    process.exit(1);
  }

  const data = buildEngineData({ gpus, cpus, games, references: refs } as never);
  const fixtures = fixFile.records;

  // Grouped holdout: hash the group key so the split is deterministic and the
  // same silicon never straddles train and test.
  const groups = [...new Set(fixtures.map(groupKey))].sort();
  const hash = (s: string) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);
  const holdoutGroups = new Set(groups.filter((g) => hash(g) % 5 === 0));
  const train = fixtures.filter((f) => !holdoutGroups.has(groupKey(f)));
  const holdout = fixtures.filter((f) => holdoutGroups.has(groupKey(f)));

  const all = evaluate(fixtures, data);
  const trainM = evaluate(train, data).metrics;
  const holdoutM = holdout.length ? evaluate(holdout, data).metrics : null;

  console.log('RIGCHECK validation\n');
  console.log(`fixtures            ${fixtures.length} (${train.length} train / ${holdout.length} holdout across ${groups.length} groups)`);
  console.log(`evaluated           ${all.metrics.n}   blocked by gates: ${all.metrics.blocked}`);
  console.log('');
  console.log('metric              all        train      holdout    gate');
  const line = (name: string, a: number, t: number, h: number | undefined, gate: string) =>
    console.log(`${name.padEnd(20)}${fmtPct(a).padEnd(11)}${fmtPct(t).padEnd(11)}${(h != null ? fmtPct(h) : 'n/a').padEnd(11)}${gate}`);
  line('median APE', all.metrics.medianAPE, trainM.medianAPE, holdoutM?.medianAPE, `< ${fmtPct(GATES.medianAPE)}`);
  line('p90 APE', all.metrics.p90APE, trainM.p90APE, holdoutM?.p90APE, `< ${fmtPct(GATES.p90APE)}`);
  line('mean APE', all.metrics.meanAPE, trainM.meanAPE, holdoutM?.meanAPE, '(reported only)');
  console.log(`${'spearman rho'.padEnd(20)}${all.metrics.spearman.toFixed(3).padEnd(11)}${trainM.spearman.toFixed(3).padEnd(11)}${(holdoutM ? holdoutM.spearman.toFixed(3) : 'n/a').padEnd(11)}>= ${GATES.spearman}`);
  console.log(`${'sign accuracy'.padEnd(20)}${fmtPct(all.metrics.signAccuracy).padEnd(11)}${''.padEnd(11)}${''.padEnd(11)}>= ${fmtPct(GATES.signAccuracy)} (${all.metrics.signPairs} pairs)`);
  console.log(`${'actual within band'.padEnd(20)}${fmtPct(all.metrics.withinBand)}`);

  // Gate evaluation. Uses holdout where available — train-only numbers prove nothing.
  const m = holdoutM ?? all.metrics;
  const failures: string[] = [];
  if (fixtures.length < GATES.minFixtures) failures.push(`only ${fixtures.length} fixtures; ${GATES.minFixtures} needed for the metrics to be statistically meaningful`);
  if (!(m.medianAPE < GATES.medianAPE)) failures.push(`median APE ${fmtPct(m.medianAPE)} exceeds ${fmtPct(GATES.medianAPE)}`);
  if (!(m.p90APE < GATES.p90APE)) failures.push(`p90 APE ${fmtPct(m.p90APE)} exceeds ${fmtPct(GATES.p90APE)}`);
  if (!(m.spearman >= GATES.spearman)) failures.push(`spearman ${m.spearman.toFixed(3)} below ${GATES.spearman}`);
  if (Number.isFinite(m.signAccuracy) && !(m.signAccuracy >= GATES.signAccuracy)) failures.push(`sign accuracy ${fmtPct(m.signAccuracy)} below ${fmtPct(GATES.signAccuracy)}`);
  if (holdoutM && Math.abs(holdoutM.medianAPE - trainM.medianAPE) > GATES.maxTrainHoldoutGap) {
    failures.push(`train/holdout median APE gap ${fmtPct(Math.abs(holdoutM.medianAPE - trainM.medianAPE))} exceeds ${fmtPct(GATES.maxTrainHoldoutGap)} — the model is fitting the fixture set, not reality`);
  }

  // Worst offenders, so tuning is directed rather than by eye.
  const worst = all.rows.filter((r) => r.ape != null).sort((a, b) => (b.ape ?? 0) - (a.ape ?? 0)).slice(0, 12);
  if (worst.length) {
    console.log('\nworst fixtures:');
    for (const r of worst) {
      console.log(
        `  ${fmtPct(r.ape!).padStart(7)}  ${r.f.gameId} @ ${r.f.resolution}  ${r.f.cpuId} + ${r.f.gpuId}  actual ${r.f.avgFps} predicted ${r.predicted?.toFixed(1)}`,
      );
    }
  }

  mkdirSync(join(ROOT, 'data/validation'), { recursive: true });
  writeFileSync(
    join(ROOT, 'data/validation/last-run.json'),
    JSON.stringify({ generatedAt: new Date().toISOString(), all: all.metrics, train: trainM, holdout: holdoutM, failures }, null, 2),
  );

  // Provenance of the fixture set determines whether this gate means anything.
  const recalled = fixtures.filter((f) => f.provenance === 'model-knowledge').length;
  const advisory = recalled / fixtures.length > 0.5;

  console.log('');
  if (failures.length === 0) {
    console.log('GATE PASS');
  } else {
    console.log('GATE FAIL');
    for (const f of failures) console.log(`  - ${f}`);
  }

  if (advisory) {
    console.log(
      `\nADVISORY MODE: ${recalled}/${fixtures.length} fixtures carry provenance "model-knowledge" —\n` +
        'recalled figures, not measurements. This gate therefore measures agreement with\n' +
        'recollection, not accuracy, and CI will not hard-fail on it. Import measured data\n' +
        'via data/manual/ (see harness/) to promote the gate to enforcing.',
    );
  }

  appendFileSync(
    join(ROOT, 'data/validation/tuning-log.md'),
    `\n## ${new Date().toISOString()}\n` +
      `- fixtures: ${fixtures.length} (${holdout.length} holdout)\n` +
      `- median APE: ${fmtPct(m.medianAPE)}, p90: ${fmtPct(m.p90APE)}, spearman: ${m.spearman.toFixed(3)}, sign: ${fmtPct(m.signAccuracy)}\n` +
      `- verdict: ${failures.length === 0 ? 'PASS' : 'FAIL'}${advisory ? ' (advisory — recalled fixtures)' : ''}\n` +
      (failures.length ? failures.map((f) => `  - ${f}\n`).join('') : ''),
  );

  // Hard-fail CI only when the fixture set is real. Failing on recalled data
  // would be theatre.
  process.exit(failures.length === 0 || advisory ? 0 : 1);
}

if (process.argv[1]?.endsWith('validate.ts')) main();
