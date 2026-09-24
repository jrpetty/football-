/**
 * Arena storage. Each tournament is a folder under data/arena/<id>/:
 *   manifest.json — entrants (model snapshots), settings, bracket, fingerprint, status
 *   games.jsonl   — append-only log of ArenaGameRecord (latest line per game key wins)
 * Plain files, like runs: auditable, diffable, easy to archive or publish.
 */
import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { DATA_DIR } from '../core/paths.ts';
import { writeJsonAtomic } from '../core/config.ts';
import type { ArenaGameLite, ArenaGameRecord, TournamentManifest } from './types.ts';

export const ARENA_DIR = join(DATA_DIR, 'arena');

export function tournamentDir(id: string): string {
  if (!/^[A-Za-z0-9_-]+$/.test(id)) throw new Error('Invalid tournament id');
  return join(ARENA_DIR, id);
}

export function newTournamentId(): string {
  const d = new Date();
  const stamp = d.toISOString().replace(/[-:]/g, '').replace(/\..*/, '').replace('T', '-');
  return `arena-${stamp}-${Math.random().toString(36).slice(2, 6)}`;
}

export function createTournamentFolder(m: TournamentManifest): void {
  const dir = tournamentDir(m.id);
  mkdirSync(dir, { recursive: true });
  writeJsonAtomic(join(dir, 'manifest.json'), m);
  if (!existsSync(join(dir, 'games.jsonl'))) writeFileSync(join(dir, 'games.jsonl'), '');
}

export function readTournament(id: string): TournamentManifest | null {
  let file: string;
  try {
    file = join(tournamentDir(id), 'manifest.json');
  } catch {
    return null;
  }
  if (!existsSync(file)) return null;
  return JSON.parse(readFileSync(file, 'utf8')) as TournamentManifest;
}

export function writeTournament(m: TournamentManifest): void {
  writeJsonAtomic(join(tournamentDir(m.id), 'manifest.json'), m);
}

export function appendGame(g: ArenaGameRecord): void {
  appendFileSync(join(tournamentDir(g.tournamentId), 'games.jsonl'), JSON.stringify(g) + '\n');
  cache.delete(g.tournamentId);
}

interface Cached {
  mtimeMs: number;
  size: number;
  games: ArenaGameRecord[];
  /** Everything ever spent, including games that errored and were replayed. */
  spentUsd: number;
}
const cache = new Map<string, Cached>();

function load(id: string): Cached {
  const file = join(tournamentDir(id), 'games.jsonl');
  if (!existsSync(file)) return { mtimeMs: 0, size: 0, games: [], spentUsd: 0 };
  const st = statSync(file);
  const hit = cache.get(id);
  if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return hit;
  const byKey = new Map<string, ArenaGameRecord>();
  let spent = 0;
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try {
      const g = JSON.parse(line) as ArenaGameRecord;
      spent += g.metrics[0].costUsd + g.metrics[1].costUsd;
      byKey.delete(g.key);
      byKey.set(g.key, g);
    } catch {
      // A torn final line after a crash is ignored; the game is replayed on resume.
    }
  }
  const out = { mtimeMs: st.mtimeMs, size: st.size, games: [...byKey.values()], spentUsd: spent };
  cache.set(id, out);
  return out;
}

/** Latest record per game key, in insertion order. */
export function readGames(id: string): ArenaGameRecord[] {
  return load(id).games;
}

export function spentUsd(id: string): number {
  return load(id).spentUsd;
}

export function toGameLite(g: ArenaGameRecord): ArenaGameLite {
  const { transcripts: _t, moves, ...rest } = g;
  return { ...rest, plies: moves.filter((m) => m.move).length, lastSnapshot: moves.length ? moves[moves.length - 1]!.snapshot : g.initial };
}

export function listTournamentIds(): string[] {
  if (!existsSync(ARENA_DIR)) return [];
  return readdirSync(ARENA_DIR)
    .filter((d) => existsSync(join(ARENA_DIR, d, 'manifest.json')))
    .sort()
    .reverse();
}

export function deleteTournament(id: string): void {
  rmSync(tournamentDir(id), { recursive: true, force: true });
  cache.delete(id);
}
