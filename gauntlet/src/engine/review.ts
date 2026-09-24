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
}

/** Results that humans can rate: `human` scorer tests (required) and artifact tests (optional second opinion). */
export function reviewQueue(testId?: string): ReviewItem[] {
  const reviewable = new Set(
    loadTests()
      .filter((t) => t.definition.kind === 'prompt' && (t.definition.scorer.type === 'human' || t.definition.scorer.type === 'artifact' || t.definition.cases.some((c) => c.scorer?.type === 'human')))
      .map((t) => t.definition.id),
  );
  const out: ReviewItem[] = [];
  for (const runId of listRunIds()) {
    for (const r of readResults(runId)) {
      if (!reviewable.has(r.testId) || (testId && r.testId !== testId)) continue;
      if (r.status === 'error' || r.status === 'cancelled') continue;
      out.push({ runId, key: r.key, testId: r.testId, caseId: r.caseId, contestantId: r.contestantId, status: r.status, score: r.score, humanScores: r.humanScores ?? [] });
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
  if (current.status === 'pending-human' || (current.scoreDetail as { humanScored?: boolean }).humanScored) {
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
