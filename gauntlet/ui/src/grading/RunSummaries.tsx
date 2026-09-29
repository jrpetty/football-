/**
 * 30-word performance summaries in the Run detail page: one line per model
 * under each test row of the results matrix, plus a toolbar to show / hide them
 * and to have an AI judge write them (cost shown and confirmed first).
 */
import { useState } from 'react';
import { Modal } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { useToast } from '../context.tsx';
import { fmtCost } from '../format.ts';
import { gradingApi, type SummaryEstimate } from './gradingApi.ts';
import { PerformanceSummary, SummaryGlyph } from './PerformanceSummary.tsx';
import { invalidateSummaries, summaryFor, useRunSummaries } from './useSummaries.ts';

export function TestSummaries({ runId, testId, contestants }: { runId: string; testId: string; contestants: Array<{ id: string; label: string; color: string }> }) {
  const s = useRunSummaries(runId);
  const rows = contestants.map((c) => ({ c, s: summaryFor(s, c.id, testId) })).filter((x) => x.s);
  if (!rows.length) return null;
  return (
    <div className="m-sums">
      {rows.map(({ c, s: sum }) => (
        <PerformanceSummary key={c.id} s={sum} variant="row" label={c.label} color={c.color} />
      ))}
    </div>
  );
}

export function SummaryToolbar({ runId, show, onShow }: { runId: string; show: boolean; onShow: (v: boolean) => void }) {
  const toast = useToast();
  const [est, setEst] = useState<SummaryEstimate | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const ask = async () => {
    setOpen(true);
    setEst(null);
    try {
      setEst(await gradingApi.summaryEstimate(runId));
    } catch (e) {
      setOpen(false);
      toast.error(e, 'Could not estimate the cost');
    }
  };
  const go = async () => {
    if (!est) return;
    setBusy(true);
    try {
      const r = await gradingApi.summaryGenerate(runId, undefined, est.totalUsd);
      const errs = Object.keys(r.errors).length;
      toast.success(`${Object.keys(r.summaries).length} summaries ready for ${fmtCost(r.costUsd)}${errs ? ` · ${errs} could not be written` : ''}.`, 'AI summaries written');
      invalidateSummaries(runId);
      setOpen(false);
    } catch (e) {
      toast.error(e, 'Could not write the summaries');
    } finally {
      setBusy(false);
    }
  };
  const writable = est?.pairs.filter((p) => p.writer && !p.cached) ?? [];
  const cached = est?.pairs.filter((p) => p.cached).length ?? 0;
  const missing = est?.pairs.filter((p) => !p.writer) ?? [];
  return (
    <div className="sum-toolbar">
      <SummaryGlyph />
      <span>
        <b>30-word summaries</b> <span className="muted">of how each model did on each test, built from the recorded results</span>
      </span>
      <span className="spacer" />
      <button className="btn sm" onClick={() => onShow(!show)} aria-pressed={show}>
        {show ? 'Hide' : 'Show'} summaries
      </button>
      <button className="btn sm no-broadcast" onClick={() => void ask()} title="An AI judge from a different vendor rewrites each summary from the same facts (cost shown first)">
        <Icon.Sparkles /> Write with AI…
      </button>
      <Modal
        open={open}
        onClose={() => !busy && setOpen(false)}
        title="Have an AI judge write the summaries?"
        width={600}
        foot={
          <>
            <button className="btn ghost" onClick={() => setOpen(false)} disabled={busy}>
              Cancel
            </button>
            <button className="btn primary" onClick={() => void go()} disabled={busy || !est || writable.length === 0} data-autofocus>
              {busy ? 'Writing…' : `Spend about ${fmtCost(est?.totalUsd ?? 0)} for ${writable.length}`}
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
              <span className="cd-v tnum">{fmtCost(est.totalUsd)}</span>
              <span className="muted">up to {fmtCost(est.totalUsdHigh)} · nothing is spent until you confirm</span>
            </div>
            <p className="gs-small" style={{ margin: 0 }}>
              Each model’s summary is written by a judge from a different company, from the recorded scores, cases and judge notes only, cut to 30 words. The free summaries stay available; AI ones are cached until the results change.
            </p>
            {cached > 0 && <div className="muted gs-small">{cached} already written and still current: free.</div>}
            {missing.length > 0 && <div className="callout warn gs-small">{missing.length} can’t be written: {missing[0]!.reason}</div>}
          </div>
        )}
      </Modal>
    </div>
  );
}
