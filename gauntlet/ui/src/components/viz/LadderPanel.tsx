/**
 * Glue between recorded results and the DifficultyLadder picture, for any ladder test in config/ladders.json:
 * computes every contestant's climb with the test's pass mark (src/core/ladders.ts) and labels each rung with
 * its growth parameter (from the config; for Horizon, also readable from the test file). Used by the Result
 * Inspector (above the case list) and the Presenter's "How high can it climb?" slide.
 */
import { useMemo } from 'react';
import type { TestDetail } from '../../types.ts';
import { ladderClimbs, ladderLevel, rungSizes, type LadderResultLike } from '../../../../src/core/ladders.ts';
import { MOCK } from '../../api.ts';
import { rungCaption } from '../../../../src/presenter/visuals/horizon.ts';
import { LADDERS, ladderRuleOf, ladderSpecOf, ladderVerdictOf } from '../../ladders.ts';
import { DifficultyLadder, type DifficultyLadderMode, type LadderClimber } from './DifficultyLadder.tsx';
import { useTestDetail } from './CaseVisualPanel.tsx';

export interface LadderContestant {
  id: string;
  label: string;
  color: string;
  baseline?: boolean;
}

export const isBaselineId = (id: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id);

/** Each rung's growth label (index 0 = level 1): config/ladders.json first, else read from the test's cases. */
export function ladderRungs(testId: string, detail: TestDetail | null | undefined): { rungs: Array<string | null>; levels: number } {
  const spec = ladderSpecOf(testId);
  const def = detail?.definition;
  const fromCases: Array<string | null> = [];
  let levels = spec?.rungs?.length ?? 0;
  if (def && def.kind === 'prompt') {
    for (const c of def.cases) {
      const lv = ladderLevel(c.id);
      if (lv === null) continue;
      levels = Math.max(levels, lv);
      fromCases[lv - 1] = rungCaption(def.id, c.prompt ?? '', c.notes);
    }
  }
  levels ||= 10;
  return { rungs: Array.from({ length: levels }, (_, i) => spec?.rungs?.[i] ?? fromCases[i] ?? null), levels };
}

/**
 * How big each level is, for the bar beside it: read from the rung labels (src/core/ladders.ts rungSizes); when the
 * labels carry no single growing number, the length of each level's real prompt (its input size). Null (no bar)
 * when neither is known; the demo's stand-in prompts are not real input, so they never count.
 */
export function ladderSizes(rungs: Array<string | null>, detail: TestDetail | null | undefined): { values: number[]; unit: string } | null {
  const fromLabels = rungSizes(rungs);
  if (fromLabels) return fromLabels;
  const def = detail?.definition;
  if (!def || def.kind !== 'prompt' || def.tags?.includes('demo')) return null;
  const values: number[] = [];
  for (const c of def.cases) {
    const lv = ladderLevel(c.id);
    if (lv !== null && lv <= rungs.length && typeof c.prompt === 'string' && c.prompt.length > 0) values[lv - 1] = c.prompt.length;
  }
  return values.length === rungs.length && values.every((v) => v > 0) ? { values, unit: 'character of input' } : null;
}

export function buildClimbers(testId: string, results: LadderResultLike[], contestants: LadderContestant[], levels: number): LadderClimber[] {
  const climbs = new Map(ladderClimbs(results, testId, { ...ladderRuleOf(testId), levels }).map((c) => [c.contestantId, c]));
  return contestants
    .filter((c) => climbs.has(c.id))
    .map((c) => ({ id: c.id, label: c.label, color: c.color, baseline: c.baseline ?? isBaselineId(c.id), climb: climbs.get(c.id)! }));
}

export function LadderPanel({
  testId,
  results,
  contestants,
  mode = 'card',
  highlight,
  detail: given,
  showHead = true,
  runId,
  scope,
}: {
  /** The run these results come from (each level up close shows its answers). */
  runId?: string | null;
  /** Passed through to the level close-up. */
  scope?: string;
  testId: string;
  results: LadderResultLike[];
  contestants: LadderContestant[];
  mode?: DifficultyLadderMode;
  highlight?: number;
  detail?: TestDetail | null;
  showHead?: boolean;
}) {
  const loaded = useTestDetail(given === undefined ? testId : null);
  const detail = given ?? loaded;
  const spec = ladderSpecOf(testId);
  const { rungs, levels } = useMemo(() => ladderRungs(testId, detail), [testId, detail]);
  const climbers = useMemo(() => buildClimbers(testId, results, contestants, levels), [testId, results, contestants, levels]);
  const sizes = useMemo(() => ladderSizes(rungs, detail), [rungs, detail]);
  if (!climbers.length) return null;
  return (
    <DifficultyLadder
      testId={testId}
      runId={runId ?? null}
      scope={scope}
      climbers={climbers}
      levels={levels}
      rungs={rungs}
      sizes={sizes}
      demo={MOCK && mode === 'card'}
      mode={mode}
      highlight={highlight}
      title={showHead ? detail?.definition.name ?? spec?.name : undefined}
      grows={showHead ? spec?.grows : undefined}
      passMark={spec?.passMark ?? 1}
      calibration={spec?.calibration}
      verdict={showHead ? ladderVerdictOf(testId) : undefined}
      calibrationMethod={LADDERS.calibrationMethod}
    />
  );
}
