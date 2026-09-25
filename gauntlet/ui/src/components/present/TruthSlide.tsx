/**
 * "Answer vs truth" Presenter slide (?truth=1): for one test, the case the
 * models disagreed on most, drawn with the test's case visual for one model
 * (the strongest model that still got it wrong), plus every model's score on
 * that same case down the side.
 */
import type { CSSProperties } from 'react';
import { api } from '../../api.ts';
import { useAsync } from '../../hooks.ts';
import type { CategoryInfo, TestDetail } from '../../types.ts';
import type { InterestPick } from '../../../../src/presenter/visuals/interest.ts';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { CaseVisual, caseInputFor } from '../viz/CaseVisualPanel.tsx';
import { ToneMark } from '../viz/VizFrame.tsx';

export interface TruthContender {
  id: string;
  label: string;
  color: string;
}

export function TruthSlide({
  runId,
  testName,
  cat,
  detail,
  pick,
  contenders,
}: {
  runId: string;
  testName: string;
  cat: CategoryInfo;
  detail: TestDetail | null;
  pick: InterestPick;
  contenders: TruthContender[];
}) {
  const state = useAsync(() => api.result(runId, pick.featuredKey), [runId, pick.featuredKey]);
  const featured = contenders.find((c) => c.id === pick.featuredContestantId);
  const input = state.data ? caseInputFor(state.data, detail, featured?.label) : null;
  const byId = new Map(contenders.map((c) => [c.id, c]));
  const rows = [...pick.perModel].sort((a, b) => b.mean - a.mean || (byId.get(a.contestantId)?.label ?? '').localeCompare(byId.get(b.contestantId)?.label ?? ''));
  return (
    <div className="s-truth">
      <div className="tr-top">
        <span className="pcat" style={{ ['--cc' as string]: cat.color } as CSSProperties}>
          <i />
          {cat.name}
        </span>
        <span className="tr-test">{testName}</span>
        <span className="tr-case mono">case {pick.caseId}</span>
        <span className="tr-k">The question the models disagreed on most</span>
      </div>
      <div className="tr-grid">
        <div className="tr-main">
          {state.error ? (
            <div className="p-empty">
              <Icon.Alert />
              <h2>This answer could not be loaded</h2>
              <p>{state.error.message}</p>
            </div>
          ) : !input ? (
            <div className="p-empty">
              <p>Loading the answer…</p>
            </div>
          ) : (
            <CaseVisual input={input} mode="slide" ctx={{ name: (id) => byId.get(id)?.label ?? id }} />
          )}
        </div>
        <aside className="tr-side">
          <div className="tr-side-k">Every model on this question</div>
          <ol>
            {rows.map((m) => {
              const c = byId.get(m.contestantId);
              const tone = m.mean >= 0.999 ? 'good' : m.mean > 0 ? 'half' : 'bad';
              return (
                <li key={m.contestantId} className={cx(m.contestantId === pick.featuredContestantId && 'featured')} style={{ ['--c' as string]: c?.color ?? 'var(--text-3)' } as CSSProperties}>
                  <i className="tr-sw" aria-hidden="true" />
                  <span className="tr-who">
                    <b>{c?.label ?? m.contestantId}</b>
                    {m.contestantId === pick.featuredContestantId && <small>shown on the left</small>}
                  </span>
                  <span className="tr-score tnum">{Math.round(m.mean * 100)}</span>
                  <ToneMark tone={tone} />
                </li>
              );
            })}
          </ol>
        </aside>
      </div>
    </div>
  );
}
