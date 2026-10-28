// The map learned from watching (shooters): where the player is from each
// look, deaths placed where their own view last placed them, killer spots
// from killcams, routes, height layers, the layout drawn from the pack's
// rough grid and the routes taken, weapon fit by range, and the answers.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { calloutMatcher, layoutMap, mapLines, mapModelFrom, MapTracker, weaponFitLine } from '../src/core/analyses/mapModel.ts'
import { arsenalText, mapsIndexText, mapText } from '../src/core/knowledge/base.ts'
import type { GameMap, PositioningSpec } from '../src/core/profile/schema.ts'
import type { GameEvent, VisibleState } from '../src/core/types.ts'
import { validateProfile } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const spec: PositioningSpec = {
  mapKey: 'mapName',
  locationKey: 'location',
  elevationKey: 'elevation',
  killerSpotKey: 'killerSpot',
  killerWeaponKey: 'killerWeapon',
  enemyPositionsKey: 'enemyPositions',
  weaponKey: 'ownWeapon',
  deathEvent: 'death',
  killEvent: 'kill',
  rangeAttribute: 'range',
  killedByAttribute: 'killedBy',
}

const yard: GameMap = {
  id: 'yard',
  name: 'Yard',
  callouts: [
    { name: 'Market', grid: 'c', elevation: ['ground'], look: 'fruit stalls, blue tarps', use: 'the middle' },
    { name: 'Clock Tower', aliases: ['Tower'], grid: 'n', elevation: ['ground', 'upper'], look: 'stone tower with a clock' },
    { name: 'Alley', grid: 'w', elevation: ['ground'] },
    { name: 'Garage', grid: 's', elevation: ['ground', 'roof'] },
  ],
  connections: [
    ['Market', 'Clock Tower'],
    ['Market', 'Alley'],
    ['Market', 'Garage'],
  ],
  powerPositions: [{ callout: 'Clock Tower', why: 'sees all of Market from above', counter: 'flash it from Alley' }],
  objectives: { hardpoint: ['Market', 'Garage', 'Clock Tower'], domination: { A: 'Alley', B: 'Market', C: 'Garage' } },
}

const fv = (value: string | number, observedAt: number) => ({ value, confidence: 0.9, observedAt, source: 'vision-model' as const, conflicted: false })
const visible = (values: Record<string, string | number>, at: number): VisibleState =>
  ({ timestampMs: at, phase: 'round', stale: false, staleReasons: [], activeSignals: [], values: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, fv(v, at)])) }) as unknown as VisibleState
const ev = (ruleId: string, at: number, attributes: Record<string, string> = {}) => ({ ruleId, at, uid: `${ruleId}-${at}`, attributes }) as unknown as GameEvent

test('callout names from the screen are matched to the map’s own (aliases, "the", extra words); new names are learned as read', () => {
  const m = calloutMatcher(yard)
  assert.equal(m('tower'), 'Clock Tower')
  assert.equal(m('The Market'), 'Market')
  assert.equal(m('market stalls'), 'Market')
  assert.equal(m('north warehouse'), 'North warehouse')
})

test('one match: time in areas, routes, heights, deaths placed by the player’s own view, killer spots, held angles, weapons by range', () => {
  const t = new MapTracker(spec, [yard])
  t.observe(visible({ mapName: 'Yard', location: 'Alley', elevation: 'ground', ownWeapon: 'MCW' }, 1000))
  t.observe(visible({ location: 'Market', elevation: 'ground', enemyPositions: '2 Clock Tower' }, 6000))
  t.observe(visible({ location: 'Market', elevation: 'ground' }, 9000))
  // Died 2 s after the last look placed them in Market; the killcam showed the killer in the tower.
  let placed = t.event(ev('death', 11_000, { range: 'mid', killedBy: 'marksman' }), visible({ killerSpot: 'tower', killerWeapon: 'Oris 8.6', ownWeapon: 'MCW' }, 11_000))
  assert.deepEqual(placed, { area: 'Market', killerSpot: 'Clock Tower', repeat: 1 })
  t.observe(visible({ location: 'Garage', elevation: 'roof' }, 30_000))
  t.event(ev('kill', 31_000, { range: 'close' }), visible({ ownWeapon: 'MCW' }, 31_000))
  t.observe(visible({ location: 'Market' }, 40_000))
  placed = t.event(ev('death', 42_000, { range: 'mid', killedBy: 'marksman' }), visible({ killerSpot: 'Clock Tower', ownWeapon: 'MCW' }, 42_000))
  assert.equal(placed?.repeat, 2, 'the second death from the same angle this match')
  // A death long after the last look is not placed (the player may have moved).
  placed = t.event(ev('death', 90_000, { range: 'long', killedBy: 'sniper' }), visible({}, 90_000))
  assert.equal(placed?.area, null)
  assert.equal(t.position(41_000).area, 'Market')

  const r = t.finish()!
  assert.equal(r.map, 'Yard')
  assert.equal(r.areas.Market.deaths, 2)
  assert.equal(r.areas.Market.seconds, 3, '6 s → 9 s; the gaps over 15 s are not counted')
  assert.equal(r.areas.Garage.kills, 1)
  assert.deepEqual(r.areas.Garage.elevations, { roof: 1 })
  assert.equal(r.areas['Clock Tower'].enemies, 4, 'two pinged, and the killer twice')
  assert.deepEqual(r.routes, { 'Alley→Market': 1, 'Garage→Market': 1 }, 'Market → Garage took 21 s: not a route')
  assert.deepEqual(r.sightlines, { 'Clock Tower→Market': 2 })
  assert.deepEqual(r.killerWeapons, { 'Oris 8.6': 1 })
  assert.deepEqual(r.weapons.MCW, { mid: { kills: 0, deaths: 2 }, close: { kills: 1, deaths: 0 } })
  assert.deepEqual(r.deathsBy, { mid: { marksman: 2 }, long: { sniper: 1 } })
  assert.equal(t.finish(), null, 'a new match starts empty')
})

test('across matches: the model of a map, what it says, and a layout that keeps north up', () => {
  const rec = (n: number) => {
    const t = new MapTracker(spec, [yard])
    t.observe(visible({ mapName: 'yard', location: 'Alley' }, 0))
    for (let i = 0; i < n; i++) {
      t.observe(visible({ location: 'Market' }, 5000 + i * 20_000))
      t.event(ev('death', 6000 + i * 20_000, { range: 'close', killedBy: 'smg' }), visible({ killerSpot: 'Clock Tower', ownWeapon: 'Signal 50' }, 6000 + i * 20_000))
      t.observe(visible({ location: 'Garage', elevation: 'roof' }, 12_000 + i * 20_000))
      for (let k = 0; k < 2; k++) t.event(ev('kill', 13_000 + i * 20_000 + k, { range: 'long' }), visible({ ownWeapon: 'Signal 50' }, 13_000 + i * 20_000))
    }
    return t.finish()!
  }
  const m = mapModelFrom([rec(2), rec(2)], [yard], 'Yard')!
  assert.equal(m.matches, 2)
  assert.equal(m.areas.find((a) => a.name === 'Market')!.deaths, 4)
  assert.deepEqual(m.sightlines[0], { from: 'Clock Tower', to: 'Market', n: 4 })
  const lines = mapLines(m)
  assert.equal(lines[0], 'Most deaths at Market (4 of 4), 4 of them shot from Clock Tower.')
  assert.ok(lines.includes('Your best area: Garage (8 kills, 0 deaths).'), lines.join(' | '))
  // Weapon fit: a sniper rifle losing close fights.
  const fit = weaponFitLine(m.weapons[0], 'long', m.deathsBy, (c) => c.toUpperCase())
  assert.equal(fit, 'Signal 50 is built for long range, but you lost 4 of 4 close-range fights with it, mostly to SMG. Play the lanes that suit it, or carry something for close range.')
  assert.equal(weaponFitLine(m.weapons[0], 'close', m.deathsBy), null, 'nothing to say when it loses where it is meant to fight')

  const laid = layoutMap(m)
  const pos = new Map(laid.map((a) => [a.name, a]))
  assert.equal(laid.length, 4, 'every callout, seen or not')
  assert.ok(pos.get('Clock Tower')!.y < pos.get('Market')!.y && pos.get('Market')!.y < pos.get('Garage')!.y, 'north up')
  assert.ok(pos.get('Alley')!.x < pos.get('Market')!.x, 'west on the left')
  assert.equal(pos.get('Garage')!.z, 2, 'seen on the roof')
  assert.equal(pos.get('Clock Tower')!.z, 1, 'an upper floor, from the reference')
  assert.deepEqual(layoutMap(m), laid, 'the same every time')
  // A map the pack does not know is still learned and drawn.
  const t = new MapTracker(spec, [yard])
  t.observe(visible({ mapName: 'Unknown Docks', location: 'Crane' }, 0))
  t.observe(visible({ location: 'Ship' }, 5000))
  const u = mapModelFrom([t.finish()!], [yard], 'Unknown Docks')!
  assert.equal(u.reference, null)
  assert.deepEqual(u.routes, [{ from: 'Crane', to: 'Ship', n: 1 }])
  assert.equal(layoutMap(u).length, 2)
})

test('prompt text: the current map’s callouts with how they look, the map list, the arsenal by name or in full', () => {
  const v = mapText(yard, { budget: 9000, detail: 'vision' })
  assert.match(v, /- Clock Tower \(also Tower\) \[n; ground\/upper\]: stone tower with a clock/)
  assert.match(v, /Report "location"/)
  const a = mapText(yard, { budget: 9000, detail: 'answer' })
  assert.match(a, /Power position Clock Tower: sees all of Market from above Counter: flash it from Alley/)
  assert.match(a, /Hardpoint rotation: Market → Garage → Clock Tower/)
  assert.match(mapsIndexText([yard], { budget: 2000, detail: 'vision' }), /- Yard/)
  const arsenal = [
    { id: 'mcw', name: 'MCW', category: 'weapon' as const, class: 'assault_rifle', bestRange: 'mid' as const, stats: { rpm: 750 }, summary: 'Laser AR.', counters: ['Outrange it'], look: 'boxy AR' },
    { id: 'ghost', name: 'Ghost', category: 'perk' as const, slot: 'Perk 1', summary: 'Hidden from UAV while moving.' },
  ]
  const av = arsenalText(arsenal, { budget: 2000, detail: 'vision' })
  assert.match(av, /### Weapons\n- MCW \(assault rifle, best mid\): boxy AR/)
  assert.match(arsenalText(arsenal, { budget: 2000, detail: 'answer' }), /- Ghost \(Perk 1\): Hidden from UAV while moving\./)
})

test('the MW4 pack reads position each look and validates', () => {
  const mw4 = builtInProfile('cod-mw4')
  assert.ok(validateProfile(mw4).ok)
  assert.equal(mw4.positioning?.locationKey, 'location')
  for (const k of ['location', 'elevation', 'killerSpot', 'killerWeapon', 'enemyPositions', 'ownWeapon']) assert.ok(mw4.vision!.state.some((s) => s.key === k), k)
  assert.ok(mw4.contextKeys?.includes('mapName'))
})
