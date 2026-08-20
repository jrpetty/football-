/**
 * Saved build library.
 *
 * URL sharing already existed, but it only ever holds ONE comparison at a time —
 * useless for keeping a customer's machine on file alongside twenty others.
 * Stored in localStorage so it survives without a backend, and exportable so it
 * is not trapped in one browser.
 */
import type { Build } from '../core/types.ts';

const KEY = 'rigcheck.library.v1';

export interface SavedBuild {
  id: string;
  name: string;
  customer?: string;
  note?: string;
  savedAt: string;
  build: Build;
}

export function loadLibrary(): SavedBuild[] {
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as SavedBuild[]) : [];
  } catch {
    return [];
  }
}

function persist(items: SavedBuild[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(items));
  } catch {
    // Quota or private-browsing failure. Silent here; the caller re-reads and
    // will show the unchanged list, which is the honest outcome.
  }
}

export function saveBuild(build: Build, meta: { name: string; customer?: string; note?: string }): SavedBuild[] {
  const items = loadLibrary();
  const entry: SavedBuild = {
    id: `${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`,
    name: meta.name,
    customer: meta.customer,
    note: meta.note,
    savedAt: new Date().toISOString(),
    build,
  };
  const next = [entry, ...items];
  persist(next);
  return next;
}

export function deleteBuild(id: string): SavedBuild[] {
  const next = loadLibrary().filter((b) => b.id !== id);
  persist(next);
  return next;
}

export function importLibrary(json: string): { imported: number; error?: string } {
  try {
    const parsed = JSON.parse(json) as SavedBuild[];
    if (!Array.isArray(parsed)) return { imported: 0, error: 'File does not contain a build list.' };
    const existing = loadLibrary();
    const ids = new Set(existing.map((b) => b.id));
    const fresh = parsed.filter((b) => b?.build?.cpuId && b?.build?.gpuId && !ids.has(b.id));
    persist([...fresh, ...existing]);
    return { imported: fresh.length };
  } catch (e) {
    return { imported: 0, error: e instanceof Error ? e.message : 'Could not parse the file.' };
  }
}
