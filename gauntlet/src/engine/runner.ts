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
  ChatMessage,
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
import { scoreResponse, type JudgePanel, type JudgeAskOptions } from '../scoring/index.ts';
import { browserAvailable } from '../scoring/browser.ts';
import { Semaphore } from './semaphore.ts';
import { SpendGuard, checkedLimits, isSpendLimitError, outputAllowance } from './spend-guard.ts';
import { callWithRetry, createRecorder, effectiveMaxOutputTokens, resolveOutputBudget, OutOfTimeError, type CallPolicy, type CallTarget } from './recorder.ts';
import { appendResult, createRunFolder, listRunIds, newRunId, readManifest, readResults, saveArtifact, writeManifest } from './store.ts';
import { caseImageRefs, caseImageSizes, estimateImageTokens, loadTestImage, stripImageData, supportsVision, testBaseDir } from '../core/vision.ts';
import { SKIP_NO_IMAGE_OUTPUT, SKIP_PICTURE_ONLY, estimateImageUsd, plannedImageSettings, supportsImageOutput } from '../core/image-output.ts';
import type { ChatImage as JudgeImage, ProgramDefinition } from '../core/types.ts';
import { gateResume, gateStart, runSpentUsd } from '../budget/spend.ts';

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
  /** Per-answer cap and output-limit choice (see RunLimits); empty = each model's own maximum, no per-answer cap. */
  limits: { perAnswerUsd?: number; sameOutputTokens?: number };
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

/** The program behind a program test (undefined for prompt tests). */
function programOf(t: LoadedTest): ProgramDefinition | undefined {
  const d = t.definition;
  return d.kind === 'program' ? PROGRAMS[d.program] : undefined;
}

/** Tests whose cases ask the model for pictures (The Gallery Masterpiece). */
function needsImageOutput(t: LoadedTest): boolean {
  return !!programOf(t)?.requiresImageOutput;
}

/** Why a job is skipped because of image output (null = it runs): no image output, or a picture-only model on a text test. */
function imageOutputSkip(t: LoadedTest, c: Contestant, providerType: ProviderType | undefined): string | null {
  if (needsImageOutput(t)) return supportsImageOutput(c, providerType) ? null : SKIP_NO_IMAGE_OUTPUT;
  return c.imageOnly ? SKIP_PICTURE_ONLY : null;
}

/**
 * Judges for a program that declares judge rules: `vision` keeps only judges that accept images, `strictVendor`
 * drops every judge from the contestant's vendor (no fallback) and the contestant's own model.
 */
export function programJudgePool(judges: ContestantSnapshot[], contestant: Contestant, rules: NonNullable<ProgramDefinition['judges']>, providerTypeOf: (c: Contestant) => ProviderType | undefined, excludeSameVendor = true): ContestantSnapshot[] {
  const pool = judges.filter((j) => !rules.vision || supportsVision(j, providerTypeOf(j)));
  if (rules.strictVendor) return pool.filter((j) => j.vendor.toLowerCase() !== contestant.vendor.toLowerCase() && !(j.model === contestant.model && j.provider === contestant.provider));
  return selectJudges(pool, contestant, excludeSameVendor);
}

function usesJudges(t: LoadedTest): boolean {
  const d = t.definition;
  if (d.kind === 'program') return !!PROGRAMS[d.program]?.judges;
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
  const pictureTests = tests.filter((t) => needsImageOutput(t));
  if (pictureTests.length) {
    const noPictures = contestants.filter((c) => !supportsImageOutput(c, providerOf(c)?.type));
    if (noPictures.length) warnings.push(`${noPictures.map((c) => c.label).join(', ')}: no image output — ${pictureTests.length} picture-making test(s) will be skipped for ${noPictures.length === 1 ? 'this model' : 'these models'} (not scored as 0, left out of the means)`);
  }
  const pictureOnly = contestants.filter((c) => c.imageOnly);
  if (pictureOnly.length && tests.some((t) => !needsImageOutput(t))) warnings.push(`${pictureOnly.map((c) => c.label).join(', ')}: picture-only ${pictureOnly.length === 1 ? 'model' : 'models'} — text tests are skipped for ${pictureOnly.length === 1 ? 'it' : 'them'}`);
  for (const t of tests) {
    const rules = programOf(t)?.judges;
    if (!rules || !judgeNeeded) continue;
    const snaps = judges.map((j) => snapshotContestant(j));
    const short = contestants.filter((c) => !imageOutputSkip(t, c, providerOf(c)?.type) && programJudgePool(snaps, c, rules, (j) => providerOf(j)?.type).length < 2);
    if (short.length) warnings.push(`${t.definition.name}: fewer than 2 eligible judges${rules.vision ? ' that can see images' : ''}${rules.strictVendor ? ' from another company' : ''} for ${short.map((c) => c.label).join(', ')} — ${short.length === 1 ? 'its' : 'their'} paintings will wait for your own rating in Blind Review`);
  }
  const manual = contestants.filter((c) => providerOf(c)?.type === 'manual');
  if (manual.length) warnings.push(`${manual.map((c) => c.label).join(', ')}: manual contestant — every prompt waits in the Manual Inbox for you to paste the model's reply`);
  if (req.maxCostUsd !== undefined && !(req.maxCostUsd > 0)) throw new Error('maxCostUsd must be a positive number');
  const limits = checkedLimits(req.limits);
  if (limits.perAnswerUsd) warnings.push('Per-answer spend limit: each model gets as many output tokens per reply as that money buys at its own price, so cheaper models get more room (not token-fair; see Methodology)');
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
    limits,
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
  /**
   * Tests that ask for each model's maximum output ("model-max", e.g. The Game Jam): USD per contestant if every
   * case used the model's full output limit (the true ceiling). Absent for other tests.
   */
  maxPerContestant?: Record<string, number>;
  /** Judge cost when every reply is that long (judges read the whole file). */
  maxJudgeUsd?: number;
}

export interface RunEstimate {
  jobs: number;
  calls: number;
  perContestant: Array<{ contestantId: string; jobs: number; estCostUsd: number; estCostUsdHigh: number; manual: boolean; /** Ceiling when "model's maximum" tests use the full output limit (only when the run has such a test). */ estCostUsdMax?: number; /** The model's output limit used for that ceiling. */ maxOutputTokens?: number; /** What set that limit: the model's own maximum, the run's same-for-all number, or the per-answer spend cap. */ outputLimitBy?: 'test' | 'model-max' | 'same-tokens' | 'per-answer' }>;
  perTest: TestCostEstimate[];
  judgeCostUsd: number;
  /** Central estimate (contestants + judges). */
  estCostUsd: number;
  /** Conservative upper estimate: +20% where measured, +60% where only the test's own estimate is known. */
  estCostUsdHigh: number;
  /** Absolute ceiling for runs with "model's maximum" tests: every such reply at the model's full output limit, judges included. */
  estCostUsdMax?: number;
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
  // "Model's maximum" tests: extra cost if every reply used the model's full output limit (contestants + judges).
  let maxExtra = 0;
  let judgeMax = 0;
  const settingsNow = loadSettings();
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
    const provider = providers.find((p) => p.id === c.provider);
    const cfg = contestantConfigHash(c);
    let jobs = 0;
    let cost = 0;
    let costHigh = 0;
    let costMax = 0;
    let anyModelMax = false;
    // The output limit per reply under the run's limits (own maximum, same-for-all, or what the per-answer cap buys).
    const allowanceFor = (t: (typeof plan.tests)[number], inputTokens: number) =>
      outputAllowance({ requested: t.definition.maxOutputTokens, contestant: c, defaultMaxOutputTokens: settingsNow.defaultMaxOutputTokens, limits: plan.limits, inputTokens });
    let firstMax: ReturnType<typeof allowanceFor> | null = null;
    plan.tests.forEach((t, i) => {
      const selected = selectedCaseIds(t);
      const visionIds = visionCaseIds(t);
      // Skipped image cases cost nothing; the ones that run pay for their image tokens (see estimateImageTokens).
      const runnable = imageOutputSkip(t, c, providerType) ? [] : sees ? selected : selected.filter((id) => !visionIds.has(id));
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
      // A model cannot write more than its own output limit (a Game Jam game hits it; see Contestant.maxOutputTokens).
      output = Math.min(output, effectiveMaxOutputTokens(c, Math.max(output, 1)));
      const pictures = programOf(t)?.imagesPerCase ?? 0;
      const allow = allowanceFor(t, input / Math.max(1, perCaseCalls));
      // A per-answer cap (or a same-for-all limit) can also stop a reply short: each call writes at most its allowance.
      if (pictures === 0) output = Math.min(output, allow.tokens * Math.max(1, perCaseCalls));
      // Picture-making programs are billed per image (plus the prompt text), not per output token.
      const testCost = isManual ? 0 : pictures > 0 ? n * estimateImageUsd(c, pictures, input, plannedImageSettings(c, provider?.baseUrl, providerType)) : (n * (input * c.pricing.inputPerM + output * c.pricing.outputPerM)) / 1e6;
      const modelMax = t.definition.maxOutputTokens === 'model-max' && pictures === 0 && n > 0;
      const maxOut = modelMax ? allow.tokens : 0;
      if (modelMax && !firstMax) firstMax = allow;
      const testMax = !modelMax || isManual ? testCost : (n * (input * c.pricing.inputPerM + maxOut * perCaseCalls * c.pricing.outputPerM)) / 1e6;
      costMax += testMax;
      if (modelMax) {
        anyModelMax = true;
        (perTest[i]!.maxPerContestant ??= {})[c.id] = Math.round(testMax * 10000) / 10000;
      }
      jobs += selected.length * plan.repeats;
      calls += n * perCaseCalls;
      cost += testCost;
      // The conservative bound never exceeds the true ceiling of a "model's maximum" test.
      costHigh += Math.min(testCost * (basis === 'measured' ? 1.2 : 1.6), modelMax ? Math.max(testCost, testMax) : Infinity);
      // A test this model skips entirely (picture-only model, no image output) has no cell: the UI shows “skipped”, not £0.
      if (runnable.length > 0 || selected.length === 0) perTest[i]!.perContestant[c.id] = Math.round(testCost * 10000) / 10000;
      if (perTest[i]!.basis !== 'measured') perTest[i]!.basis = basis;
      const judgePool = plan.plannedJudges;
      const rules = programOf(t)?.judges;
      if (rules && judgePool.length) {
        // Program judges (e.g. the Gallery): the declared per-judge usage, plus each judge's image tokens.
        const panel = programJudgePool(judgePool.map((j) => snapshotContestant(j)), c, rules, (j) => providers.find((p) => p.id === j.provider)?.type, plan.judgeExcludeSameVendor);
        let jc = 0;
        if (obs && obs.all.judgeUsd > 0) jc = (obs.all.judgeUsd / obs.all.n) * n;
        else
          for (const j of panel) {
            const jType = providers.find((p) => p.id === j.provider)?.type;
            const imgTok = (rules.perCase.images ?? 0) * estimateImageTokens(rules.perCase.imageSize?.width ?? 1536, rules.perCase.imageSize?.height ?? 1024, jType, j.model);
            jc += (n * ((rules.perCase.inputTokens + imgTok) * j.pricing.inputPerM + rules.perCase.outputTokens * j.pricing.outputPerM)) / 1e6;
          }
        calls += n * panel.length;
        judgeCost += jc;
        high += jc * 1.5;
        perTest[i]!.judgeUsd = Math.round((perTest[i]!.judgeUsd + jc) * 10000) / 10000;
      } else if (usesJudges(t) && judgePool.length) {
        const panel = plan.judgeExcludeSameVendor && judgePool.some((j) => j.vendor !== c.vendor) ? judgePool.filter((j) => j.vendor !== c.vendor) : judgePool;
        let jc = 0;
        if (obs && obs.all.judgeUsd > 0) jc = (obs.all.judgeUsd / obs.all.n) * n * (panel.length / Math.max(1, judgePool.length));
        // Judge input = fixed prompt (~2.5k tokens incl. rubric/reference) + the visible part of the reply (reasoning
        // tokens are never shown to judges; assume half of the output is visible). Judge output ≈ 1.5k at medium effort.
        // Tests can declare their own judge load (e.g. The Game Jam: whole game file + screenshots).
        else {
          const est = t.definition.estimate;
          const jin = est?.judgeInputTokens ?? 2500 + Math.min(output, 40000) * 0.5;
          const jout = est?.judgeOutputTokens ?? 1500;
          for (const j of panel) jc += (n * (jin * j.pricing.inputPerM + jout * j.pricing.outputPerM)) / 1e6;
          if (modelMax) {
            // At the ceiling the judges read a reply of up to the model's full output (minus nothing: assume all visible).
            const jinMax = jin + Math.max(0, maxOut - output);
            let jm = 0;
            for (const j of panel) jm += (n * (jinMax * j.pricing.inputPerM + jout * j.pricing.outputPerM)) / 1e6;
            judgeMax += jm - jc;
            perTest[i]!.maxJudgeUsd = Math.round(((perTest[i]!.maxJudgeUsd ?? perTest[i]!.judgeUsd) + jm) * 10000) / 10000;
          }
        }
        calls += n * panel.length;
        judgeCost += jc;
        high += jc * 1.5;
        perTest[i]!.judgeUsd = Math.round((perTest[i]!.judgeUsd + jc) * 10000) / 10000;
      }
    });
    high += costHigh;
    maxExtra += costMax - cost;
    const fm = firstMax as ReturnType<typeof allowanceFor> | null;
    return {
      contestantId: c.id,
      jobs,
      estCostUsd: Math.round(cost * 10000) / 10000,
      estCostUsdHigh: Math.round(costHigh * 10000) / 10000,
      manual: isManual,
      ...(anyModelMax && fm ? { estCostUsdMax: Math.round(costMax * 10000) / 10000, maxOutputTokens: fm.tokens, outputLimitBy: fm.by } : {}),
    };
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
    ...(perContestant.some((p) => p.estCostUsdMax !== undefined) ? { estCostUsdMax: Math.round((total + maxExtra + judgeMax) * 10000) / 10000 } : {}),
    fingerprint: plan.fingerprint,
    warnings,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Execution
// ─────────────────────────────────────────────────────────────────────────────

export async function startRun(req: RunRequest): Promise<string> {
  const plan = planRun(req);
  // "My budget": the hard stop refuses to start when the month's budget is used up, and lowers the limit to what is left.
  const budgetGate = gateStart(plan.maxCostUsd, 'run');
  plan.maxCostUsd = budgetGate.capUsd;
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
      limits: { ...(plan.maxCostUsd !== undefined ? { maxCostUsd: plan.maxCostUsd } : {}), ...plan.limits, ...(req.limits?.currency ? { currency: req.limits.currency } : {}), ...(budgetGate.clamped ? { budgetNote: budgetGate.message } : {}) },
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
  if (opts.maxCostUsd !== undefined) {
    manifest.settings.maxCostUsd = opts.maxCostUsd === null ? undefined : opts.maxCostUsd;
    // Keep the recorded limits in step (the run page and the Presenter disclose them).
    if (manifest.settings.limits) {
      const { maxCostUsd: _old, ...rest } = manifest.settings.limits;
      manifest.settings.limits = { ...rest, ...(manifest.settings.maxCostUsd !== undefined ? { maxCostUsd: manifest.settings.maxCostUsd } : {}) };
    }
  }
  // "My budget": resuming is also held to the monthly budget (the limit covers what the run already spent).
  const budgetGate = gateResume(manifest.settings.maxCostUsd, runSpentUsd(runId), 'run');
  if (budgetGate.clamped) {
    manifest.settings.maxCostUsd = budgetGate.capUsd;
    manifest.settings.limits = { ...manifest.settings.limits, maxCostUsd: budgetGate.capUsd, budgetNote: budgetGate.message };
  }
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
  manifest.stopReason = undefined;
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
    // Spend on cases a spend limit stopped half-way is not in any result, but it was spent: it counts too.
    costUsd: previous.reduce((s, r) => s + r.metrics.costUsd + r.metrics.judgeCostUsd, 0) + (manifest.unrecordedCostUsd ?? 0),
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
    const cap = manifest.settings.maxCostUsd;
    // The spend limit is enforced per call (every call's worst case is reserved first; see spend-guard.ts).
    const guard = cap !== undefined ? new SpendGuard(cap, progress.costUsd) : undefined;
    const policy: CallPolicy = { maxRetries: settings.maxRetries, temperature: manifest.settings.temperature, defaultMaxOutputTokens: settings.defaultMaxOutputTokens, ...(guard ? { spend: guard } : {}) };

    // Throttled streaming deltas (≈10 events/s per job).
    const deltas = new Map<string, { contestantId: string; text: string; label?: string; reset?: boolean }>();
    const deltaEvent = (key: string, d: { contestantId: string; text: string; label?: string; reset?: boolean }): RunEvent => ({ type: 'job.delta', runId: manifest.id, key, contestantId: d.contestantId, text: d.text, label: d.label, ...(d.reset ? { reset: true } : {}) });
    const flush = setInterval(() => {
      for (const [key, d] of deltas) emit(deltaEvent(key, d));
      deltas.clear();
    }, 100);

    let budgetHit = false;
    const stopForBudget = () => {
      if (budgetHit) return;
      budgetHit = true;
      manifest.stopReason = 'spend-limit';
      manifest.error = `Stopped: spend limit of $${(cap ?? 0).toFixed(2)} reached ($${progress.costUsd.toFixed(4)} spent). Every finished result is kept; resume with a higher limit to finish.`;
      emit({ type: 'log', runId: manifest.id, level: 'warn', message: manifest.error, at: now() });
    };
    const queues = [
      { jobs: apiJobs, cursor: 0, workers: Math.min(manifest.settings.concurrency, Math.max(1, apiJobs.length)) },
      { jobs: manualJobs, cursor: 0, workers: Math.min(200, manualJobs.length) },
    ];
    const worker = async (queue: (typeof queues)[number]) => {
      while (!controller.signal.aborted) {
        if (cap !== undefined && (budgetHit || guard?.hit || progress.costUsd >= cap)) {
          stopForBudget();
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
            if (d && d.label === label) d.text += text;
            else {
              // A new call: send the previous call's tail first, so each event belongs to one call.
              if (d) emit(deltaEvent(job.key, d));
              deltas.set(job.key, { contestantId: job.contestant.id, text, label });
            }
          },
          onDeltaReset: (label) => {
            const d = deltas.get(job.key);
            if (d && d.label !== label) emit(deltaEvent(job.key, d));
            deltas.set(job.key, { contestantId: job.contestant.id, text: '', label, reset: true });
          },
        });
        // Flush this job's last streamed text before announcing that it finished.
        const pending = deltas.get(job.key);
        if (pending) {
          emit(deltaEvent(job.key, pending));
          deltas.delete(job.key);
        }
        if (result.status === 'cancelled' && controller.signal.aborted) return;
        // Stopped half-way by the spend limit: not a model failure, so no result is stored (resume re-runs the case);
        // whatever it already spent is remembered so the cap still counts it.
        if (result.status === 'cancelled' && result.summary === SPEND_LIMIT_SUMMARY) {
          const spent = result.metrics.costUsd + result.metrics.judgeCostUsd;
          if (spent > 0) {
            manifest.unrecordedCostUsd = Math.round(((manifest.unrecordedCostUsd ?? 0) + spent) * 1e8) / 1e8;
            progress.costUsd += spent;
          }
          stopForBudget();
          return;
        }
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
          passed: result.passed,
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

/** Summary of a case the run's spend limit stopped before it finished (never stored as a result). */
export const SPEND_LIMIT_SUMMARY = 'Stopped: spend limit';

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
  onDeltaReset?: (label: string) => void;
}

function clamp01(n: number): number {
  return Number.isFinite(n) ? Math.max(0, Math.min(1, n)) : 0;
}

/** A vision case for a model without image input: recorded as skipped (not scored, excluded from means). */
function skippedResult(job: Job, manifest: RunManifest, reason?: string): CaseResult {
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
    summary: reason ?? 'Skipped — model has no image input',
    scoreDetail: {
      notes:
        reason === SKIP_NO_IMAGE_OUTPUT
          ? 'This test asks the model to make a picture. The model is not marked as making images (imageOutput: true in config/models.json), so the case was not sent and is left out of every mean. Chat apps can still take part through a Manual (copy & paste) contestant.'
          : reason === SKIP_PICTURE_ONLY
            ? 'This model only makes pictures (imageOnly: true in config/models.json), so text tests are not sent to it and are left out of every mean.'
            : 'This case shows the model an image. The model is not marked as accepting images (vision: true in config/models.json), so the case was not sent and is left out of every mean. Start the run with "Force image cases" to send it anyway.',
    },
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
  const pictureSkip = imageOutputSkip(job.test, job.contestant, env.providerTypeOf(job.contestant));
  if (pictureSkip) {
    env.emit({ type: 'job.started', runId: manifest.id, key: job.key, contestantId: job.contestant.id, testId: def.id, caseId: job.caseId, repeat: job.repeat, at: now() });
    return skippedResult(job, manifest, pictureSkip);
  }
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
  const limits = manifest.settings.limits;
  try {
    if (targetError || !target) throw targetError ?? new Error('No target');
    recorder = createRecorder({
      target,
      callContext: { runId: manifest.id, key: job.key, testId: def.id, testName: def.name, caseId: job.caseId },
      // The model's own calls: fewer retries for very long generations, and the per-answer spend cap (judges never get it).
      policy: {
        ...env.policy,
        ...(def.maxRetries !== undefined ? { maxRetries: Math.min(env.policy.maxRetries, def.maxRetries) } : {}),
        ...(limits?.perAnswerUsd ? { perAnswerUsd: limits.perAnswerUsd } : {}),
      },
      signal: controller.signal,
      maxOutputTokens: resolveOutputBudget(def.maxOutputTokens, job.contestant, settings.defaultMaxOutputTokens, limits?.sameOutputTokens),
      onDelta: env.onDelta,
      onDeltaReset: env.onDeltaReset,
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
          ...(reply!.outputLimitBy ? { outputLimitBy: reply!.outputLimitBy } : {}),
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
        if (reply!.stopReason === 'max_tokens')
          detail.notes = `${detail.notes ? detail.notes + ' · ' : ''}Response hit the output token limit${reply!.outputLimitBy === 'per-answer' ? ' (stopped by your per-answer spend limit)' : reply!.outputLimitBy === 'spend-limit' ? ' (lowered by your run spend limit)' : ''}`;
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
      const programJudges = program.judges
        ? createJudgePanel({
            judges: programJudgePool(manifest.judges, job.contestant, program.judges, env.providerTypeOf, manifest.settings.judgeExcludeSameVendor ?? true),
            targetFor: env.targetFor,
            policy: env.policy,
            signal: controller.signal,
            record: (entry) => rec.recordJudge(entry),
          })
        : undefined;
      const ctx: ProgramContext = {
        seed,
        rng: createRng(seed),
        config: { ...(program.defaults ?? {}), ...(pdef.config ?? {}) },
        model: rec.handle,
        maxOutputTokens: resolveOutputBudget(def.maxOutputTokens, job.contestant, settings.defaultMaxOutputTokens, limits?.sameOutputTokens),
        signal: controller.signal,
        artifact: (name, kind, content) => {
          save(name, kind, content);
        },
        artifactBytes: (name, kind, content) => {
          save(name, kind, Buffer.from(content));
        },
        ...(programJudges ? { judges: programJudges } : {}),
      };
      const out = await program.run(ctx);
      score = clamp01(out.score);
      passed = out.passed ?? score >= 0.5;
      if (out.status === 'refusal') status = 'refusal';
      if (out.status === 'pending-human') {
        status = 'pending-human';
        score = null;
        passed = null;
      }
      summary = out.summary;
      detail = { ...out.detail };
      replay = out.replay;
    }
  } catch (err) {
    if (isSpendLimitError(err) && !env.signal.aborted) {
      // Not the model's fault: the run's money ran out before this case could finish. The worker keeps no result.
      status = 'cancelled';
      summary = SPEND_LIMIT_SUMMARY;
      error = (err as Error).message;
    } else if (err instanceof OutOfTimeError && !env.signal.aborted) {
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
  const providers = loadProviders();
  return {
    ids: judges.map((j) => j.id),
    async ask(system, user, label, extraOrImages?: JudgeAskOptions | JudgeImage[]) {
      // Pictures come either as a list (program judges, e.g. the Gallery) or as ask options (e.g. The Game Jam).
      const extra: JudgeAskOptions | undefined = Array.isArray(extraOrImages) ? { images: extraOrImages } : extraOrImages;
      if (judges.length === 0) return [];
      // Images go with the text in one message; transcripts keep only their names and sizes.
      return Promise.all(
        judges.map(async (j) => {
          const started = Date.now();
          // Pictures only go to judges that accept image input; the others get the text-only version of the question.
          const sees = Boolean(extra?.images?.length) && supportsVision(j, providers.find((p) => p.id === j.provider)?.type);
          const messages: ChatMessage[] = [sees ? { role: 'user', content: user, images: extra!.images } : { role: 'user', content: extra?.textOnlyUser ?? user }];
          try {
            const target = opts.targetFor(j);
            const r = await callWithRetry(target, { system, messages, maxOutputTokens: 16000, temperature: 0 }, opts.policy, signal);
            const cost = computeCost(r.usage, j.pricing);
            opts.record({
              label: `${label} · ${j.label}`,
              judge: true,
              system,
              messages: stripImageData(messages),
              response: r.text,
              usage: r.usage,
              ttftMs: r.ttftMs,
              totalMs: r.totalMs,
              stopReason: r.stopReason,
              rawStopReason: r.rawStopReason,
              costUsd: cost,
              retries: r.retries,
            });
            return { judgeId: j.id, text: r.text, sawImages: sees };
          } catch (err) {
            // Out of money is not a judge failure: stop the case so it can be finished on resume.
            if (signal.aborted || isSpendLimitError(err)) throw err;
            opts.record({
              label: `${label} · ${j.label}`,
              judge: true,
              system,
              messages: stripImageData(messages),
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
