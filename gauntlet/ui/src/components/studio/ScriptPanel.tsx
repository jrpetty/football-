/** Studio → Video script: editable draft, number check, exports, and the optional "Polish with AI" (cost shown first). */
import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api.ts';
import { useAsync, useLocalStorage } from '../../hooks.ts';
import { useToast } from '../../context.tsx';
import { Callout, Card, CopyButton, cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { fmtCost, fmtTokens } from '../../format.ts';
import { unverifiedNumbers } from '../../../../src/media/script.ts';
import { downloadText } from './png.ts';
import type { ContestantView, PolishEstimate, StudioPayload } from '../../types.ts';

/** Markdown draft → plain text for teleprompters: no bold markers, headings in capitals. */
export function markdownToText(md: string): string {
  return md
    .split('\n')
    .filter((l) => !/^_About .* narration/.test(l.trim()))
    .map((l) => {
      const h = l.match(/^#{1,3}\s+(.*)$/);
      if (h) return h[1]!.replace(/\s*\(~\d+ s\)$/, '').toUpperCase();
      return l.replace(/\*\*(\[[^\]]*\])\*\*/g, '$1').replace(/\*\*/g, '');
    })
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
    .concat('\n');
}

function outline(md: string): Array<{ title: string; sec: number | null }> {
  return md
    .split('\n')
    .map((l) => l.match(/^##\s+(.*?)(?:\s*\(~(\d+) s\))?$/))
    .filter((m): m is RegExpMatchArray => !!m)
    .map((m) => ({ title: m[1]!, sec: m[2] ? Number(m[2]) : null }));
}

function fmtDur(sec: number): string {
  const m = Math.floor(sec / 60);
  return m ? `${m} min ${sec % 60} s` : `${sec} s`;
}

function slug(s: string): string {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60) || 'script';
}

export function ScriptPanel({ s }: { s: StudioPayload }) {
  const toast = useToast();
  const [draft, setDraft] = useLocalStorage<string>(`gauntlet.studio.script.${s.runId}`, s.markdown);
  const [undo, setUndo] = useState<string | null>(null);
  const allowed = useMemo(() => new Set(s.allowedNumbers), [s.allowedNumbers]);
  const unverified = useMemo(() => unverifiedNumbers(draft, allowed), [draft, allowed]);
  const parts = useMemo(() => outline(draft), [draft]);
  const total = parts.reduce((a, p) => a + (p.sec ?? 0), 0);
  const edited = draft !== s.markdown;
  const base = slug(s.runName);

  // Polish with AI
  const models = useAsync<ContestantView[]>(() => api.contestants(), []);
  const usable = useMemo(() => (models.data ?? []).filter((c) => c.enabled && c.hasKey && c.providerType !== 'manual' && c.providerType !== 'mock'), [models.data]);
  const [modelId, setModelId] = useLocalStorage<string>('gauntlet.studio.polishModel', '');
  const [est, setEst] = useState<PolishEstimate | null>(null);
  const [busy, setBusy] = useState<'estimate' | 'polish' | null>(null);
  const [last, setLast] = useState<{ cost: number; unverified: string[] } | null>(null);
  useEffect(() => {
    if (!modelId && usable[0]) setModelId(usable[0].id);
  }, [modelId, usable, setModelId]);
  useEffect(() => setEst(null), [modelId, draft]);

  const estimate = async () => {
    setBusy('estimate');
    try {
      setEst(await api.studioPolishEstimate(s.runId, modelId, draft));
    } catch (e) {
      toast.error(e, 'Could not estimate');
    } finally {
      setBusy(null);
    }
  };
  const polish = async () => {
    if (!est) return;
    setBusy('polish');
    try {
      const r = await api.studioPolish(s.runId, modelId, draft, est.costUsdHigh);
      setUndo(draft);
      setDraft(r.text.endsWith('\n') ? r.text : `${r.text}\n`);
      setLast({ cost: r.costUsd, unverified: r.unverified });
      setEst(null);
      toast.success(`Polished for ${fmtCost(r.costUsd)}. Read it through before recording.`);
    } catch (e) {
      toast.error(e, 'Polishing failed');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="script-grid">
      <Card
        className="script-editor-card"
        title="Narration script"
        desc={
          <>
            Written from the results, with cues like <code>[Slide 7]</code> that match the Presenter and <code>[Replay: …, step 14]</code> for the clips. Edit freely: your changes are kept in this browser.
          </>
        }
        tools={
          <div className="row wrap no-broadcast" style={{ gap: 6 }}>
            {edited && (
              <button type="button" className="btn sm ghost" onClick={() => (setUndo(draft), setDraft(s.markdown))} title="Throw away edits and start from the generated draft">
                <Icon.Refresh /> Reset
              </button>
            )}
            {undo !== null && (
              <button type="button" className="btn sm ghost" onClick={() => (setDraft(undo), setUndo(null), setLast(null))}>
                <Icon.StepBack /> Undo
              </button>
            )}
            <CopyButton text={draft} label="Copy" small={false} />
            <button type="button" className="btn sm" onClick={() => downloadText(draft, `${base}-script.md`, 'text/markdown')}>
              <Icon.Download /> .md
            </button>
            <button type="button" className="btn sm" onClick={() => downloadText(markdownToText(draft), `${base}-script.txt`)}>
              <Icon.Download /> .txt
            </button>
          </div>
        }
      >
        <div className={cx('numcheck', unverified.length ? 'bad' : 'good')} role="status">
          {unverified.length ? <Icon.Alert /> : <Icon.Check />}
          {unverified.length ? (
            <span>
              <strong>Check these numbers:</strong> {unverified.join(', ')} {unverified.length === 1 ? 'does' : 'do'} not appear anywhere in this run’s data.
            </span>
          ) : (
            <span>Every number in the script is backed by the run’s data.</span>
          )}
          {edited && <span className="badge outline">edited</span>}
        </div>
        <textarea className="textarea script-editor" value={draft} onChange={(e) => setDraft(e.target.value)} spellCheck aria-label="Script (Markdown)" />
      </Card>

      <div className="stack loose">
        <Card title="Running order" desc={total ? `About ${fmtDur(total)} of narration` : undefined}>
          <ol className="script-outline">
            {parts.map((p, i) => (
              <li key={`${p.title}-${i}`}>
                <span>{p.title}</span>
                {p.sec !== null && <span className="muted tnum">{p.sec} s</span>}
              </li>
            ))}
          </ol>
        </Card>

        <Card className="no-broadcast" title={<span className="row" style={{ gap: 8 }}><Icon.Sparkles style={{ width: 16, height: 16 }} /> Polish with AI</span>} desc="Optional. Sends the draft to a model to make it sound more natural. Cues and numbers must stay unchanged — the result is checked.">
          {usable.length === 0 ? (
            <p className="muted" style={{ margin: 0 }}>
              No API model with a key is set up. The template script above is free and ready to use.
            </p>
          ) : (
            <div className="stack">
              <select className="select" value={modelId} onChange={(e) => setModelId(e.target.value)} aria-label="Model for polishing">
                {usable.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.label} · ${c.pricing.inputPerM}/${c.pricing.outputPerM} per 1M tokens
                  </option>
                ))}
              </select>
              {!est ? (
                <button type="button" className="btn" onClick={estimate} disabled={!modelId || busy !== null || !draft.trim()}>
                  <Icon.Dollar /> {busy === 'estimate' ? 'Estimating…' : 'Show the cost first'}
                </button>
              ) : (
                <div className="polish-est">
                  <div className="polish-cost">
                    <span className="tnum">≈ {fmtCost(est.costUsd)}</span>
                    <span className="muted">at most {fmtCost(est.costUsdHigh)}</span>
                  </div>
                  <div className="muted" style={{ fontSize: '0.86rem' }}>
                    {est.modelLabel}: ~{fmtTokens(est.inputTokens)} tokens in, ~{fmtTokens(est.outputTokens)} out.
                  </div>
                  <div className="row" style={{ gap: 8 }}>
                    <button type="button" className="btn primary" onClick={polish} disabled={busy !== null}>
                      <Icon.Sparkles /> {busy === 'polish' ? 'Polishing…' : `Polish (up to ${fmtCost(est.costUsdHigh)})`}
                    </button>
                    <button type="button" className="btn ghost" onClick={() => setEst(null)} disabled={busy !== null}>
                      Cancel
                    </button>
                  </div>
                </div>
              )}
              {last && (
                <Callout tone={last.unverified.length ? 'warn' : 'info'}>
                  Polished for {fmtCost(last.cost)}.{' '}
                  {last.unverified.length ? `The model introduced numbers the data can’t back up (${last.unverified.join(', ')}) — fix or undo before recording.` : 'Every number still checks out.'}
                </Callout>
              )}
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
