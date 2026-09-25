/**
 * Mock Arena API (`?mock=1`): a finished 8-model chess knockout, a Connect Four
 * knockout that plays live in the browser, a finished poker knockout, a judged
 * debate knockout, a courtroom match that plays live, a debate waiting for
 * human judging, and any tournament you create on the New Tournament page.
 * Games are real (see arenaSim.ts and arenaFormatsSim.ts); spending caps stop
 * a simulated tournament exactly like the real engine does.
 */
import { ApiError } from '../api.ts';
import { GAMES } from '../../../src/arena/games/index.ts';
import { buildKnockout, buildRoundRobin, computeState } from '../../../src/arena/bracket.ts';
import type { ArenaEstimate, ArenaEvent, ArenaRequest, MatchState, TournamentDetail, TournamentListItem } from '../arena/types.ts';
import { CONTESTANTS, SETTINGS } from './fixtures.ts';
import { identityTerms, judgePacket, judgingFrom, sideAFor } from '../../../src/arena/judge.ts';
import { verdictSnapshot } from '../../../src/arena/judged.ts';
import { createRng } from '../../../src/core/rng.ts';
import type { ArenaGameRecord, JudgeVerdict } from '../arena/types.ts';
import { STRENGTH, buildSimTournament, lite, transcriptsFor, viewAt, type SimTournament } from './arenaSim.ts';

const sims = new Map<string, SimTournament>();
const LOADED = Date.now();
const BY_STRENGTH = [...CONTESTANTS].sort((a, b) => (STRENGTH[b.id] ?? 0.5) - (STRENGTH[a.id] ?? 0.5));
const JUDGE_POOL = SETTINGS.judges.map((id) => CONTESTANTS.find((c) => c.id === id)).filter((c): c is (typeof CONTESTANTS)[number] => !!c);
const pick = (...ids: string[]) => ids.map((id) => CONTESTANTS.find((c) => c.id === id)!).filter(Boolean);

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
    maxCostUsd: 16,
  });
  // Start the live demo a few seconds into game 5, so the first matches are already decided.
  const k = Math.min(4, c4.timeline.length - 1);
  c4.t0 = LOADED - c4.timeline[k]!.start - 2500;
  c4.games.forEach((g, i) => {
    g.record.startedAt = new Date(c4.t0 + c4.timeline[i]!.start).toISOString();
    g.record.finishedAt = new Date(c4.t0 + c4.timeline[i]!.end).toISOString();
  });
  sims.set(c4.manifest.id, c4);

  const poker = buildSimTournament({
    id: 'arena-demo-poker',
    name: 'Heads-up Poker Open · duplicate',
    gameId: 'poker',
    entrants: pick('kestrel-kite-reasoner', 'helios-nova-3-pro', 'meridian-atlas-4-mini', 'obsidian-sable-large'),
    format: 'knockout',
    gamesPerMatch: 2,
    createdAt: '2026-09-21T18:30:00.000Z',
    t0: Date.parse('2026-09-21T18:30:00.000Z'),
    fast: false,
    maxCostUsd: 40,
    options: { hands: '20' },
  });
  sims.set(poker.manifest.id, poker);

  const debate = buildSimTournament({
    id: 'arena-demo-debate',
    name: 'Great Debate Cup',
    gameId: 'debate',
    entrants: pick('kestrel-kite-reasoner', 'meridian-atlas-4-mini', 'obsidian-sable-large', 'helios-quill-flash'),
    format: 'knockout',
    gamesPerMatch: 2,
    createdAt: '2026-09-20T17:00:00.000Z',
    t0: Date.parse('2026-09-20T17:00:00.000Z'),
    fast: false,
    maxCostUsd: 12,
    judgePool: JUDGE_POOL,
  });
  sims.set(debate.manifest.id, debate);

  const court = buildSimTournament({
    id: 'arena-demo-courtroom',
    name: 'Courtroom · The Missing Violin · live',
    gameId: 'courtroom',
    entrants: pick('obsidian-sable-large', 'helios-quill-flash'),
    format: 'knockout',
    gamesPerMatch: 2,
    createdAt: new Date(LOADED - 30_000).toISOString(),
    t0: 0,
    fast: true,
    maxCostUsd: 3,
    options: { topic: 'missing-violin' },
    judgePool: JUDGE_POOL,
  });
  // Start the live demo in the middle of the second speech.
  court.t0 = LOADED - (court.timeline[0]!.moveAt[0] ?? 0) - 2600;
  court.games.forEach((g, i) => {
    g.record.startedAt = new Date(court.t0 + court.timeline[i]!.start).toISOString();
    g.record.finishedAt = new Date(court.t0 + court.timeline[i]!.end).toISOString();
  });
  sims.set(court.manifest.id, court);

  const blind = buildSimTournament({
    id: 'arena-demo-human-judging',
    name: 'Debate exhibition · no judge keys',
    gameId: 'debate',
    entrants: pick('meridian-atlas-4-ultra', 'kestrel-kite-reasoner'),
    format: 'knockout',
    gamesPerMatch: 2,
    createdAt: '2026-09-23T20:00:00.000Z',
    t0: Date.parse('2026-09-23T20:00:00.000Z'),
    fast: false,
    options: { topic: 'four-day-week' },
    noJudges: true,
  });
  sims.set(blind.manifest.id, blind);
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
  const spent = games.reduce((x, g) => x + g.metrics[0].costUsd + g.metrics[1].costUsd + (g.judging?.costUsd ?? 0), 0) + liveSpend;
  const state = computeState(s.manifest, games, spent);
  let status = v.status;
  let error = v.status === 'cancelled' ? 'Cancelled by you. Resume to continue from the next unplayed game.' : undefined;
  if (v.status === 'completed' && s.budgetStopped) {
    status = 'cancelled';
    error = `Budget cap of $${(s.manifest.settings.maxCostUsd ?? 0).toFixed(2)} reached ($${spent.toFixed(4)} spent). Resume with a higher cap to finish.`;
  } else if (v.status === 'completed' && state.awaitingJudges) {
    status = 'interrupted';
    error = `${state.awaitingJudges} judged game${state.awaitingJudges === 1 ? ' is' : 's are'} waiting for human judging: open "Judge" on this tournament. It continues automatically once ${state.awaitingJudges === 1 ? 'it is' : 'they are'} judged.`;
  }
  return {
    manifest: { ...s.manifest, status, finishedAt: status === 'completed' ? v.finishedAt : undefined, error },
    state,
    games,
    active: status === 'running' || status === 'queued',
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
  const choices = game.gamesPerMatchOptions ?? [2, 4, 6];
  const G = req.gamesPerMatch ?? choices[0]!;
  if (!choices.includes(G)) throw new ApiError(choices.length === 1 ? `${game.name} plays exactly ${choices[0]} games per pairing (sides swapped)` : 'gamesPerMatch must be 2, 4 or 6 (sides swap every game)', 400);
  let cfg = { ...game.defaults };
  try {
    if (game.configure) cfg = game.configure(cfg, Object.fromEntries((game.options ?? []).map((o) => [o.key, req.options?.[o.key] ?? o.default])));
  } catch (e) {
    throw new ApiError((e as Error).message, 400);
  }
  const base = game.estimateFor?.(cfg) ?? game.estimate;
  const entrants = entrantsFor(req);
  const format = req.format ?? 'knockout';
  const matches = format === 'knockout' ? buildKnockout(entrants.map((e) => e.id)) : buildRoundRobin(entrants.map((e) => e.id));
  const plies = Math.min(base.pliesPerGame, (game.engine ?? 'board') === 'board' ? (req.maxPlies ?? game.defaults.maxPlies) : cfg.maxPlies);
  const per = new Map(
    entrants.map((c) => [c.id, c.providerType === 'manual' ? 0 : ((base.inputTokensPerMove * c.pricing.inputPerM + base.outputTokensPerMove * c.pricing.outputPerM) / 1e6) * 1.05]),
  );
  const sd = format === 'knockout' && game.suddenDeath !== false ? (req.suddenDeath ?? 2) : 0;
  const byId = new Map(entrants.map((c) => [c.id, c]));
  const judgeFor = (a: string, b: string) => {
    if (!game.judge) return { usd: 0, calls: 0 };
    const panel = JUDGE_POOL.filter((j) => j.vendor !== byId.get(a)!.vendor && j.vendor !== byId.get(b)!.vendor && j.id !== a && j.id !== b);
    return { usd: panel.reduce((x, j) => x + (game.judge!.estimate.inputTokens * j.pricing.inputPerM + game.judge!.estimate.outputTokens * j.pricing.outputPerM) / 1e6, 0), calls: panel.length };
  };
  let judgeUsd = 0;
  let judgeCalls = 0;
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
    const costs = A.flatMap((a) => B.map((b) => ((per.get(a)! + per.get(b)!) * plies) / 2 + judgeFor(a, b).usd));
    if (game.judge) {
      const js = A.flatMap((a) => B.map((b) => judgeFor(a, b)));
      judgeUsd += (js.reduce((x, j) => x + j.usd, 0) / js.length) * G;
      judgeCalls += Math.round((js.reduce((x, j) => x + j.calls, 0) / js.length) * G);
    }
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
  if (game.judge && !JUDGE_POOL.length) warnings.push('No judge models are available: every game will wait for human judging on the Judge screen');
  if (req.maxCostUsd && central > req.maxCostUsd) warnings.push(`Estimated cost ${central.toFixed(2)} USD exceeds your cap of ${req.maxCostUsd.toFixed(2)} USD: the tournament will stop when the cap is reached`);
  return {
    games,
    maxGames: games + (format === 'knockout' ? matches.filter((m) => !('bye' in m.a) && !('bye' in m.b)).length * sd : 0),
    moves: games * plies,
    estCostUsd: Math.round(central * 10000) / 10000,
    estCostUsdHigh: Math.round(high * 1.6 * 10000) / 10000,
    perGame,
    perContestant: entrants.map((c) => ({ contestantId: c.id, perMoveUsd: per.get(c.id)!, perGameUsd: (per.get(c.id)! * plies) / 2, basis: 'definition' as const, manual: c.providerType === 'manual' })),
    ...(game.judge ? { judgeCalls, judgeCostUsd: Math.round(judgeUsd * 10000) / 10000, judges: JUDGE_POOL.map((j) => ({ id: j.id, label: j.label, vendor: j.vendor })) } : {}),
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
      engine: g.engine ?? 'board',
      gamesPerMatchOptions: g.gamesPerMatchOptions ?? [2, 4, 6],
      options: g.options ?? [],
      judged: Boolean(g.judge),
      unit: g.unit,
    }));
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
        gamesPerMatch: req.gamesPerMatch ?? game.gamesPerMatchOptions?.[0] ?? 2,
        createdAt: new Date().toISOString(),
        t0: Date.now() + 1200,
        fast: true,
        maxCostUsd: req.maxCostUsd,
        seeding: req.seeding,
        options: req.options,
        judgePool: JUDGE_POOL,
      });
      sims.set(newId, s);
      return { tournamentId: newId };
    }
  }
  const s = sim(id!);
  if (method === 'POST' && c === 'games' && parts[4] === 'verdict') return humanVerdict(s, decodeURIComponent(key!), body as { winner: 'A' | 'B'; rationale?: string; scores?: { side_a?: Record<string, number>; side_b?: Record<string, number> } });
  switch (route) {
    case 'GET tournaments/judging':
      return packets(s);
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
      if (s.budgetStopped && s.build) {
        // Rebuild with the new cap and continue from where the cap stopped it.
        const cap = (body as { maxCostUsd?: number | null } | undefined)?.maxCostUsd;
        const stopAt = s.games.length - 1;
        const next = buildSimTournament({ ...s.build, maxCostUsd: cap === null ? undefined : (cap ?? s.build.maxCostUsd), fast: true });
        next.t0 = Date.now() - (next.timeline[Math.max(0, stopAt)]?.start ?? 0);
        next.games.forEach((g, i) => {
          g.record.startedAt = new Date(next.t0 + next.timeline[i]!.start).toISOString();
          g.record.finishedAt = new Date(next.t0 + next.timeline[i]!.end).toISOString();
        });
        sims.set(s.manifest.id, next);
        return { ok: true };
      }
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
    if (cl && cl.phase && (!pl || pl.key !== cl.key || pl.phase !== cl.phase || pl.moves.length !== cl.moves.length)) onEvent({ type: 'game.phase', tournamentId: T, key: cl.key, phase: cl.phase, at: at() });
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

// ─────────────────────────────── Human judging (mock) ───────────────────────────────

function replay(s: SimTournament, rec: ArenaGameRecord): unknown {
  const game = GAMES[s.manifest.game.id]!;
  let state = game.setup(createRng(rec.seed).fork('setup'), s.manifest.settings.game);
  for (const m of rec.moves) if (m.kind !== 'verdict') state = game.play(state, m.move);
  return state;
}

function packets(s: SimTournament) {
  const game = GAMES[s.manifest.game.id]!;
  if (!game.judge) return [];
  const d = detail(s);
  const roundOf = new Map(d.state.matches.map((m) => [m.id, m.roundName]));
  return d.games
    .filter((g) => g.status === 'awaiting-judges')
    .map((g) => {
      const rec = s.games.find((x) => x.record.key === g.key)!.record;
      const players = rec.players.map((p) => s.manifest.entrants.find((e) => e.id === p)!);
      return {
        key: g.key,
        matchId: g.matchId,
        roundName: roundOf.get(g.matchId) ?? g.matchId,
        gameNo: g.gameNo,
        gameId: game.id,
        material: judgePacket(game.judge!, replay(s, rec), sideAFor(rec.seed, 'human'), identityTerms(players)),
        rubric: game.judge!.rubric,
        note: rec.judging?.note,
      };
    });
}

function humanVerdict(s: SimTournament, key: string, body: { winner: 'A' | 'B'; rationale?: string; scores?: { side_a?: Record<string, number>; side_b?: Record<string, number> } }) {
  const g = s.games.find((x) => x.record.key === key);
  if (!g) throw new ApiError('Game not found', 404);
  const rec = g.record;
  if (rec.status !== 'awaiting-judges') throw new ApiError('This game is not waiting for a verdict', 400);
  if (body?.winner !== 'A' && body?.winner !== 'B') throw new ApiError('winner must be "A" or "B"', 400);
  const sideA = sideAFor(rec.seed, 'human');
  const winner = (body.winner === 'A' ? sideA : 1 - sideA) as 0 | 1;
  const a = body.scores?.side_a;
  const b = body.scores?.side_b;
  const v: JudgeVerdict = { judgeId: 'human', judgeLabel: 'Human judge', vendor: '—', sideA, winner, ...(a && b ? { scores: sideA === 0 ? [a, b] : [b, a] } : {}), rationale: body.rationale ?? '', costUsd: 0, human: true };
  const judging = judgingFrom([v], { excludedVendors: rec.judging?.excludedVendors });
  const last = rec.moves[rec.moves.length - 1];
  if (last?.kind === 'verdict') rec.moves[rec.moves.length - 1] = { ...last, side: winner, label: `Verdict: ${judging.decision}`, snapshot: { ...(last.snapshot as object), verdict: verdictSnapshot(judging) } };
  Object.assign(rec, { status: 'ok', winner, reason: `${judging.decision} (human judge)`, judging });
  return { ok: true, winner, decision: judging.decision, resumed: false };
}
