/**
 * Scripted players for the Agents & Planning programs (Survival Island,
 * Escape Room, Startup). Each returns a Responder for the fake model.
 */
import { createRng } from '../../src/core/rng.ts';
import type { Responder } from './fake-model.ts';
import { generateIsland, type IslandConfig } from '../../src/programs/lib/agentic-island.ts';
import { generateEscape, type EscapeConfig } from '../../src/programs/lib/agentic-escape.ts';
import {
  competitorPrice,
  generateMarket,
  newFirm,
  oracle,
  playMonth,
  season,
  type Decisions,
} from '../../src/programs/lib/agentic-startup.ts';
import { createIslandAgent } from './agentic-island-agent.ts';

/** Survival Island: the full-knowledge agent, playing through the fake model. */
export function islandResponder(seed: number, cfg: IslandConfig, rescue = true): Responder {
  const world = generateIsland(createRng(seed), cfg);
  const agent = createIslandAgent(world, { rescue });
  return () => {
    const cmd = agent.next();
    return `Keeping my stats up and working towards rescue.\nNOTE: day ${agent.state.day}, plan: signal on ship days ${world.shipDays.join(',')}\nACTION: ${cmd}`;
  };
}

/** Escape Room: replays the generated world's optimal plan. */
export function escapeResponder(seed: number, cfg: EscapeConfig): Responder {
  const world = generateEscape(createRng(seed), cfg);
  let i = 0;
  return () => `Following my plan.\nACTION: ${world.plan[i++] ?? 'LOOK'}`;
}

function decisionBlock(d: Decisions): string {
  return [`PRICE: ${d.price.toFixed(2)}`, `PRODUCE: ${d.produce}`, `MARKETING: ${d.marketing}`, `HIRE: ${d.hire}`, ...(d.loan ? ['LOAN: ACCEPT'] : [])].join('\n');
}

/** Startup: replays the oracle's decisions (full knowledge of the hidden market). */
export function startupOracleResponder(seed: number, months = 12): Responder {
  const market = generateMarket(createRng(seed), months);
  const plan = oracle(market).plan;
  let i = 0;
  return () => {
    const p = plan[i++]!;
    return `Executing the plan.\n${decisionBlock({ price: p.price, produce: p.produce, marketing: p.marketing, hire: p.hire, loan: p.loan })}`;
  };
}

/**
 * Startup: a sensible founder who only uses what the observation shows
 * (sales, sold-out signals, leftover stock, capacity, competitor price and
 * the seasonal outlook). Keeps a mirror firm to track its own results.
 */
export function startupHeuristicResponder(seed: number, months = 12): Responder {
  const market = generateMarket(createRng(seed), months);
  const firm = newFirm(market);
  let price = 0;
  return () => {
    const m = firm.month;
    const comp = competitorPrice(market, m);
    const last = firm.history[firm.history.length - 1];
    let demandEst: number;
    if (!last) {
      price = Math.round(comp * 1.15) - 0.01;
      demandEst = market.opening.produce;
    } else {
      const missed = last.missed > 0 ? Math.max(10, Math.round(last.missed / 10) * 10) : 0;
      const wanted = last.sold + missed;
      let next = last.price;
      if (missed > 0.1 * wanted) next *= 1.08;
      else if (last.inventory > 0.3 * Math.max(1, last.sold)) next *= 0.95;
      else next *= 1.03;
      next = Math.min(next, comp * 1.6);
      // Assume demand elasticity ~2 and a moderate seasonal swing, as a founder would guess.
      const seasonal = (1 + 0.25 * (season(market, m) - 1) / market.seasonAmp) / (1 + 0.25 * (season(market, m - 1) - 1) / market.seasonAmp);
      demandEst = wanted * (next / last.price) ** -2 * seasonal;
      price = Math.round(next * 100) / 100;
    }
    const produce = Math.max(0, Math.round(demandEst * 1.05 - firm.inventory));
    const needed = Math.max(1, Math.ceil(produce / market.capacityPerStaff));
    let hire = needed - firm.staff;
    if (hire < 0 && hire > -2) hire = 0;
    hire = Math.max(-(firm.staff - 1), Math.min(2, hire));
    const d: Decisions = { price, produce, marketing: 2000, hire, loan: false };
    playMonth(market, firm, d);
    return `Adjusting to last month's results.\nNOTE: month ${m} done\n${decisionBlock(d)}`;
  };
}
