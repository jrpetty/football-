/**
 * Manual Inbox additions: the model a prompt is for, shown big on every card so a reply is never pasted into the
 * wrong model; "Which model are you using?" for prompts of the unspecified contestant; a one-click "same model for
 * all of them"; and the explicit "Reassign to a model" panel for old results.
 */
import { useMemo, useState } from 'react';
import { Callout, ConfirmDialog, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { useToast } from '../context.tsx';
import { useAsync } from '../hooks.ts';
import { fmtDate } from '../format.ts';
import type { ContestantView, ManualModelInfo, ManualRequest } from '../types.ts';
import { availabilityText, interfaceShort, type ModelCatalog } from '../../../src/manual-models/identity.ts';
import { ChoiceEditor, StatusBadge, vendorOf } from './ModelPicker.tsx';
import { lastChoice, mmApi, rememberChoice, type ModelChoice, type PendingChoiceView } from './mmApi.ts';
import './manual-models.css';

export const choiceKey = (runId: string, key: string) => `${runId}|${key}`;

/** A prompt of a copy & paste contestant that doesn't say which model it is (and can be given one here). */
export function isUnspecified(req: ManualRequest, c: ContestantView | undefined): boolean {
  return !req.runId.startsWith('arena-') && !c?.manualModel && (c?.providerType ?? 'manual') === 'manual';
}

/** The big "You are testing …" banner. */
export function TargetBanner({ catalog, info, label, locked, onChange }: { catalog: ModelCatalog; info: ManualModelInfo; label: string; locked?: boolean; onChange?: () => void }) {
  const model = catalog.models.find((m) => m.id === info.catalogId);
  const vendor = model ? vendorOf(catalog, model.vendor) : undefined;
  const where = interfaceShort(info.interface, vendor);
  return (
    <div className={cx('mm-target', info.webSearch && 'web')} style={{ ['--c' as string]: vendor?.color ?? 'var(--accent)' }}>
      <div className="mm-target-main">
        <div className="mm-eyebrow">You are testing</div>
        <div className="mm-target-name">{model?.label ?? label}</div>
        <div className="mm-target-how">
          in <b>{where}</b>
          {info.thinking === 'default' ? ' · thinking as the app has it' : ` · thinking ${info.thinking}`}
          {info.webSearch ? (
            <span className="badge warn">web search ON</span>
          ) : (
            <span className="muted"> · web search off</span>
          )}
          {info.note && <span className="muted"> · “{info.note}”</span>}
        </div>
        {model && <div className="mm-avail">{availabilityText(model, vendor)}</div>}
      </div>
      <div className="mm-target-side">
        {model && <StatusBadge model={model} />}
        {info.interface === 'company-app' && vendor?.chatAppUrl && (
          <a className="btn sm" href={vendor.chatAppUrl} target="_blank" rel="noreferrer">
            <Icon.External /> Open {vendor.chatApp}
          </a>
        )}
        {onChange && (
          <button type="button" className="btn sm ghost" onClick={onChange} disabled={locked} title={locked ? 'A reply of this conversation was already submitted with this model' : undefined}>
            {locked ? <Icon.Lock /> : <Icon.Edit />} {locked ? 'Locked for this conversation' : 'Change'}
          </button>
        )}
      </div>
    </div>
  );
}

/** Top of every Inbox card: which model this prompt goes into. */
export function InboxModelBar({ req, contestant, catalog, choice, onChoice, reloadCatalog }: { req: ManualRequest; contestant?: ContestantView; catalog: ModelCatalog | null; choice?: PendingChoiceView; onChoice: () => void; reloadCatalog: () => void }) {
  const toast = useToast();
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  if (!catalog) return null;
  if (contestant?.manualModel) return <TargetBanner catalog={catalog} info={contestant.manualModel} label={contestant.label} />;
  if (!isUnspecified(req, contestant)) return null;
  if (choice && !editing) return <TargetBanner catalog={catalog} info={choice.info} label={choice.contestantLabel} locked={choice.locked} onChange={() => setEditing(true)} />;
  const pick = async (c: ModelChoice) => {
    setBusy(true);
    try {
      await mmApi.choose(req.id, c);
      rememberChoice(c);
      setEditing(false);
      onChoice();
    } catch (e) {
      toast.error(e, 'Could not save the model');
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="mm-ask">
      <div className="mm-ask-head">
        <Icon.Cpu />
        <strong>Which model are you using?</strong>
        <span className="muted">Pick it before you paste, so the reply is saved under the right model.</span>
      </div>
      <ChoiceEditor catalog={catalog} initial={choice?.info ?? lastChoice()} onConfirm={(c) => void pick(c)} onCancel={choice ? () => setEditing(false) : undefined} busy={busy} onCatalogChanged={reloadCatalog} />
    </div>
  );
}

/** Small head chip on a collapsed card: "→ Claude 3 Opus (claude.ai)" or "model not chosen". */
export function InboxHeadChip({ req, contestant, choice }: { req: ManualRequest; contestant?: ContestantView; choice?: PendingChoiceView }) {
  if (contestant?.manualModel) return null;
  if (!isUnspecified(req, contestant)) return null;
  return choice ? <span className="badge accent mm-headchip">→ {choice.contestantLabel}</span> : <span className="badge warn mm-headchip">model not chosen</span>;
}

/** "N prompts don't say which model yet — use one model for all of them." */
export function BatchChooser({ requests, catalog, onDone, reloadCatalog }: { requests: ManualRequest[]; catalog: ModelCatalog | null; onDone: () => void; reloadCatalog: () => void }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  if (!catalog || requests.length < 2) return null;
  const apply = async (c: ModelChoice) => {
    setBusy(true);
    let ok = 0;
    const errors: string[] = [];
    for (const r of requests) {
      try {
        await mmApi.choose(r.id, c);
        ok++;
      } catch (e) {
        errors.push((e as Error).message);
      }
    }
    rememberChoice(c);
    setBusy(false);
    setOpen(false);
    if (ok) toast.success(`${ok} waiting prompt${ok === 1 ? '' : 's'} will be saved under this model.`, 'Model set');
    if (errors.length) toast.error([...new Set(errors)].join(' '), `${errors.length} not changed`);
    onDone();
  };
  return (
    <section className="card mm-batch">
      <div className="card-body">
        <div className="row wrap" style={{ gap: 12 }}>
          <Icon.Layers />
          <span>
            <b>{requests.length} waiting prompts</b> don’t say which model you are pasting them into.
          </span>
          <span className="spacer" />
          <button type="button" className={cx('btn sm', !open && 'primary')} onClick={() => setOpen((o) => !o)}>
            {open ? 'Close' : 'Use one model for all of them'}
          </button>
        </div>
        {open && (
          <div style={{ marginTop: 12 }}>
            <ChoiceEditor catalog={catalog} initial={lastChoice()} onConfirm={(c) => void apply(c)} onCancel={() => setOpen(false)} confirmLabel={`Use for all ${requests.length}`} busy={busy} onCatalogChanged={reloadCatalog} />
          </div>
        )}
      </div>
    </section>
  );
}

/** Old copy & paste results saved as "unspecified model": move them to a named model (explicit and logged). */
export function ReassignPanel({ catalog, reloadCatalog }: { catalog: ModelCatalog | null; reloadCatalog: () => void }) {
  const toast = useToast();
  const groups = useAsync(() => mmApi.unspecified(), []);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [choice, setChoice] = useState<ModelChoice | null>(null);
  const [note, setNote] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const list = groups.data ?? [];
  const gid = (g: (typeof list)[number]) => `${g.runId}|${g.contestantId}|${g.testId}`;
  const chosen = list.filter((g) => picked.has(gid(g)));
  const nResults = chosen.reduce((s, g) => s + g.keys.length, 0);
  const model = useMemo(() => (choice && catalog ? catalog.models.find((m) => m.id === choice.catalogId) : undefined), [choice, catalog]);
  if (!catalog || !list.length) return null;

  const run = async () => {
    if (!choice) return;
    setBusy(true);
    let moved = 0;
    const skipped: string[] = [];
    const byRun = new Map<string, string[]>();
    for (const g of chosen) byRun.set(g.runId, [...(byRun.get(g.runId) ?? []), ...g.keys]);
    try {
      for (const [runId, keys] of byRun) {
        const out = await mmApi.reassign({ runId, keys, to: choice, note: note.trim() || undefined });
        moved += out.moved;
        skipped.push(...out.skipped.map((s) => s.reason));
      }
      toast.success(`${moved} result${moved === 1 ? '' : 's'} moved. Each is marked “reassigned” and the move is logged.`, 'Reassigned');
      if (skipped.length) toast.info([...new Set(skipped)].join(' · '), `${skipped.length} left as they were`);
      setPicked(new Set());
      groups.reload();
    } catch (e) {
      toast.error(e, 'Could not reassign');
    } finally {
      setBusy(false);
      setConfirm(false);
    }
  };

  return (
    <details className="card mm-reassign">
      <summary className="card-head">
        <div className="t">
          <h2>Old copy &amp; paste results without a model name</h2>
          <div className="desc">
            {list.reduce((s, g) => s + g.keys.length, 0)} results were saved as “Manual entry (unspecified model)”. If you know which model made them, you can reassign them.
          </div>
        </div>
      </summary>
      <div className="card-body stack">
        <Callout tone="warn" icon={<Icon.Alert />}>
          Only do this if you are <b>sure</b> which model and app you used. Moved results are labelled “reassigned” wherever they appear, and every move is written to a log in your Gauntlet folder. It can’t be undone from here.
        </Callout>
        <table className="table mm-reassign-table">
          <thead>
            <tr>
              <th />
              <th>Run</th>
              <th>Test</th>
              <th className="num">Results</th>
              <th>Last answer</th>
            </tr>
          </thead>
          <tbody>
            {list.map((g) => (
              <tr key={gid(g)}>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`Select ${g.testName} in ${g.runName}`}
                    checked={picked.has(gid(g))}
                    onChange={(e) =>
                      setPicked((s) => {
                        const n = new Set(s);
                        if (e.target.checked) n.add(gid(g));
                        else n.delete(gid(g));
                        return n;
                      })
                    }
                  />
                </td>
                <td>
                  {g.runName} <span className="muted">· {g.contestantLabel}</span>
                </td>
                <td>{g.testName}</td>
                <td className="num tnum">{g.keys.length}</td>
                <td className="muted">{fmtDate(g.lastAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {chosen.length > 0 && (
          <>
            {choice && model ? (
              <TargetBanner catalog={catalog} info={choice} label={model.label} onChange={() => setChoice(null)} />
            ) : (
              <ChoiceEditor catalog={catalog} initial={lastChoice()} onConfirm={setChoice} confirmLabel="Choose this model" onCatalogChanged={reloadCatalog} />
            )}
            <label className="field">
              <span className="label">Why are you sure? (saved in the log)</span>
              <input className="input" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. recorded on video, the app showed Claude 3 Opus" />
            </label>
            <div className="row">
              <span className="spacer" />
              <button type="button" className="btn danger" disabled={!choice || busy} onClick={() => setConfirm(true)}>
                Reassign {nResults} result{nResults === 1 ? '' : 's'}…
              </button>
            </div>
          </>
        )}
      </div>
      <ConfirmDialog
        open={confirm}
        title="Reassign these results?"
        confirmLabel={`Move ${nResults} to ${model?.label ?? 'this model'}`}
        danger
        busy={busy}
        onCancel={() => setConfirm(false)}
        onConfirm={() => void run()}
        body={
          <p>
            {nResults} result{nResults === 1 ? '' : 's'} will count as <b>{model?.label}</b> ({choice ? interfaceShort(choice.interface, model ? vendorOf(catalog, model.vendor) : undefined) : ''}) from now on, marked “reassigned”. The move is logged.
          </p>
        }
      />
    </details>
  );
}
