import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import type { CaseResult, Contestant, RunManifest } from '../src/core/types.ts';

// Sandbox data + config before any module reads the paths.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-manual-models-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
cpSync(resolve(import.meta.dirname, '..', 'config'), join(sandbox, 'config'), { recursive: true });

const id = await import('../src/manual-models/identity.ts');
const store = await import('../src/manual-models/store.ts');
const { rankEntries, viewBest, timelineFor, NO_FILTERS } = await import('../src/manual-models/best-rank.ts');
const { bestPerTest } = await import('../src/manual-models/best.ts');
const reassign = await import('../src/manual-models/reassign.ts');
const { contestantConfigHash, loadContestants, deleteContestant, upsertContestant } = await import('../src/core/config.ts');
const { loadTests } = await import('../src/core/registry.ts');
const { createRunFolder, appendResult, readResults, readManifest, listRuns } = await import('../src/engine/store.ts');
const { combinedLeaderboard, runLeaderboard } = await import('../src/engine/leaderboards.ts');
type BestEntry = import('../src/manual-models/best-rank.ts').BestEntry;

// ───────────────────────────── Catalogue ─────────────────────────────

test('the shipped catalogue is valid: unique ids, real dates, required fields, honest verification', () => {
  const cat = store.loadShippedCatalog();
  assert.deepEqual(id.validateCatalog(cat), []);
  assert.ok(cat.models.length >= 60, 'covers the notable models since March 2024');
  assert.equal(new Set(cat.models.map((m) => m.id)).size, cat.models.length, 'ids are unique');
  for (const m of cat.models) {
    assert.ok(!Number.isNaN(Date.parse(`${id.releaseDay(m)}T00:00:00Z`)), `${m.id} release date parses`);
    assert.ok(m.source.length > 5, `${m.id} has a source`);
    assert.equal(m.status === 'unverified', m.verifiedAt === null, `${m.id}: unverified exactly when verifiedAt is null`);
  }
  // The vendors the owner asked for are all there.
  for (const v of ['Anthropic', 'OpenAI', 'Google', 'xAI', 'Meta', 'Mistral', 'DeepSeek', 'Alibaba', 'Moonshot AI']) assert.ok(cat.models.some((m) => m.vendor === v), v);
  // The oldest model the owner asked about, with its checked availability.
  const opus3 = cat.models.find((m) => m.id === 'claude-3-opus')!;
  assert.equal(opus3.status, 'retired');
  assert.equal(opus3.retiredDate, '2026-01-05');
  assert.equal(opus3.access.chatApp, 'paid-plans');
  const text = id.availabilityText(opus3, cat.vendors.find((v) => v.id === 'Anthropic'));
  assert.match(text, /Retired from Anthropic’s API on 5 Jan 2026/);
  assert.match(text, /still in claude\.ai on paid plans/);
  assert.match(text, /\(not checked\)/);
});

test('catalogue validation catches duplicates, bad dates and dishonest verification', () => {
  const cat = store.loadShippedCatalog();
  const first = cat.models[0]!;
  const bad = {
    ...cat,
    models: [
      first,
      { ...first }, // duplicate id
      { ...first, id: 'bad-date', released: '2024-13' },
      { ...first, id: 'bad-day', releaseDate: '2024-02-30', released: '2024-02' },
      { ...first, id: 'fake-check', status: 'unverified' as const }, // unverified but has verifiedAt
      { ...first, id: 'no-check', status: 'available' as const, verifiedAt: null },
      { ...first, id: 'dep', status: 'deprecated' as const, shutdownDate: undefined, retiredDate: undefined },
      { ...first, id: 'no-vendor', vendor: 'Nobody' },
      { ...first, id: 'Bad Id' },
      { ...first, id: 'future', released: '2099-01', releaseDate: undefined },
    ],
  };
  const errs = id.validateCatalog(bad).join('\n');
  for (const want of ['appears twice', 'released must be YYYY-MM', 'releaseDate must be a real', 'must have verifiedAt null', 'must have status "unverified"', 'needs its shutdownDate', 'not in the vendors list', 'id must be lowercase', 'after the catalogue was checked']) assert.match(errs, new RegExp(want.replace(/[()]/g, '\\$&')), want);
});

test('"Suggest a model not in the list" adds an unverified entry to the user folder', () => {
  const m = store.addCustomModel({ label: 'Claude 2.1', vendor: 'Anthropic', released: '2023-11' });
  assert.equal(m.status, 'unverified');
  assert.equal(m.verifiedAt, null);
  assert.ok(m.custom);
  assert.ok(existsSync(store.CUSTOM_CATALOG_FILE));
  assert.ok(store.CUSTOM_CATALOG_FILE.startsWith(join(sandbox, 'data')), 'kept in the user data folder, not the app folder');
  assert.ok(store.loadCatalog().models.some((x) => x.id === m.id));
  assert.equal(store.loadShippedCatalog().models.some((x) => x.id === m.id), false);
  assert.throws(() => store.addCustomModel({ label: 'X', released: 'March 2024' }), /2024-03/);
  assert.throws(() => store.addCustomModel({ label: ' ', released: '2024-03' }), /name/);
});

// ───────────────────────────── Identity and hash ─────────────────────────────

const catalog = () => store.loadCatalog();
const entry = (cid: string) => catalog().models.find((m) => m.id === cid)!;
const vendor = (v: string) => catalog().vendors.find((x) => x.id === v);

test('contestant identity: model + interface + settings; vendor kept for the judge rule; note is not identity', () => {
  const opus = entry('claude-3-opus');
  const app = id.buildManualContestant(opus, { catalogId: 'claude-3-opus', interface: 'company-app', thinking: 'default', webSearch: false }, vendor('Anthropic'));
  const app2 = id.buildManualContestant(opus, { catalogId: 'claude-3-opus', interface: 'company-app', thinking: 'default', webSearch: false, note: 'Pro plan' }, vendor('Anthropic'));
  const api = id.buildManualContestant(opus, { catalogId: 'claude-3-opus', interface: 'api-playground', thinking: 'default', webSearch: false }, vendor('Anthropic'));
  const off = id.buildManualContestant(opus, { catalogId: 'claude-3-opus', interface: 'company-app', thinking: 'off', webSearch: false }, vendor('Anthropic'));
  const web = id.buildManualContestant(opus, { catalogId: 'claude-3-opus', interface: 'company-app', thinking: 'default', webSearch: true }, vendor('Anthropic'));
  assert.equal(app.id, 'manual.claude-3-opus.company-app');
  assert.equal(app.vendor, 'Anthropic');
  assert.equal(app.provider, 'manual');
  assert.equal(app.label, 'Claude 3 Opus (claude.ai)');
  assert.equal(api.label, 'Claude 3 Opus (API playground)');
  assert.equal(off.label, 'Claude 3 Opus (claude.ai, thinking off)');
  assert.equal(web.label, 'Claude 3 Opus (claude.ai, web search on)');
  assert.equal(app.releaseDate, '2024-03-04');
  // Same model through the same interface = same contestant and hash, whatever the note says.
  assert.equal(app2.id, app.id);
  assert.equal(contestantConfigHash(app2), contestantConfigHash(app));
  // Anything else never silently mixes.
  const hashes = new Set([app, api, off, web].map(contestantConfigHash));
  assert.equal(hashes.size, 4);
  assert.equal(new Set([app, api, off, web].map((c) => c.id)).size, 4);
  for (const c of [app, api, off, web]) assert.match(c.id, /^[a-z0-9][a-z0-9._-]{0,63}$/, 'fits the contestant id rules and is safe as a Windows file name');
  assert.equal(id.copiedByHandText(web.manualModel!, vendor('Anthropic')), 'copied by hand · claude.ai · web search ON');
});

test('catalogue contestants are saved in the user folder and merged into the model list', () => {
  const c = reassign.ensureManualContestant({ catalogId: 'gpt-4', interface: 'api-playground' });
  assert.ok(loadContestants().some((x) => x.id === c.id));
  assert.ok(existsSync(store.CONTESTANTS_FILE));
  const shipped = JSON.parse(readFileSync(join(sandbox, 'config', 'models.json'), 'utf8')) as { contestants: Contestant[] };
  assert.equal(shipped.contestants.some((x) => x.id === c.id), false, 'models.json in the app folder is untouched');
  // Making it again returns the same contestant; editing it on the Models page keeps it in the user folder.
  assert.equal(reassign.ensureManualContestant({ catalogId: 'gpt-4', interface: 'api-playground' }).id, c.id);
  upsertContestant({ ...c, color: '#123456' });
  assert.equal(store.loadUserManualContestants().find((x) => x.id === c.id)?.color, '#123456');
  assert.ok(deleteContestant(c.id));
  assert.equal(loadContestants().some((x) => x.id === c.id), false);
  assert.throws(() => reassign.ensureManualContestant({ catalogId: 'no-such-model' }), /not in the model catalogue/);
});

// ───────────────────────────── Runs: API + manual together, reassigning ─────────────────────────────

const tests = loadTests();
const T1 = tests.find((t) => t.definition.kind === 'prompt')!;
const T2 = tests.filter((t) => t.definition.kind === 'prompt')[1]!;
const api = loadContestants().find((c) => c.id === 'gpt-4o')!;
const generic = loadContestants().find((c) => c.id === 'manual-chat')!;

function snap(c: Contestant) {
  return { ...c, configHash: contestantConfigHash(c) };
}

function makeRun(runId: string, contestants: Contestant[], createdAt: string): void {
  const m: RunManifest = {
    id: runId,
    name: runId,
    status: 'completed',
    createdAt,
    harnessVersion: 'test',
    node: process.version,
    platform: process.platform,
    fingerprint: 'fp',
    tests: [T1, T2].map((t) => ({ id: t.definition.id, version: t.definition.version, hash: t.hash, name: t.definition.name, category: t.definition.category, kind: t.definition.kind, caseIds: [], weight: 1 })),
    contestants: contestants.map(snap),
    judges: [],
    settings: { repeats: 1, concurrency: 1, temperature: 0, protocolVersion: '1' },
    totalJobs: 4,
  };
  createRunFolder(m);
}

function res(runId: string, c: Contestant, t: (typeof tests)[number], caseId: string, score: number | null, finishedAt: string, status: CaseResult['status'] = 'ok'): CaseResult {
  const r: CaseResult = {
    key: `${c.id}::${t.definition.id}::${caseId}::r0`,
    runId,
    contestantId: c.id,
    testId: t.definition.id,
    testVersion: t.definition.version,
    testHash: t.hash,
    contestantHash: contestantConfigHash(c),
    caseId,
    repeat: 0,
    status,
    score,
    passed: score === null ? null : score >= 0.5,
    summary: '',
    scoreDetail: {},
    metrics: { wallMs: 1000, ttftMs: null, apiCalls: 1, inputTokens: 10, outputTokens: 10, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0.01, judgeCostUsd: 0.002, outputTokensPerSec: null, retries: 0, responseChars: 10 },
    transcript: [],
    artifacts: [],
    startedAt: finishedAt,
    finishedAt,
  };
  appendResult(r);
  return r;
}

test('manual and API results aggregate per test: Best on each test, leaderboard labels, the same model across runs pools', () => {
  const opusApp = reassign.ensureManualContestant({ catalogId: 'claude-3-opus', interface: 'company-app' });
  const opusApi = reassign.ensureManualContestant({ catalogId: 'claude-3-opus', interface: 'api-playground' });
  makeRun('run-a', [api, opusApp], '2026-09-01T00:00:00Z');
  res('run-a', api, T1, 'c1', 1, '2026-09-01T01:00:00Z');
  res('run-a', api, T1, 'c2', 0, '2026-09-01T01:00:00Z');
  res('run-a', opusApp, T1, 'c1', 1, '2026-09-01T02:00:00Z');
  res('run-a', opusApp, T1, 'c2', 1, '2026-09-01T02:00:00Z');
  // A second run of the same model through the same app pools with the first.
  makeRun('run-b', [opusApp, opusApi], '2026-09-05T00:00:00Z');
  res('run-b', opusApp, T1, 'c3', 0, '2026-09-05T02:00:00Z');
  res('run-b', opusApi, T1, 'c1', 0.5, '2026-09-05T03:00:00Z');

  const best = bestPerTest();
  const t = best.tests.find((x) => x.id === T1.definition.id)!;
  const ranked = rankEntries(t.entries, (x) => x);
  assert.deepEqual(ranked.map((e) => e.contestantId), [opusApp.id, api.id, opusApi.id]);
  const top = ranked[0]!;
  assert.ok(top.leader);
  assert.equal(top.n, 3, 'three answers over two runs');
  assert.equal(top.runs, 2);
  assert.equal(top.lastTestedAt, '2026-09-05T02:00:00Z');
  assert.ok(Math.abs(top.score! - 2 / 3) < 1e-3);
  const model = best.models.find((m) => m.id === opusApp.id)!;
  assert.equal(model.manual, true);
  assert.equal(model.howLabel, 'copied by hand · claude.ai');
  assert.equal(model.releaseDate, '2024-03-04');
  assert.equal(best.models.find((m) => m.id === api.id)!.manual, false);
  // The timeline puts the 2024 models in release order.
  const tl = timelineFor(best, T1.definition.id, NO_FILTERS);
  assert.deepEqual(tl.map((x) => x.model.id), [opusApi.id, opusApp.id, api.id], 'Mar 2024 (by label) then GPT-4o, May 2024');
  // Filters re-rank: manual only.
  const manualOnly = viewBest(best, { ...NO_FILTERS, kind: 'manual' }).find((x) => x.id === T1.definition.id)!;
  assert.deepEqual(manualOnly.ranked.map((e) => e.contestantId), [opusApp.id, opusApi.id]);
  assert.equal(viewBest(best, { ...NO_FILTERS, year: '2019' }).length, 0);

  // Run leaderboard marks the row manual with its interface; Head to Head etc. read the same results.
  const lb = runLeaderboard('run-a')!;
  const row = lb.rows.find((r) => r.contestantId === opusApp.id)!;
  assert.equal(row.manual, true);
  assert.equal(row.manualModel?.interface, 'company-app');
  // The combined leaderboard (every suite pools the same way) has it too.
  const combined = combinedLeaderboard('core');
  if (combined.tests.some((x) => x.id === T1.definition.id)) assert.ok(combined.rows.some((r) => r.contestantId === opusApp.id && r.manualModel));
});

test('reassigning old "unspecified" manual results: explicit, logged, counted once, never twice', () => {
  makeRun('run-old', [generic, api], '2026-08-01T00:00:00Z');
  const r1 = res('run-old', generic, T2, 'c1', 1, '2026-08-01T01:00:00Z');
  const r2 = res('run-old', generic, T2, 'c2', 0, '2026-08-01T01:00:00Z');
  res('run-old', api, T2, 'c1', 0, '2026-08-01T01:00:00Z');
  const before = reassign.listUnspecified().filter((g) => g.runId === 'run-old');
  assert.equal(before.length, 1);
  assert.deepEqual(before[0]!.keys.sort(), [r1.key, r2.key].sort());
  const runsBefore = listRuns().find((x) => x.id === 'run-old')!;

  const to = reassign.ensureManualContestant({ catalogId: 'claude-3-5-sonnet-20240620', interface: 'company-app' });
  const out = reassign.reassignResults({ runId: 'run-old', keys: [r1.key, r2.key, 'nope'], to, how: 'reassign', note: 'I remember using 3.5 Sonnet' });
  assert.equal(out.moved, 2);
  assert.equal(out.skipped.length, 1);

  const all = readResults('run-old');
  const moved = all.filter((r) => r.contestantId === to.id);
  assert.equal(moved.length, 2);
  for (const r of moved) {
    assert.equal(r.manualOrigin?.how, 'reassign');
    assert.equal(r.manualOrigin?.from, 'manual-chat');
    assert.equal(r.contestantHash, contestantConfigHash(to));
  }
  // The originals stay on record (resume never asks again) but no longer count.
  const originals = all.filter((r) => r.contestantId === 'manual-chat');
  assert.equal(originals.length, 2);
  assert.ok(originals.every((r) => r.reassignedTo?.contestantId === to.id));
  const lb = runLeaderboard('run-old')!;
  assert.equal(lb.rows.some((r) => r.contestantId === 'manual-chat'), false);
  assert.equal(lb.rows.find((r) => r.contestantId === to.id)?.tests[T2.definition.id]?.score, 0.5);
  assert.ok(readManifest('run-old')!.contestants.some((c) => c.id === to.id), 'the run now lists the named model');
  // Progress and cost are not double counted.
  const runsAfter = listRuns().find((x) => x.id === 'run-old')!;
  assert.equal(runsAfter.completedJobs, runsBefore.completedJobs);
  assert.equal(runsAfter.costUsd, runsBefore.costUsd);
  // Logged, and gone from the "unspecified" list; a second move is refused.
  const log = reassign.reassignLog();
  assert.equal(log.filter((l) => l.runId === 'run-old').length, 2);
  assert.equal(reassign.listUnspecified().filter((g) => g.runId === 'run-old').length, 0);
  const again = reassign.reassignResults({ runId: 'run-old', keys: [r1.key], to, how: 'reassign' });
  assert.equal(again.moved, 0);
  assert.match(again.skipped[0]!.reason, /already moved/);
  // API results and named models can never be moved.
  const apiKey = `${api.id}::${T2.definition.id}::c1::r0`;
  assert.match(reassign.reassignResults({ runId: 'run-old', keys: [apiKey], to, how: 'reassign' }).skipped[0]!.reason, /unspecified/);
  // Best on each test shows it as reassigned.
  const e = bestPerTest().tests.find((x) => x.id === T2.definition.id)!.entries.find((x) => x.contestantId === to.id)!;
  assert.equal(e.reassigned, 2);
});

test('a model picked in the Inbox moves the case to that model once it is graded, and is locked after a reply', () => {
  makeRun('run-pick', [generic], '2026-09-20T00:00:00Z');
  const key = `${generic.id}::${T1.definition.id}::c9::r0`;
  const { contestant } = reassign.setChoice({ runId: 'run-pick', key, contestantId: generic.id }, { catalogId: 'gemini-1-5-pro', interface: 'openrouter', thinking: 'off' });
  assert.equal(contestant.id, 'manual.gemini-1-5-pro.openrouter.think-off');
  assert.equal(contestant.vendor, 'Google');
  // Not graded yet: nothing moves, the choice waits.
  assert.equal(reassign.applyPendingChoices('run-pick'), 0);
  assert.ok(reassign.choiceFor('run-pick', key));
  // A reply was submitted: switching model for this conversation is refused.
  reassign.lockChoice('run-pick', key);
  assert.throws(() => reassign.setChoice({ runId: 'run-pick', key, contestantId: generic.id }, { catalogId: 'gpt-4o' }), /already answered/);
  // Graded: moved, marked as picked at paste time (not "reassigned").
  res('run-pick', generic, T1, 'c9', 1, '2026-09-20T01:00:00Z');
  assert.equal(reassign.applyPendingChoices('run-pick'), 1);
  const moved = readResults('run-pick').find((r) => r.contestantId === contestant.id)!;
  assert.equal(moved.manualOrigin?.how, 'paste');
  assert.equal(reassign.choiceFor('run-pick', key), undefined);
  // Named contestants never get a picker.
  assert.throws(() => reassign.setChoice({ runId: 'run-a', key: 'x', contestantId: 'gpt-4o' }, { catalogId: 'gpt-4o' }), /named model/);
});

// ───────────────────────────── Ranking rules ─────────────────────────────

const E = (contestantId: string, score: number | null, n = 1): BestEntry => ({ contestantId, score, ci95: null, n, attempts: n, passRate: null, lastTestedAt: null, runs: 1, reassigned: 0 });

test('Best on each test ranking: ties share a rank, zero never leads, missing scores go last', () => {
  const r = rankEntries([E('c', 0.5), E('a', 0.9), E('b', 0.90004), E('d', null), E('e', 0.2)]);
  assert.deepEqual(r.map((x) => [x.contestantId, x.rank]), [['a', 1], ['b', 1], ['c', 3], ['e', 4], ['d', null]]);
  assert.ok(r[0]!.leader && r[1]!.leader, 'a tie for first: both lead');
  assert.ok(r[0]!.tied && !r[2]!.tied);
  assert.equal(r[4]!.leader, false);
  // More answers first among equal scores.
  assert.deepEqual(rankEntries([E('x', 0.7, 1), E('y', 0.7, 5)]).map((x) => x.contestantId), ['y', 'x']);
  // Everyone scored zero: ranked, but no leader.
  assert.ok(rankEntries([E('p', 0), E('q', 0)]).every((x) => x.rank === 1 && !x.leader));
  assert.deepEqual(rankEntries([]), []);
});
