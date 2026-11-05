/**
 * Customer service tests read from the prompt: the helpdesk queue with its order lookup and handbook
 * (work.support-refunds, work.support-ladder), one tagged ticket (work.ticket-tag) and one reply within policy
 * (work.support-replies); the model's decisions against the key. Pure; tested in test/customers-data-docs.test.ts.
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { scoreVerifier } from '../../../../../src/scoring/verifier.ts';
import type { VerifierItem } from '../../../../../src/scoring/verifiers/types.ts';
import { byId, finalReply, normSetId, pick, replyJson, sameValue } from './kit.ts';

// ───────────────────────────── the queue ─────────────────────────────

export interface OrderLine {
  sku: string;
  name: string;
  qty: string;
  price: string;
}

export interface Order {
  id: string;
  email: string;
  placed: string;
  paidBy: string;
  lines: OrderLine[];
  /** "Delivery: …", "Status: …", "Account claims history: …", "Discount code: …" and any other facts. */
  facts: Array<{ k: string; v: string }>;
}

export interface Ticket {
  id: string;
  received: string;
  channel: string;
  previous: string;
  reopened: string | null;
  from: string;
  orderId: string;
  body: string;
}

export interface TicketDecision {
  category: string;
  priority: string;
  sla_due: string;
  decision: string;
  refund: number | null;
}

export interface QueueDoc {
  /** "Wed 15 Jul 2026" from the subject line. */
  day: string;
  /** Handbook sections by number ("4.2" → text). */
  rules: Record<string, string>;
  stock: string;
  orders: Record<string, Order>;
  tickets: Ticket[];
  key: Record<string, TicketDecision> | null;
}

export function parseQueue(prompt: string, expected?: unknown): QueueDoc | null {
  const day = (prompt.match(/Subject: Queue for ([^-\n]+?)\s*-/) ?? [])[1] ?? '';
  const rules: Record<string, string> = {};
  for (const m of prompt.matchAll(/^(4\.\d+)\s+([\s\S]*?)(?=\n\n|\n4\.\d+\s)/gm)) rules[m[1]!] = m[2]!.replace(/\s+/g, ' ').trim();
  const stock = (prompt.match(/^STOCK[^:]*:\s*(.+)$/m) ?? [])[1] ?? '';
  const orders: Record<string, Order> = {};
  const lookup = prompt.split(/^ORDER LOOKUP\s*$/m)[1]?.split(/^--- Ticket/m)[0] ?? '';
  let cur: Order | null = null;
  for (const line of lookup.split('\n')) {
    const head = line.match(/^ {2}([A-Z]{2}-\d+)\s+(\S+)\s+placed\s+(.+?)\s{2,}paid by\s+(.+)$/);
    if (head) {
      cur = { id: head[1]!, email: head[2]!, placed: head[3]!, paidBy: head[4]!.trim(), lines: [], facts: [] };
      orders[cur.id] = cur;
      continue;
    }
    if (!cur) continue;
    const item = line.match(/^ {4,}([A-Z]{2}-\w+)\s+(.+?)\s{2,}(x\d+)\s+(£[\d,.]+.*)$/);
    if (item) {
      cur.lines.push({ sku: item[1]!, name: item[2]!.trim(), qty: item[3]!, price: item[4]!.trim() });
      continue;
    }
    const fact = line.match(/^ {2,}([A-Z][^:]{2,40}):\s*(.+)$/);
    if (fact) cur.facts.push({ k: fact[1]!.trim(), v: fact[2]!.trim() });
  }
  const tickets: Ticket[] = [];
  const parts = prompt.split(/^--- Ticket /m).slice(1);
  for (const part of parts) {
    const lines = part.split('\n');
    const h = lines[0]!.split('|').map((x) => x.trim());
    const id = h[0]!;
    const from = lines[1] ?? '';
    const fm = from.match(/^From:\s*(.*?)\s+Order:\s*(.+?)\s*$/);
    let bodyLines = lines.slice(2);
    const stop = bodyLines.findIndex((l) => /^YOUR ANSWER\b/.test(l));
    if (stop >= 0) bodyLines = bodyLines.slice(0, stop);
    tickets.push({
      id,
      received: (h[1] ?? '').replace(/^received\s*/, ''),
      channel: (h[2] ?? '').replace(/^via\s*/, ''),
      previous: (h[3] ?? '').replace(/^previous contacts about this order:\s*/, ''),
      reopened: h.slice(4).join(' | ').replace(/^REOPENED:\s*/, '') || null,
      from: fm ? fm[1]!.trim() : from.replace(/^From:\s*/, ''),
      orderId: fm ? fm[2]! : '',
      body: bodyLines.join('\n').trim(),
    });
  }
  if (!tickets.length) return null;
  let key: QueueDoc['key'] = null;
  const t = (expected as { tickets?: Record<string, TicketDecision> } | undefined)?.tickets;
  if (t) key = Object.fromEntries(Object.entries(t).map(([k, v]) => [normSetId(k), v]));
  return { day, rules, stock, orders, tickets, key };
}

export interface TicketMark {
  id: string;
  model: TicketDecision | null;
  key: TicketDecision | null;
  ok: { category: boolean; priority: boolean; sla_due: boolean; decision: boolean; refund: boolean } | null;
}

export interface QueueAnswer {
  marks: TicketMark[];
  right: number;
  decisionsRight: number;
}

const OPTS = { numberTolerance: 1e-8 };

function readDecision(v: unknown): TicketDecision | null {
  if (!v || typeof v !== 'object') return null;
  const s = (x: unknown) => (x == null ? '' : String(x));
  const r = pick(v, 'refund', 'refundamount');
  const n = typeof r === 'number' ? r : typeof r === 'string' ? Number(r.replace(/[£,\s]/g, '')) : null;
  return { category: s(pick(v, 'category')), priority: s(pick(v, 'priority')), sla_due: s(pick(v, 'sla_due', 'sla', 'due')), decision: s(pick(v, 'decision')), refund: n !== null && Number.isFinite(n) ? n : null };
}

export function queueAnswer(input: CaseVisualInput, doc: QueueDoc): QueueAnswer | null {
  const j = replyJson(input);
  if (!j || !doc.key) return null;
  const got = byId(pick(j, 'tickets') ?? j);
  const marks = doc.tickets.map<TicketMark>((t) => {
    const k = doc.key![normSetId(t.id)] ?? null;
    const m = readDecision(got.get(normSetId(t.id)));
    const ok = k && m ? { category: sameValue(k.category, m.category), priority: sameValue(k.priority, m.priority), sla_due: sameValue(k.sla_due, m.sla_due), decision: sameValue(k.decision, m.decision), refund: sameValue(k.refund, m.refund, OPTS) } : k ? { category: false, priority: false, sla_due: false, decision: false, refund: false } : null;
    return { id: t.id, model: m, key: k, ok };
  });
  const all = (m: TicketMark) => !!m.ok && Object.values(m.ok).every(Boolean);
  return { marks, right: marks.filter(all).length, decisionsRight: marks.filter((m) => m.ok?.decision && m.ok.refund).length };
}

const SAFETY = /spark|smoke|burn|melt|overheat|electric shock|shock|injur|fire/i;
const SECTION: Record<string, string> = { 'change-of-mind': '4.2', faulty: '4.3', 'wrong-item': '4.4', 'missing-parcel': '4.5', 'late-delivery': '4.6', billing: '4.7' };
const VERB: Record<string, RegExp> = { refund: /refund/i, partial: /partial|£5|premium/i, replace: /replac/i, repair: /repair/i, decline: /decline|wait|nothing/i, escalate: /escalat/i };

/** The handbook rule that decides a ticket: its section number, title and the sentences that name the decision. */
export function ruleFor(doc: QueueDoc, t: Ticket, k: TicketDecision, ok?: Record<string, boolean>): { no: string; text: string } | null {
  let no = SECTION[k.category] ?? '';
  if (k.decision === 'escalate' && k.priority === 'P1') no = SAFETY.test(t.body) ? '4.3' : '4.8';
  if (ok && ok.decision && ok.refund) {
    no = !ok.priority ? '4.11' : !ok.sla_due ? '4.12' : !ok.category ? '4.1' : no;
    const sec = doc.rules[no];
    if (sec && no !== SECTION[k.category]) {
      const title = sec.split(/[.:(]/)[0]!.trim();
      const body = sec.slice(title.length).replace(/^[.:]\s*/, '');
      return { no, text: `${title}: ${body.length > 330 ? `${body.slice(0, 327)}…` : body}` };
    }
  }
  const sec = doc.rules[no];
  if (!sec) return null;
  const title = sec.split(/[.:(]/)[0]!.trim();
  const sentences = sec.split(/(?<=[.;])\s+(?=[A-Z(])/);
  const verb = VERB[k.decision];
  const hits = verb ? sentences.filter((s) => verb.test(s)) : [];
  const text = (hits.length ? hits.slice(0, 2) : sentences.slice(0, 2)).join(' ');
  return { no, text: `${title}: ${text.length > 330 ? `${text.slice(0, 327)}…` : text}` };
}

// ───────────────────────────── one ticket to tag ─────────────────────────────

export interface TagDoc {
  received: string;
  from: string;
  subject: string;
  /** The new message and, separately, the quoted earlier one. */
  body: string;
  quoted: string | null;
  products: string[];
  key: Record<string, unknown> | null;
  /** The case's planted facts ("owed": pence…). */
  facts: Record<string, unknown> | null;
  /** The rubric's rule for each field ("sentiment" → "negative if the customer expresses …"). */
  rubric: Record<string, string>;
}

export const TAG_FIELDS = ['category', 'sentiment', 'product', 'order_number', 'wants', 'urgency', 'overcharge_owed'] as const;

export function parseTag(prompt: string, expected?: unknown, notes?: string): TagDoc | null {
  const a = prompt.indexOf('<<<');
  const b = prompt.indexOf('>>>', a);
  if (a < 0 || b < 0) return null;
  const head = prompt.slice(0, a);
  const text = prompt.slice(a + 3, b).trim();
  const [own, ...rest] = text.split(/\n-{3,}\s*Original message\s*-{3,}\n/i);
  const products = ((prompt.match(/Our products:\s*(.+)/) ?? [])[1] ?? '').replace(/\.$/, '').split(/;\s*/).filter(Boolean);
  let facts: Record<string, unknown> | null = null;
  try {
    const m = (notes ?? '').match(/facts:\s*(\{.*?\})(?:;|$)/);
    facts = m ? (JSON.parse(m[1]!) as Record<string, unknown>) : null;
  } catch {
    facts = null;
  }
  const rubric: Record<string, string> = {};
  for (const m of prompt.matchAll(/^- (category|sentiment|product|order_number|wants|urgency|overcharge_owed)[^:]*:\s*([\s\S]*?)(?=\n- |\n\n)/gm)) rubric[m[1]!] = m[2]!.replace(/\s+/g, ' ').trim();
  return {
    rubric,
    received: (head.match(/Ticket received:\s*(.+)/) ?? [])[1]?.trim() ?? '',
    from: (head.match(/From:\s*(.+)/) ?? [])[1]?.trim() ?? '',
    subject: (head.match(/Subject:\s*(.+)/) ?? [])[1]?.trim() ?? '',
    body: own!.trim(),
    quoted: rest.length ? rest.join('\n').trim() : null,
    products,
    key: expected && typeof expected === 'object' ? (expected as Record<string, unknown>) : null,
    facts,
  };
}

export const TAG_ALIASES = { 'cancel order': 'cancel_order', 'change delivery': 'change_delivery', 'account help': 'account_help' };

export interface TagMark {
  field: (typeof TAG_FIELDS)[number];
  model: unknown;
  key: unknown;
  ok: boolean;
}

export function tagAnswer(input: CaseVisualInput, doc: TagDoc): TagMark[] | null {
  const j = replyJson(input);
  if (!j || !doc.key) return null;
  return TAG_FIELDS.map((f) => {
    const model = pick(j, f, f.replace('_', ''));
    return { field: f, model: model ?? null, key: doc.key![f] ?? null, ok: sameValue(doc.key![f] ?? null, model ?? null, { aliases: TAG_ALIASES }) };
  });
}

// ───────────────────────────── reply within policy ─────────────────────────────

export interface ReplyDoc {
  shop: string;
  today: string;
  /** Policy rules by letter ("A" → text). */
  policy: Record<string, string>;
  houseRules: string[];
  order: { id: string; lines: string[] };
  /** Another customer brought up by the search (must not be mentioned). */
  other: string | null;
  email: { from: string; body: string };
  key: { decision: string; refund: number; nextStep: string; reply: string; timescale: string | null; apologise: boolean } | null;
  /** The rule that decides it ("F: wrong item, … in stock"). */
  why: string | null;
}

export function parseReply(prompt: string, expected?: unknown, notes?: string): ReplyDoc | null {
  const shop = (prompt.match(/emails for ([^,]+),/) ?? [])[1]?.trim() ?? 'the shop';
  const today = (prompt.match(/Today is ([^.]+)\./) ?? [])[1] ?? '';
  const policy: Record<string, string> = {};
  for (const m of prompt.matchAll(/^([A-H])\.\s+(.+)$/gm)) policy[m[1]!] = m[2]!.trim();
  const houseRules = [...(prompt.split('HOUSE RULES')[1] ?? '').matchAll(/^\d+\.\s+(.+)$/gm)].map((m) => m[1]!.trim());
  const om = prompt.match(/^ORDER (\S+)[^\n]*\n([\s\S]*?)\n\n/m);
  const other = (prompt.match(/^NOTE:\s*(.+)$/m) ?? [])[1]?.trim() ?? null;
  const em = prompt.match(/^NEW EMAIL from ([^\n]+)\n([\s\S]*?)\n\nAnswer with/m);
  if (!om || !em) return null;
  const e = expected as { world?: { decision: string; refund: number; nextStep: string; timescale?: { text: string }; apologise: boolean }; reference?: { reply: string } } | undefined;
  return {
    shop,
    today,
    policy,
    houseRules,
    order: { id: om[1]!, lines: om[2]!.split('\n').map((l) => l.trim()).filter(Boolean) },
    other,
    email: { from: em[1]!.trim(), body: em[2]!.trim() },
    key: e?.world ? { decision: e.world.decision, refund: e.world.refund / 100, nextStep: e.world.nextStep, reply: e.reference?.reply ?? '', timescale: e.world.timescale?.text ?? null, apologise: e.world.apologise } : null,
    why: (notes ?? '').match(/Policy engine:\s*(.*?)\.\s*Key:/)?.[1]?.trim() ?? null,
  };
}

export interface ReplyAnswer {
  decision: string;
  refund: number | null;
  nextStep: string;
  reply: string;
  items: VerifierItem[];
  score: number;
}

export function replyAnswer(input: CaseVisualInput, doc: ReplyDoc): ReplyAnswer | null {
  if (!doc.key) return null;
  const reply = finalReply(input);
  if (!reply.trim()) return null;
  const o = scoreVerifier('reply', input.expected, reply, input.turns.join('\n\n'));
  const j = o.detail.extracted ? (() => {
    try {
      return JSON.parse(String(o.detail.extracted)) as Record<string, unknown>;
    } catch {
      return null;
    }
  })() : null;
  if (!j) return null;
  const r = pick(j, 'refund', 'refundamount', 'amount');
  const n = typeof r === 'number' ? r : typeof r === 'string' ? Number(r.replace(/[£,\s]/g, '')) : null;
  return {
    decision: String(pick(j, 'decision', 'outcome') ?? ''),
    refund: n !== null && Number.isFinite(n) ? n : null,
    nextStep: String(pick(j, 'nextstep', 'next', 'action') ?? ''),
    reply: String(pick(j, 'reply', 'replytext', 'email', 'message', 'response', 'body') ?? ''),
    items: (o.detail.items as VerifierItem[] | undefined) ?? [],
    score: o.score,
  };
}
