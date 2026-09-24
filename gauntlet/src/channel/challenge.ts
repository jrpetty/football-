/**
 * Viewer challenge — turn viewer-submitted questions into a private test.
 *
 * Submissions (CSV from a Google Form, JSON, or pasted text) land in a review
 * queue (data/challenge/<season>.json, git-ignored). The owner approves, edits
 * or rejects each one; approved items are written to
 * tests/private/viewer-challenge-<season>.json as a normal prompt test with the
 * viewer's credit in the case notes. Held-out: never published.
 */
import { existsSync, mkdirSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadContestants, loadCategories, writeJsonAtomic } from '../core/config.ts';
import { parseNumber } from '../core/extract.ts';
import { contentHash, canonicalJson } from '../core/hash.ts';
import { DATA_DIR, PRIVATE_TESTS_DIR, ROOT } from '../core/paths.ts';
import { bumpPatch, computeTestHash, loadTests, validateTest } from '../core/registry.ts';
import type { PromptTest, PromptTestCase, ScorerSpec } from '../core/types.ts';
import { listRunIds, readResults } from '../engine/store.ts';
import type { ChallengeAnswerType, ChallengeImportResult, ChallengeIssue, ChallengeItem, ChallengeQueue, ChallengeSlide, ChallengeWriteResult } from './types.ts';

export const CHALLENGE_DIR = join(DATA_DIR, 'challenge');
const MAX_QUESTION = 1500;
const MIN_QUESTION = 20;
const NEAR_DUP = 0.6;

export function seasonSlug(season: string): string {
  const s = String(season ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
  if (!s) throw new Error('Season must contain letters or digits (e.g. 2026-s1)');
  return s;
}

export function challengeTestId(season: string, category = 'reasoning'): string {
  return `${category}.viewer-challenge-${seasonSlug(season)}`;
}

export function challengeTestFile(season: string): string {
  return join(PRIVATE_TESTS_DIR, `viewer-challenge-${seasonSlug(season)}.json`);
}

function queueFile(season: string): string {
  return join(CHALLENGE_DIR, `${seasonSlug(season)}.json`);
}

// ───────────────────────────── Parsing ─────────────────────────────

/** RFC 4180 CSV parser (quoted fields, embedded commas/newlines, "" escapes, CRLF, BOM). */
export function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  const s = text.replace(/^﻿/, '');
  // Semicolon-separated exports (some European Excel locales) when the header has no commas.
  const firstLine = s.split(/\r?\n/, 1)[0] ?? '';
  const sep = !firstLine.includes(',') && firstLine.includes(';') ? ';' : firstLine.includes('\t') && !firstLine.includes(',') ? '\t' : ',';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]!;
    if (quoted) {
      if (ch === '"') {
        if (s[i + 1] === '"') {
          field += '"';
          i++;
        } else quoted = false;
      } else field += ch;
    } else if (ch === '"' && field === '') quoted = true;
    else if (ch === sep) {
      row.push(field);
      field = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && s[i + 1] === '\n') i++;
      row.push(field);
      field = '';
      if (row.some((c) => c.trim() !== '')) rows.push(row);
      row = [];
    } else field += ch;
  }
  row.push(field);
  if (row.some((c) => c.trim() !== '')) rows.push(row);
  return rows;
}

type Field = 'question' | 'answer' | 'answerType' | 'name' | 'handle' | 'notes' | 'timestamp' | 'credit' | 'alternatives';

/** Map a (Google Forms style) header to a field. Order matters: "Answer type" before "answer". */
export function mapHeader(h: string): Field | null {
  const t = h.toLowerCase().replace(/[_\-]+/g, ' ').trim();
  if (/\btype\b|\bformat\b/.test(t) && /answer|type/.test(t)) return 'answerType';
  if (/alternative|also accept|other accepted/.test(t)) return 'alternatives';
  if (/credit|anonymous|consent|show my name/.test(t)) return 'credit';
  if (/timestamp|submitted|^date$|^time$/.test(t)) return 'timestamp';
  if (/handle|username|user name|youtube|@|channel/.test(t)) return 'handle';
  if (/\bnote|explain|explanation|source|how do you know|working|why/.test(t)) return 'notes';
  if (/answer|solution|expected/.test(t)) return 'answer';
  if (/question|prompt|puzzle|challenge/.test(t)) return 'question';
  if (/\bname\b/.test(t)) return 'name';
  return null;
}

export function normalizeAnswerType(raw: string | undefined, answer: string, question: string): { type: ChallengeAnswerType; known: boolean } {
  const t = (raw ?? '').toLowerCase().trim();
  if (/^(exact|text|word|words|short|short answer|string|name)/.test(t)) return { type: 'exact', known: true };
  if (/^(number|numeric|integer|int|decimal|digit)/.test(t)) return { type: 'number', known: true };
  if (/^(choice|multiple|mcq|letter|abcd|option)/.test(t)) return { type: 'choice', known: true };
  // Guess from the answer when the column is missing or blank.
  const known = t === '';
  if (/^[A-H]$/i.test(answer.trim()) && hasOptions(question)) return { type: 'choice', known };
  if (answer.trim() && parseNumber(answer) !== null && /^[\s$€£\-−+\d.,/%]+$/.test(answer.trim())) return { type: 'number', known };
  return { type: 'exact', known };
}

function hasOptions(q: string): boolean {
  return /(^|\n|\s)\(?[A-D][).:]\s/.test(q) && /(^|\n|\s)\(?B[).:]\s/.test(q);
}

interface RawSubmission {
  question?: string;
  answer?: string;
  answerType?: string;
  name?: string;
  handle?: string;
  notes?: string;
  timestamp?: string;
  credit?: string;
  alternatives?: string;
}

export function parseSubmissions(text: string, format: 'csv' | 'json' | 'auto' = 'auto'): { rows: RawSubmission[]; errors: string[] } {
  const trimmed = text.trim();
  const errors: string[] = [];
  if (!trimmed) return { rows: [], errors: ['Nothing to import: the text is empty.'] };
  const isJson = format === 'json' || (format === 'auto' && /^[[{]/.test(trimmed));
  if (isJson) {
    let data: unknown;
    try {
      data = JSON.parse(trimmed);
    } catch (e) {
      return { rows: [], errors: [`Not valid JSON: ${(e as Error).message}`] };
    }
    const list = Array.isArray(data) ? data : Array.isArray((data as { items?: unknown }).items) ? (data as { items: unknown[] }).items : Array.isArray((data as { submissions?: unknown }).submissions) ? (data as { submissions: unknown[] }).submissions : null;
    if (!list) return { rows: [], errors: ['JSON must be a list of submissions (or { "items": [...] }).'] };
    const rows: RawSubmission[] = [];
    list.forEach((o, i) => {
      if (!o || typeof o !== 'object') {
        errors.push(`Item ${i + 1} is not an object; skipped.`);
        return;
      }
      const r: RawSubmission = {};
      for (const [k, v] of Object.entries(o as Record<string, unknown>)) {
        const f = k === 'answer_type' || k === 'answerType' ? 'answerType' : mapHeader(k);
        if (f && v !== null && v !== undefined) r[f] = Array.isArray(v) ? v.map(String).join(' | ') : String(v);
      }
      rows.push(r);
    });
    return { rows, errors };
  }
  const table = parseCsv(trimmed);
  if (table.length < 2) return { rows: [], errors: ['The CSV needs a header row and at least one submission.'] };
  const header = table[0]!.map((h) => mapHeader(h));
  if (!header.includes('question')) return { rows: [], errors: [`No question column found. Headers: ${table[0]!.join(', ')}. Name one column "question".`] };
  if (!header.includes('answer')) errors.push('No answer column found: every item will need its answer key filled in during review.');
  const rows = table.slice(1).map((cells) => {
    const r: RawSubmission = {};
    header.forEach((f, i) => {
      if (f && cells[i] !== undefined && r[f] === undefined) r[f] = cells[i];
    });
    return r;
  });
  return { rows, errors };
}

// ───────────────────────────── Checks ─────────────────────────────

function words(s: string): string[] {
  return s.toLowerCase().replace(/[^a-z0-9\s]+/g, ' ').split(/\s+/).filter(Boolean);
}

function jaccard(A: Set<string>, B: Set<string>): number {
  let inter = 0;
  for (const g of A) if (B.has(g)) inter++;
  const union = A.size + B.size - inter;
  return union ? inter / union : 0;
}

/** Mean of word-set and word-bigram Jaccard similarity, 0..1 (robust to one inserted word). */
export function similarity(a: string, b: string): number {
  const wa = words(a);
  const wb = words(b);
  if (!wa.length || !wb.length) return 0;
  const uni = jaccard(new Set(wa), new Set(wb));
  if (Math.min(wa.length, wb.length) < 3) return uni;
  const bi = (w: string[]) => new Set(w.slice(1).map((x, i) => `${w[i]} ${x}`));
  return (uni + jaccard(bi(wa), bi(wb))) / 2;
}

export interface ExistingPrompt {
  text: string;
  where: string;
}

/** Every existing test prompt (for near-duplicate checks), except the challenge test itself. */
export function existingPrompts(exceptTestId?: string): ExistingPrompt[] {
  const out: ExistingPrompt[] = [];
  for (const t of loadTests()) {
    const d = t.definition;
    if (d.kind !== 'prompt' || d.id === exceptTestId) continue;
    for (const c of d.cases) {
      const text = c.prompt ?? c.turns?.join('\n') ?? '';
      if (text.length < 4000) out.push({ text, where: `${d.id} / ${c.id}` });
    }
  }
  return out;
}

const norm = (s: string) => words(s).join(' ');

export function checkItem(item: Pick<ChallengeItem, 'id' | 'question' | 'answer' | 'answerType' | 'status'>, others: Array<Pick<ChallengeItem, 'id' | 'question' | 'status'>>, existing: ExistingPrompt[] = []): ChallengeIssue[] {
  const issues: ChallengeIssue[] = [];
  const q = item.question.trim();
  const a = item.answer.trim();
  if (!q) issues.push({ level: 'error', code: 'missing-question', message: 'The question is empty.' });
  if (!a) issues.push({ level: 'error', code: 'missing-answer', message: 'No answer key: add the correct answer before approving.' });
  if (q.length > MAX_QUESTION) issues.push({ level: 'warn', code: 'too-long', message: `Long question (${q.length} characters; aim for under ${MAX_QUESTION}).` });
  if (q && q.length < MIN_QUESTION) issues.push({ level: 'warn', code: 'too-short', message: 'Very short question: is it clear enough to have one right answer?' });
  if (a && q && norm(q).length > 12 && norm(a).includes(norm(q))) issues.push({ level: 'error', code: 'question-in-answer', message: 'The answer contains the whole question (pasted into the wrong box?).' });
  else if (a && q && item.answerType !== 'choice') {
    const na = norm(a);
    if (na.length >= 3 && new RegExp(`(^| )${na.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}( |$)`).test(norm(q))) issues.push({ level: 'warn', code: 'answer-in-question', message: 'The answer appears word-for-word in the question: it may give itself away.' });
  }
  if (item.answerType === 'number' && a && parseNumber(a) === null) issues.push({ level: 'error', code: 'bad-number', message: `"${a}" is not a number.` });
  if (item.answerType === 'choice') {
    if (a && !/^[A-H]$/i.test(a)) issues.push({ level: 'error', code: 'bad-choice', message: 'A multiple-choice answer must be a single letter (A–H).' });
    if (q && !hasOptions(q)) issues.push({ level: 'warn', code: 'no-options', message: 'Multiple choice, but the question does not list options like "A) … B) …".' });
  }
  if (item.answerType === 'exact' && a.length > 80) issues.push({ level: 'warn', code: 'too-long', message: 'Exact answers should be short (a word, name or phrase), or models can never match them.' });
  if (q) {
    for (const o of others) {
      if (o.id === item.id || o.status === 'rejected') continue;
      if (norm(o.question) === norm(q)) {
        issues.push({ level: 'warn', code: 'duplicate', message: `Same question as submission ${o.id}.` });
        break;
      }
      const sim = similarity(o.question, q);
      if (sim >= NEAR_DUP) {
        issues.push({ level: 'warn', code: 'near-duplicate', message: `Very similar to submission ${o.id} (${Math.round(sim * 100)}% overlap).` });
        break;
      }
    }
    for (const e of existing) {
      const sim = norm(e.text) === norm(q) ? 1 : similarity(e.text, q);
      if (sim >= NEAR_DUP) {
        issues.push({ level: 'warn', code: sim === 1 ? 'duplicate' : 'near-duplicate', message: `${sim === 1 ? 'Identical to' : `Very similar to (${Math.round(sim * 100)}%)`} an existing test: ${e.where}.` });
        break;
      }
    }
  }
  return issues;
}

// ───────────────────────────── Queue ─────────────────────────────

export function loadQueue(season: string): ChallengeQueue {
  const file = queueFile(season);
  const base: ChallengeQueue = { season: seasonSlug(season), items: [], updatedAt: new Date().toISOString(), testFile: `tests/private/viewer-challenge-${seasonSlug(season)}.json`, testId: challengeTestId(season) };
  if (!existsSync(file)) return base;
  try {
    return { ...base, ...(JSON.parse(readFileSync(file, 'utf8')) as ChallengeQueue) };
  } catch {
    throw new Error(`The review queue ${file} is not valid JSON.`);
  }
}

function saveQueue(q: ChallengeQueue): ChallengeQueue {
  mkdirSync(CHALLENGE_DIR, { recursive: true });
  q.updatedAt = new Date().toISOString();
  writeJsonAtomic(queueFile(q.season), q);
  return q;
}

export function listSeasons(): string[] {
  if (!existsSync(CHALLENGE_DIR)) return [];
  return readdirSync(CHALLENGE_DIR).filter((f) => f.endsWith('.json')).map((f) => f.slice(0, -5)).sort();
}

function recheck(q: ChallengeQueue, existing = existingPrompts(q.testId)): void {
  for (const it of q.items) it.issues = checkItem(it, q.items, existing);
}

const cleanHandle = (h: string) => {
  const t = h.trim().replace(/^https?:\/\/(www\.)?youtube\.com\//i, '');
  return t && !t.startsWith('@') ? `@${t}` : t;
};

export function importSubmissions(season: string, text: string, format: 'csv' | 'json' | 'auto' = 'auto'): ChallengeImportResult {
  const q = loadQueue(season);
  const { rows, errors } = parseSubmissions(text, format);
  let added = 0;
  let skipped = 0;
  const known = new Set(q.items.map((i) => i.id));
  rows.forEach((r, n) => {
    const question = (r.question ?? '').trim();
    const answer = (r.answer ?? '').trim();
    if (!question && !answer) {
      skipped++;
      return;
    }
    const id = contentHash({ q: norm(question), h: (r.handle ?? r.name ?? '').trim().toLowerCase() }).slice(0, 8);
    if (known.has(id)) {
      skipped++;
      return;
    }
    known.add(id);
    const at = normalizeAnswerType(r.answerType, answer, question);
    if (!at.known) errors.push(`Row ${n + 2}: answer type "${r.answerType}" not recognised; guessed "${at.type}".`);
    const credit = r.credit === undefined ? true : !/^(no|n|false|0|anonymous|don'?t)/i.test(r.credit.trim());
    const ts = r.timestamp ? new Date(r.timestamp) : null;
    q.items.push({
      id,
      question,
      answer: at.type === 'choice' ? answer.toUpperCase() : answer,
      answerType: at.type,
      alternatives: r.alternatives ? r.alternatives.split(/\s*[|;]\s*/).filter(Boolean) : undefined,
      viewerName: (r.name ?? '').trim(),
      viewerHandle: cleanHandle(r.handle ?? ''),
      notes: (r.notes ?? '').trim(),
      submittedAt: ts && !Number.isNaN(ts.getTime()) ? ts.toISOString() : new Date().toISOString(),
      status: 'pending',
      credit,
      issues: [],
    });
    added++;
  });
  recheck(q);
  saveQueue(q);
  return { added, skipped, errors, queue: q };
}

export type ChallengePatch = Partial<Pick<ChallengeItem, 'question' | 'answer' | 'answerType' | 'alternatives' | 'viewerName' | 'viewerHandle' | 'notes' | 'status' | 'credit'>>;

export function updateItem(season: string, itemId: string, patch: ChallengePatch): ChallengeQueue {
  const q = loadQueue(season);
  const it = q.items.find((x) => x.id === itemId);
  if (!it) throw new Error(`No submission "${itemId}" in season ${q.season}`);
  if (patch.answerType && !['exact', 'number', 'choice'].includes(patch.answerType)) throw new Error('answerType must be exact, number or choice');
  if (patch.status && !['pending', 'approved', 'rejected'].includes(patch.status)) throw new Error('status must be pending, approved or rejected');
  const next: ChallengeItem = { ...it, ...patch };
  if (next.answerType === 'choice') next.answer = next.answer.trim().toUpperCase();
  if (patch.viewerHandle !== undefined) next.viewerHandle = cleanHandle(patch.viewerHandle);
  const issues = checkItem(next, q.items, existingPrompts(q.testId));
  if (next.status === 'approved' && issues.some((i) => i.level === 'error')) throw new Error(`Fix these before approving: ${issues.filter((i) => i.level === 'error').map((i) => i.message).join(' ')}`);
  if (patch.status && patch.status !== it.status) next.reviewedAt = new Date().toISOString();
  Object.assign(it, next);
  recheck(q);
  return saveQueue(q);
}

export function deleteItem(season: string, itemId: string): ChallengeQueue {
  const q = loadQueue(season);
  const before = q.items.length;
  q.items = q.items.filter((x) => x.id !== itemId);
  if (q.items.length === before) throw new Error(`No submission "${itemId}"`);
  recheck(q);
  return saveQueue(q);
}

// ───────────────────────────── Writing the private test ─────────────────────────────

function creditLine(it: ChallengeItem): string {
  const who = [it.viewerHandle, it.viewerName && it.viewerName !== it.viewerHandle ? `(${it.viewerName})` : ''].filter(Boolean).join(' ') || 'anonymous viewer';
  return `Viewer challenge: submitted by ${who} on ${it.submittedAt.slice(0, 10)}. Credit on screen: ${it.credit ? 'yes' : 'no (keep anonymous)'}.`;
}

/** Build the prompt test for the approved items (pure; also used by tests). */
export function buildChallengeTest(q: ChallengeQueue, opts: { category?: string; previous?: PromptTest | null } = {}): PromptTest {
  const category = opts.category ?? (opts.previous?.category || 'reasoning');
  const approved = q.items.filter((i) => i.status === 'approved');
  let next = 1;
  for (const it of q.items) {
    const m = it.caseId?.match(/^v(\d+)$/);
    if (m) next = Math.max(next, Number(m[1]) + 1);
  }
  const cases: PromptTestCase[] = approved.map((it) => {
    if (!it.caseId) it.caseId = `v${String(next++).padStart(2, '0')}`;
    const scorer: ScorerSpec | undefined = it.answerType === 'number' ? { type: 'number' } : it.answerType === 'choice' ? { type: 'choice' } : undefined;
    const expected = it.answerType === 'number' ? parseNumber(it.answer) : it.answerType === 'choice' ? it.answer.trim().toUpperCase() : [it.answer.trim(), ...(it.alternatives ?? [])].filter(Boolean);
    return {
      id: it.caseId,
      prompt: it.question.trim(),
      expected: Array.isArray(expected) && expected.length === 1 ? expected[0] : expected,
      ...(scorer ? { scorer } : {}),
      notes: [creditLine(it), it.notes ? `Viewer's notes: ${it.notes}` : ''].filter(Boolean).join(' '),
    };
  });
  // Stable order: existing cases keep their place, new ones are appended.
  cases.sort((x, y) => Number(x.id.slice(1)) - Number(y.id.slice(1)));
  const test: PromptTest = {
    kind: 'prompt',
    id: `${category}.viewer-challenge-${q.season}`,
    version: opts.previous?.version ?? '1.0.0',
    name: `Viewer Challenge (${q.season})`,
    category,
    description: `Questions submitted by viewers for season ${q.season}, each reviewed and answer-checked by the channel. Held out: never published, so no model can have trained on them.`,
    difficulty: 'hard',
    tags: ['viewer-challenge', 'held-out'],
    hook: 'You wrote the questions. Can the AIs answer them?',
    maxOutputTokens: 16000,
    estimate: { inputTokens: 300, outputTokens: 3000 },
    scorer: { type: 'exact', normalize: 'lower' },
    cases,
    author: 'Viewer challenge',
    publishPrompts: false,
  };
  if (opts.previous && canonicalJson({ ...opts.previous, version: '' }) !== canonicalJson({ ...test, version: '' })) test.version = bumpPatch(opts.previous.version);
  return test;
}

export function writeChallengeTest(season: string, opts: { category?: string } = {}): ChallengeWriteResult {
  const q = loadQueue(season);
  const file = challengeTestFile(season);
  if (opts.category && !loadCategories().some((c) => c.id === opts.category)) throw new Error(`Unknown category "${opts.category}"`);
  let previous: PromptTest | null = null;
  if (existsSync(file)) {
    try {
      previous = JSON.parse(readFileSync(file, 'utf8')) as PromptTest;
    } catch {
      previous = null;
    }
  }
  const test = buildChallengeTest(q, { category: opts.category, previous });
  const rel = `tests/private/viewer-challenge-${q.season}.json`;
  if (!test.cases.length) return { file: rel, testId: test.id, version: test.version, cases: 0, hash: '', errors: ['Approve at least one submission first.'] };
  const all = loadTests().filter((t) => t.file.replace(/\\/g, '/') !== rel && t.definition.id !== previous?.id);
  const errors = validateTest(test, all);
  if (errors.length) return { file: rel, testId: test.id, version: test.version, cases: test.cases.length, hash: '', errors };
  mkdirSync(PRIVATE_TESTS_DIR, { recursive: true });
  writeJsonAtomic(file, test);
  q.testId = test.id;
  q.testFile = rel;
  q.writtenAt = new Date().toISOString();
  q.writtenVersion = test.version;
  saveQueue(q);
  return { file: file.startsWith(ROOT) ? rel : file, testId: test.id, version: test.version, cases: test.cases.length, hash: computeTestHash(test), errors: [] };
}

/** Approved items with the latest graded outcome per model (for the Presenter slide). */
export function challengeSlides(season: string): ChallengeSlide[] {
  const q = loadQueue(season);
  const contestants = new Map(loadContestants().map((c) => [c.id, c]));
  const latest = new Map<string, { at: string; score: number | null; passed: boolean | null; contestantId: string }>();
  const cases = new Set(q.items.filter((i) => i.caseId).map((i) => i.caseId!));
  if (cases.size) {
    for (const runId of listRunIds()) {
      for (const r of readResults(runId)) {
        if (r.testId !== q.testId || !cases.has(r.caseId) || r.status === 'error' || r.status === 'cancelled') continue;
        const k = `${r.caseId}::${r.contestantId}`;
        const cur = latest.get(k);
        if (!cur || r.finishedAt > cur.at) latest.set(k, { at: r.finishedAt, score: r.score, passed: r.passed, contestantId: r.contestantId });
      }
    }
  }
  return q.items
    .filter((i) => i.status === 'approved')
    .map((i) => ({
      itemId: i.id,
      caseId: i.caseId ?? null,
      question: i.question,
      answer: i.answer,
      credit: i.credit ? i.viewerHandle || i.viewerName || null : null,
      outcomes: [...latest.entries()]
        .filter(([k]) => k.startsWith(`${i.caseId}::`))
        .map(([, v]) => {
          const c = contestants.get(v.contestantId);
          return { contestantId: v.contestantId, label: c?.label ?? v.contestantId, color: c?.color ?? '#888888', score: v.score, passed: v.passed };
        })
        .sort((a, b) => (b.score ?? -1) - (a.score ?? -1) || a.label.localeCompare(b.label)),
    }));
}
