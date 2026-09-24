/**
 * The Startup — twelve monthly decisions running a small consumer-product
 * company from $10,000.
 *
 * The market is hidden and seeded: price elasticity, marketing response
 * with diminishing returns and carry-over (brand awareness), seasonality,
 * staff capacity, holding costs, stockouts that cost sales and reputation,
 * and scripted events (price war, supplier cost spike, viral review, a loan
 * offer). The model sees only what a founder would: P&L reports, customer
 * feedback and market news.
 */
import { extractTagged, parseNumber } from '../core/extract.ts';
import type { ProgramContext, ProgramDefinition, ProgramResult, ReplayFrame } from '../core/types.ts';
import { askTurn, clamp01, money, noteBlock, round, signedMoney, truncate } from './lib/agentic-common.ts';
import {
  autopilot,
  calendarName,
  competitorPrice,
  equityOf,
  generateMarket,
  loanPaymentFor,
  MONTH_NAMES,
  newFirm,
  oracle,
  playMonth,
  priceWarActive,
  simulate,
  spikeActive,
  unitCostAt,
  type Decisions,
  type Firm,
  type Market,
  type MonthReport,
} from './lib/agentic-startup.ts';

const DEFAULTS = { months: 12, gapFloor: 5000, volatile: false };

export const STARTUP_SYSTEM = `You are playing THE STARTUP. You run a small company that makes and sells one consumer product. You start with $10,000 in cash and 2 staff. The game lasts 12 monthly turns; each turn you set this month's decisions and then receive the month's results.

HOW A MONTH WORKS
1. Your decisions take effect. Hiring fees or severance, marketing and production are paid up front, in that order. If cash runs short, marketing and then production are cut to what you can afford.
2. Customers buy. Demand depends on your price relative to competitors, brand awareness, your reputation and the season. You can only sell units you have in stock (last month's leftovers plus this month's production).
3. At month end you pay salaries, rent, storage for every unsold unit and any loan instalment.
4. If cash is below $0 at month end, the company is bankrupt and the game ends immediately.

THE MARKET (the exact numbers are hidden — learn them from your results)
- Higher prices mean fewer customers; how much fewer is something to discover.
- Marketing builds brand awareness with diminishing returns; awareness carries over from month to month but fades without upkeep.
- Selling out loses those sales and hurts your reputation, which lowers future demand. Unsold stock costs storage every month.
- Each staff member can produce a fixed number of units per month; production is capped by staff capacity. New hires work from the month they are hired.
- Demand is seasonal, and news, customer feedback and events (competitors, suppliers, reviews, bank offers) arrive as the year goes on.

YOUR GOAL: finish month 12 with as much equity as possible. Equity = cash + every unsold unit valued at the BASE unit cost (the regular supplier price, ignoring any temporary surcharge; shown every month) − any loan balance still owed. Going bankrupt scores zero.

DECISIONS (one per line)
PRICE: <selling price in dollars, e.g. 24.99>
PRODUCE: <units to manufacture this month>
MARKETING: <dollars to spend on marketing this month>
HIRE: <change in staff: e.g. 2 to hire two, 0 for no change, -1 to let one go>
LOAN: ACCEPT (only in a month when a loan is on offer; otherwise leave it out)
Any line you leave out keeps its current value (shown as your current plan). You must keep at least 1 staff member.

MEMORY: each turn shows the full history of your results plus your own note. Write a line NOTE: <text> (max 300 characters) to remember insights or plans; it is shown back to you next turn, and if you omit it your previous note is kept.

OUTPUT: think as much as you need, then end your reply with the decision lines (PRICE, PRODUCE, MARKETING, HIRE, and LOAN only when relevant), optionally preceded by a NOTE: line. If a decision line appears more than once, only the last one counts.`;

// ─────────────────────────────────────────────────────────────────────────────
// Parsing
// ─────────────────────────────────────────────────────────────────────────────

const TAGS = ['PRICE', 'PRODUCE', 'MARKETING', 'HIRE', 'LOAN'] as const;
type Tag = (typeof TAGS)[number];

/** Last "TAG: value" in the reply: at the start of a line, else anywhere (e.g. "ACTION: PRICE: 24.99"). */
function lastTagged(text: string, tag: Tag): string | null {
  const direct = extractTagged(text, tag);
  if (direct !== null) return direct;
  const re = new RegExp(`\\b${tag}\\s*[:：=]\\s*([^\\n]+)`, 'gi');
  let m: RegExpExecArray | null;
  let last: string | null = null;
  while ((m = re.exec(text)) !== null) last = m[1] ?? null;
  return last === null ? null : last.replace(/[*_`]/g, '').trim();
}

export interface ParsedDecisions {
  decisions: Decisions;
  /** Which fields the reply set. */
  set: Tag[];
  /** Human-readable problems with individual lines. */
  problems: string[];
}

/** Merge the reply's decision lines over the current plan. */
export function parseDecisions(text: string, current: Decisions): ParsedDecisions {
  const d: Decisions = { ...current, loan: false };
  const set: Tag[] = [];
  const problems: string[] = [];
  for (const tag of TAGS) {
    const raw = lastTagged(text, tag);
    if (raw === null) continue;
    if (tag === 'LOAN') {
      if (/^(ACCEPT|YES|TAKE|Y)\b/i.test(raw)) {
        d.loan = true;
        set.push(tag);
      } else if (/^(DECLINE|NO|REJECT|N)\b/i.test(raw)) set.push(tag);
      else problems.push(`LOAN: "${truncate(raw, 30)}" is not ACCEPT or DECLINE`);
      continue;
    }
    // "−1", "-1", "+2" — keep the sign for HIRE.
    const cleaned = raw.replace(/[−–]/g, '-');
    const n = parseNumber(cleaned);
    if (n === null || !Number.isFinite(n)) {
      problems.push(`${tag}: "${truncate(raw, 30)}" is not a number`);
      continue;
    }
    if (tag === 'PRICE') d.price = n;
    if (tag === 'PRODUCE') d.produce = n;
    if (tag === 'MARKETING') d.marketing = n;
    if (tag === 'HIRE') d.hire = n;
    set.push(tag);
  }
  if (!set.includes('HIRE')) d.hire = 0; // hiring never repeats implicitly
  return { decisions: d, set, problems };
}

// ─────────────────────────────────────────────────────────────────────────────
// Observation
// ─────────────────────────────────────────────────────────────────────────────

function awarenessLabel(a: number): string {
  if (a < 0.3) return 'low — few people have heard of you';
  if (a < 0.5) return 'growing';
  if (a < 0.7) return 'moderate';
  return 'high — the brand is well known';
}

function reputationLabel(r: number): string {
  if (r >= 1.05) return 'excellent';
  if (r >= 0.95) return 'good';
  if (r >= 0.8) return 'shaky';
  return 'poor';
}

function price2(n: number): string {
  return `$${n.toFixed(2)}`;
}

function planLine(d: Decisions): string {
  return `PRICE ${d.price.toFixed(2)} · PRODUCE ${d.produce} · MARKETING ${d.marketing} · HIRE ${d.hire}`;
}

function feedback(market: Market, r: MonthReport): string[] {
  const out: string[] = [];
  const rel = r.price / competitorPrice(market, r.month);
  if (rel > 1.3) out.push('"Nice product, but it costs a lot more than the alternatives."');
  else if (rel > 1.1) out.push('"A bit pricier than the others, but fair."');
  else if (rel < 0.8) out.push('"What a bargain — cheaper than everything else out there!"');
  else out.push('"The price feels about right."');
  if (r.missed > 0) out.push('"I wanted one but it was sold out."');
  if (r.inventory > Math.max(50, r.sold * 0.4)) out.push('Your storeroom is filling up with unsold stock.');
  if (r.awareness < 0.3) out.push('Most people you talk to have never heard of the brand.');
  return out;
}

function news(market: Market, firm: Firm): string[] {
  const m = firm.month;
  const out: string[] = [];
  const w = market.priceWar;
  if (m === w.start) out.push(`A rival has just launched a price war, cutting prices by about ${Math.round((1 - w.factor) * 100)}%. It may last a few months.`);
  else if (priceWarActive(market, m)) out.push('The rival’s price war continues.');
  else if (m === w.start + w.months) out.push('The price war is over: competitor prices are back to normal.');
  const sp = market.supplierSpike;
  if (m === sp.start - 1)
    out.push(
      `Supplier notice: the unit cost rises from ${price2(market.unitCost)} to ${price2(unitCostAt(market, sp.start))} from next month, for about ${sp.months} months.`,
    );
  else if (spikeActive(market, m)) out.push(`Supplier surcharge in effect: unit cost ${price2(unitCostAt(market, m))} (normally ${price2(market.unitCost)}).`);
  else if (m === sp.start + sp.months) out.push('Supplier prices are back to normal.');
  if (market.shock && m === market.shock.month)
    out.push(`Bad news: a safety scare about products like yours is all over the news this month — the whole category expects demand to fall by roughly ${Math.round((1 - market.shock.factor) * 100)}%.`);
  if (m === market.viral.month - 1) out.push('A popular online reviewer has asked for a sample; her review goes live next month.');
  if (m === market.viral.month) out.push('The review is live and people are talking about your product.');
  if (m === market.loan.month && !firm.loanTaken) {
    const n = market.months - market.loan.month + 1;
    const pay = loanPaymentFor(market);
    out.push(
      `Bank offer (this month only): borrow ${money(market.loan.principal)} now, repaid in ${n} monthly instalments of ${money(pay)} starting at the end of this month (total ${money(pay * n)}). To accept, include the line LOAN: ACCEPT.`,
    );
  }
  return out;
}

function historyLines(firm: Firm): string[] {
  if (firm.history.length === 0) return [];
  const lines = ['History (month · price · made · sold · missed sales · marketing · staff · net result · cash after):'];
  for (const h of firm.history) {
    const missed = h.missed > 0 ? `~${Math.max(10, Math.round(h.missed / 10) * 10)}` : '0';
    lines.push(
      `  ${h.month} ${h.calendar.slice(0, 3)} · ${price2(h.price)} · ${h.produced} · ${h.sold} · ${missed} · ${money(h.marketing)} · ${h.staff} · ${signedMoney(h.net)} · ${money(h.cash)}`,
    );
  }
  return lines;
}

function lastMonthLines(market: Market, r: MonthReport): string[] {
  const lines = [`Last month (month ${r.month}, ${r.calendar}):`];
  const stock =
    r.missed > 0
      ? `SOLD OUT — about ${Math.max(10, Math.round(r.missed / 10) * 10)} more customers wanted one`
      : r.inventory > 0
        ? `UNSOLD — ${r.inventory} units left on the shelf, costing ${money(r.holding)} in storage this month`
        : 'sold exactly what you had';
  lines.push(`  Price ${price2(r.price)} · made ${r.produced} · sold ${r.sold} (${stock}) · marketing ${money(r.marketing)} · staff ${r.staff}`);
  lines.push(
    `  Revenue ${money(r.revenue)} − production ${money(r.cogs)} − marketing ${money(r.marketing)} − salaries ${money(r.salaries)} − rent ${money(r.rent)} − storage ${money(r.holding)} − hiring/severance ${money(r.staffFees)} − loan instalment ${money(r.loanPayment)} = net ${signedMoney(r.net)}${r.loanIn ? ` (plus ${money(r.loanIn)} borrowed)` : ''}`,
  );
  if (r.adjustments.length) lines.push(`  Note: ${r.adjustments.join('; ')}.`);
  lines.push(`  Customer feedback: ${feedback(market, r).join(' ')}`);
  return lines;
}

function peakHint(market: Market): string {
  return `Industry outlook: demand for products like yours usually peaks around ${MONTH_NAMES[market.seasonPeak]} and is weakest around ${MONTH_NAMES[(market.seasonPeak + 6) % 12]}.`;
}

function exampleLines(market: Market, firm: Firm): string {
  const p = firm.last.price;
  const cap = firm.staff * market.capacityPerStaff;
  const opts = [
    `PRICE: ${(Math.max(1, Math.round(p * 0.8)) - 0.01).toFixed(2)}`,
    `PRICE: ${p.toFixed(2)}`,
    `PRICE: ${(Math.round(p * 1.2) - 0.01).toFixed(2)}`,
    'PRODUCE: 0',
    `PRODUCE: ${Math.round(cap / 2 / 50) * 50}`,
    `PRODUCE: ${cap}`,
    'MARKETING: 0',
    'MARKETING: 1000',
    'MARKETING: 3000',
    'HIRE: -1',
    'HIRE: 0',
    'HIRE: 1',
    'HIRE: 2',
  ];
  if (market.loan.month === firm.month && !firm.loanTaken) opts.push('LOAN: ACCEPT', 'LOAN: DECLINE');
  return opts.map((o) => `\`${o}\``).join(', ');
}

export function startupObservation(market: Market, firm: Firm, noteLine: string, lastError: string | null): string {
  const m = firm.month;
  const lines: string[] = [];
  lines.push(`THE STARTUP — Month ${m} of ${market.months} (${calendarName(market, m)}) · Your product: ${market.product}s`);
  const loan = firm.loanTaken && firm.loanBalance > 0.5 ? `loan balance ${money(firm.loanBalance)} (instalment ${money(firm.loanPayment)}/month)` : 'no loan';
  lines.push(
    `Cash ${money(firm.cash)} · Inventory ${firm.inventory} units · Staff ${firm.staff} (can make up to ${firm.staff * market.capacityPerStaff} units/month; ${market.capacityPerStaff} per person) · ${loan} · Equity ${money(equityOf(market, firm))}`,
  );
  lines.push(
    `Costs: unit cost this month ${price2(unitCostAt(market, m))} (base unit cost ${price2(market.unitCost)}, used to value unsold stock) · rent ${money(market.rent)}/month · salary ${money(market.salary)} per staff member/month · hiring fee ${money(market.hireFee)} per hire · severance ${money(market.severance)} per person let go · storage ${price2(market.holdingCost)} per unsold unit at month end`,
  );
  lines.push(
    `Market: competitors charge about ${price2(Math.round(competitorPrice(market, m) * 2) / 2)} · Brand awareness: ${awarenessLabel(firm.awareness)} · Reputation: ${reputationLabel(firm.reputation)}`,
  );
  lines.push(peakHint(market));
  const last = firm.history[firm.history.length - 1];
  if (last) lines.push(...lastMonthLines(market, last));
  else lines.push('This is your first month. No sales yet.');
  lines.push(...historyLines(firm));
  const n = news(market, firm);
  lines.push(`News: ${n.length ? n.join(' ') : 'nothing new.'}`);
  if (lastError) lines.push(`Last turn: ${lastError}`);
  lines.push(noteLine);
  lines.push(`Current plan (repeated if you don't change it; HIRE resets to 0): ${planLine({ ...firm.last, hire: 0 })}`);
  lines.push(`Example decision lines: ${exampleLines(market, firm)}`);
  lines.push(
    'Reply with brief reasoning if you like, then end with your decision lines — PRICE: <dollars>, PRODUCE: <units>, MARKETING: <dollars>, HIRE: <staff change> (plus LOAN: ACCEPT only while a loan is on offer), one per line. Omitted lines keep their current value.',
  );
  return lines.join('\n');
}

// ─────────────────────────────────────────────────────────────────────────────
// Program
// ─────────────────────────────────────────────────────────────────────────────

function decisionText(d: Decisions): string {
  return `PRICE ${price2(d.price)} · PRODUCE ${d.produce} · MKT ${money(d.marketing)} · HIRE ${d.hire >= 0 ? '+' : ''}${d.hire}${d.loan ? ' · LOAN ✓' : ''}`;
}

export const program: ProgramDefinition = {
  id: 'startup-sim',
  name: 'The Startup',
  description:
    'Twelve monthly turns running a small consumer-product company from $10,000. A hidden, seeded market decides the outcome: price elasticity, marketing with diminishing returns and carry-over brand awareness, seasonality, staff capacity, storage costs, stockouts that lose sales and reputation, and events such as a competitor price war, a supplier cost spike, a viral review and a loan offer. ' +
    'Each month the model sets price, production, marketing and hiring, then reads a P&L report, customer feedback and market news. Bankruptcy ends the game.',
  scoring:
    'Score = (final equity − autopilot equity) ÷ (oracle equity − autopilot equity), clamped to 0–1; bankruptcy scores 0. Equity = cash + unsold units valued at the base unit cost (ignoring supplier surcharges) − loan balance. ' +
    'The autopilot is the do-nothing baseline: it repeats the pre-launch plan every month (floored at $0). The oracle is a reference policy that knows every hidden market parameter and event and is tuned by grid search plus month-by-month coordinate descent. ' +
    'The gap is floored at $5,000. The volatile variant (config volatile: true) only changes the market (noisier demand, a deeper supplier spike, a harsher price war, a one-month demand shock). Passed = score ≥ 0.5 (closed at least half the gap between autopilot and oracle).',
  defaults: DEFAULTS,
  async run(ctx: ProgramContext): Promise<ProgramResult> {
    const months = Math.min(24, Math.max(3, Math.round(Number(ctx.config.months ?? DEFAULTS.months)) || DEFAULTS.months));
    const gapFloor = Math.max(1, Number(ctx.config.gapFloor ?? DEFAULTS.gapFloor) || DEFAULTS.gapFloor);
    const market = generateMarket(ctx.rng, months, { volatile: ctx.config.volatile === true });
    const firm = newFirm(market);
    const auto = simulate(market, autopilot);
    const ref = oracle(market);
    const autoEquity = equityOf(market, auto);
    const baseline = Math.max(0, autoEquity);

    let note = '';
    let lastError: string | null = null;
    let invalid = 0;
    let partial = 0;
    const frames: ReplayFrame[] = [
      {
        step: 0,
        label: 'Launch',
        outcome: `Selling ${market.product}s with ${money(market.cash0)} and ${market.staff0} staff.`,
        stats: { cash: market.cash0, equity: market.cash0, awareness: Math.round(firm.awareness * 100), reputation: 91, staff: firm.staff, month: 0 },
        tone: 'neutral',
      },
    ];

    while (firm.month <= market.months && !firm.bankrupt) {
      if (ctx.signal.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
      const observation = startupObservation(market, firm, noteBlock(note), lastError);
      const turn = await askTurn(ctx, STARTUP_SYSTEM, observation, `Month ${firm.month}`);
      if (turn.note !== null) note = turn.note;

      let decisions: Decisions;
      lastError = null;
      if (turn.failure) {
        invalid++;
        decisions = { ...firm.last, hire: 0, loan: false };
        lastError = `${turn.failure} Your current plan was repeated.`;
      } else {
        const parsed = parseDecisions(turn.reply.text, { ...firm.last, hire: 0, loan: false });
        decisions = parsed.decisions;
        if (parsed.set.length === 0) {
          invalid++;
          lastError = `no decision lines found${parsed.problems.length ? ` (${parsed.problems.join('; ')})` : ''} — your current plan was repeated.`;
        } else {
          if (parsed.set.length < 4 || parsed.problems.length) partial++;
          if (parsed.problems.length) lastError = `${parsed.problems.join('; ')} — those values were kept.`;
        }
      }

      const report = playMonth(market, firm, decisions);
      const good = report.net >= 0;
      frames.push({
        step: frames.length,
        label: `Month ${report.month} · ${report.calendar.slice(0, 3)}`,
        action: decisionText(decisions),
        outcome: truncate(
          `Sold ${report.sold}${report.missed > 0 ? ` (sold out, ~${Math.round(report.missed / 10) * 10} missed)` : ` (${report.inventory} left)`} · net ${signedMoney(report.net)} · cash ${money(report.cash)}${firm.bankrupt ? ' · BANKRUPT' : ''}${report.adjustments.length ? ` · ${report.adjustments.join('; ')}` : ''}`,
          220,
        ),
        observation: `Month ${report.month}: competitor ~${price2(competitorPrice(market, report.month))}`,
        stats: {
          cash: Math.round(report.cash),
          equity: Math.round(report.equity),
          price: report.price,
          produced: report.produced,
          sold: report.sold,
          staff: report.staff,
          awareness: Math.round(report.awareness * 100),
          reputation: Math.round((report.reputation / 1.1) * 100),
          month: report.month,
        },
        tone: firm.bankrupt ? 'bad' : good ? 'good' : 'neutral',
      });
    }

    const equity = equityOf(market, firm);
    frames.push({
      step: frames.length,
      label: firm.bankrupt ? 'Bankrupt' : 'Final result',
      outcome: firm.bankrupt
        ? `Bankrupt in month ${firm.history.length}. Oracle ${money(ref.equity)} · autopilot ${money(autoEquity)}.`
        : `Final equity ${money(equity)} (cash ${money(firm.cash)} + ${firm.inventory} units at ${price2(market.unitCost)}${firm.loanBalance > 0.5 ? ` − loan ${money(firm.loanBalance)}` : ''}) · oracle ${money(ref.equity)} · autopilot ${money(autoEquity)}.`,
      stats: {
        cash: Math.round(firm.cash),
        equity: Math.round(equity),
        oracle: Math.round(ref.equity),
        autopilot: Math.round(autoEquity),
        staff: firm.staff,
        awareness: Math.round(firm.awareness * 100),
        reputation: Math.round((firm.reputation / 1.1) * 100),
        month: firm.history.length,
      },
      tone: firm.bankrupt ? 'bad' : equity > Math.max(0, autoEquity) ? 'good' : 'neutral',
    });
    const gap = Math.max(gapFloor, ref.equity - baseline);
    const score = firm.bankrupt ? 0 : round(clamp01((equity - baseline) / gap), 4);
    const monthsPlayed = firm.history.length;
    const summary = firm.bankrupt
      ? `Bankrupt in month ${monthsPlayed} (oracle ${money(ref.equity)})`
      : `Month ${market.months}: ${money(equity)} equity (oracle ${money(ref.equity)}, autopilot ${money(autoEquity)})`;
    const cashSeries = (f: Firm): Array<{ x: number; y: number }> => [
      { x: 0, y: market.cash0 },
      ...f.history.map((h) => ({ x: h.month, y: Math.round(h.cash) })),
    ];

    return {
      score,
      passed: !firm.bankrupt && score >= 0.5,
      summary,
      detail: {
        finalEquity: round(equity, 2),
        finalCash: round(firm.cash, 2),
        finalInventory: firm.inventory,
        bankrupt: firm.bankrupt,
        bankruptMonth: firm.bankrupt ? monthsPlayed : null,
        monthsPlayed,
        oracleEquity: round(ref.equity, 2),
        autopilotEquity: round(autoEquity, 2),
        autopilotBankrupt: auto.bankrupt,
        invalidTurns: invalid,
        partialTurns: partial,
        totalRevenue: round(firm.history.reduce((a, h) => a + h.revenue, 0), 2),
        unitsSold: firm.history.reduce((a, h) => a + h.sold, 0),
        missedSales: firm.history.reduce((a, h) => a + h.missed, 0),
        stockoutMonths: firm.history.filter((h) => h.missed > 0).length,
        peakStaff: Math.max(market.staff0, ...firm.history.map((h) => h.staff)),
        loanTaken: firm.loanTaken,
        decisions: firm.history.map((h) => ({ month: h.month, price: h.price, produce: h.produceRequested, marketing: h.marketingRequested, hire: h.hires, loan: h.loanIn > 0 })),
        oraclePlan: ref.plan,
        hiddenMarket: {
          product: market.product,
          elasticity: market.elasticity,
          baseDemand: market.baseDemand,
          unitCost: market.unitCost,
          competitorPrice: market.refPrice,
          seasonAmplitude: market.seasonAmp,
          seasonPeak: MONTH_NAMES[market.seasonPeak],
          awarenessDecay: market.decay,
          marketingHalfSaturation: Math.round(market.kappa * Math.LN2),
          capacityPerStaff: market.capacityPerStaff,
          events: {
            priceWar: `months ${market.priceWar.start}–${market.priceWar.start + market.priceWar.months - 1}`,
            supplierSpike: `months ${market.supplierSpike.start}–${market.supplierSpike.start + market.supplierSpike.months - 1} (×${market.supplierSpike.factor})`,
            viralReview: `month ${market.viral.month}`,
            loanOffer: `month ${market.loan.month}: ${money(market.loan.principal)} at ${(market.loan.rate * 100).toFixed(1)}%/month`,
            demandShock: market.shock ? `month ${market.shock.month} (×${market.shock.factor})` : null,
          },
        },
        finalNote: note,
      },
      replay: {
        title: `The Startup · seed ${ctx.seed}`,
        gauges: ['awareness', 'reputation'],
        frames,
        series: [
          { name: 'Model cash', points: cashSeries(firm) },
          { name: 'Oracle cash', points: cashSeries(ref.firm) },
          { name: 'Autopilot cash', points: cashSeries(auto) },
        ],
      },
    };
  },
};
