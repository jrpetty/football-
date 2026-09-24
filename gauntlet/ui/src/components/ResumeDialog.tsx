/** Resume a run, optionally with a new spending cap (POST /api/runs/:id/resume { maxCostUsd }). */
import { useEffect, useState } from 'react';
import { api } from '../api.ts';
import { useToast } from '../context.tsx';
import { fmtCost, fmtInt } from '../format.ts';
import type { RunDetail } from '../types.ts';
import { Callout, Modal, cx } from './ui.tsx';
import { Icon } from './icons.tsx';

type CapMode = 'keep' | 'set' | 'remove';

export function ResumeDialog({ runId, runName, open, onClose, onResumed }: { runId: string; runName: string; open: boolean; onClose: () => void; onResumed: () => void }) {
  const toast = useToast();
  const [detail, setDetail] = useState<RunDetail | null>(null);
  const [mode, setMode] = useState<CapMode>('keep');
  const [capText, setCapText] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    let alive = true;
    setDetail(null);
    api
      .run(runId)
      .then((d) => {
        if (!alive) return;
        setDetail(d);
        const cap = d.manifest.settings?.maxCostUsd;
        const budgetHit = /budget|cap/i.test(d.manifest.error ?? '');
        setMode(cap ? (budgetHit ? 'set' : 'keep') : 'keep');
        const suggestion = cap ? Math.max(cap * 2, d.progress.costUsd * 1.5) : 0;
        setCapText(suggestion ? (suggestion >= 10 ? String(Math.ceil(suggestion)) : suggestion.toFixed(2)) : '');
      })
      .catch(() => alive && setDetail(null));
    return () => {
      alive = false;
    };
  }, [open, runId]);

  const cap = detail?.manifest.settings?.maxCostUsd;
  const spent = detail?.progress.costUsd ?? 0;
  const remaining = detail ? Math.max(0, detail.progress.total - detail.progress.completed) : 0;
  const newCap = Number(capText);
  const capValid = mode !== 'set' || (Number.isFinite(newCap) && newCap > 0);

  const go = async () => {
    setBusy(true);
    try {
      await api.resumeRun(runId, mode === 'keep' ? undefined : mode === 'remove' ? null : newCap);
      toast.success(`Resuming “${runName}” — only missing or errored jobs will run.`);
      onResumed();
    } catch (e) {
      toast.error(e, 'Could not resume');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Resume run"
      width={560}
      foot={
        <>
          <button className="btn ghost" onClick={onClose}>
            Cancel
          </button>
          <button className="btn primary" onClick={go} disabled={busy || !capValid}>
            <Icon.Refresh /> {busy ? 'Resuming…' : 'Resume'}
          </button>
        </>
      }
    >
      <div className="stack">
        <p>
          <strong style={{ color: 'var(--text-1)' }}>{runName}</strong> — only the {detail ? fmtInt(remaining) : '…'} missing or errored jobs will run. Completed results are kept.
        </p>
        {detail?.manifest.error && (
          <Callout tone="warn">
            <strong>Stopped:</strong> {detail.manifest.error}
          </Callout>
        )}
        <div className="stats" style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}>
          <div className="stat">
            <span className="k">Spent so far</span>
            <span className="v" style={{ fontSize: '1.2rem' }}>
              {fmtCost(spent)}
            </span>
          </div>
          <div className="stat">
            <span className="k">Current cap</span>
            <span className="v" style={{ fontSize: '1.2rem' }}>
              {cap ? fmtCost(cap) : 'none'}
            </span>
          </div>
        </div>
        <fieldset className="fieldset">
          <legend>Spending cap</legend>
          <div className="stack tight">
            <label className="check">
              <input type="radio" name="cap" checked={mode === 'keep'} onChange={() => setMode('keep')} /> Keep {cap ? `the ${fmtCost(cap)} cap` : 'no cap'}
            </label>
            <label className="check">
              <input type="radio" name="cap" checked={mode === 'set'} onChange={() => setMode('set')} /> Set a new cap
              <span className="input-prefix" style={{ width: 130, marginLeft: 6 }}>
                <span>$</span>
                <input className={cx('input sm tnum', !capValid && 'invalid')} inputMode="decimal" value={capText} onFocus={() => setMode('set')} onChange={(e) => setCapText(e.target.value)} aria-label="New spending cap in USD" />
              </span>
            </label>
            {cap ? (
              <label className="check">
                <input type="radio" name="cap" checked={mode === 'remove'} onChange={() => setMode('remove')} /> Remove the cap
              </label>
            ) : null}
          </div>
        </fieldset>
        {mode === 'set' && capValid && newCap <= spent && <Callout tone="warn">The new cap is not above what has already been spent — the run will stop again immediately.</Callout>}
      </div>
    </Modal>
  );
}
