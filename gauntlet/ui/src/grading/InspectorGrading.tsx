/** Result inspector additions: the model × test 30-word summary, station AI verdicts, disputes, and a link into the Grading Station. */
import { Link, pathOf } from '../router.tsx';
import { Icon } from '../components/icons.tsx';
import { ScorePill } from '../components/ui.tsx';
import type { CaseResult } from '../types.ts';
import { PerformanceSummary } from './PerformanceSummary.tsx';
import { summaryFor, useRunSummaries } from './useSummaries.ts';

export function InspectorSummary({ runId, testId, contestantId, label, color }: { runId: string; testId: string; contestantId: string; label: string; color?: string }) {
  const s = useRunSummaries(runId);
  const shown = summaryFor(s, contestantId, testId);
  if (!shown) return null;
  return (
    <div className="inspector-summary">
      <PerformanceSummary s={shown} variant="card" label={`${label} on this test`} color={color} />
    </div>
  );
}

const SOURCE: Record<string, string> = {
  auto: 'the machine (answer key / checks)',
  ai: 'AI judges',
  human: 'a human grade',
  average: 'the average of human and AI grades',
  arbitration: 'human arbitration (the judges disagreed)',
  pending: 'nobody yet',
};

export function InspectorGrading({ res }: { res: CaseResult }) {
  const official = res.scoreDetail?.official as { source?: string; policy?: string; why?: string } | undefined;
  const ai = res.aiGrades ?? [];
  const disputes = res.disputes ?? [];
  const humans = res.humanScores ?? [];
  if (!official && !ai.length && !disputes.length && !humans.length) {
    return (
      <div className="ig-link">
        <Link to={`${pathOf('grading')}?run=${encodeURIComponent(res.runId)}&key=${encodeURIComponent(res.key)}&show=all`} className="btn sm">
          <Icon.Target /> Grade or dispute in the Grading Station
        </Link>
      </div>
    );
  }
  return (
    <div className="ig">
      {official?.source && (
        <div className="ig-official">
          <b>Official score from {SOURCE[official.source] ?? official.source}</b>
          {official.why && <span className="muted"> · {official.why}</span>}
        </div>
      )}
      {humans.length > 0 && (
        <div>
          <div className="mini-title">Human grades · by people</div>
          <div className="ig-list">
            {humans.map((h, i) => (
              <div key={i} className="ig-row">
                <span className="badge outline">human</span>
                <b>{h.rater}</b>
                {h.blind && <span className="muted">graded blind</span>}
                {h.label && <span className="badge outline">{h.label.replace(/_/g, ' ').toLowerCase()}</span>}
                <span className="spacer" />
                <ScorePill score={h.score} />
                {h.note && <p>{h.note}</p>}
              </div>
            ))}
          </div>
        </div>
      )}
      {ai.length > 0 && (
        <div>
          <div className="mini-title">AI judges · graded later in the Grading Station</div>
          <div className="ig-list">
            {ai.map((g, i) => (
              <div key={i} className="ig-row">
                <span className="badge outline">AI judge</span>
                <b>{g.judgeLabel}</b>
                <span className="muted">{g.vendor}</span>
                {g.images > 0 && <span className="muted">saw {g.images} picture{g.images === 1 ? '' : 's'}</span>}
                {g.label && <span className="badge outline">{g.label.replace(/_/g, ' ').toLowerCase()}</span>}
                <span className="spacer" />
                <ScorePill score={g.score} />
                <p>{g.rationale}</p>
              </div>
            ))}
          </div>
        </div>
      )}
      {disputes.length > 0 && (
        <div className="callout warn">
          <Icon.Flag />
          <div>
            <b>Disputed by a person</b> (the machine score stands; answer keys are never overwritten):
            {disputes.map((d, i) => (
              <div key={i}>
                <b>{d.rater}:</b> {d.note}
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="ig-link">
        <Link to={`${pathOf('grading')}?run=${encodeURIComponent(res.runId)}&key=${encodeURIComponent(res.key)}&show=all`} className="btn sm">
          <Icon.Target /> Open in the Grading Station
        </Link>
      </div>
    </div>
  );
}
