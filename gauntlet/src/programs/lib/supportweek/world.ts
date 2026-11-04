/**
 * Run Customer Service for a Week: the seeded week. Twenty-eight tickets arrive over five days (a spike on
 * Wednesday, when a kettle recall is announced and the policy changes), built by the shared customer-service
 * generator (lib/support/world.ts). Two human agents with different skills, working days and daily capacity can
 * take tickets; the goodwill budget for the week is £40. Every ticket's hidden truth includes its priority under
 * the policy (not the priority the contact form gave it) and so its service-level deadline.
 */
import type { Rng } from '../../../core/types.ts';
import type { Level } from '../workworld/score.ts';
import type { Email } from '../workworld/world.ts';
import { SHOPS } from '../support/data.ts';
import { makeCtx, makeTicket, renumber, type Bundle, type Kind } from '../support/world.ts';
import type { Agent, SupportSetup } from '../support/scenario.ts';

const WEEKS = ['2026-02-02', '2026-06-08', '2026-10-05'];

/** Day → the kinds arriving that day, with the level each counts as. */
export const WEEK_PLAN: Array<Array<[Kind, Level]>> = [
  [
    ['return', 'easy'],
    ['wismo', 'easy'],
    ['late-express', 'medium'],
    ['damaged', 'easy'],
    ['discount', 'medium'],
    ['recall-pre', 'hard'],
    ['over-limit', 'medium'],
    ['missing', 'medium'],
  ],
  [
    ['lost-wait', 'medium'],
    ['multibuy', 'hard'],
    ['privacy', 'hard'],
    ['return', 'easy'],
    ['injection-lost', 'hard'],
  ],
  [
    ['recall', 'medium'],
    ['recall', 'medium'],
    ['recall-other', 'hard'],
    ['safety', 'hard'],
    ['fraud', 'hard'],
    ['goodwill-demand', 'hard'],
    ['damaged', 'easy'],
  ],
  [
    ['goodwill-late', 'easy'],
    ['wismo', 'easy'],
    ['discount', 'medium'],
    ['recall', 'easy'],
  ],
  [
    ['second-failed', 'medium'],
    ['outside-window', 'easy'],
    ['recall', 'easy'],
  ],
];

export const RECALL_DAY = 2;
export const GOODWILL_BUDGET = 4_000;

export function generateSupportWeek(rng: Rng, seed = 0): SupportSetup {
  const week = { monday: WEEKS[seed % WEEKS.length]! };
  const shop = SHOPS[rng.int(0, SHOPS.length - 1)]!;
  const first = rng.int(61, 78) * 100 + 1;
  const ctx = makeCtx(rng, week, shop, first);
  const parts: Bundle[] = [];
  const levelOf = new Map<number, Level>();
  WEEK_PLAN.forEach((kinds, day) => {
    const times = rng.shuffle(Array.from({ length: kinds.length }, (_, i) => (day === 0 ? 6 * 60 + 30 : 8 * 60 + 5) + i * 13 + rng.int(0, 9)));
    kinds.forEach(([kind, level], i) => {
      const b = makeTicket(ctx, kind, day, times[i]!);
      for (const t of b.truth) levelOf.set(t.id, level);
      parts.push(b);
    });
  });
  const { tickets, truth } = renumber(
    parts.flatMap((p) => p.tickets),
    parts.flatMap((p) => p.truth).map((t) => ({ ...t, level: levelOf.get(t.id) ?? t.level })),
    first,
  );
  const firsts = rng.shuffle(['Bea', 'Jamal', 'Carys', 'Tunde', 'Ilse', 'Rahul', 'Moira', 'Kenji']);
  const lasts = rng.shuffle(['Okafor', 'Reyes', 'Llewellyn', 'Bakare', 'Janssen', 'Iyer', 'Gallacher', 'Mori']);
  const agents: Agent[] = [
    { name: `${firsts[0]} ${lasts[0]}`, skills: ['refunds'], days: [0, 1, 2], capacity: 4 },
    { name: `${firsts[1]} ${lasts[1]}`, skills: ['delivery'], days: [2, 3, 4], capacity: 4 },
  ];
  const leadFirst = rng.pick(['Rosalind', 'Kayode', 'Mairi', 'Anand', 'Beatriz']);
  const leadLast = rng.pick(['Achterberg', 'Nwachukwu', 'MacInnes', 'Raghavan', 'Sousa']);
  const recallText =
    'From today the Ember 1.7L kettle (model EK-17) is recalled: it can overheat. Every customer who bought one gets a refund of what they paid for the kettle plus the delivery charge on that order. They do not need to send it back; tell them to stop using it straight away. Recall tickets are P1 (deal with them the same working day). Recall refunds are not limited to £100 and do not use the goodwill budget. Check earlier tickets about this kettle too. The Ember 1.5L kettle (EK-15) is NOT affected.';
  const announce: Email = {
    id: 9001,
    thread: 'recall',
    from: { name: `${shop.name} Product Team`, email: `product@${shop.domain}` },
    to: [{ name: 'Customer service', email: `help@${shop.domain}` }],
    day: RECALL_DAY,
    time: 7 * 60 + 45,
    subject: 'PRODUCT RECALL: Ember 1.7L kettle (EK-17)',
    body: recallText,
    tag: 'brief',
  };
  return {
    seed,
    week,
    shop,
    manager: { name: `${leadFirst} ${leadLast}`, first: leadFirst, email: `${leadFirst.toLowerCase()}@${shop.domain}` },
    me: { name: 'Ash (AI support lead)', email: `help@${shop.domain}` },
    orders: parts.flatMap((p) => p.orders),
    tickets,
    truth,
    agents,
    goodwillBudget: GOODWILL_BUDGET,
    recall: { day: RECALL_DAY, sku: 'EK-17', text: recallText },
    extraEmails: [announce],
    days: 5,
  };
}
