// Counter-Strike 2 demo review (from what a demo holds) and the AoE4World opponent record.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isDemoData, onOverview, reviewDemo, type DemoData, type DemoDeath } from '../src/core/cs2/demo.ts'
import { toDemoData } from '../electron/cs2demo.ts'
import { opponentProfile } from '../src/core/aoe4/opponent.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'

const ME = '76561190000000001'
const MATE = '76561190000000002'
const E1 = '76561190000000011'
const E2 = '76561190000000012'
const players = [ME, MATE, E1, E2].map((steamid, i) => ({ steamid, name: `P${i}` }))

const death = (tick: number, attacker: string | null, victim: string, place = 'Outside', extra: Partial<DemoDeath> = {}): DemoDeath => ({
  tick,
  attacker,
  victim,
  assister: null,
  weapon: 'ak47',
  headshot: false,
  flashAssist: false,
  victimPos: [0, 0, -400],
  attackerPos: [10, 10, -400],
  victimPlace: place,
  attackerPlace: 'Ramp',
  ...extra,
})

/** Ten rounds, 1000 ticks each, us on T (team 2). */
function demo(): DemoData {
  const rounds = Array.from({ length: 10 }, (_, i) => ({ endTick: (i + 1) * 1000, winner: (i % 2 ? 'CT' : 'T') as 'T' | 'CT', reason: null }))
  const economy = rounds.flatMap((_, i) => [
    { round: i + 1, steamid: ME, team: 2 as const, equip: i === 3 || i === 4 ? 1000 : 4500, balance: 0 },
    { round: i + 1, steamid: MATE, team: 2 as const, equip: 4500, balance: 0 },
    { round: i + 1, steamid: E1, team: 3 as const, equip: 5000, balance: 0 },
    { round: i + 1, steamid: E2, team: 3 as const, equip: 5000, balance: 0 },
  ])
  const deaths: DemoDeath[] = []
  // Rounds 1–5: I die first at Outside, untraded.
  for (let r = 0; r < 5; r++) deaths.push(death(r * 1000 + 100, E1, ME, 'Outside'))
  // Round 6: I die and my teammate trades within 5 s (64 ticks a second).
  deaths.push(death(5100, E1, ME, 'Hut'), death(5100 + 64 * 3, MATE, E1, 'Hut'))
  // Round 7: opening kill with a headshot, then a trade kill for my teammate.
  deaths.push(death(6100, ME, E1, 'Ramp', { headshot: true }), death(6200, E2, MATE, 'Ramp'), death(6300, ME, E2, 'Ramp'))
  // Round 8: an assist.
  deaths.push(death(7100, MATE, E2, 'Silo', { assister: ME }))
  const hurts = [
    { tick: 6050, attacker: ME, victim: E1, dmg: 80, weapon: 'ak47' },
    { tick: 6090, attacker: ME, victim: E1, dmg: 60, weapon: 'ak47' }, // capped at 100 for E1
    { tick: 6250, attacker: ME, victim: E2, dmg: 100, weapon: 'ak47' },
    { tick: 6260, attacker: ME, victim: MATE, dmg: 30, weapon: 'ak47' }, // team damage: not counted
  ]
  const blinds = [
    { tick: 2050, attacker: ME, victim: MATE, seconds: 3 },
    { tick: 3050, attacker: ME, victim: MATE, seconds: 3 },
    { tick: 4050, attacker: ME, victim: MATE, seconds: 3 },
    { tick: 6040, attacker: ME, victim: E1, seconds: 2.5 },
    { tick: 6045, attacker: ME, victim: E2, seconds: 0.4 }, // too short to count
  ]
  const nades = [
    { tick: 6010, type: 'smoke' as const, thrower: ME, x: 0, y: 0, z: 0 },
    { tick: 6020, type: 'flash' as const, thrower: ME, x: 0, y: 0, z: 0 },
    { tick: 6030, type: 'he' as const, thrower: MATE, x: 0, y: 0, z: 0 },
  ]
  return { map: 'de_nuke', server: null, tickRate: 64, players, rounds, deaths, hurts, blinds, nades, economy }
}

test('demo review: kills, deaths, damage capped per enemy, opening duels, trades, KAST, flashes and grenades', () => {
  const d = demo()
  assert.ok(isDemoData(d))
  assert.ok(!isDemoData({ map: 'x' }))
  const r = reviewDemo(d, ME)!
  const t = r.totals
  assert.equal(t.kills, 2)
  assert.equal(t.deaths, 6)
  assert.equal(t.assists, 1)
  assert.equal(t.headshotPct, 50)
  assert.equal(t.adr, 20, '200 damage (100 + 100, team damage not counted) over 10 rounds')
  assert.equal(t.openingWon, 1)
  assert.equal(t.openingLost, 6)
  assert.equal(t.deathsTraded, 1)
  assert.equal(t.tradeKills, 1)
  // KAST: rounds 6 (traded), 7 (kills), 8 (assist), 9 and 10 (lived) = 5 of 10.
  assert.equal(t.kast, 50)
  assert.equal(t.enemiesFlashed, 1)
  assert.equal(t.teammatesFlashed, 3)
  assert.deepEqual(t.nades, { smoke: 1, flash: 1, he: 0, fire: 0 })
  assert.equal(r.rounds[0].side, 'T')
  assert.equal(r.rounds[0].won, true)
  assert.equal(r.rounds[1].won, false)
  assert.equal(r.rounds[6].opening, 'won')
  assert.equal(r.rounds[3].equip, 1000)
  assert.equal(r.rounds[3].teamEquip, 2750)
  assert.deepEqual(r.deathPlaces[0], { place: 'Outside', deaths: 5, first: 5 })
  assert.deepEqual(r.weapons, [{ weapon: 'AK-47', kills: 2 }])
  assert.equal(r.marks.filter((m) => m.kind === 'death').length, 6)
  assert.equal(reviewDemo(d, '76561190000000099'), null)
})

test('demo findings: died first, untraded deaths, one place, low damage, few grenades, team flashes; plain numbers, no banned words', () => {
  const r = reviewDemo(demo(), ME)!
  const all = r.findings.join(' ')
  assert.ok(r.findings.length >= 3 && r.findings.length <= 4, r.findings.join(' | '))
  assert.match(r.findings[0], /^You died first in 6 of 10 rounds \(won 1 of 7 opening duels\), 5 times at Outside\./)
  assert.ok(r.findings.some((f) => /^Only 1 of your 6 deaths were traded\./.test(f)) || r.findings.some((f) => /5 of your 6 deaths were at Outside/.test(f)), all)
  assert.doesNotMatch(all, BANNED_CLAIMS)
})

test('demo reader: the knife round and warm-up before the match start are left out; economy from the last freeze end of each round', () => {
  const ev = [
    { event_name: 'round_end', tick: 1, round: 0, winner: null },
    { event_name: 'round_freeze_end', tick: 100 },
    { event_name: 'player_death', tick: 200, attacker_steamid: E1, user_steamid: ME, weapon: 'knife_t', user_X: 1, user_Y: 2, user_Z: 3 },
    { event_name: 'round_end', tick: 900, round: 1, winner: 'CT', reason: 't_killed' }, // knife round
    { event_name: 'begin_new_match', tick: 1000 },
    { event_name: 'round_freeze_end', tick: 1100 },
    { event_name: 'round_freeze_end', tick: 1200 },
    { event_name: 'player_death', tick: 1300, attacker_steamid: ME, user_steamid: E1, weapon: 'usp_silencer', headshot: true, user_X: 5.4, user_Y: 6, user_Z: 7, user_last_place_name: 'Lobby' },
    { event_name: 'player_hurt', tick: 1290, attacker_steamid: ME, user_steamid: E1, dmg_health: 100, weapon: 'usp_silencer' },
    { event_name: 'flashbang_detonate', tick: 1250, user_steamid: ME, x: 1, y: 2, z: 3 },
    { event_name: 'player_blind', tick: 1251, attacker_steamid: ME, user_steamid: E1, blind_duration: 2.345 },
    { event_name: 'round_end', tick: 2000, round: 1, winner: 'T', reason: 'ct_killed' },
    { event_name: 'round_freeze_end', tick: 2100 },
    { event_name: 'round_end', tick: 3000, round: 2, winner: 'CT', reason: 't_killed' },
  ]
  const eco = [1100, 1200, 2100].flatMap((tick) => [
    { tick, steamid: ME, team_num: 2, current_equip_value: tick === 1100 ? 0 : 200, balance: 600 },
    { tick, steamid: E1, team_num: 3, current_equip_value: 200, balance: 600 },
  ])
  const info = [
    { steamid: ME, name: 'Me', team_number: 2 },
    { steamid: E1, name: 'Them', team_number: 3 },
    { steamid: '0', name: 'GOTV', team_number: 1 },
  ]
  const d = toDemoData({ map_name: 'de_nuke', server_name: 'FACEIT' }, info, ev, eco)
  assert.equal(d.rounds.length, 2)
  assert.deepEqual(d.rounds[0], { endTick: 2000, winner: 'T', reason: 'ct_killed' })
  assert.equal(d.deaths.length, 1, 'the knife-round death is left out')
  assert.deepEqual(d.deaths[0].victimPos, [5, 6, 7])
  assert.equal(d.deaths[0].headshot, true)
  assert.equal(d.players.length, 2, 'GOTV is not a player')
  assert.deepEqual(d.blinds[0].seconds, 2.3)
  assert.equal(d.nades[0].type, 'flash')
  assert.deepEqual(
    d.economy.map((e) => [e.round, e.steamid, e.equip]),
    [
      [1, ME, 200],
      [1, E1, 200],
      [2, ME, 200],
      [2, E1, 200],
    ],
  )
  const r = reviewDemo(d, ME)!
  assert.equal(r.totals.kills, 1)
  assert.deepEqual(r.weapons, [{ weapon: 'USP-S', kills: 1 }])
  assert.equal(r.rounds[0].won, true)
})

test('demo marks land on the pack\'s Nuke overview', () => {
  const nuke = builtInProfile('cs2').knowledge.maps!.find((m) => m.id === 'nuke')!
  const [u, v] = onOverview(nuke.radar!, nuke.radar!.posX + 7 * 512, nuke.radar!.posY - 7 * 256)
  assert.ok(Math.abs(u - 0.5) < 1e-9 && Math.abs(v - 0.25) < 1e-9)
})

test('opponent record: civilisations most played first, recent form, and openings from their last games', () => {
  const games = [
    { gameId: 3, startedAt: 3, durationSec: 1500, map: 'Dry Arabia', kind: 'rm_1v1', civ: 'english', opponents: ['french'], result: 'win' as const, rating: 1210 },
    { gameId: 2, startedAt: 2, durationSec: 1500, map: 'Lipany', kind: 'rm_1v1', civ: 'english', opponents: ['rus'], result: 'loss' as const, rating: 1200 },
    { gameId: 1, startedAt: 1, durationSec: 1500, map: 'Lipany', kind: 'rm_1v1', civ: 'mongols', opponents: ['rus'], result: 'win' as const, rating: 1190 },
  ]
  const facts = [
    {
      gameId: 3,
      villagers: {},
      ages: { feudal: 290 },
      build: [
        { t: 120, kind: 'unit' as const, item: 'scout' },
        { t: 291, kind: 'building' as const, item: 'building_landmark_age1_council_hall' },
        { t: 300, kind: 'unit' as const, item: 'longbowman_2' },
        { t: 320, kind: 'unit' as const, item: 'longbowman_2' },
        { t: 340, kind: 'unit' as const, item: 'spearman_1' },
      ],
      opponentArmy: [],
    },
  ]
  const p = opponentProfile(games, facts)
  assert.equal(p.games, 3)
  assert.equal(p.wins, 2)
  assert.equal(p.rating, 1210)
  assert.deepEqual(p.civs[0], { civ: 'english', games: 2, wins: 1 })
  assert.deepEqual(p.recent, ['win', 'loss', 'win'])
  assert.equal(p.openings.length, 1)
  assert.equal(p.openings[0].feudal, 290)
  assert.equal(p.openings[0].landmark, 'building_landmark_age1_council_hall')
  assert.equal(p.openings[0].firstUnits.length, 2)
  assert.match(p.openings[0].firstUnits[0], /^longbowman/)
  assert.match(p.openings[0].firstUnits[1], /^spearman/)
})
