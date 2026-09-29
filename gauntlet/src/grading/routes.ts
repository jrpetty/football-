/**
 * Grading Station HTTP routes (docs/API.md → "Grading Station"). Registered by
 * src/server/index.ts through `registerGradingRoutes`, so the shared server
 * file only needs one line.
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { getTest } from '../core/registry.ts';
import { PROGRAMS } from '../programs/index.ts';
import { GAMES } from '../arena/games/index.ts';
import { listTournaments } from '../arena/tournament.ts';
import { readGames } from '../arena/store.ts';
import { gradingSpecFor, gradingSpecForArena } from './spec.ts';
import { OFFICIAL_POLICIES, type OfficialPolicy } from './policy.ts';
import {
  addDispute,
  aiEstimate,
  aiGrade,
  aiSummaryEstimate,
  aiSummaryGenerate,
  gradingPolicy,
  gradingQueue,
  gradingRuns,
  reapplyPolicy,
  runSummaries,
  saveHumanGrade,
  setGradingPolicy,
  stationItem,
  type HumanGradeInput,
} from './station.ts';

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string, details?: unknown) => Error;
}

/** Judged Arena games waiting for a human verdict, per tournament. */
function arenaPending(): Array<{ id: string; name: string; game: string; gameName: string; pending: number }> {
  const out: Array<{ id: string; name: string; game: string; gameName: string; pending: number }> = [];
  for (const t of listTournaments()) {
    const g = GAMES[t.game.id];
    if (!g?.judge) continue;
    const pending = readGames(t.id).filter((x) => x.status === 'awaiting-judges').length;
    if (pending) out.push({ id: t.id, name: t.name, game: t.game.id, gameName: g.name, pending });
  }
  return out;
}

export function registerGradingRoutes({ route, httpError }: Deps): void {
  const bad = (err: unknown, status = 400) => httpError(/not found/i.test((err as Error).message) ? 404 : status, (err as Error).message);
  const wrap = async <T>(fn: () => T | Promise<T>, status = 400): Promise<T> => {
    try {
      return await fn();
    } catch (err) {
      throw bad(err, status);
    }
  };

  route('GET', '/api/grading/settings', () => ({ official: gradingPolicy(), policies: OFFICIAL_POLICIES }));
  route('PUT', '/api/grading/settings', async ({ body }) => {
    const b = ((await body()) ?? {}) as { official?: string };
    return wrap(() => ({ official: setGradingPolicy(b.official as OfficialPolicy), policies: OFFICIAL_POLICIES }));
  });

  // What a test is graded on (?case=<id> adds the case's answer key and requirement checklist).
  route('GET', '/api/grading/spec/:testId', ({ params, query }) => {
    const id = decodeURIComponent(params.testId!);
    if (id.startsWith('arena.')) {
      const g = GAMES[id.slice('arena.'.length)];
      if (!g?.judge) throw httpError(404, 'Not a judged Arena game');
      return gradingSpecForArena({ id: g.id, name: g.name, rubric: g.judge.rubric });
    }
    const t = getTest(id);
    if (!t) throw httpError(404, 'Test not found');
    const d = t.definition;
    return gradingSpecFor(d, { caseId: query.get('case') || undefined, programScoring: d.kind === 'program' ? PROGRAMS[d.program]?.scoring : undefined });
  });

  route('GET', '/api/grading/runs', () => ({ runs: gradingRuns(), arena: arenaPending(), official: gradingPolicy() }));
  route('GET', '/api/grading/queue', ({ query }) => {
    const runId = query.get('runId');
    if (!runId) throw httpError(400, 'runId is required');
    return wrap(() => ({ items: gradingQueue(runId, query.get('testId') || undefined), official: gradingPolicy() }));
  });
  route('GET', '/api/grading/item/:runId/:key', ({ params }) => wrap(() => stationItem(params.runId!, decodeURIComponent(params.key!))));

  route('POST', '/api/grading/human', async ({ body }) => wrap(async () => saveHumanGrade((await body()) as HumanGradeInput)));
  route('POST', '/api/grading/dispute', async ({ body }) => wrap(async () => addDispute((await body()) as { runId: string; key: string; rater: string; note: string })));

  route('POST', '/api/grading/ai/estimate', async ({ body }) => {
    const b = ((await body()) ?? {}) as { runId?: string; keys?: string[] };
    if (!b.runId || !Array.isArray(b.keys) || !b.keys.length) throw httpError(400, 'Send runId and a non-empty keys array');
    return wrap(() => aiEstimate(b.runId!, b.keys!.slice(0, 500)));
  });
  route('POST', '/api/grading/ai/grade', async ({ body }) => {
    const b = ((await body()) ?? {}) as { runId?: string; keys?: string[]; confirmCostUsd?: number };
    if (!b.runId || !Array.isArray(b.keys) || !b.keys.length) throw httpError(400, 'Send runId and a non-empty keys array');
    return wrap(() => aiGrade(b.runId!, b.keys!.slice(0, 500), Number(b.confirmCostUsd)), 409);
  });

  route('POST', '/api/grading/runs/:id/reapply', ({ params }) => wrap(() => reapplyPolicy(params.id!)));

  route('GET', '/api/grading/runs/:id/summaries', ({ params }) => wrap(() => runSummaries(params.id!)));
  route('POST', '/api/grading/runs/:id/summaries/estimate', async ({ params, body }) => {
    const b = ((await body()) ?? {}) as { pairs?: string[] };
    return wrap(() => aiSummaryEstimate(params.id!, b.pairs));
  });
  route('POST', '/api/grading/runs/:id/summaries', async ({ params, body }) => {
    const b = ((await body()) ?? {}) as { pairs?: string[]; confirmCostUsd?: number };
    return wrap(() => aiSummaryGenerate(params.id!, b.pairs, Number(b.confirmCostUsd)), 409);
  });
}
