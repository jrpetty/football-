/**
 * Writes a synthetic finished run straight into the run store (no model calls),
 * so leaderboards, the public site and the history page can be tested — and
 * demoed — with realistic-looking data. Set GAUNTLET_DATA_DIR first.
 */
import { contestantConfigHash, loadContestants, snapshotContestant } from '../../src/core/config.ts';
import { fingerprint, resolveTests, selectedCaseIds } from '../../src/core/registry.ts';
import type { CaseResult, RunManifest } from '../../src/core/types.ts';
import { HARNESS_VERSION, PROTOCOL_VERSION } from '../../src/core/version.ts';
import { appendResult, createRunFolder } from '../../src/engine/store.ts';

/** Deterministic 0..1 noise from a string. */
function noise(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return ((h >>> 0) % 10000) / 10000;
}

const DIFF: Record<string, number> = { easy: 0.15, medium: 0.3, hard: 0.45, extreme: 0.6 };

/**
 * `skill` (0..1) per contestant id sets how often it passes a case. Returns the run id.
 */
export function writeSyntheticRun(opts: { runId: string; suiteId: string; skill: Record<string, number>; repeats?: number; name?: string; testIds?: string[] }): string {
  const tests = resolveTests({ suiteId: opts.suiteId, testIds: opts.testIds });
  const all = loadContestants();
  const contestants = Object.keys(opts.skill).map((id) => {
    const c = all.find((x) => x.id === id);
    if (!c) throw new Error(`unknown contestant ${id}`);
    return c;
  });
  const repeats = opts.repeats ?? 1;
  const at = '2026-09-20T12:00:00.000Z';
  const manifest: RunManifest = {
    id: opts.runId,
    name: opts.name ?? `Synthetic ${opts.suiteId}`,
    status: 'completed',
    createdAt: at,
    startedAt: at,
    finishedAt: at,
    harnessVersion: HARNESS_VERSION,
    node: process.version,
    platform: 'test',
    suiteId: opts.testIds ? undefined : opts.suiteId,
    fingerprint: fingerprint(tests),
    tests: tests.map((t) => ({ id: t.definition.id, version: t.definition.version, hash: t.hash, name: t.definition.name, category: t.definition.category, kind: t.definition.kind, caseIds: selectedCaseIds(t), weight: t.weight })),
    contestants: contestants.map(snapshotContestant),
    judges: [],
    settings: { repeats, concurrency: 1, temperature: 0, protocolVersion: PROTOCOL_VERSION },
    totalJobs: 0,
  };
  createRunFolder(manifest);
  for (const c of contestants) {
    const skill = opts.skill[c.id]!;
    for (const t of tests) {
      for (const caseId of selectedCaseIds(t)) {
        for (let r = 0; r < repeats; r++) {
          const key = `${c.id}::${t.definition.id}::${caseId}::r${r}`;
          const p = Math.max(0, Math.min(1, skill - (DIFF[t.definition.difficulty] ?? 0.3) + 0.25 + (noise(`${t.definition.id}${c.id}`) - 0.5) * 0.35));
          const partial = t.definition.kind === 'program' || (t.definition.kind === 'prompt' && ['constraints', 'json', 'code-js', 'judge', 'contains'].includes(t.definition.scorer.type));
          const score = partial ? Math.round(Math.max(0, Math.min(1, p + (noise(key) - 0.5) * 0.3)) * 100) / 100 : noise(key) < p ? 1 : 0;
          const input = 1500 + Math.round(noise(key + 'i') * 3000);
          const output = 800 + Math.round(skill * 4000 * noise(key + 'o'));
          const cost = (input * c.pricing.inputPerM + output * c.pricing.outputPerM) / 1e6;
          const result: CaseResult = {
            key,
            runId: opts.runId,
            contestantId: c.id,
            testId: t.definition.id,
            testVersion: t.definition.version,
            testHash: t.hash,
            contestantHash: contestantConfigHash(c),
            caseId,
            repeat: r,
            status: 'ok',
            score,
            passed: score >= 0.999,
            summary: score >= 0.999 ? 'Correct: SECRET-ANSWER-KEY' : 'Answered x · expected SECRET-ANSWER-KEY',
            scoreDetail: { expected: 'SECRET-ANSWER-KEY' },
            metrics: {
              wallMs: 4000 + Math.round(noise(key + 'w') * 40000),
              ttftMs: 600,
              apiCalls: 1,
              inputTokens: input,
              outputTokens: output,
              reasoningTokens: 0,
              cachedInputTokens: 0,
              costUsd: c.provider === 'baseline' ? 0 : cost,
              judgeCostUsd: 0,
              outputTokensPerSec: 60 + Math.round(noise(c.id) * 120),
              retries: 0,
              responseChars: output * 4,
            },
            transcript: [],
            artifacts: [],
            startedAt: at,
            finishedAt: at,
          };
          appendResult(result);
          manifest.totalJobs++;
        }
      }
    }
  }
  createRunFolder(manifest);
  return opts.runId;
}
