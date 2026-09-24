/**
 * In-browser mock of the Gauntlet API (docs/API.md) backed by fixtures.ts.
 * Includes a live-run simulator that emits the same RunEvent stream the real
 * server sends over SSE, so the Live Arena can be demoed without a server.
 */
import type {
  CaseResultLite,
  Contestant,
  ContestantView,
  EstimateResult,
  PromptTest,
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
  runId: string;
  queues: Map<string, Array<{ testId: string; caseId: string; repeat: number }>>;
  current: Map<string, Job>;
  listeners: Set<RunStreamHandlers>;
  timer: number | null;
}

const sims = new Map<string, Sim>();

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
    }
    if (!job) continue;
    anyWork = true;
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
  if (!anyWork) {
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
    if (sim) sim.listeners.add(handlers);
  }, 60);
  return () => {
    window.clearTimeout(t);
    sim?.listeners.delete(handlers);
  };
}

// ───────────────────────────── Router ─────────────────────────────

function estimate(req: RunRequest): EstimateResult {
  const ids = req.testIds?.length ? req.testIds : suiteTests(req.suiteId ?? 'core');
  const ts = ids.map((id) => tests.find((t) => t.id === id)).filter((t): t is TestDefinition => !!t);
  const repeats = req.repeats ?? 1;
  const warnings: string[] = [];
  const per = req.contestantIds.map((cid) => {
    const c = contestants.find((x) => x.id === cid);
    let cost = 0;
    let jobs = 0;
    for (const t of ts) {
      const n = caseIdsOf(t).length * repeats;
      jobs += n;
      const est = t.estimate ?? { inputTokens: 500, outputTokens: 800 };
      if (c) cost += (n * (est.inputTokens * c.pricing.inputPerM + est.outputTokens * c.pricing.outputPerM)) / 1e6;
    }
    if (c && !c.hasKey) warnings.push(`${c.label}: provider "${c.providerLabel}" has no API key (${META.providers.find((p) => p.id === c.provider)?.apiKeyEnv ?? 'unknown env var'}).`);
    if (c && !c.pricing.verifiedAt) warnings.push(`${c.label}: pricing has not been verified — cost figures may be wrong.`);
    return { contestantId: cid, jobs, estCostUsd: cost };
  });
  const jobs = per.reduce((s, p) => s + p.jobs, 0);
  const calls = ts.reduce((s, t) => s + caseIdsOf(t).length * repeats * (t.estimate?.calls ?? 1), 0) * req.contestantIds.length;
  if (ts.some((t) => t.id.startsWith('long-context'))) warnings.push('Long-context tests send ~92k input tokens per case.');
  return { jobs, calls, perContestant: per, estCostUsd: per.reduce((s, p) => s + p.estCostUsd, 0), fingerprint: (req.suiteId ?? ids.join(',')).length.toString(16).padStart(4, '0') + 'a3f19c0de42b7781', warnings };
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
      if (!b) return tests.map((t) => ({ ...summaryOf(t), source: customIds.has(t.id) ? 'custom' : 'builtin' }));
      const t = tests.find((x) => x.id === b);
      if (!t) throw new ApiError(`Test not found: ${b}`, 404);
      return { definition: t, summary: { ...summaryOf(t), source: customIds.has(t.id) ? 'custom' : 'builtin' }, rendered: renderedOf(t), program: t.kind === 'program' ? PROGRAMS.find((p) => p.id === t.program) : undefined };
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
      return estimate(body as RunRequest);
    case 'POST runs': {
      if (!b) {
        const req = body as RunRequest;
        if (!req.contestantIds?.length) throw new ApiError('Pick at least one contestant', 400);
        const ids = req.testIds?.length ? req.testIds : suiteTests(req.suiteId ?? 'core');
        const id = `run-demo-${Date.now().toString(36)}-${runSeq++}`;
        const spec: RunSpec = { id, name: req.name?.trim() || `Run ${new Date().toLocaleString('en-GB')}`, status: 'running', contestantIds: req.contestantIds, testIds: ids, repeats: req.repeats ?? 1, suiteId: req.testIds?.length ? undefined : req.suiteId, createdAt: new Date().toISOString(), completedFrac: 0, notes: req.notes, concurrency: req.concurrency };
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
        run.results = run.results.filter((r) => r.status !== 'error' && r.status !== 'timeout');
        run.manifest = { ...run.manifest, status: 'running', finishedAt: undefined, error: undefined };
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
          if (!t || t.kind !== 'prompt' || !['human', 'artifact'].includes(t.scorer.type)) continue;
          if (testId && r.testId !== testId) continue;
          out.push({ runId: id, key: r.key, testId: r.testId, caseId: r.caseId, contestantId: r.contestantId, status: r.status, score: r.score, humanScores: r.humanScores });
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
      if (t && t.kind === 'prompt' && t.scorer.type === 'human') {
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
