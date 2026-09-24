/**
 * Tournament orchestration: planning, cost estimates, scheduling games,
 * live events, spending cap, cancel and resume.
 *
 * The bracket state is always DERIVED from the manifest + the finished games
 * (bracket.ts), so a crash or a cancel loses at most the games in progress;
 * resume replays only what is missing.
 */
import { EventEmitter } from 'node:events';
import { execSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { contestantConfigHash, hasApiKey, loadContestants, loadProviders, loadSettings, snapshotContestant } from '../core/config.ts';
import { contentHash, sha256 } from '../core/hash.ts';
import { ROOT } from '../core/paths.ts';
import { createRng, hashString } from '../core/rng.ts';
import type { Contestant, ManualRequest, ProviderConfig } from '../core/types.ts';
import { HARNESS_VERSION, PROTOCOL_VERSION } from '../core/version.ts';
import { combinedLeaderboard } from '../engine/leaderboards.ts';
import { createRecorder, type CallPolicy, type CallTarget, type CaseRecorder } from '../engine/recorder.ts';
import { Semaphore } from '../engine/semaphore.ts';
import { createAdapter } from '../providers/index.ts';
import { manualEvents } from '../providers/manual.ts';
import { buildKnockout, buildRoundRobin, computeState, playableSlots } from './bracket.ts';
import { GAMES, getGame } from './games/index.ts';
import { playGame } from './match.ts';
import { ARENA_PROMPT_VERSION } from './prompt.ts';
import { appendGame, createTournamentFolder, listTournamentIds, newTournamentId, readGames, readTournament, spentUsd, toGameLite, writeTournament } from './store.ts';
import type {
  ArenaEntrant,
  ArenaEstimate,
  ArenaEvent,
  ArenaGameRecord,
  ArenaRequest,
  ArenaSettings,
  GameSlot,
  LiveGame,
  MatchSpec,
  SideMetrics,
  TournamentDetail,
  TournamentListItem,
  TournamentManifest,
  TournamentState,
} from './types.ts';

const ARENA_SRC = dirname(fileURLToPath(import.meta.url));
/** Sudden-death games start after one random move by each side, so deterministic models don't just replay game 1. */
export const SUDDEN_DEATH_OPENING_PLIES = 2;
const now = () => new Date().toISOString();

// ─────────────────────────────────────────────────────────────────────────────
// Hashes
// ─────────────────────────────────────────────────────────────────────────────

/** Hash of the game module (and every arena module it imports) + the shared prompt and match engine. */
export function gameSourceHash(gameId: string): string {
  const files = new Set<string>();
  const visit = (file: string) => {
    if (files.has(file) || !existsSync(file)) return;
    files.add(file);
    for (const m of readFileSync(file, 'utf8').matchAll(/from\s+['"](\.{1,2}\/[^'"]+)['"]/g)) {
      const target = resolve(dirname(file), m[1]!);
      if (target.startsWith(ARENA_SRC) && !target.endsWith('types.ts')) visit(target);
    }
  };
  visit(join(ARENA_SRC, 'games', `${gameId}.ts`));
  visit(join(ARENA_SRC, 'prompt.ts'));
  visit(join(ARENA_SRC, 'match.ts'));
  const sorted = [...files].sort();
  return sha256(sorted.map((f) => relative(ROOT, f).replace(/\\/g, '/') + '\n' + readFileSync(f, 'utf8').replace(/\r\n/g, '\n')).join('\n---\n')).slice(0, 12);
}

function gitCommit(): string | undefined {
  try {
    return execSync('git rev-parse --short HEAD', { cwd: ROOT, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim() || undefined;
  } catch {
    return undefined;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Planning
// ─────────────────────────────────────────────────────────────────────────────

export interface ArenaPlan {
  gameId: string;
  gameHash: string;
  entrants: ArenaEntrant[];
  settings: ArenaSettings;
  matches: MatchSpec[];
  seed: number;
  fingerprint: string;
  warnings: string[];
}

const providerType = (c: Contestant, providers: ProviderConfig[]) => providers.find((p) => p.id === c.provider)?.type;

/** Current Gauntlet Index per contestant (combined core leaderboard); empty when there are no results. */
function currentIndex(): Map<string, number> {
  try {
    const board = combinedLeaderboard('core');
    return new Map(board.rows.filter((r) => typeof r.index === 'number').map((r) => [r.contestantId, r.index as number]));
  } catch {
    return new Map();
  }
}

export function arenaFingerprint(gameHash: string, settings: ArenaSettings, seed: number): string {
  return contentHash({
    gameHash,
    prompt: ARENA_PROMPT_VERSION,
    protocol: settings.protocolVersion,
    format: settings.format,
    gamesPerMatch: settings.gamesPerMatch,
    suddenDeath: settings.suddenDeath,
    suddenDeathOpening: SUDDEN_DEATH_OPENING_PLIES,
    maxStrikes: settings.maxStrikes,
    temperature: settings.temperature,
    maxOutputTokens: settings.maxOutputTokens,
    game: settings.game,
    seed,
  });
}

export function planTournament(req: ArenaRequest): ArenaPlan {
  const game = getGame(req.game);
  const settings = loadSettings();
  const providers = loadProviders();
  const all = loadContestants();
  const ids = [...new Set((req.contestantIds ?? []).map((s) => s.trim()).filter(Boolean))];
  if (ids.length < 2) throw new Error('Pick at least 2 models');
  if (ids.length > 16) throw new Error('At most 16 models per tournament');
  const format = req.format ?? 'knockout';
  if (format !== 'knockout' && format !== 'round-robin') throw new Error('format must be "knockout" or "round-robin"');
  const gamesPerMatch = req.gamesPerMatch ?? 2;
  if (![2, 4, 6].includes(gamesPerMatch)) throw new Error('gamesPerMatch must be 2, 4 or 6 (sides swap every game)');
  if (req.maxCostUsd !== undefined && req.maxCostUsd !== null && !(req.maxCostUsd > 0)) throw new Error('maxCostUsd must be a positive number');
  const contestants = ids.map((id) => {
    const c = all.find((x) => x.id === id);
    if (!c) throw new Error(`Unknown model "${id}"`);
    return c;
  });
  const seeding = req.seeding ?? 'index';
  const index = seeding === 'index' ? currentIndex() : new Map<string, number>();
  const ordered =
    seeding === 'index'
      ? contestants
          .map((c, i) => ({ c, i, idx: index.get(c.id) }))
          .sort((a, b) => (b.idx ?? -1) - (a.idx ?? -1) || a.i - b.i)
          .map((x) => x.c)
      : contestants;
  const entrants: ArenaEntrant[] = ordered.map((c, i) => ({
    ...snapshotContestant(c),
    seed: i + 1,
    index: index.get(c.id) ?? null,
    manual: providerType(c, providers) === 'manual' || undefined,
    baseline: providerType(c, providers) === 'mock' || undefined,
  }));
  const gameCfg = { ...game.defaults };
  if (req.maxPlies !== undefined) {
    if (!(req.maxPlies >= 2 && req.maxPlies <= 1000)) throw new Error('maxPlies must be between 2 and 1000');
    gameCfg.maxPlies = Math.floor(req.maxPlies);
  }
  if (req.listLegalMoves !== undefined) gameCfg.listLegalMoves = Boolean(req.listLegalMoves);
  const arenaSettings: ArenaSettings = {
    format,
    seeding,
    gamesPerMatch,
    suddenDeath: format === 'knockout' ? Math.max(0, Math.min(6, Math.floor(req.suddenDeath ?? 2))) : 0,
    maxStrikes: Math.max(1, Math.min(10, Math.floor(req.maxStrikes ?? 3))),
    concurrency: Math.max(1, Math.min(16, Math.floor(req.concurrency ?? 2))),
    temperature: settings.temperature,
    maxOutputTokens: settings.defaultMaxOutputTokens,
    maxCostUsd: req.maxCostUsd ?? undefined,
    game: gameCfg,
    protocolVersion: PROTOCOL_VERSION,
  };
  const seed = Number.isFinite(req.seed) ? Math.floor(req.seed!) >>> 0 : 1;
  const matches = format === 'knockout' ? buildKnockout(entrants.map((e) => e.id)) : buildRoundRobin(entrants.map((e) => e.id));
  const gameHash = gameSourceHash(game.id);

  const warnings: string[] = [];
  for (const c of contestants) {
    const p = providers.find((x) => x.id === c.provider);
    if (!p) warnings.push(`${c.label}: provider "${c.provider}" is not configured`);
    else if (!hasApiKey(p)) warnings.push(`${c.label}: ${p.apiKeyEnv} is not set — its games will error`);
    if (p && p.type !== 'mock' && p.type !== 'manual' && !c.pricing.verifiedAt) warnings.push(`${c.label}: pricing is unverified — cost figures may be wrong`);
  }
  const manual = entrants.filter((e) => e.manual);
  if (manual.length) warnings.push(`${manual.map((e) => e.label).join(', ')}: manual contestant — every move waits in the Manual Inbox for you to paste the model's reply`);
  if (!gameCfg.listLegalMoves && entrants.some((e) => e.baseline)) warnings.push('Legal moves are not listed: the Random Baseline cannot pick moves and will lose on strikes');
  if (seeding === 'index' && contestants.some((c) => !index.has(c.id))) {
    const missing = contestants.filter((c) => !index.has(c.id)).map((c) => c.label);
    warnings.push(`No Gauntlet Index yet for ${missing.join(', ')}: seeded after the ranked models, in the order given`);
  }
  if (format === 'knockout' && ![4, 8, 16].includes(ids.length)) warnings.push(`${ids.length} entrants is not 4, 8 or 16: the top seeds get byes into round 2`);
  return { gameId: game.id, gameHash, entrants, settings: arenaSettings, matches, seed, fingerprint: arenaFingerprint(gameHash, arenaSettings, seed), warnings };
}

// ─────────────────────────────────────────────────────────────────────────────
// Estimates
// ─────────────────────────────────────────────────────────────────────────────

interface MoveStats {
  input: number;
  output: number;
  n: number;
}

/** Average tokens per move measured in earlier tournaments of the same game version, per contestant config. */
function measuredPerMove(gameHash: string): Map<string, MoveStats> {
  const out = new Map<string, MoveStats>();
  for (const id of listTournamentIds()) {
    const m = readTournament(id);
    if (!m || m.game.hash !== gameHash) continue;
    const cfg = new Map(m.entrants.map((e) => [e.id, e]));
    for (const g of readGames(id)) {
      for (const seat of [0, 1] as const) {
        const e = cfg.get(g.players[seat]);
        if (!e || e.manual || e.baseline) continue;
        const moves = g.moves.filter((x) => x.side === seat);
        if (!moves.length) continue;
        let s = out.get(e.configHash);
        if (!s) out.set(e.configHash, (s = { input: 0, output: 0, n: 0 }));
        for (const mv of moves) {
          s.input += mv.inputTokens;
          s.output += mv.outputTokens;
          s.n++;
        }
      }
    }
  }
  return out;
}

export function estimateTournament(req: ArenaRequest): ArenaEstimate {
  const plan = planTournament(req);
  const game = getGame(plan.gameId);
  const measured = measuredPerMove(plan.gameHash);
  const plies = Math.min(game.estimate.pliesPerGame, plan.settings.game.maxPlies);
  const per = new Map<string, { move: number; basis: 'measured' | 'definition'; manual: boolean }>();
  const perContestant = plan.entrants.map((e) => {
    const m = measured.get(e.configHash);
    const basis: 'measured' | 'definition' = m && m.n >= 10 ? 'measured' : 'definition';
    const input = basis === 'measured' ? m!.input / m!.n : game.estimate.inputTokensPerMove;
    const output = basis === 'measured' ? m!.output / m!.n : game.estimate.outputTokensPerMove;
    const free = Boolean(e.manual);
    // +5% for retries after rejected moves.
    const move = free ? 0 : ((input * e.pricing.inputPerM + output * e.pricing.outputPerM) / 1e6) * 1.05;
    per.set(e.id, { move, basis, manual: Boolean(e.manual) });
    return { contestantId: e.id, perMoveUsd: round4(move), perGameUsd: round4((move * plies) / 2), basis, manual: Boolean(e.manual) };
  });
  const pairCost = (a: string, b: string) => ((per.get(a)!.move + per.get(b)!.move) * plies) / 2;

  // Who could possibly play in each match (later knockout rounds depend on results).
  const possible = new Map<string, string[]>();
  const sourceSet = (s: MatchSpec['a']): string[] => ('entrant' in s ? [s.entrant] : 'bye' in s ? [] : (possible.get(s.winnerOf) ?? []));
  const G = plan.settings.gamesPerMatch;
  let games = 0;
  let maxGames = 0;
  let central = 0;
  let high = 0;
  const perGame: ArenaEstimate['perGame'] = [];
  for (const m of [...plan.matches].sort((x, y) => x.round - y.round || x.slot - y.slot)) {
    const A = sourceSet(m.a);
    const B = sourceSet(m.b);
    possible.set(m.id, [...A, ...B]);
    if (!A.length || !B.length) continue;
    let sum = 0;
    let max = 0;
    for (const a of A)
      for (const b of B) {
        const c = pairCost(a, b);
        sum += c;
        max = Math.max(max, c);
      }
    const avg = sum / (A.length * B.length);
    if (A.length === 1 && B.length === 1) perGame.push({ a: A[0]!, b: B[0]!, estCostUsd: round4(avg) });
    games += G;
    maxGames += G + plan.settings.suddenDeath;
    central += avg * G;
    high += max * (G + plan.settings.suddenDeath);
  }
  const anyDefinition = perContestant.some((p) => p.basis === 'definition' && p.perMoveUsd > 0);
  high *= anyDefinition ? 1.6 : 1.2;
  const warnings = plan.warnings.slice();
  if (plan.settings.maxCostUsd !== undefined && central > plan.settings.maxCostUsd) warnings.push(`Estimated cost ${central.toFixed(2)} USD exceeds your cap of ${plan.settings.maxCostUsd.toFixed(2)} USD: the tournament will stop when the cap is reached`);
  return {
    games,
    maxGames,
    moves: Math.round(games * plies),
    estCostUsd: round4(central),
    estCostUsdHigh: round4(high),
    perGame,
    perContestant,
    fingerprint: plan.fingerprint,
    warnings,
    entrants: plan.entrants.map((e) => ({ id: e.id, seed: e.seed, index: e.index })),
    matches: plan.matches,
  };
}

function round4(n: number): number {
  return Math.round(n * 10000) / 10000;
}

// ─────────────────────────────────────────────────────────────────────────────
// Execution
// ─────────────────────────────────────────────────────────────────────────────

interface ActiveTournament {
  manifest: TournamentManifest;
  controller: AbortController;
  events: EventEmitter;
  live: Map<string, LiveGame>;
  /** Spend of games in progress (not yet in games.jsonl). */
  liveSpend: () => number;
  done: Promise<void>;
}

const active = new Map<string, ActiveTournament>();

export function isTournamentActive(id: string): boolean {
  return active.has(id);
}

export function subscribeTournament(id: string, listener: (e: ArenaEvent) => void): () => void {
  const t = active.get(id);
  if (!t) return () => {};
  t.events.on('event', listener);
  return () => t.events.off('event', listener);
}

export async function waitForTournament(id: string): Promise<void> {
  await active.get(id)?.done;
}

export function startTournament(req: ArenaRequest): string {
  const plan = planTournament(req);
  const game = getGame(plan.gameId);
  const id = newTournamentId();
  const manifest: TournamentManifest = {
    id,
    name: req.name?.trim() || `${game.name} ${plan.settings.format === 'knockout' ? 'knockout' : 'round-robin'} · ${plan.entrants.length} models`,
    status: 'queued',
    createdAt: now(),
    harnessVersion: HARNESS_VERSION,
    gitCommit: gitCommit(),
    node: process.version,
    platform: `${process.platform}-${process.arch}`,
    game: { id: game.id, name: game.name, version: game.version, hash: plan.gameHash },
    fingerprint: plan.fingerprint,
    seed: plan.seed,
    entrants: plan.entrants,
    settings: plan.settings,
    matches: plan.matches,
    notes: req.notes,
  };
  createTournamentFolder(manifest);
  launch(manifest);
  return id;
}

export function resumeTournament(id: string, opts: { maxCostUsd?: number | null } = {}): void {
  if (active.has(id)) throw new Error('Tournament is already running');
  const manifest = readTournament(id);
  if (!manifest) throw new Error('Tournament not found');
  const game = GAMES[manifest.game.id];
  if (!game) throw new Error(`Game "${manifest.game.id}" is no longer available`);
  const hash = gameSourceHash(game.id);
  if (hash !== manifest.game.hash) throw new Error(`Cannot resume: the ${game.name} rules or prompts changed since this tournament started (game hash ${manifest.game.hash} → ${hash}), so new games would not be comparable`);
  const all = loadContestants();
  const changed = manifest.entrants.filter((e) => {
    const c = all.find((x) => x.id === e.id);
    return !c || contestantConfigHash(c) !== e.configHash;
  });
  if (changed.length) throw new Error(`Cannot resume: these models were changed or removed since the tournament started: ${changed.map((e) => e.label).join(', ')}`);
  if (opts.maxCostUsd !== undefined) manifest.settings.maxCostUsd = opts.maxCostUsd === null ? undefined : opts.maxCostUsd;
  manifest.error = undefined;
  launch(manifest);
}

export function cancelTournament(id: string): boolean {
  const t = active.get(id);
  if (!t) return false;
  t.controller.abort();
  return true;
}

/** Tournaments left "running" by a crashed process become "interrupted" (resumable). */
export function recoverInterruptedTournaments(): void {
  for (const id of listTournamentIds()) {
    if (active.has(id)) continue;
    const m = readTournament(id);
    if (m && (m.status === 'running' || m.status === 'queued')) {
      m.status = 'interrupted';
      writeTournament(m);
    }
  }
}

class BudgetReached extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BudgetReached';
  }
}

const emptyMetrics = (): SideMetrics => ({ costUsd: 0, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, apiCalls: 0, retries: 0, ms: 0 });

function metricsOf(r: CaseRecorder): SideMetrics {
  return {
    costUsd: Math.round(r.costUsd * 1e8) / 1e8,
    inputTokens: r.usage.inputTokens + r.usage.cachedInputTokens,
    outputTokens: r.usage.outputTokens,
    reasoningTokens: r.usage.reasoningTokens,
    apiCalls: r.apiCalls,
    retries: r.retries,
    ms: r.generationMs,
  };
}

function stateOf(manifest: TournamentManifest, extraSpend = 0): TournamentState {
  return computeState(manifest, readGames(manifest.id).map(toGameLite), spentUsd(manifest.id) + extraSpend);
}

function launch(manifest: TournamentManifest): void {
  const settings = loadSettings();
  const providers = loadProviders();
  const game = getGame(manifest.game.id);
  const controller = new AbortController();
  const events = new EventEmitter();
  events.setMaxListeners(100);
  const live = new Map<string, LiveGame>();
  const t: ActiveTournament = { manifest, controller, events, live, liveSpend: () => 0, done: Promise.resolve() };
  active.set(manifest.id, t);
  const emit = (e: ArenaEvent) => events.emit('event', e);
  const log = (level: 'info' | 'warn' | 'error', message: string) => emit({ type: 'log', tournamentId: manifest.id, level, message, at: now() });

  const onManualRequest = (request: ManualRequest) => {
    if (request.runId === manifest.id) emit({ type: 'manual.request', tournamentId: manifest.id, request });
  };
  const onManualResolved = (request: ManualRequest) => {
    if (request.runId === manifest.id) emit({ type: 'manual.resolved', tournamentId: manifest.id, requestId: request.id });
  };
  manualEvents.on('request', onManualRequest);
  manualEvents.on('resolved', onManualResolved);

  const semaphores = new Map<string, Semaphore>();
  const targets = new Map<string, CallTarget>();
  const targetFor = (c: Contestant): CallTarget => {
    let target = targets.get(c.id);
    if (!target) {
      const p = providers.find((x) => x.id === c.provider);
      if (!p) throw new Error(`Provider "${c.provider}" is not configured`);
      let sem = semaphores.get(p.id);
      if (!sem) semaphores.set(p.id, (sem = new Semaphore(p.maxConcurrency ?? 8)));
      target = { contestant: c, adapter: createAdapter(c, p), semaphore: sem };
      targets.set(c.id, target);
    }
    return target;
  };
  const policy: CallPolicy = { maxRetries: settings.maxRetries, temperature: manifest.settings.temperature, defaultMaxOutputTokens: manifest.settings.maxOutputTokens };
  const entrant = new Map(manifest.entrants.map((e) => [e.id, e]));
  const cap = manifest.settings.maxCostUsd;
  const inFlightRecorders = new Set<CaseRecorder>();
  const liveSpend = () => [...inFlightRecorders].reduce((s, r) => s + r.costUsd, 0);
  t.liveSpend = liveSpend;
  let budgetHit = false;

  // Streaming text, throttled to ~7 events/s per game.
  const thinking = new Map<string, { side: 0 | 1; text: string; attempt: number }>();
  const flush = setInterval(() => {
    for (const [key, d] of thinking) emit({ type: 'game.thinking', tournamentId: manifest.id, key, side: d.side, text: d.text, attempt: d.attempt });
    thinking.clear();
  }, 150);

  const runGame = async (slot: GameSlot & { matchId: string }): Promise<void> => {
    const ctrl = new AbortController();
    const onAbort = () => ctrl.abort();
    controller.signal.addEventListener('abort', onAbort, { once: true });
    const startedAt = now();
    const [p0, p1] = slot.players;
    // A hung API call must not stall the bracket: each move gets the per-case time limit (humans pasting replies get a week).
    const moveLimitMs = slot.players.some((p) => entrant.get(p)?.manual) ? 7 * 24 * 3600 * 1000 : settings.defaultTimeLimitSec * 1000;
    let moveTimer: ReturnType<typeof setTimeout> | undefined;
    let timedOut = false;
    const liveGame: LiveGame = {
      key: slot.key,
      matchId: slot.matchId,
      gameNo: slot.gameNo,
      players: [p0, p1],
      moves: [],
      initial: game.snapshot(game.setup(createRng(slot.seed).fork('setup'), manifest.settings.game)),
      toMove: 0,
      thinking: '',
      strikes: [0, 0],
      metrics: [emptyMetrics(), emptyMetrics()],
      startedAt,
      turnStartedAt: startedAt,
    };
    live.set(slot.key, liveGame);
    emit({ type: 'game.started', tournamentId: manifest.id, game: liveGame, at: startedAt });
    const recorders: CaseRecorder[] = [];
    let record: ArenaGameRecord | null = null;
    try {
      for (const side of [0, 1] as const) {
        const e = entrant.get(slot.players[side]);
        if (!e) throw new Error(`Unknown entrant ${slot.players[side]}`);
        const rec = createRecorder({
          target: targetFor(e),
          policy,
          signal: ctrl.signal,
          maxOutputTokens: manifest.settings.maxOutputTokens,
          callContext: { runId: manifest.id, key: slot.key, testId: `arena.${game.id}`, testName: `${game.name} · ${slot.matchId} game ${slot.gameNo}`, caseId: slot.key },
          onDelta: (text) => {
            const d = thinking.get(slot.key);
            if (d && d.side === side) d.text += text;
            else thinking.set(slot.key, { side, text, attempt: 1 });
            liveGame.thinking = (liveGame.thinking + text).slice(-3000);
          },
          onRetry: (attempt, wait, err) => log('warn', `${e.label} · ${slot.key}: retry ${attempt} in ${(wait / 1000).toFixed(1)}s (${err.message.slice(0, 160)})`),
        });
        recorders.push(rec);
        inFlightRecorders.add(rec);
      }
      const played = await playGame({
        game,
        config: manifest.settings.game,
        seed: slot.seed,
        seats: [
          { handle: recorders[0]!.handle, meter: () => ({ costUsd: recorders[0]!.costUsd, inputTokens: recorders[0]!.usage.inputTokens + recorders[0]!.usage.cachedInputTokens, outputTokens: recorders[0]!.usage.outputTokens }) },
          { handle: recorders[1]!.handle, meter: () => ({ costUsd: recorders[1]!.costUsd, inputTokens: recorders[1]!.usage.inputTokens + recorders[1]!.usage.cachedInputTokens, outputTokens: recorders[1]!.usage.outputTokens }) },
        ],
        maxStrikes: manifest.settings.maxStrikes,
        maxOutputTokens: manifest.settings.maxOutputTokens,
        openingPlies: slot.suddenDeath ? SUDDEN_DEATH_OPENING_PLIES : 0,
        signal: ctrl.signal,
        beforeCall: () => {
          if (cap !== undefined && spentUsd(manifest.id) + liveSpend() >= cap) {
            budgetHit = true;
            throw new BudgetReached(`Budget cap of $${cap.toFixed(2)} reached. Resume with a higher cap to finish.`);
          }
        },
        onTurn: (side, _ply, attempt) => {
          clearTimeout(moveTimer);
          moveTimer = setTimeout(() => {
            timedOut = true;
            ctrl.abort();
          }, moveLimitMs);
          const d = thinking.get(slot.key);
          if (d) emit({ type: 'game.thinking', tournamentId: manifest.id, key: slot.key, side: d.side, text: d.text, attempt: d.attempt });
          thinking.delete(slot.key);
          liveGame.toMove = side;
          liveGame.thinking = '';
          liveGame.turnStartedAt = now();
          emit({ type: 'game.turn', tournamentId: manifest.id, key: slot.key, side, at: liveGame.turnStartedAt });
          if (attempt > 1) log('info', `${entrant.get(slot.players[side])?.label ?? slot.players[side]} gets a retry on ${slot.key} (previous move rejected)`);
        },
        onMove: (move, strikes) => {
          liveGame.moves.push(move);
          liveGame.strikes = strikes;
          liveGame.metrics = [metricsOf(recorders[0]!), metricsOf(recorders[1]!)];
          emit({ type: 'game.move', tournamentId: manifest.id, key: slot.key, move, strikes, metrics: liveGame.metrics, at: now() });
        },
      });
      record = {
        key: slot.key,
        tournamentId: manifest.id,
        matchId: slot.matchId,
        gameNo: slot.gameNo,
        seed: slot.seed,
        players: [p0, p1],
        status: 'ok',
        winner: played.winner,
        reason: played.reason,
        moves: played.moves,
        initial: played.initial,
        strikes: played.strikes,
        illegal: played.illegal,
        metrics: [metricsOf(recorders[0]!), metricsOf(recorders[1]!)],
        transcripts: [recorders[0]!.transcript, recorders[1]!.transcript],
        startedAt,
        finishedAt: now(),
      };
    } catch (err) {
      const aborted = (ctrl.signal.aborted && !timedOut) || err instanceof BudgetReached;
      if (timedOut) err = new Error(`No reply within ${Math.round(moveLimitMs / 1000)} s for one move`);
      if (!aborted) log('error', `${slot.key}: ${(err as Error).message}`);
      record = {
        key: slot.key,
        tournamentId: manifest.id,
        matchId: slot.matchId,
        gameNo: slot.gameNo,
        seed: slot.seed,
        players: [p0, p1],
        status: aborted ? 'cancelled' : 'error',
        winner: null,
        reason: aborted ? (err instanceof BudgetReached ? 'Stopped: budget cap reached' : 'Cancelled') : 'Error',
        moves: liveGame.moves,
        initial: liveGame.initial,
        strikes: liveGame.strikes,
        illegal: [0, 0],
        metrics: recorders.length === 2 ? [metricsOf(recorders[0]!), metricsOf(recorders[1]!)] : [emptyMetrics(), emptyMetrics()],
        transcripts: recorders.length === 2 ? [recorders[0]!.transcript, recorders[1]!.transcript] : [[], []],
        error: aborted ? undefined : (err as Error).message,
        startedAt,
        finishedAt: now(),
      };
    } finally {
      clearTimeout(moveTimer);
      controller.signal.removeEventListener('abort', onAbort);
      for (const r of recorders) inFlightRecorders.delete(r);
      live.delete(slot.key);
      const d = thinking.get(slot.key);
      if (d) emit({ type: 'game.thinking', tournamentId: manifest.id, key: slot.key, side: d.side, text: d.text, attempt: d.attempt });
      thinking.delete(slot.key);
    }
    // Cancelled games with no API spend leave no trace; everything else is logged (spend always counts).
    const spent = record.metrics[0].apiCalls + record.metrics[1].apiCalls > 0;
    if (record.status !== 'cancelled' || spent) appendGame(record);
    if (record.status !== 'cancelled') emit({ type: 'game.finished', tournamentId: manifest.id, game: toGameLite(record), at: now() });
  };

  t.done = (async () => {
    manifest.status = 'running';
    manifest.startedAt ??= now();
    manifest.finishedAt = undefined;
    writeTournament(manifest);
    emit({ type: 'tournament.status', tournamentId: manifest.id, status: 'running', at: now() });
    const inFlight = new Map<string, Promise<void>>();
    const failed = new Set<string>();
    let prevDone = new Set(stateOf(manifest).matches.filter((m) => m.status === 'done').map((m) => m.id));
    try {
      for (;;) {
        const state = stateOf(manifest, liveSpend());
        for (const m of state.matches) {
          if (m.status === 'done' && !prevDone.has(m.id)) emit({ type: 'match.finished', tournamentId: manifest.id, match: m, at: now() });
        }
        prevDone = new Set(state.matches.filter((m) => m.status === 'done').map((m) => m.id));
        emit({ type: 'tournament.progress', tournamentId: manifest.id, gamesDone: state.gamesDone, gamesTotal: state.gamesTotal, costUsd: state.costUsd, at: now() });
        if (state.complete) break;
        if (!controller.signal.aborted && !budgetHit) {
          if (cap !== undefined && state.costUsd >= cap) budgetHit = true;
          else {
            const ready = playableSlots(state, new Set([...inFlight.keys(), ...failed]));
            while (inFlight.size < manifest.settings.concurrency && ready.length) {
              const slot = ready.shift()!;
              const p = runGame(slot).then(
                () => {
                  const g = readGames(manifest.id).find((x) => x.key === slot.key);
                  if (!g || g.status !== 'ok') failed.add(slot.key);
                  inFlight.delete(slot.key);
                },
                (err: unknown) => {
                  failed.add(slot.key);
                  inFlight.delete(slot.key);
                  log('error', `${slot.key}: ${(err as Error).message}`);
                },
              );
              inFlight.set(slot.key, p);
            }
          }
        }
        if (inFlight.size === 0) break;
        await Promise.race(inFlight.values());
      }
      const final = stateOf(manifest);
      if (final.complete) manifest.status = 'completed';
      else if (controller.signal.aborted) manifest.status = 'cancelled';
      else if (budgetHit) {
        manifest.status = 'cancelled';
        manifest.error = `Budget cap of $${(cap ?? 0).toFixed(2)} reached ($${final.costUsd.toFixed(4)} spent). Resume with a higher cap to finish.`;
        log('warn', manifest.error);
      } else {
        manifest.status = 'failed';
        manifest.error = `${failed.size} game(s) could not be played (${[...failed].slice(0, 4).join(', ')}${failed.size > 4 ? ', …' : ''}). Fix the cause (see the log) and resume.`;
        log('error', manifest.error);
      }
    } catch (err) {
      manifest.status = 'failed';
      manifest.error = (err as Error).message;
      log('error', manifest.error);
    } finally {
      clearInterval(flush);
      manualEvents.off('request', onManualRequest);
      manualEvents.off('resolved', onManualResolved);
      manifest.finishedAt = now();
      writeTournament(manifest);
      emit({ type: 'tournament.status', tournamentId: manifest.id, status: manifest.status, at: now(), error: manifest.error });
      active.delete(manifest.id);
      events.emit('end');
    }
  })();
}

// ─────────────────────────────────────────────────────────────────────────────
// Reading
// ─────────────────────────────────────────────────────────────────────────────

export function tournamentDetail(id: string): TournamentDetail | null {
  const manifest = active.get(id)?.manifest ?? readTournament(id);
  if (!manifest) return null;
  const t = active.get(id);
  const games = readGames(id).map(toGameLite);
  return {
    manifest,
    state: computeState(manifest, games, spentUsd(id) + (t?.liveSpend() ?? 0)),
    games,
    active: Boolean(t),
    live: t ? [...t.live.values()] : [],
  };
}

export function gameRecord(id: string, key: string): ArenaGameRecord | null {
  return readGames(id).find((g) => g.key === key) ?? null;
}

export function listTournaments(): TournamentListItem[] {
  const out: TournamentListItem[] = [];
  for (const id of listTournamentIds()) {
    const m = active.get(id)?.manifest ?? readTournament(id);
    if (!m) continue;
    const state = computeState(m, readGames(id).map(toGameLite), spentUsd(id));
    out.push({
      id,
      name: m.name,
      status: m.status,
      createdAt: m.createdAt,
      finishedAt: m.finishedAt,
      game: m.game,
      format: m.settings.format,
      entrants: m.entrants.map((e) => ({ id: e.id, label: e.label, color: e.color, seed: e.seed })),
      champion: state.champion,
      gamesDone: state.gamesDone,
      gamesTotal: state.gamesTotal,
      costUsd: state.costUsd,
      fingerprint: m.fingerprint,
    });
  }
  return out;
}

/** Stable seed for callers that want a fresh random tournament seed. */
export function randomSeed(): number {
  return hashString(`${Date.now()}|${Math.random()}`);
}
