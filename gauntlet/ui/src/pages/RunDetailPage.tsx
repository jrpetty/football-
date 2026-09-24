import { useMemo, useState } from 'react';
import { api, exportUrl } from '../api.ts';
import { useAsync, useInterval } from '../hooks.ts';
import { Link, navigate, pathOf, setQuery, useRoute } from '../router.tsx';
import { useMeta, useToast } from '../context.tsx';
import { CategoryChip, ConfirmDialog, ErrorState, HashTag, LoadingPage, ModelChip, PageHead, Progress, RunStatusBadge, Tabs } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { LeaderboardView } from '../components/leaderboard/LeaderboardView.tsx';
import { ResultsMatrix } from '../components/ResultsMatrix.tsx';
import { ResultInspector } from '../components/ResultInspector.tsx';
import { ResumeDialog } from '../components/ResumeDialog.tsx';
import type { InspectorTarget } from '../components/ResultInspector.tsx';
import { durationBetween, fmtCost, fmtDateTime, fmtInt, fmtMs, fmtPricePerM, shortHash } from '../format.ts';
import type { RunDetail } from '../types.ts';

type Tab = 'leaderboard' | 'matrix' | 'config';

export default function RunDetailPage({ runId }: { runId: string }) {
  const { query } = useRoute();
  const { cat } = useMeta();
  const toast = useToast();
  const state = useAsync<RunDetail>(() => api.run(runId), [runId]);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [resumeOpen, setResumeOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const d = state.data;
  useInterval(() => state.reload(), d?.active ? 5000 : null);

  const tab = (query.get('tab') as Tab) || (d && d.results.length === 0 ? 'config' : 'leaderboard');
  const testQ = query.get('test');
  const conQ = query.get('c');
  const keyQ = query.get('key');

  const target: InspectorTarget | null = useMemo(() => {
    if (!d || !testQ || !conQ) return null;
    const t = d.manifest.tests.find((x) => x.id === testQ);
    const c = d.manifest.contestants.find((x) => x.id === conQ);
    if (!t || !c) return null;
    return { testId: t.id, testName: t.name, contestantId: c.id, contestantLabel: c.label, contestantColor: c.color };
  }, [d, testQ, conQ]);

  if (state.loading && !d) return <LoadingPage />;
  if (state.error && !d) return <ErrorState error={state.error} onRetry={state.reload} title="Couldn’t load this run" />;
  if (!d) return null;

  const m = d.manifest;
  const resumable = ['interrupted', 'cancelled', 'failed'].includes(m.status);
  const dur = durationBetween(m.startedAt, m.finishedAt ?? (d.active ? new Date().toISOString() : undefined));

  const doCancel = async () => {
    setBusy(true);
    try {
      await api.cancelRun(runId);
      toast.info('Cancel requested — in-flight calls will finish, nothing new starts.');
      setConfirmCancel(false);
      state.reload();
    } catch (e) {
      toast.error(e, 'Could not cancel');
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Link to="/runs">Runs</Link> <Icon.ChevronRight style={{ width: 12, height: 12 }} /> <span className="mono">{m.id}</span>
          </span>
        }
        title={
          <span className="row wrap" style={{ gap: 14 }}>
            {m.name || m.id} <RunStatusBadge status={m.status} lg />
          </span>
        }
        sub={m.notes}
        actions={
          <>
            {(d.active || m.status === 'completed') && (
              <Link to={pathOf('runs', runId, 'live')} className={d.active ? 'btn live' : 'btn'}>
                <Icon.Broadcast /> {d.active ? 'Live arena' : 'Finish line'}
              </Link>
            )}
            {resumable && (
              <button className="btn primary" onClick={() => setResumeOpen(true)} disabled={busy}>
                <Icon.Refresh /> Resume
              </button>
            )}
            {d.active && (
              <button className="btn danger" onClick={() => setConfirmCancel(true)}>
                <Icon.Stop /> Cancel
              </button>
            )}
            <a className="btn" href={exportUrl(runId, 'csv')} download={`${runId}.csv`}>
              <Icon.Download /> CSV
            </a>
            <a className="btn" href={exportUrl(runId, 'json')} download={`${runId}.json`}>
              <Icon.Download /> JSON
            </a>
          </>
        }
      />

      {m.error && (
        <div className={/budget|cap/i.test(m.error) ? 'callout warn run-error' : 'callout bad run-error'} role="alert">
          {/budget|cap/i.test(m.error) ? <Icon.Dollar /> : <Icon.Alert />}
          <div className="stack tight" style={{ flex: 1 }}>
            <strong>{/budget|cap/i.test(m.error) ? 'Budget cap reached' : 'This run stopped with an error'}</strong>
            <span>{m.error}</span>
          </div>
          {resumable && (
            <button className="btn sm" onClick={() => setResumeOpen(true)}>
              <Icon.Refresh /> Resume{/budget|cap/i.test(m.error) ? ' with a higher cap' : ''}
            </button>
          )}
        </div>
      )}

      <section className="card manifest">
        <div className="manifest-grid">
          <div className="mf">
            <span className="k">Progress</span>
            <div className="stack tight">
              <span className="v tnum">
                {fmtInt(d.progress.completed)} / {fmtInt(d.progress.total)} jobs
              </span>
              <Progress value={d.progress.total ? d.progress.completed / d.progress.total : 0} striped={d.active} />
            </div>
          </div>
          <div className="mf">
            <span className="k">Spend{m.settings?.maxCostUsd ? ' · cap' : ''}</span>
            {m.settings?.maxCostUsd ? (
              <div className="stack tight">
                <span className="v tnum">
                  {fmtCost(d.progress.costUsd)} <span className="muted">of {fmtCost(m.settings.maxCostUsd)}</span>
                </span>
                <Progress value={d.progress.costUsd / m.settings.maxCostUsd} color={d.progress.costUsd >= m.settings.maxCostUsd ? 'var(--warn)' : 'var(--good)'} label="Spend against cap" />
              </div>
            ) : (
              <span className="v tnum">
                {fmtCost(d.progress.costUsd)} <span className="muted">· no cap</span>
              </span>
            )}
          </div>
          <div className="mf">
            <span className="k">Duration</span>
            <span className="v tnum">{fmtMs(dur)}</span>
          </div>
          <div className="mf">
            <span className="k">Fingerprint</span>
            <span className="v">
              <HashTag value={m.fingerprint} label="Fingerprint" n={12} />
            </span>
          </div>
          <div className="mf">
            <span className="k">Suite</span>
            <span className="v">{m.suiteId ? `${m.suiteId}${m.suiteVersion ? ` @ ${m.suiteVersion}` : ''}` : 'hand-picked tests'}</span>
          </div>
          <div className="mf">
            <span className="k">Harness</span>
            <span className="v mono">
              v{m.harnessVersion} · protocol {m.settings?.protocolVersion ?? '—'}
            </span>
          </div>
          <div className="mf">
            <span className="k">Git commit</span>
            <span className="v mono">{m.gitCommit ? shortHash(m.gitCommit, 10) : '—'}</span>
          </div>
          <div className="mf">
            <span className="k">Environment</span>
            <span className="v mono">
              {m.node ?? '—'} · {m.platform ?? '—'}
            </span>
          </div>
          <div className="mf">
            <span className="k">Settings</span>
            <span className="v">
              {m.settings?.repeats ?? 1}× repeats · concurrency {m.settings?.concurrency ?? '—'} · temp {m.settings?.temperature ?? 0}
            </span>
          </div>
          <div className="mf">
            <span className="k">Created</span>
            <span className="v">{fmtDateTime(m.createdAt)}</span>
          </div>
          <div className="mf">
            <span className="k">Started → finished</span>
            <span className="v">
              {fmtDateTime(m.startedAt)} → {m.finishedAt ? fmtDateTime(m.finishedAt) : d.active ? 'running' : '—'}
            </span>
          </div>
          <div className="mf">
            <span className="k">Judges</span>
            <span className="v chip-list">{m.judges?.length ? m.judges.map((j) => <ModelChip key={j.id} label={j.label} color={j.color} pill />) : '—'}</span>
          </div>
        </div>
      </section>

      <Tabs
        value={tab}
        onChange={(t) => setQuery({ tab: t })}
        tabs={[
          { id: 'leaderboard', label: 'Leaderboard' },
          { id: 'matrix', label: 'Results matrix', count: d.results.length },
          { id: 'config', label: 'Tests & models', count: m.tests.length },
        ]}
      />

      {tab === 'leaderboard' &&
        (d.leaderboard?.rows?.length ? (
          <LeaderboardView lb={d.leaderboard} />
        ) : (
          <div className="card">
            <div className="empty">
              <div className="art">
                <Icon.Trophy />
              </div>
              <h3>No scored results yet</h3>
              <p>Standings appear as soon as the first cases finish.</p>
            </div>
          </div>
        ))}

      {tab === 'matrix' && (
        <section className="card">
          <ResultsMatrix manifest={m} results={d.results} onCell={(testId, c) => setQuery({ test: testId, c, key: null })} />
        </section>
      )}

      {tab === 'config' && (
        <div className="grid cols-2" style={{ alignItems: 'start' }}>
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>Tests ({m.tests.length})</h2>
                <div className="desc">Snapshotted at run start — hashes pin the exact prompts, answers and scoring.</div>
              </div>
            </div>
            <div className="table-wrap" style={{ maxHeight: 620 }}>
              <table className="table compact">
                <thead>
                  <tr>
                    <th>Test</th>
                    <th>Category</th>
                    <th className="num">Cases</th>
                    <th>Version</th>
                    <th>Hash</th>
                  </tr>
                </thead>
                <tbody>
                  {m.tests.map((t) => (
                    <tr key={t.id}>
                      <td>
                        <Link to={pathOf('tests', t.id)}>{t.name}</Link>
                        <div className="muted mono" style={{ fontSize: '0.74rem' }}>
                          {t.id}
                        </div>
                      </td>
                      <td>
                        <CategoryChip name={cat(t.category).name} color={cat(t.category).color} />
                      </td>
                      <td className="num">{t.caseIds.length}</td>
                      <td className="mono">{t.version}</td>
                      <td>
                        <HashTag value={t.hash} label="Test hash" />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
          <section className="card">
            <div className="card-head">
              <div className="t">
                <h2>Contestants ({m.contestants.length})</h2>
                <div className="desc">Model configs and the pricing snapshot used to cost this run.</div>
              </div>
            </div>
            <div className="table-wrap">
              <table className="table compact">
                <thead>
                  <tr>
                    <th>Model</th>
                    <th>Model id</th>
                    <th className="num">In / 1M</th>
                    <th className="num">Out / 1M</th>
                    <th>Options</th>
                    <th>Config</th>
                  </tr>
                </thead>
                <tbody>
                  {m.contestants.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <ModelChip label={c.label} color={c.color} />
                        <div className="muted" style={{ fontSize: '0.74rem' }}>
                          {c.vendor} · {c.provider}
                        </div>
                      </td>
                      <td className="mono" style={{ fontSize: '0.8rem' }}>
                        {c.model}
                      </td>
                      <td className="num">{fmtPricePerM(c.pricing?.inputPerM)}</td>
                      <td className="num">{fmtPricePerM(c.pricing?.outputPerM)}</td>
                      <td style={{ fontSize: '0.8rem' }}>
                        {c.options?.effort ? `effort ${c.options.effort}` : ''}
                        {c.options?.supportsTemperature ? ` · temp ${c.options.temperature ?? m.settings.temperature}` : c.options ? ' · provider-default temp' : ''}
                      </td>
                      <td>
                        <HashTag value={c.configHash} label="Config hash" />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}

      <ResultInspector
        runId={runId}
        target={target}
        results={d.results}
        initialKey={keyQ}
        onClose={() => setQuery({ test: null, c: null, key: null })}
        onSelectKey={(key) => setQuery({ key })}
        names={new Map([...m.contestants, ...(m.judges ?? [])].map((c) => [c.id, c.label]))}
      />

      {resumeOpen && (
        <ResumeDialog
          open
          runId={runId}
          runName={m.name || runId}
          onClose={() => setResumeOpen(false)}
          onResumed={() => {
            setResumeOpen(false);
            navigate(pathOf('runs', runId, 'live'));
          }}
        />
      )}

      <ConfirmDialog
        open={confirmCancel}
        danger
        title="Cancel this run?"
        confirmLabel="Cancel run"
        busy={busy}
        onCancel={() => setConfirmCancel(false)}
        onConfirm={doCancel}
        body="In-flight calls finish and are recorded; no new jobs start. You can resume later to run only the missing jobs."
      />
    </div>
  );
}
