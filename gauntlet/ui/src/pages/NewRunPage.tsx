import { useEffect, useMemo, useState } from 'react';
import { api } from '../api.ts';
import { useAsync, useDebounced } from '../hooks.ts';
import { Link, navigate, pathOf, useRoute } from '../router.tsx';
import { useMeta, useToast, useViewerCaption } from '../context.tsx';
import { Callout, DifficultyBadge, Empty, ErrorState, Field, HashTag, LoadingPage, PageHead, Seg, Skeleton, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { fmtCost, fmtInt, fmtPricePerM } from '../format.ts';
import type { ContestantView, RunEstimate, RunRequest, SuiteView, TestSummary } from '../types.ts';
import { isBaseline } from '../components/leaderboard/util.ts';

type Mode = 'suite' | 'pick';

const isManual = (c: ContestantView) => c.providerType === 'manual';

function TriCheck({ checked, indeterminate, onChange, label }: { checked: boolean; indeterminate: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <input
      type="checkbox"
      aria-label={label}
      checked={checked}
      ref={(el) => {
        if (el) el.indeterminate = indeterminate;
      }}
      onChange={(e) => onChange(e.target.checked)}
    />
  );
}

function EstimatePanel({
  est,
  loading,
  error,
  contestants,
  cap,
  canStart,
  starting,
  onStart,
  blockers,
}: {
  est: RunEstimate | null;
  loading: boolean;
  error: Error | null;
  contestants: ContestantView[];
  cap: number | null;
  canStart: boolean;
  starting: boolean;
  onStart: () => void;
  blockers: string[];
}) {
  const byId = useMemo(() => new Map(contestants.map((c) => [c.id, c])), [contestants]);
  const maxHigh = Math.max(1e-9, ...(est?.perContestant ?? []).map((p) => p.estCostUsdHigh));
  const measured = est ? est.perTest.filter((t) => t.basis !== 'definition').length : 0;
  return (
    <aside className="card estimate-panel" aria-live="polite" aria-busy={loading}>
      <div className="card-head">
        <div className="t">
          <h2>Cost estimate</h2>
          <div className="desc">Updates as you change the plan.</div>
        </div>
        {loading && <span className="spinner" aria-label="Updating estimate" />}
      </div>
      <div className="card-body stack">
        {error && !est ? (
          <Callout tone="bad">{error.message}</Callout>
        ) : !est ? (
          <div className="stack tight">
            <Skeleton h={54} />
            <Skeleton h={18} w="60%" />
            <Skeleton h={120} />
          </div>
        ) : (
          <div className={cx('stack', loading && 'refetching')}>
            <div className="est-hero">
              <span className="est-range tnum">
                {fmtCost(est.estCostUsd)}
                <span className="est-dash">–</span>
                {fmtCost(est.estCostUsdHigh)}
              </span>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                central estimate – conservative upper bound · incl. {fmtCost(est.judgeCostUsd)} judges
              </span>
            </div>
            <div className="est-kpis">
              <div>
                <b className="tnum">{fmtInt(est.jobs)}</b>
                <span>jobs</span>
              </div>
              <div>
                <b className="tnum">{fmtInt(est.calls)}</b>
                <span>API calls</span>
              </div>
              <div>
                <b className="tnum">{cap ? fmtCost(cap) : '—'}</b>
                <span>spending cap</span>
              </div>
            </div>
            {cap !== null && cap < est.estCostUsd && (
              <Callout tone="warn">
                The cap is below the central estimate — the run will probably stop early (status <em>cancelled</em>). You can resume later with a higher cap.
              </Callout>
            )}
            <div className="est-rows">
              {est.perContestant.map((p) => {
                const c = byId.get(p.contestantId);
                return (
                  <div key={p.contestantId} className="est-row">
                    <div className="row" style={{ gap: 8, minWidth: 0 }}>
                      <span className="sw" style={{ background: c?.color ?? 'var(--text-3)' }} />
                      <span className="ellipsis" style={{ fontWeight: 600 }}>
                        {c?.label ?? p.contestantId}
                      </span>
                      <span className="spacer" />
                      <span className="tnum muted" style={{ fontSize: '0.78rem' }}>
                        {fmtInt(p.jobs)} jobs
                      </span>
                    </div>
                    {p.manual ? (
                      <div className="est-manual">
                        <Icon.Copy /> You paste the replies · no API cost
                      </div>
                    ) : (
                      <div className="est-bar">
                        <div className="rng">
                          <span className="lo" style={{ width: `${(p.estCostUsd / maxHigh) * 100}%`, background: c?.color }} />
                          <span className="hi" style={{ width: `${(p.estCostUsdHigh / maxHigh) * 100}%`, borderColor: c?.color }} />
                        </div>
                        <span className="tnum">
                          {fmtCost(p.estCostUsd)} – {fmtCost(p.estCostUsdHigh)}
                        </span>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            {est.perTest.length > 0 && (
              <details className="collapse">
                <summary>
                  Per-test breakdown · {est.perTest.length} tests · {measured} measured
                </summary>
                <div className="inner">
                  <div className="table-wrap" style={{ maxHeight: 320 }}>
                    <table className="table compact">
                      <thead>
                        <tr>
                          <th>Test</th>
                          <th className="num">Cases</th>
                          <th className="num">Models</th>
                          <th className="num">Judges</th>
                        </tr>
                      </thead>
                      <tbody>
                        {est.perTest.map((t) => {
                          const total = Object.values(t.perContestant).reduce((s, v) => s + v, 0);
                          return (
                            <tr key={t.testId}>
                              <td>
                                <span className="ellipsis" style={{ display: 'block', maxWidth: 190 }} title={t.name}>
                                  {t.name}
                                </span>
                                <span className={cx('basis', t.basis !== 'definition' && 'measured')} title={t.basis === 'measured' ? 'Measured from previous runs of this test version' : t.basis === 'measured-other-models' ? 'Measured on other models' : 'From the test definition (no runs yet)'}>
                                  {t.basis === 'definition' ? 'from definition' : t.basis === 'measured' ? 'measured ✓' : 'measured (other models) ✓'}
                                </span>
                              </td>
                              <td className="num">{t.cases}</td>
                              <td className="num">{fmtCost(total)}</td>
                              <td className="num">{t.judgeUsd ? fmtCost(t.judgeUsd) : '—'}</td>
                            </tr>
                          );
                        })}
                      </tbody>
                    </table>
                  </div>
                </div>
              </details>
            )}
            <div className="row" style={{ gap: 8, fontSize: '0.8rem' }}>
              <Icon.Fingerprint style={{ width: 14, height: 14, color: 'var(--text-3)' }} />
              <span className="muted">Fingerprint</span>
              <HashTag value={est.fingerprint} label="Fingerprint" n={12} />
            </div>
            {est.warnings.length > 0 && (
              <ul className="warn-list">
                {est.warnings.map((w, i) => (
                  <li key={i}>
                    <Icon.Alert /> {w}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        {blockers.length > 0 && (
          <ul className="warn-list muted-list">
            {blockers.map((b) => (
              <li key={b}>
                <Icon.Info /> {b}
              </li>
            ))}
          </ul>
        )}
        <button className="btn primary lg block" disabled={!canStart || starting} onClick={onStart}>
          <Icon.Rocket /> {starting ? 'Starting…' : 'Start run'}
        </button>
        <Link to="/costs" className="muted" style={{ fontSize: '0.8rem', textAlign: 'center' }}>
          Compare suites and models in the Cost Planner →
        </Link>
      </div>
    </aside>
  );
}

export default function NewRunPage() {
  useViewerCaption('Setting up a new run: pick the tests and the models, see the estimated cost, then press start.', 'Estimates use real token usage from earlier runs where available');
  const { query } = useRoute();
  const { meta, cat, categories } = useMeta();
  const toast = useToast();
  const data = useAsync(() => Promise.all([api.contestants(), api.tests(), api.suites()]), []);
  const [contestants, tests, suites] = data.data ?? [[], [], []] as [ContestantView[], TestSummary[], SuiteView[]];

  const presetTests = query.get('tests');
  const [mode, setMode] = useState<Mode>(presetTests ? 'pick' : 'suite');
  const [suiteId, setSuiteId] = useState(query.get('suite') ?? 'core');
  // Fall back to the first suite if the requested/default one doesn't exist on this server.
  useEffect(() => {
    if (suites.length && !suites.some((s) => s.id === suiteId)) setSuiteId(suites.some((s) => s.id === 'core') ? 'core' : suites[0].id);
  }, [suites, suiteId]);
  const [picked, setPicked] = useState<Set<string>>(() => new Set(presetTests ? presetTests.split(',').filter(Boolean) : []));
  const [selected, setSelected] = useState<Set<string> | null>(null);
  const [repeats, setRepeats] = useState<number | null>(null);
  const [repeatsTouched, setRepeatsTouched] = useState(false);
  const [concurrency, setConcurrency] = useState<number | null>(null);
  const [temperature, setTemperature] = useState<number | null>(null);
  const [judges, setJudges] = useState<string[] | null>(null);
  const [name, setName] = useState('');
  const [notes, setNotes] = useState('');
  const [capText, setCapText] = useState('');
  const [capTouched, setCapTouched] = useState(false);
  const [search, setSearch] = useState('');
  const [starting, setStarting] = useState(false);
  const [forceVision, setForceVision] = useState(false);

  const enabled = useMemo(() => contestants.filter((c) => c.enabled), [contestants]);
  const suite = suites.find((s) => s.id === suiteId);

  // Defaults once data arrives.
  useEffect(() => {
    if (!data.data) return;
    setSelected((s) => s ?? new Set(enabled.filter((c) => (c.hasKey || isManual(c)) && !isManual(c)).map((c) => c.id)));
  }, [data.data, enabled]);
  useEffect(() => {
    if (meta) {
      setConcurrency((v) => v ?? meta.settings.defaultConcurrency);
      setTemperature((v) => v ?? meta.settings.temperature);
      setJudges((v) => v ?? meta.settings.judges);
    }
  }, [meta]);
  // Follow the suite's default repeats until the user picks a value.
  useEffect(() => {
    if (!data.data || repeatsTouched) return;
    setRepeats(mode === 'suite' ? suite?.repeats ?? meta?.settings.defaultRepeats ?? 1 : meta?.settings.defaultRepeats ?? 1);
  }, [data.data, suite, meta, mode, repeatsTouched]);

  const cap = capText.trim() === '' ? null : Number(capText);
  const capValid = cap === null || (Number.isFinite(cap) && cap > 0);

  const request: RunRequest | null = useMemo(() => {
    if (!selected) return null;
    const ids = [...selected].filter((id) => enabled.some((c) => c.id === id));
    return {
      name: name.trim() || undefined,
      suiteId: mode === 'suite' ? suiteId : undefined,
      testIds: mode === 'pick' ? [...picked] : undefined,
      contestantIds: ids,
      repeats: repeats ?? 1,
      concurrency: concurrency ?? undefined,
      temperature: temperature ?? undefined,
      judgeIds: judges ?? undefined,
      maxCostUsd: cap !== null && capValid ? cap : undefined,
      notes: notes.trim() || undefined,
      forceVision: forceVision || undefined,
    };
  }, [selected, enabled, name, mode, suiteId, picked, repeats, concurrency, temperature, judges, cap, capValid, notes, forceVision]);

  // Estimate ignores name/notes/cap so typing there does not refetch.
  const estKey = request ? JSON.stringify({ ...request, name: undefined, notes: undefined, maxCostUsd: undefined }) : '';
  const debouncedKey = useDebounced(estKey, 400);
  const [est, setEst] = useState<RunEstimate | null>(null);
  const [estErr, setEstErr] = useState<Error | null>(null);
  const [estLoading, setEstLoading] = useState(false);
  useEffect(() => {
    if (!debouncedKey) return;
    const req = JSON.parse(debouncedKey) as RunRequest;
    if (!req.contestantIds.length || (req.testIds && !req.testIds.length)) {
      setEst(null);
      return;
    }
    let alive = true;
    setEstLoading(true);
    api
      .estimate(req)
      .then((e) => {
        if (!alive) return;
        setEst(e);
        setEstErr(null);
      })
      .catch((e: unknown) => alive && setEstErr(e instanceof Error ? e : new Error(String(e))))
      .finally(() => alive && setEstLoading(false));
    return () => {
      alive = false;
    };
  }, [debouncedKey]);

  // Prefill the spending cap at ~1.25× the estimate until the user edits it.
  useEffect(() => {
    if (!capTouched && est && est.estCostUsd > 0) {
      const v = est.estCostUsd * 1.25;
      setCapText(v >= 10 ? String(Math.ceil(v)) : v >= 1 ? v.toFixed(2) : v.toFixed(3));
    }
  }, [est, capTouched]);

  const groups = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const order = new Map(categories.map((c, i) => [c.id, i]));
    const m = new Map<string, TestSummary[]>();
    for (const t of tests) {
      if (needle && !`${t.name} ${t.id} ${t.tags.join(' ')}`.toLowerCase().includes(needle)) continue;
      const arr = m.get(t.category) ?? [];
      arr.push(t);
      m.set(t.category, arr);
    }
    return [...m.entries()].sort((a, b) => (order.get(a[0]) ?? 99) - (order.get(b[0]) ?? 99));
  }, [tests, search, categories]);

  if (data.loading && !data.data) return <LoadingPage />;
  if (data.error && !data.data) return <ErrorState error={data.error} onRetry={data.reload} title="Couldn’t load models and tests" />;

  const sel = selected ?? new Set<string>();
  const selectedCons = enabled.filter((c) => sel.has(c.id));
  const blockers: string[] = [];
  if (!selectedCons.length) blockers.push('Pick at least one contestant.');
  if (mode === 'pick' && picked.size === 0) blockers.push('Pick at least one test (or switch to a suite).');
  if (!capValid) blockers.push('The spending cap must be a positive number (or empty for no cap).');
  const canStart = blockers.length === 0 && !!request;
  const noKeys = enabled.length > 0 && enabled.every((c) => !c.hasKey && !isManual(c) && !isBaseline({ contestantId: c.id, vendor: c.vendor, label: c.label }));

  const start = async () => {
    if (!request) return;
    setStarting(true);
    try {
      const { runId } = await api.startRun(request);
      toast.success('Run started — opening the Live Arena.');
      navigate(pathOf('runs', runId, 'live'));
    } catch (e) {
      toast.error(e, 'Could not start the run');
    } finally {
      setStarting(false);
    }
  };

  const toggleCon = (id: string) =>
    setSelected((s) => {
      const n = new Set(s ?? []);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });

  const setPick = (ids: string[], on: boolean) =>
    setPicked((p) => {
      const n = new Set(p);
      for (const id of ids) {
        if (on) n.add(id);
        else n.delete(id);
      }
      return n;
    });

  return (
    <div className="page">
      <PageHead eyebrow="Plan" title="New run" sub="Every model receives byte-identical prompts. Results are hashed against the exact test versions and model configs you pick here." />

      {enabled.length === 0 ? (
        <div className="card">
          <Empty
            icon={<Icon.Cpu />}
            title="No enabled models"
            actions={
              <Link to="/models" className="btn primary">
                <Icon.Cpu /> Set up models
              </Link>
            }
          >
            Add or enable at least one contestant on the Models page first.
          </Empty>
        </div>
      ) : (
        <div className="grid split-8-4 newrun">
          <div className="stack loose">
            {noKeys && (
              <Callout tone="warn" icon={<Icon.Key />}>
                <strong>No API keys detected.</strong> Set the provider environment variables and restart the server — see <Link to="/models">Models</Link>. Manual (copy &amp; paste) models still work.
              </Callout>
            )}

            {/* Tests */}
            <section className="card">
              <div className="card-head">
                <div className="t">
                  <h2>1 · Tests</h2>
                  <div className="desc">Run a whole suite, or hand-pick tests.</div>
                </div>
                <div className="tools">
                  <Seg
                    label="Test selection"
                    value={mode}
                    onChange={setMode}
                    options={[
                      { value: 'suite', label: 'Suite' },
                      { value: 'pick', label: `Hand-pick${picked.size ? ` (${picked.size})` : ''}` },
                    ]}
                  />
                </div>
              </div>
              <div className="card-body">
                {mode === 'suite' ? (
                  <div className="suite-grid" role="radiogroup" aria-label="Suite">
                    {suites.map((s) => (
                      <button key={s.id} type="button" role="radio" aria-checked={s.id === suiteId} className={cx('suite-card', s.id === suiteId && 'on')} onClick={() => setSuiteId(s.id)}>
                        <div className="row between">
                          <strong>{s.name}</strong>
                          <span className="badge outline">v{s.version}</span>
                        </div>
                        <p>{s.description}</p>
                        <div className="row wrap" style={{ gap: 6 }}>
                          <span className="badge">{s.testCount} tests</span>
                          {s.repeats ? <span className="badge">{s.repeats}× repeats</span> : null}
                          <span className="hash">{s.fingerprint.slice(0, 8)}</span>
                        </div>
                      </button>
                    ))}
                    {suites.length === 0 && <div className="muted">No suites defined.</div>}
                  </div>
                ) : (
                  <div className="stack">
                    <div className="row wrap">
                      <div className="search" style={{ flex: 1, minWidth: 200 }}>
                        <Icon.Search />
                        <input className="input" placeholder="Search tests by name, id or tag…" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search tests" />
                      </div>
                      <button type="button" className="btn sm ghost" onClick={() => setPicked(new Set())} disabled={!picked.size}>
                        Clear
                      </button>
                    </div>
                    <div className="picker">
                      {groups.map(([catId, ts]) => {
                        const info = cat(catId);
                        const on = ts.filter((t) => picked.has(t.id)).length;
                        return (
                          <fieldset key={catId} className="pick-group">
                            <legend>
                              <label className="check">
                                <TriCheck checked={on === ts.length} indeterminate={on > 0 && on < ts.length} onChange={(v) => setPick(ts.map((t) => t.id), v)} label={`Select all ${info.name}`} />
                                <span className="cat-dot" style={{ background: info.color }} />
                                <strong>{info.name}</strong>
                                <span className="muted">
                                  {on}/{ts.length}
                                </span>
                              </label>
                            </legend>
                            {ts.map((t) => (
                              <label key={t.id} className={cx('pick-row', picked.has(t.id) && 'on')}>
                                <input type="checkbox" checked={picked.has(t.id)} onChange={(e) => setPick([t.id], e.target.checked)} />
                                <span className="pick-name">
                                  <span>{t.name}</span>
                                  <span className="mono muted">{t.id}</span>
                                </span>
                                {t.source === 'private' && <span className="badge accent">held-out</span>}
                                <span className="badge outline">{t.kind === 'program' ? 'simulation' : t.scorerType}</span>
                                <DifficultyBadge difficulty={t.difficulty} />
                                <span className="muted tnum" style={{ fontSize: '0.8rem', minWidth: 58, textAlign: 'right' }}>
                                  {t.caseCount} case{t.caseCount === 1 ? '' : 's'}
                                </span>
                              </label>
                            ))}
                          </fieldset>
                        );
                      })}
                      {groups.length === 0 && <div className="muted">No tests match “{search}”.</div>}
                    </div>
                  </div>
                )}
              </div>
            </section>

            {/* Contestants */}
            <section className="card">
              <div className="card-head">
                <div className="t">
                  <h2>2 · Contestants</h2>
                  <div className="desc">
                    {sel.size} of {enabled.length} enabled models selected.
                  </div>
                </div>
                <div className="tools">
                  <button className="btn sm ghost" onClick={() => setSelected(new Set(enabled.map((c) => c.id)))}>
                    All
                  </button>
                  <button className="btn sm ghost" onClick={() => setSelected(new Set())}>
                    None
                  </button>
                </div>
              </div>
              <div className="card-body">
                {enabled.some((c) => !c.hasKey && !isManual(c) && c.providerType !== 'mock') && (
                  <div style={{ marginBottom: 14 }}>
                    <Callout tone="warn" icon={<Icon.Key />}>
                      {enabled.filter((c) => !c.hasKey && !isManual(c) && c.providerType !== 'mock').length} models can’t run yet because their company isn’t connected.{' '}
                      <Link to="/keys">Add API keys</Link> (paste, save, done: no files to edit).
                    </Callout>
                  </div>
                )}
                <div className="con-grid">
                  {enabled.map((c) => {
                    const on = sel.has(c.id);
                    const manual = isManual(c);
                    return (
                      <label key={c.id} className={cx('con-card', on && 'on')} style={{ ['--c' as string]: c.color }}>
                        <input type="checkbox" checked={on} onChange={() => toggleCon(c.id)} />
                        <div className="con-main">
                          <div className="row" style={{ gap: 8 }}>
                            <strong className="ellipsis">{c.label}</strong>
                          </div>
                          <div className="muted ellipsis" style={{ fontSize: '0.78rem' }}>
                            {c.vendor}
                            {c.providerLabel && c.providerLabel !== c.vendor ? ` · ${c.providerLabel}` : ''}
                          </div>
                          <div className="mono muted ellipsis" style={{ fontSize: '0.72rem' }}>
                            {c.model}
                          </div>
                          <div className="row wrap" style={{ gap: 5, marginTop: 4 }}>
                            {manual ? (
                              <span className="badge info">
                                <Icon.Copy /> manual · copy &amp; paste
                              </span>
                            ) : (
                              <span className="badge outline tnum" title="USD per 1M input / output tokens">
                                {fmtPricePerM(c.pricing?.inputPerM)} / {fmtPricePerM(c.pricing?.outputPerM)}
                              </span>
                            )}
                            {!c.hasKey && !manual && (
                              <span className="badge bad" title="The provider's API key environment variable is not set">
                                <Icon.Key /> no API key
                              </span>
                            )}
                            {!manual && !c.pricing?.verifiedAt && (
                              <span className="badge warn" title="Pricing has not been verified by a human — cost figures may be wrong">
                                <Icon.Alert /> unverified pricing
                              </span>
                            )}
                          </div>
                        </div>
                      </label>
                    );
                  })}
                </div>
                {selectedCons.some(isManual) && (
                  <div style={{ marginTop: 12 }}>
                    <Callout tone="info" icon={<Icon.Inbox />}>
                      Manual models wait for you: each prompt appears in the <Link to="/inbox">Manual Inbox</Link> to copy into the chat app, and you paste the reply back. They run in their own queue and never hold up API models.
                    </Callout>
                  </div>
                )}
              </div>
            </section>

            {/* Settings */}
            <section className="card">
              <div className="card-head">
                <div className="t">
                  <h2>3 · Settings</h2>
                  <div className="desc">Repeats power the confidence intervals; the cap protects your wallet.</div>
                </div>
              </div>
              <div className="card-body">
                <div className="form-grid">
                  <Field label={`Repeats · ${repeats ?? 1}×`} hint="Each case runs this many times. 3+ gives meaningful 95% CIs." htmlFor="nr-repeats">
                    <div className="row">
                      <input
                        id="nr-repeats"
                        type="range"
                        min={1}
                        max={10}
                        value={repeats ?? 1}
                        onChange={(e) => {
                          setRepeatsTouched(true);
                          setRepeats(Number(e.target.value));
                        }}
                      />
                      <input className="input sm tnum" style={{ width: 64 }} type="number" min={1} max={10} value={repeats ?? 1} onChange={(e) => {
                          setRepeatsTouched(true);
                          setRepeats(Math.max(1, Math.min(10, Number(e.target.value) || 1)));
                        }}
                        aria-label="Repeats"
                      />
                    </div>
                  </Field>
                  <Field label="Concurrency" hint="Parallel jobs across the run (providers also cap their own)." htmlFor="nr-conc">
                    <input id="nr-conc" className="input" type="number" min={1} max={64} value={concurrency ?? ''} onChange={(e) => setConcurrency(Math.max(1, Math.min(64, Number(e.target.value) || 1)))} />
                  </Field>
                  <Field label="Temperature" hint="Sent only to models that accept it; others use the provider default." htmlFor="nr-temp">
                    <input id="nr-temp" className="input" type="number" min={0} max={2} step={0.1} value={temperature ?? 0} onChange={(e) => setTemperature(Number(e.target.value))} />
                  </Field>
                  <Field
                    label={
                      <>
                        <Icon.Dollar style={{ width: 13, height: 13 }} /> Spending cap (USD)
                      </>
                    }
                    hint={capTouched ? 'Hard stop for contestant + judge spend. Leave empty for no cap.' : 'Prefilled at ~1.25× the estimate. Leave empty for no cap.'}
                    error={!capValid ? 'Enter a positive number, or leave empty.' : undefined}
                    htmlFor="nr-cap"
                  >
                    <div className="input-prefix">
                      <span>$</span>
                      <input
                        id="nr-cap"
                        className={cx('input tnum', !capValid && 'invalid')}
                        inputMode="decimal"
                        placeholder="no cap"
                        value={capText}
                        onChange={(e) => {
                          setCapTouched(true);
                          setCapText(e.target.value);
                        }}
                      />
                    </div>
                  </Field>
                  {(forceVision || (est?.warnings ?? []).some((w) => /no image input/.test(w))) && (
                    <Field label="Image cases" hint="Models not marked “Accepts images” are skipped on picture questions: not scored as 0, left out of their averages. Tick to send the pictures anyway." className="span-2">
                      <label className="check">
                        <input type="checkbox" checked={forceVision} onChange={(e) => setForceVision(e.target.checked)} /> Force image cases on text-only models
                      </label>
                    </Field>
                  )}
                  <Field label="Run name" htmlFor="nr-name" className="span-2">
                    <input id="nr-name" className="input" placeholder={`${suite?.name ?? 'Custom'} · ${new Date().toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })}`} value={name} onChange={(e) => setName(e.target.value)} />
                  </Field>
                  <Field label="Judges" hint={meta?.settings.judgeExcludeSameVendor ? 'A judge never grades a model from its own vendor.' : 'Fixed judge prompts; a panel averages their verdicts.'} className="span-2">
                    <div className="chip-list">
                      {(judges ?? []).map((j) => {
                        const c = contestants.find((x) => x.id === j);
                        return (
                          <span key={j} className="chip pill">
                            <span className="sw round" style={{ background: c?.color ?? 'var(--text-3)' }} />
                            {c?.label ?? j}
                            <button type="button" className="btn ghost icon xs" aria-label={`Remove judge ${c?.label ?? j}`} onClick={() => setJudges((js) => (js ?? []).filter((x) => x !== j))}>
                              <Icon.X />
                            </button>
                          </span>
                        );
                      })}
                      <select
                        className="select sm"
                        style={{ width: 170 }}
                        value=""
                        aria-label="Add judge"
                        onChange={(e) => {
                          const v = e.target.value;
                          if (v) setJudges((js) => [...(js ?? []), v]);
                        }}
                      >
                        <option value="">+ Add judge…</option>
                        {contestants
                          .filter((c) => !(judges ?? []).includes(c.id) && !isManual(c) && !isBaseline({ contestantId: c.id, vendor: c.vendor, label: c.label }))
                          .map((c) => (
                            <option key={c.id} value={c.id}>
                              {c.label}
                            </option>
                          ))}
                      </select>
                    </div>
                  </Field>
                  <Field label="Notes" htmlFor="nr-notes" className="span-all">
                    <textarea id="nr-notes" className="textarea" rows={2} placeholder="Why this run exists (e.g. “September leaderboard video”)" value={notes} onChange={(e) => setNotes(e.target.value)} />
                  </Field>
                </div>
              </div>
            </section>
          </div>

          <div className="sticky-col">
            <EstimatePanel est={est} loading={estLoading} error={estErr} contestants={contestants} cap={cap !== null && capValid ? cap : null} canStart={canStart} starting={starting} onStart={start} blockers={blockers} />
          </div>
        </div>
      )}
    </div>
  );
}
