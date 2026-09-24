/**
 * Mock Arena API (`?mock=1`): a finished 8-model chess knockout, a Connect Four
 * knockout that plays live in the browser, and any tournament you create on
 * the New Tournament page. Games are real (see arenaSim.ts).
 */
import { ApiError } from '../api.ts';
import { GAMES } from '../../../src/arena/games/index.ts';
import { buildKnockout, buildRoundRobin, computeState } from '../../../src/arena/bracket.ts';
import type { ArenaEstimate, ArenaEvent, ArenaRequest, MatchState, TournamentDetail, TournamentListItem } from '../arena/types.ts';
import { CONTESTANTS } from './fixtures.ts';
import { STRENGTH, buildSimTournament, lite, transcriptsFor, viewAt, type SimTournament } from './arenaSim.ts';

const sims = new Map<string, SimTournament>();
const LOADED = Date.now();
const BY_STRENGTH = [...CONTESTANTS].sort((a, b) => (STRENGTH[b.id] ?? 0.5) - (STRENGTH[a.id] ?? 0.5));

function ensureFixtures(): void {
  if (sims.size) return;
  const chess = buildSimTournament({
    id: 'arena-demo-chess',
    name: 'Chess Championship · September',
    gameId: 'chess',
    entrants: BY_STRENGTH,
    format: 'knockout',
    gamesPerMatch: 2,
    createdAt: '2026-09-22T19:00:00.000Z',
    t0: Date.parse('2026-09-22T19:00:00.000Z'),
    fast: false,
    maxCostUsd: 150,
  });
  sims.set(chess.manifest.id, chess);
  const c4 = buildSimTournament({
    id: 'arena-demo-connect4',
    name: 'Connect Four Cup · live',
    gameId: 'connect4',
    entrants: BY_STRENGTH,
    format: 'knockout',
    gamesPerMatch: 2,
    createdAt: new Date(LOADED - 60_000).toISOString(),
    t0: 0,
    fast: true,
    maxCostUsd: 5,
  });
  // Start the live demo a few seconds into game 5, so the first matches are already decided.
  const k = Math.min(4, c4.timeline.length - 1);
  c4.t0 = LOADED - c4.timeline[k]!.start - 2500;
  c4.games.forEach((g, i) => {
    g.record.startedAt = new Date(c4.t0 + c4.timeline[i]!.start).toISOString();
    g.record.finishedAt = new Date(c4.t0 + c4.timeline[i]!.end).toISOString();
  });
  sims.set(c4.manifest.id, c4);
}

function sim(id: string): SimTournament {
  ensureFixtures();
  const s = sims.get(id);
  if (!s) throw new ApiError(`Tournament not found: ${id}`, 404);
  return s;
}

function detail(s: SimTournament, now = Date.now()): TournamentDetail {
  const v = viewAt(s, now);
  const games = v.revealed.map(lite);
  const liveSpend = v.live ? v.live.metrics[0].costUsd + v.live.metrics[1].costUsd : 0;
  const spent = games.reduce((x, g) => x + g.metrics[0].costUsd + g.metrics[1].costUsd, 0) + liveSpend;
  return {
    manifest: { ...s.manifest, status: v.status, finishedAt: v.finishedAt, error: v.status === 'cancelled' ? 'Cancelled by you. Resume to continue from the next unplayed game.' : undefined },
    state: computeState(s.manifest, games, spent),
    games,
    active: v.status === 'running' || v.status === 'queued',
    live: v.live ? [v.live] : [],
  };
}

function listItem(s: SimTournament): TournamentListItem {
  const d = detail(s);
  return {
    id: s.manifest.id,
    name: s.manifest.name,
    status: d.manifest.status,
    createdAt: s.manifest.createdAt,
    finishedAt: d.manifest.finishedAt,
    game: s.manifest.game,
    format: s.manifest.settings.format,
    entrants: s.manifest.entrants.map((e) => ({ id: e.id, label: e.label, color: e.color, seed: e.seed })),
    champion: d.state.champion,
    gamesDone: d.state.gamesDone,
    gamesTotal: d.state.gamesTotal,
    costUsd: d.state.costUsd,
    fingerprint: s.manifest.fingerprint,
  };
}

function entrantsFor(req: ArenaRequest) {
  const picked = (req.contestantIds ?? []).map((id) => CONTESTANTS.find((c) => c.id === id)).filter((c): c is (typeof CONTESTANTS)[number] => !!c);
  if (picked.length < 2) throw new ApiError('Pick at least 2 models', 400);
  if (picked.length > 16) throw new ApiError('At most 16 models per tournament', 400);
  return (req.seeding ?? 'index') === 'index' ? [...picked].sort((a, b) => (STRENGTH[b.id] ?? 0.5) - (STRENGTH[a.id] ?? 0.5)) : picked;
}

function estimate(req: ArenaRequest): ArenaEstimate {
  const game = GAMES[req.game];
  if (!game) throw new ApiError(`Unknown arena game "${req.game}"`, 400);
  const G = req.gamesPerMatch ?? 2;
  if (![2, 4, 6].includes(G)) throw new ApiError('gamesPerMatch must be 2, 4 or 6 (sides swap every game)', 400);
  const entrants = entrantsFor(req);
  const format = req.format ?? 'knockout';
  const matches = format === 'knockout' ? buildKnockout(entrants.map((e) => e.id)) : buildRoundRobin(entrants.map((e) => e.id));
  const plies = Math.min(game.estimate.pliesPerGame, req.maxPlies ?? game.defaults.maxPlies);
  const per = new Map(
    entrants.map((c) => [c.id, c.providerType === 'manual' ? 0 : ((game.estimate.inputTokensPerMove * c.pricing.inputPerM + game.estimate.outputTokensPerMove * c.pricing.outputPerM) / 1e6) * 1.05]),
  );
  const sd = format === 'knockout' ? (req.suddenDeath ?? 2) : 0;
  const possible = new Map<string, string[]>();
  let games = 0;
  let central = 0;
  let high = 0;
  const perGame: ArenaEstimate['perGame'] = [];
  for (const m of [...matches].sort((x, y) => x.round - y.round || x.slot - y.slot)) {
    const set = (s: (typeof m)['a']) => ('entrant' in s ? [s.entrant] : 'bye' in s ? [] : (possible.get(s.winnerOf) ?? []));
    const A = set(m.a);
    const B = set(m.b);
    possible.set(m.id, [...A, ...B]);
    if (!A.length || !B.length) continue;
    const costs = A.flatMap((a) => B.map((b) => ((per.get(a)! + per.get(b)!) * plies) / 2));
    const avg = costs.reduce((x, y) => x + y, 0) / costs.length;
    if (A.length === 1 && B.length === 1) perGame.push({ a: A[0]!, b: B[0]!, estCostUsd: avg });
    games += G;
    central += avg * G;
    high += Math.max(...costs) * (G + sd);
  }
  const warnings: string[] = [];
  const manual = entrants.filter((c) => c.providerType === 'manual');
  if (manual.length) warnings.push(`${manual.map((c) => c.label).join(', ')}: manual contestant — every move waits in the Manual Inbox for you to paste the model's reply`);
  const noKey = entrants.filter((c) => !c.hasKey && c.providerType !== 'manual');
  for (const c of noKey) warnings.push(`${c.label}: API key is not set — its games will error`);
  if (format === 'knockout' && ![4, 8, 16].includes(entrants.length)) warnings.push(`${entrants.length} entrants is not 4, 8 or 16: the top seeds get byes into round 2`);
  if (req.maxCostUsd && central > req.maxCostUsd) warnings.push(`Estimated cost ${central.toFixed(2)} USD exceeds your cap of ${req.maxCostUsd.toFixed(2)} USD: the tournament will stop when the cap is reached`);
  return {
    games,
    maxGames: games + (format === 'knockout' ? matches.filter((m) => !('bye' in m.a) && !('bye' in m.b)).length * sd : 0),
    moves: games * plies,
    estCostUsd: Math.round(central * 10000) / 10000,
    estCostUsdHigh: Math.round(high * 1.6 * 10000) / 10000,
    perGame,
    perContestant: entrants.map((c) => ({ contestantId: c.id, perMoveUsd: per.get(c.id)!, perGameUsd: (per.get(c.id)! * plies) / 2, basis: 'definition' as const, manual: c.providerType === 'manual' })),
    fingerprint: `${req.game.slice(0, 2)}${G}${format === 'knockout' ? 'k' : 'r'}9e41d07a`,
    warnings,
    entrants: entrants.map((c, i) => ({ id: c.id, seed: i + 1, index: (req.seeding ?? 'index') === 'index' ? Math.round((STRENGTH[c.id] ?? 0.5) * 800) / 10 : null })),
    matches,
  };
}

let seq = 1;

export async function handleArena(method: string, parts: string[], body: unknown): Promise<unknown> {
  ensureFixtures();
  const [a, id, c, key] = parts;
  const route = `${method} ${a ?? ''}${c ? `/${c}` : ''}`;
  if (method === 'GET' && a === 'games') {
    return Object.values(GAMES).map((g) => ({ id: g.id, name: g.name, version: g.version, tagline: g.tagline, description: g.description, sides: g.sides, rules: g.rules, moveHelp: g.moveHelp, capRule: g.capRule, defaults: g.defaults, estimate: g.estimate }));
  }
  if (method === 'POST' && a === 'estimate') return estimate(body as ArenaRequest);
  if (a !== 'tournaments') throw new ApiError('Mock: no such arena route', 404);
  if (!id) {
    if (method === 'GET') return [...sims.values()].map(listItem).sort((x, y) => y.createdAt.localeCompare(x.createdAt));
    if (method === 'POST') {
      const req = body as ArenaRequest;
      estimate(req);
      const game = GAMES[req.game]!;
      const newId = `arena-demo-${Date.now().toString(36)}-${seq++}`;
      const s = buildSimTournament({
        id: newId,
        name: req.name?.trim() || `${game.name} ${req.format === 'round-robin' ? 'round-robin' : 'knockout'} · ${req.contestantIds.length} models`,
        gameId: req.game,
        entrants: entrantsFor(req),
        format: req.format ?? 'knockout',
        gamesPerMatch: req.gamesPerMatch ?? 2,
        createdAt: new Date().toISOString(),
        t0: Date.now() + 1200,
        fast: true,
        maxCostUsd: req.maxCostUsd,
        seeding: req.seeding,
      });
      sims.set(newId, s);
      return { tournamentId: newId };
    }
  }
  const s = sim(id!);
  switch (route) {
    case 'GET tournaments':
      return detail(s);
    case 'GET tournaments/games': {
      const g = s.games.find((x) => x.record.key === key);
      if (!g) throw new ApiError('Game not found', 404);
      if (!g.record.transcripts[0].length && !g.record.transcripts[1].length) g.record.transcripts = transcriptsFor(g.record, s.manifest.game.id, s.manifest.settings, s.contestants);
      return g.record;
    }
    case 'POST tournaments/cancel':
      s.cancelledAt = Date.now();
      return { ok: true };
    case 'POST tournaments/resume':
      if (s.cancelledAt) {
        s.t0 += Date.now() - s.cancelledAt;
        s.cancelledAt = undefined;
      }
      return { ok: true };
    case 'DELETE tournaments':
      sims.delete(s.manifest.id);
      return { ok: true };
  }
  throw new ApiError(`Mock: no route for ${method} arena/${parts.join('/')}`, 404);
}

/** Emits the same ArenaEvent stream the server sends over SSE, derived from the simulation clock. */
export function subscribeArena(id: string, onEvent: (e: ArenaEvent) => void): () => void {
  ensureFixtures();
  const s = sims.get(id);
  if (!s) return () => {};
  let prev = detail(s);
  let prevThinking = '';
  const at = () => new Date().toISOString();
  const doneIds = (d: TournamentDetail) => new Set(d.state.matches.filter((m: MatchState) => m.status === 'done').map((m) => m.id));
  const timer = window.setInterval(() => {
    const cur = detail(s);
    const pl = prev.live[0];
    const cl = cur.live[0];
    const T = s.manifest.id;
    for (const g of cur.games.slice(prev.games.length)) onEvent({ type: 'game.finished', tournamentId: T, game: g, at: at() });
    const before = doneIds(prev);
    for (const m of cur.state.matches) if (m.status === 'done' && !before.has(m.id)) onEvent({ type: 'match.finished', tournamentId: T, match: m, at: at() });
    if (cl && (!pl || pl.key !== cl.key)) {
      onEvent({ type: 'game.started', tournamentId: T, game: { ...cl, moves: [], thinking: '' }, at: at() });
      for (const mv of cl.moves) onEvent({ type: 'game.move', tournamentId: T, key: cl.key, move: mv, strikes: cl.strikes, metrics: cl.metrics, at: at() });
      onEvent({ type: 'game.turn', tournamentId: T, key: cl.key, side: cl.toMove, at: cl.turnStartedAt });
      prevThinking = '';
    } else if (cl && pl) {
      for (const mv of cl.moves.slice(pl.moves.length)) onEvent({ type: 'game.move', tournamentId: T, key: cl.key, move: mv, strikes: cl.strikes, metrics: cl.metrics, at: at() });
      if (cl.moves.length !== pl.moves.length) {
        onEvent({ type: 'game.turn', tournamentId: T, key: cl.key, side: cl.toMove, at: cl.turnStartedAt });
        prevThinking = '';
      }
    }
    if (cl && cl.thinking.length > prevThinking.length && cl.thinking.startsWith(prevThinking)) {
      onEvent({ type: 'game.thinking', tournamentId: T, key: cl.key, side: cl.toMove, text: cl.thinking.slice(prevThinking.length), attempt: 1 });
      prevThinking = cl.thinking;
    }
    if (cur.manifest.status !== prev.manifest.status) onEvent({ type: 'tournament.status', tournamentId: T, status: cur.manifest.status, at: at(), error: cur.manifest.error });
    if (cur.state.gamesDone !== prev.state.gamesDone || Math.abs(cur.state.costUsd - prev.state.costUsd) > 1e-9) {
      onEvent({ type: 'tournament.progress', tournamentId: T, gamesDone: cur.state.gamesDone, gamesTotal: cur.state.gamesTotal, costUsd: cur.state.costUsd, at: at() });
    }
    prev = cur;
  }, 200);
  return () => window.clearInterval(timer);
}
