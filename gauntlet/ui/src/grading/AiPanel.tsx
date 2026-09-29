/** AI side of the Grading Station: the judge panel, every recorded AI verdict with its rationale, the cost-confirm dialog, and human-vs-AI agreement. */
import { Icon } from '../components/icons.tsx';
import { Modal, cx } from '../components/ui.tsx';
import { money } from '../money.ts';
import { BudgetIcon } from '../budget/BudgetParts.tsx';
import type { AiEstimate, BudgetCheck, StationItem } from './gradingApi.ts';

/** The monthly budget under a cost: "This month: £38 of £50 spent", and why the hard stop blocks it (or a warning). */
export function BudgetNote({ b }: { b: BudgetCheck | undefined }) {
  if (!b) return null;
  return (
    <div className={cx('cd-budget', b.blocked ? 'blocked' : b.message ? 'warn' : '')}>
      <BudgetIcon />
      <div>
        <div>
          {b.line}
          {b.hardStop && !b.blocked && b.availableUsd !== null ? ` · ${money(b.availableUsd)} left under the hard stop` : ''}
        </div>
        {b.message && <div className="cd-budget-msg">{b.message}</div>}
        <div className="muted">Counted in My budget, like every paid judge call.</div>
      </div>
    </div>
  );
}

export function Verdicts({ item, names }: { item: StationItem; names: Map<string, string> }) {
  const run = item.result.scoreDetail?.judge ?? [];
  const station = item.result.aiGrades ?? [];
  const lastBatch = station.at(-1)?.batch;
  if (!run.length && !station.length) return <div className="ap-none muted">No AI verdict yet.</div>;
  return (
    <div className="ap-verdicts">
      {run.map((j, i) => (
        <div key={`r${i}`} className="ap-v">
          <div className="ap-v-head">
            <b>{names.get(j.contestantId) ?? names.get(j.contestantId.replace(/@judge$/, '')) ?? j.contestantId.replace(/@judge$/, '')}</b>
            <span className="badge outline">in the run</span>
            {j.label && <span className="badge outline">{j.label.replace(/_/g, ' ').toLowerCase()}</span>}
            <span className="spacer" />
            <span className="ap-v-score tnum">{(j.score * 10).toFixed(1)}</span>
          </div>
          <p>{j.rationale || 'No rationale recorded.'}</p>
        </div>
      ))}
      {station.map((g, i) => (
        <div key={`s${i}`} className={cx('ap-v', g.batch !== lastBatch && 'old')}>
          <div className="ap-v-head">
            <b>{g.judgeLabel}</b>
            <span className="muted">{g.vendor}</span>
            <span className="badge outline">{g.batch === lastBatch ? 'station' : 'earlier'}</span>
            {g.images > 0 && (
              <span className="badge outline" title={`Saw ${g.images} picture${g.images === 1 ? '' : 's'}`}>
                <Icon.Eye /> {g.images}
              </span>
            )}
            {g.label && <span className="badge outline">{g.label.replace(/_/g, ' ').toLowerCase()}</span>}
            <span className="spacer" />
            <span className="ap-v-score tnum">{(g.score * 10).toFixed(1)}</span>
          </div>
          <p>{g.rationale || 'No rationale recorded.'}</p>
        </div>
      ))}
    </div>
  );
}

export function Agreement({ human, ai, level }: { human: number | null; ai: number | null; level: StationItem['agreement']['level'] }) {
  const text = level === 'agree' ? 'You and the AI agree' : level === 'close' ? 'Close: within 3 points' : level === 'disagree' ? 'You and the AI disagree' : 'Needs both grades';
  return (
    <div className={cx('ag', level)}>
      <div className="ag-head">
        <span className="ag-dot" aria-hidden="true" />
        <b>{text}</b>
      </div>
      {(['human', 'ai'] as const).map((k) => {
        const v = k === 'human' ? human : ai;
        return (
          <div key={k} className="ag-row">
            <span className="ag-k">{k === 'human' ? 'You' : 'AI judges'}</span>
            <span className="ag-track">
              <span className={cx('ag-fill', k)} style={{ width: `${(v ?? 0) * 100}%` }} />
            </span>
            <span className="ag-v tnum">{v === null ? '—' : (v * 10).toFixed(1)}</span>
          </div>
        );
      })}
    </div>
  );
}

export function CostDialog({ open, est, busy, title, onCancel, onConfirm }: { open: boolean; est: AiEstimate | null; busy: boolean; title: string; onCancel: () => void; onConfirm: () => void }) {
  const ok = est?.items.filter((i) => i.ok) ?? [];
  const skipped = est?.items.filter((i) => !i.ok) ?? [];
  const judges = new Map<string, { label: string; vendor: string; vision: boolean; images: number; usd: number }>();
  for (const it of ok)
    for (const j of it.judges) {
      const cur = judges.get(j.id) ?? { label: j.label, vendor: j.vendor, vision: j.vision, images: 0, usd: 0 };
      cur.images = Math.max(cur.images, j.images);
      cur.usd += j.estUsd;
      judges.set(j.id, cur);
    }
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title={title}
      width={620}
      foot={
        <>
          <button className="btn ghost" onClick={onCancel}>
            Cancel <kbd>Esc</kbd>
          </button>
          <button className="btn primary" onClick={onConfirm} disabled={busy || !est || ok.length === 0 || !!est.budget?.blocked} data-autofocus>
            {busy ? 'Grading…' : est?.budget?.blocked ? 'Blocked by your budget' : `Spend about ${money(est?.totalUsd ?? 0)} and grade ${ok.length}`}
          </button>
        </>
      }
    >
      {!est ? (
        <div className="muted">Working out the cost…</div>
      ) : (
        <div className="stack">
          <div className="cd-total">
            <span className="cd-k">Estimated cost</span>
            <span className="cd-v tnum">{money(est.totalUsd)}</span>
            <span className="muted">up to {money(est.totalUsdHigh)} if the judges think for a long time · nothing is spent until you confirm</span>
          </div>
          <BudgetNote b={est.budget} />
          {judges.size > 0 && (
            <ul className="cd-judges">
              {[...judges.entries()].map(([id, j]) => (
                <li key={id}>
                  <b>{j.label}</b>
                  <span className="muted">{j.vendor}</span>
                  {j.vision ? (
                    <span className="badge outline" title="This judge can see pictures: test images and screenshots are attached">
                      <Icon.Eye /> {j.images ? `sees ${j.images} picture${j.images === 1 ? '' : 's'}` : 'can see pictures · none here'}
                    </span>
                  ) : (
                    <span className="badge outline">text only</span>
                  )}
                  <span className="spacer" />
                  <span className="tnum">{money(j.usd)}</span>
                </li>
              ))}
            </ul>
          )}
          <div className="muted" style={{ fontSize: '0.84rem' }}>
            Judges never grade a model from their own vendor, and at least two must agree to take part. They use this test’s own rubric; every verdict and its reasoning is saved with the result.
          </div>
          {skipped.length > 0 && (
            <div className="callout warn">
              <Icon.Alert />
              <div>
                <b>
                  {skipped.length} answer{skipped.length === 1 ? '' : 's'} can’t be AI-graded:
                </b>{' '}
                {[...new Set(skipped.map((s) => s.reason))].slice(0, 3).join(' ')}
              </div>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
