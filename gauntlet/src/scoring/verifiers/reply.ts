/**
 * Reply Within Policy (work.support-replies): the structured header of a customer-service reply (decision, refund
 * amount, next step) is compared field by field with the policy engine's answer, and the reply text is checked by
 * written house rules, each a plain machine check (no judge, no opinion):
 *
 *   uses the customer's first name · quotes the order number · states exactly the refund amount and no other £
 *   amount (none at all when nothing is refunded) · gives the timescale the policy sets for that decision ·
 *   apologises when the policy says to · never uses a banned promise word · never names or blames the delivery
 *   company · never mentions another customer's details · at most the word limit.
 *
 * Points: decision 3, refund 3 (only with the right decision), next step 2, every text rule 1. The "never" rules
 * (banned words, delivery company, other customers, length) only earn their point on a usable reply: a real reply to
 * this customer (at least 25 words, with the customer's first name and the order number) whose decision, refund and
 * next step are all right. An empty, generic or wrong-outcome text cannot collect them.
 */
import type { AnswerVerifier, VerifierItem } from './types.ts';
import { field, gbp, normKey, readPence } from './common.ts';

export interface ReplyWorld {
  decision: string;
  /** Pence (0 when nothing is refunded now). */
  refund: number;
  nextStep: string;
  decisions: string[];
  nextSteps: string[];
  firstName: string;
  orderNo: string;
  /** The timescale the policy sets for this decision: a regular expression (case-insensitive) and how it reads. */
  timescale?: { pattern: string; text: string };
  apologise: boolean;
  /** Another customer's name, order number, email, postcode… that must not appear. */
  others: string[];
  maxWords: number;
  /** Banned words (regular expression, case-insensitive) and the delivery companies' names (likewise). */
  banned: string;
  carrier: string;
}

const MIN_WORDS = 25;

function words(text: string): number {
  return text.split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w)).length;
}

/** Every money amount written in the text, in pence: "£23.50", "£1,234", "23.50 GBP", "GBP 23.50". */
export function amountsIn(text: string): number[] {
  const out: number[] = [];
  const re = /£\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?|(?:GBP\s?)(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?|(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?\s?(?:GBP|pounds?\b)/gi;
  for (const m of text.matchAll(re)) {
    const whole = m[1] ?? m[3] ?? m[5]!;
    const frac = m[2] ?? m[4] ?? m[6] ?? '0';
    out.push(Number(whole.replace(/,/g, '')) * 100 + Number(frac.padEnd(2, '0')));
  }
  return out;
}

const squash = (s: string) => s.toLowerCase().replace(/[\s\-–—#]/g, '');

/** One of the listed codes, ignoring case, spaces and underscores ("Request info" = "REQUEST_INFO"); a value naming two is wrong. */
function readChoice(v: unknown, options: string[]): string | null {
  if (typeof v !== 'string' || /\bor\b|\/|\band\b/i.test(v.replace(/_/g, ' '))) return null;
  const k = normKey(v);
  const hit = options.filter((o) => normKey(o) === k);
  return hit.length === 1 ? hit[0]! : null;
}

export function checkReply(w: ReplyWorld, answer: Record<string, unknown>): { items: VerifierItem[] } {
  const items: VerifierItem[] = [];
  const dec = readChoice(field(answer, 'decision', 'outcome'), w.decisions);
  items.push({ label: 'decision', passed: dec === w.decision, weight: 3, detail: dec === w.decision ? undefined : `expected ${w.decision}, got ${JSON.stringify(field(answer, 'decision', 'outcome') ?? null)}` });
  const refundRaw = field(answer, 'refund', 'refundamount', 'refundgbp', 'amount');
  const refund = refundRaw === null || (typeof refundRaw === 'string' && /^(none|n\/?a|-|no refund)$/i.test(refundRaw.trim())) ? 0 : readPence(refundRaw);
  // The refund is judged with the decision: the right figure under the wrong decision earns nothing.
  const refundOk = dec === w.decision && refund === w.refund;
  items.push({ label: 'refund amount (with the right decision)', passed: refundOk, weight: 3, detail: refundOk ? undefined : refund !== w.refund ? `expected ${gbp(w.refund)}, got ${JSON.stringify(refundRaw ?? null)}` : 'the decision is wrong' });
  const next = readChoice(field(answer, 'nextstep', 'next', 'action'), w.nextSteps);
  items.push({ label: 'next step', passed: next === w.nextStep, weight: 2, detail: next === w.nextStep ? undefined : `expected ${w.nextStep}, got ${JSON.stringify(field(answer, 'nextstep', 'next', 'action') ?? null)}` });

  const rawText = field(answer, 'reply', 'replytext', 'email', 'message', 'response', 'body');
  const text = typeof rawText === 'string' ? rawText : '';
  const n = words(text);
  const hasName = new RegExp(`(^|[^\\p{L}])${w.firstName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}([^\\p{L}]|$)`, 'iu').test(text);
  const hasOrder = squash(text).includes(squash(w.orderNo));
  const real = n >= MIN_WORDS && hasName && hasOrder;
  items.push({ label: `reply uses the customer’s first name (${w.firstName})`, passed: hasName });
  items.push({ label: `reply quotes the order number ${w.orderNo}`, passed: hasOrder });
  const amounts = amountsIn(text);
  const moneyOk = w.refund > 0 ? amounts.length > 0 && amounts.every((a) => a === w.refund) : amounts.length === 0 && n > 0;
  items.push({
    label: w.refund > 0 ? `reply states the refund as ${gbp(w.refund)} and no other amount` : 'reply quotes no money amount (nothing is refunded now)',
    passed: moneyOk,
    detail: moneyOk ? undefined : amounts.length ? `amounts in the reply: ${amounts.map(gbp).join(', ')}` : 'no amount stated',
  });
  if (w.timescale) {
    const ok = new RegExp(w.timescale.pattern, 'i').test(text);
    items.push({ label: `reply gives the timescale (${w.timescale.text})`, passed: ok });
  }
  if (w.apologise) {
    const ok = /\b(sorry|apologi[sz]e[sd]?|apologies|apology)\b/i.test(text);
    items.push({ label: 'reply apologises (the policy says to)', passed: ok });
  }
  const headerOk = dec === w.decision && refundOk && next === w.nextStep;
  const usable = real && headerOk;
  const gate = !real ? 'not a reply to this customer about this order' : !headerOk ? 'only counted when the decision, refund and next step are all right' : undefined;
  const banned = text.match(new RegExp(w.banned, 'gi'));
  items.push({ label: 'no banned promise words (voucher, compensation, guarantee…)', passed: usable && !banned, detail: gate ?? (banned ? `says "${banned[0]}"` : undefined) });
  const carrier = text.match(new RegExp(w.carrier, 'gi'));
  items.push({ label: 'does not name or blame the delivery company', passed: usable && !carrier, detail: gate ?? (carrier ? `says "${carrier[0]}"` : undefined) });
  if (w.others.length) {
    const leaked = w.others.filter((o) => squash(text).includes(squash(o)));
    items.push({ label: 'no other customer’s details', passed: usable && !leaked.length, detail: gate ?? (leaked.length ? `mentions ${leaked.join(', ')}` : undefined) });
  }
  items.push({ label: `at most ${w.maxWords} words`, passed: usable && n <= w.maxWords, detail: gate ?? (n > w.maxWords ? `${n} words` : undefined) });
  return { items };
}

export const reply: AnswerVerifier<ReplyWorld> = {
  kind: 'answer',
  name: 'reply',
  noun: 'reply',
  answerKeys: ['decision', 'reply', 'nextstep', 'refund'],
  marking: [
    'The header is compared with the answer the written policy gives (worked out by a policy engine, not typed): the decision (3 points), the refund to the penny (3 points, only with the right decision) and the next step (2 points). A value that offers two options is wrong.',
    'The reply text is checked by the house rules in the brief, one point each: the customer’s first name, the order number, the exact refund amount and no other £ amount (none at all if nothing is refunded), the timescale the policy sets for that decision, an apology when the policy says to apologise.',
    'One point each for what it must never do: a banned promise word, naming or blaming the delivery company, another customer’s details, going over the word limit. These only count on a usable reply: a real reply to this customer (at least 25 words, with their first name and the order number) whose decision, refund and next step are all right. The score is the share of points earned.',
  ],
  check: checkReply,
};
