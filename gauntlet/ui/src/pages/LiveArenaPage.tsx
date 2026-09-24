/**
 * Live Arena — one lane per contestant streaming its current response, with
 * progress, spend, tokens, elapsed time, the latest simulation frame and a
 * ticker of finished cases. Resyncs from GET /api/runs/:id on (re)connect.
 */
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { api, subscribeRun } from '../api.ts';
import { useAsync, useNow } from '../hooks.ts';
import { Link, pathOf } from '../router.tsx';
import { useMeta, useToast, useViewerCaption } from '../context.tsx';
import { ConfirmDialog, ErrorState, HashTag, LoadingPage, ModelCell, Progress, RunStatusBadge, ScorePill, cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { Podium } from '../components/leaderboard/Panels.tsx';
import { isBaseline } from '../components/leaderboard/util.ts';
import { fmtClock, fmtCost, fmtIndex, fmtInt, fmtPct, fmtTokens } from '../format.ts';
import type { FinishedCase, Leaderboard, ManualRequest, ReplayFrame, RunDetail, RunEvent, RunManifest, RunStatus, TestSummary } from '../types.ts';

const STREAM_MAX = 1500;
const TICKER_MAX = 40;

interface Lane {
  id: string;
  label: string;
  vendor: string;
  color: string;
  total: number;
  completed: number;
  costUsd: number;
  inTok: number;
  outTok: number;
  scoreSum: number;
  scored: number;
  errors: number;
  firstStart: number | null;
  lastFinish: number | null;
  inFlight: Map<string, { testId: string; caseId: string; repeat: number }>;
  buffers: Map<string, string>;
  frames: Map<string, { frame?: ReplayFrame; label: string }>;
  focusKey: string | null;
  lastKey: string | null;
  ticker: FinishedCase[];
  /** Pending copy & paste requests for manual contestants. */
  manual: Map<string, ManualRequest>;
  isManual: boolean;
  /** Finished cases per test (to spot the moment a model completes a whole test). */
  testDone: Map<string, { n: number; sum: number; scored: number }>;
  /** Brief on-lane announcement when the model finishes every case of a test. */
  flash: { testId: string; score: number | null; at: number } | null;
}

interface Store {
  manifest: RunManifest | null;
  status: RunStatus;
  completed: number;
  total: number;
  costUsd: number;
  lanes: Map<string, Lane>;
  connected: boolean;
  log: Array<{ level: string; message: string; at: string }>;
  /** Most recently started test (tie-break for the "now testing" card). */
  lastTest: string | null;
}

const trim = (s: string) => (s.length > STREAM_MAX ? s.slice(s.length - STREAM_MAX) : s);
const t = (iso: string | undefined) => (iso ? new Date(iso).getTime() : Date.now());

function buildStore(d: RunDetail, prev: Store | undefined, manualProviders: Set<string>): Store {
  const m = d.manifest;
  const reps = m.settings?.repeats ?? 1;
  const perContestant = (m.tests ?? []).reduce((s, x) => s + x.caseIds.length, 0) * reps;
  const lanes = new Map<string, Lane>();
  for (const c of m.contestants ?? []) {
    const old = prev?.lanes.get(c.id);
    lanes.set(c.id, {
      id: c.id,
      label: c.label,
      vendor: c.vendor,
      color: c.color,
      total: perContestant,
      completed: 0,
      costUsd: 0,
      inTok: 0,
      outTok: 0,
      scoreSum: 0,
      scored: 0,
      errors: 0,
      firstStart: null,
      lastFinish: null,
      inFlight: old?.inFlight ?? new Map(),
      buffers: old?.buffers ?? new Map(),
      frames: old?.frames ?? new Map(),
      focusKey: old?.focusKey ?? null,
      lastKey: old?.lastKey ?? null,
      ticker: [],
      manual: old?.manual ?? new Map(),
      isManual: manualProviders.has(c.provider) || !!d.leaderboard?.rows?.find((r) => r.contestantId === c.id)?.manual,
      testDone: new Map(),
      flash: old?.flash ?? null,
    });
  }
  const sorted = [...(d.results ?? [])].sort((a, b) => t(a.finishedAt) - t(b.finishedAt));
  for (const r of sorted) {
    const l = lanes.get(r.contestantId);
    if (!l) continue;
    l.completed++;
    l.costUsd += r.metrics?.costUsd ?? 0;
    l.inTok += r.metrics?.inputTokens ?? 0;
    l.outTok += r.metrics?.outputTokens ?? 0;
    if (typeof r.score === 'number') {
      l.scoreSum += r.score;
      l.scored++;
    }
    if (r.status === 'error' || r.status === 'timeout') l.errors++;
    countTest(l, r.testId, r.score);
    const st = t(r.startedAt);
    const fin = t(r.finishedAt);
    l.firstStart = l.firstStart === null ? st : Math.min(l.firstStart, st);
    l.lastFinish = l.lastFinish === null ? fin : Math.max(l.lastFinish, fin);
    l.ticker.unshift({ key: r.key, testId: r.testId, caseId: r.caseId, repeat: r.repeat, status: r.status, score: r.score, summary: r.summary, metrics: r.metrics });
    // A job that finished while we were disconnected is no longer in flight.
    l.inFlight.delete(r.key);
  }
  for (const l of lanes.values()) l.ticker = l.ticker.slice(0, TICKER_MAX);
  return {
    manifest: m,
    status: m.status,
    completed: d.progress?.completed ?? d.results.length,
    total: d.progress?.total ?? m.totalJobs,
    costUsd: d.progress?.costUsd ?? 0,
    lanes,
    connected: prev?.connected ?? false,
    log: prev?.log ?? [],
    lastTest: prev?.lastTest ?? null,
  };
}

function countTest(l: Lane, testId: string, score: number | null) {
  const td = l.testDone.get(testId) ?? { n: 0, sum: 0, scored: 0 };
  td.n++;
  if (typeof score === 'number') {
    td.sum += score;
    td.scored++;
  }
  l.testDone.set(testId, td);
  return td;
}

/** A delta/step for a job we never saw start (e.g. connected mid-job): recover it from the key. */
function adoptJob(l: Lane, key: string) {
  if (l.inFlight.has(key) || l.ticker.some((x) => x.key === key)) return;
  const [, testId = '', caseId = '', rep = 'r0'] = key.split('::');
  l.inFlight.set(key, { testId, caseId, repeat: Number(rep.replace(/^r/, '')) || 0 });
}

function applyEvent(s: Store, e: RunEvent) {
  switch (e.type) {
    case 'run.progress':
      s.completed = e.completed;
      s.total = e.total;
      s.costUsd = e.costUsd;
      return;
    case 'run.status':
      s.status = e.status;
      if (e.error) s.log.unshift({ level: 'error', message: e.error, at: e.at });
      return;
    case 'log':
      s.log.unshift({ level: e.level, message: e.message, at: e.at });
      s.log = s.log.slice(0, 20);
      return;
    case 'manual.request': {
      const l = s.lanes.get(e.request.contestantId);
      if (!l) return;
      l.isManual = true;
      l.manual.set(e.request.id, e.request);
      return;
    }
    case 'manual.resolved': {
      for (const l of s.lanes.values()) l.manual.delete(e.requestId);
      return;
    }
    case 'job.started': {
      const l = s.lanes.get(e.contestantId);
      if (!l) return;
      l.inFlight.set(e.key, { testId: e.testId, caseId: e.caseId, repeat: e.repeat });
      l.firstStart = l.firstStart ?? t(e.at);
      s.lastTest = e.testId;
      if (!l.focusKey || !l.inFlight.has(l.focusKey)) {
        // Keep the previous answer's tail on screen with a divider, so the window never goes blank.
        const prev = l.focusKey ? l.buffers.get(l.focusKey) ?? '' : '';
        const sep = `\n\n── next · ${e.caseId}${e.repeat > 0 ? ` r${e.repeat + 1}` : ''} ──\n\n`;
        l.buffers.set(e.key, prev ? trim(prev + sep) : '');
        if (l.focusKey) {
          l.buffers.delete(l.focusKey);
          l.frames.delete(l.focusKey);
        }
        l.focusKey = e.key;
      } else {
        l.buffers.set(e.key, '');
      }
      return;
    }
    case 'job.delta': {
      const l = s.lanes.get(e.contestantId);
      if (!l) return;
      adoptJob(l, e.key);
      l.buffers.set(e.key, trim((l.buffers.get(e.key) ?? '') + e.text));
      if (!l.focusKey || !l.inFlight.has(l.focusKey)) l.focusKey = e.key;
      return;
    }
    case 'job.step': {
      const l = s.lanes.get(e.contestantId);
      if (!l) return;
      adoptJob(l, e.key);
      l.frames.set(e.key, { frame: e.frame, label: e.label });
      return;
    }
    case 'job.finished': {
      const l = s.lanes.get(e.contestantId);
      if (!l) return;
      if (l.ticker.some((x) => x.key === e.key)) return; // already counted (resync)
      l.completed++;
      l.costUsd += e.metrics?.costUsd ?? 0;
      l.inTok += e.metrics?.inputTokens ?? 0;
      l.outTok += e.metrics?.outputTokens ?? 0;
      if (typeof e.score === 'number') {
        l.scoreSum += e.score;
        l.scored++;
      }
      if (e.status === 'error' || e.status === 'timeout') l.errors++;
      {
        const td = countTest(l, e.testId, e.score);
        const snap = s.manifest?.tests.find((x) => x.id === e.testId);
        const need = snap ? snap.caseIds.length * (s.manifest?.settings?.repeats ?? 1) : Infinity;
        if (td.n === need) l.flash = { testId: e.testId, score: td.scored ? td.sum / td.scored : null, at: Date.now() };
      }
      l.lastFinish = t(e.at);
      l.ticker.unshift({ key: e.key, testId: e.testId, caseId: e.caseId, repeat: e.repeat, status: e.status, score: e.score, summary: e.summary, metrics: e.metrics });
      l.ticker = l.ticker.slice(0, TICKER_MAX);
      l.inFlight.delete(e.key);
      l.lastKey = e.key;
      if (l.focusKey === e.key) {
        const next = [...l.inFlight.keys()][0] ?? null;
        // Keep the finished text on screen until the next job streams.
        if (next) l.focusKey = next;
      }
      // Drop buffers of finished jobs except the one still on screen.
      for (const k of [...l.buffers.keys()]) if (!l.inFlight.has(k) && k !== l.focusKey) l.buffers.delete(k);
      for (const k of [...l.frames.keys()]) if (!l.inFlight.has(k) && k !== l.focusKey) l.frames.delete(k);
      return;
    }
  }
}

/** First sentence of a description, for one-line broadcast captions. */
function firstSentence(text: string | undefined): string {
  if (!text) return '';
  const m = /^(.+?[.!?])(\s|$)/.exec(text.trim());
  const out = m ? m[1] : text.trim();
  return out.length > 180 ? `${out.slice(0, 177)}…` : out;
}

function StreamWindow({ text, active }: { text: string; active: boolean }) {
  const ref = useRef<HTMLPreElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [text]);
  return (
    <pre ref={ref} className={cx('stream', active && 'active')} aria-live="off">
      {text || <span className="muted">Waiting for tokens…</span>}
      {active && <span className="caret" aria-hidden="true" />}
    </pre>
  );
}

function LaneCard({ lane, rank, testName, now, done }: { lane: Lane; rank: number | null; testName: (id: string) => string; now: number; done: boolean }) {
  const focus = lane.focusKey;
  const cur = focus ? lane.inFlight.get(focus) : undefined;
  const text = focus ? lane.buffers.get(focus) ?? '' : '';
  const frameInfo = focus ? lane.frames.get(focus) : undefined;
  const frame = frameInfo?.frame;
  const mean = lane.scored ? lane.scoreSum / lane.scored : null;
  const elapsed = lane.firstStart ? (done || lane.inFlight.size === 0 ? (lane.lastFinish ?? now) : now) - lane.firstStart : 0;
  const tps = elapsed > 0 ? lane.outTok / (elapsed / 1000) : null;
  const finished = lane.total > 0 && lane.completed >= lane.total;
  const extra = lane.inFlight.size - (cur ? 1 : 0);

  const flash = lane.flash && Date.now() - lane.flash.at < 3800 ? lane.flash : null;
  return (
    <article className={cx('lane', finished && 'finished', flash && 'flashing')} style={{ ['--c' as string]: lane.color }} aria-label={`${lane.label} lane`}>
      {flash && (
        <div className="lane-flash" key={flash.at} role="status">
          <span className="lfl-k">Finished · {testName(flash.testId)}</span>
          <span className="lfl-v tnum">
            {flash.score === null ? '—' : Math.round(flash.score * 100)}
            <small>/100</small>
          </span>
        </div>
      )}
      <header className="lane-head">
        <span className={cx('lane-rank', rank !== null && rank <= 3 && `r${rank}`)} title="Live rank by mean score">
          {rank ?? '–'}
        </span>
        <ModelCell label={lane.label} vendor={lane.isManual ? `${lane.vendor} · manual` : lane.vendor} color={lane.color} />
        <div className="lane-score">
          <span className="tnum">{mean === null ? '—' : (mean * 100).toFixed(1)}</span>
          <small>mean score</small>
        </div>
      </header>
      <div className="lane-progress">
        <Progress value={lane.total ? lane.completed / lane.total : 0} color={lane.color} striped={!finished && !done} label={`${lane.label} progress`} />
        <span className="tnum">
          {fmtInt(lane.completed)}/{fmtInt(lane.total)}
        </span>
      </div>
      <div className="lane-stats tnum">
        <span title="Spend">
          <Icon.Dollar />
          {fmtCost(lane.costUsd)}
        </span>
        <span title="Tokens in / out">
          <Icon.Layers />
          {fmtTokens(lane.inTok)} / {fmtTokens(lane.outTok)}
        </span>
        <span title="Elapsed">
          <Icon.Clock />
          {fmtClock(elapsed)}
        </span>
        <span title="Output tokens per second (wall clock)">
          <Icon.Zap />
          {tps ? `${Math.round(tps)} t/s` : '—'}
        </span>
        {lane.errors > 0 && (
          <span className="bad-text" title="Errors / timeouts">
            <Icon.Alert />
            {lane.errors}
          </span>
        )}
      </div>
      <div className="lane-now">
        {finished ? (
          <span className="lane-done">
            <Icon.Flag /> Finished
          </span>
        ) : cur ? (
          <>
            <span className="now-dot" aria-hidden="true" />
            <span className="ellipsis">
              <b>{testName(cur.testId)}</b> · {cur.caseId}
              {cur.repeat > 0 ? ` · r${cur.repeat + 1}` : ''}
            </span>
            {extra > 0 && <span className="badge outline">+{extra} in flight</span>}
          </>
        ) : (
          <span className="muted">{done ? 'Stopped' : lane.lastKey || lane.completed ? 'Next case starting…' : 'Queued…'}</span>
        )}
      </div>
      {lane.manual.size > 0 ? (
        <div className="manual-wait" role="status">
          <div className="mw-icon" aria-hidden="true">
            <Icon.Copy />
          </div>
          <div className="mw-body">
            <strong>Waiting for your pasted reply</strong>
            <span className="muted">
              {[...lane.manual.values()][0].testName} · {[...lane.manual.values()][0].label}
              {lane.manual.size > 1 ? ` · +${lane.manual.size - 1} more` : ''}
            </span>
          </div>
          <Link to={`/inbox?run=${encodeURIComponent([...lane.manual.values()][0].runId)}`} className="btn sm primary no-broadcast">
            Open inbox <Icon.ChevronRight />
          </Link>
        </div>
      ) : (
        <StreamWindow text={text} active={!!cur && !finished} />
      )}
      {frame || frameInfo?.label ? (
        <div className={cx('lane-frame', frame?.tone && `tone-${frame.tone}`)}>
          <div className="lf-label">{frame?.label ?? frameInfo?.label}</div>
          {frame?.action && (
            <div className="lf-row">
              <span>Action</span>
              <b className="mono">{frame.action}</b>
            </div>
          )}
          {frame?.outcome && (
            <div className="lf-row">
              <span>Outcome</span>
              <b>{frame.outcome}</b>
            </div>
          )}
        </div>
      ) : null}
      <div className="ticker" aria-label="Finished cases, newest first">
        {lane.ticker.length === 0 ? (
          <span className="muted" style={{ fontSize: '0.78rem' }}>
            No finished cases yet
          </span>
        ) : (
          lane.ticker.slice(0, 18).map((f, i) => (
            <span key={f.key} className={cx('tick', i === 0 && 'fresh')} title={`${testName(f.testId)} · ${f.caseId} — ${f.summary}`}>
              <ScorePill score={f.score} status={f.status} />
            </span>
          ))
        )}
      </div>
    </article>
  );
}

function FinishLine({ lb, runId }: { lb: Leaderboard; runId: string }) {
  const rows = [...lb.rows].sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99));
  return (
    <section className="finish">
      <div className="finish-banner">
        <span className="checker" aria-hidden="true" />
        <div>
          <div className="eyebrow">Finish line</div>
          <h2>Final standings</h2>
        </div>
        <span className="checker" aria-hidden="true" />
      </div>
      <Podium rows={rows} big />
      <ol className="final-list">
        {rows.map((r, i) => (
          <li key={r.contestantId} className={cx(isBaseline(r) && 'is-baseline')} style={{ ['--c' as string]: r.color, animationDelay: `${600 + i * 90}ms` }}>
            <span className="fl-rank">{isBaseline(r) ? '–' : r.rank}</span>
            <ModelCell label={r.label} vendor={r.vendor} color={r.color} />
            <span className="fl-index tnum">{fmtIndex(r.index)}</span>
            <span className="fl-meta tnum">
              {fmtCost(r.totals?.costUsd)} · {fmtPct(r.coverage)} coverage
            </span>
          </li>
        ))}
      </ol>
      <div className="row no-broadcast" style={{ justifyContent: 'center', gap: 8 }}>
        <Link to={pathOf('runs', runId)} className="btn">
          Open full results <Icon.ChevronRight />
        </Link>
        <Link to={pathOf('present', runId)} className="btn" title="Episode presenter: full-screen slides for recording">
          <Icon.Present /> Present
        </Link>
      </div>
    </section>
  );
}

export default function LiveArenaPage({ runId }: { runId: string }) {
  const toast = useToast();
  const { meta, cat } = useMeta();
  const manualProviders = useMemo(() => new Set((meta?.providers ?? []).filter((p) => p.type === 'manual').map((p) => p.id)), [meta]);
  const manualRef = useRef(manualProviders);
  manualRef.current = manualProviders;
  const storeRef = useRef<Store | null>(null);
  const [, setVersion] = useState(0);
  const [error, setError] = useState<Error | null>(null);
  const [final, setFinal] = useState<Leaderboard | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const connectedRef = useRef(false);
  /** Events that arrive before the first snapshot loads are replayed onto it. */
  const early = useRef<RunEvent[]>([]);

  const schedule = useCallback(() => {
    if (pending.current) return;
    pending.current = true;
    window.setTimeout(() => {
      pending.current = false;
      setVersion((v) => v + 1);
    }, 90);
  }, []);

  const resync = useCallback(async () => {
    try {
      const [d, pendingManual] = await Promise.all([api.run(runId), api.manualQueue(runId).catch(() => null)]);
      const first = !storeRef.current;
      storeRef.current = buildStore(d, storeRef.current ?? undefined, manualRef.current);
      storeRef.current.connected = connectedRef.current;
      // Pending copy & paste requests are not replayed over SSE — load them from the queue.
      if (pendingManual) {
        for (const l of storeRef.current.lanes.values()) l.manual = new Map();
        for (const req of pendingManual) {
          const l = storeRef.current.lanes.get(req.contestantId);
          if (!l) continue;
          l.isManual = true;
          l.manual.set(req.id, req);
          adoptJob(l, req.key);
          if (!l.focusKey) l.focusKey = req.key;
        }
      }
      if (first && early.current.length) {
        for (const ev of early.current) applyEvent(storeRef.current, ev);
        early.current = [];
      }
      if (!['running', 'queued'].includes(d.manifest.status) && d.leaderboard?.rows?.length) setFinal(d.leaderboard);
      setError(null);
      schedule();
    } catch (e) {
      if (!storeRef.current) setError(e instanceof Error ? e : new Error(String(e)));
    }
  }, [runId, schedule]);

  useEffect(() => {
    let alive = true;
    void resync();
    const unsub = subscribeRun(runId, {
      onEvent: (e) => {
        const s = storeRef.current;
        if (!alive) return;
        if (!s) {
          if (early.current.length < 2000) early.current.push(e);
          return;
        }
        applyEvent(s, e);
        if (e.type === 'run.status' && !['running', 'queued'].includes(e.status)) void resync();
        schedule();
      },
      onOpen: (reconnected) => {
        connectedRef.current = true;
        if (storeRef.current) storeRef.current.connected = true;
        if (reconnected) void resync();
        schedule();
      },
      onDisconnect: () => {
        connectedRef.current = false;
        if (storeRef.current) storeRef.current.connected = false;
        schedule();
      },
    });
    return () => {
      alive = false;
      unsub();
    };
  }, [runId, resync, schedule]);

  const s = storeRef.current;
  const active = !!s && (s.status === 'running' || s.status === 'queued');
  const now = useNow(active ? 1000 : null);
  const testInfo = useAsync(() => api.tests().catch(() => [] as TestSummary[]), []);
  const infoById = useMemo(() => new Map((testInfo.data ?? []).map((x) => [x.id, x])), [testInfo.data]);
  const laneCount = s?.lanes.size ?? 0;
  useViewerCaption(
    !s
      ? null
      : active
        ? `Live: ${laneCount} AI ${laneCount === 1 ? 'model takes' : 'models take'} the same tests side by side. Each lane shows a model’s answer as it’s written and its average score so far, out of 100.`
        : 'The finish line: the final standings for this run, ranked by the Gauntlet Index — each model’s average score out of 100 across every category.',
    active ? 'Every model gets identical prompts · scores update as each answer is graded' : 'Index whiskers = 95% bootstrap confidence interval',
  );

  const testNames = useMemo(() => new Map((s?.manifest?.tests ?? []).map((x) => [x.id, x.name])), [s?.manifest]);
  const testName = useCallback((id: string) => testNames.get(id) ?? id, [testNames]);

  if (error && !s) return <ErrorState error={error} onRetry={resync} title="Couldn’t load this run" />;
  if (!s || !s.manifest) return <LoadingPage />;

  const m = s.manifest;
  const lanes = [...s.lanes.values()];
  const ranking = lanes
    .filter((l) => l.scored > 0 && !isBaseline({ contestantId: l.id, vendor: l.vendor, label: l.label }))
    .sort((a, b) => b.scoreSum / b.scored - a.scoreSum / a.scored)
    .map((l) => l.id);
  const started = m.startedAt ? t(m.startedAt) : t(m.createdAt);
  const endAt = active ? now : m.finishedAt ? t(m.finishedAt) : Math.max(...lanes.map((l) => l.lastFinish ?? 0), started);
  const elapsed = Math.max(0, endAt - started);
  const frac = s.total ? s.completed / s.total : 0;
  const eta = active && s.completed > 0 && frac < 1 ? (elapsed / s.completed) * (s.total - s.completed) : null;
  const done = !active;

  const doCancel = async () => {
    setBusy(true);
    try {
      await api.cancelRun(runId);
      toast.info('Cancel requested.');
      setConfirm(false);
    } catch (e) {
      toast.error(e, 'Could not cancel');
    } finally {
      setBusy(false);
    }
  };

  const cols = lanes.length <= 2 ? lanes.length : lanes.length === 4 ? 2 : 3;

  // "Now testing": the test most lanes are working on (ties → the most recently started).
  const counts = new Map<string, number>();
  for (const l of lanes) for (const j of l.inFlight.values()) counts.set(j.testId, (counts.get(j.testId) ?? 0) + 1);
  let current: string | null = null;
  let best = 0;
  for (const [id, n] of counts) {
    if (n > best || (n === best && id === s.lastTest)) {
      best = n;
      current = id;
    }
  }
  const curSnap = current ? m.tests.find((x) => x.id === current) : undefined;
  const curInfo = current ? infoById.get(current) : undefined;
  const curCat = curSnap ? cat(curSnap.category) : null;
  const curIndex = curSnap ? m.tests.indexOf(curSnap) + 1 : 0;
  const nowCard = active && !!curSnap;

  return (
    <div className="page arena">
      <header className="arena-head">
        <div className="arena-title">
          <div className="row" style={{ gap: 10 }}>
            {active ? (
              <span className="badge live lg">
                <span className="dot" />
                LIVE
              </span>
            ) : (
              <RunStatusBadge status={s.status} lg />
            )}
            {!s.connected && active && (
              <span className="badge warn" title="Reconnecting to the event stream">
                <Icon.Wifi /> reconnecting…
              </span>
            )}
            <span className="muted mono no-broadcast" style={{ fontSize: '0.78rem' }}>
              {m.id}
            </span>
            {nowCard && <span className="an-run only-broadcast">{m.name || m.id}</span>}
            {nowCard && (
              <span className="an-eyebrow only-broadcast flex" style={{ ['--cc' as string]: curCat?.color }}>
                Test {curIndex} of {m.tests.length}
                {curCat && (
                  <span className="an-cat">
                    <i />
                    {curCat.name}
                  </span>
                )}
              </span>
            )}
          </div>
          <h1 className={cx('ellipsis', nowCard && 'no-broadcast')}>{m.name || m.id}</h1>
          {nowCard && curSnap && (
            <div className="arena-now only-broadcast" key={curSnap.id} style={{ ['--cc' as string]: curCat?.color }} aria-live="polite">
              <div className="an-name">
                <span className="an-now">Now testing</span>
                {curSnap.name}
              </div>
              {curInfo?.hook && <div className="an-hook">{curInfo.hook}</div>}
              <div className="an-desc">
                <b>What it measures:</b> {firstSentence(curInfo?.description) || curCat?.description || '—'}
              </div>
            </div>
          )}
          <div className={cx('row wrap', nowCard && 'no-broadcast')} style={{ gap: 8 }}>
            <span className="muted" style={{ fontSize: '0.85rem' }}>
              {m.tests.length} tests · {m.contestants.length} models · {m.settings?.repeats ?? 1}× repeats
            </span>
            <HashTag value={m.fingerprint} label="Fingerprint" />
          </div>
        </div>
        <div className="arena-progress">
          <div className="ap-top">
            <span className="ap-pct tnum">{Math.floor(frac * 100)}%</span>
            <span className="muted tnum">
              {fmtInt(s.completed)} / {fmtInt(s.total)} jobs
            </span>
          </div>
          <Progress value={frac} lg striped={active} label="Overall progress" />
        </div>
        <div className="arena-stats">
          <div className="astat">
            <span className="k">Spend</span>
            <span className="v tnum">{fmtCost(s.costUsd)}</span>
          </div>
          <div className="astat">
            <span className="k">Elapsed</span>
            <span className="v tnum">{fmtClock(elapsed)}</span>
          </div>
          <div className="astat no-broadcast-sm">
            <span className="k">ETA</span>
            <span className="v tnum">{eta === null ? '—' : fmtClock(eta)}</span>
          </div>
          {s.completed > 0 && (
            <Link to={pathOf('present', runId)} className="btn no-broadcast" title="Episode presenter: full-screen slides for recording">
              <Icon.Present /> Present
            </Link>
          )}
          {active ? (
            <button className="btn danger no-broadcast" onClick={() => setConfirm(true)}>
              <Icon.Stop /> Cancel
            </button>
          ) : (
            <Link to={pathOf('runs', runId)} className="btn no-broadcast">
              Results
            </Link>
          )}
        </div>
      </header>

      {final && done && <FinishLine lb={final} runId={runId} />}
      {done && s.status !== 'completed' && (
        <div className="callout warn">
          <Icon.Alert />
          <div>
            This run is <strong>{s.status}</strong>.{' '}
            <Link to={pathOf('runs', runId)}>Open the run</Link> to resume the missing jobs.
          </div>
        </div>
      )}

      <div className="lanes" style={{ ['--lane-cols' as string]: cols }}>
        {lanes.map((l) => {
          const r = ranking.indexOf(l.id);
          return <LaneCard key={l.id} lane={l} rank={r >= 0 ? r + 1 : null} testName={testName} now={now} done={done} />;
        })}
      </div>

      {s.log.length > 0 && (
        <div className="arena-log no-broadcast" aria-label="Run log">
          {s.log.slice(0, 4).map((x, i) => (
            <div key={i} className={cx('log-line', x.level)}>
              <span className="mono muted">{new Date(x.at).toLocaleTimeString('en-GB')}</span> {x.message}
            </div>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={confirm}
        danger
        title="Cancel this run?"
        confirmLabel="Cancel run"
        busy={busy}
        onCancel={() => setConfirm(false)}
        onConfirm={doCancel}
        body="In-flight calls finish and are recorded; no new jobs start. You can resume later."
      />
    </div>
  );
}
