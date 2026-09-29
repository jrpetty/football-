/**
 * New Run: "Test models by copy & paste". Tick models in the catalogue, say where you'll paste each one, and the
 * matching copy & paste contestants are made and added to the run (kept in your Gauntlet folder for next time).
 */
import { useMemo, useState } from 'react';
import { Callout, Seg, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { useToast } from '../context.tsx';
import { useAsync } from '../hooks.ts';
import type { ContestantView, ManualInterface } from '../types.ts';
import { fmtCatalogDate, suggestedInterface } from '../../../src/manual-models/identity.ts';
import { ModelSearch, StatusBadge, interfaceOptions, vendorOf } from './ModelPicker.tsx';
import { mmApi, type ModelChoice } from './mmApi.ts';
import './manual-models.css';

export function CopyPasteRunPanel({ onAdded, defaultOpen }: { onAdded: (views: ContestantView[]) => void; defaultOpen?: boolean }) {
  const toast = useToast();
  const [open, setOpen] = useState(Boolean(defaultOpen));
  const cat = useAsync(() => (open ? mmApi.catalog() : Promise.resolve(null)), [open]);
  const [picked, setPicked] = useState<Map<string, ManualInterface>>(new Map());
  const [thinking, setThinking] = useState<ModelChoice['thinking']>('default');
  const [web, setWeb] = useState(false);
  const [busy, setBusy] = useState(false);
  const catalog = cat.data ?? null;
  const selected = useMemo(() => new Set(picked.keys()), [picked]);

  const add = async () => {
    setBusy(true);
    try {
      const views = await mmApi.makeContestants([...picked].map(([catalogId, iface]) => ({ catalogId, interface: iface, thinking, webSearch: web })));
      toast.success(`${views.length} copy & paste model${views.length === 1 ? '' : 's'} added to this run. Their prompts will wait in the Manual Inbox.`, 'Added');
      onAdded(views);
      setPicked(new Map());
      setOpen(false);
    } catch (e) {
      toast.error(e, 'Could not add the models');
    } finally {
      setBusy(false);
    }
  };

  if (!open)
    return (
      <div className="mm-runbox">
        <Icon.Copy />
        <div>
          <b>Test models by copy &amp; paste</b>
          <div className="muted">Old models, chat-only apps, anything without an API key: pick them from a list of {'>'}75 models back to Claude 3 Opus.</div>
        </div>
        <span className="spacer" />
        <button type="button" className="btn primary" onClick={() => setOpen(true)}>
          <Icon.Plus /> Choose models
        </button>
      </div>
    );

  return (
    <div className="mm-runbox open">
      <div className="mm-runbox-head">
        <Icon.Copy />
        <b>Test models by copy &amp; paste</b>
        <span className="muted">Tick every model you will paste into. You copy each prompt from the Manual Inbox and paste the reply back.</span>
        <span className="spacer" />
        <button type="button" className="btn sm ghost" onClick={() => setOpen(false)}>
          Close
        </button>
      </div>
      {!catalog ? (
        <div className="muted" style={{ padding: 12 }}>
          Loading the model catalogue…
        </div>
      ) : (
        <div className="mm-run-grid">
          <ModelSearch
            catalog={catalog}
            selected={selected}
            height={420}
            onCatalogChanged={cat.reload}
            onToggle={(m) =>
              setPicked((p) => {
                const n = new Map(p);
                if (n.has(m.id)) n.delete(m.id);
                else n.set(m.id, suggestedInterface(m));
                return n;
              })
            }
          />
          <div className="mm-run-side">
            <div className="mm-run-side-title">
              {picked.size ? `${picked.size} model${picked.size === 1 ? '' : 's'} to test` : 'No models ticked yet'}
            </div>
            <div className="mm-run-picked">
              {[...picked].map(([id, iface]) => {
                const m = catalog.models.find((x) => x.id === id)!;
                const v = vendorOf(catalog, m.vendor);
                return (
                  <div key={id} className="mm-run-item" style={{ ['--c' as string]: v?.color ?? 'var(--accent)' }}>
                    <div className="row" style={{ gap: 8 }}>
                      <strong className="ellipsis">{m.label}</strong>
                      <span className="muted tnum">{fmtCatalogDate(m.released)}</span>
                      <span className="spacer" />
                      <StatusBadge model={m} />
                      <button type="button" className="btn xs ghost icon" aria-label={`Remove ${m.label}`} onClick={() => setPicked((p) => new Map([...p].filter(([k]) => k !== id)))}>
                        <Icon.X />
                      </button>
                    </div>
                    <select className="select sm" value={iface} aria-label={`Where you will paste ${m.label}`} onChange={(e) => setPicked((p) => new Map(p).set(id, e.target.value as ManualInterface))}>
                      {interfaceOptions(v).map((i) => (
                        <option key={i.id} value={i.id}>
                          {i.label}
                        </option>
                      ))}
                    </select>
                  </div>
                );
              })}
            </div>
            <div className="field">
              <span className="label">Thinking / extended reasoning (all of them)</span>
              <Seg
                small
                label="Thinking"
                value={thinking}
                onChange={setThinking}
                options={[
                  { value: 'default', label: 'As the app has it' },
                  { value: 'off', label: 'Off' },
                  { value: 'on', label: 'On' },
                ]}
              />
            </div>
            <div className="field">
              <span className="label">Web search</span>
              <Seg
                small
                label="Web search"
                value={web ? 'on' : 'off'}
                onChange={(v) => setWeb(v === 'on')}
                options={[
                  { value: 'off', label: 'Off (fair)' },
                  { value: 'on', label: 'On' },
                ]}
              />
            </div>
            {web && (
              <Callout tone="warn" icon={<Icon.Alert />}>
                Web search should be <b>off</b> for a fair test: API models can’t search. Results will be marked “web search on”.
              </Callout>
            )}
            <p className="muted mm-small">
              Each model + app + setting is its own contestant, so “Claude 3 Opus (claude.ai)” and “Claude 3 Opus (API playground)” are never mixed. They are saved in your Gauntlet folder and survive updates.
            </p>
            <button type="button" className={cx('btn primary', 'mm-run-add')} disabled={!picked.size || busy} onClick={() => void add()}>
              <Icon.Check /> {busy ? 'Adding…' : `Add ${picked.size || ''} to this run`}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
