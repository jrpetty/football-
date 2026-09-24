import { appendFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { join, normalize, sep } from 'node:path';
import { RUNS_DIR } from '../core/paths.ts';
import { writeJsonAtomic } from '../core/config.ts';
import type { ArtifactKind, ArtifactRef, CaseResult, CaseResultLite, RunListItem, RunManifest } from '../core/types.ts';

/**
 * Run storage. Each run is a folder:
 *   manifest.json   — immutable test/model snapshots + status
 *   results.jsonl   — append-only log of CaseResult (latest line per key wins)
 *   artifacts/      — files produced by cases (HTML, SVG, PNG, code)
 * Plain files on purpose: auditable, diffable, easy to archive or publish.
 */

export function runDir(runId: string): string {
  if (!/^[A-Za-z0-9_-]+$/.test(runId)) throw new Error('Invalid run id');
  return join(RUNS_DIR, runId);
}

export function newRunId(): string {
  const d = new Date();
  const stamp = d.toISOString().replace(/[-:]/g, '').replace(/\..*/, '').replace('T', '-');
  return `${stamp}-${Math.random().toString(36).slice(2, 6)}`;
}

export function createRunFolder(manifest: RunManifest): void {
  const dir = runDir(manifest.id);
  mkdirSync(join(dir, 'artifacts'), { recursive: true });
  writeJsonAtomic(join(dir, 'manifest.json'), manifest);
  if (!existsSync(join(dir, 'results.jsonl'))) writeFileSync(join(dir, 'results.jsonl'), '');
}

export function readManifest(runId: string): RunManifest | null {
  const file = join(runDir(runId), 'manifest.json');
  if (!existsSync(file)) return null;
  return JSON.parse(readFileSync(file, 'utf8')) as RunManifest;
}

export function writeManifest(manifest: RunManifest): void {
  writeJsonAtomic(join(runDir(manifest.id), 'manifest.json'), manifest);
}

export function appendResult(result: CaseResult): void {
  const file = join(runDir(result.runId), 'results.jsonl');
  appendFileSync(file, JSON.stringify(result) + '\n');
  cache.delete(result.runId);
}

const cache = new Map<string, { mtimeMs: number; size: number; results: CaseResult[] }>();

/** All results of a run, deduplicated by key (latest wins), in insertion order. */
export function readResults(runId: string): CaseResult[] {
  const file = join(runDir(runId), 'results.jsonl');
  if (!existsSync(file)) return [];
  const st = statSync(file);
  const hit = cache.get(runId);
  if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return hit.results;
  const byKey = new Map<string, CaseResult>();
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    try {
      const r = JSON.parse(line) as CaseResult;
      byKey.delete(r.key);
      byKey.set(r.key, r);
    } catch {
      // A torn final line after a crash is ignored; the job will be re-run on resume.
    }
  }
  const results = [...byKey.values()];
  cache.set(runId, { mtimeMs: st.mtimeMs, size: st.size, results });
  return results;
}

export function toLite(r: CaseResult): CaseResultLite {
  const { transcript: _t, replay, ...rest } = r;
  return { ...rest, hasReplay: Boolean(replay) };
}

export function listRunIds(): string[] {
  if (!existsSync(RUNS_DIR)) return [];
  return readdirSync(RUNS_DIR)
    .filter((d) => existsSync(join(RUNS_DIR, d, 'manifest.json')))
    .sort()
    .reverse();
}

export function listRuns(): RunListItem[] {
  const out: RunListItem[] = [];
  for (const id of listRunIds()) {
    const m = readManifest(id);
    if (!m) continue;
    const results = readResults(id);
    const done = results.filter((r) => r.status !== 'error' && r.status !== 'cancelled').length;
    out.push({
      id,
      name: m.name,
      status: m.status,
      createdAt: m.createdAt,
      finishedAt: m.finishedAt,
      suiteId: m.suiteId,
      fingerprint: m.fingerprint,
      contestants: m.contestants.map((c) => ({ id: c.id, label: c.label, color: c.color })),
      testCount: m.tests.length,
      totalJobs: m.totalJobs,
      completedJobs: done,
      costUsd: round6(results.reduce((s, r) => s + r.metrics.costUsd + r.metrics.judgeCostUsd, 0)),
    });
  }
  return out;
}

export function deleteRun(runId: string): void {
  rmSync(runDir(runId), { recursive: true, force: true });
  cache.delete(runId);
}

export function safeKey(key: string): string {
  return key.replace(/::/g, '__').replace(/[^A-Za-z0-9._-]/g, '_');
}

export function saveArtifact(runId: string, key: string, name: string, kind: ArtifactKind, content: string | Buffer): ArtifactRef {
  const folder = safeKey(key);
  const safeName = name.replace(/[^A-Za-z0-9._-]/g, '_');
  const dir = join(runDir(runId), 'artifacts', folder);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, safeName), content);
  return { name: safeName, kind, file: `${folder}/${safeName}`, bytes: typeof content === 'string' ? Buffer.byteLength(content) : content.length };
}

/** Resolve an artifact path, refusing anything outside the run's artifact folder. */
export function artifactPath(runId: string, file: string): string | null {
  const base = join(runDir(runId), 'artifacts');
  const full = normalize(join(base, file));
  if (!full.startsWith(base + sep)) return null;
  return existsSync(full) ? full : null;
}

function round6(n: number): number {
  return Math.round(n * 1e6) / 1e6;
}
