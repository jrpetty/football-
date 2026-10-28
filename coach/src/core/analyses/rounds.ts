// Counter-Strike 2 rounds, from the game's own data: how rounds went by the
// buy the player made (pistol, eco, force, full), what forcing cost the next
// round, how often they died early in a round or with grenades still in hand,
// damage, kills and survival per round, and each side's record. Only the
// player's own buy is known (the game sends nothing about teammates or
// enemies while playing), so team-buy lines are phrased about the player.
// Grenades: how many of those carried were thrown, what full buys carried, how
// often the player was flashed blind or stood in fire.

import { GRENADE_KINDS, countNades, type BuyType, type Cs2Round, type Cs2Rounds, type GrenadeKind } from '../gsi/cs2.ts'

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

export const GRENADE_LABEL: Readonly<Record<GrenadeKind, { one: string; many: string }>> = {
  smoke: { one: 'smoke', many: 'smokes' },
  flash: { one: 'flash', many: 'flashes' },
  he: { one: 'HE grenade', many: 'HE grenades' },
  fire: { one: 'molotov or incendiary', many: 'molotovs and incendiaries' },
  decoy: { one: 'decoy', many: 'decoys' },
}

export interface UtilityTotals {
  /** Rounds with grenade data (recorded by this version of the coach). */
  readonly rounds: number
  readonly carried: Readonly<Record<GrenadeKind, number>>
  readonly thrown: Readonly<Record<GrenadeKind, number>>
  readonly atDeath: Readonly<Record<GrenadeKind, number>>
  readonly flashed: number
  readonly blindSeconds: number
  readonly blindDeaths: number
  readonly burned: number
  /** Full buys, and those without a smoke or without a flash. */
  readonly fullBuys: number
  readonly fullNoSmoke: number
  readonly fullNoFlash: number
}

const zero = (): Record<GrenadeKind, number> => ({ smoke: 0, flash: 0, he: 0, fire: 0, decoy: 0 })

/** Grenades over finished rounds: carried, thrown, held at death, flashes and fire. */
export function utilityTotals(rounds: readonly Cs2Round[]): UtilityTotals {
  const done = rounds.filter((r) => r.result !== null && r.nades !== undefined)
  const carried = zero()
  const thrown = zero()
  const atDeath = zero()
  let flashed = 0
  let blindSeconds = 0
  let burned = 0
  for (const r of done) {
    for (const k of GRENADE_KINDS) {
      carried[k] += r.nades?.[k] ?? 0
      thrown[k] += r.thrown?.[k] ?? 0
      atDeath[k] += r.nadesAtDeath?.[k] ?? 0
    }
    flashed += r.flashed ?? 0
    blindSeconds += r.blindSeconds ?? 0
    burned += r.burned ?? 0
  }
  const full = done.filter((r) => r.buy === 'full')
  return {
    rounds: done.length,
    carried,
    thrown,
    atDeath,
    flashed,
    blindSeconds: Math.round(blindSeconds * 10) / 10,
    blindDeaths: done.filter((r) => r.died && r.blindAtDeath).length,
    burned,
    fullBuys: full.length,
    fullNoSmoke: full.filter((r) => !(r.nades?.smoke ?? 0)).length,
    fullNoFlash: full.filter((r) => !(r.nades?.flash ?? 0)).length,
  }
}

const list = (c: Readonly<Record<GrenadeKind, number>>) =>
  GRENADE_KINDS.filter((k) => c[k] > 0)
    .map((k) => `${c[k]} ${c[k] === 1 ? GRENADE_LABEL[k].one : GRENADE_LABEL[k].many}`)
    .join(', ')

/** Lines about the player's grenades (needs rounds recorded with grenade data). */
export function utilityInsights(rounds: readonly Cs2Round[]): RoundInsight[] {
  const u = utilityTotals(rounds)
  if (u.rounds < 6) return []
  const out: RoundInsight[] = []
  // Grenades thrown out of those carried: overall, or on the side where most were kept.
  const share = (x: UtilityTotals) => ({ carried: countNades(x.carried), thrown: countNades(x.thrown), atDeath: countNades(x.atDeath), x })
  const all = share(u)
  const bySide = (['T', 'CT'] as const).map((side) => ({ side, ...share(utilityTotals(rounds.filter((r) => r.side === side))) })).filter((x) => x.carried >= 6)
  const worst = bySide.sort((a, b) => a.thrown / a.carried - b.thrown / b.carried)[0]
  const pick = all.carried >= 8 && all.thrown / all.carried <= 0.6 ? { ...all, side: null } : worst && worst.thrown / worst.carried <= 0.5 ? worst : null
  if (pick)
    out.push({
      id: 'utility-thrown',
      text: `${pick.side ? `As ${pick.side} you` : 'You'} threw ${pick.thrown} of the ${pick.carried} grenades you carried (${pct(pick.thrown, pick.carried)})${pick.atDeath ? `; ${pick.atDeath} were still in your hands when you died (${list(pick.x.atDeath)})` : ''}. Decide where each grenade goes before the round starts, and throw it before you take the fight.`,
      playbook: 'support-role',
      tags: ['utility'],
    })
  if (u.fullBuys >= 4 && (u.fullNoSmoke / u.fullBuys >= 0.5 || u.fullNoFlash / u.fullBuys >= 0.5)) {
    const smoke = u.fullNoSmoke / u.fullBuys >= u.fullNoFlash / u.fullBuys
    out.push({
      id: 'full-buy-utility',
      text: `On ${u.fullBuys} full buys you went without a smoke ${u.fullNoSmoke} times and without a flash ${u.fullNoFlash} times. A full buy is a gun, armour and utility: a smoke ($300) and a flash ($200) at the least.`,
      playbook: smoke ? 'smokes' : 'flashes',
      tags: ['utility', 'economy'],
    })
  }
  if (u.flashed >= 4 || u.blindDeaths >= 2)
    out.push({
      id: 'flashed',
      text: `You were flashed blind ${u.flashed} times in ${u.rounds} rounds (about ${Math.round(u.blindSeconds)} s in all)${u.blindDeaths ? `, and ${u.blindDeaths} of your deaths came while blind` : ''}. Turn away when you hear a flash land, hold angles a step back from the corner, and reposition after a flash instead of peeking.`,
      playbook: 'play-around-utility',
      tags: ['utility', 'deaths'],
    })
  if (u.burned >= 3)
    out.push({
      id: 'burned',
      text: `You stood in fire ${u.burned} times. A molotov or incendiary does about 40 damage a second: step out at once and take another route or wait it out.`,
      playbook: 'molotovs',
      tags: ['utility'],
    })
  return out
}
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
export function roundInsights(matches: readonly Cs2Rounds[], focus: number | null = null, max = 10): RoundInsight[] {
  const ms = focus === null ? matches : matches.slice(focus, focus + 1)
  const rounds = ms.flatMap((m) => m.rounds.filter((r) => r.result !== null))
  if (rounds.length < 6) return []
  const t = roundTotals(rounds)
  const out: RoundInsight[] = []
  // Per round: damage, kills, survival.
  const adr = t.damageRounds ? Math.round(t.damage / t.damageRounds) : null
  out.push({
    id: 'per-round',
    text: `Over ${t.rounds} rounds: ${(t.kills / t.rounds).toFixed(2)} kills a round${adr !== null ? `, ${adr} damage a round` : ''}, and you survived ${pct(t.rounds - t.deaths, t.rounds)} of them${t.multiKills ? `; ${t.multiKills} ${t.multiKills === 1 ? 'round' : 'rounds'} with three or more kills` : ''}.`,
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
  // (With grenade data, the grenade lines below say this with the kinds.)
  const withUtility = rounds.filter((r) => r.died && r.utilityAtDeath !== null)
  if (withUtility.length >= 6 && !rounds.some((r) => r.nades !== undefined)) {
    const unused = withUtility.filter((r) => r.utilityAtDeath! >= 2).length
    if (unused / withUtility.length >= 0.35)
      out.push({
        id: 'unused-utility',
        text: `You died holding two or more grenades in ${unused} of ${withUtility.length} deaths. Use them before the fight: a flash or a smoke first changes the duel.`,
        playbook: 'play-around-utility',
        tags: ['utility', 'deaths'],
      })
  }
  // Grenades.
  out.push(...utilityInsights(rounds))
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
