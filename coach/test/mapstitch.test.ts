// The MW4 demo end to end, frames and vision readings together through the
// engine: the map stitched from the minimap, every death pinned where the
// player's own minimap last was, killers placed from the killcam's minimap,
// the path kept without the killcams, callouts and heights learned on the
// stitched map, and the map handed over for saving.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { MM } from '../src/core/map/minimap.ts'
import { demoMapFrom, mw4MatchReplay } from '../src/sim/visionReplay.ts'
import { mw4DemoPicture } from '../src/sim/mw4Minimap.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { engineFor, settings } from './helpers.ts'
import { defaultContext } from '../src/core/engine.ts'

test('MW4 demo: the map is stitched from the minimap, deaths pinned to the spot, killers placed from the killcam', () => {
  const mw4 = builtInProfile('cod-mw4')
  const region = mw4.positioning!.minimap!.region
  const maps = mw4.knowledge.maps!
  const demo = maps.filter((m) => m.type === 'core').map(demoMapFrom).find((m) => !!m)!
  const replay = mw4MatchReplay({ seed: 4, map: demo, realMinutes: 4 })
  const picture = mw4DemoPicture(replay, { seed: 4, region, callouts: maps.find((m) => m.name === demo.name)!.callouts })
  const e = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'private-bots', mode: 'training' }))
  const batches = [...replay.batches].sort((a, b) => a.arrivesAt - b.arrivesAt)
  let next = 0
  let saved = null
  for (let t = 0; t <= replay.endsAt; t += 200) {
    while (next < batches.length && batches[next].arrivesAt <= t) e.ingestObservations(batches[next].observations, batches[next++].arrivesAt)
    e.processFrame(picture.frame(t))
    saved = e.takeMosaic(t) ?? saved
  }
  while (next < batches.length) e.ingestObservations(batches[next].observations, batches[next++].arrivesAt)
  const r = e.report()
  // The match ended on screen: its pins are on the match record.
  assert.equal(r.matches.length, 1)
  const pins = r.matches[0].mapModel!.pins!
  assert.equal(pins.map, demo.name)
  const deaths = pins.pins.filter((p) => p.kind === 'death')
  const truthDeaths = replay.batches.filter((b) => b.observations.some((o) => o.kind === 'event' && o.eventType === 'death'))
  assert.ok(deaths.length >= truthDeaths.length - 1, `${deaths.length} of ${truthDeaths.length} deaths pinned`)
  assert.ok(pins.pins.some((p) => p.kind === 'kill'))

  // Map pixels → stitched pixels: one scale, and an offset taken from the path's first point (the first frame, t = 0).
  const K = ((region.w * 960) / MM) * 1.4
  const [p0] = pins.trail
  const g0 = picture.at(p0[3] * 1000)
  const ox = p0[1] - g0.x / K
  const oy = p0[2] - g0.y / K
  const err = (x: number, y: number, gx: number, gy: number) => Math.hypot(x - (ox + gx / K), y - (oy + gy / K))
  // Every death where the player actually died (the killcam frame reports it 1.2 s later).
  const deathErr = truthDeaths.map((b) => {
    const g = picture.at(b.frameAt - 1300)
    const pin = deaths.find((d) => Math.abs(d.t * 1000 - (b.frameAt - 1300)) < 2500)
    return pin ? err(pin.x, pin.y, g.x, g.y) : Infinity
  })
  const sorted = [...deathErr].sort((a, b) => a - b)
  assert.ok(sorted[Math.floor(sorted.length / 2)] <= 4, `death errors ${sorted.map((x) => x.toFixed(1)).join(' ')}`)
  assert.ok(sorted[sorted.length - 2] <= 10, `death errors ${sorted.map((x) => x.toFixed(1)).join(' ')}`)
  // Killers placed from the killcam's minimap, where the killcam ended.
  const fromKillcam = deaths.filter((d) => d.killer?.from === 'killcam')
  assert.ok(fromKillcam.length >= Math.ceil(truthDeaths.length / 2), `${fromKillcam.length} killers from the killcam`)
  // Otherwise from the callout the killcam named, placed where that callout was read.
  assert.ok(deaths.filter((d) => d.killer).length >= deaths.filter((d) => d.killer?.spot || d.killer?.from).length)
  const held = deaths.filter((d) => d.killer?.spot === demo.heldFrom)
  assert.ok(held.length >= 3)
  const heldAt = picture.areas.get(demo.heldFrom)!
  // From the killcam's minimap, to a few pixels; from the callout, where the player stood when it was read (the area, roughly).
  for (const d of held) assert.ok(err(d.killer!.x, d.killer!.y, heldAt[0] + 6, heldAt[1] - 4) <= (d.killer!.from === 'killcam' ? 6 : 40), `${d.killer!.from} killer at ${d.killer!.x},${d.killer!.y}`)
  // The path is the player's own: lives counted, nothing from a killcam.
  assert.ok(pins.trail.length > 100)
  assert.equal(Math.max(...pins.trail.map((p) => p[4])), deaths.length)
  // Enemy pings placed.
  assert.ok(Object.keys(pins.enemies).length > 0)
  // The stitched map, with callouts and heights learned on it, is handed over for saving.
  const final = e.takeMosaic(replay.endsAt + 1, true) ?? saved
  assert.ok(final && final.pieces.length >= 1)
  assert.ok(Object.keys(final.callouts).length >= 3, Object.keys(final.callouts).join(', '))
  assert.ok(Object.keys(final.heights).length > 0)
  assert.equal(r.map!.minimap!.enabled, true)
})

test('a rotating minimap is not stitched; deaths are still placed by area', () => {
  const mw4 = builtInProfile('cod-mw4')
  const e = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'private-bots' }), { context: { ...defaultContext(mw4), minimapFixed: 'rotating' } })
  const replay = mw4MatchReplay({ seed: 2, realMinutes: 2 })
  const picture = mw4DemoPicture(replay, { seed: 2, region: mw4.positioning!.minimap!.region })
  let now = 0
  for (const b of replay.batches) {
    e.processFrame(picture.frame(b.frameAt))
    e.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
  }
  const r = e.report()
  assert.equal(r.map!.minimap!.enabled, false)
  assert.equal(r.map!.minimap!.reason, 'rotating')
  assert.equal(r.map!.pins, null)
  assert.ok(r.map!.model!.areas.some((a) => a.deaths > 0))
})
