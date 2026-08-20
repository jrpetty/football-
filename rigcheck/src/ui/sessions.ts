/**
 * Health-session storage.
 *
 * Same pattern as the build library: localStorage, because there is no backend
 * and a machine's history has to survive a closed tab to be worth anything.
 * Export exists so a history is not trapped in one browser — losing six months
 * of measurements to a cleared cache would make the longitudinal feature a
 * liability rather than a feature.
 */
import type { HealthSession } from '../core/history.ts';

const KEY = 'rigcheck.health.sessions.v1';

export function loadSessions(): HealthSession[] {
  try {
    const raw = localStorage.getItem(KEY);
    const all = raw ? (JSON.parse(raw) as HealthSession[]) : [];
    return all.sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
  } catch {
    return [];
  }
}

export function saveSessions(list: HealthSession[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    // Storage full or blocked. Losing the write is survivable; throwing here
    // would take the whole report down with it, which is not.
  }
}

export function addSession(s: HealthSession): HealthSession[] {
  const next = [s, ...loadSessions()];
  saveSessions(next);
  return next;
}

export function removeSession(id: string): HealthSession[] {
  const next = loadSessions().filter((s) => s.id !== id);
  saveSessions(next);
  return next;
}

/** Sessions for one machine, oldest first — the order a history reads in. */
export function historyFor(sessions: HealthSession[], machineId: string): HealthSession[] {
  return sessions.filter((s) => s.machineId === machineId).sort((a, b) => Date.parse(a.at) - Date.parse(b.at));
}

/** Distinct machines on record, with their session counts. */
export function machines(sessions: HealthSession[]): { machineId: string; label: string; count: number; lastAt: string }[] {
  const byId = new Map<string, { label: string; count: number; lastAt: string }>();
  for (const s of sessions) {
    const e = byId.get(s.machineId) ?? { label: s.machineLabel, count: 0, lastAt: s.at };
    e.count++;
    if (Date.parse(s.at) > Date.parse(e.lastAt)) {
      e.lastAt = s.at;
      e.label = s.machineLabel;
    }
    byId.set(s.machineId, e);
  }
  return [...byId.entries()]
    .map(([machineId, v]) => ({ machineId, ...v }))
    .sort((a, b) => Date.parse(b.lastAt) - Date.parse(a.lastAt));
}
