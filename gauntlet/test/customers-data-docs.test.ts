/**
 * The customers, comms and data document pictures (ui/src/business/docs/customers-data): their parsers must read
 * every case of the real tests (prompt → document model), the answer side must agree with the real scorers
 * (answer → decisions), and the meeting checker must accept every key slot and reject the careless ones.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type { PromptTest } from '../src/core/types.ts';
import type { CaseVisualInput } from '../src/presenter/visuals/common.ts';
import { questionInput } from '../src/presenter/visuals/closeup.ts';
import { compareJson } from '../src/scoring/json-compare.ts';
import { scoreVerifier } from '../src/scoring/verifier.ts';
import { caseVerdict, money, niceDate, sameValue } from '../ui/src/business/docs/customers-data/kit.ts';
import { deadlineSentence, earlierCopy, emailAnswer, highlightJobs, inboxAnswer, lookalike, parseEmailActions, parseInbox } from '../ui/src/business/docs/customers-data/inbox.ts';
import { parseQueue, parseReply, parseTag, queueAnswer, replyAnswer, ruleFor, tagAnswer } from '../ui/src/business/docs/customers-data/support.ts';
import { changedClauses, clauseView, contractAnswer, describe, parseContract, redline } from '../ui/src/business/docs/customers-data/contract.ts';
import { crmAnswer, parseCrm, phoneDigits, sharedFields } from '../ui/src/business/docs/customers-data/crm.ts';
import { parseSheet, sheetAnswer } from '../ui/src/business/docs/customers-data/sheet.ts';
import { matchRows, parseSql, sqlAnswer, tablesIn } from '../ui/src/business/docs/customers-data/sql.ts';
import { layoutSql, tokenizeSql } from '../ui/src/business/docs/customers-data/sqlExtract.ts';
import { clashes, meetingAnswer, parseMeeting, slotUtc } from '../ui/src/business/docs/customers-data/meeting.ts';
import { layoutFromMatrix, parseRota, parseRoutes, rotaAnswer, rotaFlags, routesAnswer } from '../ui/src/business/docs/customers-data/plans.ts';

const load = (id: string): PromptTest => JSON.parse(readFileSync(new URL(`../tests/work/${id}.json`, import.meta.url), 'utf8')) as PromptTest;
const sqlResults = JSON.parse(readFileSync(new URL('../ui/src/business/docs/customers-data/sqlResults.json', import.meta.url), 'utf8')) as Record<string, Record<string, unknown>>;
const scripted = (f: string) => JSON.parse(readFileSync(new URL(`../verification/${f}`, import.meta.url), 'utf8')) as Record<string, Record<string, Record<string, unknown>>>;

function q(t: PromptTest, i: number): CaseVisualInput {
  const c = t.cases[i]!;
  return questionInput(t.id, c.id, { turns: [`${t.preamble ? `${t.preamble}\n\n` : ''}${c.prompt}`], expected: c.expected, notes: c.notes })!;
}
function answered(input: CaseVisualInput, reply: string, detail: Record<string, unknown> = {}): CaseVisualInput {
  return { ...input, replies: [reply], detail, status: 'ok', score: null, passed: null };
}
const asJson = (v: unknown) => `Here is my answer.\n\n\`\`\`json\n${JSON.stringify(v)}\n\`\`\``;

test('kit: verdicts, dates and money in words', () => {
  assert.equal(caseVerdict(1).tone, 'pass');
  assert.equal(caseVerdict(0).tone, 'fail');
  assert.equal(caseVerdict(0.72).title, '72 of 100');
  assert.equal(caseVerdict(null, 'error').tone, 'none');
  assert.equal(niceDate('2026-03-05'), 'Thu 5 Mar');
  assert.equal(niceDate('2026-06-16 12:30'), 'Tue 16 Jun 12:30');
  assert.equal(money(1234.5), '£1,234.50');
  assert.ok(sameValue('P1', 'p1'));
  assert.ok(sameValue(12.5, '£12.50'));
  assert.ok(!sameValue('P1', 'P1 or P2'));
});

test('Monday Inbox: every inbox read; the key scores full marks; scams, copies and deadlines explained', () => {
  const t = load('inbox-triage');
  t.cases.forEach((c, i) => {
    const input = q(t, i);
    const doc = parseInbox(input.turns[0]!, c.expected)!;
    assert.ok(doc, `${c.id} parses`);
    const n = Number(input.turns[0]!.match(/Inbox \((\d+) messages/)![1]);
    assert.equal(doc.mails.length, n, `${c.id}: every message`);
    for (const m of doc.mails) assert.ok(m.fromAddr.includes('@') && m.subject, `${c.id} ${m.id} header`);
    const ans = inboxAnswer(answered(input, asJson(c.expected)), doc)!;
    assert.equal(ans.catRight, doc.mails.length);
    assert.equal(ans.actRight, ans.actTotal);
    for (const [id, cat] of Object.entries(doc.key!.cats)) {
      const m = doc.mails.find((x) => x.id === id)!;
      if (cat === 'phishing') assert.ok(lookalike(doc, m.fromAddr), `${c.id} ${id}: a scam address is not genuine`);
    }
  });
  const c = t.cases[0]!;
  const doc = parseInbox(t.cases[0]!.prompt!, c.expected)!;
  assert.deepEqual(lookalike(doc, 'servicedesk@marlowfleldworks.co.uk'), { fake: 'marlowfleldworks.co.uk', real: 'marlowfieldworks.co.uk' });
  assert.equal(earlierCopy(doc, 'M09'), 'M07');
  assert.match(deadlineSentence(doc.mails.find((m) => m.id === 'M05')!.body)!, /close of play tomorrow/);
  // The careless player's answer: the marks agree with the real scorer field by field.
  const careless = scripted('realworld-work/scripted.json')['work.inbox-triage']![c.id]!.careless;
  const ans = inboxAnswer(answered(q(t, 0), asJson(careless)), doc)!;
  const real = compareJson(c.expected, careless, { lenient: true, sets: { actions: 3 } });
  const wrongCats = real.items.filter((it) => /^messages\.M\d+\.category/.test(it.label) && !it.passed).length;
  assert.equal(ans.marks.filter((m) => m.catOk === false).length, wrongCats);
});

test('What Do I Need to Do?: thread, jobs and highlighted sentences', () => {
  const t = load('email-actions');
  t.cases.forEach((c, i) => {
    const input = q(t, i);
    const doc = parseEmailActions(input.turns[0]!, c.expected, c.notes)!;
    assert.ok(doc && doc.jobs.length >= 3, `${c.id} jobs`);
    assert.ok(doc.jobs.every((j) => j.key && j.why), `${c.id} key and reason for every job`);
    const marks = emailAnswer(answered(input, asJson(c.expected)), doc)!;
    assert.ok(marks.every((m) => m.mineOk && m.dueOk), `${c.id} key is all right`);
  });
  const doc = parseEmailActions(t.cases[7]!.prompt!, t.cases[7]!.expected, t.cases[7]!.notes)!;
  assert.ok(doc.messages.some((m) => m.forwarded), 'a forwarded message is marked');
  const hl = highlightJobs(doc.messages[0]!.body, doc.jobs);
  assert.ok(hl.some((s) => s.job === 'T1'), 'the upload request is highlighted as T1');
});

test('Customer support queue and ladder: tickets, orders, handbook; key and careless marks', () => {
  for (const id of ['support-refunds', 'support-ladder']) {
    const t = load(id);
    t.cases.forEach((c, i) => {
      const input = q(t, i);
      const doc = parseQueue(input.turns[0]!, c.expected)!;
      const keyIds = Object.keys((c.expected as { tickets: object }).tickets);
      assert.equal(doc.tickets.length, keyIds.length, `${id} ${c.id}: every ticket`);
      assert.ok(doc.tickets.every((tk) => !/^[A-Z]{2}-\d+$/.test(tk.orderId) || doc.orders[tk.orderId]), `${id} ${c.id}: every ticket's order in the lookup`);
      assert.ok(doc.rules['4.2'] && doc.rules['4.12'], `${id} ${c.id}: handbook sections`);
      const ans = queueAnswer(answered(input, asJson(c.expected)), doc)!;
      assert.equal(ans.right, doc.tickets.length);
      for (const tk of doc.tickets) assert.ok(ruleFor(doc, tk, doc.key![tk.id.replace('#', '')]!), `${id} ${c.id} ${tk.id}: a rule`);
    });
  }
  const t = load('support-refunds');
  const c = t.cases[0]!;
  const careless = scripted('realworld-business/scripted.json')['work.support-refunds']![c.id]!.careless;
  const doc = parseQueue(c.prompt!, c.expected)!;
  const ans = queueAnswer(answered(q(t, 0), asJson(careless)), doc)!;
  const real = compareJson(c.expected, careless, { lenient: true, numberTolerance: 1e-8 });
  assert.equal(ans.marks.reduce((n, m) => n + Object.values(m.ok!).filter((x) => !x).length, 0), real.items.filter((x) => !x.passed).length);
});

test('Tag the Ticket and Reply Within Policy: the documents and the real reply checks', () => {
  const tt = load('ticket-tag');
  tt.cases.forEach((c, i) => {
    const doc = parseTag(q(tt, i).turns[0]!, c.expected, c.notes)!;
    assert.ok(doc.body && doc.products.length && doc.rubric.urgency, `${c.id} ticket`);
    assert.ok(tagAnswer(answered(q(tt, i), asJson(c.expected)), doc)!.every((m) => m.ok));
  });
  const tr = load('support-replies');
  tr.cases.forEach((c, i) => {
    const input = q(tr, i);
    const doc = parseReply(input.turns[0]!, c.expected, c.notes)!;
    assert.ok(doc && doc.order.lines.length && doc.email.body && doc.key && doc.why, `${c.id} reply case`);
    const ref = (c.expected as { reference: unknown }).reference;
    const ans = replyAnswer(answered(input, asJson(ref)), doc)!;
    assert.equal(ans.score, scoreVerifier('reply', c.expected, asJson(ref), input.turns[0]!).score);
    assert.ok(ans.items.every((it) => it.passed), `${c.id} reference passes every check`);
  });
});

test('Contracts: clauses of both versions, redline, the key and invented changes', () => {
  for (const id of ['contract-redline', 'contract-ladder']) {
    const t = load(id);
    t.cases.forEach((c, i) => {
      const input = q(t, i);
      const doc = parseContract(input.turns[0]!, c.expected)!;
      assert.ok(doc.v1.length > 10 && doc.v2.length > 10, `${id} ${c.id} clauses`);
      assert.ok(changedClauses(doc).length > 0);
      const ans = contractAnswer(answered(input, asJson(c.expected)), doc)!;
      assert.equal(ans.right, Object.keys(doc.key!).length, `${id} ${c.id} key all right`);
      for (const no of Object.values(doc.keyNo)) if (!doc.ladder) assert.ok(clauseView(doc, no), `${c.id} clause ${no} found`);
    });
  }
  const pieces = redline('within thirty (30) days', 'within 60 days');
  assert.deepEqual(pieces.filter((p) => p.op !== 'same').map((p) => p.op), ['del', 'ins']);
  assert.equal(describe({ type: 'amount', old: 250000, new: 1000000 }), '£250,000 → £1,000,000');
  const t = load('contract-redline');
  const c = t.cases[0]!;
  const doc = parseContract(c.prompt!, c.expected)!;
  const ans = contractAnswer(answered(q(t, 0), asJson({ changes: { ...(c.expected as { changes: object }).changes, '6.1': { type: 'amount', old: 2000000, new: 2000000 } } })), doc)!;
  assert.equal(ans.invented, 1);
});

test('Customer list: records, linking fields, and right / wrong-merge / left-in groups', () => {
  for (const id of ['crm-dedupe', 'crm-ladder']) {
    const t = load(id);
    t.cases.forEach((c, i) => {
      const input = q(t, i);
      const doc = parseCrm(input.turns[0]!, c.expected)!;
      const n = Number(input.turns[0]!.match(/(\d+) records/)?.[1] ?? doc.records.length);
      assert.ok(doc.records.length >= Math.min(n, 20), `${id} ${c.id} records`);
      const ans = crmAnswer(answered(input, asJson(c.expected)), doc)!;
      assert.equal(ans.rightGroups, doc.key!.clusters.length, `${id} ${c.id} key groups all right`);
      assert.equal(ans.goldenRight, doc.key!.clusters.length);
    });
  }
  assert.equal(phoneDigits('+44 7700 900123'), '07700900123');
  const t = load('crm-dedupe');
  const c = t.cases[0]!;
  const doc = parseCrm(c.prompt!, c.expected)!;
  const key = c.expected as { clusters: string[][]; golden: object };
  // Merge the first two groups and drop the third: one wrong merge, one duplicate left in.
  const bad = { clusters: [[...key.clusters[0]!, ...key.clusters[1]!], ...key.clusters.slice(3)], golden: key.golden };
  const ans = crmAnswer(answered(q(t, 0), asJson(bad)), doc)!;
  assert.equal(ans.wrongMerges, 1);
  assert.equal(ans.leftIn, 1);
  assert.ok(sharedFields(key.clusters[0]!.map((x) => doc.records.find((r) => r.id === x)!)).size > 0);
});

test('Spreadsheet: rows, traps and the rows each question reads; naive answers recognised', () => {
  const t = load('sheet-lookup');
  t.cases.forEach((c, i) => {
    const input = q(t, i);
    const doc = parseSheet(input.turns[0]!, c.expected, c.notes)!;
    assert.ok(doc.rows.length >= 40 && doc.questions.length >= 1, `${c.id} sheet`);
    for (const qq of doc.questions) assert.ok(qq.rows.length > 0, `${c.id} ${qq.id} reads rows`);
    if (/duplicated/.test(c.notes ?? '')) assert.ok(doc.rows.some((r) => r.trap === 'repeat'), `${c.id} repeated row found`);
    if (/subtotal rows/.test(c.notes ?? '')) assert.ok(doc.rows.some((r) => r.trap === 'subtotal'));
    assert.ok(sheetAnswer(answered(input, asJson(c.expected)), doc)!.every((m) => m.ok));
    const naive = sheetAnswer(answered(input, asJson(doc.naive)), doc)!;
    assert.ok(naive.filter((m) => !m.ok).every((m) => m.trap), `${c.id}: a naive answer is explained`);
  });
});

test('SQL: question, schema with joins, correct result tables, and per-database verdicts', () => {
  for (const id of ['sql-questions', 'sql-hard', 'sql-ladder']) {
    const t = load(id);
    t.cases.forEach((c, i) => {
      const input = q(t, i);
      const doc = parseSql(input, sqlResults)!;
      assert.ok(doc && doc.question && doc.asker, `${id} ${c.id} question`);
      assert.ok(doc.tables.length >= 3 && doc.tables.some((x) => x.columns.some((col) => col.ref)), `${id} ${c.id} schema with joins`);
      assert.ok(tablesIn(doc.reference, doc.tables).length >= 1);
      assert.ok(doc.correct && doc.correct.rows.length > 0, `${id} ${c.id} correct result`);
    });
  }
  const ans = sqlAnswer(answered(q(load('sql-questions'), 0), '```sql\nSELECT 1\n```', { items: [{ label: 'live database (sampled in the prompt)', passed: true }, { label: 'hidden copy A', passed: false, detail: '0 of 1 rows right' }] }))!;
  assert.equal(ans.sql, 'SELECT 1');
  assert.equal(ans.right, 1);
  assert.deepEqual(matchRows([['a', 1], ['b', 2]], [['b', 2.004], ['c', 3]]), { gotOk: [true, false], missing: [0] });
  assert.ok(tokenizeSql('SELECT x FROM t').some((k) => k.k === 'kw'));
  assert.match(layoutSql('SELECT a, b FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.status = 1'), /\nFROM[^\n]*\nJOIN/);
});

test('Find a Slot: every key slot keeps every rule; careless slots are explained', () => {
  const t = load('meeting-scheduler');
  const careless = scripted('realworld-work/scripted.json')['work.meeting-scheduler']!;
  t.cases.forEach((c, i) => {
    const input = q(t, i);
    const doc = parseMeeting(input.turns[0]!, c.expected)!;
    assert.ok(doc && doc.people.length >= 2 && doc.meetings.length >= 4, `${c.id} parses`);
    const booked: Array<{ m: (typeof doc.meetings)[number]; at: number }> = [];
    for (const mt of doc.meetings) {
      const at = slotUtc(doc, doc.key![mt.id]);
      assert.ok(at !== null, `${c.id} ${mt.id} key slot`);
      assert.deepEqual(clashes(doc, mt, at!, doc.inOrder ? booked : []), [], `${c.id} (${mt.id}) at ${doc.key![mt.id]}`);
      booked.push({ m: mt, at: at! });
    }
    const slip = careless[c.id]?.careless as Record<string, string | null> | undefined;
    if (slip) {
      const marks = meetingAnswer(answered(input, asJson(slip)), doc)!;
      for (const m of marks) if (!m.ok) assert.ok(m.why, `${c.id} (${m.id}) ${m.model}: explained`);
    }
  });
});

test('Van routes: a map from the travel times; the best plan is legal and optimal; a late drop is flagged', () => {
  const t = load('delivery-routes');
  t.cases.forEach((c, i) => {
    const input = q(t, i);
    const doc = parseRoutes(input)!;
    assert.equal(doc.pos.length, doc.world.drops.length + 1);
    assert.ok(doc.pos.every(([x, y]) => x >= 0 && x <= 1 && y >= 0 && y <= 1));
    const ref = (c.expected as { reference: unknown }).reference;
    const ans = routesAnswer(answered(input, asJson(ref)), doc)!;
    assert.equal(ans.total, doc.optimum, `${c.id}: the reference plan is the optimum`);
    assert.equal(ans.late.size, 0);
  });
  // Nearer places sit nearer on the map.
  const p = layoutFromMatrix([[0, 10, 100], [10, 0, 95], [100, 95, 0]]);
  const d = (a: number, b: number) => Math.hypot(p[a]![0] - p[b]![0], p[a]![1] - p[b]![1]);
  assert.ok(d(0, 1) < d(0, 2));
  // Reversing a route with a time window misses it.
  for (const [i, c] of t.cases.entries()) {
    const input = q(t, i);
    const doc = parseRoutes(input)!;
    const ref = structuredClone((c.expected as { reference: { routes: Record<string, string[]> } }).reference);
    for (const v of Object.keys(ref.routes)) ref.routes[v]!.reverse();
    const ans = routesAnswer(answered(input, asJson(ref)), doc)!;
    if (ans.outcome.detail.verdict === 'infeasible' && ans.replays.some((x) => x.r.problems.some((pr) => /window closes/.test(pr)))) {
      assert.ok(ans.late.size > 0, `${c.id}: the late drops are marked`);
      return;
    }
  }
});

test('Rota: the cheapest legal rota has no flagged cell; a broken one is flagged on its cells', () => {
  const t = load('shift-rota');
  t.cases.forEach((c, i) => {
    const input = q(t, i);
    const doc = parseRota(input)!;
    const ref = (c.expected as { reference: unknown }).reference;
    const ans = rotaAnswer(answered(input, asJson(ref)), doc)!;
    assert.equal(ans.cost, doc.optimum, `${c.id}: reference is the optimum`);
    assert.deepEqual([ans.cells.length, ans.shifts.length, Object.keys(ans.hours).length], [0, 0, 0], `${c.id}: nothing flagged`);
  });
  const doc = parseRota(q(t, 0))!;
  const s0 = doc.world.shifts[0]!;
  const broken = new Map(doc.best);
  broken.set(s0.id, []);
  const f = rotaFlags(doc.world, broken);
  assert.ok(f.shifts.some((x) => x.sid === s0.id), 'an empty shift is flagged');
});
