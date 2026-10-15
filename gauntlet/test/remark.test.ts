// Re-marking stored answers under the current marking rules (src/engine/remark.ts).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { appendFileSync, cpSync, existsSync, mkdtempSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import type { CaseResult, ContestantSnapshot, PromptTest, RunManifest, TranscriptEntry } from '../src/core/types.ts';

// Sandbox data + config before any module reads the paths.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-remark-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
cpSync(resolve(import.meta.dirname, '..', 'config'), join(sandbox, 'config'), { recursive: true });

const { loadTests, renderCase } = await import('../src/core/registry.ts');
const { createRunFolder, appendResult, readResults, runDir } = await import('../src/engine/store.ts');
const { remarkAllRuns, remarkResult, remarkSummaryLine, stripFairPlay, protocolLabel } = await import('../src/engine/remark.ts');
const { PROTOCOL_VERSION } = await import('../src/core/version.ts');

const tests = loadTests();
const testById = (id: string) => tests.find((t) => t.definition.id === id)!;

function snap(id: string, provider: string, label = id): ContestantSnapshot {
  return { id, label, vendor: 'V', provider, model: id, color: '#000000', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } } as ContestantSnapshot;
}

function entry(system: string | undefined, user: string, response: string, extra: Partial<TranscriptEntry> = {}): TranscriptEntry {
  return { label: 'response', system, messages: [{ role: 'user', content: user }], response, usage: { inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 } as never, ttftMs: null, totalMs: 1, stopReason: 'end', rawStopReason: 'stop', costUsd: 0, retries: 0, ...extra };
}

function stored(runId: string, contestantId: string, testId: string, caseId: string, response: string, score: number, opts: { userPrefix?: string; user?: string; judges?: TranscriptEntry[]; hash?: string; detail?: Record<string, unknown> } = {}): CaseResult {
  const t = testById(testId);
  const def = t.definition as PromptTest;
  const r = renderCase(def, def.cases.find((c) => c.id === caseId)!);
  return {
    key: `${contestantId}::${testId}::${caseId}::r0`,
    runId,
    contestantId,
    testId,
    testVersion: def.version,
    testHash: opts.hash ?? 'hash-under-the-old-rules',
    contestantHash: 'c',
    caseId,
    repeat: 0,
    status: 'ok',
    score,
    passed: score >= 1,
    summary: `old mark ${score}`,
    scoreDetail: { ...(opts.detail ?? {}) },
    metrics: { wallMs: 1, ttftMs: null, apiCalls: 1, inputTokens: 0, outputTokens: 0, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0, judgeCostUsd: 0, outputTokensPerSec: null, retries: 0, responseChars: response.length },
    transcript: [entry(r.system, opts.user ?? `${opts.userPrefix ?? ''}${r.turns[0]}`, response), ...(opts.judges ?? [])],
    artifacts: [],
    startedAt: '2026-09-01T00:00:00Z',
    finishedAt: '2026-09-01T00:00:01Z',
  };
}

function makeRun(runId: string, results: CaseResult[], protocolVersion = '2026.09'): void {
  const manifest = {
    id: runId,
    name: runId,
    status: 'completed',
    createdAt: '2026-09-01T00:00:00Z',
    harnessVersion: 'x',
    node: 'x',
    platform: 'x',
    fingerprint: 'f',
    tests: [],
    contestants: [snap('api-model', 'openai'), snap('pasted-model', 'manual')],
    judges: [snap('judge-a', 'openai', 'Judge A')],
    settings: { repeats: 1, concurrency: 1, temperature: 0, protocolVersion },
    totalJobs: results.length,
  } as unknown as RunManifest;
  createRunFolder(manifest);
  for (const r of results) appendResult(r);
}

const STUB = '```javascript\nfunction romanToInt(s) { return null; }\n```';

test('protocol bumped for the October 2026 marking rules', () => {
  assert.equal(PROTOCOL_VERSION, '2026.10');
  assert.equal(protocolLabel('2026.10'), 'October 2026');
});

test('re-marking applies today\'s rules to stored replies, keeps the old mark, and runs once', { timeout: 120_000 }, async () => {
  const runId = 'run-old-rules';
  makeRun(runId, [
    stored(runId, 'api-model', 'trick.false-premise', 'r01', 'Marie Curie won Chemistry in 1911.\nFINAL ANSWER: \\boxed{1911}', 0),
    // A copy & paste (manual) answer is re-marked exactly like an API one.
    stored(runId, 'pasted-model', 'trick.false-premise', 'r01', 'FINAL ANSWER: $\\boxed{\\text{1911}}$', 0),
    // A stub that the old sandbox gave 19/41 for.
    stored(runId, 'api-model', 'coding.debug-and-edge-cases', 'e5', STUB, 0.4634),
    // Unchanged under the new rules: no new line is written.
    stored(runId, 'api-model', 'trick.false-premise', 'r02', 'FINAL ANSWER: Eris', 1),
    // The question changed since: the mark is left alone.
    stored(runId, 'api-model', 'trick.false-premise', 'r03', 'FINAL ANSWER: \\boxed{Mexico}', 0, { user: 'An older wording of the question.' }),
    // The stored reply is missing: kept and listed.
    { ...stored(runId, 'api-model', 'trick.false-premise', 'r04', '', 0), transcript: [] },
  ]);
  const before = statSync(join(runDir(runId), 'results.jsonl')).size;
  const s = await remarkAllRuns({ runIds: [runId] });
  assert.equal(s.changed, 3);
  assert.equal(s.remarked, 4, 'the unchanged answer was re-marked too, it just kept its mark');
  assert.deepEqual(s.kept.map((k) => k.reason), ['stored reply missing']);
  assert.match(remarkSummaryLine(s), /^Re-marked 4 answers under the October 2026 rules: 3 changed/);

  const byKey = new Map(readResults(runId).map((r) => [r.key, r]));
  const boxed = byKey.get('api-model::trick.false-premise::r01::r0')!;
  assert.equal(boxed.score, 1, '"\\boxed{1911}" is now read as 1911');
  assert.equal(boxed.scoreDetail.previousScore, 0);
  assert.equal(boxed.scoreDetail.remarkedUnder, '2026.10');
  assert.equal(byKey.get('pasted-model::trick.false-premise::r01::r0')!.score, 1);
  const stub = byKey.get('api-model::coding.debug-and-edge-cases::e5::r0')!;
  assert.equal(stub.score, 0, 'a stub loses its credit');
  assert.equal(stub.scoreDetail.previousScore, 0.4634);
  assert.equal(byKey.get('api-model::trick.false-premise::r02::r0')!.scoreDetail.remarkedUnder, undefined);
  assert.equal(byKey.get('api-model::trick.false-premise::r03::r0')!.score, 0, 'changed question: mark left alone');

  // Running twice changes nothing (fast path: the run is marked done).
  const size = statSync(join(runDir(runId), 'results.jsonl')).size;
  assert.ok(size > before);
  const again = await remarkAllRuns({ runIds: [runId] });
  assert.equal(again.runs, 0);
  assert.equal(statSync(join(runDir(runId), 'results.jsonl')).size, size);

  // Interrupted before the run was marked done: the next pass skips what was already re-marked.
  rmSync(join(runDir(runId), 'remark.json'));
  const resumed = await remarkAllRuns({ runIds: [runId] });
  assert.equal(resumed.changed, 0);
  assert.equal(statSync(join(runDir(runId), 'results.jsonl')).size, size);
  assert.equal(readResults(runId).find((r) => r.key === boxed.key)!.scoreDetail.previousScore, 0, 'the first old mark is kept');
});

test('an append torn by a crash does not swallow the next re-marked line', async () => {
  const runId = 'run-torn';
  makeRun(runId, [stored(runId, 'api-model', 'trick.false-premise', 'r01', 'FINAL ANSWER: \\boxed{1911}', 0)]);
  appendFileSync(join(runDir(runId), 'results.jsonl'), '{"key":"half a line');
  const s = await remarkAllRuns({ runIds: [runId] });
  assert.equal(s.changed, 1);
  assert.equal(readResults(runId).find((r) => r.key === 'api-model::trick.false-premise::r01::r0')!.score, 1);
});

test('runs made under the current rules are left alone; running runs wait for the next start', async () => {
  makeRun('run-new-rules', [stored('run-new-rules', 'api-model', 'trick.false-premise', 'r01', 'FINAL ANSWER: \\boxed{1911}', 0)], PROTOCOL_VERSION);
  const s = await remarkAllRuns({ runIds: ['run-new-rules'] });
  assert.equal(s.remarked, 0);
  assert.equal(readResults('run-new-rules')[0]!.score, 0);
  makeRun('run-busy', [stored('run-busy', 'api-model', 'trick.false-premise', 'r01', 'FINAL ANSWER: \\boxed{1911}', 0)]);
  const busy = await remarkAllRuns({ runIds: ['run-busy'], isActive: () => true });
  assert.deepEqual(busy.busyRuns, ['run-busy']);
  assert.equal(existsSync(join(runDir('run-busy'), 'remark.json')), false);
});

test('a leading fair-play notice is ignored when comparing questions', async () => {
  assert.equal(stripFairPlay('[Fair-play rules] Answer on your own.\nNo tools.\n\nThe question'), 'The question');
  assert.equal(stripFairPlay('The question'), 'The question');
  const r = stored('run-x', 'api-model', 'trick.false-premise', 'r01', 'FINAL ANSWER: \\boxed{1911}', 0, { userPrefix: '[Fair-play rules] Work alone.\n\n' });
  const out = await remarkResult(r, testById('trick.false-premise'), new Map());
  assert.equal(out.kind, 'changed');
});

test('judge-graded answers are re-marked by parsing the stored judge replies again (no judge is called)', async () => {
  const def: PromptTest = { kind: 'prompt', id: 'custom.judged', version: '1.0.0', name: 'Judged', category: 'creative', description: '', scorer: { type: 'judge', rubric: 'Be good.' }, cases: [{ id: 'c1', prompt: 'Write a haiku.' }] } as unknown as PromptTest;
  const t = { definition: def, hash: 'h', file: 'x.json', source: 'custom' as const };
  const judgeReply: TranscriptEntry = { ...entry('judge system', 'judge prompt', 'Lovely.\nSCORE: 10/10'), label: 'judge · Judge A', judge: true };
  const r: CaseResult = {
    ...stored('run-j', 'api-model', 'trick.false-premise', 'r01', 'An old pond...', 0.1),
    testId: 'custom.judged',
    caseId: 'c1',
    key: 'api-model::custom.judged::c1::r0',
    transcript: [entry(undefined, 'Write a haiku.', 'An old pond...'), judgeReply],
  };
  const out = await remarkResult(r, t as never, new Map([['Judge A', 'judge-a']]));
  assert.equal(out.kind, 'changed');
  if (out.kind === 'changed') {
    assert.equal(out.result.score, 1, '"SCORE: 10/10" is ten out of ten');
    assert.equal(out.result.scoreDetail.judge?.[0]?.contestantId, 'judge-a');
  }
  const noJudges = await remarkResult({ ...r, transcript: [r.transcript[0]!] }, t as never, new Map());
  assert.deepEqual(noJudges, { kind: 'kept', reason: 'stored judge replies missing' });
});

test('a person\'s grade stays final: only the machine score underneath is re-marked', async () => {
  const r = stored('run-h', 'api-model', 'trick.false-premise', 'r01', 'FINAL ANSWER: \\boxed{1911}', 0.8, { detail: { arbitrated: true, automatedScore: 0 } });
  r.humanScores = [{ rater: 'owner', score: 0.8, at: '2026-09-02T00:00:00Z' }];
  const out = await remarkResult(r, testById('trick.false-premise'), new Map());
  assert.equal(out.kind, 'changed');
  if (out.kind === 'changed') {
    assert.equal(out.result.score, 0.8, 'the arbitrated score is unchanged');
    assert.equal(out.result.scoreDetail.automatedScore, 1);
    assert.deepEqual(out.result.humanScores, r.humanScores);
  }
});
