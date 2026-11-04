/**
 * The difficulty ladder on a test's own page: the ten rungs with what grows on each, and every model's height
 * from all recorded runs (GET /api/ladders/:id). A planned ladder shows its rungs as "coming soon".
 */
import { api } from '../../api.ts';
import { useAsync } from '../../hooks.ts';
import type { TestDetail } from '../../types.ts';
import { LADDERS, ladderSpecOf, ladderVerdictOf } from '../../ladders.ts';
import { DifficultyLadder } from './DifficultyLadder.tsx';
import { isBaselineId, ladderRungs } from './LadderPanel.tsx';

export function LadderTestCard({ testId, detail }: { testId: string; detail?: TestDetail | null }) {
  const spec = ladderSpecOf(testId);
  const board = useAsync(() => (spec && !spec.planned ? api.ladder(testId) : Promise.resolve(null)), [testId]);
  if (!spec) return null;
  const { rungs, levels } = ladderRungs(testId, detail);
  const climbers = (board.data?.climbs ?? []).map((c) => ({ ...c, baseline: isBaselineId(c.id) }));
  return (
    <section className="card dl-card-wrap" aria-label="Difficulty ladder">
      <div className="card-body">
        <DifficultyLadder
          title="How high can it climb?"
          eyebrow={board.data?.demo ? 'Difficulty ladder · demo heights' : 'Difficulty ladder'}
          grows={`What grows: ${spec.grows}`}
          passMark={spec.passMark}
          levels={levels}
          rungs={rungs}
          climbers={climbers}
          comingSoon={spec.planned}
          calibration={spec.calibration}
          verdict={ladderVerdictOf(testId)}
          calibrationMethod={LADDERS.calibrationMethod}
        />
      </div>
    </section>
  );
}
