/**
 * Arena HTTP routes (docs/API.md → "Arena"). Registered by src/server/index.ts
 * through `registerArenaRoutes`, so the shared server file only needs one line.
 */
import type { IncomingMessage, ServerResponse } from 'node:http';
import { GAMES } from './games/index.ts';
import { deleteTournament, readGames, readTournament } from './store.ts';
import {
  cancelTournament,
  estimateTournament,
  gameRecord,
  isTournamentActive,
  listTournaments,
  resumeTournament,
  startTournament,
  subscribeTournament,
  tournamentDetail,
} from './tournament.ts';
import type { ArenaEvent, ArenaRequest } from './types.ts';

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

export interface ArenaRouteDeps {
  route: (method: string, path: string, handler: Handler) => void;
  /** Build an HTTP error the server turns into `{ error }` with this status. */
  httpError: (status: number, message: string, details?: unknown) => Error;
  /** Return value telling the server the handler wrote the response itself. */
  streaming: symbol;
}

/** Public description of every game (no functions). */
export function gameInfos() {
  return Object.values(GAMES).map((g) => ({
    id: g.id,
    name: g.name,
    version: g.version,
    tagline: g.tagline,
    description: g.description,
    sides: g.sides,
    rules: g.rules,
    moveHelp: g.moveHelp,
    capRule: g.capRule,
    defaults: g.defaults,
    estimate: g.estimate,
  }));
}

export function registerArenaRoutes({ route, httpError, streaming }: ArenaRouteDeps): void {
  const need = (id: string) => {
    const d = tournamentDetail(id);
    if (!d) throw httpError(404, 'Tournament not found');
    return d;
  };

  route('GET', '/api/arena/games', () => gameInfos());

  route('POST', '/api/arena/estimate', async ({ body }) => {
    try {
      return estimateTournament((await body()) as ArenaRequest);
    } catch (err) {
      throw httpError(400, (err as Error).message);
    }
  });

  route('POST', '/api/arena/tournaments', async ({ body }) => {
    try {
      return { tournamentId: startTournament((await body()) as ArenaRequest) };
    } catch (err) {
      throw httpError(400, (err as Error).message);
    }
  });

  route('GET', '/api/arena/tournaments', () => listTournaments());

  route('GET', '/api/arena/tournaments/:id', ({ params }) => need(params.id!));

  route('GET', '/api/arena/tournaments/:id/games/:key', ({ params }) => {
    need(params.id!);
    const g = gameRecord(params.id!, decodeURIComponent(params.key!));
    if (!g) throw httpError(404, 'Game not found');
    return g;
  });

  route('POST', '/api/arena/tournaments/:id/cancel', ({ params }) => {
    need(params.id!);
    if (!cancelTournament(params.id!)) throw httpError(409, 'Tournament is not running');
    return { ok: true };
  });

  route('POST', '/api/arena/tournaments/:id/resume', async ({ params, body }) => {
    need(params.id!);
    const { maxCostUsd } = ((await body()) ?? {}) as { maxCostUsd?: number | null };
    if (maxCostUsd !== undefined && maxCostUsd !== null && !(maxCostUsd > 0)) throw httpError(400, 'maxCostUsd must be a positive number or null');
    try {
      resumeTournament(params.id!, { maxCostUsd });
    } catch (err) {
      throw httpError(409, (err as Error).message);
    }
    return { ok: true };
  });

  route('DELETE', '/api/arena/tournaments/:id', ({ params }) => {
    need(params.id!);
    if (isTournamentActive(params.id!)) throw httpError(409, 'Cancel the tournament before deleting it');
    deleteTournament(params.id!);
    return { ok: true };
  });

  route('GET', '/api/arena/tournaments/:id/export.json', ({ res, params }) => {
    const manifest = readTournament(params.id!);
    if (!manifest) throw httpError(404, 'Tournament not found');
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'content-disposition': `attachment; filename="gauntlet-${manifest.id}.json"` });
    res.end(JSON.stringify({ manifest, state: need(manifest.id).state, games: readGames(manifest.id) }, null, 2));
    return streaming;
  });

  route('GET', '/api/arena/tournaments/:id/events', ({ req, res, params }) => {
    const d = need(params.id!);
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    });
    const send = (e: ArenaEvent) => res.write(`data: ${JSON.stringify(e)}\n\n`);
    const at = new Date().toISOString();
    send({ type: 'tournament.progress', tournamentId: d.manifest.id, gamesDone: d.state.gamesDone, gamesTotal: d.state.gamesTotal, costUsd: d.state.costUsd, at });
    send({ type: 'tournament.status', tournamentId: d.manifest.id, status: d.active ? 'running' : d.manifest.status, at });
    for (const g of d.live) send({ type: 'game.started', tournamentId: d.manifest.id, game: g, at });
    const unsubscribe = subscribeTournament(d.manifest.id, send);
    const heartbeat = setInterval(() => res.write(': ping\n\n'), 15_000);
    req.on('close', () => {
      clearInterval(heartbeat);
      unsubscribe();
    });
    return streaming;
  });
}
