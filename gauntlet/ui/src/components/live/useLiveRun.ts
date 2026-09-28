/**
 * One subscription to a run's live event stream, shared by the Watch view,
 * the run page's live panel and the OBS live overlay: the tiles' state
 * (watchModel.ts) and the commentary feed (commentary.ts). Resyncs from
 * GET /api/runs/:id on (re)connect, like the Live Arena.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, subscribeRun } from '../../api.ts';
import { useMeta } from '../../context.tsx';
import { isBaseline } from '../leaderboard/util.ts';
import type { RunDetail, RunEvent, RunManifest } from '../../types.ts';
import { commentate, seedCommentary, type CommentaryContext, type CommentaryLine, type CommentaryState } from './commentary.ts';
import { applyWatchEvent, createWatch, type WatchContestant, type WatchState } from './watchModel.ts';

/** Lines kept in the feed. */
const LINES_MAX = 80;

export interface LiveRun {
  manifest: RunManifest | null;
  watch: WatchState | null;
  /** Newest first. */
  lines: CommentaryLine[];
  ctx: CommentaryContext | null;
  connected: boolean;
  active: boolean;
  error: Error | null;
  /** Bumps whenever something changed (render trigger). */
  version: number;
  reload: () => void;
}

export function commentaryContext(m: RunManifest): CommentaryContext {
  return {
    contestants: m.contestants.map((c) => ({ id: c.id, label: c.label, color: c.color, baseline: isBaseline({ contestantId: c.id, vendor: c.vendor, label: c.label }) })),
    tests: m.tests.map((t) => ({ id: t.id, name: t.name, kind: t.kind, caseIds: t.caseIds })),
    repeats: m.settings?.repeats ?? 1,
  };
}

function watchContestants(m: RunManifest, manualProviders: Set<string>): WatchContestant[] {
  return m.contestants.map((c) => ({
    id: c.id,
    label: c.label,
    vendor: c.vendor,
    color: c.color,
    baseline: isBaseline({ contestantId: c.id, vendor: c.vendor, label: c.label }),
    manual: manualProviders.has(c.provider),
    outputPerM: c.pricing?.outputPerM,
  }));
}

export function useLiveRun(runId: string | null): LiveRun {
  let meta: ReturnType<typeof useMeta>['meta'] | null = null;
  try {
    meta = useMeta().meta;
  } catch {
    meta = null;
  }
  const manualProviders = useMemo(() => new Set((meta?.providers ?? []).filter((p) => p.type === 'manual').map((p) => p.id)), [meta]);
  const manualRef = useRef(manualProviders);
  manualRef.current = manualProviders;

  const watchRef = useRef<WatchState | null>(null);
  const commRef = useRef<CommentaryState | null>(null);
  const ctxRef = useRef<CommentaryContext | null>(null);
  const linesRef = useRef<CommentaryLine[]>([]);
  const manifestRef = useRef<RunManifest | null>(null);
  const early = useRef<RunEvent[]>([]);
  const [version, setVersion] = useState(0);
  const [connected, setConnected] = useState(false);
  const [active, setActive] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const pending = useRef(false);

  const schedule = useCallback(() => {
    if (pending.current) return;
    pending.current = true;
    window.setTimeout(() => {
      pending.current = false;
      setVersion((v) => v + 1);
    }, 80);
  }, []);

  const apply = useCallback((e: RunEvent) => {
    const w = watchRef.current;
    const ctx = ctxRef.current;
    if (!w || !ctx || !commRef.current) return;
    applyWatchEvent(w, e, Date.now());
    const out = commentate(commRef.current, e, ctx);
    commRef.current = out.state;
    if (out.lines.length) linesRef.current = [...out.lines.reverse(), ...linesRef.current].slice(0, LINES_MAX);
    if (e.type === 'run.status') setActive(e.status === 'running' || e.status === 'queued');
  }, []);

  const resync = useCallback(async () => {
    if (!runId) return;
    try {
      const [d, manual] = await Promise.all([api.run(runId), api.manualQueue(runId).catch(() => null)]);
      const m = (d as RunDetail).manifest;
      manifestRef.current = m;
      const ctx = commentaryContext(m);
      ctxRef.current = ctx;
      const per = m.tests.reduce((s, t) => s + t.caseIds.length, 0) * (m.settings?.repeats ?? 1);
      const w = createWatch(watchContestants(m, manualRef.current), per, d.results, watchRef.current ?? undefined);
      w.completed = d.progress?.completed ?? d.results.length;
      w.total = d.progress?.total ?? m.totalJobs;
      w.costUsd = d.progress?.costUsd ?? d.results.reduce((s, r) => s + (r.metrics?.costUsd ?? 0) + (r.metrics?.judgeCostUsd ?? 0), 0);
      w.status = m.status;
      if (manual) {
        w.manualReqs = new Map();
        for (const t of w.tiles.values()) t.waitingManual = 0;
        for (const r of manual) applyWatchEvent(w, { type: 'manual.request', runId: m.id, request: r }, Date.now());
      }
      watchRef.current = w;
      // Commentary: keep what was already said; catch up silently on the rest.
      const results = d.results.map((r) => ({ ...r, at: r.finishedAt }));
      commRef.current = seedCommentary(ctx, results);
      const isActive = d.active || m.status === 'running' || m.status === 'queued';
      setActive(isActive);
      if (early.current.length) {
        for (const ev of early.current) apply(ev);
        early.current = [];
      }
      setError(null);
      schedule();
    } catch (e) {
      if (!watchRef.current) setError(e instanceof Error ? e : new Error(String(e)));
    }
  }, [runId, apply, schedule]);

  useEffect(() => {
    if (!runId) return;
    let alive = true;
    watchRef.current = null;
    commRef.current = null;
    linesRef.current = [];
    void resync();
    const unsub = subscribeRun(runId, {
      onEvent: (e) => {
        if (!alive) return;
        if (!watchRef.current) {
          if (early.current.length < 2000) early.current.push(e);
          return;
        }
        apply(e);
        if (e.type === 'run.status' && !['running', 'queued'].includes(e.status)) void resync();
        schedule();
      },
      onOpen: (reconnected) => {
        setConnected(true);
        if (reconnected) void resync();
      },
      onDisconnect: () => setConnected(false),
    });
    return () => {
      alive = false;
      unsub();
    };
  }, [runId, resync, apply, schedule]);

  return {
    manifest: manifestRef.current,
    watch: watchRef.current,
    lines: linesRef.current,
    ctx: ctxRef.current,
    connected,
    active,
    error,
    version,
    reload: () => void resync(),
  };
}
