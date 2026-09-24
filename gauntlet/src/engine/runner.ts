import { EventEmitter } from 'node:events';
import { execSync } from 'node:child_process';
import { loadCategories, loadContestants, loadProviders, loadSettings, hasApiKey, snapshotContestant, contestantConfigHash } from '../core/config.ts';
import { computeCost, emptyUsage } from '../core/cost.ts';
import { createRng } from '../core/rng.ts';
import { HARNESS_VERSION, PROTOCOL_VERSION } from '../core/version.ts';
import { ROOT } from '../core/paths.ts';
import { answerTimeLimitSec, caseScorer, fingerprint, getSuite, loadTests, renderCase, resolveTests, selectedCaseIds, testEstimate, type LoadedTest, type ResolvedTest } from '../core/registry.ts';
import type {
  CaseResult,
  ChatImage,
  Contestant,
  ContestantSnapshot,
  ProgramContext,
  ProgramTest,
  PromptTest,
  ProviderConfig,
  ProviderType,
  ReplayData,
  ResultStatus,
  RunEvent,
  RunManifest,
  RunRequest,
  ScoreDetail,
  TestSnapshot,
  TranscriptEntry,
} from '../core/types.ts';
import { createAdapter } from '../providers/index.ts';
import { manualEvents } from '../providers/manual.ts';
import { PROGRAMS } from '../programs/index.ts';
import { scoreResponse, type JudgePanel } from '../scoring/index.ts';
import { browserAvailable } from '../scoring/browser.ts';
import { Semaphore } from './semaphore.ts';
import { callWithRetry, createRecorder, OutOfTimeError, type CallPolicy, type CallTarget } from './recorder.ts';
import { appendResult, createRunFolder, listRunIds, newRunId, readManifest, readResults, saveArtifact, writeManifest } from './store.ts';
import { caseImageRefs, caseImageSizes, estimateImageTokens, loadTestImage, supportsVision, testBaseDir } from '../core/vision.ts';

interface Job {
  key: string;
  contestant: ContestantSnapshot;
  test: LoadedTest;
  caseId: string;
  repeat: number;
  seed?: number;
}

interface ActiveRun {
  manifest: RunManifest;
  controller: AbortController;
  events: EventEmitter;
  progress: { completed: number; total: number; costUsd: number };
  done: Promise<void>;
}

const active = new Map<string, ActiveRun>();

export function isActive(runId: string): boolean {
  return active.has(runId);
}

export function activeProgress(runId: string): ActiveRun['progress'] | null {
  return active.get(runId)?.progress ?? null;
}

export function subscribe(runId: string, listener: (e: RunEvent) => void): () => void {
  const run = active.get(runId);
  if (!run) return () => {};
  run.events.on('event', listener);
  return () => run.events.off('event', listener);
}

function gitCommit(): string | undefined {
  try {
    return execSync('git rev-parse --short HEAD', { cwd: ROOT, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim() || undefined;
  } catch {
    return undefined;
  }
}

function now(): string {
  return new Date().toISOString();
}

export function jobKey(contestantId: string, testId: string, caseId: string, repeat: number): string {
  return `${contestantId}::${testId}::${caseId}::r${repeat}`;
}

function seedOf(caseId: string): number | undefined {
  const m = caseId.match(/^seed-(\d+)$/);
  return m ? Number(m[1]) : undefined;
}

/** Interleave jobs so every contestant works on the same test/case at the same time (fair + great for the live view). */
function buildJobs(tests: Array<LoadedTest & { caseFilter?: string[] }>, contestants: ContestantSnapshot[], repeats: number): Job[] {
  const jobs: Job[] = [];
  for (const test of tests) {
    for (const caseId of selectedCaseIds(test)) {
      for (let repeat = 0; repeat < repeats; repeat++) {
        for (const c of contestants) {
          jobs.push({ key: jobKey(c.id, test.definition.id, caseId, repeat), contestant: c, test, caseId, repeat, seed: seedOf(caseId) });
        }
      }
    }
  }
  return jobs;
}

// ─────────────────────────────────────────────────────────────────────────────
// Planning & estimates
// ─────────────────────────────────────────────────────────────────────────────

export interface RunPlan {
  tests: ResolvedTest[];
  contestants: Contestant[];
  /** Judges that will actually be used (configured and with an API key). */
  judges: Contestant[];
  /** All configured judges, used for cost estimates even before their keys are set. */
  plannedJudges: Contestant[];
  repeats: number;
  concurrency: number;
  temperature: number;
  maxCostUsd?: number;
  judgeExcludeSameVendor: boolean;
  forceVision: boolean;
  fingerprint: string;
  warnings: string[];
}

/** Case ids of a test that show the model an image (programs flagged `requiresVision`: every case). */
function visionCaseIds(t: LoadedTest & { caseFilter?: string[] }): Set<string> {
  const d = t.definition;
  if (d.kind === 'program') return new Set(PROGRAMS[d.program]?.requiresVision ? selectedCaseIds(t) : []);
  return new Set(d.cases.filter((c) => caseImageRefs(c).length > 0).map((c) => c.id));
}

function needsVision(t: LoadedTest & { caseFilter?: string[] }): boolean {
  const ids = visionCaseIds(t);
  return selectedCaseIds(t).some((id) => ids.has(id));
}

function usesJudges(t: LoadedTest): boolean {
  const d = t.definition;
  if (d.kind !== 'prompt') return false;
  return d.cases.some((c) => {
    const s = caseScorer(d, c);
    return s.type === 'judge' || s.type === 'judge-classify' || (s.type === 'artifact' && (s.judgeWeight ?? 0) > 0);
  });
}

/**
 * A judge is the configured model with the judge-specific effort applied. It gets
 * its own id so it never shares an adapter/config with the same model competing.
 */
export function asJudge(c: Contestant, effort: 'low' | 'medium' | 'high' | null): Contestant {
  if (!effort || !c.options?.effort) return { ...c, id: `${c.id}@judge` };
  return { ...c, id: `${c.id}@judge`, options: { ...c.options, effort } };
}

export function planRun(req: RunRequest): RunPlan {
  const settings = loadSettings();
  const all = loadContestants();
  const providers = loadProviders();
  const tests = resolveTests({ suiteId: req.suiteId, testIds: req.testIds });
  if (tests.length === 0) throw new Error('No tests selected');
  if (!req.contestantIds?.length) throw new Error('Select at least one model');
  const contestants = req.contestantIds.map((id) => {
    const c = all.find((x) => x.id === id);
    if (!c) throw new Error(`Unknown model "${id}"`);
    return c;
  });
  const judgeIds = req.judgeIds ?? settings.judges;
  const warnings: string[] = [];
  const providerOf = (c: Contestant) => providers.find((p) => p.id === c.provider);
  for (const c of contestants) {
    const p = providerOf(c);
    if (!p) warnings.push(`${c.label}: provider "${c.provider}" is not configured`);
    else if (!hasApiKey(p)) warnings.push(`${c.label}: ${p.apiKeyEnv} is not set — its jobs will fail`);
    if (!c.pricing.verifiedAt) warnings.push(`${c.label}: pricing is unverified — cost figures may be wrong`);
  }
  const judgeNeeded = tests.some(usesJudges);
  const judges: Contestant[] = [];
  const plannedJudges: Contestant[] = [];
  if (judgeNeeded) {
    for (const id of judgeIds) {
      const j = all.find((x) => x.id === id);
      if (!j) {
        warnings.push(`Judge "${id}" is not a configured model — skipped`);
        continue;
      }
      plannedJudges.push(asJudge(j, settings.judgeEffort));
      const p = providerOf(j);
      if (!p || !hasApiKey(p)) {
        warnings.push(`Judge ${j.label}: no API key — skipped (panel continues without it)`);
        continue;
      }
      judges.push(asJudge(j, settings.judgeEffort));
    }
    if (judges.length === 0) warnings.push('No usable judge models: judge-scored tests will error. Configure judges in config/settings.json.');
    else if (new Set(judges.map((j) => j.vendor)).size === 1) warnings.push(`All judges are from ${judges[0]!.vendor}: consider a cross-vendor panel to avoid self-preference bias`);
  }
  const visionTests = tests.filter((t) => needsVision(t));
  if (visionTests.length) {
    const blind = contestants.filter((c) => !supportsVision(c, providerOf(c)?.type));
    if (blind.length && req.forceVision) warnings.push(`${blind.map((c) => c.label).join(', ')}: not marked as accepting images, but image cases are forced on — the API may reject them`);
    else if (blind.length) warnings.push(`${blind.map((c) => c.label).join(', ')}: no image input — ${visionTests.length} vision test(s) will be skipped for ${blind.length === 1 ? 'this model' : 'these models'} (not scored as 0, left out of the means)`);
  }
  const manual = contestants.filter((c) => providerOf(c)?.type === 'manual');
  if (manual.length) warnings.push(`${manual.map((c) => c.label).join(', ')}: manual contestant — every prompt waits in the Manual Inbox for you to paste the model's reply`);
  if (req.maxCostUsd !== undefined && !(req.maxCostUsd > 0)) throw new Error('maxCostUsd must be a positive number');
  const humanTests = tests.filter((t) => t.definition.kind === 'prompt' && t.definition.cases.some((c) => caseScorer(t.definition as PromptTest, c).type === 'human'));
  if (humanTests.length) warnings.push(`${humanTests.length} test(s) need human scoring in Blind Review before they count`);
  return {
    tests,
    contestants,
    judges,
    plannedJudges,
    repeats: Math.max(1, Math.min(20, req.repeats ?? getSuite(req.suiteId ?? 'core')?.repeats ?? settings.defaultRepeats)),
    concurrency: Math.max(1, Math.min(64, req.concurrency ?? settings.defaultConcurrency)),
    temperature: req.temperature ?? settings.temperature,
    maxCostUsd: req.maxCostUsd,
    judgeExcludeSameVendor: settings.judgeExcludeSameVendor,
    forceVision: Boolean(req.forceVision),
    fingerprint: fingerprint(tests),
    warnings,
  };
}

export interface TestCostEstimate {
  testId: string;
  name: string;
  category: string;
  cases: number;
  /** Estimated USD per contestant for this test (all cases × repeats). */
  perContestant: Record<string, number>;
  judgeUsd: number;
  /** Where the token figures came from. */
  basis: 'measured' | 'measured-other-models' | 'definition';
}

export interface RunEstimate {
  jobs: number;
  calls: number;
  perContestant: Array<{ contestantId: string; jobs: number; estCostUsd: number; estCostUsdHigh: number; manual: boolean }>;
  perTest: TestCostEstimate[];
  judgeCostUsd: number;
  /** Central estimate (contestants + judges). */
  estCostUsd: number;
  /** Conservative upper estimate: +20% where measured, +60% where only the test's own estimate is known. */
  estCostUsdHigh: number;
  fingerprint: string;
  warnings: string[];
}

interface TokenStats {
  input: number;
  output: number;
  calls: number;
  judgeUsd: number;
  n: number;
}

/**
 * Average per-case token usage observed in previous runs, keyed by test hash
 * (so only the identical test version counts) and contestant config hash.
 * Estimates therefore get more accurate every time you run something.
 */
function observedTokenStats(): Map<string, { byContestant: Map<string, TokenStats>; all: TokenStats }> {
  const out = new Map<string, { byContestant: Map<string, TokenStats>; all: TokenStats }>();
  const add = (t: TokenStats, r: CaseResult) => {
    t.input += r.metrics.inputTokens + r.metrics.cachedInputTokens;
    t.output += r.metrics.outputTokens;
    t.calls += r.metrics.apiCalls;
    t.judgeUsd += r.metrics.judgeCostUsd;
    t.n++;
  };
  // Only real API models calibrate estimates (the random baseline and manual entries have no real token usage).
  const providers = loadProviders();
  const simulated = new Set(loadContestants().filter((c) => ['mock', 'manual'].includes(providers.find((p) => p.id === c.provider)?.type ?? '')).map((c) => c.id));
  for (const runId of listRunIds()) {
    for (const r of readResults(runId)) {
      if (r.status === 'error' || r.status === 'cancelled' || r.metrics.apiCalls === 0) continue;
      if (simulated.has(r.contestantId) || r.transcript.some((e) => e.rawStopReason === 'manual')) continue;
      let entry = out.get(r.testHash);
      if (!entry) out.set(r.testHash, (entry = { byContestant: new Map(), all: { input: 0, output: 0, calls: 0, judgeUsd: 0, n: 0 } }));
      add(entry.all, r);
      const k = r.contestantHash;
      let c = entry.byContestant.get(k);
      if (!c) entry.byContestant.set(k, (c = { input: 0, output: 0, calls: 0, judgeUsd: 0, n: 0 }));
      add(c, r);
    }
  }
  return out;
}

export async function estimateRun(req: RunRequest): Promise<RunEstimate> {
  const plan = planRun(req);
  const providers = loadProviders();
  const observed = observedTokenStats();
  let calls = 0;
  let judgeCost = 0;
  let high = 0;
  const perTest: TestCostEstimate[] = plan.tests.map((t) => ({
    testId: t.definition.id,
    name: t.definition.name,
    category: t.definition.category,
    cases: selectedCaseIds(t).length,
    perContestant: {},
    judgeUsd: 0,
    basis: 'definition',
  }));
  const perContestant = plan.contestants.map((c) => {
    const providerType = providers.find((p) => p.id === c.provider)?.type;
    const isManual = providerType === 'manual';
    const sees = plan.forceVision || supportsVision(c, providerType);
    const cfg = contestantConfigHash(c);
    let jobs = 0;
    let cost = 0;
    let costHigh = 0;
    plan.tests.forEach((t, i) => {
      const selected = selectedCaseIds(t);
      const visionIds = visionCaseIds(t);
      // Skipped image cases cost nothing; the ones that run pay for their image tokens (see estimateImageTokens).
      const runnable = sees ? selected : selected.filter((id) => !visionIds.has(id));
      const n = runnable.length * plan.repeats;
      let imageTokens = 0;
      if (sees && t.definition.kind === 'prompt' && visionIds.size) {
        const base = testBaseDir(t.file);
        const d = t.definition;
        for (const id of runnable) {
          const tc = d.cases.find((x) => x.id === id);
          if (!tc) continue;
          for (const size of caseImageSizes(tc, base)) imageTokens += estimateImageTokens(size.width, size.height, providerType, c.model);
        }
        imageTokens /= Math.max(1, runnable.length);
      }
      const def = testEstimate(t.definition);
      const obs = observed.get(t.hash);
      const mine = obs?.byContestant.get(cfg);
      let input = def.inputTokens + imageTokens;
      let output = def.outputTokens;
      let perCaseCalls = def.calls;
      let basis: TestCostEstimate['basis'] = 'definition';
      if (mine && mine.n > 0) {
        input = mine.input / mine.n;
        output = mine.output / mine.n;
        perCaseCalls = mine.calls / mine.n;
        basis = 'measured';
      } else if (obs && obs.all.n > 0) {
        // Other models' measured usage: keep the measured input, and use the larger of measured/declared output (models differ most in output).
        input = obs.all.input / obs.all.n;
        output = Math.max(def.outputTokens, obs.all.output / obs.all.n);
        perCaseCalls = obs.all.calls / obs.all.n;
        basis = 'measured-other-models';
      }
      const testCost = isManual ? 0 : (n * (input * c.pricing.inputPerM + output * c.pricing.outputPerM)) / 1e6;
      jobs += selected.length * plan.repeats;
      calls += n * perCaseCalls;
      cost += testCost;
      costHigh += testCost * (basis === 'measured' ? 1.2 : 1.6);
      perTest[i]!.perContestant[c.id] = Math.round(testCost * 10000) / 10000;
      if (perTest[i]!.basis !== 'measured') perTest[i]!.basis = basis;
      const judgePool = plan.plannedJudges;
      if (usesJudges(t) && judgePool.length) {
        const panel = plan.judgeExcludeSameVendor && judgePool.some((j) => j.vendor !== c.vendor) ? judgePool.filter((j) => j.vendor !== c.vendor) : judgePool;
        let jc = 0;
        if (obs && obs.all.judgeUsd > 0) jc = (obs.all.judgeUsd / obs.all.n) * n * (panel.length / Math.max(1, judgePool.length));
        // Judge input = fixed prompt (~2.5k tokens incl. rubric/reference) + the visible part of the reply (reasoning
        // tokens are never shown to judges; assume half of the output is visible). Judge output ≈ 1.5k at medium effort.
        else for (const j of panel) jc += (n * ((2500 + Math.min(output, 40000) * 0.5) * j.pricing.inputPerM + 1500 * j.pricing.outputPerM)) / 1e6;
        calls += n * panel.length;
        judgeCost += jc;
        high += jc * 1.5;
        perTest[i]!.judgeUsd = Math.round((perTest[i]!.judgeUsd + jc) * 10000) / 10000;
      }
    });
    high += costHigh;
    return { contestantId: c.id, jobs, estCostUsd: Math.round(cost * 10000) / 10000, estCostUsdHigh: Math.round(costHigh * 10000) / 10000, manual: isManual };
  });
  const warnings = plan.warnings.slice();
  const needsBrowser = plan.tests.some((t) => t.definition.kind === 'prompt' && t.definition.scorer.type === 'artifact');
  if (needsBrowser && !(await browserAvailable())) warnings.push('Headless Chromium not available: browser checks for game/SVG tests will be skipped');
  const total = perContestant.reduce((s, p) => s + p.estCostUsd, 0) + judgeCost;
  if (plan.maxCostUsd !== undefined && total > plan.maxCostUsd) warnings.push(`Estimated cost ${total.toFixed(2)} USD exceeds your cap of ${plan.maxCostUsd.toFixed(2)} USD: the run will stop early when the cap is reached`);
  return {
    jobs: perContestant.reduce((s, p) => s + p.jobs, 0),
    calls: Math.round(calls),
    perContestant,
    perTest,
    judgeCostUsd: Math.round(judgeCost * 10000) / 10000,
    estCostUsd: Math.round(total * 10000) / 10000,
    estCostUsdHigh: Math.round(high * 10000) / 10000,
    fingerprint: plan.fingerprint,
    warnings,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Execution
// ─────────────────────────────────────────────────────────────────────────────

export async function startRun(req: RunRequest): Promise<string> {
  const plan = planRun(req);
  const id = newRunId();
  const tests: TestSnapshot[] = plan.tests.map((t) => ({
    id: t.definition.id,
    version: t.definition.version,
    hash: t.hash,
    name: t.definition.name,
    category: t.definition.category,
    kind: t.definition.kind,
    caseIds: selectedCaseIds(t),
    weight: t.weight,
  }));
  const contestants = plan.contestants.map(snapshotContestant);
  const manifest: RunManifest = {
    id,
    name: req.name?.trim() || `${plan.contestants.length} models × ${plan.tests.length} tests`,
    status: 'queued',
    createdAt: now(),
    harnessVersion: HARNESS_VERSION,
    gitCommit: gitCommit(),
    node: process.version,
    platform: `${process.platform}-${process.arch}`,
    suiteId: req.testIds?.length ? undefined : (req.suiteId ?? 'core'),
    suiteVersion: req.testIds?.length ? undefined : getSuite(req.suiteId ?? 'core')?.version,
    fingerprint: plan.fingerprint,
    tests,
    contestants,
    judges: plan.judges.map(snapshotContestant),
    settings: {
      repeats: plan.repeats,
      concurrency: plan.concurrency,
      temperature: plan.temperature,
      protocolVersion: PROTOCOL_VERSION,
      maxCostUsd: plan.maxCostUsd,
      judgeExcludeSameVendor: plan.judgeExcludeSameVendor,
      ...(plan.forceVision ? { forceVision: true } : {}),
    },
    totalJobs: tests.reduce((s, t) => s + t.caseIds.length, 0) * plan.repeats * contestants.length,
    notes: req.notes,
  };
  createRunFolder(manifest);
  launch(manifest, plan.tests, new Set());
  return id;
}

export function resumeRun(runId: string, opts: { maxCostUsd?: number | null } = {}): void {
  if (active.has(runId)) throw new Error('Run is already active');
  const manifest = readManifest(runId);
  if (!manifest) throw new Error('Run not found');
  if (opts.maxCostUsd !== undefined) manifest.settings.maxCostUsd = opts.maxCostUsd === null ? undefined : opts.maxCostUsd;
  const loaded = loadTests();
  const tests: Array<LoadedTest & { caseFilter?: string[] }> = [];
  const changed: string[] = [];
  for (const snap of manifest.tests) {
    const t = loaded.find((x) => x.definition.id === snap.id);
    if (!t || t.hash !== snap.hash) changed.push(snap.id);
    else tests.push({ ...t, caseFilter: snap.caseIds });
  }
  if (changed.length) throw new Error(`Cannot resume: these tests changed since the run started (results would not be comparable): ${changed.join(', ')}`);
  const done = new Set(readResults(runId).filter((r) => r.status !== 'error' && r.status !== 'cancelled').map((r) => r.key));
  manifest.error = undefined;
  launch(manifest, tests, done);
}

export function cancelRun(runId: string): boolean {
  const run = active.get(runId);
  if (!run) return false;
  run.controller.abort();
  return true;
}

/** Mark runs left "running" by a crashed process as interrupted so they can be resumed. */
export function recoverInterruptedRuns(ids: string[]): void {
  for (const id of ids) {
    if (active.has(id)) continue;
    const m = readManifest(id);
    if (m && (m.status === 'running' || m.status === 'queued')) {
      m.status = 'interrupted';
      writeManifest(m);
    }
  }
}

function launch(manifest: RunManifest, tests: Array<LoadedTest & { caseFilter?: string[] }>, skip: Set<string>): void {
  const settings = loadSettings();
  const providers = loadProviders();
  const controller = new AbortController();
  const events = new EventEmitter();
  events.setMaxListeners(100);
  const isManual = (c: Contestant) => providers.find((p) => p.id === c.provider)?.type === 'manual';
  const allJobs = buildJobs(tests, manifest.contestants, manifest.settings.repeats).filter((j) => !skip.has(j.key));
  // Manual (copy & paste) jobs get their own queue so a person working through the inbox never blocks API models.
  const apiJobs = allJobs.filter((j) => !isManual(j.contestant));
  const manualJobs = allJobs.filter((j) => isManual(j.contestant));
  const previous = readResults(manifest.id).filter((r) => skip.has(r.key));
  const progress = {
    completed: previous.length,
    total: manifest.totalJobs,
    costUsd: previous.reduce((s, r) => s + r.metrics.costUsd + r.metrics.judgeCostUsd, 0),
  };
  const run: ActiveRun = { manifest, controller, events, progress, done: Promise.resolve() };
  active.set(manifest.id, run);
  const emit = (e: RunEvent) => events.emit('event', e);
  const onManualRequest = (request: import('../core/types.ts').ManualRequest) => {
    if (request.runId === manifest.id) emit({ type: 'manual.request', runId: manifest.id, request });
  };
  const onManualResolved = (request: import('../core/types.ts').ManualRequest) => {
    if (request.runId === manifest.id) emit({ type: 'manual.resolved', runId: manifest.id, requestId: request.id });
  };
  manualEvents.on('request', onManualRequest);
  manualEvents.on('resolved', onManualResolved);

  run.done = (async () => {
    manifest.status = 'running';
    manifest.startedAt ??= now();
    manifest.finishedAt = undefined;
    writeManifest(manifest);
    emit({ type: 'run.status', runId: manifest.id, status: 'running', at: now() });

    const semaphores = new Map<string, Semaphore>();
    const semFor = (p: ProviderConfig) => {
      let s = semaphores.get(p.id);
      if (!s) semaphores.set(p.id, (s = new Semaphore(p.maxConcurrency ?? 8)));
      return s;
    };
    const targets = new Map<string, CallTarget | Error>();
    const targetFor = (c: Contestant): CallTarget => {
      let t = targets.get(c.id);
      if (!t) {
        try {
          const p = providers.find((x) => x.id === c.provider);
          if (!p) throw new Error(`Provider "${c.provider}" is not configured`);
          t = { contestant: c, adapter: createAdapter(c, p), semaphore: semFor(p) };
        } catch (e) {
          t = e as Error;
        }
        targets.set(c.id, t);
      }
      if (t instanceof Error) throw t;
      return t;
    };
    const policy: CallPolicy = { maxRetries: settings.maxRetries, temperature: manifest.settings.temperature, defaultMaxOutputTokens: settings.defaultMaxOutputTokens };

    // Throttled streaming deltas (≈10 events/s per job).
    const deltas = new Map<string, { contestantId: string; text: string; label?: string }>();
    const flush = setInterval(() => {
      for (const [key, d] of deltas) emit({ type: 'job.delta', runId: manifest.id, key, contestantId: d.contestantId, text: d.text, label: d.label });
      deltas.clear();
    }, 100);

    const cap = manifest.settings.maxCostUsd;
    let budgetHit = false;
    const queues = [
      { jobs: apiJobs, cursor: 0, workers: Math.min(manifest.settings.concurrency, Math.max(1, apiJobs.length)) },
      { jobs: manualJobs, cursor: 0, workers: Math.min(200, manualJobs.length) },
    ];
    const worker = async (queue: (typeof queues)[number]) => {
      while (!controller.signal.aborted) {
        if (cap !== undefined && progress.costUsd >= cap) {
          if (!budgetHit) {
            budgetHit = true;
            manifest.error = `Budget cap of $${cap.toFixed(2)} reached ($${progress.costUsd.toFixed(4)} spent). Resume with a higher cap to finish.`;
            emit({ type: 'log', runId: manifest.id, level: 'warn', message: manifest.error, at: now() });
          }
          return;
        }
        const job = queue.jobs[queue.cursor++];
        if (!job) return;
        const result = await executeJob(job, {
          isManual,
          providerTypeOf: (c) => providers.find((p) => p.id === c.provider)?.type,
          manifest,
          settings,
          policy,
          targetFor,
          signal: controller.signal,
          emit,
          onDelta: (text, label) => {
            const d = deltas.get(job.key);
            if (d) {
              d.text += text;
              d.label = label;
            } else deltas.set(job.key, { contestantId: job.contestant.id, text, label });
          },
        });
        // Flush this job's last streamed text before announcing that it finished.
        const pending = deltas.get(job.key);
        if (pending) {
          emit({ type: 'job.delta', runId: manifest.id, key: job.key, contestantId: pending.contestantId, text: pending.text, label: pending.label });
          deltas.delete(job.key);
        }
        if (result.status === 'cancelled' && controller.signal.aborted) return;
        appendResult(result);
        progress.completed++;
        progress.costUsd += result.metrics.costUsd + result.metrics.judgeCostUsd;
        emit({
          type: 'job.finished',
          runId: manifest.id,
          key: result.key,
          contestantId: result.contestantId,
          testId: result.testId,
          caseId: result.caseId,
          repeat: result.repeat,
          status: result.status,
          score: result.score,
          summary: result.summary,
          metrics: result.metrics,
          at: now(),
        });
        emit({ type: 'run.progress', runId: manifest.id, completed: progress.completed, total: progress.total, costUsd: progress.costUsd, at: now() });
      }
    };

    try {
      await Promise.all(queues.flatMap((q) => Array.from({ length: q.workers }, () => worker(q))));
      manifest.status = controller.signal.aborted || budgetHit ? 'cancelled' : 'completed';
    } catch (err) {
      manifest.status = 'failed';
      manifest.error = (err as Error).message;
      emit({ type: 'log', runId: manifest.id, level: 'error', message: (err as Error).message, at: now() });
    } finally {
      clearInterval(flush);
      manualEvents.off('request', onManualRequest);
      manualEvents.off('resolved', onManualResolved);
      manifest.finishedAt = now();
      writeManifest(manifest);
      emit({ type: 'run.status', runId: manifest.id, status: manifest.status, at: now(), error: manifest.error });
      active.delete(manifest.id);
      events.emit('end');
    }
  })();
}

/** Wait for an active run to finish (CLI). */
export async function waitForRun(runId: string): Promise<void> {
  await active.get(runId)?.done;
}

interface JobEnv {
  isManual: (c: Contestant) => boolean;
  providerTypeOf: (c: Contestant) => ProviderType | undefined;
  manifest: RunManifest;
  settings: ReturnType<typeof loadSettings>;
  policy: CallPolicy;
  targetFor: (c: Contestant) => CallTarget;
  signal: AbortSignal;
  emit: (e: RunEvent) => void;
  onDelta: (text: string, label?: string) => void;
}

function clamp01(n: number): number {
  return Number.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0;
}

/** A vision case for a model without image input: recorded as skipped (not scored, excluded from means). */
function skippedResult(job: Job, manifest: RunManifest): CaseResult {
  const at = now();
  return {
    key: job.key,
    runId: manifest.id,
    contestantId: job.contestant.id,
    testId: job.test.definition.id,
    testVersion: job.test.definition.version,
    testHash: job.test.hash,
    contestantHash: job.contestant.configHash ?? contestantConfigHash(job.contestant),
    caseId: job.caseId,
    repeat: job.repeat,
    seed: job.seed,
    status: 'skipped',
    score: null,
    passed: null,
    summary: 'Skipped — model has no image input',
    scoreDetail: { notes: 'This case shows the model an image. The model is not marked as accepting images (vision: true in config/models.json), so the case was not sent and is left out of every mean. Start the run with "Force image cases" to send it anyway.' },
    metrics: { wallMs: 0, ttftMs: null, apiCalls: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0, judgeCostUsd: 0, outputTokensPerSec: null, retries: 0, responseChars: 0 },
    transcript: [],
    artifacts: [],
    startedAt: at,
    finishedAt: at,
  };
}

async function executeJob(job: Job, env: JobEnv): Promise<CaseResult> {
  const { manifest, settings } = env;
  const def = job.test.definition;
  if (!manifest.settings.forceVision && !supportsVision(job.contestant, env.providerTypeOf(job.contestant)) && visionCaseIds(job.test).has(job.caseId)) {
    env.emit({ type: 'job.started', runId: manifest.id, key: job.key, contestantId: job.contestant.id, testId: def.id, caseId: job.caseId, repeat: job.repeat, at: now() });
    return skippedResult(job, manifest);
  }
  const startedAt = new Date();
  const controller = new AbortController();
  const onRunAbort = () => controller.abort();
  env.signal.addEventListener('abort', onRunAbort, { once: true });
  let target: CallTarget | null = null;
  let targetError: Error | null = null;
  try {
    target = env.targetFor(job.contestant);
  } catch (e) {
    targetError = e as Error;
  }
  const manual = env.isManual(job.contestant);
  // Humans pasting replies get a week; API models get the test's time limit.
  const timeLimitMs = manual ? 7 * 24 * 3600 * 1000 : (def.timeLimitSec ?? settings.defaultTimeLimitSec) * 1000;
  // Time-pressure tests: a per-answer limit shown in the prompt and enforced per model call (API models only).
  const answerSec = def.kind === 'prompt' && !manual ? (() => {
    const tc = def.cases.find((c) => c.id === job.caseId);
    return tc ? answerTimeLimitSec(def, tc) : undefined;
  })() : undefined;
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeLimitMs);

  env.emit({ type: 'job.started', runId: manifest.id, key: job.key, contestantId: job.contestant.id, testId: def.id, caseId: job.caseId, repeat: job.repeat, at: now() });

  const artifacts: CaseResult['artifacts'] = [];
  const save = (name: string, kind: CaseResult['artifacts'][number]['kind'], content: string | Buffer) => {
    const ref = saveArtifact(manifest.id, job.key, name, kind, content);
    const i = artifacts.findIndex((a) => a.name === ref.name);
    if (i >= 0) artifacts[i] = ref;
    else artifacts.push(ref);
    return ref;
  };

  let status: ResultStatus = 'ok';
  let score: number | null = null;
  let passed: boolean | null = null;
  let summary = '';
  let detail: ScoreDetail = {};
  let replay: ReplayData | undefined;
  let error: string | undefined;

  let recorder: ReturnType<typeof createRecorder> | null = null;
  try {
    if (targetError || !target) throw targetError ?? new Error('No target');
    recorder = createRecorder({
      target,
      callContext: { runId: manifest.id, key: job.key, testId: def.id, testName: def.name, caseId: job.caseId },
      policy: env.policy,
      signal: controller.signal,
      maxOutputTokens: def.maxOutputTokens ?? settings.defaultMaxOutputTokens,
      onDelta: env.onDelta,
      attemptLimitMs: answerSec ? answerSec * 1000 : undefined,
      onCall: (label) => env.emit({ type: 'job.step', runId: manifest.id, key: job.key, contestantId: job.contestant.id, label }),
      onRetry: (attempt, wait, err) =>
        env.emit({ type: 'log', runId: manifest.id, level: 'warn', message: `${job.contestant.label} · ${def.id}/${job.caseId}: retry ${attempt} in ${(wait / 1000).toFixed(1)}s (${err.message.slice(0, 160)})`, at: now() }),
    });
    const rec = recorder;
    const judges = createJudgePanel({
      judges: selectJudges(manifest.judges, job.contestant, manifest.settings.judgeExcludeSameVendor ?? true),
      targetFor: env.targetFor,
      policy: env.policy,
      signal: controller.signal,
      record: (entry) => rec.recordJudge(entry),
    });

    if (def.kind === 'prompt') {
      const tc = def.cases.find((c) => c.id === job.caseId);
      if (!tc) throw new Error(`Case ${job.caseId} no longer exists`);
      const rendered = renderCase(def, tc);
      const imageBase = testBaseDir(job.test.file);
      const imagesByTurn = new Map<number, ChatImage[]>();
      for (const ref of caseImageRefs(tc)) imagesByTurn.set(ref.turn, [...(imagesByTurn.get(ref.turn) ?? []), loadTestImage(imageBase, ref.file)]);
      const chat = rec.handle.chat(rendered.system);
      let reply = null as Awaited<ReturnType<typeof chat.send>> | null;
      for (let i = 0; i < rendered.turns.length; i++) {
        reply = await chat.send(rendered.turns[i]!, { label: rendered.turns.length > 1 ? `turn ${i + 1}` : 'response', images: imagesByTurn.get(i) });
      }
      const history = chat.history.slice(0, -1);
      const taskText = [rendered.system ? `[System prompt]\n${rendered.system}` : '', ...history.map((m) => `[${m.role === 'user' ? 'User' : 'Assistant'}]\n${m.content}`)].filter(Boolean).join('\n\n');
      if (reply!.stopReason === 'refusal' && !reply!.text.trim()) {
        status = 'refusal';
        score = 0;
        passed = false;
        summary = 'Refused to answer';
      } else {
        const outcome = await scoreResponse({
          scorer: caseScorer(def, tc),
          expected: tc.expected,
          response: reply!.text,
          stopReason: reply!.stopReason,
          taskText,
          judges,
          saveArtifact: save,
          signal: controller.signal,
        });
        score = outcome.score === null ? null : clamp01(outcome.score);
        passed = outcome.passed;
        summary = outcome.summary;
        detail = outcome.detail;
        if (outcome.pendingHuman) status = 'pending-human';
        if (reply!.stopReason === 'max_tokens') detail.notes = `${detail.notes ? detail.notes + ' · ' : ''}Response hit the output token limit`;
      }
      if (answerSec) {
        detail.timeLimitSec = answerSec;
        detail.responseMs = reply!.totalMs;
      }
    } else {
      const pdef = def as ProgramTest;
      const program = PROGRAMS[pdef.program];
      if (!program) throw new Error(`Program "${pdef.program}" is not registered`);
      const seed = job.seed ?? 0;
      const ctx: ProgramContext = {
        seed,
        rng: createRng(seed),
        config: { ...(program.defaults ?? {}), ...(pdef.config ?? {}) },
        model: rec.handle,
        maxOutputTokens: def.maxOutputTokens ?? settings.defaultMaxOutputTokens,
        signal: controller.signal,
        artifact: (name, kind, content) => {
          save(name, kind, content);
        },
      };
      const out = await program.run(ctx);
      score = clamp01(out.score);
      passed = out.passed ?? score >= 0.5;
      summary = out.summary;
      detail = { ...out.detail };
      replay = out.replay;
    }
  } catch (err) {
    if (err instanceof OutOfTimeError && !env.signal.aborted) {
      status = 'timeout';
      score = 0;
      passed = false;
      summary = `Out of time: no answer within ${Math.round(err.limitMs / 1000)} s`;
      detail = { outOfTime: true, timeLimitSec: Math.round(err.limitMs / 1000), responseMs: err.elapsedMs, formatOk: false };
    } else if (timedOut) {
      status = 'timeout';
      score = 0;
      passed = false;
      summary = `Timed out after ${Math.round(timeLimitMs / 1000)} s`;
    } else if (env.signal.aborted) {
      status = 'cancelled';
      summary = 'Cancelled';
    } else {
      status = 'error';
      error = (err as Error).message;
      summary = `Error: ${error.slice(0, 120)}`;
    }
  } finally {
    clearTimeout(timer);
    env.signal.removeEventListener('abort', onRunAbort);
  }

  const rec = recorder;
  const usage = rec?.usage ?? emptyUsage();
  const wallMs = Date.now() - startedAt.getTime();
  return {
    key: job.key,
    runId: manifest.id,
    contestantId: job.contestant.id,
    testId: def.id,
    testVersion: def.version,
    testHash: job.test.hash,
    contestantHash: job.contestant.configHash ?? contestantConfigHash(job.contestant),
    caseId: job.caseId,
    repeat: job.repeat,
    seed: job.seed,
    status,
    score,
    passed,
    summary,
    scoreDetail: detail,
    metrics: {
      wallMs,
      ttftMs: rec?.firstTtftMs ?? null,
      apiCalls: rec?.apiCalls ?? 0,
      inputTokens: usage.inputTokens,
      outputTokens: usage.outputTokens,
      reasoningTokens: usage.reasoningTokens,
      cachedInputTokens: usage.cachedInputTokens,
      costUsd: Math.round((rec?.costUsd ?? 0) * 1e8) / 1e8,
      judgeCostUsd: Math.round((rec?.judgeCostUsd ?? 0) * 1e8) / 1e8,
      outputTokensPerSec: rec && rec.generationMs > 0 && usage.outputTokens > 0 ? Math.round((usage.outputTokens / (rec.generationMs / 1000)) * 10) / 10 : null,
      retries: rec?.retries ?? 0,
      responseChars: rec?.responseChars ?? 0,
    },
    transcript: rec?.transcript ?? [],
    artifacts,
    replay,
    error,
    startedAt: startedAt.toISOString(),
    finishedAt: now(),
  };
}

/**
 * Judges for one case. With `excludeSameVendor`, a judge never grades a model
 * from its own vendor (self-preference bias) as long as another judge is
 * available; a model never grades itself.
 */
export function selectJudges(judges: ContestantSnapshot[], contestant: Contestant, excludeSameVendor: boolean): ContestantSnapshot[] {
  const notSelf = judges.filter((j) => j.model !== contestant.model || j.provider !== contestant.provider);
  const pool = notSelf.length ? notSelf : judges;
  if (!excludeSameVendor) return pool;
  const otherVendors = pool.filter((j) => j.vendor.toLowerCase() !== contestant.vendor.toLowerCase());
  return otherVendors.length ? otherVendors : pool;
}

export function createJudgePanel(opts: {
  judges: Contestant[];
  targetFor: (c: Contestant) => CallTarget;
  policy: CallPolicy;
  signal: AbortSignal;
  record: (entry: TranscriptEntry) => void;
}): JudgePanel {
  const { judges, signal } = opts;
  return {
    ids: judges.map((j) => j.id),
    async ask(system, user, label) {
      if (judges.length === 0) return [];
      return Promise.all(
        judges.map(async (j) => {
          const started = Date.now();
          try {
            const target = opts.targetFor(j);
            const r = await callWithRetry(target, { system, messages: [{ role: 'user', content: user }], maxOutputTokens: 16000, temperature: 0 }, opts.policy, signal);
            const cost = computeCost(r.usage, j.pricing);
            opts.record({
              label: `${label} · ${j.label}`,
              judge: true,
              system,
              messages: [{ role: 'user', content: user }],
              response: r.text,
              usage: r.usage,
              ttftMs: r.ttftMs,
              totalMs: r.totalMs,
              stopReason: r.stopReason,
              rawStopReason: r.rawStopReason,
              costUsd: cost,
              retries: r.retries,
            });
            return { judgeId: j.id, text: r.text };
          } catch (err) {
            if (signal.aborted) throw err;
            opts.record({
              label: `${label} · ${j.label}`,
              judge: true,
              system,
              messages: [{ role: 'user', content: user }],
              response: '',
              usage: emptyUsage(),
              ttftMs: null,
              totalMs: Date.now() - started,
              stopReason: 'other',
              rawStopReason: 'error',
              costUsd: 0,
              retries: 0,
              error: (err as Error).message,
            });
            return { judgeId: j.id, text: '', error: (err as Error).message };
          }
        }),
      );
    },
  };
}

/** Category metadata passthrough for callers that only import the runner. */
export { loadCategories };
