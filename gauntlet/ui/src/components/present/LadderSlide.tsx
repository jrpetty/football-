/**
 * Presenter slide for a Horizon test: "How far up the ladder". Every model's climber sits on the highest
 * level it solved reliably; rung dots show every level's result. Drawn only from the run's recorded results.
 */
import type { CSSProperties } from 'react';
import type { CategoryInfo, CaseResultLite, TestDetail } from '../../types.ts';
import { HorizonLadderPanel, type LadderContestant } from '../viz/HorizonLadderPanel.tsx';
import { ladderClimbs } from '../../../../src/presenter/visuals/horizon.ts';

export function ladderSlideHeadline(testId: string, results: CaseResultLite[], contenders: LadderContestant[]): string {
  const real = new Set(contenders.filter((c) => !c.baseline).map((c) => c.id));
  const climbs = ladderClimbs(results, testId).filter((c) => real.has(c.contestantId));
  if (!climbs.length) return 'Nobody has climbed yet';
  const top = Math.max(...climbs.map((c) => c.height));
  const leaders = climbs.filter((c) => c.height === top).map((c) => contenders.find((x) => x.id === c.contestantId)?.label ?? c.contestantId);
  if (top === 0) return 'No model solved even the first level reliably';
  const who = leaders.length === 1 ? leaders[0] : leaders.length === 2 ? `${leaders[0]} and ${leaders[1]}` : `${leaders.length} models`;
  return top >= 10 ? `${who} reached the summit` : `${who} got highest: level ${top} of 10`;
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
  return (
    <div className="s-ladder">
      <div className="tr-top">
        <span className="pcat" style={{ ['--cc' as string]: cat.color } as CSSProperties}>
          <i />
          {cat.name}
        </span>
        <span className="tr-test">{testName}</span>
        <span className="tr-k">{ladderSlideHeadline(testId, results, contenders)}</span>
      </div>
      <div className="s-ladder-main">
        <HorizonLadderPanel testId={testId} results={results} contestants={contenders} mode="slide" detail={detail} />
      </div>
    </div>
  );
}
