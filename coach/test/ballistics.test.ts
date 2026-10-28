import { test } from 'node:test'
import assert from 'node:assert/strict'
import cs2 from '../src/profiles/cs2.json' with { type: 'json' }
import type { ArsenalItem, WeaponBallistics } from '../src/core/profile/schema.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { accurateRange, ballisticsLines, damageTable, FULL_ARMOUR, gunSummary, hitDamage, hitsToKill, NO_ARMOUR, oneShotHeadRange, timeToKill } from '../src/core/knowledge/ballistics.ts'

const arsenal = (cs2 as unknown as { knowledge: { arsenal: ArsenalItem[] } }).knowledge.arsenal
const gun = (id: string): WeaponBallistics => {
  const b = arsenal.find((a) => a.id === id)?.ballistics
  assert.ok(b, `${id} has ballistics`)
  return b
}

test('every gun in the pack carries the weapon file numbers', () => {
  const guns = arsenal.filter((a) => a.category === 'weapon')
  assert.equal(guns.length, 34)
  for (const g of guns) assert.ok(g.ballistics, `${g.name} has ballistics`)
})

test('rifle numbers match the well-known counts', () => {
  const ak = gun('ak-47')
  assert.equal(hitsToKill(ak, 10, 'chest', FULL_ARMOUR), 4)
  assert.equal(hitsToKill(ak, 10, 'stomach', FULL_ARMOUR), 3)
  assert.equal(hitsToKill(ak, 10, 'head', FULL_ARMOUR), 1)
  const tap = oneShotHeadRange(ak, true)!
  assert.ok(tap > 65 && tap < 72, `AK helmet one-tap to ${tap} m`)
  assert.equal(Math.round(timeToKill(ak, 4)! * 100), 30)
  assert.ok(Math.abs(accurateRange(ak, 'stand') - 21.4) < 0.1)
  assert.ok(Math.abs(accurateRange(ak, 'crouch') - 27.7) < 0.1)

  const m4 = gun('m4a4')
  assert.equal(oneShotHeadRange(m4, true), null, 'the M4A4 does not one-tap through a helmet')
  assert.ok(oneShotHeadRange(m4, false)! > 50)
  assert.equal(hitsToKill(m4, 10, 'chest', FULL_ARMOUR), 5)

  const m4s = gun('m4a1-s')
  assert.equal(hitsToKill(m4s, 10, 'chest', FULL_ARMOUR), 4)
  assert.equal(hitsToKill(m4s, 20, 'chest', FULL_ARMOUR), 5)

  const awp = gun('awp')
  assert.equal(hitsToKill(awp, 30, 'chest', FULL_ARMOUR), 1)
  assert.equal(hitsToKill(awp, 30, 'legs', NO_ARMOUR), 2)
  assert.ok(accurateRange(awp, 'stand', true) > 50 && accurateRange(awp, 'stand') < 3, 'the AWP is accurate only scoped')
  assert.equal(oneShotHeadRange(gun('sg-553'), true)! > 100, true)
})

test('kevlar never covers the legs, and the head only with a helmet', () => {
  const ak = gun('ak-47')
  assert.deepEqual(hitDamage(ak, 10, 'legs', FULL_ARMOUR), hitDamage(ak, 10, 'legs', NO_ARMOUR))
  assert.deepEqual(hitDamage(ak, 10, 'head', { kevlar: 100, helmet: false }), hitDamage(ak, 10, 'head', NO_ARMOUR))
  assert.ok(hitDamage(ak, 10, 'head', FULL_ARMOUR).health < hitDamage(ak, 10, 'head', NO_ARMOUR).health)
  // Worn-out kevlar lets more through: it soaks at most half its points.
  const weak = hitDamage(ak, 10, 'chest', { kevlar: 1, helmet: false })
  assert.ok(weak.health > hitDamage(ak, 10, 'chest', FULL_ARMOUR).health)
  assert.equal(weak.armour, 1)
})

test('damage falls with distance and stops at the gun range', () => {
  const p250 = gun('p250')
  const rows = damageTable(p250)
  for (let i = 1; i < rows.length; i++) assert.ok(rows[i].chest.damage <= rows[i - 1].chest.damage)
  assert.equal(hitDamage(p250, (p250.maxRange + 10) * 0.0254, 'head', NO_ARMOUR).health, 0)
  assert.equal(hitsToKill(p250, (p250.maxRange + 10) * 0.0254, 'chest', NO_ARMOUR), null)
})

test('shotguns are counted in pellets and whole shots', () => {
  const nova = gun('nova')
  assert.equal(nova.pellets, 9)
  const h = hitsToKill(nova, 5, 'chest', FULL_ARMOUR)!
  assert.equal(timeToKill(nova, h), (Math.ceil(h / 9) - 1) * nova.cycleTime)
  const lines = ballisticsLines(arsenal.find((a) => a.id === 'nova')!)
  assert.match(lines[0], /pellets a shot/)
  assert.ok(!lines.some((l) => /One headshot/.test(l)))
})

test('the sentences for every gun avoid causal or absolute words', () => {
  for (const a of arsenal.filter((x) => x.ballistics)) {
    const lines = ballisticsLines(a)
    assert.ok(lines.length >= 3, a.name)
    for (const l of lines) assert.ok(!BANNED_CLAIMS.test(l), `${a.name}: ${l}`)
    const s = gunSummary(a.ballistics!)
    assert.ok(s.rpm > 0 && s.accurateStand > 0)
  }
})
