import type { IncomingMessage, ServerResponse } from 'node:http';
import { EXPLAINERS, explainerFor } from '../core/explainers.ts';
import { getTest } from '../core/registry.ts';
import { buildArenaSample, buildSample } from '../core/test-sample.ts';
import { testBaseDir } from '../core/vision.ts';
import { PROGRAMS } from '../programs/index.ts';
import { GAMES } from '../arena/games/index.ts';

/**
 * Read-only explainer routes (plain-English "what is this test"):
 *  GET /api/explainers                    every hand-written explainer, keyed by test id ("arena.<game>" for Arena games)
 *  GET /api/tests/:id/sample[?reveal=1]   the first case as a viewer sees it; the answer only with reveal=1
 */

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string) => Error;
}

export function registerExplainRoutes({ route, httpError }: Deps): void {
  route('GET', '/api/explainers', () => ({ explainers: EXPLAINERS }));

  route('GET', '/api/tests/:id/sample', async ({ params, query }) => {
    const id = decodeURIComponent(params.id!);
    const reveal = query.get('reveal') === '1';
    const game = id.startsWith('arena.') ? GAMES[id.slice('arena.'.length)] : undefined;
    if (game) return { ...buildArenaSample(id, game, explainerFor(id)?.opening ?? null), explainer: explainerFor(id) ?? null };
    const t = getTest(id);
    if (!t) throw httpError(404, 'Test not found');
    const d = t.definition;
    const sample = await buildSample(d, {
      reveal,
      baseDir: testBaseDir(t.file),
      program: d.kind === 'program' ? PROGRAMS[d.program] : undefined,
      hash: t.hash,
    });
    // Held-out tests never show their questions.
    if (t.source === 'private') return { ...sample, text: '', tail: undefined, context: null, images: [], hasAnswer: false, answer: undefined, answerNote: undefined, private: true };
    return sample;
  });
}
