import { mean } from '../core/stats.ts';
import type { CaseResult, CaseResultLite } from '../core/types.ts';
import { appendResult, listRunIds, readManifest, readResults, toLite } from './store.ts';
import { loadTests } from '../core/registry.ts';

export interface ReviewItem {
  runId: string;
  key: string;
  testId: string;
  caseId: string;
  contestantId: string;
  status: CaseResult['status'];
  score: number | null;
  humanScores: NonNullable<CaseResult['humanScores']>;
  /** Why it is in the queue: human-scored test, judge panel disagreement (human arbitrates), or optional second opinion. */
  reason: 'human-scored' | 'judge-disagreement' | 'second-opinion';
}

/**
 * Results that humans can rate:
 *  - `human` scorer tests (required before they count),
 *  - any judge-scored result where the judge panel disagreed (a human arbitrates; their score becomes final),
 *  - artifact tests (optional second opinion, stored alongside the automated score).
 */
export function reviewQueue(testId?: string): ReviewItem[] {
  const reviewable = new Set(
    loadTests()
      .filter((t) => t.definition.kind === 'prompt' && (t.definition.scorer.type === 'human' || t.definition.scorer.type === 'artifact' || t.definition.cases.some((c) => c.scorer?.type === 'human')))
      .map((t) => t.definition.id),
  );
  const out: ReviewItem[] = [];
  for (const runId of listRunIds()) {
    for (const r of readResults(runId)) {
      const disagreement = Boolean(r.scoreDetail.judgeDisagreement);
      if ((!reviewable.has(r.testId) && !disagreement) || (testId && r.testId !== testId)) continue;
      if (r.status === 'error' || r.status === 'cancelled') continue;
      const reason: ReviewItem['reason'] = r.status === 'pending-human' || r.scoreDetail.humanScored ? 'human-scored' : disagreement ? 'judge-disagreement' : 'second-opinion';
      out.push({ runId, key: r.key, testId: r.testId, caseId: r.caseId, contestantId: r.contestantId, status: r.status, score: r.score, humanScores: r.humanScores ?? [], reason });
    }
  }
  return out;
}

export function submitHumanScore(input: { runId: string; key: string; score: number; rater: string; note?: string }): CaseResultLite {
  if (!readManifest(input.runId)) throw new Error('Run not found');
  if (!(input.score >= 0 && input.score <= 1)) throw new Error('score must be within 0..1');
  const rater = input.rater?.trim();
  if (!rater) throw new Error('rater is required');
  const current = readResults(input.runId).find((r) => r.key === input.key);
  if (!current) throw new Error('Result not found');
  const humanScores = [...(current.humanScores ?? []).filter((h) => h.rater !== rater), { rater, score: input.score, at: new Date().toISOString(), note: input.note?.slice(0, 1000) }];
  const humanMean = mean(humanScores.map((h) => h.score))!;
  const updated: CaseResult = { ...current, humanScores, scoreDetail: { ...current.scoreDetail, humanScore: Math.round(humanMean * 10000) / 10000 } };
  if (current.scoreDetail.judgeDisagreement) {
    // Judges disagreed: the human panel arbitrates and its mean becomes the score. The judges' verdicts stay on record.
    const automated = (current.scoreDetail.automatedScore as number | undefined) ?? current.score;
    updated.score = Math.round(humanMean * 10000) / 10000;
    updated.passed = humanMean >= 0.7;
    updated.summary = `Arbitrated by humans ${(humanMean * 10).toFixed(1)}/10 (judges disagreed; panel gave ${automated === null ? '—' : (automated * 10).toFixed(1)})`;
    updated.scoreDetail = { ...updated.scoreDetail, automatedScore: automated, arbitrated: true };
  } else if (current.status === 'pending-human' || (current.scoreDetail as { humanScored?: boolean }).humanScored) {
    // Human-scored tests: the human panel mean *is* the score.
    updated.status = 'ok';
    updated.score = Math.round(humanMean * 10000) / 10000;
    updated.passed = humanMean >= 0.7;
    updated.summary = `Human panel ${(humanMean * 10).toFixed(1)}/10 (${humanScores.length} rater${humanScores.length > 1 ? 's' : ''})`;
    updated.scoreDetail = { ...updated.scoreDetail, humanScored: true };
  }
  appendResult(updated);
  return toLite(updated);
}
