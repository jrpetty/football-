/**
 * Arena API client (docs/API.md → "Arena") + live event stream.
 * With `?mock=1` every call is served by ui/src/mock/arenaMock.ts.
 */
import { MOCK, request } from '../api.ts';
import type { ArenaEstimate, ArenaEvent, ArenaGameRecord, ArenaRequest, TournamentDetail, TournamentListItem } from './types.ts';

export interface GameInfo {
  id: string;
  name: string;
  version: string;
  tagline: string;
  description: string;
  sides: Array<{ name: string; color: string }>;
  rules: string;
  moveHelp: string;
  capRule: string;
  defaults: { maxPlies: number; listLegalMoves: boolean };
  estimate: { pliesPerGame: number; inputTokensPerMove: number; outputTokensPerMove: number };
}

const enc = encodeURIComponent;

export const arenaApi = {
  games: () => request<GameInfo[]>('GET', '/api/arena/games'),
  estimate: (req: ArenaRequest) => request<ArenaEstimate>('POST', '/api/arena/estimate', req),
  start: (req: ArenaRequest) => request<{ tournamentId: string }>('POST', '/api/arena/tournaments', req),
  list: () => request<TournamentListItem[]>('GET', '/api/arena/tournaments'),
  get: (id: string) => request<TournamentDetail>('GET', `/api/arena/tournaments/${enc(id)}`),
  game: (id: string, key: string) => request<ArenaGameRecord>('GET', `/api/arena/tournaments/${enc(id)}/games/${enc(key)}`),
  cancel: (id: string) => request<{ ok: true }>('POST', `/api/arena/tournaments/${enc(id)}/cancel`),
  resume: (id: string, maxCostUsd?: number | null) => request<{ ok: true }>('POST', `/api/arena/tournaments/${enc(id)}/resume`, maxCostUsd === undefined ? {} : { maxCostUsd }),
  remove: (id: string) => request<{ ok: true }>('DELETE', `/api/arena/tournaments/${enc(id)}`),
};

export function exportTournamentUrl(id: string): string | null {
  return MOCK ? null : `/api/arena/tournaments/${enc(id)}/export.json`;
}

/** Subscribe to a tournament's live events. Reconnects automatically; returns an unsubscribe function. */
export function subscribeTournament(id: string, onEvent: (e: ArenaEvent) => void, onOpen?: (reconnected: boolean) => void): () => void {
  let closed = false;
  if (MOCK) {
    let unsub: (() => void) | null = null;
    void import('../mock/arenaMock.ts').then((m) => {
      if (!closed) unsub = m.subscribeArena(id, onEvent);
      onOpen?.(false);
    });
    return () => {
      closed = true;
      unsub?.();
    };
  }
  let es: EventSource | null = null;
  let opens = 0;
  let timer: number | undefined;
  let backoff = 1000;
  const connect = () => {
    if (closed) return;
    es = new EventSource(`/api/arena/tournaments/${enc(id)}/events`);
    es.onmessage = (msg: MessageEvent<string>) => {
      try {
        const e = JSON.parse(msg.data) as ArenaEvent;
        if (e && typeof e === 'object' && 'type' in e) onEvent(e);
      } catch {
        /* keep-alive comments */
      }
    };
    es.onopen = () => {
      backoff = 1000;
      onOpen?.(opens > 0);
      opens++;
    };
    es.onerror = () => {
      if (es && es.readyState === EventSource.CLOSED) {
        es.close();
        es = null;
        timer = window.setTimeout(connect, backoff);
        backoff = Math.min(backoff * 2, 15000);
      }
    };
  };
  connect();
  return () => {
    closed = true;
    window.clearTimeout(timer);
    es?.close();
  };
}
