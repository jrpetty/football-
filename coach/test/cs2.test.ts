// Counter-Strike 2: the game's own data (Game State Integration) turned into
// kills, deaths (flashed, reloading), rounds with the player's buy, money and
// result; the screen's details of each death added to the death the game
// reported; and the CS2 demo end to end through the engine.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { Cs2Gsi, isCs2Rounds } from '../src/core/gsi/cs2.ts'
import { roundInsights, roundTotals } from '../src/core/analyses/rounds.ts'
import { isMatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { cs2MatchReplay } from '../src/sim/cs2Match.ts'
import { demoMapFrom } from '../src/sim/visionReplay.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { engineFor, settings } from './helpers.ts'

const cs2 = builtInProfile('cs2')
const mirage = cs2.knowledge.maps!.find((m) => m.id === 'mirage')!

function replay(seed: number) {
  return cs2MatchReplay({ seed, map: demoMapFrom(mirage)!, mapId: 'de_mirage' })
}

test('Game State Integration: names, kills, deaths and each round with the buy and result', () => {
  const r = replay(3)
  const g = new Cs2Gsi({ profileId: 'cs2', maps: cs2.knowledge.maps, arsenal: cs2.knowledge.arsenal })
  assert.equal(g.mapName('de_dust2'), 'Dust II')
  assert.equal(g.mapName('de_mirage'), 'Mirage')
  assert.equal(g.weaponName('weapon_m4a1_silencer'), 'M4A1-S')
  assert.equal(g.weaponName('weapon_ak47'), 'AK-47')
  let kills = 0
  let deaths = 0
  let blind = 0
  for (const u of r.gameState!) {
    for (const o of g.payload(u.payload, u.at)) {
      if (o.kind !== 'event') continue
      if (o.eventType === 'kill') kills++
      if (o.eventType === 'death') {
        deaths++
        if (o.attributes?.blind === 'blind') blind++
      }
    }
  }
  const truth = r.truth as { rounds: number; won: number; kills: number; deaths: number; blindDeaths: number; byBuy: Record<string, number>; score: Record<string, number> }
  assert.equal(kills, truth.kills)
  assert.equal(deaths, truth.deaths)
  assert.equal(blind, truth.blindDeaths)
  const rounds = g.snapshot()!
  assert.ok(isCs2Rounds(JSON.parse(JSON.stringify(rounds))))
  assert.equal(rounds.rounds.length, truth.rounds)
  assert.equal(rounds.rounds.filter((x) => x.result === 'won').length, truth.won)
  for (const b of ['pistol', 'eco', 'force', 'full'] as const) assert.equal(rounds.rounds.filter((x) => x.buy === b).length, truth.byBuy[b], b)
  assert.equal(rounds.rounds[0].buy, 'pistol')
  assert.equal(rounds.rounds[0].startMoney, 800)
  assert.ok(rounds.rounds.every((x) => x.reason !== null))
  // Damaged stored rounds are refused.
  assert.equal(isCs2Rounds({ ...rounds, rounds: [{ ...rounds.rounds[0], buy: 'yolo' }] }), false)
  const totals = roundTotals(rounds.rounds)
  assert.equal(totals.kills, truth.kills)
  assert.ok(roundInsights([rounds]).some((l) => l.id === 'per-round'))
})

test('an update that carries only what changed is merged into the last', () => {
  const g = new Cs2Gsi({ profileId: 'cs2', maps: cs2.knowledge.maps })
  const full = {
    provider: { steamid: '1' },
    map: { name: 'de_inferno', phase: 'live', mode: 'competitive', team_ct: { score: 3 }, team_t: { score: 1 } },
    round: { phase: 'live' },
    player: { steamid: '1', team: 'T', state: { health: 100, money: 2000, round_kills: 0, round_killhs: 0, flashed: 0 } },
  }
  const a = g.payload(full, 1000)
  assert.equal(a.find((o) => o.eventType === 'mapName')?.value, 'Inferno')
  assert.equal(a.find((o) => o.eventType === 'playerScore')?.value, 1)
  assert.equal(a.find((o) => o.eventType === 'phase')?.value, 'combat')
  // Only the changed fields: a kill.
  const b = g.payload({ player: { state: { round_kills: 1, round_killhs: 1 } } }, 1500)
  const kill = b.find((o) => o.kind === 'event' && o.eventType === 'kill')
  assert.equal(kill?.attributes?.headshot, 'head')
  assert.equal(kill?.source, 'game-api')
  // Spectating someone else mid-round, without the update that showed health at zero: a death.
  const c = g.payload({ player: { steamid: '2', team: 'T', state: { health: 80 } } }, 4000)
  assert.equal(c.filter((o) => o.kind === 'event' && o.eventType === 'death').length, 1)
  assert.equal(g.status(4000).watching, 'other')
  assert.equal(g.status(30_000).connected, false)
})

test('CS2 demo end to end: the game data and the screen together, rounds in the match record', () => {
  const r = replay(5)
  const s = settings({ profileId: 'cs2', profileModeId: 'practice', mode: 'training' })
  const e = engineFor(cs2, s)
  const updates = [...r.gameState!].sort((a, b) => a.at - b.at)
  const batches = [...r.batches].sort((a, b) => a.arrivesAt - b.arrivesAt)
  let u = 0
  let v = 0
  for (let t = 0; t <= r.endsAt; t += 100) {
    while (u < updates.length && updates[u].at <= t) e.ingestGameState(updates[u++].payload, t)
    while (v < batches.length && batches[v].arrivesAt <= t) e.ingestObservations(batches[v].observations, batches[v++].arrivesAt)
  }
  const rep = e.report()
  const truth = r.truth as { rounds: number; deaths: number; kills: number; blindDeaths: number }
  assert.equal(rep.matches.length, 1, 'the match ended on the game-over update')
  const m = rep.matches[0]
  assert.ok(m.roundLog, 'the match record has the rounds')
  assert.equal(m.roundLog.rounds.length, truth.rounds)
  assert.ok(isMatchRecord(JSON.parse(JSON.stringify(m))))
  // Every death counted once (the screen's sighting of each added its details, not a second death).
  const factors = rep.factors
  const traded = factors.find((f) => f.spec.id === 'traded')!
  assert.equal(traded.taggedLosses + traded.untagged, truth.deaths)
  assert.ok(traded.taggedLosses >= truth.deaths - 1, `${traded.taggedLosses} of ${truth.deaths} deaths got the screen's details`)
  const blind = factors.find((f) => f.spec.id === 'blind')!
  assert.equal(blind.rows.find((x) => x.value === 'blind')!.losses, truth.blindDeaths)
  assert.equal(rep.context.mapName ?? m.context?.mapName, 'Mirage')
  assert.ok((rep.rounds?.insights ?? []).length >= 2, (rep.rounds?.insights ?? []).map((l) => l.id).join(', '))
})
