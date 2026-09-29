/**
 * 30-word performance summaries for a run, shared by the Run detail page, the
 * result inspector, the Grading Station and the Presenter. Fetched once per
 * run (cached), refreshed after grading or writing AI summaries.
 */
import { useEffect, useState } from 'react';
import { gradingApi, type RunSummaries } from './gradingApi.ts';

const cache = new Map<string, Promise<RunSummaries | null>>();
const listeners = new Set<(runId: string) => void>();

function load(runId: string): Promise<RunSummaries | null> {
  let p = cache.get(runId);
  if (!p) {
    p = gradingApi.summaries(runId).catch(() => null);
    cache.set(runId, p);
  }
  return p;
}

/** Drop the cached summaries of a run (after a grade or new AI summaries) and tell every mounted hook. */
export function invalidateSummaries(runId: string): void {
  cache.delete(runId);
  for (const l of listeners) l(runId);
}

export function useRunSummaries(runId: string | null | undefined): RunSummaries | null {
  const [s, setS] = useState<RunSummaries | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const l = (id: string) => id === runId && setTick((t) => t + 1);
    listeners.add(l);
    return () => void listeners.delete(l);
  }, [runId]);
  useEffect(() => {
    if (!runId) return;
    let alive = true;
    void load(runId).then((x) => alive && setS(x));
    return () => {
      alive = false;
    };
  }, [runId, tick]);
  return s;
}

export interface ShownSummary {
  text: string;
  source: 'ai' | 'template';
  /** AI summaries: who wrote it. */
  writer?: string;
  costUsd?: number;
}

/** The summary to show for one model × test: a current AI one when it exists, else the template. */
export function summaryFor(s: RunSummaries | null, contestantId: string, testId: string, prefer: 'ai' | 'template' = 'ai'): ShownSummary | null {
  if (!s) return null;
  const k = `${contestantId}|${testId}`;
  const ai = s.ai[k];
  if (prefer === 'ai' && ai && !ai.stale) return { text: ai.text, source: 'ai', writer: ai.writerLabel, costUsd: ai.costUsd };
  const t = s.template[k];
  return t ? { text: t, source: 'template' } : null;
}
