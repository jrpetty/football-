/**
 * Attach copy & paste results to a named model.
 *
 * Two ways a result made by the generic "Manual entry (unspecified model)" contestant gets a real model name:
 *   paste     the owner picked "Which model are you using?" on the Manual Inbox card before pasting the reply
 *   reassign  the owner later moves old results to a model with the explicit "Reassign to a model" action
 *
 * Both do the same honest thing: the run's results log is append-only, so a copy of the result is appended under
 * the chosen model's key (with `manualOrigin` saying where it came from and how), and the original line is appended
 * again with `reassignedTo`, which removes it from every score (aggregate.ts) while keeping it on record, so
 * resuming the run never asks for that reply again. Every move is written to the reassign log in the user folder.
 */
import { existsSync, readFileSync } from 'node:fs';
import { contestantConfigHash, loadContestants, loadProviders } from '../core/config.ts';
import type { CaseResult, Contestant, ManualModelInfo } from '../core/types.ts';
import { appendResult, listRunIds, readManifest, readResults, writeManifest } from '../engine/store.ts';
import { buildManualContestant, manualContestantId, normalizeInfo, type ReassignOutcome, type UnspecifiedGroup } from './identity.ts';

export type { ReassignOutcome, UnspecifiedGroup };
import { PENDING_CHOICES_FILE, REASSIGN_LOG, appendLog, loadCatalog, readJson, saveUserManualContestant, writeJson } from './store.ts';

export class ManualModelError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

/** Is this (snapshot of a) contestant a copy & paste one? */
export function isManualContestant(c: Pick<Contestant, 'provider'>, providers = loadProviders()): boolean {
  return providers.find((p) => p.id === c.provider)?.type === 'manual';
}

/** Copy & paste results that don't say which model made them (the generic contestant, or any manual one without a catalogue model). */
export function isUnspecifiedManual(c: Pick<Contestant, 'provider' | 'manualModel'>, providers = loadProviders()): boolean {
  return isManualContestant(c, providers) && !c.manualModel;
}

/** The contestant for a catalogue model + interface + settings: found in the model list, or made and saved in the user folder. */
export function ensureManualContestant(infoIn: Partial<ManualModelInfo> & { catalogId: string }): Contestant {
  const info = normalizeInfo(infoIn);
  const catalog = loadCatalog();
  const model = catalog.models.find((m) => m.id === info.catalogId);
  if (!model) throw new ManualModelError(404, `"${info.catalogId}" is not in the model catalogue`);
  const id = manualContestantId(info);
  const existing = loadContestants().find((c) => c.id === id);
  if (existing) {
    // Only the free-text note may change on an existing contestant (it is not part of the identity).
    if (existing.manualModel && (info.note ?? '') !== (existing.manualModel.note ?? '') && info.note !== undefined) {
      const next = { ...existing, manualModel: { ...existing.manualModel, note: info.note } };
      saveUserManualContestant(next);
      return next;
    }
    return existing;
  }
  const c = buildManualContestant(model, info, catalog.vendors.find((v) => v.id === model.vendor));
  saveUserManualContestant(c);
  return c;
}

function newKeyFor(r: CaseResult, toId: string): string {
  return `${toId}::${r.testId}::${r.caseId}::r${r.repeat}`;
}

/**
 * Move copy & paste results of one run to a catalogue contestant. Only results of unspecified manual contestants can
 * move (a result already made by a named model keeps its name), and only once.
 */
export function reassignResults(opts: { runId: string; keys: string[]; to: Contestant; how: 'paste' | 'reassign'; note?: string; at?: string }): ReassignOutcome {
  const { runId, to, how } = opts;
  if (!to.manualModel) throw new ManualModelError(400, 'Results can only be moved to a model from the catalogue');
  const manifest = readManifest(runId);
  if (!manifest) throw new ManualModelError(404, 'Run not found');
  const at = opts.at ?? new Date().toISOString();
  const note = opts.note?.trim() ? opts.note.trim().slice(0, 300) : undefined;
  const providers = loadProviders();
  const snapshots = new Map(manifest.contestants.map((c) => [c.id, c]));
  const results = readResults(runId);
  const byKey = new Map(results.map((r) => [r.key, r]));
  const out: ReassignOutcome = { moved: 0, skipped: [], to: { id: to.id, label: to.label } };
  const toHash = contestantConfigHash(to);
  for (const key of new Set(opts.keys)) {
    const r = byKey.get(key);
    if (!r) {
      out.skipped.push({ key, reason: 'no such result in this run' });
      continue;
    }
    const from = snapshots.get(r.contestantId);
    if (!from || !isUnspecifiedManual(from, providers)) {
      out.skipped.push({ key, reason: 'only results of “Manual entry (unspecified model)” can be moved' });
      continue;
    }
    if (r.reassignedTo) {
      out.skipped.push({ key, reason: `already moved to ${r.reassignedTo.contestantId}` });
      continue;
    }
    if (r.status === 'cancelled') {
      out.skipped.push({ key, reason: 'cancelled results are never scored' });
      continue;
    }
    const newKey = newKeyFor(r, to.id);
    if (byKey.has(newKey)) {
      out.skipped.push({ key, reason: `${to.label} already has a result for this case in this run` });
      continue;
    }
    const copy: CaseResult = { ...r, key: newKey, contestantId: to.id, contestantHash: toHash, manualOrigin: { from: r.contestantId, fromKey: r.key, how, at, ...(note ? { note } : {}) } };
    delete copy.reassignedTo;
    appendResult(copy);
    appendResult({ ...r, reassignedTo: { contestantId: to.id, key: newKey, at } });
    byKey.set(newKey, copy);
    appendLog(REASSIGN_LOG, { at, how, runId, fromKey: key, fromContestant: r.contestantId, toKey: newKey, toContestant: to.id, toLabel: to.label, testId: r.testId, caseId: r.caseId, score: r.score, ...(note ? { note } : {}) });
    out.moved++;
  }
  if (out.moved > 0 && !snapshots.has(to.id)) {
    // The run now has results for this model: record its snapshot like any contestant it started with.
    manifest.contestants.push({ ...structuredClone(to), configHash: toHash });
    writeManifest(manifest);
  }
  return out;
}

/** Reassign log, newest first (for the "Reassign to a model" panel). */
export function reassignLog(limit = 50): Array<Record<string, unknown>> {
  if (!existsSync(REASSIGN_LOG)) return [];
  return readFileSync(REASSIGN_LOG, 'utf8')
    .split('\n')
    .filter((l) => l.trim())
    .flatMap((l) => {
      try {
        return [JSON.parse(l) as Record<string, unknown>];
      } catch {
        return [];
      }
    })
    .reverse()
    .slice(0, limit);
}

// ───────────────────────────── Unspecified results (for the reassign panel) ─────────────────────────────

/** Every copy & paste result that still says "unspecified model", grouped by run, contestant and test. */
export function listUnspecified(): UnspecifiedGroup[] {
  const providers = loadProviders();
  const out: UnspecifiedGroup[] = [];
  for (const runId of listRunIds()) {
    const m = readManifest(runId);
    if (!m) continue;
    const unspecified = new Map(m.contestants.filter((c) => isUnspecifiedManual(c, providers)).map((c) => [c.id, c]));
    if (!unspecified.size) continue;
    const groups = new Map<string, UnspecifiedGroup>();
    for (const r of readResults(runId)) {
      const c = unspecified.get(r.contestantId);
      if (!c || r.reassignedTo || r.status === 'cancelled') continue;
      const gk = `${r.contestantId}|${r.testId}`;
      let g = groups.get(gk);
      if (!g) {
        g = { runId, runName: m.name || runId, createdAt: m.createdAt, contestantId: c.id, contestantLabel: c.label, testId: r.testId, testName: m.tests.find((t) => t.id === r.testId)?.name ?? r.testId, keys: [], scored: 0, lastAt: r.finishedAt };
        groups.set(gk, g);
      }
      g.keys.push(r.key);
      if (r.score !== null && r.status !== 'error') g.scored++;
      if (r.finishedAt > g.lastAt) g.lastAt = r.finishedAt;
    }
    out.push(...groups.values());
  }
  return out;
}

// ───────────────────────────── Picked in the Inbox (paste) ─────────────────────────────

export interface PendingChoice {
  runId: string;
  /** The case key (CaseResult.key) of the unspecified contestant. */
  caseKey: string;
  contestantId: string;
  info: ManualModelInfo;
  at: string;
  /** Set once a reply of this case was submitted: the model can no longer change for this conversation. */
  locked?: boolean;
}

export function loadPendingChoices(): PendingChoice[] {
  const v = readJson<PendingChoice[]>(PENDING_CHOICES_FILE, []);
  return Array.isArray(v) ? v : [];
}

function savePendingChoices(list: PendingChoice[]): void {
  writeJson(PENDING_CHOICES_FILE, list);
}

/** The model chosen for a case (if any). */
export function choiceFor(runId: string, caseKey: string): PendingChoice | undefined {
  return loadPendingChoices().find((p) => p.runId === runId && p.caseKey === caseKey);
}

/**
 * Remember which model the owner is pasting into for one case of an unspecified manual contestant. The case's
 * result moves to that model as soon as it is graded (applyPendingChoices).
 */
export function setChoice(req: { runId: string; key: string; contestantId: string }, infoIn: Partial<ManualModelInfo> & { catalogId: string }): { choice: PendingChoice; contestant: Contestant } {
  const manifest = readManifest(req.runId);
  if (!manifest) throw new ManualModelError(400, 'Only prompts from a normal run can be given a model here');
  const snap = manifest.contestants.find((c) => c.id === req.contestantId);
  if (!snap || !isUnspecifiedManual(snap)) throw new ManualModelError(400, 'This prompt already belongs to a named model');
  const list = loadPendingChoices();
  const i = list.findIndex((p) => p.runId === req.runId && p.caseKey === req.key);
  const contestant = ensureManualContestant(infoIn);
  if (i >= 0 && list[i]!.locked && list[i]!.contestantId !== contestant.id) {
    throw new ManualModelError(409, `This conversation was already answered with ${loadContestants().find((c) => c.id === list[i]!.contestantId)?.label ?? list[i]!.contestantId}: keep using that model for the rest of it`);
  }
  const choice: PendingChoice = { runId: req.runId, caseKey: req.key, contestantId: contestant.id, info: contestant.manualModel!, at: new Date().toISOString(), ...(i >= 0 && list[i]!.locked ? { locked: true } : {}) };
  if (i >= 0) list[i] = choice;
  else list.push(choice);
  savePendingChoices(list);
  return { choice, contestant };
}

export function clearChoice(runId: string, caseKey: string): boolean {
  const list = loadPendingChoices();
  const i = list.findIndex((p) => p.runId === runId && p.caseKey === caseKey);
  if (i < 0) return false;
  if (list[i]!.locked) throw new ManualModelError(409, 'A reply of this conversation was already submitted with that model');
  list.splice(i, 1);
  savePendingChoices(list);
  return true;
}

/** A reply of this case was submitted: its model is now fixed for the rest of the conversation. */
export function lockChoice(runId: string, caseKey: string): void {
  const list = loadPendingChoices();
  const p = list.find((x) => x.runId === runId && x.caseKey === caseKey);
  if (!p || p.locked) return;
  p.locked = true;
  savePendingChoices(list);
}

/** Move every graded case that has a pending choice to its model. Returns how many results moved. */
export function applyPendingChoices(runId?: string): number {
  const list = loadPendingChoices();
  if (!list.length) return 0;
  const keep: PendingChoice[] = [];
  let moved = 0;
  const contestants = loadContestants();
  for (const p of list) {
    if (runId && p.runId !== runId) {
      keep.push(p);
      continue;
    }
    const manifest = readManifest(p.runId);
    if (!manifest) continue; // run deleted: drop the choice
    const r = readResults(p.runId).find((x) => x.key === p.caseKey);
    if (!r) {
      keep.push(p); // not graded yet
      continue;
    }
    const to = contestants.find((c) => c.id === p.contestantId);
    if (!to || r.reassignedTo || r.status === 'cancelled') continue;
    moved += reassignResults({ runId: p.runId, keys: [p.caseKey], to, how: 'paste', note: p.info.note, at: p.at }).moved;
  }
  if (keep.length !== list.length) savePendingChoices(keep);
  return moved;
}
