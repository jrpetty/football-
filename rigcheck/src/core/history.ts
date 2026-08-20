/**
 * Machine history: the same PC, measured more than once.
 *
 * A single health check is a snapshot, and a snapshot cannot answer the two
 * questions people most want answered. "Did the thing I just did actually
 * help?" and "is this machine getting slower?" both need a second measurement
 * and something that knows how to compare them.
 *
 * Comparing them properly is harder than subtracting two numbers, because a
 * frame rate moves for reasons that have nothing to do with the machine
 * degrading: a different preset, a game patch, a driver update, a different
 * scene. Everything here therefore works on MATCHED measurements — same game,
 * same resolution, same preset, same upscaling, same ray-tracing tier — and
 * refuses to compare anything else. An unmatched pair is reported as
 * unmatchable rather than silently differenced, because a confident wrong
 * answer about degradation sends someone dismantling a working machine.
 */
import type { DetectedSystem, Measurement } from './health.ts';

/** One run of the health check, kept so a later one can be compared to it. */
export interface HealthSession {
  id: string;
  /** Stable across configuration changes; changes when the CPU or GPU changes. */
  machineId: string;
  machineLabel: string;
  /** ISO timestamp. */
  at: string;
  system: DetectedSystem;
  measurements: Measurement[];
  /** Finding ids outstanding at the time, so later sessions can see what was fixed. */
  findings: { id: string; title: string; severity: string; estimatedGainPct?: number }[];
  /** Fixes the operator says they applied since the previous session. */
  fixesApplied: AppliedFix[];
  note?: string;
}

export interface AppliedFix {
  findingId: string;
  title: string;
  /** What the report said this would be worth, so the claim can be checked. */
  predictedGainPct?: number;
  appliedAt: string;
}

/**
 * Machine identity.
 *
 * Deliberately the CPU and GPU only. Swapping memory, a drive or a cooler is
 * something you do TO a machine and the history should survive it — that is
 * the whole point of before-and-after. Swapping the CPU or GPU makes it a
 * different machine, and carrying a frame-rate history across that boundary
 * would produce a "degradation" report about a part that is no longer present.
 */
export function machineId(system: DetectedSystem): string {
  return `${system.build.cpuId}::${system.build.gpuId}`;
}

/** A measurement's identity for matching. Everything that changes what it means. */
export function measurementKey(m: Measurement): string {
  const up = m.upscaling as { tech?: string; quality?: string; frameGen?: boolean } | undefined;
  return [
    m.gameId,
    m.resolution,
    (m.preset ?? 'high').toLowerCase(),
    up?.tech ?? 'none',
    up?.quality ?? 'native',
    up?.frameGen ? 'fg' : 'nofg',
    m.rtTier ?? 'off',
  ].join('|');
}

export interface MatchedPair {
  key: string;
  gameId: string;
  before: Measurement;
  after: Measurement;
  beforeAt: string;
  afterAt: string;
  /** Fractional change in the average. 0.1 = 10% faster than before. */
  deltaPct: number;
  /** Same, for the 1% low. Undefined when either session omitted it. */
  lowDeltaPct?: number;
  daysApart: number;
}

/** Measurements present in both sessions at identical settings. */
export function matchMeasurements(before: HealthSession, after: HealthSession): {
  matched: MatchedPair[];
  unmatchable: { key: string; reason: string }[];
} {
  const beforeByKey = new Map(before.measurements.map((m) => [measurementKey(m), m]));
  const afterByKey = new Map(after.measurements.map((m) => [measurementKey(m), m]));
  const matched: MatchedPair[] = [];
  const unmatchable: { key: string; reason: string }[] = [];
  const days = Math.max(0, Math.round((Date.parse(after.at) - Date.parse(before.at)) / 86400000));

  for (const [key, a] of afterByKey) {
    const b = beforeByKey.get(key);
    if (!b) {
      // Same game at different settings is the common case, and saying so is
      // more useful than "not found" — it tells them what to re-run.
      const sameGame = [...beforeByKey.keys()].filter((k) => k.split('|')[0] === key.split('|')[0]);
      unmatchable.push({
        key,
        reason: sameGame.length
          ? `${key.split('|')[0]} was measured before, but at different settings. Frame rates are only comparable at identical settings, so re-run it at the earlier configuration to get a comparison.`
          : `${key.split('|')[0]} was not measured in the earlier session, so there is nothing to compare it against.`,
      });
      continue;
    }
    matched.push({
      key,
      gameId: a.gameId,
      before: b,
      after: a,
      beforeAt: before.at,
      afterAt: after.at,
      deltaPct: a.avgFps / b.avgFps - 1,
      lowDeltaPct:
        b.low1PctFps && a.low1PctFps ? a.low1PctFps / b.low1PctFps - 1 : undefined,
      daysApart: days,
    });
  }
  return { matched, unmatchable };
}

// ---------------------------------------------------------------------------
// Did the fix work?
// ---------------------------------------------------------------------------

export interface FixVerdict {
  fix: AppliedFix;
  /** Measured change across matched pairs, as a fraction. */
  actualGainPct: number | null;
  predictedGainPct?: number;
  /** How many matched measurements the verdict rests on. */
  samples: number;
  outcome: 'delivered' | 'partial' | 'no-change' | 'worse' | 'unverifiable';
  detail: string;
  /**
   * True when the measured gain is far enough from the prediction to be worth
   * treating as evidence about the MODEL rather than about the machine.
   */
  modelDisagreement: boolean;
}

/**
 * Compare what a fix was predicted to be worth against what it actually
 * delivered.
 *
 * The interesting outcome is not "it worked". It is a fix that was predicted to
 * be worth 47% and delivered 12%, because that is evidence the model's number
 * was wrong — and a tool that quietly ignores its own failed prediction is
 * doing the one thing this project is built to avoid.
 *
 * Only meaningful where the fix is the ONLY thing that changed. Multiple fixes
 * applied together share one measurement and cannot be told apart; that is
 * reported rather than attributed arbitrarily.
 */
export function verifyFixes(before: HealthSession, after: HealthSession): FixVerdict[] {
  const { matched } = matchMeasurements(before, after);
  const fixes = after.fixesApplied;
  if (!fixes.length) return [];

  const cpuBound = matched.filter((m) => m.deltaPct !== 0);
  const median = (xs: number[]) => {
    if (!xs.length) return null;
    const s = [...xs].sort((a, b) => a - b);
    const i = Math.floor(s.length / 2);
    return s.length % 2 ? s[i] : (s[i - 1] + s[i]) / 2;
  };
  const actual = median(matched.map((m) => m.deltaPct));
  const shared = fixes.length > 1;

  return fixes.map((fix) => {
    if (actual == null || !matched.length) {
      return {
        fix,
        actualGainPct: null,
        predictedGainPct: fix.predictedGainPct,
        samples: 0,
        outcome: 'unverifiable' as const,
        detail:
          'No measurement from before this fix can be matched to one after it. Re-run the same game at the same settings as the earlier session and the comparison becomes possible.',
        modelDisagreement: false,
      };
    }

    const predicted = fix.predictedGainPct;
    let outcome: FixVerdict['outcome'];
    if (actual < -0.03) outcome = 'worse';
    else if (actual < 0.02) outcome = 'no-change';
    else if (predicted == null || actual >= predicted * 0.6) outcome = 'delivered';
    else outcome = 'partial';

    const pct = (x: number) => `${x >= 0 ? '+' : ''}${(x * 100).toFixed(0)}%`;
    const basis = `${matched.length} matched measurement${matched.length === 1 ? '' : 's'}${cpuBound.length ? '' : ' (none of which moved at all)'}`;
    const sharedNote = shared
      ? ` ${fixes.length} fixes were applied together, so this figure is their combined effect and cannot be split between them — apply one at a time if you need to know which did the work.`
      : '';

    let detail: string;
    switch (outcome) {
      case 'worse':
        detail = `The machine got SLOWER by ${pct(-actual)} across ${basis}. Something changed for the worse alongside this fix — check whether a driver, a game patch or a Windows update landed in the same window.${sharedNote}`;
        break;
      case 'no-change':
        detail = `No measurable change (${pct(actual)}) across ${basis}. Either the fix did not take effect, or this was not the constraint. Confirm the change actually applied before ruling it out.${sharedNote}`;
        break;
      case 'partial':
        detail = `Measured ${pct(actual)} against a predicted ${pct(predicted!)}, across ${basis}. Real, but well short of the prediction.${sharedNote}`;
        break;
      default:
        detail = predicted != null
          ? `Measured ${pct(actual)} against a predicted ${pct(predicted)}, across ${basis}.${sharedNote}`
          : `Measured ${pct(actual)} across ${basis}.${sharedNote}`;
    }

    // A prediction that misses by more than half its own size, in either
    // direction, is a fact about the model worth surfacing.
    const modelDisagreement =
      predicted != null && predicted > 0.02 && Math.abs(actual - predicted) > Math.max(0.08, predicted * 0.5);

    return { fix, actualGainPct: actual, predictedGainPct: predicted, samples: matched.length, outcome, detail, modelDisagreement };
  });
}

// ---------------------------------------------------------------------------
// Is it getting slower?
// ---------------------------------------------------------------------------

export type DegradationCause =
  | 'configuration-changed'
  | 'driver-changed'
  | 'thermal'
  | 'storage'
  | 'unexplained'
  | 'none';

export interface DegradationReport {
  /** Sessions in chronological order that carried at least one matched pair. */
  span: { fromAt: string; toAt: string; days: number } | null;
  pairs: MatchedPair[];
  /** Median change across matched pairs. Negative is degradation. */
  trendPct: number | null;
  degraded: boolean;
  cause: DegradationCause;
  detail: string;
  /** Anything that changed between the sessions, whether or not it explains it. */
  changes: string[];
}

/** Configuration differences between two sessions of the same machine. */
export function configChanges(before: DetectedSystem, after: DetectedSystem): string[] {
  const out: string[] = [];
  const b = before.build;
  const a = after.build;
  if (b.ram.channels !== a.ram.channels) out.push(`memory channels ${b.ram.channels} → ${a.ram.channels}`);
  if (b.ram.speedMTs !== a.ram.speedMTs) out.push(`memory speed ${b.ram.speedMTs} → ${a.ram.speedMTs} MT/s`);
  if (b.ram.totalGB !== a.ram.totalGB) out.push(`memory capacity ${b.ram.totalGB} → ${a.ram.totalGB} GB`);
  if (b.storage !== a.storage) out.push(`games drive ${b.storage} → ${a.storage}`);
  if (before.pcieLink?.width !== after.pcieLink?.width) out.push(`PCIe width x${before.pcieLink?.width ?? '?'} → x${after.pcieLink?.width ?? '?'}`);
  if (before.cooling?.airflow !== after.cooling?.airflow) out.push(`case airflow ${before.cooling?.airflow ?? '?'} → ${after.cooling?.airflow ?? '?'}`);
  if (before.driverVersion !== after.driverVersion && after.driverVersion) {
    out.push(`graphics driver ${before.driverVersion ?? 'unknown'} → ${after.driverVersion}`);
  }
  if (before.psuWatts !== after.psuWatts) out.push(`power supply ${before.psuWatts ?? '?'}W → ${after.psuWatts ?? '?'}W`);
  return out;
}

/**
 * Look for degradation between the earliest and latest session of a machine,
 * and attribute it only as far as the evidence allows.
 *
 * The attribution ladder matters. A configuration change explains a slowdown
 * completely and must be checked first — someone who pulled a memory stick has
 * a slower machine for a known reason, and calling that "thermal degradation"
 * would send them cleaning a heatsink that is fine. Only when nothing in the
 * specification changed is a physical cause worth raising, and even then the
 * strongest signal available is whether the 1% lows fell further than the
 * average, which points at streaming rather than at clocks.
 */
export function detectDegradation(sessions: HealthSession[]): DegradationReport {
  const ordered = [...sessions].sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
  if (ordered.length < 2) {
    return {
      span: null, pairs: [], trendPct: null, degraded: false, cause: 'none',
      detail: 'Only one session recorded. Degradation needs a second measurement of the same game at the same settings, taken later.',
      changes: [],
    };
  }

  const first = ordered[0];
  const last = ordered[ordered.length - 1];
  const { matched } = matchMeasurements(first, last);
  const changes = configChanges(first.system, last.system);

  if (!matched.length) {
    return {
      span: { fromAt: first.at, toAt: last.at, days: Math.round((Date.parse(last.at) - Date.parse(first.at)) / 86400000) },
      pairs: [], trendPct: null, degraded: false, cause: 'none',
      detail: 'No measurement is repeated at identical settings across these sessions, so nothing can be compared. Pick one game and one configuration and re-run it each time — that repeated measurement is what makes a history worth keeping.',
      changes,
    };
  }

  const deltas = matched.map((m) => m.deltaPct).sort((a, b) => a - b);
  const trendPct = deltas.length % 2 ? deltas[Math.floor(deltas.length / 2)] : (deltas[deltas.length / 2 - 1] + deltas[deltas.length / 2]) / 2;
  const days = matched[0].daysApart;
  const degraded = trendPct < -0.04;
  // Two checks on the same day is a normal before/after, and "0 days ago"
  // reads as a bug. Say what actually happened instead.
  const over = days === 0 ? 'since the earlier check today' : days === 1 ? 'since yesterday' : `over ${days} days`;

  const pct = (x: number) => `${(Math.abs(x) * 100).toFixed(0)}%`;
  if (!degraded) {
    return {
      span: { fromAt: first.at, toAt: last.at, days },
      pairs: matched, trendPct, degraded: false, cause: 'none',
      detail: trendPct > 0.04
        ? `This machine is ${pct(trendPct)} FASTER ${over}, across ${matched.length} matched measurement${matched.length === 1 ? '' : 's'}. Something you did worked.`
        : `Holding steady — ${pct(trendPct)} change ${over} across ${matched.length} matched measurement${matched.length === 1 ? '' : 's'}, which is within normal run-to-run variation.`,
      changes,
    };
  }

  // Attribute, most-explanatory first.
  const specChanges = changes.filter((c) => !c.startsWith('graphics driver'));
  if (specChanges.length) {
    return {
      span: { fromAt: first.at, toAt: last.at, days },
      pairs: matched, trendPct, degraded: true, cause: 'configuration-changed',
      detail: `${pct(trendPct)} slower ${over}, but the machine is not the same machine: ${specChanges.join('; ')}. That explains a slowdown without anything having degraded — re-check the findings against the current configuration before looking for a physical cause.`,
      changes,
    };
  }

  const driverChanged = changes.some((c) => c.startsWith('graphics driver'));
  if (driverChanged) {
    return {
      span: { fromAt: first.at, toAt: last.at, days },
      pairs: matched, trendPct, degraded: true, cause: 'driver-changed',
      detail: `${pct(trendPct)} slower ${over}, and the graphics driver changed in that window. Driver regressions are real and usually title-specific. Rolling back to the earlier version is a reasonable test and a normal thing to do.`,
      changes,
    };
  }

  // Nothing in the specification changed. The lows-versus-average split is the
  // only signal available that separates a streaming problem from a clock one.
  const withLows = matched.filter((m) => m.lowDeltaPct != null);
  const lowsFellFurther =
    withLows.length > 0 &&
    withLows.every((m) => (m.lowDeltaPct as number) < m.deltaPct - 0.05);

  if (lowsFellFurther) {
    return {
      span: { fromAt: first.at, toAt: last.at, days },
      pairs: matched, trendPct, degraded: true, cause: 'storage',
      detail: `${pct(trendPct)} slower ${over} with nothing changed in the specification, and the 1% lows fell further than the average. That pattern points at streaming rather than at clocks: a drive filling up, fragmentation on a mechanical disk, or a game whose install has grown. Check free space first — an SSD above about 90% full slows down markedly.`,
      changes,
    };
  }

  return {
    span: { fromAt: first.at, toAt: last.at, days },
    pairs: matched, trendPct, degraded: true, cause: 'thermal',
    detail: `${pct(trendPct)} slower ${over} with nothing changed in the specification and the 1% lows tracking the average. On a machine that has not been altered, a uniform slowdown is usually heat: dust in the heatsink and filters, or thermal paste that has dried out. Both are cheap to rule out and are the first thing to check. Verify by watching the SUSTAINED clock after fifteen minutes of load rather than the peak.`,
    changes,
  };
}

/** Chronological series for one measurement key, for a chart. */
export function seriesFor(sessions: HealthSession[], key: string): { at: string; avgFps: number; low1PctFps?: number }[] {
  return [...sessions]
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at))
    .flatMap((s) => s.measurements.filter((m) => measurementKey(m) === key).map((m) => ({ at: s.at, avgFps: m.avgFps, low1PctFps: m.low1PctFps })));
}

/** Measurement keys that appear in at least two sessions — the trackable ones. */
export function trackableKeys(sessions: HealthSession[]): { key: string; gameId: string; count: number }[] {
  const counts = new Map<string, { gameId: string; count: number }>();
  for (const s of sessions) {
    for (const k of new Set(s.measurements.map(measurementKey))) {
      const m = s.measurements.find((x) => measurementKey(x) === k)!;
      const e = counts.get(k) ?? { gameId: m.gameId, count: 0 };
      e.count++;
      counts.set(k, e);
    }
  }
  return [...counts.entries()]
    .filter(([, v]) => v.count >= 2)
    .map(([key, v]) => ({ key, gameId: v.gameId, count: v.count }))
    .sort((a, b) => b.count - a.count);
}
