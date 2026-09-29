/**
 * "Best on each test": data shapes and the ranking rules (pure, shared with the UI so filters re-rank in the browser).
 *
 * Ranking: highest score first. Scores equal to 4 decimals share a rank (1, 1, 3). The leader is every rank-1
 * model with a score above zero. Models that took the test but have no score yet (all errors, or waiting for a
 * human grade) are listed last without a rank.
 */
import type { CategoryInfo, ManualModelInfo } from '../core/types.ts';

export interface BestModel {
  id: string;
  label: string;
  vendor: string;
  color: string;
  family?: string;
  /** YYYY-MM-DD, for the timeline and the release-year filter. */
  releaseDate: string | null;
  /** Replies pasted in by hand (copy & paste). */
  manual: boolean;
  /** Copy & paste model from the catalogue: interface + settings. */
  manualModel?: ManualModelInfo;
  /** e.g. "copied by hand · claude.ai" (empty for API models). */
  howLabel: string;
  /** Ran through OpenRouter instead of the company's own API. */
  routed?: boolean;
}

export interface BestEntry {
  contestantId: string;
  /** 0..1 mean over cases (null = took the test but nothing scored yet). */
  score: number | null;
  ci95: [number, number] | null;
  /** Scored answers (cases × repeats) behind the score. */
  n: number;
  /** Every answer on record, including errors. */
  attempts: number;
  passRate: number | null;
  /** When it last took this test (ISO). */
  lastTestedAt: string | null;
  /** How many runs it took this test in. */
  runs: number;
  /** Of `n`: answers that were moved to this model after the fact ("Reassign to a model"). */
  reassigned: number;
}

export interface BestTest {
  id: string;
  name: string;
  category: string;
  entries: BestEntry[];
}

export interface BestData {
  generatedAt: string;
  categories: CategoryInfo[];
  tests: BestTest[];
  models: BestModel[];
  /** Results left out because the test or the model's settings changed since. */
  staleExcluded: number;
}

export interface RankedEntry extends BestEntry {
  /** 1-based, shared by ties; null when there is no score. */
  rank: number | null;
  leader: boolean;
  tied: boolean;
}

const round4 = (v: number) => Math.round(v * 1e4) / 1e4;

/** Rank one test's entries (see the file comment). `labelOf` breaks display ties alphabetically. */
export function rankEntries(entries: BestEntry[], labelOf: (id: string) => string = (id) => id): RankedEntry[] {
  const scored = entries.filter((e) => e.score !== null).sort((a, b) => round4(b.score!) - round4(a.score!) || b.n - a.n || labelOf(a.contestantId).localeCompare(labelOf(b.contestantId)));
  const unscored = entries.filter((e) => e.score === null).sort((a, b) => labelOf(a.contestantId).localeCompare(labelOf(b.contestantId)));
  const out: RankedEntry[] = [];
  scored.forEach((e, i) => {
    const prev = out[i - 1];
    const rank = prev && round4(prev.score!) === round4(e.score!) ? prev.rank! : i + 1;
    out.push({ ...e, rank, leader: rank === 1 && e.score! > 0, tied: false });
  });
  for (const e of out) e.tied = out.filter((x) => x.rank === e.rank).length > 1;
  return [...out, ...unscored.map((e) => ({ ...e, rank: null, leader: false, tied: false }))];
}

export interface BestFilters {
  vendor: string;
  /** Release year ("2024"), or '' for all. */
  year: string;
  kind: 'all' | 'api' | 'manual';
  category: string;
}

export const NO_FILTERS: BestFilters = { vendor: '', year: '', kind: 'all', category: '' };

export function modelPasses(m: BestModel, f: BestFilters): boolean {
  if (f.vendor && m.vendor !== f.vendor) return false;
  if (f.year && (m.releaseDate ?? '').slice(0, 4) !== f.year) return false;
  if (f.kind === 'api' && m.manual) return false;
  if (f.kind === 'manual' && !m.manual) return false;
  return true;
}

/** The tests (with ranked, filtered entries) to show; tests left with no entries are dropped. */
export function viewBest(data: BestData, f: BestFilters): Array<BestTest & { ranked: RankedEntry[] }> {
  const models = new Map(data.models.map((m) => [m.id, m]));
  const labelOf = (id: string) => models.get(id)?.label ?? id;
  return data.tests
    .filter((t) => !f.category || t.category === f.category)
    .map((t) => ({ ...t, ranked: rankEntries(t.entries.filter((e) => models.has(e.contestantId) && modelPasses(models.get(e.contestantId)!, f)), labelOf) }))
    .filter((t) => t.ranked.length > 0);
}

/** Release years present (newest first), for the filter. */
export function releaseYears(data: BestData): string[] {
  return [...new Set(data.models.map((m) => (m.releaseDate ?? '').slice(0, 4)).filter(Boolean))].sort().reverse();
}

/** Timeline for one test: scored models with a release date, oldest first. */
export function timelineFor(data: BestData, testId: string, f: BestFilters): Array<{ model: BestModel; entry: RankedEntry }> {
  const t = viewBest(data, { ...f, category: '' }).find((x) => x.id === testId);
  if (!t) return [];
  const models = new Map(data.models.map((m) => [m.id, m]));
  return t.ranked
    .filter((e) => e.score !== null && models.get(e.contestantId)?.releaseDate)
    .map((entry) => ({ model: models.get(entry.contestantId)!, entry }))
    .sort((a, b) => a.model.releaseDate!.localeCompare(b.model.releaseDate!) || a.model.label.localeCompare(b.model.label));
}
