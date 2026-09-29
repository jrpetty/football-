/**
 * Mock Grading Station API (?mock=1). Uses the same pure grading code as the
 * server (grading specs, official-score policy, 30-word summaries), over the
 * demo run in gradingDemo.ts plus the regular mock runs. AI judges here are
 * simulated (clearly labelled demo verdicts); nothing is spent.
 */
import { ApiError } from '../api.ts';
import { mockArtifactUrls } from './registry.ts';
import { CONTESTANTS, PROGRAMS, SETTINGS, TESTS, artifactContent, detailFor, renderedOf } from './fixtures.ts';
import { GALLERY_PROGRAMS, GALLERY_RUN_SPEC, GALLERY_TESTS } from './galleryMock.ts';
import { GAME_JAM_RUN_SPEC } from './gameJamMock.ts';
import { budgetApi, mockRecordSpend } from '../budget/budgetApi.ts';
import { budgetLine, MIN_BUDGET_LEFT_USD } from '../../../src/budget/budget.ts';
import { applyGalleryHumanScores, isGalleryResult } from '../../../src/engine/gallery-review.ts';
import { money } from '../money.ts';
import { mockRunForFeatures } from './mockServer.ts';
import { DEMO_RUN_ID, DEMO_RUN_NAME, demoData, demoImages, demoTest, demoTurns } from './gradingDemo.ts';
import type { AiGrade, CaseResult, CaseResultLite, TestDefinition } from '../types.ts';
import { gradingSpecFor, requirementPoints, scoreFromGrade, type GradingSpec } from '../../../src/grading/spec.ts';
import { applyOfficial, humanMean, needOf, runAiScore, stationAiScore } from '../../../src/grading/official.ts';
import { agreement, type OfficialPolicy } from '../../../src/grading/policy.ts';
import { templateSummary } from '../../../src/grading/summary.ts';
import { explainerForDefinition } from '../../../src/core/explainers.ts';
import type { AiEstimate, AiSummary, BudgetCheck, GradingRunInfo, HumanGradeInput, QueueItem, RunSummaries, StationItem } from '../../../src/grading/types.ts';

const STATION_RUNS = [DEMO_RUN_ID, GAME_JAM_RUN_SPEC.id, GALLERY_RUN_SPEC.id, 'run-2026-09-12-creative', 'run-2026-09-21-core'];

/** The mock month's budget (budgetApi's demo month), as the server's spendCheck would report it. */
async function budgetCheck(estUsd: number, what: string): Promise<BudgetCheck> {
  const st = await budgetApi.get();
  const line = budgetLine(st, st.currency, undefined, what).replace(/ · this .*$/, '');
  const avail = st.availableUsd;
  const base = { line, availableUsd: avail, hardStop: !!st.settings.hardStop };
  if (st.settings.monthlyUsd === undefined || avail === null) return { ...base, blocked: false };
  if (st.settings.hardStop && avail < MIN_BUDGET_LEFT_USD) return { ...base, blocked: true, message: `Your ${money(st.settings.monthlyUsd)} monthly budget is used up, so the hard stop will not let ${what} spend anything.` };
  if (st.settings.hardStop && estUsd > avail) return { ...base, blocked: true, message: `This would cost about ${money(estUsd)}, but only ${money(avail)} is left of your monthly budget, so the hard stop blocks it.` };
  return { ...base, blocked: false };
}
let policy: OfficialPolicy = 'methodology';
const store = new Map<string, Map<string, CaseResult>>();
const aiSummaries = new Map<string, Record<string, AiSummary>>();

function testDef(id: string): TestDefinition | undefined {
  return demoTest(id) ?? TESTS.find((t) => t.id === id) ?? GALLERY_TESTS.find((t) => t.id === id);
}

function specOf(r: Pick<CaseResult, 'testId' | 'caseId'>): GradingSpec {
  const d = testDef(r.testId);
  if (!d) return { testId: r.testId, testName: r.testId, kind: 'objective', scorerType: 'unknown', gradedOn: 'Unknown test.', howScored: [], humanRole: 'dispute', aiRole: 'none', unit: 'question', criteria: [], scaleMax: 0, checklist: [], passThreshold: 1, rules: [], output: 'text', minJudges: 0 };
  const programScoring = d.kind === 'program' ? [...PROGRAMS, ...GALLERY_PROGRAMS].find((p) => p.id === d.program)?.scoring : undefined;
  return gradingSpecFor(d, { caseId: r.caseId, programScoring });
}

async function results(runId: string): Promise<Map<string, CaseResult>> {
  let m = store.get(runId);
  if (m) return m;
  m = new Map();
  if (runId === DEMO_RUN_ID) {
    const d = await demoData();
    for (const [k, v] of Object.entries(d.artifacts)) mockArtifactUrls.set(k, v);
    for (const r of d.results) m.set(r.key, r);
  } else {
    const run = mockRunForFeatures(runId);
    if (!run) throw new ApiError('Run not found', 404);
    for (const lite of run.results) m.set(lite.key, lite as unknown as CaseResult);
  }
  store.set(runId, m);
  return m;
}

/** Full result (mock runs keep only light rows until an item is opened). */
async function full(runId: string, key: string): Promise<CaseResult> {
  const m = await results(runId);
  const r = m.get(key);
  if (!r) throw new ApiError('Result not found', 404);
  if (!r.transcript) {
    const f = detailFor(r as unknown as CaseResultLite);
    for (const art of f.artifacts ?? []) if (!mockArtifactUrls.has(`${runId}/${art.file}`)) mockArtifactUrls.set(`${runId}/${art.file}`, `data:${art.kind === 'svg' ? 'image/svg+xml' : art.kind === 'html' ? 'text/html' : 'text/plain'};charset=utf-8,${encodeURIComponent(artifactContent(art))}`);
    const merged = { ...f, humanScores: r.humanScores, aiGrades: r.aiGrades, disputes: r.disputes, score: r.score, status: r.status, summary: r.summary, scoreDetail: { ...f.scoreDetail, ...r.scoreDetail } };
    m.set(key, merged);
    return merged;
  }
  return r;
}

function lite(r: CaseResult): CaseResultLite {
  const { transcript: _t, replay, ...rest } = r;
  return { ...rest, hasReplay: Boolean(replay) } as CaseResultLite;
}

function runName(id: string): string {
  return id === DEMO_RUN_ID ? DEMO_RUN_NAME : mockRunForFeatures(id)?.manifest.name ?? id;
}

function testName(id: string): string {
  return testDef(id)?.name ?? id;
}

async function queue(runId: string): Promise<QueueItem[]> {
  const out: QueueItem[] = [];
  for (const r of (await results(runId)).values()) {
    if (r.status === 'cancelled' || r.status === 'skipped') continue;
    const spec = specOf(r);
    if (r.status === 'error' && !/judges failed/i.test(r.error ?? '')) continue;
    const need = needOf(r, spec);
    const humans = r.humanScores?.length ?? 0;
    const ais = r.aiGrades?.length ?? 0;
    const todo = need === 'grade' ? humans === 0 && !(ais > 0 && policy !== 'methodology') : need === 'arbitrate' ? humans === 0 : need === 'judge-failed' ? humans === 0 && ais === 0 : false;
    out.push({ runId, key: r.key, testId: r.testId, testName: testName(r.testId), category: testDef(r.testId)?.category ?? '', caseId: r.caseId, repeat: r.repeat, contestantId: r.contestantId, status: r.status, score: r.score, summary: r.summary, kind: spec.kind, humanRole: spec.humanRole, aiRole: spec.aiRole, need, todo, humans, ais, disputes: r.disputes?.length ?? 0, official: (r.scoreDetail?.official as { source?: QueueItem['official'] } | undefined)?.source, hasReplay: Boolean(r.replay) || Boolean((r as unknown as CaseResultLite).hasReplay), artifacts: (r.artifacts ?? []).map((a) => a.name) });
  }
  // Group by test, keeping each test's first appearance.
  const order = new Map<string, number>();
  out.forEach((q) => order.has(q.testId) || order.set(q.testId, order.size));
  return out.sort((a, b) => order.get(a.testId)! - order.get(b.testId)!);
}

function judgesFor(contestantId: string) {
  const vendor = CONTESTANTS.find((c) => c.id === contestantId)?.vendor ?? '';
  return SETTINGS.judges
    .map((id) => CONTESTANTS.find((c) => c.id === id)!)
    .filter((j) => j && j.vendor !== vendor)
    .map((j) => ({ id: j.id, label: j.label, vendor: j.vendor, vision: j.vision ?? true, hasKey: true }));
}

async function item(runId: string, key: string): Promise<StationItem> {
  const r = await full(runId, key);
  const d = testDef(r.testId);
  const spec = specOf(r);
  const con = CONTESTANTS.find((c) => c.id === r.contestantId);
  const rendered = d?.kind === 'prompt' ? (demoTest(d.id) ? { caseId: r.caseId, system: d.system, turns: demoTurns(d, r.caseId), expected: d.cases.find((c) => c.id === r.caseId)?.expected, images: demoImages(d, r.caseId) } : renderedOf(d).find((x) => x.caseId === r.caseId) ?? null) : null;
  const judges = judgesFor(r.contestantId);
  const human = humanMean(r);
  const ai = stationAiScore(r) ?? runAiScore(r, spec);
  return {
    result: r,
    spec,
    rendered,
    test: d ? { id: d.id, name: d.name, category: d.category, kind: d.kind, description: d.description, hook: d.hook } : null,
    contestant: con ? { id: con.id, label: con.label, vendor: con.vendor, color: con.color, manual: con.providerType === 'manual' } : null,
    explainer: d ? explainerForDefinition(d) : null,
    program: d?.kind === 'program' ? { id: d.program, name: d.name, scoring: spec.formula ?? '' } : undefined,
    policy,
    official: (r.scoreDetail?.official as StationItem['official'] | undefined) ?? {},
    agreement: agreement(human, ai),
    humanScore: human,
    aiScore: ai,
    aiPanel: { judges, ok: judges.length >= 2 && spec.aiRole !== 'none', reason: spec.aiRole === 'none' ? 'Scored by machine: there is nothing for an AI judge to grade.' : judges.length < 2 ? 'Needs at least 2 judges from other vendors.' : undefined },
  };
}

async function human(b: HumanGradeInput): Promise<CaseResultLite> {
  if (!b.rater?.trim()) throw new ApiError('Enter your rater name first: it is stored with every grade.', 400);
  const r = await full(b.runId, b.key);
  const spec = specOf(r);
  if (spec.humanRole === 'dispute') throw new ApiError('This result is scored by machine against an answer key: flag a dispute instead of grading it.', 400);
  const criteria = { ...(b.criteria ?? {}) };
  for (const c of spec.criteria) {
    if (!c.requirements) continue;
    const states = Object.fromEntries(c.requirements.items.map((it) => [it.id, b.requirements?.[`${c.id}:${it.id}`]]));
    if (Object.values(states).some(Boolean)) criteria[c.id] = requirementPoints(c, states);
  }
  const score = scoreFromGrade(spec, { criteria, label: b.label });
  if (score === null) throw new ApiError('Grade every criterion before saving.', 400);
  const humanScores = [...(r.humanScores ?? []).filter((h) => h.rater !== b.rater.trim()), { rater: b.rater.trim(), score, at: new Date().toISOString(), note: b.note, criteria, requirements: b.requirements, label: b.label, blind: b.blind }];
  if (isGalleryResult(r)) {
    // The Gallery's own rule: the owner's artistry replaces the judges' artistry (same code as the server).
    const g = applyGalleryHumanScores({ ...r, humanScores });
    g.scoreDetail = { ...g.scoreDetail, official: { source: 'human', policy, why: 'The Gallery’s rule: your artistry rating replaces the judges’ artistry; brief adherence stays as judged.' } };
    (await results(b.runId)).set(b.key, g);
    return lite(g);
  }
  const next = applyOfficial({ ...r, humanScores }, spec, policy);
  (await results(b.runId)).set(b.key, next);
  return lite(next);
}

async function estimate(runId: string, keys: string[], m: Map<string, CaseResult>): Promise<AiEstimate> {
  const items = keys.map((k) => {
    const r = m.get(k);
    if (!r) return { key: k, ok: false, reason: 'Result not found', judges: [], estUsd: 0 };
    const spec = specOf(r);
    if (spec.aiRole === 'none') return { key: k, ok: false, reason: 'Scored by machine: nothing for an AI judge to grade.', judges: [], estUsd: 0 };
    // Same pictures as the server: playtest frames + motion strip for the Game Jam, the painting for the Gallery, else screenshots and test images.
    const images = spec.playtest ? spec.playtest.screenshots + (spec.playtest.motionStrip ? 1 : 0) : isGalleryResult(r) ? 1 : (r.artifacts ?? []).filter((a) => a.kind === 'png').length + (demoImages(testDef(r.testId)!, r.caseId)?.length ?? 0);
    const text = spec.playtest ? 0.03 : 0.0042;
    const js = judgesFor(r.contestantId)
      .filter((j) => !isGalleryResult(r) || j.vision)
      .map((j) => ({ ...j, images: j.vision ? images : 0, estUsd: text + (j.vision ? images * 0.0011 : 0) }));
    return { key: k, ok: js.length >= 2, reason: js.length < 2 ? 'Needs at least 2 judges from other vendors.' : undefined, judges: js, estUsd: js.reduce((s, j) => s + j.estUsd, 0) };
  });
  void runId;
  const total = Math.round(items.reduce((s, i) => s + i.estUsd, 0) * 1e6) / 1e6;
  return { items, totalUsd: total, totalUsdHigh: Math.round(total * 2.5 * 1e6) / 1e6, gradable: items.filter((i) => i.ok).length, budget: await budgetCheck(total, 'AI grading') };
}

const DEMO_RATIONALES = [
  'Demo verdict (mock mode): the answer covers the brief and the requirements are mostly met; one detail falls short of the rubric.',
  'Demo verdict (mock mode): solid work against the rubric, with a noticeable gap in one criterion.',
  'Demo verdict (mock mode): partly meets the rubric; several requirements are missing or broken.',
];

async function aiGrade(runId: string, keys: string[]): Promise<{ outcomes: Array<{ key: string; ok: boolean; error?: string; costUsd: number; result?: CaseResultLite }>; costUsd: number }> {
  const m = await results(runId);
  const est = await estimate(runId, keys, m);
  if (est.budget.blocked) throw new ApiError(est.budget.message ?? 'The monthly budget’s hard stop blocks this.', 409);
  const outcomes = [];
  let spent = 0;
  for (const it of est.items) {
    if (!it.ok) {
      outcomes.push({ key: it.key, ok: false, error: it.reason, costUsd: 0 });
      continue;
    }
    const r = await full(runId, it.key);
    const spec = specOf(r);
    const batch = Math.random().toString(36).slice(2, 10);
    const at = new Date().toISOString();
    const grades: AiGrade[] = it.judges.map((j, i) => {
      const seed = [...(it.key + j.id)].reduce((s, c) => s + c.charCodeAt(0), 0);
      const label = spec.labels?.length ? spec.labels.filter((l) => l.applies)[seed % spec.labels.filter((l) => l.applies).length] : undefined;
      const score = label ? label.score : Math.round(((seed % 7) + 3) / 10 * 100) / 100;
      return { judgeId: j.id, judgeLabel: j.label, vendor: j.vendor, score, label: label?.id, rationale: DEMO_RATIONALES[(seed + i) % 3]!, at, batch, costUsd: j.estUsd, images: j.images };
    });
    spent += it.estUsd;
    const withGrades = { ...r, aiGrades: [...(r.aiGrades ?? []), ...grades] };
    const next = isGalleryResult(r) ? withGrades : applyOfficial(withGrades, spec, policy);
    m.set(it.key, next);
    outcomes.push({ key: it.key, ok: true, costUsd: it.estUsd, result: lite(next) });
  }
  const graded = outcomes.filter((o) => o.ok).length;
  mockRecordSpend({ kind: 'grade', name: `Grading Station: AI judges graded ${graded} answer${graded === 1 ? '' : 's'} (${runName(runId)})`, spentUsd: spent });
  return { outcomes, costUsd: spent };
}

function unitOf(testId: string): { unit: string; kind: 'prompt' | 'program' } {
  const d = testDef(testId);
  return d ? { unit: gradingSpecFor(d).unit, kind: d.kind } : { unit: 'question', kind: 'prompt' };
}

async function summaries(runId: string): Promise<RunSummaries> {
  let rows: CaseResult[];
  try {
    rows = [...(await results(runId)).values()];
  } catch {
    return { template: {}, ai: {} };
  }
  const groups = new Map<string, CaseResult[]>();
  for (const r of rows) groups.set(`${r.contestantId}|${r.testId}`, [...(groups.get(`${r.contestantId}|${r.testId}`) ?? []), r]);
  const template: Record<string, string> = {};
  for (const [k, rs] of groups) template[k] = templateSummary({ ...unitOf(rs[0]!.testId), results: rs, manual: CONTESTANTS.find((c) => c.id === rs[0]!.contestantId)?.providerType === 'manual' });
  const ai: RunSummaries['ai'] = {};
  for (const [k, s] of Object.entries(aiSummaries.get(runId) ?? {})) ai[k] = { ...s, stale: false };
  return { template, ai };
}

export async function handleGrading(method: string, parts: string[], q: URLSearchParams, body: unknown): Promise<unknown> {
  const [a, b, c, d] = parts;
  if (a === 'settings') {
    if (method === 'PUT') policy = ((body as { official?: OfficialPolicy })?.official ?? policy) as OfficialPolicy;
    return { official: policy, policies: ['methodology', 'human', 'ai', 'average'] };
  }
  if (a === 'spec' && b) {
    const def = testDef(b);
    if (!def) throw new ApiError('Test not found', 404);
    return gradingSpecFor(def, { caseId: q.get('case') ?? undefined });
  }
  if (a === 'runs' && !b) {
    const runs: GradingRunInfo[] = [];
    for (const id of STATION_RUNS) {
      const items = await queue(id).catch(() => [] as QueueItem[]);
      runs.push({ id, name: runName(id), createdAt: id === DEMO_RUN_ID ? '2026-09-27T18:00:00Z' : mockRunForFeatures(id)?.manifest.createdAt ?? '', status: 'completed', results: items.length, todo: items.filter((i) => i.todo).length, gradable: items.filter((i) => i.humanRole !== 'dispute').length });
    }
    return { runs, arena: [{ id: 'arena-demo-debate', name: 'Debate Night', game: 'debate', gameName: 'Debate', pending: 2 }], official: policy };
  }
  if (a === 'queue') return { items: await queue(q.get('runId') ?? DEMO_RUN_ID), official: policy };
  if (a === 'item' && b && c) return item(b, c);
  if (a === 'human' && method === 'POST') return human(body as HumanGradeInput);
  if (a === 'dispute' && method === 'POST') {
    const x = body as { runId: string; key: string; rater: string; note: string };
    if (!x.rater?.trim()) throw new ApiError('Enter your rater name first.', 400);
    const r = await full(x.runId, x.key);
    const next = { ...r, disputes: [...(r.disputes ?? []), { rater: x.rater, note: x.note, at: new Date().toISOString() }] };
    (await results(x.runId)).set(x.key, next);
    return lite(next);
  }
  if (a === 'ai' && b === 'estimate') {
    const x = body as { runId: string; keys: string[] };
    const m = await results(x.runId);
    for (const k of x.keys) await full(x.runId, k).catch(() => null);
    return await estimate(x.runId, x.keys, m);
  }
  if (a === 'ai' && b === 'grade') {
    const x = body as { runId: string; keys: string[] };
    await new Promise((r) => setTimeout(r, 900));
    return aiGrade(x.runId, x.keys);
  }
  if (a === 'runs' && b && c === 'reapply') {
    const m = await results(b);
    let n = 0;
    for (const [k, r] of m) {
      if (!r.humanScores?.length && !r.aiGrades?.length) continue;
      m.set(k, applyOfficial(r, specOf(r), policy));
      n++;
    }
    return { updated: n };
  }
  if (a === 'runs' && b && c === 'summaries') {
    const s = await summaries(b);
    if (method === 'GET') return s;
    const pairs = ((body as { pairs?: string[] })?.pairs ?? Object.keys(s.template)).filter((k) => s.template[k]);
    const writer = (cid: string) => judgesFor(cid)[0];
    if (d === 'estimate') {
      const list = pairs.map((k) => ({ key: k, contestantId: k.split('|')[0]!, testId: k.split('|')[1]!, writer: writer(k.split('|')[0]!) ?? null, estUsd: s.ai[k] ? 0 : 0.0009, cached: !!s.ai[k] }));
      const total = list.reduce((t, p) => t + p.estUsd, 0);
      return { pairs: list, totalUsd: total, totalUsdHigh: total * 3, budget: await budgetCheck(total, 'AI summaries') };
    }
    const out = { ...(aiSummaries.get(b) ?? {}) };
    for (const k of pairs) {
      const w = writer(k.split('|')[0]!);
      if (w && !out[k]) out[k] = { text: s.template[k]!, writerId: w.id, writerLabel: w.label, vendor: w.vendor, costUsd: 0.0009, at: new Date().toISOString(), basis: 'demo' };
    }
    aiSummaries.set(b, out);
    mockRecordSpend({ kind: 'other', name: `AI-written performance summaries (${runName(b)})`, spentUsd: pairs.length * 0.0009 });
    return { summaries: out, costUsd: pairs.length * 0.0009, errors: {} };
  }
  throw new ApiError(`Mock: no grading route for ${method} ${parts.join('/')}`, 404);
}
