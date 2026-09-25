/**
 * Mock-mode data for "Fix the Bug" (the code-agent program). The replay was
 * recorded from a real scripted episode on the invoice-calc repo
 * (codeAgentReplay.json), so the demo shows exactly what a run looks like.
 */
import recorded from './codeAgentReplay.json';
import type { ProgramInfo, ProgramTest, ReplayData, ReplayFrame } from '../types.ts';

export const CODE_AGENT_PROGRAM: ProgramInfo = {
  id: 'code-agent',
  name: 'Fix the Bug',
  description:
    'A small real JavaScript repo with a failing test suite and planted bugs. The model uses one tool action per turn (LIST, READ, SEARCH, WRITE, PATCH, RUN_TESTS, SUBMIT) to investigate, edit and make the tests pass. Hidden tests decide the score.',
  scoring: '0.75 × hidden tests fixed + 0.15 × visible tests fixed + 0.10 × efficiency (actions and tokens), minus 0.1 per attempt to edit a test file.',
};

export const CODE_AGENT_TEST: ProgramTest = {
  kind: 'program',
  version: '1.0.0',
  id: 'agentic.code-agent',
  name: 'Fix the Bug',
  category: 'agentic',
  difficulty: 'hard',
  description: 'A real repo, a red test suite and planted bugs. Investigate, edit and make the tests pass within 30 actions; hidden tests decide the score.',
  hook: 'A real repo, a red test suite and 30 actions. Can it find the bugs — and do the hidden tests agree?',
  program: 'code-agent',
  seeds: [101, 202, 303, 404],
  estimate: { inputTokens: 110000, outputTokens: 24000, calls: 16 },
};

const FULL = (recorded as unknown as { replay: ReplayData }).replay;

/** The recorded replay; low scores get a plausible partial run that ran out of ideas. */
export function codeAgentReplay(seed: number, score: number): ReplayData {
  if (score >= 0.85) return { ...FULL, title: `${FULL.title} · seed ${seed}` };
  if (score >= 0.7) {
    // Every visible test green, but two hidden tests still fail: the "bug the visible tests didn't show" case.
    const frames = FULL.frames.map((f) => ({ ...f }));
    const last = frames[frames.length - 1]!;
    frames[frames.length - 1] = {
      ...last,
      outcome: 'Submitted. Hidden tests: 11 of 13 pass (4 passed before). Visible: 9/9.',
      tone: 'neutral',
      code: { ...last.code!, hidden: { passed: 11, total: 13, before: 4 } },
    };
    return { ...FULL, title: `${FULL.title} · seed ${seed}`, frames };
  }
  const frames: ReplayFrame[] = FULL.frames.slice(0, 5).map((f) => ({ ...f }));
  const last = FULL.frames[FULL.frames.length - 1]!;
  const lastCode = last.code!;
  frames.push({
    ...last,
    step: frames.length,
    outcome: 'Out of ideas — submitted. Hidden tests: 8 of 13 pass (4 passed before). Visible: 8/9.',
    tone: 'neutral',
    code: {
      ...lastCode,
      files: frames[frames.length - 1]!.code!.files,
      tests: { passed: 8, total: 9, failing: ['invoice.test.js › a percent discount is applied before tax'], ranThisStep: true },
      hidden: { passed: 8, total: 13, before: 4 },
      actions: { used: 30, budget: 30 },
      tokens: { used: 61240, budget: 150000 },
    },
  });
  return { ...FULL, title: `${FULL.title} · seed ${seed}`, frames };
}

export function codeAgentSummary(score: number): string {
  if (score >= 0.85) return `Fixed it: all 13 hidden tests pass · ${10 + Math.round((1 - score) * 20)} actions (par 8)`;
  if (score >= 0.7) return 'Hidden tests 11/13 (was 4) · visible 9/9 · 10 actions';
  return `Hidden tests 8/13 (was 4) · visible 8/9 · 30 actions`;
}
