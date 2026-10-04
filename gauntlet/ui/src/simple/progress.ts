/** Remembers the Home checklist's "seen" steps: opening a finished run's results, and opening the Presenter. */
import { useEffect } from 'react';
import { MOCK, api } from '../api.ts';
import { markSeen } from './prefs.ts';

export function useProgressTracking(parts: string[]): void {
  const [a, b, c] = parts;
  useEffect(() => {
    // Demo data (?mock=1) never ticks off the owner's real checklist.
    if (MOCK) return;
    if (a === 'present' || a === 'slides') {
      markSeen('presenter');
      return;
    }
    if (a !== 'runs' || !b || c) return;
    let alive = true;
    api
      .run(b)
      .then((r) => {
        const done = r.manifest.status !== 'running' && r.manifest.status !== 'queued' && r.results.length > 0;
        if (alive && done) markSeen('results');
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [a, b, c]);
}
