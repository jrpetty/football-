/**
 * Model history — Gauntlet Index (or one category) against release date, one
 * line per model family, with "biggest jump" callouts.
 */
import { loadContestants, loadProviders } from '../core/config.ts';
import type { Contestant, Leaderboard } from '../core/types.ts';
import { combinedLeaderboard } from '../engine/leaderboards.ts';
import type { HistoryData, HistoryJump, HistoryPoint } from './types.ts';

const DAY = 86_400_000;

/** Family of a contestant: explicit `family`, else its vendor. */
export function familyOf(c: Pick<Contestant, 'family' | 'vendor'>): string {
  return c.family?.trim() || c.vendor || 'Other';
}

function metricValue(p: HistoryPoint, metric: string): number | null {
  if (metric === 'index') return p.index;
  const v = p.categoryScores[metric];
  return typeof v === 'number' ? Math.round(v * 1000) / 10 : null;
}

export function valueOf(p: HistoryPoint, metric: string): number | null {
  return metricValue(p, metric);
}

/**
 * Pure builder (unit-tested): combine a leaderboard with contestant metadata.
 * `exclude` removes ids (e.g. the random baseline); `tiers` keeps only those tiers (empty = all).
 */
export function buildHistory(
  board: Leaderboard,
  contestants: Contestant[],
  opts: { metric?: string; tiers?: string[]; exclude?: Set<string>; suiteId?: string } = {},
): HistoryData {
  const metric = opts.metric || 'index';
  const tiers = new Set((opts.tiers ?? []).filter(Boolean));
  const rows = new Map(board.rows.map((r) => [r.contestantId, r]));
  const points: HistoryPoint[] = [];
  for (const c of contestants) {
    if (opts.exclude?.has(c.id)) continue;
    if (tiers.size && !(c.tier && tiers.has(c.tier))) continue;
    const row = rows.get(c.id);
    const releaseDate = c.releaseDate && /^\d{4}-\d{2}-\d{2}$/.test(c.releaseDate) ? c.releaseDate : null;
    points.push({
      contestantId: c.id,
      label: c.label,
      vendor: c.vendor,
      color: c.color,
      family: familyOf(c),
      tier: c.tier ?? null,
      releaseDate,
      index: row?.index ?? null,
      indexCi95: row?.indexCi95 ?? null,
      categoryScores: row?.categoryScores ?? {},
      coverage: row?.coverage ?? 0,
    });
  }
  const hasValue = (p: HistoryPoint) => metricValue(p, metric) !== null;
  const plotted = points.filter((p) => p.releaseDate && hasValue(p));
  const undated = points.filter((p) => !p.releaseDate && hasValue(p));
  const noResults = points.filter((p) => !hasValue(p));

  const byFamily = new Map<string, HistoryPoint[]>();
  for (const p of plotted) {
    const arr = byFamily.get(p.family) ?? [];
    arr.push(p);
    byFamily.set(p.family, arr);
  }
  const families = [...byFamily.entries()]
    .map(([family, pts]) => {
      pts.sort((a, b) => a.releaseDate!.localeCompare(b.releaseDate!) || a.label.localeCompare(b.label));
      // Family colour: its flagship (or first) model's colour, so the line matches the model chips.
      const lead = [...pts].reverse().find((p) => p.tier === 'flagship') ?? pts[pts.length - 1]!;
      // The family line follows its flagship models (when it has at least two), taking the best model at each
      // release date; smaller siblings stay as dots so a cheap "mini" release never reads as a regression.
      const flagships = pts.filter((p) => p.tier === 'flagship');
      const lineSource = flagships.length >= 2 ? flagships : pts;
      const best = new Map<string, HistoryPoint>();
      for (const p of lineSource) {
        const cur = best.get(p.releaseDate!);
        if (!cur || metricValue(p, metric)! > metricValue(cur, metric)!) best.set(p.releaseDate!, p);
      }
      return { family, color: lead.color, points: pts, line: [...best.values()] };
    })
    .sort((a, b) => a.family.localeCompare(b.family));

  const jumps: HistoryJump[] = [];
  for (const f of families) {
    for (let i = 1; i < f.line.length; i++) {
      const a = f.line[i - 1]!;
      const b = f.line[i]!;
      const va = metricValue(a, metric)!;
      const vb = metricValue(b, metric)!;
      jumps.push({
        family: f.family,
        from: { contestantId: a.contestantId, label: a.label, releaseDate: a.releaseDate!, value: va },
        to: { contestantId: b.contestantId, label: b.label, releaseDate: b.releaseDate!, value: vb },
        delta: Math.round((vb - va) * 10) / 10,
        days: Math.round((Date.parse(b.releaseDate!) - Date.parse(a.releaseDate!)) / DAY),
      });
    }
  }
  jumps.sort((a, b) => b.delta - a.delta);
  return {
    suiteId: opts.suiteId ?? (board.scope.kind === 'combined' ? board.scope.suiteId : 'run'),
    metric,
    families,
    undated: undated.sort((a, b) => a.label.localeCompare(b.label)),
    noResults: noResults.sort((a, b) => a.label.localeCompare(b.label)),
    jumps: jumps.filter((j) => j.delta > 0).slice(0, 3),
    generatedAt: new Date().toISOString(),
  };
}

/** Contestants that never belong on a history chart: the random baseline, and manual placeholders without a release date. */
export function simulatedIds(): Set<string> {
  const providers = loadProviders();
  const type = (c: Contestant) => providers.find((p) => p.id === c.provider)?.type;
  return new Set(loadContestants().filter((c) => type(c) === 'mock' || (type(c) === 'manual' && !c.releaseDate)).map((c) => c.id));
}

export function historyFor(suiteId: string, opts: { metric?: string; tiers?: string[] } = {}): HistoryData {
  return buildHistory(combinedLeaderboard(suiteId), loadContestants(), { ...opts, exclude: simulatedIds(), suiteId });
}
