/**
 * "Which model are you using?" — a searchable picker over the model catalogue (grouped by company, newest first,
 * with release dates and plain availability badges), plus the interface and settings used.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { Callout, Seg, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { useToast } from '../context.tsx';
import type { ManualInterface } from '../types.ts';
import {
  INTERFACES,
  availabilityText,
  fmtCatalogDate,
  groupedCatalog,
  isReachable,
  matchesSearch,
  statusBadge,
  suggestedInterface,
  type CatalogModel,
  type CatalogVendor,
  type ModelCatalog,
} from '../../../src/manual-models/identity.ts';
import { mmApi, type ModelChoice } from './mmApi.ts';

export const vendorOf = (catalog: ModelCatalog, name: string): CatalogVendor | undefined => catalog.vendors.find((v) => v.id === name);

export function StatusBadge({ model, lg }: { model: CatalogModel; lg?: boolean }) {
  const b = statusBadge(model);
  const cls = b.tone === 'good' ? 'good' : b.tone === 'warn' ? 'warn' : b.tone === 'bad' ? 'bad' : 'outline';
  return <span className={cx('badge', cls, lg && 'lg')}>{b.text}</span>;
}

/** Searchable list of catalogue models. Single pick (onPick) or multi pick (selected + onToggle). */
export function ModelSearch({
  catalog,
  value,
  onPick,
  selected,
  onToggle,
  onCatalogChanged,
  autoFocus,
  height = 340,
}: {
  catalog: ModelCatalog;
  value?: string | null;
  onPick?: (m: CatalogModel) => void;
  selected?: Set<string>;
  onToggle?: (m: CatalogModel) => void;
  onCatalogChanged?: () => void;
  autoFocus?: boolean;
  height?: number;
}) {
  const [q, setQ] = useState('');
  const [usable, setUsable] = useState(false);
  const [suggest, setSuggest] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (autoFocus) input.current?.focus();
  }, [autoFocus]);
  const groups = useMemo(() => groupedCatalog(catalog, catalog.models.filter((m) => matchesSearch(m, q) && (!usable || isReachable(m)))), [catalog, q, usable]);
  const count = groups.reduce((s, g) => s + g.models.length, 0);
  const multi = Boolean(selected && onToggle);

  return (
    <div className="mm-search">
      <div className="mm-search-bar">
        <Icon.Search className="mm-search-icon" />
        <input
          ref={input}
          className="input"
          placeholder={`Search ${catalog.models.length} models — e.g. “opus 3”, “gpt-4o”, “2024”`}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          aria-label="Search the model catalogue"
        />
        <label className="mm-check" title="Hide models that are retired everywhere we know of">
          <input type="checkbox" checked={usable} onChange={(e) => setUsable(e.target.checked)} /> Only ones I can still use
        </label>
      </div>
      <div className="mm-list" style={{ maxHeight: height }} role="listbox" aria-label="Models" aria-multiselectable={multi || undefined}>
        {groups.map((g) => (
          <div key={g.vendor.id} className="mm-group">
            <div className="mm-group-head">
              <i style={{ background: g.vendor.color }} />
              {g.vendor.id}
              <span className="muted">· {g.vendor.chatApp}</span>
            </div>
            {g.models.map((m) => {
              const on = multi ? selected!.has(m.id) : value === m.id;
              return (
                <button
                  key={m.id}
                  type="button"
                  role="option"
                  aria-selected={on}
                  className={cx('mm-row', on && 'on')}
                  onClick={() => (multi ? onToggle!(m) : onPick?.(m))}
                  title={availabilityText(m, g.vendor)}
                >
                  {multi && <span className={cx('mm-tick', on && 'on')}>{on && <Icon.Check />}</span>}
                  <span className="mm-row-main">
                    <span className="mm-row-top">
                      <strong>{m.label}</strong>
                      <span className="muted tnum">{fmtCatalogDate(m.released)}</span>
                      {m.reasoning && <span className="badge outline mm-mini">reasoning</span>}
                      {m.vision && <span className="badge outline mm-mini">images</span>}
                    </span>
                    <span className="mm-row-avail">{availabilityText(m, g.vendor)}</span>
                  </span>
                  <StatusBadge model={m} />
                </button>
              );
            })}
          </div>
        ))}
        {count === 0 && <div className="mm-empty">No model matches “{q}”. Try fewer words, or suggest it below.</div>}
      </div>
      <div className="mm-search-foot">
        <span className="muted">
          {count} of {catalog.models.length} · availability checked {fmtCatalogDate(catalog.checkedAt)}
        </span>
        <span className="spacer" />
        <button type="button" className="btn xs ghost" onClick={() => setSuggest((s) => !s)}>
          <Icon.Plus /> Suggest a model not in the list
        </button>
      </div>
      {suggest && (
        <SuggestForm
          vendors={catalog.vendors}
          initialName={q}
          onDone={(m) => {
            setSuggest(false);
            setQ(m.label);
            onCatalogChanged?.();
          }}
        />
      )}
    </div>
  );
}

function SuggestForm({ vendors, initialName, onDone }: { vendors: CatalogVendor[]; initialName: string; onDone: (m: CatalogModel) => void }) {
  const toast = useToast();
  const [label, setLabel] = useState(initialName);
  const [vendor, setVendor] = useState(vendors[0]?.id ?? 'Other');
  const [released, setReleased] = useState('');
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      const m = await mmApi.suggestModel({ label, vendor, released });
      toast.success(`${m.label} added to your own list (marked unverified).`, 'Model added');
      onDone(m);
    } catch (e) {
      toast.error(e, 'Could not add it');
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="mm-suggest">
      <label className="field">
        <span className="label">Model name</span>
        <input className="input sm" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="e.g. Claude 2.1" />
      </label>
      <label className="field">
        <span className="label">Company</span>
        <select className="select sm" value={vendor} onChange={(e) => setVendor(e.target.value)}>
          {vendors.map((v) => (
            <option key={v.id}>{v.id}</option>
          ))}
          <option>Other</option>
        </select>
      </label>
      <label className="field">
        <span className="label">Released (month)</span>
        <input className="input sm" type="month" value={released} onChange={(e) => setReleased(e.target.value)} />
      </label>
      <button type="button" className="btn sm primary" disabled={busy || !label.trim() || !released} onClick={() => void save()}>
        Add to my list
      </button>
      <p className="muted mm-suggest-note">Saved in your own Gauntlet folder, so it survives updates. It is marked “unverified” because nobody has checked it.</p>
    </div>
  );
}

export function interfaceOptions(vendor?: CatalogVendor): Array<{ id: ManualInterface; label: string; hint: string }> {
  return INTERFACES.map((i) =>
    i.id === 'company-app' && vendor ? { ...i, label: `${vendor.chatApp} (${vendor.id}’s own app)` } : i.id === 'api-playground' && vendor?.playground ? { ...i, label: `API playground (${vendor.playground})` } : i,
  );
}

/** Model + interface + settings, ending in "Use this model". */
export function ChoiceEditor({
  catalog,
  initial,
  onConfirm,
  onCancel,
  confirmLabel = 'Use this model',
  busy,
  onCatalogChanged,
}: {
  catalog: ModelCatalog;
  initial?: ModelChoice | null;
  onConfirm: (c: ModelChoice) => void;
  onCancel?: () => void;
  confirmLabel?: string;
  busy?: boolean;
  onCatalogChanged?: () => void;
}) {
  const [catalogId, setCatalogId] = useState<string | null>(initial?.catalogId ?? null);
  const [picking, setPicking] = useState(!initial?.catalogId);
  const [iface, setIface] = useState<ManualInterface>(initial?.interface ?? 'company-app');
  const [thinking, setThinking] = useState<ModelChoice['thinking']>(initial?.thinking ?? 'default');
  const [web, setWeb] = useState(initial?.webSearch ?? false);
  const [note, setNote] = useState(initial?.note ?? '');
  const model = catalog.models.find((m) => m.id === catalogId) ?? null;
  const vendor = model ? vendorOf(catalog, model.vendor) : undefined;

  return (
    <div className="mm-editor">
      {picking || !model ? (
        <ModelSearch
          catalog={catalog}
          value={catalogId}
          autoFocus
          onCatalogChanged={onCatalogChanged}
          onPick={(m) => {
            setCatalogId(m.id);
            setPicking(false);
            if (m.id !== initial?.catalogId) setIface(suggestedInterface(m));
          }}
        />
      ) : (
        <div className="mm-picked">
          <span className="mm-dot" style={{ background: vendor?.color }} />
          <div className="mm-picked-main">
            <div className="row wrap" style={{ gap: 8 }}>
              <strong className="mm-picked-name">{model.label}</strong>
              <span className="muted">
                {model.vendor} · released {fmtCatalogDate(model.releaseDate ?? model.released)}
              </span>
              <StatusBadge model={model} />
            </div>
            <div className="mm-avail">{availabilityText(model, vendor)}</div>
          </div>
          <button type="button" className="btn sm ghost" onClick={() => setPicking(true)}>
            Change model
          </button>
        </div>
      )}

      {model && !picking && (
        <div className="mm-settings">
          <label className="field">
            <span className="label">Where are you pasting it?</span>
            <select className="select" value={iface} onChange={(e) => setIface(e.target.value as ManualInterface)}>
              {interfaceOptions(vendor).map((i) => (
                <option key={i.id} value={i.id} title={i.hint}>
                  {i.label}
                </option>
              ))}
            </select>
          </label>
          <div className="field">
            <span className="label">Thinking / extended reasoning</span>
            <Seg
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
              label="Web search"
              value={web ? 'on' : 'off'}
              onChange={(v) => setWeb(v === 'on')}
              options={[
                { value: 'off', label: 'Off (fair)' },
                { value: 'on', label: 'On' },
              ]}
            />
          </div>
          <label className="field mm-note">
            <span className="label">Note (optional)</span>
            <input className="input" value={note} maxLength={200} placeholder="e.g. Pro plan, model menu said “Opus 3”" onChange={(e) => setNote(e.target.value)} />
          </label>
          {web && (
            <div className="mm-wide">
              <Callout tone="warn" icon={<Icon.Alert />}>
                <b>Turn web search OFF for a fair test.</b> API models never search the web, so a model that can look things up is not being tested on the same thing. If you keep it on, every result is marked “web search on”.
              </Callout>
            </div>
          )}
          {model.status === 'unverified' && (
            <div className="mm-wide">
              <Callout tone="info" icon={<Icon.Info />}>
                We could not confirm where {model.label} is available today. Record exactly what the app’s model menu said in the note.
              </Callout>
            </div>
          )}
        </div>
      )}

      <div className="mm-editor-actions">
        {onCancel && (
          <button type="button" className="btn sm ghost" onClick={onCancel}>
            Cancel
          </button>
        )}
        <span className="spacer" />
        <button
          type="button"
          className="btn primary"
          disabled={!model || picking || busy}
          onClick={() => model && onConfirm({ catalogId: model.id, interface: iface, thinking, webSearch: web, ...(note.trim() ? { note: note.trim() } : {}) })}
        >
          <Icon.Check /> {confirmLabel}
        </button>
      </div>
    </div>
  );
}
