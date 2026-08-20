/**
 * The evidence ladder: how much any one fixture is allowed to count for.
 *
 * This lives in core rather than in the validation script because two things
 * need it and they must not be allowed to disagree: the gate that decides
 * whether a model change is an improvement, and the Model Health screen that
 * shows an operator what the gate is currently standing on. A dashboard quoting
 * a different weighting from the gate would be worse than no dashboard.
 *
 * The principle is that not all evidence is equal and the numbers should say so.
 * A frametime capture from the harness on known hardware outranks a figure
 * recalled from training data by a wide margin, so better data actually changes
 * the answer rather than merely being present.
 */
import type { Build, RamConfig, Resolution, UpscalingSetting } from './types.ts';

export type ProvenanceTier = 'harness' | 'manual-measured' | 'reviewer' | 'community' | 'model-knowledge';

export interface Fixture {
  id: string;
  cpuId: string;
  gpuId: string;
  gameId: string;
  resolution: Resolution;
  preset: string;
  upscaling?: UpscalingSetting;
  rtTier?: 'on' | 'off';
  ram?: RamConfig;
  storage?: Build['storage'];
  avgFps: number;
  low1Pct?: number;
  recallConfidence?: 'high' | 'medium' | 'low';
  /** Fixtures measured as an A/B pair; sign accuracy is scored over these. */
  pairId?: string;
  stratum?: string;
  sourceNote?: string;
  provenance?: string;
}

/**
 * Source-quality ladder. These weights also drive automatic gate promotion:
 * once measured rows carry the majority of the evidence weight, the strict
 * thresholds arm themselves.
 */
export const SOURCE_TIER: Record<string, { weight: number; label: string; measured: boolean }> = {
  harness: { weight: 1.0, label: 'measured by the operator harness (PresentMon frametime capture)', measured: true },
  'manual-measured': { weight: 0.9, label: 'operator-supplied measurement', measured: true },
  reviewer: { weight: 0.7, label: 'published reviewer dataset under licence', measured: false },
  community: { weight: 0.5, label: 'community-submitted benchmark', measured: false },
  'model-knowledge': { weight: 0.25, label: 'recalled from training data — NOT a measurement', measured: false },
};

/** Order the ladder is displayed and reasoned about in, best evidence first. */
export const TIER_ORDER: ProvenanceTier[] = ['harness', 'manual-measured', 'reviewer', 'community', 'model-knowledge'];

export const RECALL_WEIGHT: Record<string, number> = { high: 1.0, medium: 0.6, low: 0.3 };

/** Combined row weight: source tier x stated recall confidence. */
export function rowWeight(f: Fixture): number {
  const tier = SOURCE_TIER[f.provenance ?? 'model-knowledge']?.weight ?? 0.25;
  const recall = RECALL_WEIGHT[f.recallConfidence ?? 'medium'] ?? 0.6;
  // Recall confidence only modulates recalled rows; a measurement is a
  // measurement regardless of how well anyone remembers taking it.
  return f.provenance === 'model-knowledge' ? tier * recall : tier;
}

/** Share of total evidence weight that comes from genuine measurements. */
export function measuredShare(fixtures: Fixture[]): number {
  const total = fixtures.reduce((s, f) => s + rowWeight(f), 0);
  if (!total) return 0;
  const measured = fixtures
    .filter((f) => SOURCE_TIER[f.provenance ?? 'model-knowledge']?.measured)
    .reduce((s, f) => s + rowWeight(f), 0);
  return measured / total;
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

/** The strict tail gate arms once measurements outweigh recollections. */
export const STRICT_GATE_ARMS_AT = 0.5;

export interface LadderRung {
  tier: string;
  label: string;
  perRowWeight: number;
  measured: boolean;
  rows: number;
  weight: number;
  /** Share of total evidence weight this rung carries. */
  weightShare: number;
}

export interface EvidenceLadder {
  rungs: LadderRung[];
  totalRows: number;
  totalWeight: number;
  measuredShare: number;
  strictGateArmed: boolean;
  /**
   * How many harness rows, added to the current set, would arm the strict tail
   * gate. Solves w/(W+w) >= 0.5 for w at the harness weight of 1.0 per row.
   */
  harnessRowsToArmStrictGate: number;
}

/**
 * Bucket a fixture set by evidence tier and work out what it would take to
 * promote the gate. The last figure is the one that makes the abstract ladder
 * actionable: it turns "the data is recalled" into a row count someone can go
 * and measure.
 */
export function evidenceLadder(fixtures: Fixture[]): EvidenceLadder {
  const byTier = new Map<string, { rows: number; weight: number }>();
  for (const f of fixtures) {
    const tier = f.provenance ?? 'model-knowledge';
    const acc = byTier.get(tier) ?? { rows: 0, weight: 0 };
    acc.rows += 1;
    acc.weight += rowWeight(f);
    byTier.set(tier, acc);
  }

  const totalWeight = [...byTier.values()].reduce((s, v) => s + v.weight, 0);
  const known = TIER_ORDER.filter((t) => byTier.has(t));
  const unknown = [...byTier.keys()].filter((t) => !TIER_ORDER.includes(t as ProvenanceTier));

  const rungs: LadderRung[] = [...known, ...unknown].map((tier) => {
    const v = byTier.get(tier)!;
    const meta = SOURCE_TIER[tier];
    return {
      tier,
      label: meta?.label ?? `unrecognised provenance "${tier}" — weighted as recalled`,
      perRowWeight: meta?.weight ?? 0.25,
      measured: meta?.measured ?? false,
      rows: v.rows,
      weight: v.weight,
      weightShare: totalWeight ? v.weight / totalWeight : 0,
    };
  });

  const share = measuredShare(fixtures);
  const measuredWeight = share * totalWeight;
  const recalledWeight = totalWeight - measuredWeight;
  // w / (measured + recalled + w) >= 0.5  =>  w >= recalled - measured
  const deficit = recalledWeight - measuredWeight;

  return {
    rungs,
    totalRows: fixtures.length,
    totalWeight,
    measuredShare: share,
    strictGateArmed: share >= STRICT_GATE_ARMS_AT,
    harnessRowsToArmStrictGate: deficit <= 0 ? 0 : Math.ceil(deficit / SOURCE_TIER.harness.weight),
  };
}

export interface CoverageCell {
  key: string;
  rows: number;
  weight: number;
}

/**
 * Where the fixture set is thin, by whichever dimension you ask for.
 *
 * This is a COVERAGE measure, not an information-gain calculation: it says
 * which cells have least evidence behind them, not which measurement would move
 * the model most. Those usually coincide, but not always, and claiming the
 * stronger thing would be dishonest.
 */
export function thinnestCells(fixtures: Fixture[], by: (f: Fixture) => string, limit = 12): CoverageCell[] {
  const cells = new Map<string, CoverageCell>();
  for (const f of fixtures) {
    const key = by(f);
    const c = cells.get(key) ?? { key, rows: 0, weight: 0 };
    c.rows += 1;
    c.weight += rowWeight(f);
    cells.set(key, c);
  }
  return [...cells.values()].sort((a, b) => a.weight - b.weight || a.rows - b.rows).slice(0, limit);
}
