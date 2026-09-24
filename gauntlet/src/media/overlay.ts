/**
 * Data for the OBS overlays (scoreboard, ticker, lower third, bracket-lite).
 * Compact on purpose: the overlay page refetches it whenever a live event
 * arrives. Pure, so the mock mode can build the same payload.
 */
import { isBaselineId } from './common.ts';
import type { OverlayData, StudioResult, StudioTestInfo } from './types.ts';
import type { Leaderboard, RunManifest } from '../core/types.ts';

type LiteResult = Pick<StudioResult, 'key' | 'contestantId' | 'testId' | 'caseId' | 'status' | 'score' | 'summary' | 'finishedAt'>;

export function overlayData(manifest: RunManifest, results: LiteResult[], leaderboard: Leaderboard | null, tests: StudioTestInfo[] = [], active = false): OverlayData {
  const info = new Map(tests.map((t) => [t.id, t]));
  const name = (id: string) => info.get(id)?.name ?? manifest.tests.find((t) => t.id === id)?.name ?? id;
  const cById = new Map(manifest.contestants.map((c) => [c.id, c]));
  const repeats = manifest.settings?.repeats ?? 1;
  const perContestantTotal = manifest.tests.reduce((s, t) => s + t.caseIds.length * repeats, 0);
  const done = results.filter((r) => r.status !== 'error' && r.status !== 'cancelled');

  const rows = leaderboard?.rows ?? [];
  const final = !active && rows.length > 0 && rows.filter((r) => !isBaselineId({ id: r.contestantId, vendor: r.vendor, label: r.label })).every((r) => typeof r.index === 'number');
  const standings = manifest.contestants.map((c) => {
    const mine = done.filter((r) => r.contestantId === c.id);
    const scored = mine.filter((r) => typeof r.score === 'number').map((r) => r.score as number);
    const row = rows.find((r) => r.contestantId === c.id);
    const score = final && typeof row?.index === 'number' ? row.index : scored.length ? (scored.reduce((a, b) => a + b, 0) / scored.length) * 100 : null;
    return { id: c.id, label: c.label, color: c.color, score: score === null ? null : Math.round(score * 10) / 10, done: mine.length, total: perContestantTotal };
  });
  standings.sort((a, b) => (b.score ?? -1) - (a.score ?? -1) || a.label.localeCompare(b.label));

  const ticker = [...done]
    .sort((a, b) => (b.finishedAt ?? '').localeCompare(a.finishedAt ?? '') || a.key.localeCompare(b.key))
    .slice(0, 12)
    .map((r) => {
      const c = cById.get(r.contestantId);
      return { key: r.key, contestantId: r.contestantId, label: c?.label ?? r.contestantId, color: c?.color ?? '#64748b', testName: name(r.testId), score: r.score, status: r.status, summary: r.summary, at: r.finishedAt };
    });

  const perTest = manifest.tests.map((t) => {
    const rs = done.filter((r) => r.testId === t.id);
    const by = new Map<string, number[]>();
    for (const r of rs) if (typeof r.score === 'number') by.set(r.contestantId, [...(by.get(r.contestantId) ?? []), r.score]);
    const means = [...by].map(([id, xs]) => ({ id, score: (xs.reduce((a, b) => a + b, 0) / xs.length) * 100 })).sort((a, b) => b.score - a.score || a.id.localeCompare(b.id));
    const w = means[0];
    const c = w ? cById.get(w.id) : undefined;
    return {
      id: t.id,
      name: name(t.id),
      winner: w && c ? { id: w.id, label: c.label, color: c.color, score: Math.round(w.score) } : null,
      done: rs.length,
      total: t.caseIds.length * repeats * manifest.contestants.length,
    };
  });

  // "Now testing": the test of the most recent result that is not finished yet, else the first unfinished test.
  const latest = ticker[0] ? results.find((r) => r.key === ticker[0]!.key) : undefined;
  const unfinished = perTest.filter((t) => t.done < t.total);
  const nowTest = (latest && unfinished.find((t) => t.id === latest.testId)) ?? unfinished[0];
  const now = nowTest
    ? {
        testId: nowTest.id,
        testName: nowTest.name,
        hook: info.get(nowTest.id)?.hook,
        contestants: manifest.contestants.filter((c) => !isBaselineId(c)).map((c) => ({ id: c.id, label: c.label, color: c.color })),
      }
    : null;

  return {
    runId: manifest.id,
    runName: manifest.name || manifest.id,
    status: active ? 'running' : manifest.status,
    scoreKind: final ? 'index' : 'average',
    standings,
    ticker,
    now,
    tests: perTest,
    progress: { completed: done.length, total: manifest.totalJobs },
  };
}
