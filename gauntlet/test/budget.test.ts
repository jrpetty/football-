import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Isolate run storage, config and the test library before any Gauntlet module is loaded.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-budget-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  for (let i = 0; i < 2; i++) models.contestants.push({ id: `bot-${i}`, label: `Bot ${i}`, vendor: 'Test', provider: 'baseline', model: `random-${i}`, color: '#123456', enabled: true, pricing: { inputPerM: 0, outputPerM: 0 } });
  writeFileSync(file, JSON.stringify(models));
}
mkdirSync(join(sandbox, 'tests', 'math'), { recursive: true });
writeFileSync(
  join(sandbox, 'tests', 'math', 'arith.json'),
  JSON.stringify({ kind: 'prompt', id: 'math.arith', version: '1.0.0', name: 'Arithmetic', category: 'math', description: 'Tiny arithmetic', difficulty: 'easy', scorer: { type: 'number' }, cases: [{ id: 'c1', prompt: 'What is 1 + 1?', expected: 2 }] }),
);

const B = await import('../src/budget/budget.ts');
const spend = await import('../src/budget/spend.ts');
const routes = await import('../src/budget/routes.ts');
const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const arena = await import('../src/arena/tournament.ts');

const GBP = { code: 'GBP' as const, usdPerUnit: 1.25 };
const DATA = join(sandbox, 'data');

/** A stored run with hand-written results (only the fields spend tracking reads). */
function fakeRun(id: string, o: { status?: string; createdAt: string; finishedAt?: string; cap?: number; unrecorded?: number; contestants?: Array<{ id: string; provider: string }>; lines: Array<{ key: string; contestantId?: string; startedAt: string; cost: number; judge?: number }> }) {
  const dir = join(DATA, 'runs', id);
  mkdirSync(dir, { recursive: true });
  const manifest = {
    id,
    name: `Run ${id}`,
    status: o.status ?? 'completed',
    createdAt: o.createdAt,
    startedAt: o.createdAt,
    finishedAt: o.finishedAt ?? (o.status === 'running' ? undefined : o.createdAt),
    contestants: o.contestants ?? [{ id: 'api-model', provider: 'openai' }],
    tests: [],
    judges: [],
    settings: { repeats: 1, concurrency: 1, temperature: 0, protocolVersion: 'x', ...(o.cap !== undefined ? { maxCostUsd: o.cap } : {}) },
    totalJobs: o.lines.length,
    ...(o.unrecorded ? { unrecordedCostUsd: o.unrecorded } : {}),
  };
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify(manifest));
  writeFileSync(join(dir, 'results.jsonl'), o.lines.map((l) => JSON.stringify({ key: l.key, contestantId: l.contestantId ?? 'api-model', startedAt: l.startedAt, metrics: { costUsd: l.cost, judgeCostUsd: l.judge ?? 0 } })).join('\n') + '\n');
}

function fakeTournament(id: string, o: { createdAt: string; status?: string; entrants: Array<{ id: string; manual?: boolean; baseline?: boolean }>; games: Array<{ key: string; players: [string, string]; startedAt: string; costs: [number, number]; judge?: number; amends?: boolean }> }) {
  const dir = join(DATA, 'arena', id);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'manifest.json'), JSON.stringify({ id, name: `Cup ${id}`, status: o.status ?? 'completed', createdAt: o.createdAt, finishedAt: o.createdAt, entrants: o.entrants, settings: {} }));
  writeFileSync(
    join(dir, 'games.jsonl'),
    o.games.map((g) => JSON.stringify({ key: g.key, players: g.players, startedAt: g.startedAt, metrics: [{ costUsd: g.costs[0] }, { costUsd: g.costs[1] }], ...(g.judge ? { judging: { costUsd: g.judge } } : {}), ...(g.amends ? { amends: true } : {}) })).join('\n') + '\n',
  );
}

const local = (y: number, m: number, d: number, h = 12, min = 0) => new Date(y, m - 1, d, h, min);

test('month boundaries: calendar month in local time, and the next reset date', () => {
  const b = B.monthBounds(local(2026, 9, 30, 23, 59));
  assert.equal(b.key, '2026-09');
  assert.equal(b.label, 'September 2026');
  assert.equal(b.nextResetLabel, '1 October 2026');
  assert.equal(b.start.getTime(), local(2026, 9, 1, 0).getTime());
  assert.equal(b.nextReset.getTime(), local(2026, 10, 1, 0).getTime());
  assert.ok(B.inMonth(local(2026, 9, 1, 0, 0).toISOString(), b), 'midnight on the 1st belongs to the new month');
  assert.ok(B.inMonth(local(2026, 9, 30, 23, 59).toISOString(), b));
  assert.ok(!B.inMonth(local(2026, 10, 1, 0, 0).toISOString(), b), 'the reset moment starts the next month');
  assert.ok(!B.inMonth(local(2026, 8, 31, 23, 59).toISOString(), b));
  assert.ok(!B.inMonth(undefined, b));
  const dec = B.monthBounds(local(2026, 12, 15));
  assert.equal(dec.nextResetLabel, '1 January 2027', 'December rolls over into the next year');
  assert.equal(B.localDate(local(2026, 9, 5, 23, 30)), '2026-09-05', 'days are local, not UTC');
});

test('settings validation: amounts, clearing, hard stop needs a monthly budget, junk rejected', () => {
  assert.deepEqual(B.checkBudgetInput({ monthlyUsd: 66.5, hardStop: true }), { monthlyUsd: 66.5, hardStop: true });
  assert.deepEqual(B.checkBudgetInput({ defaultRunUsd: 13.3 }, { monthlyUsd: 66.5 }), { monthlyUsd: 66.5, defaultRunUsd: 13.3 }, 'a missing field keeps its value');
  assert.deepEqual(B.checkBudgetInput({ monthlyUsd: null, hardStop: false }, { monthlyUsd: 66.5, hardStop: true }), {}, 'null clears');
  assert.match(B.checkBudgetInput({ monthlyUsd: -5 }) as string, /above zero/);
  assert.match(B.checkBudgetInput({ monthlyUsd: '50' }) as string, /above zero/);
  assert.match(B.checkBudgetInput({ monthlyUsd: 5e9 }) as string, /typo/);
  assert.match(B.checkBudgetInput({ hardStop: 'yes' }) as string, /true or false/);
  assert.match(B.checkBudgetInput({ hardStop: true }) as string, /needs a monthly budget/);
  assert.match(B.checkBudgetInput({ monthlyUsd: null }, { monthlyUsd: 10, hardStop: true }) as string, /needs a monthly budget/);
  assert.match(B.checkBudgetInput({ defaultRunUsd: 5, defaultPerAnswerUsd: 6 }) as string, /per-answer/);
  assert.match(B.checkBudgetInput({ weekly: 5 }) as string, /Unknown budget setting: weekly/);
  assert.match(B.checkBudgetInput([1]) as string, /object/);
  // Stored junk never breaks the app: bad values are dropped.
  assert.deepEqual(B.normalizeBudget({ monthlyUsd: 'lots', defaultRunUsd: 10, hardStop: 'on' }), { defaultRunUsd: 10 });
  assert.deepEqual(B.normalizeBudget(undefined), {});
});

test('meter colours: green, amber from 80 %, red at 100 %', () => {
  assert.equal(B.meterTone(null), 'none');
  assert.equal(B.meterTone(0.79), 'ok');
  assert.equal(B.meterTone(0.8), 'warn');
  assert.equal(B.meterTone(0.999), 'warn');
  assert.equal(B.meterTone(1), 'over');
  const s = B.summarize({ monthlyUsd: 50, hardStop: true }, 40, 6);
  assert.deepEqual({ ...s }, { remainingUsd: 10, availableUsd: 4, fraction: 0.8, tone: 'warn', blocked: false });
  assert.equal(B.summarize({ monthlyUsd: 50, hardStop: true }, 45, 5).blocked, true, 'money reserved by running jobs counts as used');
  assert.equal(B.summarize({ monthlyUsd: 50 }, 60, 0).blocked, false, 'without the hard stop nothing is blocked');
  assert.equal(B.summarize({}, 60, 0).remainingUsd, null);
});

test('clamping a run limit to what is left, the hard stop and warnings', () => {
  const month = B.monthBounds(local(2026, 9, 10));
  const status = (settings: object, spentUsd: number, committed = 0) => ({ settings, spentUsd, month: { ...month, start: '', nextReset: '' }, ...B.summarize(settings, spentUsd, committed) });
  // No monthly budget: untouched.
  assert.deepEqual(B.gateLimit(10, status({}, 999), GBP), { capUsd: 10, clamped: false });
  // Hard stop on, £50 (= $62.50) a month, $47 spent: $15.50 left.
  const s = status({ monthlyUsd: 62.5, hardStop: true }, 47);
  const g = B.gateLimit(undefined, s, GBP);
  assert.equal(g.capUsd, 15.5);
  assert.ok(g.clamped);
  assert.equal(g.message, "Limited to £12.40: what's left of your £50.00 monthly budget.");
  assert.equal(B.gateLimit(30, s, GBP).capUsd, 15.5, 'a limit above what is left is lowered');
  assert.deepEqual(B.gateLimit(5, s, GBP), { capUsd: 5, clamped: false }, 'a limit below what is left is kept');
  // Clamps round down to whole cents so they never exceed the money left.
  assert.equal(B.gateLimit(undefined, status({ monthlyUsd: 10, hardStop: true }, 3.337), GBP).capUsd, 6.66);
  // Resume: the limit covers what the run already spent, so the cap is spent-so-far + what is left.
  assert.equal(B.gateLimit(undefined, s, GBP, 4).capUsd, 19.5);
  assert.deepEqual(B.gateLimit(18, s, GBP, 4), { capUsd: 18, clamped: false });
  // Used up: refused, with the reset date.
  assert.throws(() => B.gateLimit(5, status({ monthlyUsd: 62.5, hardStop: true }, 62.5), GBP), (e: Error) => B.isBudgetBlockedError(e) && /used up/.test(e.message) && /1 October 2026/.test(e.message) && /start/.test(e.message));
  assert.throws(() => B.gateLimit(5, status({ monthlyUsd: 62.5, hardStop: true }, 40, 22.5), GBP), /used up/, 'running jobs’ reserved money counts');
  assert.throws(() => B.gateLimit(5, status({ monthlyUsd: 62.5, hardStop: true }, 70), GBP, 3), /resume/);
  // Hard stop off: warnings only, the limit is never changed.
  const w = B.gateLimit(undefined, status({ monthlyUsd: 62.5 }, 70), GBP);
  assert.equal(w.capUsd, undefined);
  assert.match(w.message!, /used up.*starts anyway/);
  assert.match(B.gateLimit(30, status({ monthlyUsd: 62.5 }, 50), GBP).message!, /more than the £10.00 left/);
  assert.equal(B.gateLimit(5, status({ monthlyUsd: 62.5 }, 50), GBP).message, undefined);
  // The compact New Run line.
  assert.equal(B.budgetLine(s, GBP, null), 'This month: £37.60 of £50.00 spent · this run up to £12.40');
  assert.equal(B.budgetLine(s, GBP, 5), 'This month: £37.60 of £50.00 spent · this run up to £4.00');
  assert.equal(B.budgetLine(status({}, 5), GBP, null, 'tournament'), 'This month: £4.00 spent · this tournament has no limit');
});

test('spend this month: sums runs, re-tries, Arena, judges and one-off calls; Manual and Random cost nothing', () => {
  const now = local(2026, 9, 20);
  const sep = (d: number, h = 12) => local(2026, 9, d, h).toISOString();
  fakeRun('r-sep', {
    createdAt: sep(3),
    finishedAt: sep(4),
    unrecorded: 0.5,
    contestants: [{ id: 'api-model', provider: 'openai' }, { id: 'manual-chat', provider: 'manual' }, { id: 'random-baseline', provider: 'baseline' }],
    lines: [
      { key: 'a', startedAt: sep(3), cost: 1, judge: 0.25 },
      // A human re-score appends a copy of the same attempt: counted once.
      { key: 'a', startedAt: sep(3), cost: 1, judge: 0.25 },
      // A replayed (errored) attempt is a second, real spend.
      { key: 'b', startedAt: sep(3), cost: 0.4 },
      { key: 'b', startedAt: sep(4), cost: 0.6 },
      // Manual and Random contestants cost nothing; their AI judges still count.
      { key: 'm', contestantId: 'manual-chat', startedAt: sep(4), cost: 9, judge: 0.1 },
      { key: 'z', contestantId: 'random-baseline', startedAt: sep(4), cost: 9 },
    ],
  });
  // Started in August, resumed in September: only the September spending counts this month.
  fakeRun('r-aug', { createdAt: local(2026, 8, 30).toISOString(), finishedAt: sep(2), lines: [{ key: 'x', startedAt: local(2026, 8, 30).toISOString(), cost: 5 }, { key: 'y', startedAt: sep(2), cost: 2 }] });
  // Finished last month: skipped.
  fakeRun('r-old', { createdAt: local(2026, 7, 1).toISOString(), finishedAt: local(2026, 7, 2).toISOString(), lines: [{ key: 'x', startedAt: local(2026, 7, 1).toISOString(), cost: 50 }] });
  // Running with a $10 limit and $1 spent: $9 still reserved.
  fakeRun('r-live', { status: 'running', createdAt: sep(20, 9), cap: 10, lines: [{ key: 'x', startedAt: sep(20, 9), cost: 1 }] });
  fakeTournament('t-sep', {
    createdAt: sep(10),
    entrants: [{ id: 'p1' }, { id: 'p2' }, { id: 'me', manual: true }],
    games: [
      { key: 'g1', players: ['p1', 'p2'], startedAt: sep(10), costs: [0.3, 0.2], judge: 0.05 },
      { key: 'g2', players: ['p1', 'me'], startedAt: sep(11), costs: [0.1, 7] },
      // A human verdict amends a game: it cost nothing new.
      { key: 'g1', players: ['p1', 'p2'], startedAt: sep(10), costs: [0.3, 0.2], judge: 0.05, amends: true },
    ],
  });
  spend.recordSpend({ kind: 'grade', name: 'AI judges graded a pasted answer', costUsd: 0.07, at: sep(12) });
  spend.recordSpend({ kind: 'polish', name: 'Script polish', costUsd: 0.03, at: local(2026, 8, 12).toISOString() });
  spend.recordSpend({ kind: 'grade', name: 'free', costUsd: 0 });

  const m = spend.monthSpend(now);
  const byId = new Map(m.items.map((i) => [i.id ?? i.name, i.spentUsd]));
  assert.equal(byId.get('r-sep'), 1 + 0.25 + 0.4 + 0.6 + 0.1 + 0.5);
  assert.equal(byId.get('r-aug'), 2);
  assert.equal(byId.has('r-old'), false);
  assert.equal(byId.get('r-live'), 1);
  assert.equal(byId.get('t-sep'), 0.55 + 0.1);
  assert.equal(byId.get('AI judges graded a pasted answer'), 0.07);
  assert.equal(byId.has('Script polish'), false, 'last month’s one-off call is not counted');
  assert.ok(Math.abs(m.spentUsd - (2.85 + 2 + 1 + 0.65 + 0.07)) < 1e-9);
  assert.equal(m.committedUsd, 9);
  assert.equal(m.items[0]!.id, 'r-live', 'newest first');
  assert.ok(m.items.find((i) => i.id === 'r-live')!.active);
  const days = new Map(m.days.map((d) => [d.date, d.spentUsd]));
  assert.equal(days.get('2026-09-02'), 2);
  assert.ok(Math.abs(days.get('2026-09-03')! - (1.25 + 0.4 + 0.5)) < 1e-9, 'unrecorded spend is dated at the run start');
  assert.ok(Math.abs(days.get('2026-09-04')! - 0.7) < 1e-9);
  assert.ok(Math.abs(m.days.reduce((s, d) => s + d.spentUsd, 0) - m.spentUsd) < 1e-9, 'the per-day totals add up to the month');
  // A whole run's spend (all months) is what its limit covers on resume.
  assert.equal(spend.runSpentUsd('r-aug'), 7);
  assert.equal(spend.tournamentSpentUsd('t-sep'), 0.65);
  // In October, September's spending no longer counts.
  const oct = spend.monthSpend(local(2026, 10, 2));
  assert.equal(oct.spentUsd, 0);
  assert.equal(oct.committedUsd, 9, 'a run still running keeps its reservation into the new month');
});

test('server-side enforcement at run start and on resume', async () => {
  const nowIso = new Date().toISOString();
  routes.saveBudget({});
  const plain = await runner.startRun({ contestantIds: ['random-baseline'], testIds: ['math.arith'], repeats: 1 });
  await runner.waitForRun(plain);
  assert.equal(store.readManifest(plain)!.settings.maxCostUsd, undefined, 'no budget: nothing changes');

  // Whatever earlier fixtures spent or reserve this month is already used; leave exactly $3 on top of it.
  const used = () => {
    const s = spend.budgetStatus();
    return s.spentUsd + s.committedUsd;
  };
  // Hard stop with $3 left: an unlimited run is limited to $3 and the manifest says why.
  routes.saveBudget({ monthlyUsd: Math.round((used() + 3) * 1e6) / 1e6, hardStop: true, defaultRunUsd: 10 });
  const clamped = await runner.startRun({ contestantIds: ['random-baseline'], testIds: ['math.arith'], repeats: 1 });
  await runner.waitForRun(clamped);
  const cm = store.readManifest(clamped)!;
  assert.equal(cm.settings.maxCostUsd, 3);
  assert.match(cm.settings.limits!.budgetNote!, /^Limited to .*: what's left of your .* monthly budget\.$/);
  // A lower limit is kept.
  const low = await runner.startRun({ contestantIds: ['random-baseline'], testIds: ['math.arith'], repeats: 1, maxCostUsd: 1 });
  await runner.waitForRun(low);
  assert.equal(store.readManifest(low)!.settings.maxCostUsd, 1);

  // Spend the rest of the month (another run's recorded cost): nothing may start or resume.
  fakeRun('r-spent-now', { createdAt: nowIso, lines: [{ key: 'k', startedAt: nowIso, cost: 3 }] });
  assert.equal(spend.budgetStatus().blocked, true);
  await assert.rejects(runner.startRun({ contestantIds: ['random-baseline'], testIds: ['math.arith'], repeats: 1 }), (e: Error) => B.isBudgetBlockedError(e) && /used up/.test(e.message));
  assert.throws(() => runner.resumeRun(plain), (e: Error) => B.isBudgetBlockedError(e) && /resume/.test(e.message));
  assert.throws(() => arena.startTournament({ game: 'connect4', contestantIds: ['bot-0', 'bot-1'], seeding: 'manual' }), (e: Error) => B.isBudgetBlockedError(e) && /tournament/.test(e.message), 'Arena tournaments are held to the same budget');

  // Hard stop off: it starts anyway (warnings only) with the limit it asked for.
  routes.saveBudget({ monthlyUsd: spend.budgetStatus().settings.monthlyUsd });
  const warned = await runner.startRun({ contestantIds: ['random-baseline'], testIds: ['math.arith'], repeats: 1 });
  await runner.waitForRun(warned);
  assert.equal(store.readManifest(warned)!.settings.maxCostUsd, undefined);

  // Raise the budget by $2 with the hard stop on: resuming caps the run at what it already spent ($0) + $2.
  routes.saveBudget({ monthlyUsd: Math.round((used() + 2) * 1e6) / 1e6, hardStop: true });
  runner.resumeRun(plain);
  await runner.waitForRun(plain);
  assert.equal(store.readManifest(plain)!.settings.maxCostUsd, 2);

  // A tournament starting with $2 left is limited to $2 too.
  const tid = arena.startTournament({ game: 'connect4', contestantIds: ['bot-0', 'bot-1'], seeding: 'manual', gamesPerMatch: 2 });
  assert.equal(arena.tournamentDetail(tid)!.manifest.settings.maxCostUsd, 2);
  assert.match(arena.tournamentDetail(tid)!.manifest.settings.budgetNote!, /^Limited to/);
  await arena.waitForTournament(tid);
});
