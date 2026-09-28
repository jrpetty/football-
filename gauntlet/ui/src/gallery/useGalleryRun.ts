/** Loads one run for the Gallery views: its Gallery tests, their commissions and every artist's entry. */
import { useMemo } from 'react';
import { api } from '../api.ts';
import { useAsync } from '../hooks.ts';
import { useMeta } from '../context.tsx';
import type { RunDetail, TestSnapshot } from '../types.ts';
import { briefsIn, isGalleryTest, wallFor, type Brief, type WallEntry } from '../components/viz/galleryModel.ts';

export interface GalleryRun {
  detail: RunDetail;
  tests: TestSnapshot[];
  manualProviders: Set<string>;
  briefs(testId: string): Array<{ caseId: string; brief: Brief }>;
  wall(testId: string, caseId: string, includeSkipped?: boolean): WallEntry[];
}

export function modeOf(testId: string): 'image' | 'code' {
  return testId.includes('painted-in-code') ? 'code' : 'image';
}

export function useGalleryRun(runId: string | undefined) {
  const { meta } = useMeta();
  const state = useAsync<RunDetail | null>(() => (runId ? api.run(runId) : Promise.resolve(null)), [runId]);
  const manualProviders = useMemo(() => new Set((meta?.providers ?? []).filter((p) => p.type === 'manual').map((p) => p.id)), [meta]);
  const run = useMemo<GalleryRun | null>(() => {
    const d = state.data;
    if (!d) return null;
    const tests = d.manifest.tests.filter((t) => isGalleryTest(t.id));
    const results = d.results ?? [];
    return {
      detail: d,
      tests,
      manualProviders,
      briefs: (testId) => briefsIn(results, testId, tests.find((t) => t.id === testId)?.caseIds),
      wall: (testId, caseId, includeSkipped) => wallFor({ runId: d.manifest.id, testId, caseId, results, contestants: d.manifest.contestants, manualProviders, includeSkipped }),
    };
  }, [state.data, manualProviders]);
  return { ...state, run };
}
