/**
 * New Model Day — add a just-released model, check it works, see what each
 * suite costs, run under a spending cap, then get the headline and titles.
 * Nothing is spent without an explicit click that states the price.
 */
import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { MOCK, api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, pathOf } from '../router.tsx';
import { useMeta, useToast, useViewerCaption } from '../context.tsx';
import { Callout, ConfirmDialog, CopyButton, Field, PageHead, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost, fmtMs } from '../format.ts';
import { channelApi, type NewModelHeadline, type NewModelInput, type NewModelPrepared, type NewModelSuiteCost } from './channelApi.ts';
import './channel.css';

type Stage = 'model' | 'ping' | 'cost' | 'running' | 'done';
const ORDER: Stage[] = ['model', 'ping', 'cost', 'running', 'done'];

function Step({ n, title, stage, current, children, summary }: { n: number; title: string; stage: Stage; current: Stage; children?: ReactNode; summary?: ReactNode }) {
  const state = ORDER.indexOf(current) > ORDER.indexOf(stage) ? 'done' : current === stage ? 'active' : 'todo';
  return (
    <section className={cx('card ch-step', state)} aria-current={state === 'active' ? 'step' : undefined}>
      <header className="ch-step-head">
        <span className="ch-step-n">{state === 'done' ? <Icon.Check /> : n}</span>
        <h2>{title}</h2>
        {state === 'done' && summary && <span className="ch-step-sum">{summary}</span>}
      </header>
      {state === 'active' && <div className="card-body">{children}</div>}
    </section>
  );
}

const today = () => new Date().toISOString().slice(0, 10);
const num = (s: string) => (s.trim() === '' || !Number.isFinite(Number(s)) ? undefined : Number(s));

export default function NewModelPage() {
  const { meta } = useMeta();
  const toast = useToast();
  const providers = (meta?.providers ?? []).filter((p) => p.type !== 'mock');
  const [stage, setStage] = useState<Stage>('model');
  const [form, setForm] = useState({ provider: 'openai', model: '', label: '', input: '', output: '', verified: false, family: '', tier: '', releaseDate: today() });
  const [prep, setPrep] = useState<NewModelPrepared | null>(null);
  const [busy, setBusy] = useState(false);
  const [ping, setPing] = useState<{ ok: boolean; text?: string; totalMs?: number; costUsd?: number; error?: string } | null>(null);
  const [costs, setCosts] = useState<NewModelSuiteCost[] | null>(null);
  const [chosen, setChosen] = useState<string[]>(['quick']);
  const [cap, setCap] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [runs, setRuns] = useState<Array<{ suiteId: string; runId: string; status: string; spent: number }>>([]);
  const [headlines, setHeadlines] = useState<NewModelHeadline[]>([]);
  const discovered = useAsync<string[]>(() => (form.provider && providers.find((p) => p.id === form.provider)?.hasKey ? api.providerModels(form.provider).then((r) => r.models).catch(() => []) : Promise.resolve([])), [form.provider, providers.length]);

  useEffect(() => {
    if (providers.length && !providers.some((p) => p.id === form.provider)) setForm((f) => ({ ...f, provider: providers[0]!.id }));
  }, [providers, form.provider]);

  const plan = useMemo(() => {
    const sel = (costs ?? []).filter((c) => chosen.includes(c.suiteId));
    const est = sel.reduce((s, c) => s + c.estimate.estCostUsd, 0);
    const high = sel.reduce((s, c) => s + c.estimate.estCostUsdHigh, 0);
    return { sel, est, high, defaultCap: Math.max(0.05, Math.ceil(high * 1.1 * 100) / 100) };
  }, [costs, chosen]);
  const capUsd = num(cap) ?? plan.defaultCap;

  useViewerCaption(
    stage === 'done' && headlines[0] ? headlines[0].headline : `New model day: adding ${form.label || 'a brand-new model'} to the benchmark, checking it answers, and pricing the test suites before spending anything.`,
    stage === 'done' ? 'Rank is on the combined leaderboard: every model, same tests' : undefined,
  );

  const submitModel = async () => {
    const input: NewModelInput = {
      provider: form.provider,
      model: form.model.trim(),
      label: form.label.trim() || undefined,
      inputPerM: num(form.input),
      outputPerM: num(form.output),
      pricesVerified: form.verified,
      family: form.family.trim() || undefined,
      tier: (form.tier || undefined) as NewModelInput['tier'],
      releaseDate: form.releaseDate || undefined,
    };
    setBusy(true);
    try {
      const p = await channelApi.prepareModel(input);
      setPrep(p);
      setStage('ping');
    } catch (e) {
      toast.error(e, 'Could not add the model');
    } finally {
      setBusy(false);
    }
  };

  const doPing = async () => {
    if (!prep) return;
    setBusy(true);
    try {
      const r = await channelApi.ping(prep.contestant.id);
      setPing(r);
      if (r.ok) {
        const c = await channelApi.modelCosts(prep.contestant.id);
        setCosts(c);
        setStage('cost');
      }
    } catch (e) {
      setPing({ ok: false, error: (e as Error).message });
    } finally {
      setBusy(false);
    }
  };

  const startRuns = async () => {
    if (!prep) return;
    setConfirming(false);
    setBusy(true);
    try {
      const started: typeof runs = [];
      for (const s of plan.sel) {
        // Split the cap across suites in proportion to their estimates, so the total can never exceed it.
        const share = plan.est > 0 ? s.estimate.estCostUsd / plan.est : 1 / plan.sel.length;
        const { runId } = await channelApi.startRun({ suiteId: s.suiteId, contestantIds: [prep.contestant.id], maxCostUsd: Math.max(0.01, +(capUsd * share).toFixed(2)), name: `New model day: ${prep.contestant.label} (${s.suiteId})` });
        started.push({ suiteId: s.suiteId, runId, status: 'running', spent: 0 });
      }
      setRuns(started);
      setStage('running');
    } catch (e) {
      toast.error(e, 'Could not start the run');
    } finally {
      setBusy(false);
    }
  };

  // Poll the runs until they finish, then fetch the headlines.
  useEffect(() => {
    if (stage !== 'running' || !prep) return;
    let alive = true;
    const tick = async () => {
      const next = await Promise.all(
        runs.map(async (r) => {
          if (MOCK) return { ...r, status: 'completed', spent: 0.63 };
          try {
            const d = await api.run(r.runId);
            return { ...r, status: d.active ? 'running' : d.manifest.status, spent: d.progress.costUsd };
          } catch {
            return r;
          }
        }),
      );
      if (!alive) return;
      setRuns(next);
      if (next.every((r) => r.status !== 'running' && r.status !== 'queued')) {
        const hs = await Promise.all(next.map((r) => channelApi.headline(prep.contestant.id, r.suiteId)));
        if (!alive) return;
        setHeadlines(hs);
        setStage('done');
      } else timer = window.setTimeout(tick, 3000);
    };
    let timer = window.setTimeout(tick, MOCK ? 1200 : 1500);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stage]);

  const c = prep?.contestant;
  return (
    <div className="page ch-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Sparkles style={{ width: 14, height: 14 }} /> Channel
          </span>
        }
        title="New Model Day"
        sub="A new model just dropped. Add it, check it works, see the price, run it and find out where it ranks: in five steps."
        actions={
          stage !== 'model' && (
            <button
              className="btn ghost"
              onClick={() => {
                setStage('model');
                setPrep(null);
                setPing(null);
                setCosts(null);
                setRuns([]);
                setHeadlines([]);
              }}
            >
              <Icon.Refresh /> Start over
            </button>
          )
        }
      />

      <div className="ch-steps">
        <Step n={1} title="Add the model" stage="model" current={stage} summary={c && <>{c.label} · <code>{c.model}</code> · ${c.pricing.inputPerM} / ${c.pricing.outputPerM} per 1M tokens{c.pricing.verifiedAt ? '' : ' · price unverified'}</>}>
          <div className="form-grid ch-form">
            <Field label="Provider">
              <select className="select" value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value })}>
                {providers.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                    {p.apiKeyEnv && !p.hasKey ? ' (no API key)' : ''}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Model id" hint={discovered.data?.length ? `${discovered.data.length} models found for your key: start typing to pick one.` : 'Exactly as the API expects it, e.g. gpt-6'}>
              <input className="input mono" list="nm-models" value={form.model} placeholder="gpt-6" onChange={(e) => setForm({ ...form, model: e.target.value })} />
              <datalist id="nm-models">
                {(discovered.data ?? []).map((m) => (
                  <option key={m} value={m} />
                ))}
              </datalist>
            </Field>
            <Field label="Display name" hint="How it appears on screen.">
              <input className="input" value={form.label} placeholder="GPT-6" onChange={(e) => setForm({ ...form, label: e.target.value })} />
            </Field>
            <Field label="Input price" hint="USD per 1M input tokens (0 for free/local).">
              <input className="input" inputMode="decimal" value={form.input} placeholder="5" onChange={(e) => setForm({ ...form, input: e.target.value })} />
            </Field>
            <Field label="Output price" hint="USD per 1M output tokens.">
              <input className="input" inputMode="decimal" value={form.output} placeholder="20" onChange={(e) => setForm({ ...form, output: e.target.value })} />
            </Field>
            <Field label="Release date" hint="For the History chart.">
              <input className="input" type="date" value={form.releaseDate} onChange={(e) => setForm({ ...form, releaseDate: e.target.value })} />
            </Field>
            <Field label="Family" hint="Optional: guessed from the name (e.g. GPT).">
              <input className="input" value={form.family} placeholder="auto" onChange={(e) => setForm({ ...form, family: e.target.value })} />
            </Field>
            <Field label="Tier" hint="Optional: guessed from the name.">
              <select className="select" value={form.tier} onChange={(e) => setForm({ ...form, tier: e.target.value })}>
                <option value="">auto</option>
                <option value="flagship">flagship</option>
                <option value="mid">mid</option>
                <option value="small">small</option>
              </select>
            </Field>
          </div>
          <label className="row ch-check">
            <input type="checkbox" checked={form.verified} onChange={(e) => setForm({ ...form, verified: e.target.checked })} />
            I checked these prices on the provider’s official price page today
          </label>
          <div className="row" style={{ gap: 10, marginTop: 14 }}>
            <button className="btn primary" disabled={busy || !form.model.trim() || num(form.input) === undefined || num(form.output) === undefined} onClick={() => void submitModel()}>
              <Icon.Plus /> {busy ? 'Adding…' : 'Add model'}
            </button>
            <span className="muted small">Free: this only saves the model to config/models.json and checks the id against your key’s model list.</span>
          </div>
        </Step>

        <Step n={2} title="Check it works" stage="ping" current={stage} summary={ping?.ok && <>answered “{ping.text?.trim().slice(0, 20)}” in {fmtMs(ping.totalMs)} · {fmtCost(ping.costUsd)}</>}>
          {prep && (
            <div className="stack">
              {prep.discovered === true && <Callout tone="info" icon={<Icon.Check />}><code>{prep.contestant.model}</code> is in your provider’s model list.</Callout>}
              {prep.discovered === false && (
                <Callout tone="warn">
                  <code>{prep.contestant.model}</code> is not in your provider’s model list. {prep.suggestions.length > 0 && <>Did you mean: {prep.suggestions.map((s) => <code key={s}>{s} </code>)}?</>}
                </Callout>
              )}
              {prep.discovered === null && prep.discoverError && <Callout tone="plain">Couldn’t check the model list: {prep.discoverError}</Callout>}
              {prep.warnings.map((w) => (
                <Callout key={w} tone="warn">
                  {w}
                </Callout>
              ))}
              {ping && !ping.ok && (
                <Callout tone="bad">
                  <strong>No answer:</strong> {ping.error}
                </Callout>
              )}
              <div className="row" style={{ gap: 10 }}>
                <button className="btn primary" disabled={busy} onClick={() => void doPing()}>
                  <Icon.Zap /> {busy ? 'Waiting for the model…' : 'Send a one-word test message (costs < $0.01)'}
                </button>
              </div>
            </div>
          )}
        </Step>

        <Step n={3} title="What will it cost?" stage="cost" current={stage} summary={plan.sel.length > 0 && <>{chosen.join(' + ')} · est. {fmtCost(plan.est)} · cap {fmtCost(capUsd)}</>}>
          {costs && (
            <div className="stack">
              <div className="ch-costs">
                {costs.map((s) => (
                  <button key={s.suiteId} type="button" className={cx('ch-cost', chosen.includes(s.suiteId) && 'on')} aria-pressed={chosen.includes(s.suiteId)} onClick={() => setChosen((x) => (x.includes(s.suiteId) ? x.filter((y) => y !== s.suiteId) : [...x, s.suiteId]))}>
                    <span className="eyebrow">{s.name}</span>
                    <span className="v">{fmtCost(s.estimate.estCostUsd)}</span>
                    <span className="s">up to {fmtCost(s.estimate.estCostUsdHigh)}</span>
                    <span className="s">
                      {s.tests} tests · {s.cases} cases
                    </span>
                    <span className="tick">{chosen.includes(s.suiteId) ? <Icon.Check /> : null}</span>
                  </button>
                ))}
              </div>
              <div className="row wrap" style={{ gap: 14, alignItems: 'flex-end' }}>
                <Field label="Spending cap (USD)" hint="The runs stop starting new cases when this is reached.">
                  <input className="input" inputMode="decimal" value={cap} placeholder={plan.defaultCap.toFixed(2)} onChange={(e) => setCap(e.target.value)} style={{ maxWidth: 160 }} />
                </Field>
                <button className="btn primary lg" style={{ marginBottom: 22 }} disabled={busy || plan.sel.length === 0 || !(capUsd > 0)} onClick={() => setConfirming(true)}>
                  <Icon.Rocket /> Run {chosen.join(' + ') || '…'}
                </button>
              </div>
              {[...new Set(plan.sel.flatMap((s) => s.estimate.warnings))].map((w) => (
                <Callout key={w} tone="warn">
                  {w}
                </Callout>
              ))}
            </div>
          )}
        </Step>

        <Step n={4} title="Running" stage="running" current={stage} summary={runs.length > 0 && <>{runs.map((r) => `${r.suiteId}: ${r.status}`).join(' · ')} · spent {fmtCost(runs.reduce((s, r) => s + r.spent, 0))}</>}>
          <div className="stack">
            {runs.map((r) => (
              <div key={r.runId} className="row ch-run">
                <span className="status-dot ok" />
                <strong>{r.suiteId}</strong>
                <span className="muted">{r.status}</span>
                <span className="muted">spent {fmtCost(r.spent)}</span>
                <span className="spacer" />
                <Link to={pathOf('runs', r.runId, 'live')} className="btn sm">
                  <Icon.Broadcast /> Watch live
                </Link>
              </div>
            ))}
            <p className="muted small">You can leave this page: the runs continue on the server. Come back to see the headline.</p>
          </div>
        </Step>

        <Step n={5} title="The verdict" stage="done" current={stage}>
          <div className="stack loose">
            {headlines.map((h) => (
              <div key={h.suiteId} className="ch-verdict">
                <div className="ch-verdict-main">
                  <span className="eyebrow">{h.suiteName}</span>
                  <div className="ch-rank">{h.rank ? `#${h.rank}` : '—'}<small>{h.rank ? ` of ${h.of}` : ''}</small></div>
                  <div className="ch-idx">{h.index?.toFixed(1) ?? '—'} <small>Gauntlet Index{h.indexCi95 ? ` (95% CI ${h.indexCi95[0].toFixed(1)}–${h.indexCi95[1].toFixed(1)})` : ''}</small></div>
                  <p className="ch-headline">{h.headline}</p>
                  {h.bestCategory && (
                    <p className="muted">
                      Best at <strong>{h.bestCategory.name}</strong> ({Math.round(h.bestCategory.score * 100)}){h.worstCategory && <>, weakest at <strong>{h.worstCategory.name}</strong> ({Math.round(h.worstCategory.score * 100)})</>}.
                    </p>
                  )}
                </div>
                <ol className="ch-ladder">
                  {h.above.map((a) => (
                    <li key={a.label}>
                      <span>{a.label}</span>
                      <b>{a.index?.toFixed(1)}</b>
                    </li>
                  ))}
                  <li className="me">
                    <span>{h.label}</span>
                    <b>{h.index?.toFixed(1)}</b>
                  </li>
                  {h.below.map((a) => (
                    <li key={a.label}>
                      <span>{a.label}</span>
                      <b>{a.index?.toFixed(1)}</b>
                    </li>
                  ))}
                </ol>
                {h.titles.length > 0 && (
                  <div className="ch-titles">
                    <span className="eyebrow">Video title ideas</span>
                    {h.titles.map((t) => (
                      <div key={t} className="row">
                        <span className="spacer">{t}</span>
                        <CopyButton text={t} iconOnly label="Copy title" />
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
            <div className="row" style={{ gap: 10 }}>
              <Link to="/" className="btn">
                <Icon.Trophy /> Leaderboard
              </Link>
              {runs[0] && (
                <Link to={pathOf('present', runs[0].runId)} className="btn">
                  <Icon.Present /> Presenter
                </Link>
              )}
              <Link to="/history" className="btn">
                <Icon.Chart /> History
              </Link>
            </div>
          </div>
        </Step>
      </div>

      <ConfirmDialog
        open={confirming}
        title="Start spending?"
        confirmLabel={`Run and spend up to ${fmtCost(capUsd)}`}
        onCancel={() => setConfirming(false)}
        onConfirm={() => void startRuns()}
        busy={busy}
        body={
          <div className="stack">
            <p>
              <strong>{c?.label}</strong> will run {plan.sel.map((s) => s.name).join(' and ')}.
            </p>
            <p>
              Estimated cost <strong>{fmtCost(plan.est)}</strong> (conservative upper bound {fmtCost(plan.high)}). Hard spending cap: <strong>{fmtCost(capUsd)}</strong>.
            </p>
            {c && !c.pricing.verifiedAt && <Callout tone="warn">The price is unverified, so the real bill may differ.</Callout>}
          </div>
        }
      />
    </div>
  );
}
