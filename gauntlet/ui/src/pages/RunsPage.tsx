import { useMemo, useState } from 'react';
import { api, exportUrl } from '../api.ts';
import { useAsync, useInterval } from '../hooks.ts';
import { Link, navigate, pathOf } from '../router.tsx';
import { useToast } from '../context.tsx';
import { ConfirmDialog, Empty, ErrorState, HashTag, ModelChip, PageHead, Progress, RunStatusBadge, SkeletonRows, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { ResumeDialog } from '../components/ResumeDialog.tsx';
import { fmtCost, fmtDateTime, fmtInt, fmtRelative } from '../format.ts';
import type { RunListItem, RunStatus } from '../types.ts';

const RESUMABLE: RunStatus[] = ['interrupted', 'cancelled', 'failed'];
const FILTERS: Array<{ id: 'all' | RunStatus; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'running', label: 'Running' },
  { id: 'completed', label: 'Completed' },
  { id: 'interrupted', label: 'Interrupted' },
  { id: 'failed', label: 'Failed' },
  { id: 'cancelled', label: 'Cancelled' },
];

export default function RunsPage() {
  const runs = useAsync<RunListItem[]>(() => api.runs(), []);
  const toast = useToast();
  const [confirm, setConfirm] = useState<RunListItem | null>(null);
  const [resuming, setResuming] = useState<RunListItem | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [filter, setFilter] = useState<'all' | RunStatus>('all');
  const [q, setQ] = useState('');

  const anyActive = (runs.data ?? []).some((r) => r.status === 'running' || r.status === 'queued');
  useInterval(() => runs.reload(), anyActive ? 4000 : null);

  const list = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return (runs.data ?? []).filter(
      (r) =>
        (filter === 'all' || r.status === filter) &&
        (!needle || r.name.toLowerCase().includes(needle) || r.id.toLowerCase().includes(needle) || r.contestants.some((c) => c.label.toLowerCase().includes(needle))),
    );
  }, [runs.data, filter, q]);

  const totals = useMemo(() => {
    const all = runs.data ?? [];
    return {
      count: all.length,
      spend: all.reduce((s, r) => s + (r.costUsd ?? 0), 0),
      jobs: all.reduce((s, r) => s + (r.completedJobs ?? 0), 0),
      active: all.filter((r) => r.status === 'running' || r.status === 'queued').length,
    };
  }, [runs.data]);

  const remove = async () => {
    if (!confirm) return;
    setBusy(confirm.id);
    try {
      await api.deleteRun(confirm.id);
      toast.success(`Deleted “${confirm.name}”.`);
      runs.setData((xs) => (xs ?? []).filter((x) => x.id !== confirm.id));
      setConfirm(null);
    } catch (e) {
      toast.error(e, 'Could not delete run');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="page">
      <PageHead
        eyebrow="History"
        title="Runs"
        sub="Every run is stored with its manifest: exact test hashes, model configs, pricing snapshot and harness version."
        actions={
          <>
            <button className="btn" onClick={runs.reload} aria-label="Refresh runs">
              <Icon.Refresh /> Refresh
            </button>
            <Link to="/run/new" className="btn primary">
              <Icon.Rocket /> New run
            </Link>
          </>
        }
      />

      {runs.data && runs.data.length > 0 && (
        <div className="stats">
          <div className="stat">
            <span className="k">Runs</span>
            <span className="v">{fmtInt(totals.count)}</span>
            <span className="s">{totals.active ? `${totals.active} in progress` : 'none in progress'}</span>
          </div>
          <div className="stat">
            <span className="k">Cases scored</span>
            <span className="v">{fmtInt(totals.jobs)}</span>
            <span className="s">across all runs</span>
          </div>
          <div className="stat">
            <span className="k">Total spend</span>
            <span className="v">{fmtCost(totals.spend)}</span>
            <span className="s">contestant calls</span>
          </div>
        </div>
      )}

      <section className="card">
        <div className="lb-toolbar">
          <div className="seg" role="group" aria-label="Filter by status">
            {FILTERS.map((f) => (
              <button key={f.id} type="button" aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
                {f.label}
              </button>
            ))}
          </div>
          <span className="spacer" />
          <div className="search" style={{ width: 260 }}>
            <Icon.Search />
            <input className="input sm" placeholder="Search runs or models…" value={q} onChange={(e) => setQ(e.target.value)} aria-label="Search runs" />
          </div>
        </div>
        {runs.loading && !runs.data ? (
          <div className="card-body">
            <SkeletonRows rows={6} h={46} />
          </div>
        ) : runs.error && !runs.data ? (
          <div className="card-body">
            <ErrorState error={runs.error} onRetry={runs.reload} title="Couldn’t load runs" />
          </div>
        ) : (runs.data ?? []).length === 0 ? (
          <Empty
            icon={<Icon.History />}
            title="No runs yet"
            actions={
              <Link to="/run/new" className="btn primary">
                <Icon.Rocket /> Start your first run
              </Link>
            }
          >
            Pick a suite and a few models, check the cost estimate, and press start. Results stream in live.
          </Empty>
        ) : list.length === 0 ? (
          <Empty icon={<Icon.Filter />} title="No runs match">
            Try a different status filter or search term.
          </Empty>
        ) : (
          <div className="table-wrap">
            <table className="table runs-table">
              <thead>
                <tr>
                  <th>Status</th>
                  <th>Run</th>
                  <th>Models</th>
                  <th className="num">Tests</th>
                  <th style={{ minWidth: 170 }}>Progress</th>
                  <th className="num">Cost</th>
                  <th>Created</th>
                  <th className="num">Actions</th>
                </tr>
              </thead>
              <tbody>
                {list.map((r) => {
                  const live = r.status === 'running' || r.status === 'queued';
                  const frac = r.totalJobs ? r.completedJobs / r.totalJobs : 0;
                  return (
                    <tr key={r.id}>
                      <td>
                        <RunStatusBadge status={r.status} />
                      </td>
                      <td style={{ minWidth: 240 }}>
                        <Link to={pathOf('runs', r.id)} className="run-name">
                          {r.name || r.id}
                        </Link>
                        <div className="row" style={{ gap: 6, marginTop: 3 }}>
                          {r.suiteId && <span className="badge outline">suite: {r.suiteId}</span>}
                          <HashTag value={r.fingerprint} label="Fingerprint" n={8} />
                        </div>
                      </td>
                      <td style={{ maxWidth: 320 }}>
                        <div className="chip-list">
                          {r.contestants.slice(0, 4).map((c) => (
                            <ModelChip key={c.id} label={c.label} color={c.color} pill />
                          ))}
                          {r.contestants.length > 4 && (
                            <span className="chip pill" title={r.contestants.slice(4).map((c) => c.label).join(', ')}>
                              +{r.contestants.length - 4}
                            </span>
                          )}
                        </div>
                      </td>
                      <td className="num">{fmtInt(r.testCount)}</td>
                      <td>
                        <div className="stack tight">
                          <Progress value={frac} striped={live} color={r.status === 'failed' ? 'var(--bad)' : r.status === 'interrupted' ? 'var(--warn)' : undefined} label={`${r.completedJobs} of ${r.totalJobs} jobs`} />
                          <span className="muted tnum" style={{ fontSize: '0.78rem' }}>
                            {fmtInt(r.completedJobs)} / {fmtInt(r.totalJobs)} jobs · {Math.round(frac * 100)}%
                          </span>
                        </div>
                      </td>
                      <td className="num">{fmtCost(r.costUsd)}</td>
                      <td className="nowrap" title={fmtDateTime(r.createdAt)}>
                        {fmtRelative(r.createdAt)}
                      </td>
                      <td className="num">
                        <div className="row" style={{ justifyContent: 'flex-end', gap: 4 }}>
                          {live && (
                            <Link to={pathOf('runs', r.id, 'live')} className="btn xs live">
                              <Icon.Broadcast /> Live
                            </Link>
                          )}
                          {RESUMABLE.includes(r.status) && (
                            <button className="btn xs" onClick={() => setResuming(r)} disabled={busy === r.id} title="Re-run only missing or errored jobs (optionally with a new spending cap)">
                              <Icon.Refresh /> Resume
                            </button>
                          )}
                          {r.completedJobs > 0 && (
                            <Link to={pathOf('present', r.id)} className="btn xs" title="Episode presenter: full-screen slides for recording" aria-label={`Present ${r.name}`}>
                              <Icon.Present /> Present
                            </Link>
                          )}
                          <Link to={pathOf('runs', r.id)} className="btn xs">
                            Open
                          </Link>
                          <a className="btn xs ghost" href={exportUrl(r.id, 'csv')} download={`${r.id}.csv`} aria-label={`Export ${r.name} as CSV`} title="Export CSV">
                            CSV
                          </a>
                          <a className="btn xs ghost" href={exportUrl(r.id, 'json')} download={`${r.id}.json`} aria-label={`Export ${r.name} as JSON`} title="Export JSON">
                            JSON
                          </a>
                          <button className={cx('btn xs ghost icon')} aria-label={`Delete ${r.name}`} title="Delete run" onClick={() => setConfirm(r)} disabled={live}>
                            <Icon.Trash />
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {resuming && (
        <ResumeDialog
          open
          runId={resuming.id}
          runName={resuming.name}
          onClose={() => setResuming(null)}
          onResumed={() => {
            const id = resuming.id;
            setResuming(null);
            navigate(pathOf('runs', id, 'live'));
          }}
        />
      )}

      <ConfirmDialog
        open={!!confirm}
        danger
        title="Delete this run?"
        confirmLabel="Delete run"
        busy={busy === confirm?.id}
        onCancel={() => setConfirm(null)}
        onConfirm={remove}
        body={
          <>
            <p>
              <strong style={{ color: 'var(--text-1)' }}>{confirm?.name}</strong> and all {fmtInt(confirm?.completedJobs ?? 0)} of its results, transcripts and artifacts will be permanently deleted.
            </p>
            <p style={{ marginTop: 8 }}>Its results will also disappear from the combined leaderboard. This cannot be undone.</p>
          </>
        }
      />
    </div>
  );
}
