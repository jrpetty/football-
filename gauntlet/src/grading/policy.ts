/**
 * Which grade counts: the official-score policy for results a person and/or
 * AI judges graded. Pure, so the rules are unit-tested and the UI can preview
 * them. Objective tests (answer keys, unit tests, simulations) always keep the
 * machine's score: a person can only flag a dispute.
 *
 * Policies (config/settings.json → "gradingOfficial"; default "methodology"):
 *  - methodology: the AI judges recorded in the run count. People count when the
 *    test is human-scored, when the judges disagreed (human arbitration: the human
 *    mean becomes the score, as in docs/METHODOLOGY.md), or when no AI grade exists.
 *  - human: a human grade, when there is one, replaces the judges' part of the score.
 *  - ai: the AI judges count whenever they graded; people count only where no AI grade exists.
 *  - average: the mean of the human and the AI grade (whichever exist).
 * For artifact tests the grade is only the judged share: score = (1 − w) × checks + w × grade
 * (except methodology arbitration, where the human mean replaces the whole score).
 */
import type { AiRole, HumanRole } from './spec.ts';

export type OfficialPolicy = 'methodology' | 'human' | 'ai' | 'average';
export const OFFICIAL_POLICIES: OfficialPolicy[] = ['methodology', 'human', 'ai', 'average'];

/** Where the official score came from. */
export type OfficialSource = 'auto' | 'ai' | 'human' | 'average' | 'arbitration' | 'pending';

export interface OfficialInput {
  humanRole: HumanRole;
  aiRole: AiRole;
  /** Human-scored test (scorer "human"). */
  humanScored: boolean;
  /** Artifact tests: share of the score from grading; null for everything else. */
  judgeWeight: number | null;
  /** The automated score recorded by the run (null when pending or when judging failed). */
  autoScore: number | null;
  /** Artifact tests: the automated-checks part (0..1). */
  checkScore: number | null;
  /** Mean of the AI judges recorded in the run (null when none). */
  runAiScore: number | null;
  /** Mean of AI judges run later from the Grading Station (null when none). */
  stationAiScore: number | null;
  /** Mean of the human grades (null when none). */
  humanScore: number | null;
  /** The run's judge panel disagreed (spread > 3 points or split labels). */
  disagreement: boolean;
}

export interface OfficialResult {
  score: number | null;
  source: OfficialSource;
  /** One plain sentence explaining the choice. */
  why: string;
}

function r4(n: number): number {
  return Math.round(n * 10000) / 10000;
}

function mean(xs: Array<number | null>): number | null {
  const v = xs.filter((x): x is number => typeof x === 'number');
  return v.length ? v.reduce((s, x) => s + x, 0) / v.length : null;
}

export function officialScore(i: OfficialInput, policy: OfficialPolicy = 'methodology'): OfficialResult {
  if (i.humanRole === 'dispute' || (i.humanRole === 'second-opinion' && i.aiRole !== 'grade')) {
    return { score: i.autoScore, source: i.autoScore === null ? 'pending' : 'auto', why: 'Scored by machine; human and AI ratings are shown as opinions only.' };
  }
  // Station AI grades only count where the run recorded no AI verdict (re-rolling judges would be cherry-picking).
  const ai = i.runAiScore ?? (i.aiRole === 'grade' || i.humanScored ? i.stationAiScore : null);
  const human = i.humanScore;
  let grade: number | null = null;
  let source: OfficialSource = 'pending';
  let why = '';
  switch (policy) {
    case 'methodology':
      if (i.humanScored) {
        grade = human;
        source = human === null ? 'pending' : 'human';
        why = human === null ? 'Human-scored test: waiting for a person to grade it.' : 'Human-scored test: the human grade counts.';
      } else if (i.disagreement && human !== null) {
        return { score: r4(human), source: 'arbitration', why: 'The judges disagreed, so the human grade arbitrates and becomes the score.' };
      } else if (i.runAiScore !== null) {
        grade = i.runAiScore;
        source = 'ai';
        why = human !== null ? 'The run’s AI judges count; the human grade is a second opinion.' : 'Graded by the run’s AI judges.';
      } else if (i.stationAiScore !== null || human !== null) {
        grade = human ?? i.stationAiScore;
        source = human !== null ? 'human' : 'ai';
        why = human !== null ? 'No AI verdict was recorded in the run, so the human grade counts.' : 'No verdict was recorded in the run, so the Grading Station’s AI judges count.';
      }
      break;
    case 'human':
      grade = human ?? ai;
      source = human !== null ? 'human' : ai !== null ? 'ai' : 'pending';
      why = human !== null ? 'Policy “human”: the human grade counts.' : 'Policy “human”: no human grade yet, so the AI judges count.';
      break;
    case 'ai':
      grade = ai ?? human;
      source = ai !== null ? 'ai' : human !== null ? 'human' : 'pending';
      why = ai !== null ? 'Policy “AI”: the AI judges count.' : 'Policy “AI”: no AI grade, so the human grade counts.';
      break;
    case 'average':
      grade = mean([human, ai]);
      source = human !== null && ai !== null ? 'average' : human !== null ? 'human' : ai !== null ? 'ai' : 'pending';
      why = source === 'average' ? 'Policy “average”: the mean of the human and the AI grade.' : 'Policy “average”: only one grade so far, so it counts alone.';
      break;
  }
  if (grade === null) return { score: i.autoScore, source: i.autoScore === null ? 'pending' : 'auto', why: why || 'No grade yet.' };
  if (i.judgeWeight !== null && i.judgeWeight > 0 && i.checkScore !== null) {
    return { score: r4((1 - i.judgeWeight) * i.checkScore + i.judgeWeight * grade), source, why: `${why} Automated checks keep their ${Math.round((1 - i.judgeWeight) * 100)}% share.` };
  }
  return { score: r4(grade), source, why };
}

/** Agreement between a human and an AI grade (0..1 scale); the 0.3 threshold matches the judge-disagreement rule. */
export function agreement(human: number | null, ai: number | null): { level: 'agree' | 'close' | 'disagree' | 'n/a'; gap: number | null } {
  if (human === null || ai === null) return { level: 'n/a', gap: null };
  const gap = Math.abs(human - ai);
  return { level: gap <= 0.1 ? 'agree' : gap <= 0.3 ? 'close' : 'disagree', gap: r4(gap) };
}
