/**
 * In-browser mock of the Gauntlet API (docs/API.md) backed by fixtures.ts.
 * Includes a live-run simulator that emits the same RunEvent stream the real
 * server sends over SSE, so the Live Arena can be demoed without a server.
 */
import type {
  ArtifactRef,
  CaseResultLite,
  ChatMessage,
  Contestant,
  ContestantView,
  GradeRequest,
  GradeResult,
  ManualReply,
  ManualRequest,
  PromptTest,
  RunEstimate,
  ScoreDetail,
  TranscriptEntry,
  ReplayFrame,
  ReviewItem,
  ReviewScoreRequest,
  RunDetail,
  RunEvent,
  RunRequest,
  RunStatus,
  TestDefinition,
} from '../types.ts';
import type { RunStreamHandlers } from '../api.ts';
import { ApiError } from '../api.ts';
import { mockArtifactUrls } from './registry.ts';
import {
  CONTESTANTS,
  DISCOVERABLE,
  META,
  PROGRAMS,
  RUN_SPECS,
  SUITES,
  TESTS,
  artifactContent,
  buildLeaderboard,
  buildRun,
  caseIdsOf,
  isManualId,
  PRIVATE_IDS,
  detailFor,
  newLiveResult,
  renderedOf,
  replayFor,
  responseFor,
  rngFrom,
  runListItem,
  summaryOf,
} from './fixtures.ts';
import type { MockRun, RunSpec } from './fixtures.ts';

const contestants: ContestantView[] = CONTESTANTS.map((c) => ({ ...c }));
const tests: TestDefinition[] = [...TESTS];
const customIds = new Set(['reasoning.calendar-puzzle']);
const sourceOf = (id: string) => (customIds.has(id) ? 'custom' : PRIVATE_IDS.has(id) ? 'private' : 'builtin');
const runs = new Map<string, MockRun>();
const specs = new Map<string, RunSpec>();
for (const s of RUN_SPECS) specs.set(s.id, s);

function getRun(id: string): MockRun {
  let run = runs.get(id);
  if (!run) {
    const spec = specs.get(id);
    if (!spec) throw new ApiError(`Run not found: ${id}`, 404);
    run = buildRun(spec.id === 'run-2026-09-24-agents' ? { ...spec, completedFrac: 0.28 } : spec);
    runs.set(id, run);
  }
  return run;
}

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

function registerExports(run: MockRun) {
  const id = run.manifest.id;
  const json = JSON.stringify({ manifest: run.manifest, results: run.results }, null, 2);
  mockArtifactUrls.set(`export:${id}:json`, `data:application/json;charset=utf-8,${encodeURIComponent(json)}`);
  const header = 'key,contestant,test,case,repeat,status,score,cost_usd,wall_ms';
  const lines = run.results.map((r) => [r.key, r.contestantId, r.testId, r.caseId, r.repeat, r.status, r.score ?? '', r.metrics.costUsd.toFixed(6), r.metrics.wallMs].join(','));
  mockArtifactUrls.set(`export:${id}:csv`, `data:text/csv;charset=utf-8,${encodeURIComponent([header, ...lines].join('\n'))}`);
}

function runDetail(id: string): RunDetail {
  const run = getRun(id);
  registerExports(run);
  const sim = sims.get(id);
  return {
    manifest: run.manifest,
    leaderboard: buildLeaderboard(run.manifest, run.results, { kind: 'run', runId: id }),
    results: run.results,
    progress: { completed: run.results.length, total: run.manifest.totalJobs, costUsd: run.results.reduce((s, r) => s + r.metrics.costUsd, 0) },
    active: !!sim && sim.timer !== null,
  };
}

function suiteTests(suiteId: string): string[] {
  const suite = SUITES.find((s) => s.id === suiteId);
  if (!suite) throw new ApiError(`Unknown suite: ${suiteId}`, 404);
  return suite.tests.some((t) => t.id === '*') ? tests.map((t) => t.id) : suite.tests.map((t) => t.id);
}

function validate(def: TestDefinition): string[] {
  const errors: string[] = [];
  if (!def || typeof def !== 'object') return ['definition must be an object'];
  if (!/^[a-z0-9][a-z0-9-]*\.[a-z0-9][a-z0-9.-]*$/.test(def.id ?? '')) errors.push('id: must look like "<category>.<slug>" (lowercase letters, digits, dashes)');
  if (!def.name?.trim()) errors.push('name: required');
  if (!META.categories.some((c) => c.id === def.category)) errors.push(`category: unknown category "${def.category}"`);
  if (!/^\d+\.\d+\.\d+$/.test(def.version ?? '')) errors.push('version: must be semver, e.g. 1.0.0');
  if (!def.description?.trim()) errors.push('description: required');
  if (def.kind === 'prompt') {
    if (!def.cases?.length) errors.push('cases: at least one case is required');
    const ids = new Set<string>();
    def.cases?.forEach((c, i) => {
      if (!c.id) errors.push(`cases[${i}].id: required`);
      if (ids.has(c.id)) errors.push(`cases[${i}].id: duplicate id "${c.id}"`);
      ids.add(c.id);
      if (!c.prompt?.trim() && !c.turns?.some((t) => t.trim())) errors.push(`cases[${i}]: needs a prompt or at least one turn`);
      const sc = c.scorer ?? def.scorer;
      if (['exact', 'number', 'choice', 'json', 'code-js', 'constraints'].includes(sc.type) && (c.expected === undefined || c.expected === '')) errors.push(`cases[${i}].expected: required for scorer "${sc.type}"`);
      if (sc.type === 'number' && c.expected !== undefined && typeof c.expected !== 'number') errors.push(`cases[${i}].expected: must be a number`);
      if (sc.type === 'constraints' && c.expected !== undefined && !Array.isArray(c.expected)) errors.push(`cases[${i}].expected: must be an array of constraints`);
    });
    const s = def.scorer;
    if (!s?.type) errors.push('scorer.type: required');
    if ((s?.type === 'judge' || s?.type === 'human') && !s.rubric?.trim()) errors.push(`scorer.rubric: required for ${s.type}`);
    if (s?.type === 'judge-classify' && (!s.labels?.length || !s.instructions?.trim())) errors.push('scorer: judge-classify needs instructions and at least one label');
  }
  return errors;
}

// ───────────────────────────── Live simulation ─────────────────────────────

interface Job {
  key: string;
  testId: string;
  caseId: string;
  repeat: number;
  text: string;
  emitted: number;
  charsPerTick: number;
  frames: ReplayFrame[];
  frameIdx: number;
  ticksPerFrame: number;
  tick: number;
}

interface Sim {
  manual?: boolean;
  runId: string;
  queues: Map<string, Array<{ testId: string; caseId: string; repeat: number }>>;
  current: Map<string, Job>;
  listeners: Set<RunStreamHandlers>;
  timer: number | null;
}

const sims = new Map<string, Sim>();

// ─────────── Manual (copy & paste) requests ───────────

interface PendingManual {
  req: ManualRequest;
  sim: Sim;
  contestantId: string;
}
const manualPending = new Map<string, PendingManual>();
let manualSeq = 1;

function combine(system: string | undefined, messages: ChatMessage[]): string {
  const parts: string[] = [];
  if (system) parts.push(`[System instructions]\n${system}`);
  const multi = messages.length > 1;
  for (const m of messages) parts.push(multi ? `[${m.role === 'user' ? 'User' : 'Assistant (your earlier reply)'}]\n${m.content}` : m.content);
  return parts.join('\n\n');
}

function makeManualRequest(sim: Sim, contestantId: string, job: Job): ManualRequest {
  const c = contestants.find((x) => x.id === contestantId);
  const t = tests.find((x) => x.id === job.testId);
  const rendered = t ? renderedOf(t).find((r) => r.caseId === job.caseId) : undefined;
  let messages: ChatMessage[];
  let label = 'response';
  let system = rendered?.system;
  if (t?.kind === 'program') {
    system = t.program === 'liars-table' ? 'You are a detective interrogating four suspects. Ask ONE question per turn in the form ASK <name>: <question>, or ACCUSE <name> BECAUSE <contradiction>.' : 'You are playing a survival simulation. Reply with ACTION and REASON lines only.';
    if (t.program === 'liars-table') {
      label = 'Question 3';
      messages = [
        { role: 'user', content: 'Case file: The museum key vanished between 20:00 and 22:00. Suspects: Ada (curator), Bram (guard), Cleo (visitor), Dmitri (cleaner). You may ask 12 questions.' },
        { role: 'assistant', content: 'ASK Bram: Where were you between 20:00 and 21:00?' },
        { role: 'user', content: 'Bram: "At the front desk the whole time. Cleo signed out at 20:40 — I watched her leave."' },
        { role: 'assistant', content: 'ASK Cleo: What time did you leave the museum?' },
        { role: 'user', content: 'Cleo: "Just before nine. I remember because the café closed at 21:00 and it was already shut."' },
      ];
    } else {
      label = 'Day 1';
      messages = [{ role: 'user', content: 'Day 1 · Morning. You wake on a beach. Vitals: health 100, food 80, water 90. You see palms to the north and a stream to the east.\nChoose ONE action: move <dir> | forage | build | rest | drink.' }];
    }
  } else {
    messages = (rendered?.turns ?? ['']).map((content) => ({ role: 'user' as const, content }));
    if (messages.length > 1) {
      const withReplies: ChatMessage[] = [];
      messages.forEach((m, i) => {
        withReplies.push(m);
        if (i < messages.length - 1) withReplies.push({ role: 'assistant', content: '(your earlier reply)' });
      });
      messages = withReplies;
      label = `turn ${Math.ceil(messages.length / 2)}`;
    }
  }
  const users = messages.filter((m) => m.role === 'user');
  return {
    id: `mr-${Date.now().toString(36)}-${manualSeq++}`,
    runId: sim.runId,
    key: job.key,
    contestantId,
    contestantLabel: c?.label ?? contestantId,
    testId: job.testId,
    testName: t?.name ?? job.testId,
    caseId: job.caseId,
    label,
    system,
    messages,
    combinedPrompt: combine(system, messages),
    latestUserMessage: users[users.length - 1]?.content ?? '',
    isContinuation: messages.length > 1,
    createdAt: new Date(Date.now() - (sim.runId === 'run-2026-09-23-manual' ? 26 * 60_000 : 40_000)).toISOString(),
  };
}

function finishJob(sim: Sim, contestantId: string, job: Job, overrides?: Partial<CaseResultLite>) {
  const run = getRun(sim.runId);
  const lite = newLiveResult(sim.runId, contestantId, job.testId, job.caseId, job.repeat);
  sim.current.delete(contestantId);
  if (!lite) return;
  const res: CaseResultLite = { ...lite, ...overrides, metrics: { ...lite.metrics, ...(overrides?.metrics ?? {}) } };
  run.results.push(res);
  emit(sim, { type: 'job.finished', runId: sim.runId, key: res.key, contestantId, testId: res.testId, caseId: res.caseId, repeat: res.repeat, status: res.status, score: res.score, summary: res.summary, metrics: res.metrics, at: new Date().toISOString() });
  emit(sim, progressEvent(sim.runId));
}

function resolveManual(id: string, reply: ManualReply | null, reason?: string) {
  const p = manualPending.get(id);
  if (!p) throw new ApiError('This request is no longer waiting (answered, cancelled or timed out)', 404);
  manualPending.delete(id);
  const job = p.sim.current.get(p.contestantId);
  emit(p.sim, { type: 'manual.resolved', runId: p.sim.runId, requestId: id });
  if (!job) return;
  if (reply) {
    emit(p.sim, { type: 'job.delta', runId: p.sim.runId, key: job.key, contestantId: p.contestantId, text: reply.text });
    const est = Math.round(reply.text.length / 4);
    finishJob(p.sim, p.contestantId, job, {
      metrics: {
        ...({} as CaseResultLite['metrics']),
        outputTokens: reply.outputTokens ?? est,
        inputTokens: reply.inputTokens ?? Math.round(p.req.combinedPrompt.length / 4),
        reasoningTokens: reply.reasoningTokens ?? 0,
        costUsd: reply.costUsd ?? 0,
        responseChars: reply.text.length,
      },
    });
  } else {
    finishJob(p.sim, p.contestantId, job, { status: 'error', score: null, passed: null, summary: 'Marked failed by operator', error: reason || 'Marked failed in the Manual Inbox' });
  }
  // Resume ticking: the next tick starts the next job or completes the run.
  if (p.sim.timer === null) startSim(p.sim);
}

function emit(sim: Sim, e: RunEvent) {
  for (const l of sim.listeners) l.onEvent(e);
}

function progressEvent(runId: string): RunEvent {
  const run = getRun(runId);
  return { type: 'run.progress', runId, completed: run.results.length, total: run.manifest.totalJobs, costUsd: run.results.reduce((s, r) => s + r.metrics.costUsd, 0), at: new Date().toISOString() };
}

function setStatus(runId: string, status: RunStatus, error?: string) {
  const run = getRun(runId);
  run.manifest = { ...run.manifest, status, finishedAt: ['completed', 'cancelled', 'failed', 'interrupted'].includes(status) ? new Date().toISOString() : undefined, error };
  const spec = specs.get(runId);
  if (spec) specs.set(runId, { ...spec, status });
  const sim = sims.get(runId);
  if (sim) emit(sim, { type: 'run.status', runId, status, at: new Date().toISOString(), error });
}

function ensureSim(runId: string): Sim | null {
  const run = getRun(runId);
  if (run.manifest.status !== 'running' && run.manifest.status !== 'queued') return sims.get(runId) ?? null;
  let sim = sims.get(runId);
  if (sim) return sim;
  const done = new Set(run.results.map((r) => r.key));
  const queues = new Map<string, Array<{ testId: string; caseId: string; repeat: number }>>();
  for (const c of run.manifest.contestants) {
    const q: Array<{ testId: string; caseId: string; repeat: number }> = [];
    for (const t of run.manifest.tests)
      for (const caseId of t.caseIds)
        for (let rep = 0; rep < run.manifest.settings.repeats; rep++) {
          if (!done.has(`${c.id}::${t.id}::${caseId}::r${rep}`)) q.push({ testId: t.id, caseId, repeat: rep });
        }
    queues.set(c.id, q);
  }
  sim = { runId, queues, current: new Map(), listeners: new Set(), timer: null };
  sims.set(runId, sim);
  startSim(sim);
  return sim;
}

const TICK = 110;

function startSim(sim: Sim) {
  if (sim.timer !== null) return;
  const run = getRun(sim.runId);
  if (run.manifest.status === 'queued') setStatus(sim.runId, 'running');
  sim.timer = window.setInterval(() => tickSim(sim), TICK);
}

function stopSim(sim: Sim) {
  if (sim.timer !== null) window.clearInterval(sim.timer);
  sim.timer = null;
}

function tickSim(sim: Sim) {
  const run = getRun(sim.runId);
  let anyWork = false;
  for (const c of run.manifest.contestants) {
    let job = sim.current.get(c.id);
    const q = sim.queues.get(c.id) ?? [];
    if (!job && q.length) {
      const next = q.shift()!;
      const t = tests.find((x) => x.id === next.testId);
      if (!t) continue;
      const r = rngFrom(`sim|${sim.runId}|${c.id}|${next.testId}|${next.caseId}|${next.repeat}`);
      const persona = c.id.includes('quill') ? 3 : c.id.includes('sable') ? 2.4 : c.id.includes('nova') ? 1.6 : 1;
      const frames = t.kind === 'program' ? (replayFor(t, Number(next.caseId.replace('seed-', '')) || 1, 0.3 + r.next() * 0.7)?.frames ?? []).slice(0, 7) : [];
      const text =
        t.kind === 'program'
          ? frames.map((f) => `[${f.label}] ${f.observation ?? ''}\nACTION: ${f.action ?? 'wait'}\nREASON: ${r.pick(['Safest option given current vitals.', 'Gather information before committing.', 'The storm makes shelter the priority.', 'Maximise expected value this turn.'])}\n`).join('\n')
          : responseFor(t, next.caseId, r.next() < 0.7 ? 1 : 0);
      job = {
        key: `${c.id}::${next.testId}::${next.caseId}::r${next.repeat}`,
        testId: next.testId,
        caseId: next.caseId,
        repeat: next.repeat,
        text,
        emitted: 0,
        charsPerTick: Math.max(6, Math.round(12 * persona + r.next() * 8)),
        frames,
        frameIdx: 0,
        ticksPerFrame: Math.max(3, Math.round(7 / persona)),
        tick: 0,
      };
      sim.current.set(c.id, job);
      emit(sim, { type: 'job.started', runId: sim.runId, key: job.key, contestantId: c.id, testId: job.testId, caseId: job.caseId, repeat: job.repeat, at: new Date().toISOString() });
      if (isManualId(c.id)) {
        const req = makeManualRequest(sim, c.id, job);
        manualPending.set(req.id, { req, sim, contestantId: c.id });
        emit(sim, { type: 'manual.request', runId: sim.runId, request: req });
      }
    }
    if (!job) continue;
    anyWork = true;
    if (isManualId(c.id)) continue; // waits for a pasted reply in the Manual Inbox
    job.tick++;
    if (job.frames.length) {
      if (job.tick % job.ticksPerFrame === 0 && job.frameIdx < job.frames.length) {
        const f = job.frames[job.frameIdx++];
        emit(sim, { type: 'job.step', runId: sim.runId, key: job.key, contestantId: c.id, label: f.label ?? `step ${f.step}`, frame: f });
        const chunkEnd = job.text.indexOf('\n\n', job.emitted + 1);
        const end = chunkEnd === -1 ? job.text.length : chunkEnd + 2;
        emit(sim, { type: 'job.delta', runId: sim.runId, key: job.key, contestantId: c.id, text: job.text.slice(job.emitted, end), label: f.label });
        job.emitted = end;
      }
    } else if (job.emitted < job.text.length) {
      const end = Math.min(job.text.length, job.emitted + job.charsPerTick);
      emit(sim, { type: 'job.delta', runId: sim.runId, key: job.key, contestantId: c.id, text: job.text.slice(job.emitted, end) });
      job.emitted = end;
    }
    const finished = job.frames.length ? job.frameIdx >= job.frames.length && job.tick % job.ticksPerFrame === 0 : job.emitted >= job.text.length;
    if (finished) {
      const lite = newLiveResult(sim.runId, c.id, job.testId, job.caseId, job.repeat);
      sim.current.delete(c.id);
      if (lite) {
        run.results.push(lite);
        emit(sim, {
          type: 'job.finished',
          runId: sim.runId,
          key: lite.key,
          contestantId: c.id,
          testId: lite.testId,
          caseId: lite.caseId,
          repeat: lite.repeat,
          status: lite.status,
          score: lite.score,
          summary: lite.summary,
          metrics: lite.metrics,
          at: new Date().toISOString(),
        });
        emit(sim, progressEvent(sim.runId));
      }
    }
  }
  const waitingManual = [...sim.current.keys()].some((id) => isManualId(id));
  if (!anyWork || (waitingManual && [...sim.current.keys()].every((id) => isManualId(id)) && [...sim.queues.entries()].every(([id, q]) => isManualId(id) || q.length === 0))) {
    if (waitingManual) {
      // Only manual jobs remain: pause ticking until a reply is pasted.
      stopSim(sim);
      return;
    }
    stopSim(sim);
    setStatus(sim.runId, 'completed');
    emit(sim, { type: 'log', runId: sim.runId, level: 'info', message: 'Run completed.', at: new Date().toISOString() });
  }
}

export function subscribe(runId: string, handlers: RunStreamHandlers): () => void {
  let sim: Sim | null = null;
  const t = window.setTimeout(() => {
    try {
      sim = ensureSim(runId);
    } catch {
      return;
    }
    handlers.onOpen?.(false);
    handlers.onEvent(progressEvent(runId));
    for (const p of manualPending.values()) if (p.req.runId === runId) handlers.onEvent({ type: 'manual.request', runId, request: p.req });
    if (sim) sim.listeners.add(handlers);
  }, 60);
  return () => {
    window.clearTimeout(t);
    sim?.listeners.delete(handlers);
  };
}

// ───────────────────────────── Router ─────────────────────────────

const JUDGED = new Set(['judge', 'judge-classify', 'artifact']);

function measuredCasePrice(contestantId: string, testId: string): number | null {
  const core = getRun('run-2026-09-21-core');
  const rs = core.results.filter((r) => r.contestantId === contestantId && r.testId === testId);
  if (!rs.length) return null;
  return rs.reduce((s, r) => s + r.metrics.costUsd, 0) / rs.length;
}

function estimateRun(req: RunRequest): RunEstimate {
  const ids = req.testIds?.length ? req.testIds : suiteTests(req.suiteId ?? 'core');
  const ts = ids.map((id) => tests.find((t) => t.id === id)).filter((t): t is TestDefinition => !!t);
  const repeats = Math.max(1, req.repeats ?? 1);
  const cons = req.contestantIds.map((id) => contestants.find((c) => c.id === id)).filter((c): c is ContestantView => !!c);
  const warnings: string[] = [];
  const perTest: RunEstimate['perTest'] = [];
  const perCon = new Map<string, { jobs: number; est: number; high: number }>();
  let judgeTotal = 0;
  for (const t of ts) {
    const cases = caseIdsOf(t).length;
    const n = cases * repeats;
    const row: Record<string, number> = {};
    let basis: RunEstimate['perTest'][number]['basis'] = 'definition';
    let anyMeasured = false;
    let allMeasured = cons.length > 0;
    for (const c of cons) {
      const manual = c.providerType === 'manual';
      const measured = manual ? null : measuredCasePrice(c.id, t.id);
      const est = t.estimate ?? { inputTokens: 500, outputTokens: 800 };
      const fromDef = (est.inputTokens * c.pricing.inputPerM + est.outputTokens * c.pricing.outputPerM) / 1e6;
      const perCase = manual ? 0 : measured ?? fromDef;
      if (measured !== null) anyMeasured = true;
      else if (!manual) allMeasured = false;
      const usd = perCase * n;
      row[c.id] = usd;
      const acc = perCon.get(c.id) ?? { jobs: 0, est: 0, high: 0 };
      acc.jobs += n;
      acc.est += usd;
      acc.high += usd * (measured !== null ? 1.25 : 1.8);
      perCon.set(c.id, acc);
    }
    if (anyMeasured) basis = allMeasured ? 'measured' : 'measured-other-models';
    const judgeUsd = t.kind === 'prompt' && JUDGED.has(t.scorer.type) ? n * cons.length * 0.0042 * Math.min(3, SETTINGS_JUDGES()) : 0;
    judgeTotal += judgeUsd;
    perTest.push({ testId: t.id, name: t.name, category: t.category, cases, perContestant: row, judgeUsd, basis });
  }
  for (const c of cons) {
    if (!c.hasKey && c.providerType !== 'manual') warnings.push(`${c.label}: provider "${c.providerLabel}" has no API key (${META.providers.find((p) => p.id === c.provider)?.apiKeyEnv ?? 'unknown env var'}).`);
    if (!c.pricing.verifiedAt && c.providerType !== 'manual') warnings.push(`${c.label}: pricing has not been verified — cost figures may be wrong.`);
    if (c.providerType === 'manual') warnings.push(`${c.label} is a manual model: you will paste ${fmtN(perCon.get(c.id)?.jobs ?? 0)}+ replies by hand in the Manual Inbox.`);
  }
  if (ts.some((t) => t.id.startsWith('long-context'))) warnings.push('Long-context tests send ~92k input tokens per case.');
  const per = cons.map((c) => {
    const acc = perCon.get(c.id) ?? { jobs: 0, est: 0, high: 0 };
    return { contestantId: c.id, jobs: acc.jobs, estCostUsd: acc.est, estCostUsdHigh: acc.high, manual: c.providerType === 'manual' };
  });
  const jobs = per.reduce((s, p) => s + p.jobs, 0);
  const calls = ts.reduce((s, t) => s + caseIdsOf(t).length * repeats * (t.estimate?.calls ?? 1), 0) * cons.length;
  const est = per.reduce((s, p) => s + p.estCostUsd, 0) + judgeTotal;
  const high = per.reduce((s, p) => s + p.estCostUsdHigh, 0) + judgeTotal * 1.5;
  return {
    jobs,
    calls,
    perContestant: per,
    perTest,
    judgeCostUsd: judgeTotal,
    estCostUsd: est,
    estCostUsdHigh: high,
    fingerprint: `${(req.suiteId ?? ids.join(',')).length.toString(16).padStart(4, '0')}a3f19c0de42b7781`,
    warnings,
  };
}

const SETTINGS_JUDGES = () => META.settings.judges.length;
const fmtN = (n: number) => n.toLocaleString('en-US');

// ─────────── Grader ───────────

function extractFinal(text: string): { answer: string; formatOk: boolean } {
  const m = [...text.matchAll(/final\s+answer\s*[:：]\s*(.+)/gi)].pop();
  if (m) return { answer: m[1].replace(/\*\*|`/g, '').trim().replace(/\.$/, ''), formatOk: true };
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean);
  return { answer: lines[lines.length - 1] ?? '', formatOk: false };
}
const norm = (v: unknown) => String(v ?? '').toLowerCase().replace(/[^a-z0-9.]/g, '');

let gradeSeq = 1;
function grade(req: GradeRequest): GradeResult {
  const t = tests.find((x) => x.id === req.testId);
  if (!t) throw new ApiError(`Unknown test: ${req.testId}`, 400);
  if (t.kind !== 'prompt') throw new ApiError('Only prompt tests can be graded from a pasted reply', 400);
  const c = t.cases.find((x) => x.id === req.caseId);
  if (!c) throw new ApiError(`Unknown case: ${req.caseId}`, 400);
  const rendered = renderedOf(t).find((r) => r.caseId === c.id)!;
  const text = req.response ?? '';
  const scorer = c.scorer ?? t.scorer;
  const detail: ScoreDetail = {};
  let score: number | null = null;
  let summary = '';
  const artifacts: ArtifactRef[] = [];
  const judgeTranscript: TranscriptEntry[] = [];
  const gradeId = `g-${Date.now().toString(36)}-${gradeSeq++}`;
  const judgePool = META.settings.judges.map((id) => contestants.find((x) => x.id === id)).filter((x): x is ContestantView => !!x);
  const judges = judgePool.filter((j) => !req.vendor || j.vendor.toLowerCase() !== req.vendor.toLowerCase());
  const excluded = judgePool.length - judges.length;
  if (['exact', 'number', 'choice', 'regex'].includes(scorer.type)) {
    const { answer, formatOk } = extractFinal(text);
    const exp = c.expected;
    const accepted = Array.isArray(exp) ? exp : [exp];
    let ok = false;
    if (scorer.type === 'number') ok = Math.abs(Number(answer.replace(/[, ]/g, '')) - Number(exp)) <= (('tolerance' in scorer ? scorer.tolerance : 0) ?? 0) + 1e-9;
    else ok = accepted.some((a) => norm(a) === norm(answer));
    score = ok ? 1 : 0;
    Object.assign(detail, { extracted: answer, expected: exp, formatOk });
    summary = ok ? 'Correct' : `Incorrect — expected ${Array.isArray(exp) ? exp[0] : String(exp)}`;
  } else if (scorer.type === 'constraints') {
    const cons = (Array.isArray(c.expected) ? c.expected : []) as Array<Record<string, unknown> & { check: string }>;
    const words = text.trim().split(/\s+/).filter(Boolean);
    const lines = text.split('\n').filter((l) => l.trim());
    const items = cons.map((k) => {
      let passed = true;
      let d: string | undefined;
      switch (k.check) {
        case 'word_count':
          passed = (k.min === undefined || words.length >= Number(k.min)) && (k.max === undefined || words.length <= Number(k.max));
          d = `${words.length} words`;
          break;
        case 'no_letter':
          passed = !text.toLowerCase().includes(String(k.letter).toLowerCase());
          break;
        case 'all_lowercase':
          passed = text === text.toLowerCase();
          break;
        case 'no_commas':
          passed = !text.includes(',');
          break;
        case 'line_count':
          passed = (k.min === undefined || lines.length >= Number(k.min)) && (k.max === undefined || lines.length <= Number(k.max));
          d = `${lines.length} lines`;
          break;
        case 'acrostic':
          passed = lines.map((l) => l.trim()[0]?.toLowerCase()).join('') === String(k.word).toLowerCase();
          break;
        case 'ends_with':
          passed = text.trim().toLowerCase().replace(/[.!]$/, '').endsWith(String(k.text).toLowerCase());
          break;
        case 'json':
          try {
            JSON.parse(text.replace(/^```\w*|```$/g, ''));
          } catch {
            passed = false;
          }
          break;
      }
      return { label: k.check.replace(/_/g, ' '), passed, detail: d };
    });
    score = items.length ? items.filter((i) => i.passed).length / items.length : 0;
    detail.items = items;
    summary = `${items.filter((i) => i.passed).length}/${items.length} constraints met`;
  } else if (scorer.type === 'json') {
    let parsed: unknown = null;
    try {
      parsed = JSON.parse((text.match(/```(?:json)?\s*([\s\S]*?)```/)?.[1] ?? text).trim());
    } catch {
      /* not json */
    }
    const exp = (c.expected ?? {}) as Record<string, unknown>;
    const keys = Object.keys(exp);
    const items = keys.map((k) => ({ label: k, passed: !!parsed && typeof parsed === 'object' && JSON.stringify((parsed as Record<string, unknown>)[k]) === JSON.stringify(exp[k]) }));
    score = keys.length ? items.filter((i) => i.passed).length / keys.length : 0;
    Object.assign(detail, { items, expected: exp, formatOk: parsed !== null });
    summary = parsed === null ? 'Not valid JSON' : `${Math.round(score * 100)}% of fields exact`;
  } else if (scorer.type === 'code-js') {
    const hasFn = /function|=>/.test(text);
    const items = Array.from({ length: 5 }, (_, i) => ({ label: `unit test ${i + 1}`, passed: hasFn && i < 4 }));
    score = items.filter((i) => i.passed).length / items.length;
    detail.items = items;
    summary = `${items.filter((i) => i.passed).length}/5 hidden tests passed`;
  } else if (scorer.type === 'judge-classify' || scorer.type === 'judge') {
    const honest = /no such|does ?n.t exist|not aware|cannot|can't|isn't a|there is no|fabricat|not a real|no record/i.test(text);
    score = honest ? 1 : /might|possibly|not sure|uncertain/i.test(text) ? 0.5 : 0;
    detail.judge = judges.map((j, i) => ({
      contestantId: j.id,
      score: i === 1 && score === 0.5 ? 1 : score ?? 0,
      label: score === 1 ? 'corrects' : score === 0.5 ? 'hedges' : 'fabricates',
      rationale: score === 1 ? 'Identifies the false premise clearly and offers a correct alternative.' : score === 0.5 ? 'Flags uncertainty but still offers invented specifics.' : 'States invented facts confidently with no hedging.',
    }));
    summary = score === 1 ? 'corrects the premise' : score === 0.5 ? 'hedges' : 'fabricates';
  } else if (scorer.type === 'artifact') {
    const html = text.match(/```html\s*([\s\S]*?)```/)?.[1] ?? (/<(!doctype|html)/i.test(text) ? text : null);
    const svg = text.match(/<svg[\s\S]*<\/svg>/i)?.[0] ?? null;
    const content = scorer.format === 'svg' ? svg : html;
    if (content) {
      const file = `${gradeId}/artifact.${scorer.format}`;
      mockArtifactUrls.set(`graded/${file}`, `data:${scorer.format === 'svg' ? 'image/svg+xml' : 'text/html'};charset=utf-8,${encodeURIComponent(content)}`);
      artifacts.push({ name: `artifact.${scorer.format}`, kind: scorer.format, file, bytes: content.length });
    }
    detail.items = (scorer.checks ?? [{ check: 'parses' }]).map((k) => ({ label: k.check.replace(/_/g, ' '), passed: !!content }));
    detail.judge = content ? judges.map((j) => ({ contestantId: j.id, score: 0.7, rationale: 'Renders correctly and responds to input; visuals are functional but plain.' })) : [];
    score = content ? 0.76 : 0;
    summary = content ? 'Artifact extracted · checks passed' : `No ${scorer.format.toUpperCase()} artifact found in the reply`;
  } else if (scorer.type === 'human') {
    return { outcome: { score: null, passed: null, summary: 'Human-scored test — rate it in Blind Review after a run', detail: {}, pendingHuman: true }, rendered, judgeCostUsd: 0, judgeTranscript: [], artifacts: [], gradeId };
  } else {
    score = text.trim() ? 0.5 : 0;
    summary = 'Scored';
  }
  const judged = JUDGED.has(scorer.type);
  if (judged) {
    for (const j of judges) {
      judgeTranscript.push({
        label: `judge · ${j.label}`,
        system: 'You are a strict, impartial grader. You do not know which model wrote the response. Output JSON only.',
        messages: [{ role: 'user', content: `Rubric / instructions:\n${'rubric' in scorer ? scorer.rubric ?? '' : 'instructions' in scorer ? scorer.instructions : ''}\n\nResponse to grade:\n<<<\n${text.slice(0, 600)}\n>>>` }],
        response: JSON.stringify({ score, verdict: summary }),
        usage: { inputTokens: 520, outputTokens: 80, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 },
        ttftMs: 640,
        totalMs: 1900,
        stopReason: 'end',
        rawStopReason: 'stop',
        costUsd: 0.0041,
        retries: 0,
        judge: true,
      });
    }
    if (excluded) detail.notes = `${excluded} judge${excluded === 1 ? '' : 's'} excluded because they are from the same vendor (${req.vendor}).`;
  }
  return {
    outcome: { score, passed: score === null ? null : score >= 0.5, summary, detail },
    rendered,
    judgeCostUsd: judged ? judges.length * 0.0041 : 0,
    judgeTranscript,
    artifacts,
    gradeId,
  };
}

let runSeq = 1;

export async function handle(method: string, fullPath: string, body: unknown): Promise<unknown> {
  await delay(90 + Math.random() * 160);
  const [path, qs = ''] = fullPath.split('?');
  const q = new URLSearchParams(qs);
  const parts = path.replace(/^\/api\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  const [a, b, c, d] = parts;
  const route = `${method} ${a ?? ''}`;

  switch (route) {
    case 'GET meta':
      return META;
    case 'GET contestants':
      return contestants;
    case 'PUT contestants': {
      const input = body as Contestant;
      const p = META.providers.find((x) => x.id === input.provider);
      const view: ContestantView = { ...input, configHash: `cfg${Date.now().toString(16)}`, hasKey: p?.hasKey ?? false, providerLabel: p?.label ?? input.provider, providerType: p?.type ?? 'openai-compatible' };
      const i = contestants.findIndex((x) => x.id === b);
      if (i >= 0) contestants[i] = view;
      else contestants.push(view);
      return view;
    }
    case 'DELETE contestants': {
      const i = contestants.findIndex((x) => x.id === b);
      if (i >= 0) contestants.splice(i, 1);
      return { ok: true };
    }
    case 'POST contestants': {
      const con = contestants.find((x) => x.id === b);
      await delay(500);
      if (!con) throw new ApiError('Unknown contestant', 404);
      if (!con.hasKey) return { ok: false, error: `Missing API key: set ${META.providers.find((p) => p.id === con.provider)?.apiKeyEnv} in the server environment.` };
      return { ok: true, text: 'pong', totalMs: 420 + Math.round(Math.random() * 600), ttftMs: 180 + Math.round(Math.random() * 300), usage: { inputTokens: 14, outputTokens: 3, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 }, costUsd: (14 * con.pricing.inputPerM + 3 * con.pricing.outputPerM) / 1e6 };
    }
    case 'GET providers':
      await delay(400);
      return { models: DISCOVERABLE[b] ?? [] };
    case 'GET tests': {
      if (!b) return tests.map((t) => ({ ...summaryOf(t), source: sourceOf(t.id) }));
      const t = tests.find((x) => x.id === b);
      if (!t) throw new ApiError(`Test not found: ${b}`, 404);
      return { definition: t, summary: { ...summaryOf(t), source: sourceOf(t.id) }, rendered: renderedOf(t), program: t.kind === 'program' ? PROGRAMS.find((p) => p.id === t.program) : undefined };
    }
    case 'POST tests': {
      const def = (body as { definition: TestDefinition }).definition;
      const errors = validate(def);
      if (b === 'validate') return { ok: errors.length === 0, errors, hash: errors.length ? undefined : summaryOf(def).hash };
      if (errors.length) throw new ApiError('Invalid test definition', 400, errors);
      if (tests.some((t) => t.id === def.id)) throw new ApiError(`A test with id "${def.id}" already exists`, 409);
      tests.push(def);
      customIds.add(def.id);
      return { ...summaryOf(def), source: 'custom' };
    }
    case 'PUT tests': {
      const def = (body as { definition: TestDefinition }).definition;
      if (!customIds.has(b)) throw new ApiError('Only custom tests can be edited', 403);
      const errors = validate(def);
      if (errors.length) throw new ApiError('Invalid test definition', 400, errors);
      const i = tests.findIndex((t) => t.id === b);
      const prev = tests[i];
      let next = def;
      if (prev && prev.version === def.version && JSON.stringify(prev) !== JSON.stringify(def)) {
        const [x, y, z] = def.version.split('.').map(Number);
        next = { ...def, version: `${x}.${y}.${z + 1}` } as PromptTest;
      }
      if (i >= 0) tests[i] = next;
      return { ...summaryOf(next), source: 'custom' };
    }
    case 'DELETE tests': {
      if (!customIds.has(b)) throw new ApiError('Only custom tests can be deleted', 403);
      const i = tests.findIndex((t) => t.id === b);
      if (i >= 0) tests.splice(i, 1);
      customIds.delete(b);
      return { ok: true };
    }
    case 'GET suites':
      return SUITES.map((s) => ({ ...s, testCount: s.tests.some((t) => t.id === '*') ? tests.length : s.tests.length }));
    case 'POST estimate':
      return estimateRun(body as RunRequest);
    case 'GET costs': {
      const suiteId = q.get('suite') ?? 'core';
      const repeats = Number(q.get('repeats') ?? 1) || 1;
      const models = (q.get('models') ?? '').split(',').filter(Boolean);
      const ids = models.length ? models : contestants.filter((c) => c.enabled && c.providerType !== 'manual' && c.providerType !== 'mock').map((c) => c.id);
      return estimateRun({ suiteId, repeats, contestantIds: ids });
    }
    case 'GET manual': {
      for (const [id, spec] of specs) if (spec.status === 'running') ensureSim(id);
      const runId = q.get('runId');
      return [...manualPending.values()].map((p) => p.req).filter((r) => !runId || r.runId === runId).sort((x, y) => x.createdAt.localeCompare(y.createdAt));
    }
    case 'POST manual': {
      if (c === 'fail') {
        resolveManual(b, null, (body as { reason?: string })?.reason);
        return { ok: true };
      }
      const reply = body as ManualReply;
      if (typeof reply?.text !== 'string' || !reply.text.trim()) throw new ApiError('Paste the model reply into "text"', 400);
      resolveManual(b, reply);
      return { ok: true };
    }
    case 'POST grade':
      await delay(700);
      return grade(body as GradeRequest);
    case 'POST runs': {
      if (!b) {
        const req = body as RunRequest;
        if (!req.contestantIds?.length) throw new ApiError('Pick at least one contestant', 400);
        const ids = req.testIds?.length ? req.testIds : suiteTests(req.suiteId ?? 'core');
        const id = `run-demo-${Date.now().toString(36)}-${runSeq++}`;
        const spec: RunSpec = { id, name: req.name?.trim() || `Run ${new Date().toLocaleString('en-GB')}`, status: 'running', contestantIds: req.contestantIds, testIds: ids, repeats: req.repeats ?? 1, suiteId: req.testIds?.length ? undefined : req.suiteId, createdAt: new Date().toISOString(), completedFrac: 0, notes: req.notes, concurrency: req.concurrency, maxCostUsd: req.maxCostUsd };
        specs.set(id, spec);
        runs.set(id, buildRun(spec));
        return { runId: id };
      }
      if (c === 'cancel') {
        const sim = sims.get(b);
        if (sim) stopSim(sim);
        setStatus(b, 'cancelled');
        return { ok: true };
      }
      if (c === 'resume') {
        const run = getRun(b);
        const cap = (body as { maxCostUsd?: number | null } | undefined)?.maxCostUsd;
        if (cap !== undefined && cap !== null && !(cap > 0)) throw new ApiError('maxCostUsd must be a positive number or null', 400);
        run.results = run.results.filter((r) => r.status !== 'error' && r.status !== 'timeout');
        const settings = { ...run.manifest.settings, maxCostUsd: cap === undefined ? run.manifest.settings.maxCostUsd : cap ?? undefined };
        run.manifest = { ...run.manifest, status: 'running', finishedAt: undefined, error: undefined, settings };
        const spec = specs.get(b);
        if (spec) specs.set(b, { ...spec, status: 'running', error: undefined });
        sims.delete(b);
        ensureSim(b);
        return { ok: true };
      }
      throw new ApiError('Not found', 404);
    }
    case 'GET runs': {
      if (!b) {
        const ids = [...specs.keys()];
        return ids
          .map((id) => {
            const run = getRun(id);
            registerExports(run);
            return runListItem(run);
          })
          .sort((x, y) => y.createdAt.localeCompare(x.createdAt));
      }
      if (!c) {
        const run = getRun(b);
        if (run.manifest.status === 'running') ensureSim(b);
        return runDetail(b);
      }
      if (c === 'results' && d) {
        const run = getRun(b);
        const lite = run.results.find((r) => r.key === d);
        if (!lite) throw new ApiError(`Result not found: ${d}`, 404);
        const full = detailFor(lite);
        for (const art of full.artifacts) {
          const content = artifactContent(art);
          const mime = art.kind === 'svg' ? 'image/svg+xml' : art.kind === 'html' ? 'text/html' : 'text/plain';
          mockArtifactUrls.set(`${b}/${art.file}`, `data:${mime};charset=utf-8,${encodeURIComponent(content)}`);
        }
        return full;
      }
      throw new ApiError('Not found', 404);
    }
    case 'DELETE runs': {
      const sim = sims.get(b);
      if (sim) stopSim(sim);
      specs.delete(b);
      runs.delete(b);
      return { ok: true };
    }
    case 'GET leaderboard': {
      const suiteId = q.get('suite') ?? 'core';
      const ids = new Set(suiteTests(suiteId));
      const core = getRun('run-2026-09-21-core');
      const manifest = { ...core.manifest, tests: core.manifest.tests.filter((t) => ids.has(t.id)), fingerprint: SUITES.find((s) => s.id === suiteId)?.fingerprint ?? core.manifest.fingerprint };
      return buildLeaderboard(manifest, core.results.filter((r) => ids.has(r.testId)), { kind: 'combined', suiteId }, suiteId === 'core' ? 17 : 0);
    }
    case 'GET review': {
      const testId = q.get('testId');
      const out: ReviewItem[] = [];
      for (const id of specs.keys()) {
        const run = getRun(id);
        for (const r of run.results) {
          const t = tests.find((x) => x.id === r.testId);
          if (!t || t.kind !== 'prompt') continue;
          if (testId && r.testId !== testId) continue;
          let reason: ReviewItem['reason'] | null = null;
          if (t.scorer.type === 'human') reason = 'human-scored';
          else if (t.scorer.type === 'artifact') reason = 'second-opinion';
          else if (t.scorer.type === 'judge-classify' && rngFrom(`disagree|${r.key}`).next() < 0.22 && !(r.humanScores?.length)) reason = 'judge-disagreement';
          if (!reason) continue;
          out.push({ runId: id, key: r.key, testId: r.testId, caseId: r.caseId, contestantId: r.contestantId, status: r.status, score: r.score, humanScores: r.humanScores, reason });
        }
      }
      return out;
    }
    case 'POST review': {
      const req = body as ReviewScoreRequest;
      const run = getRun(req.runId);
      const r = run.results.find((x) => x.key === req.key);
      if (!r) throw new ApiError('Result not found', 404);
      const hs = [...(r.humanScores ?? []), { rater: req.rater, score: req.score, at: new Date().toISOString(), note: req.note }];
      const next: CaseResultLite = { ...r, humanScores: hs };
      const t = tests.find((x) => x.id === r.testId);
      if (t && t.kind === 'prompt' && (t.scorer.type === 'human' || t.scorer.type === 'judge-classify')) {
        next.score = hs.reduce((s, h) => s + h.score, 0) / hs.length;
        next.status = 'ok';
        next.passed = next.score >= 0.5;
      }
      run.results = run.results.map((x) => (x.key === r.key ? next : x));
      return next;
    }
  }
  throw new ApiError(`Mock: no route for ${method} ${path}`, 404);
}
