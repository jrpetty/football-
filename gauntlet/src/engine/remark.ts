/**
 * Re-marking: when the marking rules change (PROTOCOL_VERSION), every stored answer is marked again from its
 * stored reply under the current rules, so a leaderboard never mixes answers marked by old and new rules.
 *
 * What is re-marked: "ok" answers to prompt tests whose case still exists and asks the same question (the rendered
 * system prompt and user turns equal what the transcript shows was sent, ignoring an optional leading
 * "[Fair-play rules]" notice; the test hash need not match):
 *  - exact / number / choice / regex / contains / constraints / json / ladder: from the stored reply text;
 *  - code-js: the code is taken from the stored reply again and its hidden unit tests run in the sandbox;
 *  - judge / judge-classify: the stored judge replies are parsed again (a replay panel; no judge is called).
 * Never re-marked: simulations (their rule changes bumped test versions), artifact tests (they need a browser
 * replay), human-scored tests, refusals, time-outs, errors and answers to questions that have since changed. Human grades are never changed:
 * a result whose official score came from a person keeps it, and only the machine score underneath is updated.
 *
 * No model is called and nothing is spent. Each changed result is appended to the run's results.jsonl (latest
 * line wins) with its old mark kept in scoreDetail.previousScore; a run is marked done in remark.json only after
 * every result was handled, so an interrupted pass simply continues next time (already re-marked answers carry
 * remarkedUnder and are skipped).
 */
import { appendFileSync, closeSync, existsSync, openSync, readFileSync, readSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { writeJsonAtomic } from '../core/config.ts';
import { caseScorer, loadTests, renderCase, type LoadedTest } from '../core/registry.ts';
import type { CaseResult, PromptTest, PromptTestCase, ScoreDetail, ScorerSpec, TranscriptEntry } from '../core/types.ts';
import { PROTOCOL_VERSION } from '../core/version.ts';
import { scoreResponse, type JudgeCall, type JudgePanel } from '../scoring/index.ts';
import { applyOfficial } from '../grading/official.ts';
import { gradingSpecFor } from '../grading/spec.ts';
import { gradingPolicy } from '../grading/station.ts';
import { appendResult, listRunIds, readManifest, readResults, runDir, saveArtifact } from './store.ts';

const REMARKABLE = new Set<ScorerSpec['type']>(['exact', 'number', 'choice', 'regex', 'contains', 'constraints', 'json', 'ladder', 'code-js', 'judge', 'judge-classify']);

/** Detail fields that belong to the result's history or to human/station grading, not to the scorer: kept as they are. */
const CARRIED = ['timeLimitSec', 'responseMs', 'humanScore', 'official', 'arbitrated', 'automatedScore', 'autoScore', 'autoSummary', 'humanScored'];

export interface RemarkSummary {
  protocol: string;
  runs: number;
  /** Answers marked again under the current rules. */
  remarked: number;
  /** ...of which the mark changed. */
  changed: number;
  /** Answers that could not be re-marked (they keep their mark), with the reason. */
  kept: Array<{ runId: string; key: string; reason: string }>;
  /** Runs skipped because they are running right now (re-marked next time). */
  busyRuns: string[];
  changes: Array<{ runId: string; key: string; from: number | null; to: number | null }>;
}

export interface RemarkOptions {
  runIds?: string[];
  /** Runs currently executing (skipped). */
  isActive?: (runId: string) => boolean;
  /** Re-mark even runs already marked done under this protocol (results already re-marked are still skipped). */
  force?: boolean;
  protocol?: string;
}

/** "2026.10" → "October 2026". */
export function protocolLabel(protocol = PROTOCOL_VERSION): string {
  const m = /^(\d{4})\.(\d{1,2})$/.exec(protocol);
  if (!m) return protocol;
  const month = new Date(Date.UTC(2000, Number(m[2]) - 1, 1)).toLocaleString('en-GB', { month: 'long', timeZone: 'UTC' });
  return `${month} ${m[1]}`;
}

export function remarkSummaryLine(s: RemarkSummary): string {
  const base = `Re-marked ${s.remarked} answer${s.remarked === 1 ? '' : 's'} under the ${protocolLabel(s.protocol)} rules: ${s.changed} changed`;
  const kept = s.kept.length ? ` · ${s.kept.length} kept their mark (${[...new Set(s.kept.map((k) => k.reason))].slice(0, 3).join('; ')})` : '';
  const busy = s.busyRuns.length ? ` · ${s.busyRuns.length} running run${s.busyRuns.length === 1 ? '' : 's'} left for next time` : '';
  return base + kept + busy;
}

function markerFile(runId: string): string {
  return join(runDir(runId), 'remark.json');
}

export function runRemarkProtocol(runId: string): string | null {
  const f = markerFile(runId);
  if (!existsSync(f)) return null;
  try {
    return (JSON.parse(readFileSync(f, 'utf8')) as { protocol?: string }).protocol ?? null;
  } catch {
    return null;
  }
}

/** A crash in the middle of an append leaves a line without its newline; start the next line cleanly. */
function ensureTrailingNewline(runId: string): void {
  const file = join(runDir(runId), 'results.jsonl');
  if (!existsSync(file)) return;
  const size = statSync(file).size;
  if (size === 0) return;
  const fd = openSync(file, 'r');
  const b = Buffer.alloc(1);
  try {
    readSync(fd, b, 0, 1, size - 1);
  } finally {
    closeSync(fd);
  }
  if (b[0] !== 0x0a) appendFileSync(file, '\n');
}

/**
 * Remove an optional leading "[Fair-play rules]" notice block (everything from that line to the first blank line),
 * so answers given before and after the notice was added compare equally.
 */
export function stripFairPlay(text: string | undefined): string {
  const t = (text ?? '').replace(/\r\n/g, '\n');
  const m = /^\s*\[Fair-play rules\][^\n]*(?:\n(?![ \t]*\n)[^\n]*)*(?:\n[ \t]*\n|$)/.exec(t);
  return (m ? t.slice(m[0].length) : t).trim();
}

/**
 * Whether the stored reply answered the same question the test asks today: the case's system prompt and user
 * turns, as rendered now, equal what the transcript shows was sent (ignoring a leading fair-play notice).
 */
export function sameQuestion(def: PromptTest, tc: PromptTestCase, sent: TranscriptEntry): boolean {
  const now = renderCase(def, tc);
  if (stripFairPlay(now.system) !== stripFairPlay(sent.system)) return false;
  const userTurns = (sent.messages ?? []).filter((m) => m.role === 'user').map((m) => stripFairPlay(m.content));
  const want = now.turns.map((t) => stripFairPlay(t));
  return userTurns.length === want.length && userTurns.every((u, i) => u === want[i]);
}

/** A judge panel that answers from the judge replies stored with the result, in the order they were recorded. */
function replayPanel(entries: TranscriptEntry[], judgeIdByLabel: Map<string, string>): JudgePanel {
  const used = new Set<TranscriptEntry>();
  const ids = [...new Set(entries.map((e) => judgeIdOf(e, judgeIdByLabel)))];
  return {
    ids,
    async ask(_system: string, _user: string, label: string): Promise<JudgeCall[]> {
      const mine = entries.filter((e) => !used.has(e) && (e.label === label || (e.label ?? '').startsWith(`${label} · `)));
      if (!mine.length) throw new NoStoredJudgesError(`no stored "${label}" judge replies`);
      // One ask = one reply per judge; take the first unused reply of each judge.
      const seen = new Set<string>();
      const calls: JudgeCall[] = [];
      for (const e of mine) {
        const id = judgeIdOf(e, judgeIdByLabel);
        if (seen.has(id)) continue;
        seen.add(id);
        used.add(e);
        calls.push(e.error ? { judgeId: id, text: '', error: e.error } : { judgeId: id, text: e.response });
      }
      return calls;
    },
  };
}

class NoStoredJudgesError extends Error {}

function judgeIdOf(e: TranscriptEntry, byLabel: Map<string, string>): string {
  const label = (e.label ?? '').split(' · ').slice(1).join(' · ');
  return byLabel.get(label) ?? label ?? 'judge';
}

function machineScore(r: CaseResult): number | null {
  const d = r.scoreDetail ?? {};
  if (d.official && 'autoScore' in d) return (d.autoScore as number | null) ?? null;
  if (d.arbitrated) return (d.automatedScore as number | null | undefined) ?? r.score;
  return r.score;
}

function clamp01(n: number): number {
  return Math.max(0, Math.min(1, n));
}

type Outcome = { kind: 'skip' } | { kind: 'kept'; reason: string } | { kind: 'same' } | { kind: 'changed'; result: CaseResult; from: number | null; to: number | null };

/** Mark one stored result again under the current rules (pure apart from re-saving code artifacts). */
export async function remarkResult(r: CaseResult, test: LoadedTest | undefined, judgeIdByLabel: Map<string, string>, protocol = PROTOCOL_VERSION): Promise<Outcome> {
  if (r.reassignedTo || r.status !== 'ok') return { kind: 'skip' };
  if ((r.scoreDetail as ScoreDetail | undefined)?.remarkedUnder === protocol) return { kind: 'skip' };
  if (!test || test.definition.kind !== 'prompt') return { kind: 'skip' };
  const def = test.definition;
  const tc = def.cases.find((c) => c.id === r.caseId);
  if (!tc) return { kind: 'skip' };
  const scorer = caseScorer(def, tc);
  if (scorer.type === 'human') return { kind: 'skip' };
  if (!REMARKABLE.has(scorer.type)) return { kind: 'kept', reason: `${scorer.type === 'artifact' ? 'artifact tests need a browser replay' : `${scorer.type} is not re-marked`}` };
  const own = (r.transcript ?? []).filter((e) => !e.judge && !e.error);
  const last = own[own.length - 1];
  if (!last || typeof last.response !== 'string') return { kind: 'kept', reason: 'stored reply missing' };
  // Today's rules apply only to the same question; an answer to a since-changed question keeps its mark.
  if (!sameQuestion(def, tc, last)) return { kind: 'skip' };
  const taskText = [last.system ? `[System prompt]\n${last.system}` : '', ...(last.messages ?? []).map((m) => `[${m.role === 'user' ? 'User' : 'Assistant'}]\n${m.content}`)].filter(Boolean).join('\n\n');
  const judges = replayPanel((r.transcript ?? []).filter((e) => e.judge), judgeIdByLabel);
  let outcome;
  try {
    outcome = await scoreResponse({
      scorer,
      expected: tc.expected,
      response: last.response,
      stopReason: last.stopReason,
      ...(last.outputLimitBy ? { outputLimitBy: last.outputLimitBy } : {}),
      taskText,
      judges,
      saveArtifact: (name, kind, content) => saveArtifact(r.runId, r.key, name, kind, content),
      signal: new AbortController().signal,
    });
  } catch (err) {
    return { kind: 'kept', reason: err instanceof NoStoredJudgesError ? 'stored judge replies missing' : `could not be re-marked (${(err as Error).message.slice(0, 80)})` };
  }
  if (outcome.score === null || outcome.pendingHuman) return { kind: 'kept', reason: 'needs a person' };
  const to = Math.round(clamp01(outcome.score) * 10000) / 10000;
  const from = machineScore(r);
  const old = r.scoreDetail ?? {};
  const plain = !old.official && !old.arbitrated;
  if (from !== null && Math.abs(from - to) < 1e-9 && (!plain || Boolean(r.passed) === Boolean(outcome.passed))) return { kind: 'same' };

  const detail: ScoreDetail = { ...outcome.detail };
  for (const k of CARRIED) if (k in old) detail[k] = old[k];
  if (last.stopReason === 'max_tokens')
    detail.notes = `${detail.notes ? detail.notes + ' · ' : ''}Response hit the output token limit${last.outputLimitBy === 'per-answer' ? ' (stopped by your per-answer spend limit)' : last.outputLimitBy === 'spend-limit' ? ' (lowered by your run spend limit)' : ''}`;
  detail.previousScore = from;
  detail.previousSummary = (old.official ? (old.autoSummary as string | undefined) : undefined) ?? r.summary;
  detail.remarkedAt = new Date().toISOString();
  detail.remarkedUnder = protocol;

  let next: CaseResult = { ...r, scoreDetail: detail };
  if (old.official) {
    // A station grade decided the official score: update the machine score under it and apply the policy again.
    detail.autoScore = to;
    detail.autoSummary = outcome.summary;
    next = applyOfficial({ ...next, score: to, passed: outcome.passed, summary: outcome.summary }, gradingSpecFor(def, { caseId: r.caseId }), gradingPolicy());
  } else if (old.arbitrated) {
    // People arbitrated (Blind Review): their score stays final; only the panel's score underneath changes.
    detail.automatedScore = to;
  } else {
    next.score = to;
    next.passed = outcome.passed;
    next.summary = outcome.summary;
  }
  return { kind: 'changed', result: next, from, to: next.score };
}

/** Re-mark every stored answer that was marked under an older protocol. Idempotent; never calls a model. */
export async function remarkAllRuns(opts: RemarkOptions = {}): Promise<RemarkSummary> {
  const protocol = opts.protocol ?? PROTOCOL_VERSION;
  const summary: RemarkSummary = { protocol, runs: 0, remarked: 0, changed: 0, kept: [], busyRuns: [], changes: [] };
  const tests = new Map(loadTests().map((t) => [t.definition.id, t]));
  for (const runId of opts.runIds ?? listRunIds()) {
    const manifest = readManifest(runId);
    if (!manifest) continue;
    if (!opts.force && runRemarkProtocol(runId) === protocol) continue;
    if (opts.isActive?.(runId)) {
      summary.busyRuns.push(runId);
      continue;
    }
    summary.runs++;
    // A run made under the current rules needs nothing.
    if (!opts.force && manifest.settings?.protocolVersion === protocol) {
      writeJsonAtomic(markerFile(runId), { protocol, at: new Date().toISOString(), remarked: 0, changed: 0, note: 'run made under these rules' });
      continue;
    }
    const judgeIdByLabel = new Map((manifest.judges ?? []).map((j) => [j.label, j.id]));
    let remarked = 0;
    let changed = 0;
    ensureTrailingNewline(runId);
    for (const r of readResults(runId)) {
      const out = await remarkResult(r, tests.get(r.testId), judgeIdByLabel, protocol);
      if (out.kind === 'skip') continue;
      if (out.kind === 'kept') {
        summary.kept.push({ runId, key: r.key, reason: out.reason });
        continue;
      }
      remarked++;
      if (out.kind === 'changed') {
        appendResult(out.result);
        changed++;
        summary.changes.push({ runId, key: r.key, from: out.from, to: out.to });
      }
    }
    summary.remarked += remarked;
    summary.changed += changed;
    writeJsonAtomic(markerFile(runId), { protocol, at: new Date().toISOString(), remarked, changed });
  }
  return summary;
}
