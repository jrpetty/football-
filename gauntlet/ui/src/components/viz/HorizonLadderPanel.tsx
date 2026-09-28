/**
 * Glue between recorded results and the HorizonLadder picture: computes every contestant's climb on one
 * Horizon test from a run's results and labels the rungs from the test file. Used by the Result Inspector
 * (above the case list) and by the Presenter's "How far up the ladder" slide.
 */
import { useMemo } from 'react';
import type { TestDetail } from '../../types.ts';
import { ladderClimbs, ladderLevel, rungCaption, type LadderResultLike } from '../../../../src/presenter/visuals/horizon.ts';
import { HorizonLadder, type LadderClimber } from './HorizonLadder.tsx';
import { useTestDetail } from './CaseVisualPanel.tsx';

export interface LadderContestant {
  id: string;
  label: string;
  color: string;
  baseline?: boolean;
}

export const isBaselineId = (id: string) => /(^|[-_.])(random|baseline)([-_.]|$)/i.test(id);

export function ladderCaptions(detail: TestDetail | null | undefined): { captions: Array<string | null>; levels: number } {
  const def = detail?.definition;
  if (!def || def.kind !== 'prompt') return { captions: [], levels: 10 };
  const captions: Array<string | null> = [];
  let levels = 0;
  for (const c of def.cases) {
    const lv = ladderLevel(c.id);
    if (lv === null) continue;
    levels = Math.max(levels, lv);
    captions[lv - 1] = rungCaption(def.id, c.prompt ?? '', c.notes);
  }
  return { captions, levels: levels || 10 };
}

export function buildClimbers(testId: string, results: LadderResultLike[], contestants: LadderContestant[], levels: number): LadderClimber[] {
  const climbs = new Map(ladderClimbs(results, testId, levels).map((c) => [c.contestantId, c]));
  return contestants
    .filter((c) => climbs.has(c.id))
    .map((c) => ({ id: c.id, label: c.label, color: c.color, baseline: c.baseline ?? isBaselineId(c.id), climb: climbs.get(c.id)! }));
}

export function HorizonLadderPanel({
  testId,
  results,
  contestants,
  mode = 'inspector',
  highlight,
  detail: given,
}: {
  testId: string;
  results: LadderResultLike[];
  contestants: LadderContestant[];
  mode?: 'inspector' | 'slide';
  highlight?: number;
  detail?: TestDetail | null;
}) {
  const loaded = useTestDetail(given === undefined ? testId : null);
  const detail = given ?? loaded;
  const { captions, levels } = useMemo(() => ladderCaptions(detail), [detail]);
  const climbers = useMemo(() => buildClimbers(testId, results, contestants, levels), [testId, results, contestants, levels]);
  if (!climbers.length) return null;
  return <HorizonLadder climbers={climbers} levels={levels} captions={captions} mode={mode} highlight={highlight} title={detail?.definition.name} />;
}
