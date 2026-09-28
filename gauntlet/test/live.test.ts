/**
 * Live viewing (UI logic): the plain-English commentary generated from run
 * events, and the "Watch it think" tile model. Both are pure TypeScript under
 * ui/src/components/live/.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { caseLong, caseShort, commentate, emptyCommentary, saidVsAnswer, seedCommentary, type CommentaryContext, type CommentaryLine, type CommentaryState } from '../ui/src/components/live/commentary.ts';
import { applyWatchEvent, createWatch, currentTest, gridCols, jobText, splitTail, tileView, VERDICT_MS, watchLeader } from '../ui/src/components/live/watchModel.ts';
import type { CaseMetrics, RunEvent, ResultStatus } from '../src/core/types.ts';

const ctx: CommentaryContext = {
  contestants: [
    { id: 'nova', label: 'Nova 3 Pro', color: '#f00' },
    { id: 'quill', label: 'Quill Flash', color: '#0f0' },
    { id: 'kite', label: 'Kite', color: '#00f' },
    { id: 'rnd', label: 'Random Baseline', color: '#888', baseline: true },
  ],
  tests: [
    { id: 'needle', name: 'Needle', kind: 'prompt', caseIds: ['q1', 'q2', 'q3', 'q4'] },
    { id: 'grid', name: 'Logic Grid', kind: 'prompt', caseIds: ['a'] },
    { id: 'island', name: 'Survival Island', kind: 'program', caseIds: ['seed-7', 'seed-9'] },
  ],
  repeats: 1,
};

const metrics = (wallMs: number): CaseMetrics => ({ wallMs, ttftMs: 100, apiCalls: 1, inputTokens: 100, outputTokens: 50, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0.001, judgeCostUsd: 0, outputTokensPerSec: 10, retries: 0, responseChars: 200 });
let clock = Date.parse('2026-09-28T10:00:00Z');
function fin(contestantId: string, testId: string, caseId: string, score: number | null, summary = '', status: ResultStatus = 'ok', wallMs = 12_300, repeat = 0): RunEvent {
  clock += 1000;
  return { type: 'job.finished', runId: 'r', key: `${contestantId}::${testId}::${caseId}::r${repeat}`, contestantId, testId, caseId, repeat, status, score, passed: score === null ? null : score >= 0.5, summary, metrics: metrics(wallMs), at: new Date(clock).toISOString() };
}
function play(events: RunEvent[], st: CommentaryState = emptyCommentary()): { st: CommentaryState; lines: CommentaryLine[] } {
  const lines: CommentaryLine[] = [];
  for (const e of events) {
    const out = commentate(st, e, ctx);
    st = out.state;
    lines.push(...out.lines);
  }
  return { st, lines };
}
const texts = (ls: CommentaryLine[]) => ls.map((l) => l.text);

test('commentary: right answers name the model, the test, the question and the time', () => {
  const { lines } = play([fin('nova', 'needle', 'q4', 1, 'Correct: "Anna"')]);
  assert.deepEqual(texts(lines), ['Nova 3 Pro gets Needle Q4 right, in 12.3 s']);
  assert.equal(lines[0]!.tone, 'good');
  assert.equal(lines[0]!.color, '#f00');
});

test('commentary: a miss quotes what the model said and the right answer, from the grader summary', () => {
  const { lines } = play([fin('quill', 'needle', 'q2', 0, 'Answered "1776" · expected "1778"')]);
  assert.deepEqual(texts(lines), ['Quill Flash misses Needle Q2: said 1776, answer 1778']);
  assert.equal(lines[0]!.tone, 'bad');
  assert.deepEqual(saidVsAnswer('Chose B · expected D'), { said: 'B', answer: 'D' });
  assert.deepEqual(saidVsAnswer('Answered “Paris” · expected “Lyon”'), { said: 'Paris', answer: 'Lyon' });
  assert.equal(saidVsAnswer('Answered "—" · expected "4"'), null, 'no answer found: nothing to quote');
  assert.equal(saidVsAnswer('Judge panel 7.5/10'), null);
  assert.equal(saidVsAnswer(undefined), null);
  // Unparseable or generic summaries fall back to a plain sentence (never invented details).
  assert.deepEqual(texts(play([fin('quill', 'needle', 'q3', 0, 'Incorrect')]).lines), ['Quill Flash misses Needle Q3']);
  assert.deepEqual(texts(play([fin('quill', 'needle', 'q3', 0, 'No valid JSON found')]).lines), ['Quill Flash misses Needle Q3: no valid JSON found']);
});

test('commentary: partial scores, simulations, errors, timeouts, refusals, skips and human review', () => {
  assert.deepEqual(texts(play([fin('kite', 'needle', 'q1', 0.65, '2/3 content checks')]).lines), ['Kite scores 65 out of 100 on Needle Q1: 2/3 content checks']);
  assert.equal(play([fin('kite', 'needle', 'q1', 0.2)]).lines[0]!.tone, 'bad');
  assert.deepEqual(texts(play([fin('kite', 'island', 'seed-9', 1, 'Survived all 30 days')]).lines), ['Kite aces Survival Island game 2: survived all 30 days']);
  assert.deepEqual(texts(play([fin('kite', 'island', 'seed-7', 0.4, 'Died on day 12 · dehydration')]).lines), ['Kite scores 40 out of 100 on Survival Island game 1: died on day 12 · dehydration']);
  assert.deepEqual(texts(play([fin('kite', 'needle', 'q1', null, 'Error: boom', 'error')]).lines), ['Kite hits an error on Needle Q1 (not counted)']);
  assert.deepEqual(texts(play([fin('kite', 'needle', 'q1', 0, 'Timed out', 'timeout')]).lines), ['Kite runs out of time on Needle Q1']);
  assert.deepEqual(texts(play([fin('kite', 'needle', 'q1', 0, 'Refused', 'refusal')]).lines), ['Kite refuses to answer Needle Q1']);
  assert.deepEqual(texts(play([fin('kite', 'needle', 'q1', null, 'Skipped', 'skipped')]).lines), ['Kite skips Needle Q1: it can’t see images']);
  assert.deepEqual(texts(play([fin('kite', 'needle', 'q1', null, 'Awaiting human review', 'pending-human')]).lines), ['Kite’s answer to Needle Q1 goes to a human judge']);
  assert.deepEqual(play([fin('kite', 'needle', 'q1', null, 'Cancelled', 'cancelled')]).lines, []);
  assert.equal(caseShort(ctx, 'needle', 'q2', 1), 'Q2 (attempt 2)');
  assert.equal(caseLong(ctx, 'needle', 'q2'), 'Question 2 of 4');
  assert.equal(caseLong(ctx, 'island', 'seed-9'), 'Game 2 of 2');
  assert.equal(caseShort(ctx, 'unknown', 'x7'), 'x7');
});

test('commentary: the lead changes hands only when someone is strictly ahead; the baseline never leads', () => {
  const { lines } = play([
    fin('rnd', 'needle', 'q1', 1), // the random guesser gets lucky: no line, no lead
    fin('nova', 'needle', 'q1', 1),
    fin('kite', 'needle', 'q1', 0),
    fin('kite', 'needle', 'q2', 1), // Kite 50 vs Nova 100: no change
    fin('nova', 'needle', 'q2', 0), // Nova 50 = Kite 50: tie keeps the leader
    fin('kite', 'needle', 'q3', 1), // Kite 67 > Nova 50: takes the lead
  ]);
  const lead = lines.filter((l) => l.kind === 'lead');
  assert.deepEqual(texts(lead), ['Nova 3 Pro takes the early lead, averaging 100', 'Kite takes the lead from Nova 3 Pro, averaging 67']);
  assert.ok(!texts(lines).some((t) => t.includes('Random Baseline')));
});

test('commentary: models finishing a test, the whole run, and streaks', () => {
  const ev = [
    ...['q1', 'q2', 'q3', 'q4'].map((q) => fin('nova', 'needle', q, 1)),
    ...['q1', 'q2', 'q3', 'q4'].map((q) => fin('quill', 'needle', q, 0)),
    ...['q1', 'q2', 'q3', 'q4'].map((q) => fin('rnd', 'needle', q, 0)),
    ...['q1', 'q2', 'q3', 'q4'].map((q) => fin('kite', 'needle', q, q === 'q1' ? 1 : 0.5)),
  ];
  const { lines, st } = play(ev);
  const progress = texts(lines.filter((l) => l.kind === 'progress'));
  assert.deepEqual(progress, ['Nova 3 Pro is the first to finish Needle', '2 of 3 models have finished Needle', 'All 3 models have finished Needle: Nova 3 Pro tops it with 100']);
  assert.deepEqual(texts(lines.filter((l) => l.kind === 'streak')), ['Nova 3 Pro is on a roll: 3 right in a row']);
  // Finishing the whole run.
  const more = play([fin('nova', 'grid', 'a', 1), fin('nova', 'island', 'seed-7', 1), fin('nova', 'island', 'seed-9', 0.5, 'Died on day 20')], st);
  assert.ok(texts(more.lines).includes('Nova 3 Pro has finished every test, averaging 93'));
  assert.ok(texts(more.lines).includes('Nova 3 Pro is on a roll: 5 right in a row'));
});

test('commentary: run status lines, and duplicate or already-seen results stay silent', () => {
  let { st } = play([fin('nova', 'needle', 'q1', 1), fin('kite', 'needle', 'q1', 0)]);
  const done = commentate(st, { type: 'run.status', runId: 'r', status: 'completed', at: 'x' }, ctx);
  assert.deepEqual(texts(done.lines), ['Run complete: Nova 3 Pro finishes top, averaging 100']);
  const cap = commentate(st, { type: 'run.status', runId: 'r', status: 'cancelled', at: 'x', error: 'Budget cap of $1.00 reached' }, ctx);
  assert.deepEqual(texts(cap.lines), ['Budget cap reached: the run stops here']);
  assert.deepEqual(commentate(st, { type: 'run.status', runId: 'r', status: 'running', at: 'x' }, ctx).lines, []);
  assert.deepEqual(commentate(st, { type: 'job.delta', runId: 'r', key: 'k', contestantId: 'nova', text: 'hi' }, ctx).lines, []);
  // The same finished event twice (resync): said once.
  const e = fin('quill', 'needle', 'q1', 1);
  const a = commentate(st, e, ctx);
  st = a.state;
  assert.equal(a.lines.length, 1);
  assert.deepEqual(commentate(st, e, ctx).lines, []);
  // Input state is never mutated.
  const before = JSON.stringify(st);
  commentate(st, fin('kite', 'needle', 'q2', 1), ctx);
  assert.equal(JSON.stringify(st), before);
});

test('commentary: seeding from stored results is silent and keeps the tallies', () => {
  const stored = [fin('nova', 'needle', 'q1', 1), fin('kite', 'needle', 'q1', 0)].map((e) => {
    const f = e as Extract<RunEvent, { type: 'job.finished' }>;
    return { ...f, at: f.at };
  });
  const st = seedCommentary(ctx, stored);
  assert.equal(st.leader, 'nova');
  // Seeing a seeded result again says nothing; a new one continues the story.
  assert.deepEqual(commentate(st, fin('nova', 'needle', 'q1', 1), ctx).lines, []);
  const next = commentate(st, fin('kite', 'needle', 'q2', 1), ctx);
  assert.deepEqual(texts(next.lines), ['Kite gets Needle Q2 right, in 12.3 s']);
});

// ── Watch it think ──

const contestants = [
  { id: 'nova', label: 'Nova 3 Pro', color: '#f00', outputPerM: 10 },
  { id: 'kite', label: 'Kite', color: '#00f', outputPerM: 2 },
  { id: 'rnd', label: 'Random', color: '#888', baseline: true },
];

test('watch: a tile thinks, types, shows the verdict with exact numbers, then moves on', () => {
  const w = createWatch(contestants, 4);
  const t0 = 1_000_000;
  const key = 'nova::needle::q1::r0';
  applyWatchEvent(w, { type: 'job.started', runId: 'r', key, contestantId: 'nova', testId: 'needle', caseId: 'q1', repeat: 0, at: new Date(t0).toISOString() }, t0);
  const tile = w.tiles.get('nova')!;
  assert.equal(tileView(tile, t0 + 500).phase, 'thinking');
  assert.equal(tileView(tile, t0 + 500).elapsedMs, 500);
  applyWatchEvent(w, { type: 'job.delta', runId: 'r', key, contestantId: 'nova', text: 'Let me think.\n', label: 'response' }, t0 + 800);
  applyWatchEvent(w, { type: 'job.delta', runId: 'r', key, contestantId: 'nova', text: 'FINAL ANSWER: 4', label: 'response' }, t0 + 900);
  let v = tileView(tile, t0 + 1000);
  assert.equal(v.phase, 'typing');
  assert.equal(v.text, 'Let me think.\nFINAL ANSWER: 4');
  assert.equal(v.tokens, Math.ceil(v.text.length / 4));
  assert.equal(v.tokensExact, false);
  assert.ok(Math.abs(v.spend - (v.tokens * 10) / 1e6) < 1e-12, 'estimated cost = estimated tokens × output price');
  assert.equal(v.spendExact, false);
  applyWatchEvent(w, fin('nova', 'needle', 'q1', 1, 'Correct: "4"', 'ok', 1500), t0 + 1500);
  v = tileView(tile, t0 + 1600);
  assert.equal(v.phase, 'verdict');
  assert.equal(v.verdict!.score, 1);
  assert.equal(v.tokens, 50, 'recorded token count replaces the estimate');
  assert.equal(v.tokensExact, true);
  assert.equal(v.spend, 0.001);
  assert.equal(v.text, 'Let me think.\nFINAL ANSWER: 4');
  assert.equal(tile.done, 1);
  assert.equal(tileView(tile, t0 + 1500 + VERDICT_MS + 1).phase, 'idle');
  // The next case takes over once the verdict has been shown.
  applyWatchEvent(w, { type: 'job.started', runId: 'r', key: 'nova::needle::q2::r0', contestantId: 'nova', testId: 'needle', caseId: 'q2', repeat: 0, at: new Date(t0 + 1600).toISOString() }, t0 + 1600);
  assert.equal(tileView(tile, t0 + 1700).phase, 'verdict');
  assert.equal(tileView(tile, t0 + 1500 + VERDICT_MS + 1).phase, 'thinking');
});

test('watch: retried calls drop their partial text; multi-call jobs keep earlier turns; late joiners adopt jobs', () => {
  const w = createWatch(contestants, 4);
  const key = 'kite::island::seed-7::r0';
  applyWatchEvent(w, { type: 'job.delta', runId: 'r', key, contestantId: 'kite', text: 'Day 1: forage', label: 'Day 1' }, 10);
  const tile = w.tiles.get('kite')!;
  assert.equal(tile.jobs.get(key)!.testId, 'island', 'recovered from the key');
  applyWatchEvent(w, { type: 'job.step', runId: 'r', key, contestantId: 'kite', label: 'Day 2' }, 11);
  applyWatchEvent(w, { type: 'job.delta', runId: 'r', key, contestantId: 'kite', text: 'Day 2: bui', label: 'Day 2' }, 12);
  applyWatchEvent(w, { type: 'job.delta', runId: 'r', key, contestantId: 'kite', text: 'Day 2: build shelter', label: 'Day 2', reset: true }, 13);
  assert.equal(jobText(tile.jobs.get(key)), 'Day 1: forage\n\nDay 2: build shelter');
  assert.equal(tileView(tile, 20).job!.step, 'Day 2');
  assert.equal(tile.jobs.get(key)!.chars, 'Day 1: forage'.length + 'Day 2: build shelter'.length);
});

test('watch: counts, leader, current test, manual waits and resync without double counting', () => {
  const w = createWatch(contestants, 2, [
    { key: 'nova::needle::q1::r0', contestantId: 'nova', status: 'ok', score: 1, metrics: { costUsd: 0.5, outputTokens: 10 } },
    { key: 'rnd::needle::q1::r0', contestantId: 'rnd', status: 'ok', score: 1, metrics: { costUsd: 0 } },
  ]);
  assert.equal(watchLeader(w)!.id, 'nova', 'the random baseline never leads');
  applyWatchEvent(w, fin('nova', 'needle', 'q1', 1), 5); // already counted
  assert.equal(w.tiles.get('nova')!.done, 1);
  applyWatchEvent(w, { type: 'job.started', runId: 'r', key: 'kite::grid::a::r0', contestantId: 'kite', testId: 'grid', caseId: 'a', repeat: 0, at: new Date(0).toISOString() }, 5);
  applyWatchEvent(w, { type: 'job.started', runId: 'r', key: 'nova::grid::a::r0', contestantId: 'nova', testId: 'grid', caseId: 'a', repeat: 0, at: new Date(0).toISOString() }, 5);
  applyWatchEvent(w, { type: 'job.started', runId: 'r', key: 'rnd::needle::q2::r0', contestantId: 'rnd', testId: 'needle', caseId: 'q2', repeat: 0, at: new Date(0).toISOString() }, 5);
  assert.equal(currentTest(w), 'grid');
  const req = { id: 'm1', runId: 'r', key: 'kite::grid::a::r0', contestantId: 'kite', contestantLabel: 'Kite', testId: 'grid', testName: 'Logic Grid', caseId: 'a', label: 'response', messages: [], combinedPrompt: '', latestUserMessage: '', isContinuation: false, createdAt: '' };
  applyWatchEvent(w, { type: 'manual.request', runId: 'r', request: req }, 6);
  assert.equal(tileView(w.tiles.get('kite')!, 7).phase, 'waiting-manual');
  applyWatchEvent(w, { type: 'manual.resolved', runId: 'r', requestId: 'm1' }, 8);
  assert.equal(tileView(w.tiles.get('kite')!, 9).phase, 'thinking');
  applyWatchEvent(w, { type: 'run.progress', runId: 'r', completed: 3, total: 6, costUsd: 1.25, at: '' }, 9);
  assert.equal(w.costUsd, 1.25);
  // Resync keeps in-flight jobs.
  const again = createWatch(contestants, 2, [], w);
  assert.equal(again.tiles.get('kite')!.jobs.size, 1);
  // Finished all cases.
  applyWatchEvent(w, fin('nova', 'grid', 'a', 0), 10);
  assert.equal(tileView(w.tiles.get('nova')!, 10 + VERDICT_MS + 1).phase, 'finished');
});

test('watch: layout helpers', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7, 8].map(gridCols), [1, 2, 3, 2, 3, 3, 4, 4]);
  assert.deepEqual(splitTail('a\nb\nc\nd', 2), { head: 'a\nb\n', tail: 'c\nd' });
  assert.deepEqual(splitTail('one line', 2), { head: '', tail: 'one line' });
});
