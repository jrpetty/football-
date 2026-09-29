/**
 * HTTP routes for copy & paste models (docs/API.md "Copy & paste models"):
 *
 *   GET    /api/manual-models/catalog            the model catalogue (+ the owner's own additions)
 *   POST   /api/manual-models/catalog            "Suggest a model not in the list"
 *   POST   /api/manual-models/contestants        make contestants for catalogue models (New Run's copy & paste mode)
 *   GET    /api/manual-models/choices            models picked in the Inbox for unspecified prompts
 *   POST   /api/manual-models/choice             pick the model for one waiting prompt
 *   DELETE /api/manual-models/choice/:requestId  forget that pick (before any reply was submitted)
 *   GET    /api/manual-models/unspecified        old copy & paste results that don't say which model made them
 *   POST   /api/manual-models/reassign           move such results to a model (explicit, logged)
 *   GET    /api/manual-models/reassign-log       every move, newest first
 *   GET    /api/best-per-test                    "Best on each test"
 */
import { loadContestants, toView } from '../core/config.ts';
import type { ManualModelInfo } from '../core/types.ts';
import { subscribe } from '../engine/runner.ts';
import { manualEvents, manualRequest } from '../providers/manual.ts';
import { bestPerTest } from './best.ts';
import { isInterface } from './identity.ts';
import { ManualModelError, applyPendingChoices, clearChoice, ensureManualContestant, loadPendingChoices, lockChoice, reassignLog, reassignResults, setChoice, listUnspecified } from './reassign.ts';
import { addCustomModel, catalogProblems, loadCatalog } from './store.ts';

type Route = (method: string, path: string, handler: (ctx: { params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown) => void;

interface Deps {
  route: Route;
  httpError: (status: number, message: string) => Error;
}

function readInfo(v: unknown, httpError: Deps['httpError']): Partial<ManualModelInfo> & { catalogId: string } {
  const o = (v ?? {}) as Record<string, unknown>;
  if (typeof o.catalogId !== 'string' || !o.catalogId) throw httpError(400, 'Pick a model from the catalogue ("catalogId")');
  if (o.interface !== undefined && !isInterface(o.interface)) throw httpError(400, 'Unknown interface');
  return {
    catalogId: o.catalogId,
    ...(o.interface ? { interface: o.interface as ManualModelInfo['interface'] } : {}),
    thinking: o.thinking === 'on' || o.thinking === 'off' ? o.thinking : 'default',
    webSearch: o.webSearch === true,
    ...(typeof o.note === 'string' ? { note: o.note } : {}),
  };
}

const watched = new Set<string>();

/** Move a case's result to its picked model as soon as the run grades it. */
function watchRun(runId: string): void {
  if (watched.has(runId)) return;
  const off = subscribe(runId, (e) => {
    if (e.type === 'job.finished') applyPendingChoices(runId);
    if (e.type === 'run.status' && e.status !== 'running' && e.status !== 'queued') {
      applyPendingChoices(runId);
      off();
      watched.delete(runId);
    }
  });
  watched.add(runId);
}

export function registerManualModelRoutes({ route, httpError }: Deps): void {
  const wrap = <T>(fn: () => T): T => {
    try {
      return fn();
    } catch (e) {
      if (e instanceof ManualModelError) throw httpError(e.status, e.message);
      throw e;
    }
  };

  // A reply to a prompt with a picked model was submitted: that model is now fixed for the rest of the conversation.
  manualEvents.on('resolved', (req: { runId: string; key: string }) => {
    try {
      lockChoice(req.runId, req.key);
    } catch {
      /* never break the Inbox over bookkeeping */
    }
  });
  // Picks made before a restart: attach any results graded meanwhile.
  try {
    applyPendingChoices();
  } catch (e) {
    console.warn(`Copy & paste models: could not apply pending model picks: ${(e as Error).message}`);
  }

  route('GET', '/api/manual-models/catalog', () => ({ ...loadCatalog(), problems: catalogProblems() }));

  route('POST', '/api/manual-models/catalog', async ({ body }) => {
    try {
      return addCustomModel(((await body()) ?? {}) as Record<string, unknown>);
    } catch (e) {
      throw httpError(400, (e as Error).message);
    }
  });

  route('POST', '/api/manual-models/contestants', async ({ body }) => {
    const b = ((await body()) ?? {}) as { models?: unknown };
    if (!Array.isArray(b.models) || !b.models.length) throw httpError(400, 'Pick at least one model');
    if (b.models.length > 40) throw httpError(400, 'At most 40 models at a time');
    const infos = b.models.map((m) => readInfo(m, httpError));
    return wrap(() => infos.map((i) => toView(ensureManualContestant(i))));
  });

  route('GET', '/api/manual-models/choices', ({ query }) => {
    applyPendingChoices();
    const runId = query.get('runId');
    const labels = new Map(loadContestants().map((c) => [c.id, c.label]));
    return loadPendingChoices()
      .filter((p) => !runId || p.runId === runId)
      .map((p) => ({ ...p, contestantLabel: labels.get(p.contestantId) ?? p.contestantId }));
  });

  route('POST', '/api/manual-models/choice', async ({ body }) => {
    const b = ((await body()) ?? {}) as { requestId?: unknown; choice?: unknown };
    const req = typeof b.requestId === 'string' ? manualRequest(b.requestId) : undefined;
    if (!req) throw httpError(404, 'This prompt is no longer waiting');
    const info = readInfo(b.choice, httpError);
    const out = wrap(() => setChoice(req, info));
    watchRun(req.runId);
    return { choice: out.choice, contestant: toView(out.contestant) };
  });

  route('DELETE', '/api/manual-models/choice/:requestId', ({ params }) => {
    const req = manualRequest(decodeURIComponent(params.requestId!));
    if (!req) throw httpError(404, 'This prompt is no longer waiting');
    return { ok: wrap(() => clearChoice(req.runId, req.key)) };
  });

  route('GET', '/api/manual-models/unspecified', () => listUnspecified());

  route('POST', '/api/manual-models/reassign', async ({ body }) => {
    const b = ((await body()) ?? {}) as { runId?: unknown; keys?: unknown; to?: unknown; note?: unknown; confirm?: unknown };
    if (b.confirm !== true) throw httpError(400, 'Reassigning is permanent: send "confirm": true');
    if (typeof b.runId !== 'string' || !Array.isArray(b.keys) || !b.keys.every((k) => typeof k === 'string')) throw httpError(400, 'Send "runId" and the result "keys" to move');
    const info = readInfo(b.to, httpError);
    return wrap(() => {
      const to = ensureManualContestant(info);
      return reassignResults({ runId: b.runId as string, keys: b.keys as string[], to, how: 'reassign', note: typeof b.note === 'string' ? b.note : undefined });
    });
  });

  route('GET', '/api/manual-models/reassign-log', () => reassignLog());

  route('GET', '/api/best-per-test', () => bestPerTest());
}
