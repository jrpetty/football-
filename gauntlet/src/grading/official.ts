/**
 * Official score of a graded result (pure: the server and the UI's mock mode share it).
 * Combines the machine's score, the run's judges, Grading Station AI judges and human
 * grades under the policy in policy.ts, and writes a one-line summary of the outcome.
 */
import { mean } from '../core/stats.ts';
import type { CaseResult } from '../core/types.ts';
import type { GradingSpec } from './spec.ts';
import { officialScore, type OfficialPolicy, type OfficialResult, type OfficialSource } from './policy.ts';
import type { GradingNeed } from './types.ts';

const JUDGE_FAILURE = /all judges failed|no judges configured/i;

export function r4(n: number): number {
  return Math.round(n * 10000) / 10000;
}

function tenths(x: number | null): string {
  return x === null ? '—' : (x * 10).toFixed(1);
}

export function judgeFailed(r: CaseResult): boolean {
  return r.status === 'error' && JUDGE_FAILURE.test(r.error ?? '');
}

/** Mean of the run's own judge verdicts (null when the run recorded none). */
export function runAiScore(r: CaseResult, spec: GradingSpec): number | null {
  const d = r.scoreDetail ?? {};
  if (spec.kind === 'artifact' && typeof d.judgeScore === 'number') return d.judgeScore;
  if (spec.kind !== 'judged' && spec.kind !== 'artifact') return null;
  return mean((d.judge ?? []).map((j) => j.score));
}

/** Mean of the latest Grading Station AI batch. */
export function stationAiScore(r: Pick<CaseResult, 'aiGrades'>): number | null {
  const g = r.aiGrades ?? [];
  if (!g.length) return null;
  const last = g[g.length - 1]!.batch;
  return mean(g.filter((x) => x.batch === last).map((x) => x.score));
}

export function humanMean(r: Pick<CaseResult, 'humanScores'>): number | null {
  return mean((r.humanScores ?? []).map((h) => h.score));
}

// ───────────────────────────── Official score ─────────────────────────────

/** Recompute the official score of a result from its stored grades under a policy. Returns a new object. */
export function applyOfficial(r: CaseResult, spec: GradingSpec, policy: OfficialPolicy): CaseResult {
  const d = { ...(r.scoreDetail ?? {}) };
  // Remember what the machine / run recorded before any station grade touched it.
  if (!('autoScore' in d)) {
    d.autoScore = r.status === 'pending-human' || r.status === 'error' ? null : d.arbitrated ? ((d.automatedScore as number | undefined) ?? r.score) : r.score;
    d.autoSummary = r.summary;
  }
  const autoScore = (d.autoScore as number | null | undefined) ?? null;
  const human = humanMean(r);
  const ai = stationAiScore(r);
  const run = runAiScore(r, spec);
  const off: OfficialResult = officialScore(
    {
      humanRole: spec.humanRole,
      aiRole: spec.aiRole,
      humanScored: spec.kind === 'human',
      judgeWeight: spec.kind === 'artifact' ? (spec.judgeWeight ?? 0) : null,
      autoScore,
      checkScore: typeof d.checkScore === 'number' ? d.checkScore : null,
      runAiScore: run,
      stationAiScore: ai,
      humanScore: human,
      disagreement: Boolean(d.judgeDisagreement),
    },
    policy,
  );
  const out: CaseResult = { ...r, scoreDetail: d };
  if (human !== null) d.humanScore = r4(human);
  d.official = { source: off.source, policy, why: off.why };
  if (off.score === null) return out;
  // A cap the scorer applied (a Game Jam game that froze, stayed blank or loads from the internet) still holds.
  const cap = /capped at (\d+)/.exec(String((d.gameJam as { cap?: string } | undefined)?.cap ?? ''))?.[1];
  if (cap !== undefined) off.score = Math.min(off.score, Number(cap) / 100);
  out.score = off.score;
  out.passed = off.score >= spec.passThreshold;
  if (r.status === 'pending-human' || judgeFailed(r)) {
    out.status = 'ok';
    out.error = undefined;
  }
  if (spec.kind === 'human' && (off.source === 'human' || off.source === 'average')) d.humanScored = true;
  d.arbitrated = off.source === 'arbitration' || undefined;
  if (off.source === 'arbitration') d.automatedScore = autoScore;
  out.summary = officialSummary(off.source, { human, ai, run, raters: r.humanScores?.length ?? 0, auto: autoScore, autoSummary: (d.autoSummary as string | undefined) ?? r.summary, spec, score: off.score });
  return out;
}

function officialSummary(source: OfficialSource, x: { human: number | null; ai: number | null; run: number | null; raters: number; auto: number | null; autoSummary: string; spec: GradingSpec; score: number }): string {
  const raters = `${x.raters} rater${x.raters === 1 ? '' : 's'}`;
  const pre = x.spec.kind === 'artifact' && x.spec.judgeWeight ? `${Math.round(x.score * 100)}/100 · ` : '';
  const ai = x.run ?? x.ai;
  switch (source) {
    case 'arbitration':
      return `Arbitrated by humans ${tenths(x.human)}/10 (judges disagreed; panel gave ${tenths(x.auto)})`;
    case 'human':
      return `${pre}Human grade ${tenths(x.human)}/10 (${raters})${ai !== null ? ` · AI ${tenths(ai)}/10` : ''}`;
    case 'average':
      return `${pre}Human ${tenths(x.human)} and AI ${tenths(ai)} averaged`;
    case 'ai':
      if (x.run !== null) return `${x.autoSummary}${x.human !== null ? ` · human ${tenths(x.human)}/10` : ''}`;
      return `${pre}AI judges ${tenths(x.ai)}/10 (graded in the Grading Station)${x.human !== null ? ` · human ${tenths(x.human)}/10` : ''}`;
    default:
      return x.autoSummary;
  }
}

export function needOf(r: CaseResult, spec: GradingSpec): GradingNeed {
  if (spec.humanRole === 'dispute') return 'review';
  if (r.status === 'pending-human') return 'grade';
  if (judgeFailed(r)) return 'judge-failed';
  if (r.scoreDetail?.judgeDisagreement) return 'arbitrate';
  // The Gallery: the owner's artistry rating always counts (it replaces the judges' artistry).
  if (spec.scorerType.startsWith('program:gallery')) return 'owner-rating';
  return 'second-opinion';
}
