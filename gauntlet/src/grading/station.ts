/**
 * The Grading Station — server side.
 *
 * One place to grade any stored result: by a person (rubric sliders, labels,
 * requirement checklists, notes), by AI judges (the test's own rubric, cross-vendor,
 * with pictures for judges that can see), or both. Every grade is stored on the
 * result (humanScores / aiGrades / disputes, all optional fields) and the
 * official score follows the policy in policy.ts. Objective scores (answer keys,
 * unit tests, simulations) are never overwritten: people can only dispute them.
 *
 * Also: the 30-word performance summaries (template, and an optional AI-written
 * version with its cost shown first, cached in the run folder).
 */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { getProvider, hasApiKey, loadContestants, loadProviders, loadSettings, saveSettings, writeJsonAtomic } from '../core/config.ts';
import { computeCost } from '../core/cost.ts';
import { sha256 } from '../core/hash.ts';
import { mean } from '../core/stats.ts';
import { caseScorer, getTest, renderCase, type RenderedCase } from '../core/registry.ts';
import { estimateImageTokens, imageInfo, loadTestImage, stripImageData, supportsVision, testBaseDir } from '../core/vision.ts';
import { explainerForDefinition, type TestExplainer } from '../core/explainers.ts';
import { extractTagged, parseNumber } from '../core/extract.ts';
import type { AiGrade, CaseResult, CaseResultLite, ChatImage, Contestant, ContestantSnapshot, HumanScore, PromptTestCase, RunManifest, TestDefinition, TranscriptEntry } from '../core/types.ts';
import { appendResult, artifactPath, listRunIds, readManifest, readResults, runDir, saveArtifact, toLite } from '../engine/store.ts';
import { asJudge } from '../engine/runner.ts';
import { callWithRetry, type CallTarget } from '../engine/recorder.ts';
import { Semaphore } from '../engine/semaphore.ts';
import { createAdapter } from '../providers/index.ts';
import { PROGRAMS } from '../programs/index.ts';
import { scoreResponse, type JudgePanel } from '../scoring/index.ts';
import { JUDGE_RUBRIC_TEMPLATE, JUDGE_SYSTEM, fill } from '../scoring/judge-prompts.ts';
import { gradingSpecFor, requirementPoints, scoreFromGrade, type GradingSpec } from './spec.ts';
import { OFFICIAL_POLICIES, agreement, type OfficialPolicy, type OfficialSource } from './policy.ts';
import { applyOfficial, humanMean, judgeFailed, needOf, r4, runAiScore, stationAiScore } from './official.ts';
import { SUMMARY_WORD_LIMIT, clampWords, missReason, templateSummary } from './summary.ts';
import type { AiEstimate, AiEstimateItem, AiGradeOutcome, AiSummary, GradingNeed, GradingRunInfo, HumanGradeInput, JudgeInfo, QueueItem, RunSummaries, StationItem, SummaryEstimate } from './types.ts';

export { applyOfficial, humanMean, stationAiScore };
export type { AiEstimate, AiEstimateItem, AiGradeOutcome, AiSummary, GradingNeed, GradingRunInfo, HumanGradeInput, JudgeInfo, QueueItem, RunSummaries, StationItem, SummaryEstimate };

// ───────────────────────────── Settings ─────────────────────────────

export function gradingPolicy(): OfficialPolicy {
  const p = loadSettings().gradingOfficial;
  return p && OFFICIAL_POLICIES.includes(p) ? p : 'methodology';
}

export function setGradingPolicy(policy: OfficialPolicy): OfficialPolicy {
  if (!OFFICIAL_POLICIES.includes(policy)) throw new Error(`official must be one of ${OFFICIAL_POLICIES.join(', ')}`);
  saveSettings({ ...loadSettings(), gradingOfficial: policy });
  return policy;
}

// ───────────────────────────── Helpers ─────────────────────────────

function requireRun(runId: string): RunManifest {
  const m = readManifest(runId);
  if (!m) throw new Error('Run not found');
  return m;
}

function requireResult(runId: string, key: string): CaseResult {
  const r = readResults(runId).find((x) => x.key === key);
  if (!r) throw new Error('Result not found');
  return r;
}

interface TestCtx {
  def: TestDefinition | null;
  file?: string;
  c?: PromptTestCase;
  spec: GradingSpec;
  explainer: TestExplainer | null;
}

function testCtx(testId: string, caseId: string, fallbackName = testId): TestCtx {
  const t = getTest(testId);
  if (!t) {
    // The test was deleted or renamed: grade what is stored, conservatively (dispute only).
    return {
      def: null,
      explainer: null,
      spec: { testId, testName: fallbackName, kind: 'objective', scorerType: 'unknown', gradedOn: 'This test is no longer in the library, so only disputes can be recorded.', howScored: [], humanRole: 'dispute', aiRole: 'none', unit: 'question', criteria: [], scaleMax: 0, checklist: [], passThreshold: 1, rules: [], output: 'text' },
    };
  }
  const def = t.definition;
  const programScoring = def.kind === 'program' ? PROGRAMS[def.program]?.scoring : undefined;
  const explainer = explainerForDefinition(def, programScoring);
  return { def, file: t.file, c: def.kind === 'prompt' ? def.cases.find((x) => x.id === caseId) : undefined, spec: gradingSpecFor(def, { caseId, programScoring, explainer }), explainer };
}

/** The model's final visible reply (last non-judge call). */
export function finalResponse(r: Pick<CaseResult, 'transcript'>): string {
  const e = [...(r.transcript ?? [])].reverse().find((t) => !t.judge);
  return e?.response ?? '';
}

function taskText(rendered: RenderedCase): string {
  return [rendered.system ? `[System prompt]\n${rendered.system}` : '', ...rendered.turns.map((t) => `[User]\n${t}`)].filter(Boolean).join('\n\n');
}

// ───────────────────────────── Queue ─────────────────────────────


export function gradingQueue(runId: string, testId?: string): QueueItem[] {
  const m = requireRun(runId);
  const names = new Map(m.tests.map((t) => [t.id, t]));
  const specs = new Map<string, TestCtx>();
  const policy = gradingPolicy();
  const out: QueueItem[] = [];
  for (const r of readResults(runId)) {
    if (testId && r.testId !== testId) continue;
    if (r.status === 'cancelled' || r.status === 'skipped') continue;
    if (r.status === 'error' && !judgeFailed(r)) continue;
    const k = `${r.testId}::${r.caseId}`;
    let ctx = specs.get(k);
    if (!ctx) specs.set(k, (ctx = testCtx(r.testId, r.caseId, names.get(r.testId)?.name)));
    const spec = ctx.spec;
    const need = needOf(r, spec);
    const humans = r.humanScores?.length ?? 0;
    const ais = r.aiGrades?.length ?? 0;
    const todo = need === 'grade' ? humans === 0 && !(ais > 0 && policy !== 'methodology') : need === 'arbitrate' ? humans === 0 : need === 'judge-failed' ? humans === 0 && ais === 0 : false;
    out.push({
      runId,
      key: r.key,
      testId: r.testId,
      testName: names.get(r.testId)?.name ?? spec.testName,
      category: names.get(r.testId)?.category ?? '',
      caseId: r.caseId,
      repeat: r.repeat,
      contestantId: r.contestantId,
      status: r.status,
      score: r.score,
      summary: r.summary,
      kind: spec.kind,
      humanRole: spec.humanRole,
      aiRole: spec.aiRole,
      need,
      todo,
      humans,
      ais,
      disputes: r.disputes?.length ?? 0,
      official: (r.scoreDetail?.official as { source?: OfficialSource } | undefined)?.source,
      hasReplay: Boolean(r.replay),
      artifacts: (r.artifacts ?? []).map((a) => a.name),
    });
  }
  return out;
}

/** Runs with results, newest first, with how many results still need grading. */
export function gradingRuns(): GradingRunInfo[] {
  const out: GradingRunInfo[] = [];
  for (const id of listRunIds()) {
    const m = readManifest(id);
    if (!m) continue;
    let q: QueueItem[] = [];
    try {
      q = gradingQueue(id);
    } catch {
      continue;
    }
    if (!q.length) continue;
    out.push({ id, name: m.name || id, createdAt: m.createdAt, status: m.status, results: q.length, todo: q.filter((x) => x.todo).length, gradable: q.filter((x) => x.humanRole !== 'dispute').length });
  }
  return out;
}

// ───────────────────────────── One item ─────────────────────────────

export function stationItem(runId: string, key: string): StationItem {
  const m = requireRun(runId);
  const r = requireResult(runId, key);
  const ctx = testCtx(r.testId, r.caseId, m.tests.find((t) => t.id === r.testId)?.name);
  const def = ctx.def;
  const rendered = def?.kind === 'prompt' && ctx.c ? renderCase(def, ctx.c, testBaseDir(ctx.file)) : null;
  const con = m.contestants.find((c) => c.id === r.contestantId);
  const manualProviders = new Set(loadProviders().filter((p) => p.type === 'manual').map((p) => p.id));
  const human = humanMean(r);
  const ai = stationAiScore(r) ?? runAiScore(r, ctx.spec);
  const panel = con ? panelFor(con) : { judges: [], reason: 'Unknown contestant' };
  const official = (r.scoreDetail?.official as { source?: OfficialSource; why?: string } | undefined) ?? {};
  return {
    result: r,
    spec: ctx.spec,
    rendered,
    test: def ? { id: def.id, name: def.name, category: def.category, kind: def.kind, description: def.description, hook: def.hook } : null,
    contestant: con ? { id: con.id, label: con.label, vendor: con.vendor, color: con.color, manual: manualProviders.has(con.provider) } : null,
    explainer: ctx.explainer,
    program: def?.kind === 'program' && PROGRAMS[def.program] ? { id: def.program, name: PROGRAMS[def.program]!.name, scoring: PROGRAMS[def.program]!.scoring } : undefined,
    policy: gradingPolicy(),
    official,
    agreement: agreement(human, ai),
    humanScore: human,
    aiScore: ai,
    aiPanel: { judges: panel.judges.map(judgeInfo), ok: panel.judges.length >= 2 && ctx.spec.aiRole !== 'none', reason: ctx.spec.aiRole === 'none' ? 'Scored by machine: there is nothing for an AI judge to grade.' : panel.reason },
  };
}

// ───────────────────────────── Human grades & disputes ─────────────────────────────

export function saveHumanGrade(input: HumanGradeInput): CaseResultLite {
  requireRun(input.runId);
  const rater = input.rater?.trim();
  if (!rater) throw new Error('Enter your rater name first: it is stored with every grade.');
  const current = requireResult(input.runId, input.key);
  const { spec } = testCtx(current.testId, current.caseId);
  if (spec.humanRole === 'dispute') throw new Error('This result is scored by machine against an answer key: flag a dispute instead of grading it.');
  const criteria: Record<string, number> = { ...(input.criteria ?? {}) };
  const reqs = input.requirements ?? {};
  for (const c of spec.criteria) {
    if (!c.requirements) continue;
    const states = Object.fromEntries(c.requirements.items.map((it) => [it.id, reqs[`${c.id}:${it.id}`]]));
    if (Object.values(states).some(Boolean)) criteria[c.id] = requirementPoints(c, states);
  }
  let score = scoreFromGrade(spec, { criteria, label: input.label });
  if (score === null && typeof input.score === 'number' && !spec.labels?.length && spec.criteria.length <= 1) score = input.score;
  if (score === null || !(score >= 0 && score <= 1)) throw new Error(spec.labels?.length ? 'Pick one label.' : 'Grade every criterion before saving.');
  const entry: HumanScore = {
    rater,
    score: r4(score),
    at: new Date().toISOString(),
    note: input.note?.trim().slice(0, 2000) || undefined,
    criteria: spec.criteria.length ? criteria : undefined,
    requirements: Object.keys(reqs).length ? reqs : undefined,
    label: input.label,
    blind: input.blind,
  };
  const humanScores = [...(current.humanScores ?? []).filter((h) => h.rater !== rater), entry];
  const updated = applyOfficial({ ...current, humanScores }, spec, gradingPolicy());
  appendResult(updated);
  return toLite(updated);
}

export function addDispute(input: { runId: string; key: string; rater: string; note: string }): CaseResultLite {
  requireRun(input.runId);
  const rater = input.rater?.trim();
  if (!rater) throw new Error('Enter your rater name first.');
  const note = input.note?.trim();
  if (!note) throw new Error('Say what is wrong: the dispute note is shown in the result inspector.');
  const current = requireResult(input.runId, input.key);
  const updated: CaseResult = { ...current, disputes: [...(current.disputes ?? []), { rater, note: note.slice(0, 2000), at: new Date().toISOString() }] };
  appendResult(updated);
  return toLite(updated);
}

/** Re-apply the current policy to every graded result of a run (after changing the policy). */
export function reapplyPolicy(runId: string): { updated: number } {
  requireRun(runId);
  const policy = gradingPolicy();
  let n = 0;
  for (const r of readResults(runId)) {
    if (!r.humanScores?.length && !r.aiGrades?.length) continue;
    const { spec } = testCtx(r.testId, r.caseId);
    const next = applyOfficial(r, spec, policy);
    if (next.score !== r.score || next.summary !== r.summary || JSON.stringify(next.scoreDetail.official) !== JSON.stringify(r.scoreDetail.official)) {
      appendResult(next);
      n++;
    }
  }
  return { updated: n };
}

// ───────────────────────────── AI judges ─────────────────────────────

function judgeInfo(j: Contestant): JudgeInfo {
  const providers = loadProviders();
  const p = providers.find((x) => x.id === j.provider);
  return { id: j.id.replace(/@judge$/, ''), label: j.label, vendor: j.vendor, vision: supportsVision(j, p?.type), hasKey: p ? hasApiKey(p) : false };
}

/** Configured judges with a key (manual copy & paste models can't judge). */
function judgePool(): Contestant[] {
  const settings = loadSettings();
  const all = loadContestants();
  const providers = loadProviders();
  return settings.judges
    .map((id) => all.find((m) => m.id === id))
    .filter((m): m is Contestant => Boolean(m))
    .filter((m) => {
      const p = providers.find((x) => x.id === m.provider);
      return p ? hasApiKey(p) && p.type !== 'manual' : false;
    })
    .map((j) => asJudge(j, settings.judgeEffort));
}

/** The station's panel for one contestant: never its own vendor, never itself, at least two judges. */
export function panelFor(contestant: Pick<Contestant, 'vendor' | 'model' | 'provider'>): { judges: Contestant[]; reason?: string } {
  const pool = judgePool();
  const judges = pool.filter((j) => j.vendor.toLowerCase() !== contestant.vendor.toLowerCase() && !(j.model === contestant.model && j.provider === contestant.provider));
  if (judges.length >= 2) return { judges };
  const configured = loadSettings().judges.length;
  return {
    judges,
    reason:
      pool.length < 2
        ? `AI grading needs at least 2 judge models with API keys (${pool.length} of ${configured} configured judges have one). Add keys on the API Keys page or more judges in config/settings.json.`
        : `AI grading needs at least 2 judges from vendors other than ${contestant.vendor}; only ${judges.length} available.`,
  };
}

interface GradeImage extends ChatImage {
  data: string;
}

/** Pictures a vision judge should see: the test's own images, then recorded screenshots / renders. */
function imagesFor(r: CaseResult, def: TestDefinition | null, file: string | undefined, c: PromptTestCase | undefined): GradeImage[] {
  const out: GradeImage[] = [];
  if (def?.kind === 'prompt' && c) {
    const rendered = renderCase(def, c, testBaseDir(file));
    for (const img of rendered.images ?? []) {
      try {
        const x = loadTestImage(testBaseDir(file), img.file);
        if (x.data) out.push(x as GradeImage);
      } catch {
        /* missing image: the judge grades from the text */
      }
    }
  }
  for (const a of r.artifacts ?? []) {
    if (a.kind !== 'png' || out.length >= 4) continue;
    const full = artifactPath(r.runId, a.file);
    if (!full) continue;
    const buf = readFileSync(full);
    const info = imageInfo(buf);
    if (!info) continue;
    out.push({ name: a.name, mediaType: info.mediaType, data: buf.toString('base64'), bytes: buf.length, width: info.width, height: info.height });
  }
  return out.slice(0, 4);
}

function providerTypeOf(j: Contestant) {
  return loadProviders().find((p) => p.id === j.provider)?.type;
}

function estimateItem(m: RunManifest, r: CaseResult): AiEstimateItem {
  const ctx = testCtx(r.testId, r.caseId);
  if (ctx.spec.aiRole === 'none') return { key: r.key, ok: false, reason: 'Scored by machine: nothing for an AI judge to grade.', judges: [], estUsd: 0 };
  const con = m.contestants.find((c) => c.id === r.contestantId);
  if (!con) return { key: r.key, ok: false, reason: 'Unknown contestant', judges: [], estUsd: 0 };
  const panel = panelFor(con);
  if (panel.judges.length < 2) return { key: r.key, ok: false, reason: panel.reason, judges: panel.judges.map((j) => ({ ...judgeInfo(j), images: 0, estUsd: 0 })), estUsd: 0 };
  const response = finalResponse(r);
  if (!response.trim()) return { key: r.key, ok: false, reason: 'No reply recorded to grade.', judges: [], estUsd: 0 };
  const rendered = ctx.def?.kind === 'prompt' && ctx.c ? renderCase(ctx.def, ctx.c, testBaseDir(ctx.file)) : null;
  const textTokens = 700 + Math.ceil((Math.min(response.length, 60_000) + Math.min((rendered ? taskText(rendered) : '').length, 30_000) + (ctx.spec.rubricText?.length ?? 0)) / 3.8);
  const images = imagesFor(r, ctx.def, ctx.file, ctx.c);
  const judges = panel.judges.map((j) => {
    const pt = providerTypeOf(j);
    const vision = supportsVision(j, pt);
    const imgTokens = vision ? images.reduce((s, im) => s + estimateImageTokens(im.width ?? 1024, im.height ?? 1024, pt, j.model), 0) : 0;
    const estUsd = computeCost({ inputTokens: textTokens + imgTokens, outputTokens: 1500, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 }, j.pricing);
    return { ...judgeInfo(j), images: vision ? images.length : 0, estUsd: Math.round(estUsd * 1e6) / 1e6 };
  });
  return { key: r.key, ok: true, judges, estUsd: judges.reduce((s, j) => s + j.estUsd, 0) };
}

export function aiEstimate(runId: string, keys: string[]): AiEstimate {
  const m = requireRun(runId);
  const all = readResults(runId);
  const items = keys.map((k) => {
    const r = all.find((x) => x.key === k);
    return r ? estimateItem(m, r) : { key: k, ok: false, reason: 'Result not found', judges: [], estUsd: 0 };
  });
  const total = items.reduce((s, i) => s + i.estUsd, 0);
  return { items, totalUsd: Math.round(total * 1e6) / 1e6, totalUsdHigh: Math.round(total * 2.5 * 1e6) / 1e6, gradable: items.filter((i) => i.ok).length };
}

/** Grade results with AI judges. `confirmCostUsd` must cover a fresh estimate: nothing is spent without it. */
export async function aiGrade(runId: string, keys: string[], confirmCostUsd: number, signal?: AbortSignal): Promise<{ outcomes: AiGradeOutcome[]; costUsd: number }> {
  const est = aiEstimate(runId, keys);
  if (!(typeof confirmCostUsd === 'number' && Number.isFinite(confirmCostUsd))) throw new Error('Confirm the cost first: call the estimate, then send confirmCostUsd.');
  if (est.totalUsd > confirmCostUsd * 1.05 + 0.0005) throw new Error(`The estimate is now $${est.totalUsd.toFixed(4)}, more than the $${confirmCostUsd.toFixed(4)} you confirmed. Review the new estimate.`);
  const settings = loadSettings();
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 15 * 60 * 1000);
  signal?.addEventListener('abort', () => ctl.abort(), { once: true });
  const sem = new Semaphore(3);
  const targets = new Map<string, CallTarget>();
  const targetFor = (j: Contestant): CallTarget => {
    let t = targets.get(j.id);
    if (!t) {
      const p = getProvider(j.provider);
      t = { contestant: j, adapter: createAdapter(j, p), semaphore: new Semaphore(p.maxConcurrency ?? 4) };
      targets.set(j.id, t);
    }
    return t;
  };
  try {
    const outcomes = await Promise.all(
      est.items.map(async (item): Promise<AiGradeOutcome> => {
        if (!item.ok) return { key: item.key, ok: false, error: item.reason, costUsd: 0 };
        const release = await sem.acquire();
        try {
          return await aiGradeOne(runId, item.key, targetFor, settings.maxRetries, ctl.signal);
        } catch (err) {
          return { key: item.key, ok: false, error: (err as Error).message, costUsd: 0 };
        } finally {
          release();
        }
      }),
    );
    return { outcomes, costUsd: Math.round(outcomes.reduce((s, o) => s + o.costUsd, 0) * 1e6) / 1e6 };
  } finally {
    clearTimeout(timer);
  }
}

async function aiGradeOne(runId: string, key: string, targetFor: (j: Contestant) => CallTarget, maxRetries: number, signal: AbortSignal): Promise<AiGradeOutcome> {
  const m = requireRun(runId);
  const r = requireResult(runId, key);
  const ctx = testCtx(r.testId, r.caseId);
  const { def, c, spec } = ctx;
  if (!def || def.kind !== 'prompt' || !c) throw new Error('Only prompt tests can be graded by AI judges.');
  const con = m.contestants.find((x) => x.id === r.contestantId)!;
  const { judges } = panelFor(con);
  if (judges.length < 2) throw new Error('Not enough eligible judges.');
  const response = finalResponse(r);
  const rendered = renderCase(def, c, testBaseDir(ctx.file));
  const images = imagesFor(r, def, ctx.file, c);
  const batch = randomUUID().slice(0, 8);
  const at = new Date().toISOString();
  const transcript: TranscriptEntry[] = [];
  const perJudge = new Map<string, { cost: number; images: number }>();
  const panel: JudgePanel = {
    ids: judges.map((j) => j.id),
    ask: (system, user, label) =>
      Promise.all(
        judges.map(async (j) => {
          const vision = supportsVision(j, providerTypeOf(j));
          const imgs = vision ? images : [];
          const messages = [{ role: 'user' as const, content: user, images: imgs.length ? imgs : undefined }];
          try {
            const res = await callWithRetry(targetFor(j), { system, messages, maxOutputTokens: 16000, temperature: 0 }, { maxRetries, temperature: 0, defaultMaxOutputTokens: 16000 }, signal);
            const cost = computeCost(res.usage, j.pricing);
            perJudge.set(j.id, { cost: (perJudge.get(j.id)?.cost ?? 0) + cost, images: imgs.length });
            transcript.push({ label: `station ${label} · ${j.label}`, judge: true, system, messages: stripImageData(messages), response: res.text, usage: res.usage, ttftMs: res.ttftMs, totalMs: res.totalMs, stopReason: res.stopReason, rawStopReason: res.rawStopReason, costUsd: cost, retries: res.retries });
            return { judgeId: j.id, text: res.text };
          } catch (err) {
            if (signal.aborted) throw err;
            transcript.push({ label: `station ${label} · ${j.label}`, judge: true, system, messages: stripImageData(messages), response: '', usage: { inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 }, ttftMs: null, totalMs: 0, stopReason: 'other', rawStopReason: 'error', costUsd: 0, retries: 0, error: (err as Error).message });
            return { judgeId: j.id, text: '', error: (err as Error).message };
          }
        }),
      ),
  };

  const sc = caseScorer(def, c);
  let verdicts: Array<{ contestantId: string; score: number; label?: string; rationale: string }> = [];
  let extraDetail: Record<string, unknown> = {};
  let error: string | undefined;
  try {
    if (sc.type === 'judge' || sc.type === 'judge-classify' || sc.type === 'artifact') {
      const scorer = sc.type === 'artifact' ? { ...sc, judgeWeight: (sc.judgeWeight ?? 0) > 0 ? sc.judgeWeight : 1 } : sc;
      const outcome = await scoreResponse({
        scorer,
        expected: c.expected,
        response,
        stopReason: 'end',
        taskText: taskText(rendered),
        judges: panel,
        saveArtifact: (name, kind, content) => saveArtifact(runId, key, name, kind, content),
        signal,
      });
      verdicts = outcome.detail.judge ?? [];
      // The run recorded no verdict (judges failed): keep the automated part the scorer just measured.
      if (judgeFailed(r)) {
        const { judge: _j, judgeDisagreement: _dis, judgeSpread: _sp, notes: _n, judgeScore: _js, ...rest } = outcome.detail;
        extraDetail = rest;
      }
    } else if (sc.type === 'human') {
      const user = fill(JUDGE_RUBRIC_TEMPLATE, { task: taskText(rendered).slice(0, 30_000), reference: c.expected === undefined ? '' : typeof c.expected === 'string' ? c.expected : JSON.stringify(c.expected, null, 2), rubric: sc.rubric, response: response.slice(0, 60_000) });
      const calls = await panel.ask(JUDGE_SYSTEM, user, 'judge');
      for (const call of calls) {
        if (call.error) continue;
        const raw = extractTagged(call.text, 'SCORE');
        const n = raw === null ? null : parseNumber(raw);
        if (n === null || n < 0 || n > 10) continue;
        verdicts.push({ contestantId: call.judgeId, score: n / 10, rationale: call.text.replace(/SCORE\s*:.*$/im, '').trim().slice(0, 1200) });
      }
      if (!verdicts.length) throw new Error('All judges failed to give a readable verdict.');
    } else throw new Error('This test has no rubric for AI judges.');
  } catch (err) {
    error = (err as Error).message;
  }
  const byId = new Map(judges.map((j) => [j.id, j]));
  const grades: AiGrade[] = verdicts.map((v) => {
    const j = byId.get(v.contestantId);
    return { judgeId: v.contestantId.replace(/@judge$/, ''), judgeLabel: j?.label ?? v.contestantId, vendor: j?.vendor ?? '', score: r4(v.score), label: v.label, rationale: v.rationale, at, batch, costUsd: Math.round((perJudge.get(v.contestantId)?.cost ?? 0) * 1e6) / 1e6, images: perJudge.get(v.contestantId)?.images ?? 0 };
  });
  const cost = transcript.reduce((s, e) => s + e.costUsd, 0);
  // Record the calls (and their cost) even when grading failed: money was spent.
  const latest = requireResult(runId, key);
  let next: CaseResult = {
    ...latest,
    transcript: [...(latest.transcript ?? []), ...transcript],
    metrics: { ...latest.metrics, judgeCostUsd: Math.round(((latest.metrics?.judgeCostUsd ?? 0) + cost) * 1e6) / 1e6 },
    aiGrades: [...(latest.aiGrades ?? []), ...grades],
    scoreDetail: { ...(latest.scoreDetail ?? {}), ...extraDetail },
  };
  if (grades.length) next = applyOfficial(next, spec, gradingPolicy());
  appendResult(next);
  return { key, ok: grades.length > 0, error: grades.length ? undefined : (error ?? 'No judge verdicts'), costUsd: cost, result: toLite(next) };
}

// ───────────────────────────── Performance summaries ─────────────────────────────

function summariesFile(runId: string): string {
  return join(runDir(runId), 'summaries.json');
}

function readAiSummaries(runId: string): Record<string, AiSummary> {
  const f = summariesFile(runId);
  if (!existsSync(f)) return {};
  try {
    return JSON.parse(readFileSync(f, 'utf8')) as Record<string, AiSummary>;
  } catch {
    return {};
  }
}

function unitAndKind(testId: string): { unit: string; kind: 'prompt' | 'program' } {
  const t = getTest(testId);
  if (!t) return { unit: 'question', kind: 'prompt' };
  return { unit: gradingSpecFor(t.definition).unit, kind: t.definition.kind };
}

function pairResults(runId: string): Map<string, CaseResult[]> {
  const groups = new Map<string, CaseResult[]>();
  for (const r of readResults(runId)) {
    const k = `${r.contestantId}|${r.testId}`;
    groups.set(k, [...(groups.get(k) ?? []), r]);
  }
  return groups;
}

function basisOf(rs: CaseResult[]): string {
  return sha256(JSON.stringify(rs.map((r) => [r.key, r.status, r.score]).sort()));
}

/** Template summaries for every model × test ("contestantId|testId" → text), plus cached AI ones. */
export function runSummaries(runId: string): RunSummaries {
  const m = requireRun(runId);
  const manual = new Set(loadProviders().filter((p) => p.type === 'manual').map((p) => p.id));
  const manualIds = new Set(m.contestants.filter((c) => manual.has(c.provider)).map((c) => c.id));
  const template: Record<string, string> = {};
  const groups = pairResults(runId);
  const cache = new Map<string, { unit: string; kind: 'prompt' | 'program' }>();
  for (const [k, rs] of groups) {
    const testId = rs[0]!.testId;
    let uk = cache.get(testId);
    if (!uk) cache.set(testId, (uk = unitAndKind(testId)));
    template[k] = templateSummary({ ...uk, results: rs, manual: manualIds.has(rs[0]!.contestantId) });
  }
  const ai: Record<string, AiSummary & { stale: boolean }> = {};
  for (const [k, s] of Object.entries(readAiSummaries(runId))) {
    const rs = groups.get(k);
    if (rs) ai[k] = { ...s, stale: s.basis !== basisOf(rs) };
  }
  return { template, ai };
}

/** A different-vendor writer for a contestant's summary: the cheapest configured judge with a key. */
function summaryWriter(con: Pick<Contestant, 'vendor' | 'model' | 'provider'>): Contestant | null {
  const pool = judgePool().filter((j) => j.vendor.toLowerCase() !== con.vendor.toLowerCase() && !(j.model === con.model && j.provider === con.provider));
  pool.sort((a, b) => a.pricing.outputPerM + a.pricing.inputPerM - (b.pricing.outputPerM + b.pricing.inputPerM));
  return pool[0] ?? null;
}

const SUMMARY_SYSTEM = `You write one short performance summary for a public AI benchmark video.
Rules:
- Use ONLY the facts given. Never invent numbers, reasons, quotes or comparisons.
- At most ${SUMMARY_WORD_LIMIT} words, plain English, past tense, no model or vendor names, no hype, no emoji.
- Say how it scored, where it lost points and why (from the facts), then time/cost if given.
- Reply with the summary only.`;

function summaryPrompt(con: ContestantSnapshot, testId: string, testName: string, rs: CaseResult[], template: string): string {
  const t = getTest(testId);
  const what = t ? explainerForDefinition(t.definition, t.definition.kind === 'program' ? PROGRAMS[t.definition.program]?.scoring : undefined).whatItTests : '';
  const cases = rs.slice(0, 30).map((r) => ({
    case: r.caseId,
    try: r.repeat + 1,
    status: r.status,
    score: r.score === null ? null : Math.round(r.score * 100),
    result: r.summary.slice(0, 160),
    whyLostPoints: r.score !== null && r.score < 1 ? missReason(r) : undefined,
  }));
  const rationales = rs
    .flatMap((r) => [...(r.scoreDetail?.judge ?? []), ...(r.aiGrades ?? [])].map((j) => j.rationale))
    .filter(Boolean)
    .slice(0, 3)
    .map((x) => x.replace(/\s+/g, ' ').slice(0, 300));
  const facts = {
    test: testName,
    whatItTests: what,
    cases,
    judgeRationales: rationales.length ? rationales : undefined,
    avgSecondsPerCase: Math.round((mean(rs.map((r) => r.metrics?.wallMs ?? 0)) ?? 0) / 100) / 10,
    costUsd: Math.round(rs.reduce((s, r) => s + (r.metrics?.costUsd ?? 0), 0) * 10000) / 10000,
  };
  void con;
  return `Facts (JSON):\n${JSON.stringify(facts, null, 1)}\n\nTemplate summary: ${template}\n\nWrite the performance summary (at most ${SUMMARY_WORD_LIMIT} words).`;
}

export function aiSummaryEstimate(runId: string, pairs?: string[]): SummaryEstimate {
  const m = requireRun(runId);
  const groups = pairResults(runId);
  const { template, ai } = runSummaries(runId);
  const keys = pairs?.length ? pairs : [...groups.keys()];
  const out: SummaryEstimate['pairs'] = [];
  for (const k of keys) {
    const rs = groups.get(k);
    const [contestantId = '', testId = ''] = k.split('|');
    if (!rs) {
      out.push({ key: k, contestantId, testId, writer: null, estUsd: 0, reason: 'No results', cached: false });
      continue;
    }
    const con = m.contestants.find((c) => c.id === contestantId);
    const w = con ? summaryWriter(con) : null;
    if (!w) {
      out.push({ key: k, contestantId, testId, writer: null, estUsd: 0, reason: `No judge model from another vendor has an API key.`, cached: false });
      continue;
    }
    const cached = Boolean(ai[k] && !ai[k]!.stale);
    const inputTokens = Math.ceil((SUMMARY_SYSTEM.length + summaryPrompt(con!, testId, m.tests.find((t) => t.id === testId)?.name ?? testId, rs, template[k] ?? '').length) / 3.8);
    const thinking = w.options?.effort && !['none', 'minimal', 'low'].includes(w.options.effort) ? 6 : 1;
    const estUsd = cached ? 0 : computeCost({ inputTokens, outputTokens: 80 * thinking, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 }, w.pricing);
    out.push({ key: k, contestantId, testId, writer: judgeInfo(w), estUsd: Math.round(estUsd * 1e6) / 1e6, cached });
  }
  const total = out.reduce((s, p) => s + p.estUsd, 0);
  return { pairs: out, totalUsd: Math.round(total * 1e6) / 1e6, totalUsdHigh: Math.round(total * 3 * 1e6) / 1e6 };
}

/** Write AI summaries (cached per model × test until the results change). Requires the confirmed cost. */
export async function aiSummaryGenerate(runId: string, pairs: string[] | undefined, confirmCostUsd: number): Promise<{ summaries: Record<string, AiSummary>; costUsd: number; errors: Record<string, string> }> {
  const est = aiSummaryEstimate(runId, pairs);
  if (!(typeof confirmCostUsd === 'number' && Number.isFinite(confirmCostUsd))) throw new Error('Confirm the cost first: call the estimate, then send confirmCostUsd.');
  if (est.totalUsd > confirmCostUsd * 1.05 + 0.0005) throw new Error(`The estimate is now $${est.totalUsd.toFixed(4)}, more than the $${confirmCostUsd.toFixed(4)} you confirmed.`);
  const m = requireRun(runId);
  const groups = pairResults(runId);
  const { template } = runSummaries(runId);
  const settings = loadSettings();
  const store = readAiSummaries(runId);
  const errors: Record<string, string> = {};
  let spent = 0;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), 10 * 60 * 1000);
  const sem = new Semaphore(4);
  try {
    await Promise.all(
      est.pairs.map(async (p) => {
        if (p.cached || !p.writer) {
          if (!p.writer && p.reason) errors[p.key] = p.reason;
          return;
        }
        const rs = groups.get(p.key)!;
        const con = m.contestants.find((c) => c.id === p.contestantId)!;
        const w = summaryWriter(con)!;
        const provider = getProvider(w.provider);
        const release = await sem.acquire();
        try {
          const res = await callWithRetry(
            { contestant: w, adapter: createAdapter(w, provider), semaphore: new Semaphore(1) },
            { system: SUMMARY_SYSTEM, messages: [{ role: 'user', content: summaryPrompt(con, p.testId, m.tests.find((t) => t.id === p.testId)?.name ?? p.testId, rs, template[p.key] ?? '') }], maxOutputTokens: 4000, temperature: 0 },
            { maxRetries: settings.maxRetries, temperature: 0, defaultMaxOutputTokens: 4000 },
            ctl.signal,
          );
          const cost = computeCost(res.usage, w.pricing);
          spent += cost;
          const text = clampWords(res.text.replace(/^["“'\s]+|["”'\s]+$/g, '').replace(/\s+/g, ' '));
          if (!text) {
            errors[p.key] = 'The writer returned an empty summary.';
            return;
          }
          store[p.key] = { text, writerId: w.id.replace(/@judge$/, ''), writerLabel: w.label, vendor: w.vendor, costUsd: Math.round(cost * 1e6) / 1e6, at: new Date().toISOString(), basis: basisOf(rs) };
        } catch (err) {
          errors[p.key] = (err as Error).message;
        } finally {
          release();
        }
      }),
    );
  } finally {
    clearTimeout(timer);
  }
  writeJsonAtomic(summariesFile(runId), store);
  return { summaries: store, costUsd: Math.round(spent * 1e6) / 1e6, errors };
}

/** Facts for the Studio script generator: one summary per model × test (AI-written when cached and current, else the template). */
export function performanceFacts(runId: string): Array<{ contestantId: string; testId: string; text: string; source: 'template' | 'ai' }> {
  let s: ReturnType<typeof runSummaries>;
  try {
    s = runSummaries(runId);
  } catch {
    return [];
  }
  return Object.entries(s.template).map(([k, text]) => {
    const [contestantId = '', testId = ''] = k.split('|');
    const ai = s.ai[k];
    return ai && !ai.stale ? { contestantId, testId, text: ai.text, source: 'ai' as const } : { contestantId, testId, text, source: 'template' as const };
  });
}

export type { OfficialPolicy };
