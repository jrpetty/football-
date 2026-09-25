/**
 * The one registry of "answer vs truth" case visuals: test id (or, for tests
 * without an explicit entry, scorer type + category) → how to build the
 * visual's model from a result, and how to draw it. Used by the Result
 * Inspector and the Presenter. `build` returns null whenever the data does
 * not parse cleanly, and callers then show the plain view.
 */
import type { ReactNode } from 'react';
import type { CaseVisualInput } from '../../../../src/presenter/visuals/common.ts';
import { gridVisual } from '../../../../src/presenter/visuals/grid.ts';
import { islandVisual } from '../../../../src/presenter/visuals/truth.ts';
import { plainNumber } from '../../../../src/presenter/visuals/common.ts';
import { mathsVisual } from '../../../../src/presenter/visuals/maths.ts';
import { instructionVisual } from '../../../../src/presenter/visuals/instruction.ts';
import { extractionVisual } from '../../../../src/presenter/visuals/extraction.ts';
import { honestyVisual } from '../../../../src/presenter/visuals/honesty.ts';
import { trickVisual } from '../../../../src/presenter/visuals/trick.ts';
import { codeVisual } from '../../../../src/presenter/visuals/code.ts';
import type { VizMode } from './VizFrame.tsx';
import { DeductionGridVisual } from './DeductionGridVisual.tsx';
import { IslandersVisual } from './IslandersVisual.tsx';
import { ShortestPlanVisual } from './ShortestPlanVisual.tsx';
import { MathProblemCard } from './MathProblemCard.tsx';
import { InstructionRulesVisual } from './InstructionRulesVisual.tsx';
import { JsonTruthDiff } from './JsonTruthDiff.tsx';
import { HonestyTrapCard } from './HonestyTrapCard.tsx';
import { TrickAnswerCard } from './TrickAnswerCard.tsx';
import { CodeTestBoard } from './CodeTestBoard.tsx';

export type CaseVisualFamily = 'grid' | 'islanders' | 'plan' | 'maths' | 'instruction' | 'extraction' | 'honesty' | 'trick' | 'code';

export interface VisualContext {
  /** Contestant id → display label (judges). */
  name?: (id: string) => string;
}

interface Entry<M> {
  family: CaseVisualFamily;
  /** What the visual shows, for the Presenter's caption. */
  caption: string;
  build: (input: CaseVisualInput) => M | null;
  render: (model: M, mode: VizMode, input: CaseVisualInput, ctx: VisualContext) => ReactNode;
}

function entry<M>(e: Entry<M>): Entry<unknown> {
  return e as unknown as Entry<unknown>;
}

const ENTRIES: Record<CaseVisualFamily, Entry<unknown>> = {
  grid: entry({
    family: 'grid',
    caption: 'The full solution of the logic grid. The rows the question asked for show the model’s answer: green cells are right, red cells show its wrong value struck out with the correct one underneath.',
    build: gridVisual,
    render: (v, mode) => <DeductionGridVisual v={v} mode={mode} />,
  }),
  islanders: entry({
    family: 'islanders',
    caption: 'Every islander with what they said. Each one’s true role comes from the answer key; underneath is the role the model gave them, ticked or crossed.',
    build: islandVisual,
    render: (v, mode) => <IslandersVisual v={v} mode={mode} />,
  }),
  plan: entry({
    family: 'plan',
    caption: 'The model’s claimed minimum against the true minimum, and one optimal plan played step by step. One step too many scores zero.',
    build: (input) => (typeof input.expected === 'number' || plainNumber(String(input.expected ?? '')) !== null ? input : null),
    render: (input, mode) => <ShortestPlanVisual input={input as CaseVisualInput} mode={mode} />,
  }),
  maths: entry({
    family: 'maths',
    caption: 'The problem as the model saw it, its final answer against the answer key. Only the exact number scores.',
    build: mathsVisual,
    render: (v, mode) => <MathProblemCard v={v} mode={mode} />,
  }),
  instruction: entry({
    family: 'instruction',
    caption: 'The model’s reply with every rule beside it. Red marks show exactly where a rule was broken; the bars compare counts with their targets.',
    build: instructionVisual,
    render: (v, mode, input) => <InstructionRulesVisual v={v} mode={mode} score={input.score} />,
  }),
  extraction: entry({
    family: 'extraction',
    caption: 'Every field the model had to pull out of the document, answer key against the model. Wrong values are struck through; amber lines in the document are the corrections and changes that set the traps.',
    build: extractionVisual,
    render: (v, mode, input) => <JsonTruthDiff v={v} mode={mode} score={input.score} />,
  }),
  honesty: entry({
    family: 'honesty',
    caption: 'A question built to tempt the model into making something up. The verdict comes from a panel of AI judges reading the answer key.',
    build: honestyVisual,
    render: (v, mode, _i, ctx) => <HonestyTrapCard v={v} mode={mode} judgeName={ctx.name} />,
  }),
  trick: entry({
    family: 'trick',
    caption: 'A trick question: the tempting wrong answer, the correct one, and what the model said.',
    build: trickVisual,
    render: (v, mode) => <TrickAnswerCard v={v} mode={mode} />,
  }),
  code: entry({
    family: 'code',
    caption: 'Every hidden test run against the model’s code: green passed, red wrong or crashed, amber too slow. Below: one failing test and the code itself.',
    build: codeVisual,
    render: (v, mode) => <CodeTestBoard v={v} mode={mode} />,
  }),
};

const BY_ID: Record<string, CaseVisualFamily> = {
  'reasoning.deduction-grid': 'grid',
  'reasoning.deduction-grid-extreme': 'grid',
  'reasoning.truth-tellers': 'islanders',
  'reasoning.truth-tellers-extreme': 'islanders',
  'reasoning.planning': 'plan',
  'reasoning.planning-extreme': 'plan',
  'math.competition': 'maths',
  'math.olympiad': 'maths',
  'math.word-problems': 'maths',
  'instruction.precision-formatting': 'instruction',
  'instruction.system-prompt-adherence': 'instruction',
  'instruction.extreme-constraints': 'instruction',
  'instruction.adversarial-system': 'instruction',
  'extraction.structured-json': 'extraction',
  'extraction.frontier': 'extraction',
  'honesty.honesty-trap': 'honesty',
  'honesty.pressure-traps': 'honesty',
  'coding.algorithms': 'code',
  'coding.debug-and-edge-cases': 'code',
  'coding.hard': 'code',
  'coding.frontier': 'code',
};

/** Which visual (if any) draws cases of this test. */
export function visualFamilyFor(testId: string, scorerType?: string, category?: string): CaseVisualFamily | null {
  if (BY_ID[testId]) return BY_ID[testId]!;
  if (category === 'trick') return 'trick';
  switch (scorerType) {
    case 'code-js':
      return 'code';
    case 'constraints':
      return 'instruction';
    case 'json':
      return 'extraction';
    case 'judge-classify':
      return category === 'honesty' ? 'honesty' : null;
    case 'number':
      return category === 'math' ? 'maths' : null;
    default:
      return null;
  }
}

export function visualEntry(family: CaseVisualFamily): Entry<unknown> {
  return ENTRIES[family];
}

/** Build and draw in one go; null when the data does not parse (caller falls back to the plain view). */
export function renderCaseVisual(family: CaseVisualFamily, input: CaseVisualInput, mode: VizMode, ctx: VisualContext = {}): ReactNode | null {
  const e = ENTRIES[family];
  let model: unknown = null;
  try {
    model = e.build(input);
  } catch {
    model = null;
  }
  return model === null ? null : e.render(model, mode, input, ctx);
}
