/**
 * Presenter slide for a difficulty-ladder test (Horizon, Frontier Ladders, Business Ladders): "How high can it
 * climb?". Every model's lane is filled up to the highest level it passed reliably, with every attempt's chip
 * on every rung. Drawn only from the run's recorded results, with the test's pass mark (config/ladders.json).
 */
import type { CSSProperties } from 'react';
import type { CategoryInfo, CaseResultLite, TestDetail } from '../../types.ts';
import { LadderPanel, type LadderContestant } from '../viz/LadderPanel.tsx';
import { ladderClimbs } from '../../../../src/core/ladders.ts';
import { ladderRuleOf, ladderSpecOf, passMarkText } from '../../ladders.ts';

export const LADDER_SLIDE_TITLE = 'How high can it climb?';

export function ladderSlideHeadline(testId: string, results: CaseResultLite[], contenders: LadderContestant[]): string {
  const real = new Set(contenders.filter((c) => !c.baseline).map((c) => c.id));
  const rule = ladderRuleOf(testId);
  const levels = rule.levels ?? 10;
  const climbs = ladderClimbs(results, testId, rule).filter((c) => real.has(c.contestantId));
  if (!climbs.length) return 'Nobody has climbed yet';
  const top = Math.max(...climbs.map((c) => c.height));
  const leaders = climbs.filter((c) => c.height === top).map((c) => contenders.find((x) => x.id === c.contestantId)?.label ?? c.contestantId);
  if (top === 0) return 'No model passed even the first level reliably';
  const who = leaders.length === 1 ? leaders[0] : leaders.length === 2 ? `${leaders[0]} and ${leaders[1]}` : `${leaders.length} models`;
  return top >= levels ? `${who} reached the top` : `${who} got highest: level ${top} of ${levels}`;
}

export function LadderSlide({
  testId,
  testName,
  cat,
  detail,
  results,
  contenders,
}: {
  testId: string;
  testName: string;
  cat: CategoryInfo;
  detail: TestDetail | null;
  results: CaseResultLite[];
  contenders: LadderContestant[];
}) {
  const spec = ladderSpecOf(testId);
  return (
    <div className="s-ladder">
      <div className="tr-top">
        <span className="pcat" style={{ ['--cc' as string]: cat.color } as CSSProperties}>
          <i />
          {cat.name}
        </span>
        <span className="tr-test">
          {LADDER_SLIDE_TITLE} · {testName}
        </span>
        <span className="tr-k">{ladderSlideHeadline(testId, results, contenders)}</span>
      </div>
      {spec && (
        <p className="s-ladder-grows">
          <b>What grows:</b> {spec.grows} <span>· {passMarkText(spec.passMark)}</span>
        </p>
      )}
      <div className="s-ladder-main">
        <LadderPanel testId={testId} results={results} contestants={contenders} mode="slide" detail={detail} showHead={false} />
      </div>
    </div>
  );
}
