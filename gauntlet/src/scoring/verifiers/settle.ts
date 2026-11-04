/**
 * Splitting the Bills (life.bill-split): checks each flatmate's balance and the settle-up transfers against the
 * solver's exact result. One check per person's balance and one per expected transfer; every extra transfer the
 * answer adds is one more failed check, so listing every possible payment can never help.
 */
import type { AnswerVerifier, VerifierItem } from './types.ts';
import { field, gbp, normText, readPence } from './common.ts';

export interface SettleWorld {
  people: string[];
  /** Pence: what each person has paid minus their fair share (positive: they are owed money). */
  balances: Record<string, number>;
  /** The transfers the stated method produces, in order. */
  transfers: Array<{ from: string; to: string; amount: number }>;
}

function readName(v: unknown, people: string[]): string | null {
  if (typeof v !== 'string') return null;
  const n = normText(v);
  const hits = people.filter((p) => normText(p) === n || n.split(' ').includes(normText(p)));
  return hits.length === 1 ? hits[0]! : null;
}

export function checkSettle(w: SettleWorld, answer: Record<string, unknown>): { items: VerifierItem[] } {
  const items: VerifierItem[] = [];
  // Balances: an object {name: amount} or a list [{name, balance}].
  const rawB = field(answer, 'balances', 'balance', 'net', 'netbalances');
  const got = new Map<string, number | null>();
  if (Array.isArray(rawB)) {
    for (const row of rawB) {
      const name = readName(field(row, 'name', 'person', 'who', 'flatmate'), w.people);
      if (name) got.set(name, readPence(field(row, 'balance', 'amount', 'net', 'value')));
    }
  } else if (rawB && typeof rawB === 'object') {
    for (const [k, v] of Object.entries(rawB as Record<string, unknown>)) {
      const name = readName(k, w.people);
      if (name) got.set(name, readPence(v));
    }
  }
  for (const p of w.people) {
    const g = got.get(p);
    const ok = g !== undefined && g !== null && g === w.balances[p];
    items.push({ label: `${p}’s balance`, passed: ok, detail: ok ? undefined : `expected ${gbp(w.balances[p]!)}, got ${g === undefined || g === null ? 'missing' : gbp(g)}` });
  }
  // Transfers.
  const rawT = field(answer, 'transfers', 'payments', 'settle', 'settlement', 'settleup');
  const list = Array.isArray(rawT) ? rawT : [];
  const given = list.map((t) => ({
    from: readName(field(t, 'from', 'payer', 'pays', 'sender'), w.people),
    to: readName(field(t, 'to', 'payee', 'receiver', 'recipient'), w.people),
    amount: readPence(field(t, 'amount', 'value', 'sum', 'gbp')),
  }));
  const used = new Set<number>();
  for (const e of w.transfers) {
    const i = given.findIndex((g, k) => !used.has(k) && g.from === e.from && g.to === e.to && g.amount === e.amount);
    if (i >= 0) used.add(i);
    items.push({ label: `${e.from} pays ${e.to} ${gbp(e.amount)}`, passed: i >= 0, detail: i >= 0 ? undefined : 'missing or wrong' });
  }
  given.forEach((g, k) => {
    if (used.has(k)) return;
    items.push({ label: 'no extra transfers', passed: false, detail: `extra: ${g.from ?? '?'} → ${g.to ?? '?'} ${g.amount === null ? '?' : gbp(g.amount)}` });
  });
  return { items };
}

export const settle: AnswerVerifier<SettleWorld> = {
  kind: 'answer',
  name: 'settle',
  noun: 'settle-up',
  answerKeys: ['transfers', 'balances', 'payments'],
  marking: [
    'Each flatmate’s balance (what they paid minus their fair share) is checked to the penny against the answer-key program, one check per person.',
    'Each settle-up transfer the stated method produces must appear with the right payer, payee and amount, one check per transfer; every extra transfer counts as one more wrong check, so listing extra payments always costs points.',
    'The score is the share of checks right. Names and amounts may be written in any reasonable way ("£12.30" or 12.3).',
  ],
  check: checkSettle,
};
