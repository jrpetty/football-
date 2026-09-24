import { useMemo, useState } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { useMeta, useToast, useViewerCaption } from '../context.tsx';
import { Callout, ConfirmDialog, Drawer, Empty, ErrorState, Field, PageHead, SkeletonRows, Switch, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { SERIES_PALETTE } from '../components/charts/scale.ts';
import { fmtCost, fmtDate, fmtMs, fmtPricePerM, fmtTokens, slugify } from '../format.ts';
import type { Contestant, ContestantOptions, ContestantView, PingResult, ProviderView } from '../types.ts';
import { VisionBadge } from '../components/VisionImage.tsx';

const EFFORTS: Array<NonNullable<ContestantOptions['effort']>> = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];

function toContestant(c: ContestantView | Contestant): Contestant {
  const { configHash: _a, hasKey: _b, providerLabel: _c, providerType: _d, ...rest } = c as ContestantView;
  return rest;
}

function KeyStatus({ p }: { p: ProviderView | undefined }) {
  if (!p) return <span className="badge">unknown provider</span>;
  if (p.type === 'manual') return <span className="badge info">no key needed</span>;
  if (!p.apiKeyEnv) return <span className="badge">no key needed</span>;
  return p.hasKey ? (
    <span className="badge good" title={`${p.apiKeyEnv} is set`}>
      <Icon.Check /> set
    </span>
  ) : (
    <span className="badge bad" title={`${p.apiKeyEnv} is not set`}>
      <Icon.X /> missing
    </span>
  );
}

function ContestantForm({
  initial,
  isNew,
  providers,
  discovered,
  onCancel,
  onSaved,
  usedColors,
}: {
  initial: Contestant;
  isNew: boolean;
  providers: ProviderView[];
  discovered: Record<string, string[]>;
  onCancel: () => void;
  onSaved: (c: ContestantView) => void;
  usedColors: string[];
}) {
  const toast = useToast();
  const [c, setC] = useState<Contestant>(initial);
  const [extra, setExtra] = useState(initial.options?.extraBody ? JSON.stringify(initial.options.extraBody, null, 2) : '');
  const [extraErr, setExtraErr] = useState<string | null>(null);
  const [idTouched, setIdTouched] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const provider = providers.find((p) => p.id === c.provider);
  const manual = provider?.type === 'manual';
  const set = (patch: Partial<Contestant>) =>
    setC((x) => {
      const next = { ...x, ...patch };
      if (isNew && !idTouched && (patch.label !== undefined || patch.provider !== undefined)) next.id = slugify(`${next.provider}-${next.label}`);
      return next;
    });
  const setOpt = (patch: Partial<ContestantOptions>) => setC((x) => ({ ...x, options: { ...(x.options ?? {}), ...patch } }));
  const setPrice = (patch: Partial<Contestant['pricing']>) => setC((x) => ({ ...x, pricing: { ...x.pricing, ...patch } }));
  const numOrU = (v: string) => (v === '' ? undefined : Number(v));

  const save = async () => {
    const errs: string[] = [];
    if (!c.id.trim()) errs.push('Id is required.');
    if (!c.label.trim()) errs.push('Label is required.');
    if (!c.provider) errs.push('Pick a provider.');
    if (!c.model.trim()) errs.push('Model id is required.');
    let extraBody: Record<string, unknown> | undefined;
    if (extra.trim()) {
      try {
        const v = JSON.parse(extra) as unknown;
        if (!v || typeof v !== 'object' || Array.isArray(v)) throw new Error('must be a JSON object');
        extraBody = v as Record<string, unknown>;
      } catch (e) {
        errs.push(`extraBody: ${(e as Error).message}`);
      }
    }
    setErrors(errs);
    if (errs.length) return;
    const options: ContestantOptions = { ...(c.options ?? {}), extraBody };
    for (const k of Object.keys(options) as Array<keyof ContestantOptions>) if (options[k] === undefined || (options[k] as unknown) === '') delete options[k];
    const body: Contestant = { ...toContestant(c), options: Object.keys(options).length ? options : undefined };
    setSaving(true);
    try {
      const saved = await api.saveContestant(body);
      toast.success(`${saved.label} saved.`);
      onSaved(saved);
    } catch (e) {
      const details = (e as { details?: unknown }).details;
      if (Array.isArray(details)) setErrors(details.map(String));
      toast.error(e, 'Could not save model');
    } finally {
      setSaving(false);
    }
  };

  const dl = `models-${c.provider}`;
  return (
    <Drawer
      open
      onClose={onCancel}
      width={720}
      title={isNew ? 'Add model' : `Edit ${initial.label}`}
      sub={isNew ? 'A contestant is one model plus one fixed configuration.' : <span className="mono">{c.id}</span>}
      foot={
        <>
          <button className="btn ghost" onClick={onCancel}>
            Cancel
          </button>
          <button className="btn primary" onClick={save} disabled={saving}>
            {saving ? 'Saving…' : isNew ? 'Add model' : 'Save changes'}
          </button>
        </>
      }
    >
      <div className="stack loose">
        {errors.length > 0 && (
          <Callout tone="bad">
            {errors.map((e) => (
              <div key={e}>{e}</div>
            ))}
          </Callout>
        )}
        {!isNew && (
          <Callout tone="info" icon={<Icon.Fingerprint />}>
            Changing the model id, options or provider changes this contestant’s config hash; its earlier results then stop counting toward the leaderboard.
          </Callout>
        )}
        <div className="form-grid">
          <Field label="Label" htmlFor="m-label" className="span-2">
            <input id="m-label" className="input" value={c.label} placeholder="Atlas-4 Ultra" onChange={(e) => set({ label: e.target.value })} />
          </Field>
          <Field label="Vendor" htmlFor="m-vendor" hint={manual ? 'The company behind the chat app — used to keep judges impartial.' : undefined}>
            <input id="m-vendor" className="input" value={c.vendor} onChange={(e) => set({ vendor: e.target.value })} />
          </Field>
          <Field label="Provider" htmlFor="m-provider">
            <select id="m-provider" className="select" value={c.provider} onChange={(e) => set({ provider: e.target.value })}>
              <option value="">Choose…</option>
              {providers.map((p) => (
                <option key={p.id} value={p.id}>
                  {p.label}
                  {p.type !== 'manual' && p.apiKeyEnv && !p.hasKey ? ' (no key)' : ''}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Model id" hint={manual ? 'Describe exactly what you tested (app, mode, date).' : 'Sent to the provider API.'} htmlFor="m-model" className="span-2">
            <input id="m-model" className="input mono" list={dl} value={c.model} onChange={(e) => set({ model: e.target.value })} />
            <datalist id={dl}>
              {(discovered[c.provider] ?? []).map((m) => (
                <option key={m} value={m} />
              ))}
            </datalist>
          </Field>
          <Field label="Contestant id" hint="Stable identifier; results are keyed by it." htmlFor="m-id" className="span-2">
            <input
              id="m-id"
              className="input mono"
              value={c.id}
              disabled={!isNew}
              onChange={(e) => {
                setIdTouched(true);
                set({ id: e.target.value });
              }}
            />
          </Field>
        </div>

        {manual && (
          <Callout tone="info" icon={<Icon.Copy />}>
            <strong>Manual (copy &amp; paste) model.</strong> Every prompt goes to the <em>Manual Inbox</em>; you paste it into the chat app and paste the reply back. Make each chatbot or product you test by hand its <strong>own model entry</strong> with the correct vendor
            (e.g. “Orbit Chat (web)” by Orbit Labs), so judges from the same company are excluded and results don’t mix.
          </Callout>
        )}

        <div className="field">
          <span className="label">Colour</span>
          <div className="row wrap" style={{ gap: 8 }}>
            <input type="color" className="color-input" value={c.color || '#3987e5'} onChange={(e) => set({ color: e.target.value })} aria-label="Pick a colour" />
            {SERIES_PALETTE.map((col) => (
              <button key={col} type="button" className={cx('swatch-btn', c.color.toLowerCase() === col && 'on', usedColors.includes(col) && 'used')} style={{ background: col }} aria-label={`Use colour ${col}`} title={usedColors.includes(col) ? `${col} (already used)` : col} onClick={() => set({ color: col })} />
            ))}
            <span className="mono muted" style={{ fontSize: '0.8rem' }}>
              {c.color}
            </span>
          </div>
          <span className="hint" style={{ fontSize: '0.78rem', color: 'var(--text-3)' }}>
            Suggested colours are a colour-blind-checked set; prefer one not already used.
          </span>
        </div>

        <div className="row">
          <Switch checked={c.enabled} onChange={(v) => set({ enabled: v })} label="Enabled" />
          <span>Enabled — appears in New Run</span>
        </div>

        {!manual && (
          <fieldset className="fieldset">
            <legend>Pricing (USD per 1M tokens)</legend>
            <div className="form-grid">
              <Field label="Input">
                <input className="input tnum" type="number" step="any" min={0} value={c.pricing.inputPerM} onChange={(e) => setPrice({ inputPerM: Number(e.target.value) })} />
              </Field>
              <Field label="Output (incl. reasoning)">
                <input className="input tnum" type="number" step="any" min={0} value={c.pricing.outputPerM} onChange={(e) => setPrice({ outputPerM: Number(e.target.value) })} />
              </Field>
              <Field label="Cached input">
                <input className="input tnum" type="number" step="any" min={0} placeholder="= input" value={c.pricing.cachedInputPerM ?? ''} onChange={(e) => setPrice({ cachedInputPerM: numOrU(e.target.value) })} />
              </Field>
              <Field label="Cache write">
                <input className="input tnum" type="number" step="any" min={0} placeholder="= input" value={c.pricing.cacheWritePerM ?? ''} onChange={(e) => setPrice({ cacheWritePerM: numOrU(e.target.value) })} />
              </Field>
              <Field label="Source" className="span-2">
                <input className="input" placeholder="URL of the pricing page" value={c.pricing.source ?? ''} onChange={(e) => setPrice({ source: e.target.value || undefined })} />
              </Field>
              <Field label="Verified on" hint={c.pricing.verifiedAt ? undefined : 'Unverified prices show an amber badge.'} className="span-2">
                <div className="row">
                  <input className="input" type="date" value={c.pricing.verifiedAt ?? ''} onChange={(e) => setPrice({ verifiedAt: e.target.value || null })} />
                  <button type="button" className="btn sm" onClick={() => setPrice({ verifiedAt: new Date().toISOString().slice(0, 10) })}>
                    Today
                  </button>
                  <button type="button" className="btn sm ghost" onClick={() => setPrice({ verifiedAt: null })}>
                    Clear
                  </button>
                </div>
              </Field>
            </div>
          </fieldset>
        )}

        {!manual && (
          <fieldset className="fieldset">
            <legend>Options</legend>
            <div className="form-grid">
              <Field label="Reasoning effort" hint="Mapped per provider.">
                <select className="select" value={c.options?.effort ?? ''} onChange={(e) => setOpt({ effort: (e.target.value || undefined) as ContestantOptions['effort'] })}>
                  <option value="">provider default</option>
                  {EFFORTS.map((x) => (
                    <option key={x} value={x}>
                      {x}
                    </option>
                  ))}
                </select>
              </Field>
              <label className="check" style={{ alignSelf: 'end', paddingBottom: 8 }}>
                <input type="checkbox" checked={!!c.options?.supportsTemperature} onChange={(e) => setOpt({ supportsTemperature: e.target.checked })} /> Accepts temperature
              </label>
              <label className="check" style={{ alignSelf: 'end', paddingBottom: 8 }} title="Vision tests are skipped (not scored as 0) for models that don’t accept images">
                <input type="checkbox" checked={!!c.vision} onChange={(e) => set({ vision: e.target.checked })} /> Accepts images (vision tests)
              </label>
              <Field label="Temperature" hint={c.options?.supportsTemperature ? 'Overrides the harness default.' : 'Only sent when accepted.'}>
                <input className="input tnum" type="number" step={0.1} min={0} max={2} disabled={!c.options?.supportsTemperature} placeholder="harness default" value={c.options?.temperature ?? ''} onChange={(e) => setOpt({ temperature: numOrU(e.target.value) })} />
              </Field>
              <Field label="Max output tokens cap">
                <input className="input tnum" type="number" min={1} placeholder="none" value={c.options?.maxOutputTokensCap ?? ''} onChange={(e) => setOpt({ maxOutputTokensCap: numOrU(e.target.value) })} />
              </Field>
              <Field label="Context window">
                <input className="input tnum" type="number" min={1} placeholder="unknown" value={c.contextWindow ?? ''} onChange={(e) => set({ contextWindow: numOrU(e.target.value) })} />
              </Field>
              <Field label="extraBody (JSON)" hint="Deep-merged into every request body." error={extraErr ?? undefined} className="span-all">
                <textarea
                  className={cx('textarea mono', extraErr && 'invalid')}
                  rows={3}
                  placeholder='{ "top_p": 1 }'
                  value={extra}
                  onChange={(e) => {
                    setExtra(e.target.value);
                    if (!e.target.value.trim()) return setExtraErr(null);
                    try {
                      JSON.parse(e.target.value);
                      setExtraErr(null);
                    } catch (ex) {
                      setExtraErr((ex as Error).message);
                    }
                  }}
                />
              </Field>
            </div>
          </fieldset>
        )}
        <Field label="Notes">
          <textarea className="textarea" rows={2} value={c.notes ?? ''} onChange={(e) => set({ notes: e.target.value || undefined })} />
        </Field>
      </div>
    </Drawer>
  );
}

function DiscoverDrawer({ provider, existing, onClose, onAdd }: { provider: ProviderView; existing: Set<string>; onClose: () => void; onAdd: (model: string, all: string[]) => void }) {
  const list = useAsync(() => api.providerModels(provider.id), [provider.id]);
  const [q, setQ] = useState('');
  const models = (list.data?.models ?? []).filter((m) => m.toLowerCase().includes(q.toLowerCase()));
  return (
    <Drawer open onClose={onClose} width={560} title={`Discover · ${provider.label}`} sub="Live list from the provider’s models endpoint.">
      <div className="stack">
        <div className="search">
          <Icon.Search />
          <input className="input" placeholder="Filter models…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Filter models" data-autofocus />
        </div>
        {list.loading && !list.data ? (
          <SkeletonRows rows={6} h={40} />
        ) : list.error ? (
          <Callout tone="bad">{list.error.message}</Callout>
        ) : models.length === 0 ? (
          <div className="muted">No models found.</div>
        ) : (
          <ul className="discover-list">
            {models.map((m) => (
              <li key={m}>
                <span className="mono">{m}</span>
                <span className="spacer" />
                {existing.has(m) && <span className="badge good">configured</span>}
                <button className="btn xs" onClick={() => onAdd(m, list.data?.models ?? [])}>
                  <Icon.Plus /> Add as contestant
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Drawer>
  );
}

export default function ModelsPage() {
  useViewerCaption('The contestants: every AI model in the lab, who makes it and what it costs per million tokens of text.', 'API keys are read from the environment and never shown');
  const { meta, reload: reloadMeta } = useMeta();
  const toast = useToast();
  const list = useAsync<ContestantView[]>(() => api.contestants(), []);
  const providers = useMemo(() => meta?.providers ?? [], [meta]);
  const [editing, setEditing] = useState<{ c: Contestant; isNew: boolean } | null>(null);
  const [discover, setDiscover] = useState<ProviderView | null>(null);
  const [discovered, setDiscovered] = useState<Record<string, string[]>>({});
  const [pings, setPings] = useState<Record<string, PingResult | 'loading'>>({});
  const [confirm, setConfirm] = useState<ContestantView | null>(null);
  const cons = list.data ?? [];
  const usedColors = cons.map((c) => c.color.toLowerCase());

  const nextColor = () => SERIES_PALETTE.find((c) => !usedColors.includes(c)) ?? SERIES_PALETTE[cons.length % SERIES_PALETTE.length];

  const blank = (provider = providers.find((p) => p.type !== 'mock')?.id ?? '', model = ''): Contestant => {
    const p = providers.find((x) => x.id === provider);
    return {
      id: model ? slugify(`${provider}-${model}`) : '',
      label: model,
      vendor: p?.type === 'manual' ? '' : p?.label ?? '',
      provider,
      model,
      color: nextColor(),
      enabled: true,
      pricing: { inputPerM: 0, outputPerM: 0, verifiedAt: null },
    };
  };

  const toggle = async (c: ContestantView, enabled: boolean) => {
    list.setData((xs) => (xs ?? []).map((x) => (x.id === c.id ? { ...x, enabled } : x)));
    try {
      const saved = await api.saveContestant({ ...toContestant(c), enabled });
      list.setData((xs) => (xs ?? []).map((x) => (x.id === c.id ? saved : x)));
    } catch (e) {
      list.setData((xs) => (xs ?? []).map((x) => (x.id === c.id ? c : x)));
      toast.error(e, 'Could not update');
    }
  };

  const ping = async (c: ContestantView) => {
    setPings((p) => ({ ...p, [c.id]: 'loading' }));
    try {
      const r = await api.ping(c.id);
      setPings((p) => ({ ...p, [c.id]: r }));
    } catch (e) {
      setPings((p) => ({ ...p, [c.id]: { ok: false, error: (e as Error).message } }));
    }
  };

  const remove = async () => {
    if (!confirm) return;
    try {
      await api.deleteContestant(confirm.id);
      list.setData((xs) => (xs ?? []).filter((x) => x.id !== confirm.id));
      toast.success(`Removed ${confirm.label}.`);
    } catch (e) {
      toast.error(e, 'Could not delete');
    } finally {
      setConfirm(null);
    }
  };

  const missingKeys = providers.filter((p) => p.type !== 'manual' && p.type !== 'mock' && p.apiKeyEnv && !p.hasKey);

  return (
    <div className="page">
      <PageHead
        eyebrow="Lab"
        title="Models"
        sub="Contestants are a model plus a fixed configuration. API keys live in environment variables on the server — they are never shown here."
        actions={
          <button className="btn primary" onClick={() => setEditing({ c: blank(), isNew: true })}>
            <Icon.Plus /> Add model
          </button>
        }
      />

      <section className="card">
        <div className="card-head">
          <div className="t">
            <h2>Contestants</h2>
            <div className="desc">
              {cons.filter((c) => c.enabled).length} enabled of {cons.length}
            </div>
          </div>
        </div>
        {list.loading && !list.data ? (
          <div className="card-body">
            <SkeletonRows rows={6} h={46} />
          </div>
        ) : list.error && !list.data ? (
          <div className="card-body">
            <ErrorState error={list.error} onRetry={list.reload} />
          </div>
        ) : cons.length === 0 ? (
          <Empty
            icon={<Icon.Cpu />}
            title="No models yet"
            actions={
              <button className="btn primary" onClick={() => setEditing({ c: blank(), isNew: true })}>
                <Icon.Plus /> Add model
              </button>
            }
          >
            Add a model manually, or use “Discover” on a provider below.
          </Empty>
        ) : (
          <div className="table-wrap">
            <table className="table models-table">
              <thead>
                <tr>
                  <th>Model</th>
                  <th>Provider</th>
                  <th>Model id</th>
                  <th>Effort</th>
                  <th className="num">In / 1M</th>
                  <th className="num">Out / 1M</th>
                  <th>Price verified</th>
                  <th className="center">Key</th>
                  <th className="center">Enabled</th>
                  <th className="num">Actions</th>
                </tr>
              </thead>
              <tbody>
                {cons.map((c) => {
                  const p = providers.find((x) => x.id === c.provider);
                  const manual = c.providerType === 'manual';
                  const pr = pings[c.id];
                  return [
                    <tr key={c.id} className={cx(!c.enabled && 'disabled-row')}>
                      <td>
                        <div className="model-cell">
                          <span className="bar" style={{ background: c.color }} />
                          <div className="names">
                            <span className="label">{c.label}</span>
                            <span className="vendor">
                              {c.vendor} · <span className="mono">{c.id}</span>
                            </span>
                          </div>
                        </div>
                      </td>
                      <td>
                        <span className="nowrap">{c.providerLabel || c.provider}</span>
                        {manual && (
                          <div>
                            <span className="badge info">copy &amp; paste</span>
                          </div>
                        )}
                      </td>
                      <td className="mono" style={{ fontSize: '0.8rem', maxWidth: 220, overflowWrap: 'anywhere' }}>
                        {c.model}
                        {c.vision && (
                          <div>
                            <VisionBadge label="sees images" />
                          </div>
                        )}
                      </td>
                      <td>{c.options?.effort ?? <span className="muted">default</span>}</td>
                      <td className="num">{manual ? <span className="muted">—</span> : fmtPricePerM(c.pricing?.inputPerM)}</td>
                      <td className="num">{manual ? <span className="muted">—</span> : fmtPricePerM(c.pricing?.outputPerM)}</td>
                      <td>
                        {manual ? (
                          <span className="muted">n/a</span>
                        ) : c.pricing?.verifiedAt ? (
                          <span className="nowrap" title={c.pricing.source}>
                            {fmtDate(c.pricing.verifiedAt)}
                          </span>
                        ) : (
                          <span className="badge warn" title="Pricing not verified by a human — costs may be wrong">
                            <Icon.Alert /> unverified
                          </span>
                        )}
                      </td>
                      <td className="center">
                        {manual || !p?.apiKeyEnv ? (
                          <span className="muted" title="No API key needed">
                            —
                          </span>
                        ) : c.hasKey ? (
                          <span className="key-ok" title={`${p.apiKeyEnv} is set`} aria-label="API key set">
                            ✓
                          </span>
                        ) : (
                          <span className="key-bad" title={`${p.apiKeyEnv} is not set`} aria-label="API key missing">
                            ✗
                          </span>
                        )}
                      </td>
                      <td className="center">
                        <Switch checked={c.enabled} onChange={(v) => toggle(c, v)} label={`${c.enabled ? 'Disable' : 'Enable'} ${c.label}`} />
                      </td>
                      <td className="num">
                        <div className="row" style={{ justifyContent: 'flex-end', gap: 4 }}>
                          {!manual && (
                            <button className="btn xs" onClick={() => ping(c)} disabled={pr === 'loading'} title="Send a tiny test request">
                              <Icon.Zap /> {pr === 'loading' ? 'Pinging…' : 'Ping'}
                            </button>
                          )}
                          <button className="btn xs" onClick={() => setEditing({ c: toContestant(c), isNew: false })}>
                            <Icon.Edit /> Edit
                          </button>
                          <button className="btn xs ghost icon" aria-label={`Delete ${c.label}`} title="Delete" onClick={() => setConfirm(c)}>
                            <Icon.Trash />
                          </button>
                        </div>
                      </td>
                    </tr>,
                    pr && pr !== 'loading' ? (
                      <tr key={`${c.id}-ping`} className="ping-row">
                        <td colSpan={10}>
                          {pr.ok ? (
                            <div className="ping ok">
                              <span className="badge good">
                                <Icon.Check /> reachable
                              </span>
                              <span>
                                latency <b className="tnum">{fmtMs(pr.totalMs)}</b>
                              </span>
                              <span>
                                TTFT <b className="tnum">{fmtMs(pr.ttftMs)}</b>
                              </span>
                              <span>
                                tokens <b className="tnum">{fmtTokens(pr.usage?.inputTokens)} / {fmtTokens(pr.usage?.outputTokens)}</b>
                              </span>
                              <span>
                                cost <b className="tnum">{fmtCost(pr.costUsd)}</b>
                              </span>
                              <span className="ping-text mono">“{pr.text}”</span>
                              <button className="btn ghost icon xs" aria-label="Dismiss ping result" onClick={() =>
                                  setPings((x) => {
                                    const n = { ...x };
                                    delete n[c.id];
                                    return n;
                                  })
                                }>
                                <Icon.X />
                              </button>
                            </div>
                          ) : (
                            <div className="ping bad">
                              <span className="badge bad">
                                <Icon.X /> failed
                              </span>
                              <span>{pr.error}</span>
                            </div>
                          )}
                        </td>
                      </tr>
                    ) : null,
                  ];
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="card">
        <div className="card-head">
          <div className="t">
            <h2>Providers</h2>
            <div className="desc">Set API keys as environment variables on the machine running the server, then restart it.</div>
          </div>
          <div className="tools">
            <button className="btn sm" onClick={reloadMeta}>
              <Icon.Refresh /> Re-check keys
            </button>
          </div>
        </div>
        {missingKeys.length > 0 && (
          <div className="card-body" style={{ paddingBottom: 0 }}>
            <Callout tone="warn" icon={<Icon.Key />}>
              Missing: {missingKeys.map((p) => <code key={p.id} style={{ marginRight: 8 }}>{p.apiKeyEnv}</code>)} — e.g. <code>export {missingKeys[0].apiKeyEnv}=…</code> before <code>npm run serve</code>.
            </Callout>
          </div>
        )}
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Provider</th>
                <th>Type</th>
                <th>Base URL</th>
                <th>API key env var</th>
                <th>Key</th>
                <th className="num">Max concurrency</th>
                <th className="num" />
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <tr key={p.id}>
                  <td>
                    <strong>{p.label}</strong>
                    <div className="mono muted" style={{ fontSize: '0.74rem' }}>
                      {p.id}
                    </div>
                  </td>
                  <td>
                    <span className={cx('badge', p.type === 'manual' ? 'info' : 'outline')}>{p.type === 'manual' ? 'manual · copy & paste' : p.type}</span>
                  </td>
                  <td className="mono" style={{ fontSize: '0.78rem', overflowWrap: 'anywhere', maxWidth: 280 }}>
                    {p.baseUrl ?? <span className="muted">default</span>}
                  </td>
                  <td className="mono" style={{ fontSize: '0.8rem' }}>
                    {p.apiKeyEnv ?? <span className="muted">—</span>}
                  </td>
                  <td>
                    <KeyStatus p={p} />
                  </td>
                  <td className="num">{p.maxConcurrency ?? '—'}</td>
                  <td className="num">
                    {p.type === 'manual' ? (
                      <button className="btn xs" onClick={() => setEditing({ c: blank(p.id), isNew: true })}>
                        <Icon.Plus /> Add a chatbot
                      </button>
                    ) : p.type !== 'mock' ? (
                      <button className="btn xs" onClick={() => setDiscover(p)} disabled={!p.hasKey && !!p.apiKeyEnv} title={!p.hasKey && p.apiKeyEnv ? 'Set the API key first' : 'List available models'}>
                        <Icon.Search /> Discover models
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {editing && (
        <ContestantForm
          initial={editing.c}
          isNew={editing.isNew}
          providers={providers}
          discovered={discovered}
          usedColors={usedColors}
          onCancel={() => setEditing(null)}
          onSaved={(saved) => {
            list.setData((xs) => {
              const arr = xs ?? [];
              return arr.some((x) => x.id === saved.id) ? arr.map((x) => (x.id === saved.id ? saved : x)) : [...arr, saved];
            });
            setEditing(null);
          }}
        />
      )}
      {discover && (
        <DiscoverDrawer
          provider={discover}
          existing={new Set(cons.filter((c) => c.provider === discover.id).map((c) => c.model))}
          onClose={() => setDiscover(null)}
          onAdd={(model, all) => {
            setDiscovered((d) => ({ ...d, [discover.id]: all }));
            setEditing({ c: blank(discover.id, model), isNew: true });
            setDiscover(null);
          }}
        />
      )}
      <ConfirmDialog
        open={!!confirm}
        danger
        title={`Delete ${confirm?.label ?? 'model'}?`}
        confirmLabel="Delete model"
        onCancel={() => setConfirm(null)}
        onConfirm={remove}
        body="The contestant is removed from config. Past run results keep their snapshot, but it will no longer appear in New Run or the combined leaderboard."
      />
    </div>
  );
}
