/**
 * "Watch it think": what each model is doing right now, rebuilt from the run's
 * live events. One tile per model: the case it is on, the answer streaming in,
 * a clock, an estimated token/cost counter while it types (exact once graded)
 * and the verdict of the case it just finished. No React here, so it is tested
 * in test/live.test.ts.
 */
import type { CaseMetrics, ResultStatus, RunEvent } from '../../types.ts';

/** Text kept per job (the tile only shows the tail). */
export const TEXT_MAX = 6000;
/** How long a verdict stays on the tile before it moves on to the next case. */
export const VERDICT_MS = 2200;
/** Rough characters per token, only for the live estimate (labelled "≈"; replaced by the recorded count). */
export const CHARS_PER_TOKEN = 4;

export interface WatchContestant {
  id: string;
  label: string;
  vendor?: string;
  color: string;
  baseline?: boolean;
  manual?: boolean;
  /** USD per 1M output tokens (for the live cost estimate). */
  outputPerM?: number;
}

export interface WatchJob {
  key: string;
  testId: string;
  caseId: string;
  repeat: number;
  startedAt: number;
  /** Segments, one per model call (label), so a retried call can drop its partial text. */
  segments: Array<{ label: string; text: string; sep: string }>;
  /** Latest step label for multi-step tests ("Day 4", "turn 2"). */
  step?: string;
  chars: number;
  firstTextAt: number | null;
}

export interface WatchVerdict {
  key: string;
  testId: string;
  caseId: string;
  repeat: number;
  status: ResultStatus;
  score: number | null;
  passed: boolean | null;
  summary: string;
  at: number;
  startedAt: number;
  wallMs: number | null;
  outputTokens: number | null;
  costUsd: number | null;
  text: string;
  step?: string;
}

export interface WatchTile extends WatchContestant {
  jobs: Map<string, WatchJob>;
  focus: string | null;
  verdict: WatchVerdict | null;
  done: number;
  total: number;
  costUsd: number;
  outTokens: number;
  sum: number;
  scored: number;
  waitingManual: number;
}

export interface WatchState {
  tiles: Map<string, WatchTile>;
  completed: number;
  total: number;
  costUsd: number;
  status: string;
  seen: Set<string>;
  /** Pending copy & paste requests → the model they belong to. */
  manualReqs: Map<string, string>;
}

export interface ResultLike {
  key: string;
  contestantId: string;
  status: ResultStatus;
  score: number | null;
  metrics?: Partial<CaseMetrics>;
}

export function jobText(j: Pick<WatchJob, 'segments'> | null | undefined): string {
  return j ? j.segments.map((s) => (s.text ? s.sep + s.text : '')).join('').replace(/^\n+/, '') : '';
}

export function createWatch(contestants: WatchContestant[], perContestant: number, results: ResultLike[] = [], prev?: WatchState): WatchState {
  const tiles = new Map<string, WatchTile>();
  for (const c of contestants) {
    const old = prev?.tiles.get(c.id);
    tiles.set(c.id, { ...c, jobs: old?.jobs ?? new Map(), focus: old?.focus ?? null, verdict: old?.verdict ?? null, done: 0, total: perContestant, costUsd: 0, outTokens: 0, sum: 0, scored: 0, waitingManual: old?.waitingManual ?? 0 });
  }
  const st: WatchState = { tiles, completed: 0, total: perContestant * contestants.length, costUsd: 0, status: prev?.status ?? 'running', seen: new Set(), manualReqs: prev?.manualReqs ?? new Map() };
  for (const r of results) countResult(st, r);
  return st;
}

function countResult(st: WatchState, r: ResultLike) {
  if (st.seen.has(r.key) || r.status === 'cancelled') return;
  st.seen.add(r.key);
  const t = st.tiles.get(r.contestantId);
  if (!t) return;
  t.done++;
  t.costUsd += r.metrics?.costUsd ?? 0;
  t.outTokens += r.metrics?.outputTokens ?? 0;
  if (typeof r.score === 'number') {
    t.sum += r.score;
    t.scored++;
  }
  t.jobs.delete(r.key);
  if (t.focus === r.key) t.focus = null;
}

/** A delta/step for a job we never saw start (joined mid-case): recover it from its key. */
function adopt(t: WatchTile, key: string, now: number): WatchJob {
  let j = t.jobs.get(key);
  if (!j) {
    const [, testId = '', caseId = '', rep = 'r0'] = key.split('::');
    j = { key, testId, caseId, repeat: Number(rep.replace(/^r/, '')) || 0, startedAt: now, segments: [], chars: 0, firstTextAt: null };
    t.jobs.set(key, j);
  }
  if (!t.focus || !t.jobs.has(t.focus)) t.focus = key;
  return j;
}

function trimJob(j: WatchJob) {
  let over = j.segments.reduce((s, x) => s + x.text.length, 0) - TEXT_MAX;
  while (over > 0 && j.segments.length) {
    const first = j.segments[0]!;
    if (first.text.length <= over && j.segments.length > 1) {
      over -= first.text.length;
      j.segments.shift();
    } else {
      first.text = first.text.slice(Math.min(first.text.length, over));
      over = 0;
    }
  }
}

/** Apply one event (mutates `st`). `now` = the viewer's clock in ms. */
export function applyWatchEvent(st: WatchState, e: RunEvent, now: number): void {
  switch (e.type) {
    case 'run.progress':
      st.completed = e.completed;
      st.total = e.total;
      st.costUsd = e.costUsd;
      return;
    case 'run.status':
      st.status = e.status;
      return;
    case 'job.started': {
      const t = st.tiles.get(e.contestantId);
      if (!t || st.seen.has(e.key)) return;
      t.jobs.set(e.key, { key: e.key, testId: e.testId, caseId: e.caseId, repeat: e.repeat, startedAt: Math.min(now, Date.parse(e.at) || now), segments: [], chars: 0, firstTextAt: null });
      if (!t.focus || !t.jobs.has(t.focus)) t.focus = e.key;
      return;
    }
    case 'job.delta': {
      const t = st.tiles.get(e.contestantId);
      if (!t || st.seen.has(e.key)) return;
      const j = adopt(t, e.key, now);
      const label = e.label ?? '';
      if (e.reset) {
        const seg = [...j.segments].reverse().find((s) => s.label === label);
        if (seg) {
          j.chars -= seg.text.length;
          seg.text = '';
        }
      }
      if (!e.text) return;
      const last = j.segments[j.segments.length - 1];
      if (last && last.label === label) last.text += e.text;
      else j.segments.push({ label, text: e.text, sep: j.segments.length ? '\n\n' : '' });
      j.chars += e.text.length;
      j.firstTextAt ??= now;
      trimJob(j);
      return;
    }
    case 'job.step': {
      const t = st.tiles.get(e.contestantId);
      if (!t || st.seen.has(e.key)) return;
      const j = adopt(t, e.key, now);
      j.step = e.frame?.label ?? e.label;
      return;
    }
    case 'job.finished': {
      const t = st.tiles.get(e.contestantId);
      if (!t || st.seen.has(e.key)) return;
      const j = t.jobs.get(e.key);
      if (e.status !== 'cancelled') {
        t.verdict = {
          key: e.key,
          testId: e.testId,
          caseId: e.caseId,
          repeat: e.repeat,
          status: e.status,
          score: e.score,
          passed: e.passed ?? (typeof e.score === 'number' ? e.score >= 0.5 : null),
          summary: e.summary,
          at: now,
          startedAt: j?.startedAt ?? now - (e.metrics?.wallMs ?? 0),
          wallMs: e.metrics?.wallMs ?? null,
          outputTokens: e.metrics?.outputTokens ?? null,
          costUsd: e.metrics ? (e.metrics.costUsd ?? 0) : null,
          text: jobText(j),
          step: j?.step,
        };
      }
      countResult(st, { key: e.key, contestantId: e.contestantId, status: e.status, score: e.score, metrics: e.metrics });
      if (!t.focus || !t.jobs.has(t.focus)) t.focus = [...t.jobs.keys()][0] ?? null;
      return;
    }
    case 'manual.request': {
      const t = st.tiles.get(e.request.contestantId);
      if (!t || st.manualReqs.has(e.request.id)) return;
      st.manualReqs.set(e.request.id, t.id);
      t.waitingManual++;
      if (!st.seen.has(e.request.key)) adopt(t, e.request.key, Date.parse(e.request.createdAt) || now);
      return;
    }
    case 'manual.resolved': {
      const t = st.tiles.get(st.manualReqs.get(e.requestId) ?? '');
      st.manualReqs.delete(e.requestId);
      if (t) t.waitingManual = Math.max(0, t.waitingManual - 1);
      return;
    }
  }
}

export type TilePhase = 'verdict' | 'thinking' | 'typing' | 'waiting-manual' | 'idle' | 'finished';

export interface TileView {
  phase: TilePhase;
  /** The job on screen (null when idle/finished). */
  job: WatchJob | null;
  verdict: WatchVerdict | null;
  text: string;
  elapsedMs: number;
  /** Tokens for the case on screen: exact when graded, otherwise estimated from the text. */
  tokens: number;
  tokensExact: boolean;
  /** This model's spend so far: recorded, plus an estimate for the answer being typed. */
  spend: number;
  spendExact: boolean;
  others: number;
  mean: number | null;
}

/** What a tile shows at time `now`: a fresh verdict first, then the current case. */
export function tileView(t: WatchTile, now: number): TileView {
  const job = t.focus ? t.jobs.get(t.focus) ?? null : null;
  const mean = t.scored ? t.sum / t.scored : null;
  const v = t.verdict && now - t.verdict.at < VERDICT_MS ? t.verdict : null;
  const others = Math.max(0, t.jobs.size - (job ? 1 : 0));
  if (v) {
    return { phase: 'verdict', job: null, verdict: v, text: v.text, elapsedMs: v.wallMs ?? v.at - v.startedAt, tokens: v.outputTokens ?? 0, tokensExact: v.outputTokens !== null, spend: t.costUsd, spendExact: true, others: t.jobs.size, mean };
  }
  if (job) {
    const text = jobText(job);
    const est = Math.ceil(job.chars / CHARS_PER_TOKEN);
    const estCost = t.outputPerM ? (est * t.outputPerM) / 1e6 : 0;
    const phase: TilePhase = t.waitingManual > 0 && !text ? 'waiting-manual' : text ? 'typing' : 'thinking';
    return { phase, job, verdict: t.verdict, text, elapsedMs: Math.max(0, now - job.startedAt), tokens: est, tokensExact: false, spend: t.costUsd + estCost, spendExact: estCost === 0, others, mean };
  }
  const finished = t.total > 0 && t.done >= t.total;
  return { phase: finished ? 'finished' : t.waitingManual > 0 ? 'waiting-manual' : 'idle', job: null, verdict: t.verdict, text: t.verdict?.text ?? '', elapsedMs: 0, tokens: t.verdict?.outputTokens ?? 0, tokensExact: true, spend: t.costUsd, spendExact: true, others, mean };
}

/** Leader by mean score so far (the random baseline never leads). */
export function watchLeader(st: WatchState): WatchTile | null {
  let best: WatchTile | null = null;
  for (const t of st.tiles.values()) {
    if (t.baseline || !t.scored) continue;
    if (!best || t.sum / t.scored > best.sum / best.scored) best = t;
  }
  const contenders = [...st.tiles.values()].filter((t) => !t.baseline && t.scored).length;
  return contenders >= 1 ? best : null;
}

/** The test most models are working on right now. */
export function currentTest(st: WatchState): string | null {
  const counts = new Map<string, number>();
  for (const t of st.tiles.values()) for (const j of t.jobs.values()) counts.set(j.testId, (counts.get(j.testId) ?? 0) + 1);
  let best: string | null = null;
  let n = 0;
  for (const [id, k] of counts) if (k > n) (best = id), (n = k);
  return best;
}

/** Split text into the dimmed history and the last `n` lines, which are emphasised. */
export function splitTail(text: string, n = 2): { head: string; tail: string } {
  let idx = text.length;
  for (let i = 0; i < n; i++) {
    const p = text.lastIndexOf('\n', idx - 1);
    if (p <= 0) return { head: '', tail: text };
    idx = p;
  }
  return { head: text.slice(0, idx + 1), tail: text.slice(idx + 1) };
}

/** Grid columns for N tiles, so 1–8 models fill a 16:9 screen. */
export function gridCols(n: number): number {
  if (n <= 1) return 1;
  if (n <= 2) return 2;
  if (n <= 3) return 3;
  if (n <= 4) return 2;
  if (n <= 6) return 3;
  return 4;
}
