import { useMemo } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { Link, setQuery, useRoute } from '../router.tsx';
import { useMeta, useViewerCaption } from '../context.tsx';
import { Empty, ErrorState, HashTag, LoadingPage, PageHead, Seg } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { LeaderboardView } from '../components/leaderboard/LeaderboardView.tsx';
import { fmtDate, fmtInt } from '../format.ts';
import type { Leaderboard, RunListItem, SuiteView } from '../types.ts';

export default function LeaderboardPage() {
  const { query } = useRoute();
  const { meta } = useMeta();
  const runId = query.get('run');
  const scope: 'suite' | 'run' = runId ? 'run' : 'suite';

  const suites = useAsync<SuiteView[]>(() => api.suites().catch(() => []), []);
  const runs = useAsync<RunListItem[]>(() => api.runs().catch(() => []), []);
  // Default to the flagship "core" suite when it exists, otherwise the first suite the server has.
  const suiteId = query.get('suite') ?? (suites.data ? (suites.data.some((s) => s.id === 'core') ? 'core' : suites.data[0]?.id ?? 'core') : null);
  const lbState = useAsync<Leaderboard | null>(
    () => (runId ? api.run(runId).then((d) => d.leaderboard) : suiteId ? api.leaderboard(suiteId) : new Promise<null>(() => undefined)),
    [runId, suiteId],
  );

  const suite = suites.data?.find((s) => s.id === suiteId);
  const run = runs.data?.find((r) => r.id === runId);
  const runOptions = useMemo(() => (runs.data ?? []).filter((r) => r.completedJobs > 0), [runs.data]);
  const noKeys = meta && meta.providers.length > 0 && meta.providers.every((p) => !p.hasKey && p.apiKeyEnv);

  const title = scope === 'run' ? run?.name ?? 'Run leaderboard' : suite?.name ?? (suiteId === 'core' ? 'Core Gauntlet' : suiteId ?? 'Leaderboard');
  const sub =
    scope === 'run'
      ? `Single-run standings${run ? ` · ${fmtDate(run.createdAt)}` : ''}.`
      : suite?.description ?? 'Combined standings: the most recent valid result for every model, test, case and repeat across all runs.';

  const lb = lbState.data;
  const nModels = (lb?.rows ?? []).length;
  useViewerCaption(
    lb && nModels
      ? `The overall ranking${scope === 'run' ? ' for this run' : ''}: the Gauntlet Index is each model’s average score out of 100 across every category. Thin lines show uncertainty — overlaps are too close to call.`
      : 'The Gauntlet leaderboard: AI models ranked by their average score across every category of tests.',
    'Index = weighted mean of category scores · whiskers = 95% bootstrap confidence interval',
  );

  return (
    <div className="page leaderboard-page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 10 }}>
            <Icon.Trophy style={{ width: 14, height: 14 }} /> {scope === 'run' ? 'Run leaderboard' : 'Leaderboard'}
          </span>
        }
        title={title}
        sub={sub}
        actions={
          <div className="lb-controls no-broadcast">
            <Seg
              label="Leaderboard scope"
              value={scope}
              onChange={(v) => {
                if (v === 'suite') setQuery({ run: null, suite: suiteId });
                else if (runOptions[0]) setQuery({ run: runOptions[0].id, suite: null });
              }}
              options={[
                { value: 'suite', label: 'Suite (combined)' },
                { value: 'run', label: 'Single run' },
              ]}
            />
            {scope === 'suite' ? (
              <select className="select" style={{ width: 200 }} aria-label="Suite" value={suiteId ?? ''} onChange={(e) => setQuery({ suite: e.target.value, run: null })}>
                {(suites.data ?? []).length === 0 && <option value={suiteId ?? ''}>{suiteId ?? '…'}</option>}
                {(suites.data ?? []).map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name} ({s.testCount} tests)
                  </option>
                ))}
              </select>
            ) : (
              <select className="select" style={{ width: 260 }} aria-label="Run" value={runId ?? ''} onChange={(e) => setQuery({ run: e.target.value })}>
                {runOptions.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name} · {fmtDate(r.createdAt)}
                  </option>
                ))}
              </select>
            )}
            <button className="btn icon" aria-label="Refresh leaderboard" title="Refresh" onClick={lbState.reload}>
              <Icon.Refresh />
            </button>
          </div>
        }
      />

      {lb && (
        <div className="only-broadcast flex broadcast-meta">
          <span className="badge lg accent">{fmtInt(lb.rows.length)} models</span>
          <span className="badge lg">{fmtInt(lb.tests?.length ?? 0)} tests</span>
          <span className="badge lg">
            <Icon.Fingerprint /> fingerprint <HashTag value={lb.fingerprint} n={10} />
          </span>
          {meta && <span className="badge lg">protocol {meta.protocolVersion}</span>}
        </div>
      )}

      {lbState.loading && !lb ? (
        <LoadingPage />
      ) : lbState.error && !lb ? (
        <ErrorState error={lbState.error} onRetry={lbState.reload} title="Couldn’t load the leaderboard" />
      ) : !lb || lb.rows.length === 0 ? (
        <div className="card">
          <Empty
            icon={<Icon.Trophy />}
            title="No results yet"
            actions={
              <>
                <Link to="/run/new" className="btn primary">
                  <Icon.Rocket /> Start your first run
                </Link>
                {noKeys && (
                  <Link to="/models" className="btn">
                    <Icon.Key /> Add API keys
                  </Link>
                )}
              </>
            }
          >
            {noKeys
              ? 'No provider API keys are configured yet. Add keys (as environment variables) on the Models page, then start a run.'
              : 'Run the suite against a few models and the standings, medal table and cost frontier will appear here.'}
          </Empty>
        </div>
      ) : (
        <div style={{ opacity: lbState.loading ? 0.55 : 1, transition: 'opacity 200ms' }}>
          <LeaderboardView lb={lb} />
        </div>
      )}
    </div>
  );
}
