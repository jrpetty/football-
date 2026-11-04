/**
 * Glue between recorded results and the DifficultyLadder picture, for any ladder test in config/ladders.json:
 * computes every contestant's climb with the test's pass mark (src/core/ladders.ts) and labels each rung with
 * its growth parameter (from the config; for Horizon, also readable from the test file). Used by the Result
 * Inspector (above the case list) and the Presenter's "How high can it climb?" slide.
 */
import { useMemo } from 'react';
import type { TestDetail } from '../../types.ts';
import { ladderClimbs, ladderLevel, type LadderResultLike } from '../../../../src/core/ladders.ts';
import { rungCaption } from '../../../../src/presenter/visuals/horizon.ts';
import { ladderRuleOf, ladderSpecOf } from '../../ladders.ts';
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
}: {
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
  if (!climbers.length) return null;
  return (
    <DifficultyLadder
      climbers={climbers}
      levels={levels}
      rungs={rungs}
      mode={mode}
      highlight={highlight}
      title={showHead ? detail?.definition.name ?? spec?.name : undefined}
      grows={showHead ? spec?.grows : undefined}
      passMark={spec?.passMark ?? 1}
    />
  );
}
