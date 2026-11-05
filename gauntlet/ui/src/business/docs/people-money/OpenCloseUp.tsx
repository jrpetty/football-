/**
 * "See it up close" in the Result Inspector: opens the close-up (question, the model's answer drawn on the
 * documents, the result) on this very result, for the people-and-money tests.
 */
import { useState } from 'react';
import { LevelCloseUp } from '../../../components/viz/LevelCloseUp.tsx';
import { ladderLevel } from '../../../../../src/core/ladders.ts';
import { PEOPLE_MONEY_VISUALS } from './registry.ts';

export function OpenCloseUp({ testId, caseId, contestantId, runId }: { testId: string; caseId: string; contestantId: string; runId?: string | null }) {
  const [open, setOpen] = useState(false);
  if (!PEOPLE_MONEY_VISUALS[testId]) return null;
  const level = ladderLevel(caseId);
  return (
    <>
      <button type="button" className="btn sm" onClick={() => setOpen(true)} style={{ alignSelf: 'flex-start' }}>
        See it up close: the documents, the answer and the result
      </button>
      {open && <LevelCloseUp testId={testId} level={level ?? 1} caseId={level ? undefined : caseId} contestantId={contestantId} runId={runId ?? null} onClose={() => setOpen(false)} />}
    </>
  );
}
