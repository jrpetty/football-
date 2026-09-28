/**
 * Live commentary: plain-English one-liners generated from a run's actual
 * events (no model calls, nothing invented). Pure and deterministic, so the
 * run page, the Watch view and the OBS overlay all say exactly the same thing.
 *
 *   let st = seedCommentary(ctx, existingResults);   // silent catch-up
 *   const out = commentate(st, event, ctx);           // → { state, lines }
 */
import type { CaseMetrics, ResultStatus, RunEvent } from '../../types.ts';

export interface CommentaryContext {
  contestants: Array<{ id: string; label: string; color?: string; baseline?: boolean }>;
  tests: Array<{ id: string; name: string; kind?: 'prompt' | 'program'; caseIds: string[] }>;
  repeats: number;
}

export type CommentaryTone = 'good' | 'bad' | 'partial' | 'lead' | 'info' | 'warn';

export interface CommentaryLine {
  id: string;
  text: string;
  tone: CommentaryTone;
  kind: 'verdict' | 'lead' | 'progress' | 'streak' | 'finish' | 'status';
  contestantId?: string;
  color?: string;
  at: string;
}

export interface CommentaryState {
  /** Per model: sum and count of graded scores (0–1). */
  scores: Record<string, { sum: number; n: number }>;
  /** Per test → per model: finished cases. */
  done: Record<string, Record<string, number>>;
  /** Per test: the models (not the baseline) that have finished every case, in order. */
  finishedBy: Record<string, string[]>;
  /** Per model: right answers in a row. */
  streak: Record<string, number>;
  /** Per model: finished cases over the whole run. */
  total: Record<string, number>;
  leader: string | null;
  seen: Record<string, true>;
  seq: number;
}

/** A finished case, as found in a job.finished event or a stored result. */
export interface FinishedCaseLike {
  key: string;
  contestantId: string;
  testId: string;
  caseId: string;
  repeat: number;
  status: ResultStatus;
  score: number | null;
  passed?: boolean | null;
  summary?: string;
  metrics?: Partial<CaseMetrics>;
  at?: string;
}

export function emptyCommentary(): CommentaryState {
  return { scores: {}, done: {}, finishedBy: {}, streak: {}, total: {}, leader: null, seen: {}, seq: 0 };
}

const STREAKS = new Set([3, 5, 10, 20]);

/** "12.3 s", "1m 05s". */
export function fmtSeconds(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '';
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const t = Math.round(ms / 1000);
  return `${Math.floor(t / 60)}m ${String(t % 60).padStart(2, '0')}s`;
}

/** Short case name for a sentence: "Q4" for questions, "game 2" for simulations; "(attempt 2)" on repeats. */
export function caseShort(ctx: CommentaryContext, testId: string, caseId: string, repeat = 0): string {
  const t = ctx.tests.find((x) => x.id === testId);
  const i = t ? t.caseIds.indexOf(caseId) : -1;
  const seed = /^seed-\d+$/.test(caseId) || t?.kind === 'program';
  const base = i >= 0 ? (seed ? `game ${i + 1}` : `Q${i + 1}`) : caseId;
  return repeat > 0 ? `${base} (attempt ${repeat + 1})` : base;
}

/** Long case name for a tile: "Question 4 of 10", "Game 2 of 3". */
export function caseLong(ctx: CommentaryContext, testId: string, caseId: string, repeat = 0): string {
  const t = ctx.tests.find((x) => x.id === testId);
  const i = t ? t.caseIds.indexOf(caseId) : -1;
  const seed = /^seed-\d+$/.test(caseId) || t?.kind === 'program';
  const base = i >= 0 && t ? `${seed ? 'Game' : 'Question'} ${i + 1} of ${t.caseIds.length}` : caseId;
  return repeat > 0 ? `${base} · attempt ${repeat + 1} of ${ctx.repeats}` : base;
}

const unquote = (s: string) => s.trim().replace(/^["“”'‘’]+|["“”'‘’]+$/g, '').trim();
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n - 1).trimEnd()}…` : s);

/** The model's answer and the right one, when the grader recorded both ("Answered "11" · expected "4""). */
export function saidVsAnswer(summary: string | undefined): { said: string; answer: string } | null {
  if (!summary) return null;
  const m = /^(?:Answered|Chose)\s+(.+?)\s+·\s+expected\s+(.+?)$/.exec(summary.trim());
  if (!m) return null;
  const said = unquote(m[1]!);
  const answer = unquote(m[2]!);
  if (!said || !answer || said === '—') return null;
  return { said: clip(said, 40), answer: clip(answer, 40) };
}

/** A recorded summary worth quoting (not just "Correct"/"Incorrect"). */
function usefulSummary(summary: string | undefined): string | null {
  if (!summary) return null;
  const s = summary.trim();
  if (!s || /^(correct|incorrect|correct:.*|provider error|timed out|cancelled)$/i.test(s)) return null;
  if (/^(answered|chose)\s/i.test(s)) return null;
  return clip(s.charAt(0).toLowerCase() + s.slice(1), 64);
}

const pct = (score: number) => Math.round(score * 100);
const mean = (s: { sum: number; n: number } | undefined) => (s && s.n ? s.sum / s.n : null);

function leaderOf(st: CommentaryState, ctx: CommentaryContext): { id: string | null; contenders: number } {
  let best: string | null = null;
  let bestV = -1;
  let contenders = 0;
  for (const c of ctx.contestants) {
    if (c.baseline) continue;
    const v = mean(st.scores[c.id]);
    if (v === null) continue;
    contenders++;
    // Ties keep the current leader: the lead only changes hands when someone is strictly ahead.
    if (v > bestV + 1e-9 || (Math.abs(v - bestV) <= 1e-9 && c.id === st.leader)) {
      best = c.id;
      bestV = v;
    }
  }
  return { id: best, contenders };
}

function clone(st: CommentaryState): CommentaryState {
  return {
    scores: Object.fromEntries(Object.entries(st.scores).map(([k, v]) => [k, { ...v }])),
    done: Object.fromEntries(Object.entries(st.done).map(([k, v]) => [k, { ...v }])),
    finishedBy: Object.fromEntries(Object.entries(st.finishedBy).map(([k, v]) => [k, [...v]])),
    streak: { ...st.streak },
    total: { ...st.total },
    leader: st.leader,
    seen: { ...st.seen },
    seq: st.seq,
  };
}

/** Apply one finished case to the tallies; returns the sentences it produces. */
function finish(st: CommentaryState, f: FinishedCaseLike, ctx: CommentaryContext, speak: boolean): CommentaryLine[] {
  if (st.seen[f.key] || f.status === 'cancelled') return [];
  st.seen[f.key] = true;
  const at = f.at ?? new Date().toISOString();
  const c = ctx.contestants.find((x) => x.id === f.contestantId);
  const t = ctx.tests.find((x) => x.id === f.testId);
  const who = c?.label ?? f.contestantId;
  const test = t?.name ?? f.testId;
  const q = caseShort(ctx, f.testId, f.caseId, f.repeat);
  const lines: CommentaryLine[] = [];
  const say = (text: string, tone: CommentaryTone, kind: CommentaryLine['kind'], cid: string | undefined = f.contestantId) => {
    const cc = cid ? ctx.contestants.find((x) => x.id === cid) : undefined;
    lines.push({ id: `${f.key}#${kind}#${st.seq++}`, text, tone, kind, contestantId: cid, color: cc?.color, at });
  };

  // 1. The verdict.
  const graded = typeof f.score === 'number';
  if (graded) {
    const s = st.scores[f.contestantId] ?? { sum: 0, n: 0 };
    s.sum += f.score as number;
    s.n++;
    st.scores[f.contestantId] = s;
  }
  const perfect = graded && (f.score as number) >= 0.999;
  st.streak[f.contestantId] = perfect ? (st.streak[f.contestantId] ?? 0) + 1 : 0;
  if (speak && !c?.baseline) {
    const secs = f.metrics?.wallMs ? fmtSeconds(f.metrics.wallMs) : '';
    const extra = usefulSummary(f.summary);
    if (f.status === 'timeout') say(`${who} runs out of time on ${test} ${q}`, 'bad', 'verdict');
    else if (f.status === 'error') say(`${who} hits an error on ${test} ${q} (not counted)`, 'warn', 'verdict');
    else if (f.status === 'refusal') say(`${who} refuses to answer ${test} ${q}`, 'bad', 'verdict');
    else if (f.status === 'skipped') say(`${who} skips ${test} ${q}: it can’t see images`, 'info', 'verdict');
    else if (f.status === 'pending-human') say(`${who}’s answer to ${test} ${q} goes to a human judge`, 'info', 'verdict');
    else if (graded && perfect) {
      if (t?.kind === 'program' && extra) say(`${who} aces ${test} ${q}: ${extra}`, 'good', 'verdict');
      else say(`${who} gets ${test} ${q} right${secs ? `, in ${secs}` : ''}`, 'good', 'verdict');
    } else if (graded && (f.score as number) <= 0.001) {
      const sv = saidVsAnswer(f.summary);
      if (sv) say(`${who} misses ${test} ${q}: said ${sv.said}, answer ${sv.answer}`, 'bad', 'verdict');
      else say(`${who} misses ${test} ${q}${extra ? `: ${extra}` : ''}`, 'bad', 'verdict');
    } else if (graded) {
      say(`${who} scores ${pct(f.score as number)} out of 100 on ${test} ${q}${extra ? `: ${extra}` : ''}`, (f.score as number) >= 0.5 ? 'partial' : 'bad', 'verdict');
    }
  }

  // 2. Streaks.
  const k = st.streak[f.contestantId] ?? 0;
  if (speak && perfect && STREAKS.has(k) && !c?.baseline) say(`${who} is on a roll: ${k} right in a row`, 'good', 'streak');

  // 3. The lead.
  const prevLeader = st.leader;
  const lead = leaderOf(st, ctx);
  if (lead.id && lead.contenders >= 2 && lead.id !== prevLeader) {
    const name = ctx.contestants.find((x) => x.id === lead.id)?.label ?? lead.id;
    const v = mean(st.scores[lead.id]);
    if (speak) {
      if (prevLeader) {
        const was = ctx.contestants.find((x) => x.id === prevLeader)?.label ?? prevLeader;
        say(`${name} takes the lead from ${was}, averaging ${v === null ? '—' : pct(v)}`, 'lead', 'lead', lead.id);
      } else say(`${name} takes the early lead, averaging ${v === null ? '—' : pct(v)}`, 'lead', 'lead', lead.id);
    }
    st.leader = lead.id;
  } else if (lead.id && lead.contenders >= 2) st.leader = lead.id;

  // 4. Progress through the test and the run.
  const per = t ? t.caseIds.length * Math.max(1, ctx.repeats) : Infinity;
  const done = (st.done[f.testId] ??= {});
  done[f.contestantId] = (done[f.contestantId] ?? 0) + 1;
  st.total[f.contestantId] = (st.total[f.contestantId] ?? 0) + 1;
  const models = ctx.contestants.filter((x) => !x.baseline);
  if (!c?.baseline && done[f.contestantId] === per) {
    const by = (st.finishedBy[f.testId] ??= []);
    if (!by.includes(f.contestantId)) by.push(f.contestantId);
    if (speak && models.length > 1) {
      if (by.length === models.length) {
        let best: { id: string; v: number } | null = null;
        for (const m of models) {
          // The test's own average for each model (not the whole run's).
          const v = testMean(st, f.testId, m.id);
          if (v !== null && (!best || v > best.v)) best = { id: m.id, v };
        }
        const w = best ? ctx.contestants.find((x) => x.id === best!.id) : undefined;
        say(`All ${models.length} models have finished ${test}${w && best ? `: ${w.label} tops it with ${pct(best.v)}` : ''}`, 'info', 'progress', w?.id);
      } else if (by.length === 1) say(`${who} is the first to finish ${test}`, 'info', 'progress');
      else say(`${by.length} of ${models.length} models have finished ${test}`, 'info', 'progress');
    }
  }
  const runPer = ctx.tests.reduce((s, x) => s + x.caseIds.length, 0) * Math.max(1, ctx.repeats);
  if (speak && !c?.baseline && runPer > 0 && st.total[f.contestantId] === runPer && ctx.tests.length > 1) {
    const v = mean(st.scores[f.contestantId]);
    say(`${who} has finished every test${v === null ? '' : `, averaging ${pct(v)}`}`, 'info', 'finish');
  }
  return lines;
}

/** Per-test average for one model, from the tallies kept for that purpose. */
function testMean(st: CommentaryState, testId: string, cid: string): number | null {
  const s = st.scores[`${cid}\u0000${testId}`];
  return mean(s);
}

function trackTest(st: CommentaryState, f: FinishedCaseLike) {
  if (typeof f.score !== 'number' || st.seen[f.key]) return;
  const k = `${f.contestantId}\u0000${f.testId}`;
  const s = st.scores[k] ?? { sum: 0, n: 0 };
  s.sum += f.score;
  s.n++;
  st.scores[k] = s;
}

/** Catch up silently on results that finished before we started watching. */
export function seedCommentary(ctx: CommentaryContext, results: FinishedCaseLike[]): CommentaryState {
  const st = emptyCommentary();
  const sorted = [...results].sort((a, b) => (a.at ?? '').localeCompare(b.at ?? ''));
  for (const r of sorted) {
    trackTest(st, r);
    finish(st, r, ctx, false);
  }
  return st;
}

/** One event in, zero or more sentences out. The input state is not modified. */
export function commentate(state: CommentaryState, e: RunEvent, ctx: CommentaryContext): { state: CommentaryState; lines: CommentaryLine[] } {
  if (e.type === 'job.finished') {
    if (state.seen[e.key]) return { state, lines: [] };
    const st = clone(state);
    const f: FinishedCaseLike = { key: e.key, contestantId: e.contestantId, testId: e.testId, caseId: e.caseId, repeat: e.repeat, status: e.status, score: e.score, passed: e.passed, summary: e.summary, metrics: e.metrics, at: e.at };
    trackTest(st, f);
    return { state: st, lines: finish(st, f, ctx, true) };
  }
  if (e.type === 'run.status') {
    const st = clone(state);
    const line = (text: string, tone: CommentaryTone): CommentaryLine => ({ id: `status#${e.status}#${st.seq++}`, text, tone, kind: 'status', at: e.at });
    if (e.status === 'completed') {
      const lead = leaderOf(st, ctx);
      const c = lead.id ? ctx.contestants.find((x) => x.id === lead.id) : undefined;
      const v = lead.id ? mean(st.scores[lead.id]) : null;
      return {
        state: st,
        lines: [c && v !== null && lead.contenders >= 2 ? { ...line(`Run complete: ${c.label} finishes top, averaging ${pct(v)}`, 'lead'), contestantId: c.id, color: c.color } : line('Run complete', 'info')],
      };
    }
    if (e.status === 'cancelled') return { state: st, lines: [line(e.error && /budget|cap/i.test(e.error) ? 'Budget cap reached: the run stops here' : 'The run has been stopped', 'warn')] };
    if (e.status === 'failed') return { state: st, lines: [line('The run stopped with an error', 'warn')] };
    return { state, lines: [] };
  }
  return { state, lines: [] };
}
