/**
 * Saved build library.
 *
 * URL sharing already existed, but it only ever holds ONE comparison at a time —
 * useless for keeping a customer's machine on file alongside twenty others.
 * Stored in localStorage so it survives without a backend, and exportable so it
 * is not trapped in one browser.
 *
 * Slots keep a REVISION HISTORY rather than a single snapshot. The way anyone
 * actually uses a saved build is to open it, change one part, and want both:
 * overwriting silently loses the version you were comparing against, and saving
 * a second entry every time leaves a library full of "gaming pc", "gaming pc 2",
 * "gaming pc final". A slot is the machine; revisions are what you tried.
 */
import type { Build } from '../core/types.ts';

const KEY = 'rigcheck.library.v1';

export interface BuildRevision {
  savedAt: string;
  build: Build;
  /** Why this revision exists. The thing you will want in three weeks. */
  note?: string;
}

export interface SavedBuild {
  id: string;
  name: string;
  customer?: string;
  note?: string;
  savedAt: string;
  build: Build;
  /**
   * Earlier versions, newest first. Absent on entries saved before slots
   * existed, which is why every reader treats it as optional rather than
   * assuming it — an old library must keep working.
   */
  revisions?: BuildRevision[];
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


/**
 * Save into an existing slot, pushing the current build onto its history.
 *
 * The revision list is capped. A slot someone iterates on for an afternoon
 * would otherwise grow without limit inside a storage mechanism with a few
 * megabytes to its name, and losing the whole library to a quota error would
 * be a far worse outcome than losing the twentieth-oldest revision.
 */
const MAX_REVISIONS = 20;

export function saveRevision(id: string, build: Build, note?: string): SavedBuild[] {
  const items = loadLibrary();
  const next = items.map((s) => {
    if (s.id !== id) return s;
    const history: BuildRevision[] = [
      { savedAt: s.savedAt, build: s.build, note: s.note },
      ...(s.revisions ?? []),
    ].slice(0, MAX_REVISIONS);
    return { ...s, build, note, savedAt: new Date().toISOString(), revisions: history };
  });
  persist(next);
  return next;
}

/** Roll a slot back to one of its revisions, keeping the current one in history. */
export function restoreRevision(id: string, savedAt: string): SavedBuild[] {
  const items = loadLibrary();
  const slot = items.find((s) => s.id === id);
  const rev = slot?.revisions?.find((r) => r.savedAt === savedAt);
  if (!slot || !rev) return items;
  return saveRevision(id, rev.build, `restored from ${savedAt.slice(0, 10)}`);
}

export function renameSlot(id: string, name: string, customer?: string): SavedBuild[] {
  const next = loadLibrary().map((s) => (s.id === id ? { ...s, name, customer } : s));
  persist(next);
  return next;
}
