import { mean } from '../core/stats.ts';
import type { CaseResult, CaseResultLite } from '../core/types.ts';
import { appendResult, listRunIds, readManifest, readResults, toLite } from './store.ts';
import { loadTests } from '../core/registry.ts';
import { applyGalleryHumanScores, isGalleryResult } from './gallery-review.ts';
import { needsArbitration, withScorerCap } from '../grading/official.ts';

export interface ReviewItem {
  runId: string;
  key: string;
  testId: string;
  caseId: string;
  contestantId: string;
  status: CaseResult['status'];
  score: number | null;
  humanScores: NonNullable<CaseResult['humanScores']>;
  /**
   * Why it is in the queue: human-scored test, judge panel disagreement (human arbitrates), optional second opinion,
   * or a copy & paste reply that shows signs of outside help (fair-play check; the score stands, a person looks).
   */
  reason: 'human-scored' | 'judge-disagreement' | 'second-opinion' | 'outside-help';
  /** For 'outside-help': what the fair-play check saw. */
  fairPlay?: { rule: string; excerpt: string };
}

/**
 * Results that humans can rate:
 *  - `human` scorer tests (required before they count),
 *  - any judge-scored result where the judge panel disagreed (a human arbitrates; their score becomes final),
 *  - artifact tests (optional second opinion, stored alongside the automated score),
 *  - any reply the fair-play check flagged as possible outside help (src/scoring/integrity.ts).
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
      // A disagreement, or only one of several judges answered: a person arbitrates.
      const disagreement = needsArbitration(r.scoreDetail);
      const outsideHelp = r.scoreDetail.integrity?.verdict === 'flagged' ? r.scoreDetail.integrity : undefined;
      // Gallery paintings are always open to the owner's artistry rating.
      if ((!reviewable.has(r.testId) && !disagreement && !outsideHelp && !isGalleryResult(r)) || (testId && r.testId !== testId)) continue;
      if (r.status === 'error' || r.status === 'cancelled') continue;
      // A fair-play violation is already 0 and no rating can change it.
      if (r.scoreDetail.integrity?.verdict === 'cheating') continue;
      const reason: ReviewItem['reason'] = r.status === 'pending-human' || r.scoreDetail.humanScored ? 'human-scored' : disagreement ? 'judge-disagreement' : outsideHelp ? 'outside-help' : 'second-opinion';
      out.push({ runId, key: r.key, testId: r.testId, caseId: r.caseId, contestantId: r.contestantId, status: r.status, score: r.score, humanScores: r.humanScores ?? [], reason, ...(outsideHelp ? { fairPlay: { rule: outsideHelp.rule, excerpt: outsideHelp.excerpt } } : {}) });
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
  if (isGalleryResult(current)) {
    // Gallery: named raters rate artistry (it replaces the judges'); blind votes are recorded only (src/engine/gallery-review.ts).
    const g = applyGalleryHumanScores(updated);
    appendResult(g);
    return toLite(g);
  }
  if (needsArbitration(current.scoreDetail)) {
    // Judges disagreed: the human panel arbitrates and its mean becomes the score. The judges' verdicts stay on record.
    const automated = (current.scoreDetail.automatedScore as number | undefined) ?? current.score;
    // The scorer's cap (a Game Jam game that froze or stayed blank) still holds, exactly as in the Grading Station.
    const arbitrated = withScorerCap(Math.round(humanMean * 10000) / 10000, current.scoreDetail);
    updated.score = arbitrated;
    updated.passed = arbitrated >= 0.7;
    updated.summary = `Arbitrated by humans ${(humanMean * 10).toFixed(1)}/10 (${current.scoreDetail.judgeDisagreement ? 'judges disagreed' : 'only one judge answered'}; panel gave ${automated === null ? '—' : (automated * 10).toFixed(1)})${arbitrated < humanMean - 1e-9 ? ` · capped at ${Math.round(arbitrated * 100)}` : ''}`;
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
