/**
 * Mock mode (`?mock=1`) for copy & paste models: the real model catalogue, two waiting Inbox prompts (one for a
 * catalogue model, one that doesn't say which model yet), old "unspecified" results to reassign, and a made-up
 * "Best on each test" data set (the page says it is demo data).
 */
import catalogJson from '../../../config/model-catalog.json';
import { ApiError } from '../api.ts';
import type { CategoryInfo, ContestantView, ManualModelInfo, ManualRequest } from '../types.ts';
import { buildManualContestant, copiedByHandText, normalizeInfo, releaseDay, type CatalogModel, type ModelCatalog, type UnspecifiedGroup } from '../../../src/manual-models/identity.ts';
import type { BestData, BestEntry, BestModel } from '../../../src/manual-models/best-rank.ts';
import { CATEGORIES, TESTS, rngFrom } from './fixtures.ts';

const catalog: ModelCatalog = structuredClone(catalogJson) as unknown as ModelCatalog;
const model = (id: string) => catalog.models.find((m) => m.id === id)!;
const vendor = (m: CatalogModel) => catalog.vendors.find((v) => v.id === m.vendor);

function view(info: ManualModelInfo): ContestantView {
  const m = model(info.catalogId);
  const c = buildManualContestant(m, normalizeInfo(info), vendor(m));
  return { ...c, configHash: `mm${c.id.length.toString(16)}${c.id.slice(-6)}`, hasKey: true, providerLabel: 'Manual (copy & paste)', providerType: 'manual' };
}

const made = new Map<string, ContestantView>();
function ensure(info: ManualModelInfo): ContestantView {
  const v = view(info);
  if (!made.has(v.id)) made.set(v.id, v);
  return made.get(v.id)!;
}

const OPUS3 = ensure({ catalogId: 'claude-3-opus', interface: 'company-app', thinking: 'default', webSearch: false });
ensure({ catalogId: 'gpt-4o', interface: 'api-playground', thinking: 'default', webSearch: false });

const GENERIC: ContestantView = {
  id: 'manual-chat',
  label: 'Manual entry (unspecified model)',
  vendor: 'Manual',
  provider: 'manual',
  model: 'any',
  color: '#EAB308',
  enabled: true,
  pricing: { inputPerM: 0, outputPerM: 0, source: 'Manual entry', verifiedAt: '2026-09-24' },
  configHash: 'mmgeneric',
  hasKey: true,
  providerLabel: 'Manual (copy & paste)',
  providerType: 'manual',
};

/** Contestants the mock model list gains: the generic entry and two catalogue models. */
export function mockManualContestants(): ContestantView[] {
  return [GENERIC, ...made.values()];
}

// ───────────────────────────── Inbox prompts ─────────────────────────────

const RUN = 'run-2026-09-23-manual';
const answered = new Set<string>();
const known = new Map<string, ManualRequest>();

function req(id: string, c: ContestantView, testId: string, caseId: string, minsAgo: number): ManualRequest {
  const t = TESTS.find((x) => x.id === testId)!;
  const prompt = t.kind === 'prompt' ? `${t.cases.find((x) => x.id === caseId)?.prompt ?? t.cases[0]!.prompt}\n\nEnd your reply with a line: FINAL ANSWER: <number>` : 'Play the game.';
  return {
    id,
    runId: RUN,
    key: `${c.id}::${testId}::${caseId}::r0`,
    contestantId: c.id,
    contestantLabel: c.label,
    testId,
    testName: t.name,
    caseId,
    label: 'response',
    system: t.kind === 'prompt' ? t.system : undefined,
    messages: [{ role: 'user', content: prompt }],
    combinedPrompt: t.kind === 'prompt' && t.system ? `[SYSTEM INSTRUCTIONS — follow these for the whole conversation]\n${t.system}\n\n[USER]\n${prompt}\n\n[Reply to the last USER message only.]` : prompt,
    latestUserMessage: prompt,
    isContinuation: false,
    createdAt: new Date(Date.now() - minsAgo * 60_000).toISOString(),
  };
}

/** Waiting prompts added to the mock Inbox. */
export function mockManualModelRequests(): ManualRequest[] {
  const list = [req('mm-demo-opus3', OPUS3, 'reasoning.river-crossing', 'goat-rope', 31), req('mm-demo-generic-1', GENERIC, 'math.probability-traps', 'monty-four', 30), req('mm-demo-generic-2', GENERIC, 'reasoning.river-crossing', 'lantern-bridge', 29)];
  return list.filter((r) => !answered.has(r.id));
}

/** Every prompt the Inbox has shown (so a model can be picked for any of them). */
export function rememberMockRequests(list: ManualRequest[]): void {
  for (const r of list) known.set(r.id, r);
}

/** Answer one of this module's prompts; false when the id is not ours. */
export function answerMockManualModel(id: string): boolean {
  if (!id.startsWith('mm-demo-') || answered.has(id)) return false;
  answered.add(id);
  return true;
}

// ───────────────────────────── Picks and reassigning ─────────────────────────────

interface Choice {
  runId: string;
  caseKey: string;
  contestantId: string;
  contestantLabel: string;
  info: ManualModelInfo;
  at: string;
  locked?: boolean;
}
const choices = new Map<string, Choice>();
const reassigned = new Set<string>();

const UNSPECIFIED: UnspecifiedGroup[] = [
  { runId: 'run-2026-09-18-orbit', runName: 'Chat apps by hand · first try', createdAt: '2026-09-18T10:00:00Z', contestantId: 'manual-chat', contestantLabel: 'Manual entry (unspecified model)', testId: 'reasoning.knights-knaves', testName: 'Knights & Knaves', keys: ['manual-chat::reasoning.knights-knaves::k1::r0', 'manual-chat::reasoning.knights-knaves::k2::r0', 'manual-chat::reasoning.knights-knaves::k3::r0'], scored: 3, lastAt: '2026-09-18T11:20:00Z' },
  { runId: 'run-2026-09-18-orbit', runName: 'Chat apps by hand · first try', createdAt: '2026-09-18T10:00:00Z', contestantId: 'manual-chat', contestantLabel: 'Manual entry (unspecified model)', testId: 'honesty.honesty-trap', testName: 'The Honesty Trap', keys: ['manual-chat::honesty.honesty-trap::h1::r0', 'manual-chat::honesty.honesty-trap::h2::r0'], scored: 2, lastAt: '2026-09-18T11:40:00Z' },
];

// ───────────────────────────── Best on each test (made-up scores) ─────────────────────────────

const BEST_MODELS: Array<{ catalogId: string; manual?: ManualModelInfo['interface']; web?: boolean }> = [
  { catalogId: 'claude-3-opus', manual: 'company-app' },
  { catalogId: 'gpt-4', manual: 'api-playground' },
  { catalogId: 'gpt-4o', manual: 'api-playground' },
  { catalogId: 'claude-3-5-sonnet-20240620', manual: 'openrouter' },
  { catalogId: 'gemini-1-5-pro', manual: 'openrouter' },
  { catalogId: 'llama-3-1-405b', manual: 'openrouter' },
  { catalogId: 'o1', manual: 'api-playground' },
  { catalogId: 'deepseek-r1', manual: 'openrouter', web: true },
  { catalogId: 'claude-3-7-sonnet', manual: 'poe' },
  { catalogId: 'gemini-2-5-pro' },
  { catalogId: 'gpt-5' },
  { catalogId: 'claude-opus-4-6' },
  { catalogId: 'grok-4-7' },
  { catalogId: 'gpt-5-6-sol' },
  { catalogId: 'claude-opus-5-5' },
  { catalogId: 'claude-fable-5-1' },
];

function bestData(): BestData {
  const models: BestModel[] = BEST_MODELS.map((b) => {
    const m = model(b.catalogId);
    const v = vendor(m);
    if (b.manual) {
      const info = normalizeInfo({ catalogId: m.id, interface: b.manual, webSearch: b.web });
      const c = buildManualContestant(m, info, v);
      return { id: c.id, label: c.label, vendor: m.vendor, color: c.color, family: m.family, releaseDate: releaseDay(m), manual: true, manualModel: info, howLabel: copiedByHandText(info, v) };
    }
    return { id: m.id, label: m.label, vendor: m.vendor, color: v?.color ?? '#64748B', family: m.family, releaseDate: releaseDay(m), manual: false, howLabel: '' };
  });
  const testIds = ['reasoning.river-crossing', 'reasoning.knights-knaves', 'math.competition-mix', 'math.probability-traps', 'coding.interval-merge', 'instruction.constraint-gauntlet', 'honesty.honesty-trap', 'extraction.invoice-json'];
  const t0 = Date.parse('2024-03-01');
  const t1 = Date.parse('2026-09-28');
  const tests = testIds.map((tid, ti) => {
    const t = TESTS.find((x) => x.id === tid)!;
    const rng = rngFrom(`best-${tid}`);
    const entries: BestEntry[] = models.flatMap((m, mi) => {
      // Not every model took every test (missing data); one test has a tie at the top.
      if ((mi + ti) % 5 === 3 && mi < 12) return [];
      const age = (Date.parse(m.releaseDate!) - t0) / (t1 - t0);
      const n = m.manual ? 8 : 24;
      const reasoning = model(m.manualModel?.catalogId ?? m.id).reasoning ? 0.1 : 0;
      let score = Math.round(Math.min(0.99, Math.max(0.02, 0.2 + 0.66 * age + reasoning + (rng.next() - 0.5) * 0.16)) * 1000) / 1000;
      if (tid === 'math.competition-mix' && (m.id === 'claude-opus-5-5' || m.id === 'gpt-5-6-sol')) score = 0.958;
      const day = 1 + ((mi * 3 + ti) % 26);
      return [{ contestantId: m.id, score: m.id === 'o1' && ti === 2 ? null : score, ci95: null, n: m.id === 'o1' && ti === 2 ? 0 : n, attempts: n, passRate: null, lastTestedAt: `2026-09-${String(day).padStart(2, '0')}T12:00:00Z`, runs: m.manual ? 1 : 2, reassigned: m.id.startsWith('manual.claude-3-opus') && ti === 1 ? 3 : 0 }];
    });
    return { id: tid, name: t.name, category: t.category, entries };
  });
  const cats: CategoryInfo[] = CATEGORIES.filter((c) => tests.some((t) => t.category === c.id));
  return { generatedAt: new Date().toISOString(), categories: cats, tests, models, staleExcluded: 0 };
}

// ───────────────────────────── Routes ─────────────────────────────

export async function handleManualModels(method: string, parts: string[], q: URLSearchParams, body: unknown): Promise<unknown> {
  const [a, b, c] = parts;
  if (a === 'best-per-test') return bestData();
  const route = `${method} ${b ?? ''}`;
  switch (route) {
    case 'GET catalog':
      return { ...catalog, problems: [] };
    case 'POST catalog': {
      const x = body as { label?: string; vendor?: string; released?: string };
      if (!x.label?.trim()) throw new ApiError('Give the model a name', 400);
      if (!/^\d{4}-\d{2}$/.test(x.released ?? '')) throw new ApiError('Release month must look like 2024-03', 400);
      const m: CatalogModel = { id: `custom-${x.label.toLowerCase().replace(/[^a-z0-9.]+/g, '-')}`, label: x.label.trim(), vendor: x.vendor || 'Other', family: x.vendor || 'Other', released: x.released!, status: 'unverified', access: { api: 'unknown', chatApp: 'unknown' }, vision: false, reasoning: false, source: 'Added by you in Gauntlet (not verified)', verifiedAt: null, custom: true };
      catalog.models.push(m);
      return m;
    }
    case 'POST contestants': {
      const list = ((body as { models?: ManualModelInfo[] }).models ?? []).map((i) => ensure(normalizeInfo(i)));
      return list;
    }
    case 'GET choices':
      return [...choices.values()].filter((x) => !q.get('runId') || x.runId === q.get('runId'));
    case 'POST choice': {
      const x = body as { requestId: string; choice: ManualModelInfo };
      const r = known.get(x.requestId);
      if (!r) throw new ApiError('This prompt is no longer waiting', 404);
      const k = `${r.runId}|${r.key}`;
      const cv = ensure(normalizeInfo(x.choice));
      const prev = choices.get(k);
      if (prev?.locked && prev.contestantId !== cv.id) throw new ApiError(`This conversation was already answered with ${prev.contestantLabel}`, 409);
      const choice: Choice = { runId: r.runId, caseKey: r.key, contestantId: cv.id, contestantLabel: cv.label, info: cv.manualModel!, at: new Date().toISOString() };
      choices.set(k, choice);
      return { choice, contestant: cv };
    }
    case 'DELETE choice': {
      const r = known.get(c ?? '');
      if (r) choices.delete(`${r.runId}|${r.key}`);
      return { ok: true };
    }
    case 'GET unspecified':
      return UNSPECIFIED.filter((g) => !g.keys.every((k) => reassigned.has(k)));
    case 'POST reassign': {
      const x = body as { keys: string[]; to: ManualModelInfo };
      const to = ensure(normalizeInfo(x.to));
      let moved = 0;
      for (const k of x.keys) if (!reassigned.has(k)) (reassigned.add(k), moved++);
      return { moved, skipped: [], to: { id: to.id, label: to.label } };
    }
    case 'GET reassign-log':
      return [];
  }
  throw new ApiError(`Mock: no route for ${method} /api/${parts.join('/')}`, 404);
}
