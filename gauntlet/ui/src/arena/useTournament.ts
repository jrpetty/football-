/** Live tournament state: GET detail once, then patch it from the event stream. */
import { useCallback, useEffect, useRef, useState } from 'react';
import { arenaApi, subscribeTournament } from './client.ts';
import type { ArenaEntrant, ArenaEvent, LiveGame, TournamentDetail } from './types.ts';

const THINK_MAX = 4000;

export interface TournamentLive {
  detail: TournamentDetail | null;
  error: Error | null;
  live: LiveGame[];
  /** Most recent log line worth showing (retries, errors). */
  lastLog: { level: string; message: string } | null;
  reload: () => void;
  /** Bumps whenever a match finishes (drives bracket animations). */
  matchTick: number;
}

export function useTournament(id: string): TournamentLive {
  const [detail, setDetail] = useState<TournamentDetail | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [live, setLive] = useState<Map<string, LiveGame>>(new Map());
  const [lastLog, setLastLog] = useState<TournamentLive['lastLog']>(null);
  const [matchTick, setMatchTick] = useState(0);
  const timer = useRef<number | undefined>(undefined);

  const load = useCallback(() => {
    arenaApi
      .get(id)
      .then((d) => {
        setDetail(d);
        setError(null);
        setLive(new Map(d.live.map((g) => [g.key, g])));
      })
      .catch((e: unknown) => setError(e instanceof Error ? e : new Error(String(e))));
  }, [id]);

  const reloadSoon = useCallback(() => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(load, 250);
  }, [load]);

  useEffect(() => {
    load();
    const patch = (key: string, fn: (g: LiveGame) => LiveGame) =>
      setLive((m) => {
        const g = m.get(key);
        if (!g) return m;
        const next = new Map(m);
        next.set(key, fn(g));
        return next;
      });
    const onEvent = (e: ArenaEvent) => {
      switch (e.type) {
        case 'game.started':
          setLive((m) => new Map(m).set(e.game.key, e.game));
          break;
        case 'game.turn':
          patch(e.key, (g) => ({ ...g, toMove: e.side, thinking: '', turnStartedAt: e.at }));
          break;
        case 'game.thinking':
          patch(e.key, (g) => ({ ...g, thinking: (g.thinking + e.text).slice(-THINK_MAX) }));
          break;
        case 'game.move':
          patch(e.key, (g) => (g.moves.some((m) => m.ply === e.move.ply) ? g : { ...g, moves: [...g.moves, e.move], strikes: e.strikes, metrics: e.metrics }));
          break;
        case 'game.finished':
          setLive((m) => {
            const next = new Map(m);
            next.delete(e.game.key);
            return next;
          });
          reloadSoon();
          break;
        case 'match.finished':
          setMatchTick((n) => n + 1);
          reloadSoon();
          break;
        case 'tournament.status':
          reloadSoon();
          break;
        case 'tournament.progress':
          setDetail((d) => (d ? { ...d, state: { ...d.state, gamesDone: e.gamesDone, gamesTotal: e.gamesTotal, costUsd: e.costUsd } } : d));
          break;
        case 'log':
          if (e.level !== 'info') setLastLog({ level: e.level, message: e.message });
          break;
      }
    };
    const unsub = subscribeTournament(id, onEvent, (reconnected) => {
      if (reconnected) load();
    });
    return () => {
      unsub();
      window.clearTimeout(timer.current);
    };
  }, [id, load, reloadSoon]);

  return { detail, error, live: [...live.values()], lastLog, reload: load, matchTick };
}

export function entrantMap(d: TournamentDetail | null): Map<string, ArenaEntrant> {
  return new Map((d?.manifest.entrants ?? []).map((e) => [e.id, e]));
}

/** "½" formatting for match points. */
export function pts(x: number): string {
  const w = Math.floor(x);
  return x - w ? `${w || ''}½` : String(w);
}

export function formatName(f: string): string {
  return f === 'knockout' ? 'Knockout' : 'Round-robin';
}
