// Counter-Strike 2 rounds, from the game's own data: how rounds went by the
// buy the player made (pistol, eco, force, full), what forcing cost the next
// round, how often they died early in a round or with grenades still in hand,
// damage, kills and survival per round, and each side's record. Only the
// player's own buy is known (the game sends nothing about teammates or
// enemies while playing), so team-buy lines are phrased about the player.

import type { BuyType, Cs2Round, Cs2Rounds } from '../gsi/cs2.ts'

export interface RoundInsight {
  readonly id: string
  readonly text: string
  readonly playbook?: string
  readonly tags: readonly string[]
}

export interface RoundTotals {
  readonly rounds: number
  readonly won: number
  readonly kills: number
  readonly deaths: number
  readonly damage: number
  /** Rounds with damage known (the denominator for damage per round). */
  readonly damageRounds: number
  readonly byBuy: Readonly<Record<BuyType, { readonly n: number; readonly won: number }>>
  readonly bySide: Readonly<Record<'CT' | 'T', { readonly n: number; readonly won: number }>>
  readonly multiKills: number
}

const pct = (k: number, n: number) => `${Math.round((100 * k) / Math.max(1, n))}%`
const BUY_LABEL: Readonly<Record<BuyType, string>> = { pistol: 'pistol rounds', eco: 'eco rounds', force: 'force buys', full: 'full buys' }

export function roundTotals(rounds: readonly Cs2Round[]): RoundTotals {
  const done = rounds.filter((r) => r.result !== null)
  const byBuy = { pistol: { n: 0, won: 0 }, eco: { n: 0, won: 0 }, force: { n: 0, won: 0 }, full: { n: 0, won: 0 } }
  const bySide = { CT: { n: 0, won: 0 }, T: { n: 0, won: 0 } }
  for (const r of done) {
    if (r.buy) {
      byBuy[r.buy].n++
      if (r.result === 'won') byBuy[r.buy].won++
    }
    if (r.side) {
      bySide[r.side].n++
      if (r.result === 'won') bySide[r.side].won++
    }
  }
  const withDmg = done.filter((r) => r.damage !== null)
  return {
    rounds: done.length,
    won: done.filter((r) => r.result === 'won').length,
    kills: done.reduce((s, r) => s + r.kills, 0),
    deaths: done.filter((r) => r.died).length,
    damage: withDmg.reduce((s, r) => s + (r.damage ?? 0), 0),
    damageRounds: withDmg.length,
    byBuy,
    bySide,
    multiKills: done.filter((r) => r.kills >= 3).length,
  }
}

/** Lines for one match or several (oldest first); `focus`: only that one. */
export function roundInsights(matches: readonly Cs2Rounds[], focus: number | null = null, max = 7): RoundInsight[] {
  const ms = focus === null ? matches : matches.slice(focus, focus + 1)
  const rounds = ms.flatMap((m) => m.rounds.filter((r) => r.result !== null))
  if (rounds.length < 6) return []
  const t = roundTotals(rounds)
  const out: RoundInsight[] = []
  // Per round: damage, kills, survival.
  const adr = t.damageRounds ? Math.round(t.damage / t.damageRounds) : null
  out.push({
    id: 'per-round',
    text: `Over ${t.rounds} rounds: ${(t.kills / t.rounds).toFixed(2)} kills a round${adr !== null ? `, ${adr} damage a round` : ''}, and you survived ${pct(t.rounds - t.deaths, t.rounds)} of them${t.multiKills ? `; ${t.multiKills} rounds with three or more kills` : ''}.`,
    tags: ['numbers'],
  })
  // Rounds won by the player's buy.
  const buys = (['pistol', 'eco', 'force', 'full'] as const).filter((b) => t.byBuy[b].n > 0)
  if (buys.length >= 2)
    out.push({
      id: 'by-buy',
      text: `Rounds won by your buy: ${buys.map((b) => `${BUY_LABEL[b]} ${t.byBuy[b].won} of ${t.byBuy[b].n}`).join(', ')}.`,
      tags: ['economy'],
    })
  // Forcing: how those rounds went, and whether the next round could be bought.
  const forced = rounds.filter((r) => r.buy === 'force')
  if (forced.length >= 4) {
    const lostForces = forced.filter((r) => r.result === 'lost')
    const broke = lostForces.filter((r) => {
      const next = rounds.find((x) => x.n === r.n + 1 && x.side === r.side)
      return !!next && (next.buy === 'eco' || next.buy === 'force')
    }).length
    if (lostForces.length / forced.length >= 0.6)
      out.push({
        id: 'forcing',
        text: `You force-bought ${forced.length} times and lost ${lostForces.length} of those rounds${broke ? `; ${broke} times you could not full-buy the round after` : ''}. Before forcing, add your money to the loss bonus you would get: if that is short of a full buy next round, save instead.`,
        playbook: 'buy-rules',
        tags: ['economy', 'buying'],
      })
  }
  // Pistol rounds.
  const pistols = rounds.filter((r) => r.buy === 'pistol')
  if (pistols.length >= 2) {
    const won = pistols.filter((r) => r.result === 'won').length
    if (won / pistols.length <= 0.34)
      out.push({
        id: 'pistols',
        text: `You won ${won} of ${pistols.length} pistol rounds. They set the economy for the next rounds: play them together and trade.`,
        playbook: 'pistol-rounds',
        tags: ['economy', 'pistol'],
      })
  }
  // Deaths early in the round.
  const deaths = rounds.filter((r) => r.died && r.diedAt !== null)
  if (deaths.length >= 6) {
    const early = deaths.filter((r) => r.diedAt! <= 20).length
    if (early / deaths.length >= 0.4)
      out.push({
        id: 'early-deaths',
        text: `${early} of your ${deaths.length} deaths came in the first 20 seconds of the round. An early death leaves your team a player down for the rest of it: take the first fights only with a teammate to trade, or gather information first.`,
        playbook: 'play-for-info',
        tags: ['deaths', 'timing'],
      })
  }
  // Dying with grenades in hand.
  const withUtility = rounds.filter((r) => r.died && r.utilityAtDeath !== null)
  if (withUtility.length >= 6) {
    const unused = withUtility.filter((r) => r.utilityAtDeath! >= 2).length
    if (unused / withUtility.length >= 0.35)
      out.push({
        id: 'unused-utility',
        text: `You died holding two or more grenades in ${unused} of ${withUtility.length} deaths. Use them before the fight: a flash or a smoke first changes the duel.`,
        playbook: 'play-around-utility',
        tags: ['utility', 'deaths'],
      })
  }
  // Each side.
  if (t.bySide.CT.n >= 4 && t.bySide.T.n >= 4) {
    const ct = t.bySide.CT.won / t.bySide.CT.n
    const tt = t.bySide.T.won / t.bySide.T.n
    out.push({
      id: 'sides',
      text: `Your team won ${pct(t.bySide.CT.won, t.bySide.CT.n)} of rounds as CT and ${pct(t.bySide.T.won, t.bySide.T.n)} as T${Math.abs(ct - tt) >= 0.2 ? `: the ${ct > tt ? 'T' : 'CT'} side is where the most rounds are being lost` : ''}.`,
      ...(Math.abs(ct - tt) >= 0.2 ? { playbook: ct > tt ? 't-defaults' : 'ct-setups-crossfire' } : {}),
      tags: ['sides'],
    })
  }
  return out.slice(0, max)
}
