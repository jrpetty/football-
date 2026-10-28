// Counter-Strike gun numbers worked out from the game's own weapon file:
// damage at a distance on each part of the body, with and without armour,
// hits and time to kill, how far out one headshot kills, how far the first
// shot stays on a head, and what moving does to it. The model is the one the
// game's code has used since CS:GO (damage falls by the range modifier every
// 500 units; armour lets through armour ratio / 2 of it and soaks half the
// rest), with hits rounded down as the damage report shows them.

import type { ArsenalItem, WeaponBallistics } from '../profile/schema.ts'

/** One game unit in metres (the game's player is 72 units, about 1.83 m). */
export const UNIT_M = 0.0254
/** A full-health player. */
export const FULL_HEALTH = 100
/** The "accurate range": where the widest first-shot miss is 15 cm, about a head either way (Valve's own measure). */
export const ACCURATE_MISS_M = 0.15
/** Distances the tables use, in metres. */
export const TABLE_DISTANCES_M = [5, 10, 20, 30, 50] as const

export const HIT_GROUPS = ['head', 'chest', 'stomach', 'legs'] as const
export type HitGroup = (typeof HIT_GROUPS)[number]
/** Multipliers on a hit (arms count as chest); the head's is the gun's own. */
const GROUP_MULT: Readonly<Record<Exclude<HitGroup, 'head'>, number>> = { chest: 1, stomach: 1.25, legs: 0.75 }

export interface Protection {
  /** Kevlar points (0 to 100). */
  readonly kevlar: number
  readonly helmet: boolean
}
export const NO_ARMOUR: Protection = { kevlar: 0, helmet: false }
export const FULL_ARMOUR: Protection = { kevlar: 100, helmet: true }

/** Damage of one hit (one pellet for shotguns): to health and to armour. */
export function hitDamage(b: WeaponBallistics, metres: number, group: HitGroup, armour: Protection): { readonly health: number; readonly armour: number } {
  const units = metres / UNIT_M
  if (units > b.maxRange) return { health: 0, armour: 0 }
  const raw = b.damage * b.rangeModifier ** (units / 500) * (group === 'head' ? b.headshotMultiplier : GROUP_MULT[group])
  // Kevlar covers the body and arms; the head only with a helmet; never the legs.
  const covered = armour.kevlar > 0 && (group === 'head' ? armour.helmet : group !== 'legs')
  if (!covered) return { health: Math.floor(raw), armour: 0 }
  const ratio = Math.min(1, b.armorRatio / 2)
  let toHealth = raw * ratio
  let toArmour = (raw - toHealth) * 0.5
  if (toArmour > armour.kevlar) {
    toArmour = armour.kevlar
    toHealth = raw - armour.kevlar / 0.5
  }
  return { health: Math.floor(toHealth), armour: Math.floor(toArmour) }
}

/** Hits needed to kill from full health (armour wears down hit by hit); null beyond the gun's range or past 40 hits. */
export function hitsToKill(b: WeaponBallistics, metres: number, group: HitGroup, armour: Protection, health = FULL_HEALTH): number | null {
  let hp = health
  let kevlar = armour.kevlar
  for (let n = 1; n <= 40; n++) {
    const d = hitDamage(b, metres, group, { kevlar, helmet: armour.helmet })
    if (d.health <= 0) return null
    hp -= d.health
    kevlar = Math.max(0, kevlar - d.armour)
    if (hp <= 0) return n
  }
  return null
}

/** Seconds from the first hit to the killing one, every bullet landing (shotguns: whole shots, all pellets landing). */
export function timeToKill(b: WeaponBallistics, hits: number | null): number | null {
  if (hits === null) return null
  const shots = Math.ceil(hits / Math.max(1, b.pellets ?? 1))
  return (shots - 1) * b.cycleTime
}

/** Rounds a minute. */
export function roundsPerMinute(b: WeaponBallistics): number {
  return Math.round(60 / b.cycleTime)
}

/** The furthest distance (m) at which one hit to the head kills from full health, or null if it never does. */
export function oneShotHeadRange(b: WeaponBallistics, helmet: boolean): number | null {
  const armour: Protection = helmet ? FULL_ARMOUR : NO_ARMOUR
  const max = b.maxRange * UNIT_M
  if (hitDamage(b, 0, 'head', armour).health < FULL_HEALTH) return null
  if (hitDamage(b, max, 'head', armour).health >= FULL_HEALTH) return max
  let lo = 0
  let hi = max
  for (let i = 0; i < 40; i++) {
    const mid = (lo + hi) / 2
    if (hitDamage(b, mid, 'head', armour).health >= FULL_HEALTH) lo = mid
    else hi = mid
  }
  return lo
}

/** Where the widest first-shot miss is 15 cm, standing still or crouched; `scoped` uses the scope's numbers when the gun has one. */
export function accurateRange(b: WeaponBallistics, stance: 'stand' | 'crouch', scoped = false): number {
  const s = scoped && b.scoped ? b.scoped : b
  const cone = s.spread + (stance === 'crouch' ? s.inaccuracyCrouch : s.inaccuracyStand)
  return ACCURATE_MISS_M / cone
}

/** The widest first-shot miss (m) at a distance while moving at full speed. */
export function movingMiss(b: WeaponBallistics, metres: number): number {
  return metres * (b.spread + b.inaccuracyMove)
}

export interface DamageCell {
  readonly damage: number
  readonly hits: number | null
}
export interface DamageRow {
  readonly metres: number
  readonly head: DamageCell
  readonly headHelmet: DamageCell
  readonly chest: DamageCell
  readonly chestArmour: DamageCell
  readonly stomachArmour: DamageCell
  readonly legs: DamageCell
}

/** Damage and hits to kill at each distance: head with and without a helmet, body with and without kevlar, legs. */
export function damageTable(b: WeaponBallistics, distances: readonly number[] = TABLE_DISTANCES_M): DamageRow[] {
  const cell = (m: number, g: HitGroup, a: Protection): DamageCell => ({ damage: hitDamage(b, m, g, a).health, hits: hitsToKill(b, m, g, a) })
  return distances.map((m) => ({
    metres: m,
    head: cell(m, 'head', NO_ARMOUR),
    headHelmet: cell(m, 'head', FULL_ARMOUR),
    chest: cell(m, 'chest', NO_ARMOUR),
    chestArmour: cell(m, 'chest', FULL_ARMOUR),
    stomachArmour: cell(m, 'stomach', FULL_ARMOUR),
    legs: cell(m, 'legs', NO_ARMOUR),
  }))
}

export interface GunSummary {
  readonly rpm: number
  /** Body hits through kevlar at 10 m, and the time to land them. */
  readonly chestHits10: number | null
  readonly ttk10: number | null
  readonly oneShotHelmet: number | null
  readonly oneShotBare: number | null
  readonly accurateStand: number
  readonly accurateCrouch: number
  readonly accurateScoped: number | null
  /** Widest miss at 10 m moving at full speed. */
  readonly movingMiss10: number
  readonly armourPen: number
}

export function gunSummary(b: WeaponBallistics): GunSummary {
  const chestHits10 = hitsToKill(b, 10, 'chest', FULL_ARMOUR)
  return {
    rpm: roundsPerMinute(b),
    chestHits10,
    ttk10: timeToKill(b, chestHits10),
    oneShotHelmet: oneShotHeadRange(b, true),
    oneShotBare: oneShotHeadRange(b, false),
    accurateStand: accurateRange(b, 'stand'),
    accurateCrouch: accurateRange(b, 'crouch'),
    accurateScoped: b.scoped ? accurateRange(b, 'stand', true) : null,
    movingMiss10: movingMiss(b, 10),
    armourPen: Math.min(100, Math.round(b.armorRatio * 50 * 10) / 10),
  }
}

const m = (v: number) => (v >= 100 ? `${Math.round(v / 10) * 10} m` : v >= 10 ? `${Math.round(v)} m` : `${v.toFixed(1)} m`)
const s = (v: number) => (v === 0 ? 'at once' : `in ${v.toFixed(2)} s`)

/** The gun's numbers in sentences (answers and the coach's reference). */
export function ballisticsLines(item: Pick<ArsenalItem, 'name' | 'ballistics'>): string[] {
  const b = item.ballistics
  if (!b) return []
  const g = gunSummary(b)
  const out: string[] = []
  const pellets = (b.pellets ?? 1) > 1
  if (pellets) {
    const at = (metres: number) => {
      const h = hitsToKill(b, metres, 'chest', FULL_ARMOUR)
      if (h === null) return `no kill at ${metres} m`
      const shots = Math.ceil(h / b.pellets!)
      return `${h} body pellets at ${metres} m (${shots === 1 ? 'one shot' : `${shots} shots`} with every pellet landing)`
    }
    out.push(`${b.pellets} pellets a shot, ${b.damage} damage each before range and armour. Through kevlar it takes ${at(5)}, and ${at(10)}; damage falls off quickly with distance.`)
  } else {
    const h20 = hitsToKill(b, 20, 'chest', FULL_ARMOUR)
    out.push(
      `Body hits through kevlar: ${g.chestHits10 ?? 'no kill'} at 10 m${g.ttk10 !== null ? ` (kills ${s(g.ttk10)} at ${g.rpm} rounds a minute)` : ''}${h20 !== g.chestHits10 && h20 !== null ? `, ${h20} at 20 m` : ''}.`,
    )
  }
  if (pellets) {
    // A head-height shot lands several pellets at once; per-pellet head numbers would mislead.
  } else if (g.oneShotHelmet !== null) out.push(`One headshot kills through a helmet out to about ${m(g.oneShotHelmet)}.`)
  else if (g.oneShotBare !== null) out.push(`One headshot kills only without a helmet (out to about ${m(g.oneShotBare)}); a helmet saves them (${hitDamage(b, 10, 'head', FULL_ARMOUR).health} damage at 10 m).`)
  else out.push(`A headshot does not kill on its own (${hitDamage(b, 10, 'head', FULL_ARMOUR).health} through a helmet at 10 m).`)
  out.push(
    `First shot stays on a head out to about ${m(g.accurateStand)} standing and ${m(g.accurateCrouch)} crouched${g.accurateScoped !== null ? ` (${m(g.accurateScoped)} scoped)` : ''}; running at full speed it can miss by up to ${m(g.movingMiss10)} at 10 m.`,
  )
  out.push(`Armour penetration ${g.armourPen}%; moves at ${b.maxSpeed} units a second${b.scoped?.maxSpeed ? ` (${b.scoped.maxSpeed} scoped)` : ''}${b.reloadSeconds ? `; ready to fire ${b.reloadSeconds.toFixed(1)} s into a reload` : ''}.`)
  return out
}
