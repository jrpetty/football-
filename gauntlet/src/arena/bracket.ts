/**
 * Tournament structure and scoring — pure functions, no I/O.
 *
 * Knockout: standard seeding (1 v 16, 8 v 9, …) so the top two seeds can only
 * meet in the final; when the field is not a power of two the top seeds get
 * byes. Round-robin: everyone plays everyone (circle method).
 *
 * Every pairing is a mini-match of `gamesPerMatch` games with sides swapped
 * each game. A level knockout match goes to sudden-death games (sides keep
 * alternating); if those are level too it is decided by fewer illegal moves,
 * then lower cost, then the higher seed.
 */
import { hashString } from '../core/rng.ts';
import type { ArenaEntrant, ArenaGameLite, ArenaSettings, GameSlot, MatchDecision, MatchSource, MatchSpec, MatchState, StandingRow, TournamentState } from './types.ts';

export function nextPow2(n: number): number {
  let p = 1;
  while (p < n) p *= 2;
  return p;
}

/** Seeds (1-based) in bracket order, e.g. size 8 → [1, 8, 4, 5, 2, 7, 3, 6]. */
export function seedOrder(size: number): number[] {
  let order = [1];
  while (order.length < size) {
    const n = order.length * 2;
    order = order.flatMap((s) => [s, n + 1 - s]);
  }
  return order;
}

export function roundName(round: number, rounds: number): string {
  const left = rounds - round;
  if (left === 0) return 'Final';
  if (left === 1) return 'Semi-finals';
  if (left === 2) return 'Quarter-finals';
  return `Round of ${2 ** (left + 1)}`;
}

/** Knockout bracket for entrants listed in seed order (index 0 = seed 1). */
export function buildKnockout(entrantIds: string[]): MatchSpec[] {
  if (entrantIds.length < 2) throw new Error('A knockout needs at least 2 entrants');
  const size = nextPow2(entrantIds.length);
  const rounds = Math.log2(size);
  const order = seedOrder(size);
  const src = (seed: number): MatchSource => (seed <= entrantIds.length ? { entrant: entrantIds[seed - 1]! } : { bye: true });
  const out: MatchSpec[] = [];
  for (let i = 0; i < size / 2; i++) {
    out.push({ id: `R1-M${i + 1}`, round: 1, roundName: roundName(1, rounds), slot: i, a: src(order[2 * i]!), b: src(order[2 * i + 1]!) });
  }
  for (let r = 2; r <= rounds; r++) {
    const n = size / 2 ** r;
    for (let i = 0; i < n; i++) {
      out.push({ id: `R${r}-M${i + 1}`, round: r, roundName: roundName(r, rounds), slot: i, a: { winnerOf: `R${r - 1}-M${2 * i + 1}` }, b: { winnerOf: `R${r - 1}-M${2 * i + 2}` } });
    }
  }
  return out;
}

/** Round-robin schedule (circle method); an odd field gets a rest each round. */
export function buildRoundRobin(entrantIds: string[]): MatchSpec[] {
  if (entrantIds.length < 2) throw new Error('A round-robin needs at least 2 entrants');
  const ids: Array<string | null> = [...entrantIds];
  if (ids.length % 2) ids.push(null);
  const n = ids.length;
  const out: MatchSpec[] = [];
  let arr = ids.slice();
  for (let r = 0; r < n - 1; r++) {
    let slot = 0;
    for (let i = 0; i < n / 2; i++) {
      const a = arr[i]!;
      const b = arr[n - 1 - i]!;
      if (a === null || b === null) continue;
      // Alternate who gets the first seat of game 1 so no one always starts.
      const [x, y] = (r + i) % 2 === 0 ? [a, b] : [b, a];
      out.push({ id: `RR${r + 1}-M${slot + 1}`, round: r + 1, roundName: `Round ${r + 1}`, slot: slot++, a: { entrant: x }, b: { entrant: y } });
    }
    arr = [arr[0]!, arr[n - 1]!, ...arr.slice(1, n - 1)];
  }
  return out;
}

/** Seed of one game. Colour-swapped pairs share a seed (same deal / same fallback stream per pair). */
export function gameSeed(tournamentSeed: number, matchId: string, gameNo: number, gamesPerMatch: number): number {
  const tag = gameNo <= gamesPerMatch ? `pair${Math.ceil(gameNo / 2)}` : `sd${gameNo}`;
  return hashString(`${tournamentSeed}|${matchId}|${tag}`);
}

export const gameKey = (matchId: string, gameNo: number) => `${matchId}-g${gameNo}`;

export interface BracketSpec {
  seed: number;
  entrants: ArenaEntrant[];
  settings: Pick<ArenaSettings, 'format' | 'gamesPerMatch' | 'suddenDeath'>;
  matches: MatchSpec[];
}

const half = (x: number) => {
  const whole = Math.floor(x);
  const frac = x - whole;
  return frac ? `${whole ? whole : ''}½` : String(whole);
};

/**
 * Derive the whole tournament state from the spec and the finished games.
 * `games` = latest record per game key (any status; only 'ok' games count).
 */
export function computeState(spec: BracketSpec, games: ArenaGameLite[], spentUsd?: number): TournamentState {
  const byKey = new Map(games.filter((g) => g.status === 'ok').map((g) => [g.key, g]));
  const seedOf = new Map(spec.entrants.map((e) => [e.id, e.seed]));
  const G = spec.settings.gamesPerMatch;
  const knockout = spec.settings.format === 'knockout';
  const states = new Map<string, MatchState>();
  const labelOf = new Map(spec.entrants.map((e) => [e.id, e.label]));

  const resolve = (s: MatchSource): { id: string | null; bye: boolean } => {
    if ('entrant' in s) return { id: s.entrant, bye: false };
    if ('bye' in s) return { id: null, bye: true };
    const prev = states.get(s.winnerOf);
    return { id: prev?.winner ?? null, bye: false };
  };

  for (const m of [...spec.matches].sort((x, y) => x.round - y.round || x.slot - y.slot)) {
    const a = resolve(m.a);
    const b = resolve(m.b);
    const st: MatchState = {
      id: m.id,
      round: m.round,
      roundName: m.roundName,
      slot: m.slot,
      players: [a.id, b.id],
      status: 'waiting',
      games: [],
      score: [0, 0],
      illegal: [0, 0],
      cost: [0, 0],
      winner: null,
      summary: '',
    };
    states.set(m.id, st);
    if (a.bye || b.bye) {
      st.status = 'bye';
      st.winner = a.bye ? b.id : a.id;
      st.decidedBy = 'bye';
      st.summary = st.winner ? `${labelOf.get(st.winner) ?? st.winner} advances with a bye` : 'Bye';
      continue;
    }
    if (!a.id || !b.id) continue;
    const pa = a.id;
    const pb = b.id;
    const slot = (n: number): GameSlot => {
      const players: [string, string] = n % 2 === 1 ? [pa, pb] : [pb, pa];
      const key = gameKey(m.id, n);
      return { key, gameNo: n, seed: gameSeed(spec.seed, m.id, n, G), players, suddenDeath: n > G, game: byKey.get(key) };
    };
    const tally = (s: GameSlot) => {
      const g = s.game;
      if (!g) return;
      for (const seat of [0, 1] as const) {
        const who = g.players[seat] === pa ? 0 : 1;
        st.illegal[who] += g.illegal[seat];
        st.cost[who] += g.metrics[seat].costUsd;
        if (g.winner === null) st.score[who] += 0.5;
        else if (g.winner === seat) st.score[who] += 1;
      }
    };
    for (let n = 1; n <= G; n++) st.games.push(slot(n));
    st.games.forEach(tally);
    const plannedDone = st.games.every((s) => s.game);
    st.status = st.games.some((s) => s.game) ? 'playing' : 'ready';
    if (!plannedDone) continue;

    const winnerBy = (decidedBy: MatchDecision, idx: 0 | 1) => {
      st.winner = idx === 0 ? pa : pb;
      st.decidedBy = decidedBy;
      st.status = 'done';
    };
    if (st.score[0] !== st.score[1]) winnerBy('games', st.score[0] > st.score[1] ? 0 : 1);
    else if (!knockout) {
      st.status = 'done';
      st.decidedBy = 'games';
    } else {
      // Sudden death: one game at a time, sides keep alternating; the first decisive game wins.
      let decided = false;
      for (let n = G + 1; n <= G + spec.settings.suddenDeath; n++) {
        const s = slot(n);
        st.games.push(s);
        if (!s.game) break;
        tally(s);
        if (s.game.winner !== null) {
          winnerBy('sudden-death', s.game.players[s.game.winner] === pa ? 0 : 1);
          decided = true;
          break;
        }
      }
      const sdDone = st.games.every((s) => s.game);
      if (!decided && sdDone) {
        if (st.illegal[0] !== st.illegal[1]) winnerBy('fewer illegal moves', st.illegal[0] < st.illegal[1] ? 0 : 1);
        else if (Math.abs(st.cost[0] - st.cost[1]) > 1e-9) winnerBy('lower cost', st.cost[0] < st.cost[1] ? 0 : 1);
        else winnerBy('higher seed', (seedOf.get(pa) ?? 99) <= (seedOf.get(pb) ?? 99) ? 0 : 1);
      }
    }
    if (st.status === 'done') {
      const la = labelOf.get(pa) ?? pa;
      const lb = labelOf.get(pb) ?? pb;
      const score = `${half(st.score[0])}–${half(st.score[1])}`;
      if (!st.winner) st.summary = `${la} and ${lb} draw ${score}`;
      else {
        const wIdx = st.winner === pa ? 0 : 1;
        const s = `${half(st.score[wIdx])}–${half(st.score[1 - wIdx]!)}`;
        const how = st.decidedBy === 'games' ? '' : st.decidedBy === 'sudden-death' ? ' in sudden death' : ` on ${st.decidedBy}`;
        st.summary = `${labelOf.get(st.winner) ?? st.winner} wins ${s}${how}`;
      }
    }
  }

  const matches = spec.matches.map((m) => states.get(m.id)!);
  const complete = matches.every((m) => m.status === 'done' || m.status === 'bye');

  // Standings
  const rows = new Map<string, StandingRow>();
  for (const e of spec.entrants) rows.set(e.id, { contestantId: e.id, seed: e.seed, played: 0, wins: 0, draws: 0, losses: 0, points: 0, illegal: 0, costUsd: 0, rank: 0 });
  const h2h = new Map<string, number>();
  for (const m of matches) {
    for (const s of m.games) {
      const g = s.game;
      if (!g) continue;
      for (const seat of [0, 1] as const) {
        const r = rows.get(g.players[seat]);
        if (!r) continue;
        r.played++;
        r.illegal += g.illegal[seat];
        r.costUsd += g.metrics[seat].costUsd;
        const pts = g.winner === null ? 0.5 : g.winner === seat ? 1 : 0;
        r.points += pts;
        if (g.winner === null) r.draws++;
        else if (g.winner === seat) r.wins++;
        else r.losses++;
        const k = `${g.players[seat]}>${g.players[1 - seat]}`;
        h2h.set(k, (h2h.get(k) ?? 0) + pts);
      }
    }
  }
  const standings = [...rows.values()];
  // Knockout: how far each entrant got (higher is better).
  const stage = new Map<string, number>();
  if (knockout) {
    for (const m of matches) for (const p of m.players) if (p) stage.set(p, Math.max(stage.get(p) ?? 0, m.round));
    const final = matches.find((m) => m.roundName === 'Final');
    if (final?.winner) stage.set(final.winner, (stage.get(final.winner) ?? 0) + 1);
  }
  const h2hAmong = (id: string, group: string[]) => group.reduce((s, o) => s + (o === id ? 0 : (h2h.get(`${id}>${o}`) ?? 0)), 0);
  standings.sort((x, y) => {
    if (knockout) {
      const d = (stage.get(y.contestantId) ?? 0) - (stage.get(x.contestantId) ?? 0);
      if (d) return d;
    }
    if (y.points !== x.points) return y.points - x.points;
    if (!knockout) {
      const tied = standings.filter((s) => s.points === x.points).map((s) => s.contestantId);
      const d = h2hAmong(y.contestantId, tied) - h2hAmong(x.contestantId, tied);
      if (d) return d;
    }
    if (x.illegal !== y.illegal) return x.illegal - y.illegal;
    if (Math.abs(x.costUsd - y.costUsd) > 1e-9) return x.costUsd - y.costUsd;
    return x.seed - y.seed;
  });
  standings.forEach((s, i) => (s.rank = i + 1));

  let champion: string | null = null;
  let runnerUp: string | null = null;
  if (knockout) {
    const final = matches.find((m) => m.roundName === 'Final');
    if (final?.winner) {
      champion = final.winner;
      runnerUp = final.players.find((p) => p && p !== final.winner) ?? null;
    }
  } else if (complete) {
    champion = standings[0]?.contestantId ?? null;
    runnerUp = standings[1]?.contestantId ?? null;
  }

  let gamesDone = 0;
  let gamesTotal = 0;
  for (const m of matches) {
    if (m.status === 'bye') continue;
    gamesDone += m.games.filter((s) => s.game).length;
    gamesTotal += Math.max(G, m.games.length);
  }
  const costUsd = spentUsd ?? games.reduce((s, g) => s + g.metrics[0].costUsd + g.metrics[1].costUsd, 0);
  return { matches, standings, champion, runnerUp, complete, gamesDone, gamesTotal, costUsd: Math.round(costUsd * 1e6) / 1e6 };
}

/** Games that can start now: both players known, match undecided, no finished record, not already running. */
export function playableSlots(state: TournamentState, exclude: Set<string>): Array<GameSlot & { matchId: string }> {
  const out: Array<GameSlot & { matchId: string }> = [];
  for (const m of state.matches) {
    if (m.status !== 'ready' && m.status !== 'playing') continue;
    for (const s of m.games) if (!s.game && !exclude.has(s.key)) out.push({ ...s, matchId: m.id });
  }
  return out;
}
