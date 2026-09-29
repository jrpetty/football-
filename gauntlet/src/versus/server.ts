/**
 * Head to Head HTTP routes (docs/API.md → "Head to Head"). Read-only: every
 * number comes from stored results; no model is called. Registered by
 * src/server/index.ts through `registerVersusRoutes`.
 *
 *   GET  /api/versus?a=<id>&b=<id>[&run=<runId>][&suite=<suiteId>]
 *   GET  /api/versus/options[?run=<runId>][&suite=<suiteId>]
 *   POST /api/versus/render  { a, b, run? }  → the vertical Shorts card as a PNG
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { contestantConfigHash, loadCategories, loadContestants, loadProviders } from '../core/config.ts';
import { loadTests, resolveTests } from '../core/registry.ts';
import type { CaseResult, Contestant } from '../core/types.ts';
import { listRunIds, listRuns, readManifest, readResults } from '../engine/store.ts';
import { browserHint, renderPng } from '../media/studio.ts';
import { buildVersus, buildVersusOptions, fighterOf, VersusInputError, type VersusTestInfo } from './build.ts';
import { renderVersusCardHtml, versusCardFileName, VERSUS_CARD } from './card.ts';
import type { VersusData, VersusFighter, VersusOptions, VersusScope } from './types.ts';

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

export interface VersusRouteDeps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string, details?: unknown) => Error;
}

interface Scope {
  scope: VersusScope;
  tests: VersusTestInfo[];
  fighters: VersusFighter[];
  results: CaseResult[];
}

function flagsFor(c: Pick<Contestant, 'provider'>): { baseline?: boolean; manual?: boolean } {
  const type = loadProviders().find((p) => p.id === c.provider)?.type;
  return { baseline: type === 'mock', manual: type === 'manual' };
}

function hooks(): Map<string, string | undefined> {
  return new Map(loadTests().map((t) => [t.definition.id, t.definition.hook]));
}

/**
 * Load a scope. With a run: that run's results, tests and model snapshots.
 * Without: every stored sample whose test hash and model config still match —
 * the same pool the combined leaderboard uses.
 */
export function loadScope(runId?: string, suiteId?: string): Scope {
  if (runId) {
    const manifest = readManifest(runId);
    if (!manifest) throw new VersusInputError(404, 'Run not found');
    const hook = hooks();
    return {
      scope: { kind: 'run', runId: manifest.id, runName: manifest.name || manifest.id },
      tests: manifest.tests.map((t) => ({ id: t.id, name: t.name, category: t.category, hook: hook.get(t.id) })),
      fighters: manifest.contestants.map((c) => fighterOf(c, flagsFor(c))),
      results: readResults(runId).filter((r) => !r.reassignedTo),
    };
  }
  const all = loadTests();
  let resolved: Array<(typeof all)[number] & { caseFilter?: string[] }>;
  try {
    resolved = suiteId ? resolveTests({ suiteId }, all) : all;
  } catch (e) {
    throw new VersusInputError(400, (e as Error).message);
  }
  const hashById = new Map(resolved.map((t) => [t.definition.id, t.hash]));
  const caseFilter = new Map(resolved.filter((t) => t.caseFilter).map((t) => [t.definition.id, new Set(t.caseFilter)]));
  const contestants = loadContestants();
  const configHash = new Map(contestants.map((c) => [c.id, contestantConfigHash(c)]));
  const results: CaseResult[] = [];
  for (const id of listRunIds()) {
    for (const r of readResults(id)) {
      if (hashById.get(r.testId) !== r.testHash || configHash.get(r.contestantId) !== r.contestantHash) continue;
      if (r.reassignedTo) continue; // moved to a named copy & paste model (counted there)
      if (caseFilter.get(r.testId) && !caseFilter.get(r.testId)!.has(r.caseId)) continue;
      results.push(r);
    }
  }
  return {
    scope: { kind: 'combined', ...(suiteId ? { suiteId } : {}) },
    tests: resolved.map((t) => ({ id: t.definition.id, name: t.definition.name, category: t.definition.category, hook: t.definition.hook })),
    fighters: contestants.map((c) => fighterOf(c, flagsFor(c))),
    results,
  };
}

export function versusFor(a: string, b: string, runId?: string, suiteId?: string): VersusData {
  if (!a || !b) throw new VersusInputError(400, 'Pick two models: ?a=<model id>&b=<model id>');
  const s = loadScope(runId, suiteId);
  const fa = s.fighters.find((f) => f.id === a);
  const fb = s.fighters.find((f) => f.id === b);
  const where = runId ? 'in this run' : 'in your model list';
  if (!fa) throw new VersusInputError(404, `Model "${a}" is not ${where}`);
  if (!fb) throw new VersusInputError(404, `Model "${b}" is not ${where}`);
  return buildVersus({ scope: s.scope, a: fa, b: fb, tests: s.tests, categories: loadCategories(), results: s.results.filter((r) => r.contestantId === a || r.contestantId === b) });
}

export function versusOptions(runId?: string, suiteId?: string): VersusOptions {
  const s = loadScope(runId, suiteId);
  const runs = listRuns()
    .filter((r) => r.completedJobs > 0)
    .map((r) => ({ id: r.id, name: r.name || r.id, createdAt: r.createdAt, contestantIds: r.contestants.map((c) => c.id) }));
  return buildVersusOptions(s.scope, s.fighters, s.tests, s.results, runs);
}

export function registerVersusRoutes({ route, httpError }: VersusRouteDeps): void {
  const wrap = <T>(fn: () => T): T => {
    try {
      return fn();
    } catch (e) {
      if (e instanceof VersusInputError) throw httpError(e.status, e.message);
      throw e;
    }
  };
  const opt = (v: string | null | undefined) => (v && v.trim() ? v.trim() : undefined);

  route('GET', '/api/versus', ({ query }) => wrap(() => versusFor(query.get('a') ?? '', query.get('b') ?? '', opt(query.get('run')), opt(query.get('suite')))));

  route('GET', '/api/versus/options', ({ query }) => wrap(() => versusOptions(opt(query.get('run')), opt(query.get('suite')))));

  route('POST', '/api/versus/render', async ({ body }) => {
    const b = ((await body()) ?? {}) as { a?: string; b?: string; run?: string; suite?: string };
    const data = wrap(() => versusFor(b.a ?? '', b.b ?? '', opt(b.run), opt(b.suite)));
    const png = await renderPng(renderVersusCardHtml(data), VERSUS_CARD.width, VERSUS_CARD.height);
    if (!png) throw httpError(409, browserHint(), { fallback: 'browser' });
    return { png: `data:image/png;base64,${png.toString('base64')}`, width: VERSUS_CARD.width, height: VERSUS_CARD.height, fileName: versusCardFileName(data) };
  });
}
