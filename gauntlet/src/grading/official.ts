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

/**
 * A cap the scorer applied to a result (a Game Jam game that froze, stayed blank or loads files from the internet is
 * capped at 30) also holds for any grade that replaces the score: Grading Station, Blind Review arbitration.
 */
export function withScorerCap(score: number, detail: CaseResult['scoreDetail'] | undefined): number {
  // A fair-play violation scored 0 (src/scoring/integrity.ts); no grade can give it back.
  if (detail?.integrity?.verdict === 'cheating') return 0;
  const cap = /capped at (\d+)/.exec(String((detail?.gameJam as { cap?: string } | undefined)?.cap ?? ''))?.[1];
  return cap === undefined ? score : Math.min(score, Number(cap) / 100);
}

/** The judges disagreed, or only one of several judges answered: a person should arbitrate (Blind Review, Grading Station). */
export function needsArbitration(detail: CaseResult['scoreDetail'] | undefined): boolean {
  return Boolean(detail?.judgeDisagreement || detail?.singleJudge);
}

/**
 * The Game Jam's own rule: a game cut off at the output limit is an unfinished file, so its judged share scores 0
 * (the judges are not asked). A human or Grading Station grade can't give it back, under any policy.
 */
export function jamTruncated(detail: CaseResult['scoreDetail'] | undefined): boolean {
  return (detail?.gameJam as { truncated?: boolean } | undefined)?.truncated === true;
}

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
  let off: OfficialResult = officialScore(
    {
      humanRole: spec.humanRole,
      aiRole: spec.aiRole,
      humanScored: spec.kind === 'human',
      judgeWeight: spec.kind === 'artifact' ? (spec.judgeWeight ?? 0) : null,
      zeroIfGradedZero: spec.kind === 'artifact' && spec.zeroIfGradedZero === true,
      autoScore,
      checkScore: typeof d.checkScore === 'number' ? d.checkScore : null,
      runAiScore: run,
      stationAiScore: ai,
      humanScore: human,
      disagreement: needsArbitration(d),
    },
    policy,
  );
  if (spec.kind === 'artifact' && jamTruncated(d) && off.score !== null) {
    const checks = typeof d.checkScore === 'number' ? d.checkScore : null;
    const score = checks === null ? autoScore : r4((1 - (spec.judgeWeight ?? 0)) * checks);
    off = { score, source: score === null ? 'pending' : 'auto', why: 'The game was cut off at the output limit, so its judged part scores 0 (the Game Jam rule); grades are shown as opinions only.' };
  }
  const out: CaseResult = { ...r, scoreDetail: d };
  if (human !== null) d.humanScore = r4(human);
  if (d.integrity?.verdict === 'cheating') {
    // A fair-play violation stays at 0 under every policy; station and human grades are kept as opinions only.
    d.official = { source: 'auto', policy, why: `Fair-play violation (${d.integrity.rule}): the reply scored 0, and no grade can change that.` };
    return out;
  }
  d.official = { source: off.source, policy, why: off.why };
  if (off.score === null) return out;
  // A cap the scorer applied (a Game Jam game that froze, stayed blank or loads from the internet) still holds.
  off.score = withScorerCap(off.score, d);
  out.score = off.score;
  out.passed = off.score >= spec.passThreshold;
  if (r.status === 'pending-human' || judgeFailed(r)) {
    out.status = 'ok';
    out.error = undefined;
  }
  if (spec.kind === 'human' && (off.source === 'human' || off.source === 'average')) d.humanScored = true;
  d.arbitrated = off.source === 'arbitration' || undefined;
  if (off.source === 'arbitration') d.automatedScore = autoScore;
  out.summary = officialSummary(off.source, { human, ai, run, raters: r.humanScores?.length ?? 0, auto: autoScore, autoSummary: (d.autoSummary as string | undefined) ?? r.summary, spec, score: off.score, single: Boolean(d.singleJudge) && !d.judgeDisagreement });
  return out;
}

function officialSummary(source: OfficialSource, x: { human: number | null; ai: number | null; run: number | null; raters: number; auto: number | null; autoSummary: string; spec: GradingSpec; score: number; single?: boolean }): string {
  const raters = `${x.raters} rater${x.raters === 1 ? '' : 's'}`;
  const pre = x.spec.kind === 'artifact' && x.spec.judgeWeight ? `${Math.round(x.score * 100)}/100 · ` : '';
  const ai = x.run ?? x.ai;
  switch (source) {
    case 'arbitration':
      return `Arbitrated by humans ${tenths(x.human)}/10 (${x.single ? 'only one judge answered' : 'judges disagreed'}; panel gave ${tenths(x.auto)})`;
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
  if (needsArbitration(r.scoreDetail)) return 'arbitrate';
  // The Gallery: the owner's artistry rating always counts (it replaces the judges' artistry).
  if (spec.scorerType.startsWith('program:gallery')) return 'owner-rating';
  return 'second-opinion';
}
