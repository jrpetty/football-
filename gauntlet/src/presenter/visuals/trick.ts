/**
 * "Can It Be Fooled?" in the Result Inspector: the question, the tempting
 * wrong answer vs the correct one, and what this model said, in the same
 * style as the Presenter's trick slides.
 */
import { displayQuestion, matchesLure } from '../trick-highlights.ts';
import { statedAnswer, type CaseVisualInput } from './common.ts';

export interface TrickVisual {
  question: string;
  correct: string;
  lure: string | null;
  answer: string;
  verdict: 'correct' | 'fooled' | 'out-of-time' | 'no-answer';
  tookBait: boolean;
  timeLimitSec: number | null;
  responseMs: number | null;
  oneLineBroken: boolean;
}

export function trickVisual(input: CaseVisualInput): TrickVisual | null {
  const q = input.turns[input.turns.length - 1];
  if (!q) return null;
  const correct = input.displayAnswer ?? (Array.isArray(input.expected) ? String(input.expected[0] ?? '') : typeof input.expected === 'string' || typeof input.expected === 'number' ? String(input.expected) : '');
  if (!correct || (input.scorerType === 'regex' && !input.displayAnswer)) return null;
  const stated = statedAnswer(input);
  const outOfTime = input.status === 'timeout' || input.detail.outOfTime === true;
  const answer = outOfTime ? '' : stated?.answer ?? '';
  const verdict: TrickVisual['verdict'] = outOfTime ? 'out-of-time' : !answer ? 'no-answer' : (input.score ?? 0) >= 0.999 ? 'correct' : 'fooled';
  const lure = input.lure ?? null;
  return {
    question: displayQuestion(q).replace(/When you are finished, write your final answer[\s\S]*$/i, '').replace(/\n*TIME LIMIT: you must answer within[^\n]*/i, '').trim(),
    correct,
    lure,
    answer,
    verdict,
    tookBait: verdict === 'fooled' && matchesLure(answer, lure),
    timeLimitSec: typeof input.detail.timeLimitSec === 'number' ? input.detail.timeLimitSec : input.answerWithinSec ?? null,
    responseMs: typeof input.detail.responseMs === 'number' ? input.detail.responseMs : null,
    oneLineBroken: input.detail.oneLine === false,
  };
}

export function trickHeadline(v: TrickVisual): string {
  switch (v.verdict) {
    case 'correct':
      return 'Not fooled';
    case 'out-of-time':
      return 'Ran out of time';
    case 'no-answer':
      return 'No answer given';
    case 'fooled':
      if (v.oneLineBroken && !v.tookBait) return 'Broke the one-line rule';
      return `${v.tookBait ? 'Took the bait' : 'Fooled'}: said “${v.answer}”, the answer is “${v.correct}”`;
  }
}
