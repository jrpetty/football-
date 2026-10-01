// Camp stations, each modelled as a work area at three levels: improvised
// (wood, tarps, scavenged junk), built (timber frames, corrugated roofs, some
// machinery) and industrial (concrete pads, steel, powered machines).
// Every model reports where workers stand, which pivots animate, and where
// lights, flames and smoke come from (userData.info).
import { Builder, seeded } from './kit.js'
import { STATIONS } from '../game/data.js'
import {
  COL, shadeHex, crate, ammoCrate, barrel, jerrycan, sack, sandbags, pallet, tireStack, tire, log, logPile, plankStack, firewood, stump,
  scrapPile, tarp, corrRoof, gableRoof, lantern, lampPost, bulb, stringLights, table, stool, bench, logBench, campChair, cot, bedroll, shelf,
  toolWall, vise, anvil, sawhorse, ladder, toolbox, bucket, wheelbarrow, hayBale, gasBottle, cableReel, sign, ibcTote, waterTank, pipe,
  valveWheel, gauge, carBattery, radioSet, monitor, solarPanel, clothesline, mannequin, cropRow, scarecrow, cinderBlocks, bricks, rock,
  barbedCoil, generatorSmall, screenMat, shelfItems,
} from './parts.js'
import { STATIONS2 } from './stations2.js'
import { HD } from './stationsHD.js'
import { slab, deck, boardWall, windowPane, door, frame, hangingTools, carWreck, roof } from './stationkit.js'
export { slab, deck, boardWall, windowPane, door, frame, carWreck }

const TAU = Math.PI * 2
const FACE_BACK = Math.PI // facing −z (towards the back of the footprint)

// ---------------------------------------------------------------- living
const M = {
  campfire(b, L, I) {
    const rnd = seeded(101)
    b.cyl(1.25, 1.35, 0.03, { mat: 'dirt', color: '#8a7660', y: 0.0, seg: 24, shadow: false })
    b.cyl(0.62, 0.64, 0.035, { mat: 'plain', color: '#2a2622', y: 0.02, seg: 20 })
    for (let i = 0; i < 16; i++) {
      const a = (i / 16) * TAU + rnd() * 0.15
      rock(b, { x: Math.cos(a) * 0.74, z: Math.sin(a) * 0.74, s: 0.14 + rnd() * 0.06, seed: i + 3, color: shadeHex('#8a8680', -rnd() * 0.25) })
    }
    for (let i = 0; i < 7; i++) {
      const a = (i / 7) * TAU + 0.3
      b.beam([Math.cos(a) * 0.48, 0.05, Math.sin(a) * 0.48], [Math.cos(a) * 0.05, 0.62, Math.sin(a) * 0.05], 0.1, 0.1, { mat: 'bark', color: i % 2 ? '#5a4a3a' : '#3a302a', round: true })
    }
    for (let i = 0; i < 10; i++) b.box(0.12, 0.05, 0.08, { mat: 'embers', color: '#ffffff', x: (rnd() - 0.5) * 0.6, y: 0.06, z: (rnd() - 0.5) * 0.6, ry: rnd() * 3, shadow: false })
    I.flames.push({ x: 0, y: 0.06, z: 0, w: 0.95, h: 1.35, when: 'always' })
    I.lights.push({ x: 0, y: 0.9, z: 0, color: '#ff8a3a', intensity: 7, dist: 15, flicker: true, when: 'always' })
    I.emitters.push({ kind: 'campfire', x: 0, y: 0.4, z: 0, when: 'always' })
    // cooking tripod with a pot
    for (let i = 0; i < 3; i++) {
      const a = (i / 3) * TAU + 0.5
      b.beam([Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9], [0, 1.55, 0], 0.04, 0.04, { mat: 'steel', color: '#3a3a3a', round: true })
    }
    b.rope([[0, 1.55, 0], [0, 1.0, 0]], 0.006, { mat: 'steel', color: '#3a3a3a', sag: 0, steps: 2 })
    b.lathe([[0.16, 0], [0.2, 0.06], [0.21, 0.2], [0.2, 0.24]], { mat: 'metal', color: '#3a3a38', y: 0.76, seg: 16 })
    b.torus(0.2, 0.006, { mat: 'steel', color: '#3a3a3a', y: 0.98, rz: Math.PI / 2, arc: Math.PI })
    // benches all round, gaps on the gate side
    const seats = [
      [0, -1.6, 0],
      [-1.6, 0, Math.PI / 2],
      [1.6, 0, Math.PI / 2],
      [-1.15, 1.2, -Math.PI / 4],
    ]
    for (const [x, z, ry] of seats) logBench(b, { x, z, ry, len: 1.5 })
    for (const [x, z, ry] of seats) {
      for (const k of [-0.45, 0.45]) {
        const sx = x + Math.cos(ry) * k
        const sz = z - Math.sin(ry) * k
        I.seats.push({ x: sx, z: sz, face: Math.atan2(-sx, -sz), y: 0.36 - 0.46 + 0.02 })
      }
    }
    campChair(b, { x: 1.15, z: 1.25, ry: Math.PI + Math.PI / 4 + 0.3, color: '#7a3a2a' })
    I.seats.push({ x: 1.15, z: 1.2, face: Math.atan2(-1.15, -1.2), y: 0 })
    // firewood stack and kit
    firewood(b, { x: -1.55, z: -1.55, ry: Math.PI / 4, len: 0.9, h: 0.6, roof: false })
    crate(b, { x: 1.55, z: -1.5, ry: 0.4, w: 0.5, h: 0.4, d: 0.5 })
    b.cyl(0.05, 0.05, 0.1, { mat: 'metal', color: '#c8ccd0', x: 1.5, y: 0.45, z: -1.45 })
    b.cyl(0.045, 0.04, 0.09, { mat: 'paint', color: '#e8e4dc', x: 1.62, y: 0.445, z: -1.58 })
    b.lathe([[0.08, 0], [0.1, 0.12], [0.06, 0.18], [0.02, 0.2]], { mat: 'metal', color: '#4a6a8a', x: 0.5, y: 0.04, z: 0.55, seg: 12 })
    // a guitar leaning on a bench
    b.at({ x: -1.62, y: 0.32, z: 0.55, rx: -0.35, ry: 0.4 }, () => {
      b.sphere(0.17, { mat: 'wood', color: '#c8884a', sz: 0.35, y: 0.18 })
      b.sphere(0.13, { mat: 'wood', color: '#c8884a', sz: 0.35, y: 0.42 })
      b.cyl(0.04, 0.04, 0.005, { mat: 'plain', color: '#1a1410', y: 0.32, z: 0.06, rx: Math.PI / 2 })
      b.box(0.05, 0.5, 0.025, { mat: 'wood', color: '#3a2418', y: 0.75 })
      b.box(0.07, 0.14, 0.03, { mat: 'wood', color: '#3a2418', y: 1.06 })
    })
  },

  bunkhouse(b, L, I) {
    const { w, d } = I
    if (L === 1) {
      // two canvas tents with a clothesline between them
      for (const [tx, color, seed] of [[-1.4, COL.canvasOlive, 1], [1.45, COL.canvas, 2]]) {
        b.at({ x: tx, z: -0.3 }, () => {
          const tw = 2.3
          const td = 3.0
          const th = 1.75
          const slope = Math.atan2(th, tw / 2)
          const len = Math.hypot(tw / 2, th)
          roof(b, I, (r) => {
            for (const s of [-1, 1]) r.box(len, 0.02, td, { mat: 'canvas', color, x: (s * tw) / 4, y: th / 2, rz: -s * slope })
          })
          b.wedge(tw, th, 0.02, { mat: 'canvas', color: shadeHex(color, -0.08), z: -td / 2 })
          // open front flaps
          for (const s of [-1, 1]) b.wedge(tw * 0.42, th * 0.95, 0.02, { mat: 'canvas', color: shadeHex(color, -0.05), x: s * tw * 0.42, z: td / 2 + 0.25, ry: s * 0.5 })
          b.box(0.02, 0.02, 0.1, { mat: 'plain', color: '#1a1814', y: th * 0.5, z: td / 2 - 0.01 })
          b.cyl(0.03, 0.03, th, { mat: 'wood', color: COL.woodGrey, y: th / 2, z: td / 2 })
          b.cyl(0.03, 0.03, th, { mat: 'wood', color: COL.woodGrey, y: th / 2, z: -td / 2 })
          b.cyl(0.025, 0.025, td + 0.3, { mat: 'wood', color: COL.woodGrey, y: th + 0.02, rx: Math.PI / 2 })
          for (const s of [-1, 1]) for (const z of [-td / 2, 0, td / 2]) {
            b.rope([[s * tw * 0.36, th * 0.3, z], [s * (tw / 2 + 0.55), 0.02, z]], 0.006, { mat: 'cloth', color: COL.rope, sag: 0.01, steps: 2 })
            b.box(0.03, 0.1, 0.03, { mat: 'wood', color: COL.woodDark, x: s * (tw / 2 + 0.55), y: 0.03, z })
          }
          // floor and bedrolls inside, visible through the door
          b.box(tw - 0.1, 0.02, td, { mat: 'canvas', color: '#5a5040', y: 0.01 })
          bedroll(b, { x: -0.5, z: 0.1, color: seed === 1 ? '#4a5a6a' : '#6a3a3a' })
          bedroll(b, { x: 0.5, z: 0.1, color: seed === 1 ? '#3a4a3a' : '#5a5a6a' })
        })
      }
      clothesline(b, [-2.6, 1.7, 1.9], [2.6, 1.7, 1.9], { seed: 4 })
      lampPost(b, { x: 0.05, z: 1.3, h: 2.2 }) && I.lights.push({ x: 0.45, y: 1.6, z: 1.3, color: '#ffb060', intensity: 2.2, dist: 7, when: 'night' })
      crate(b, { x: -2.5, z: 1.1, ry: 0.3, w: 0.5, h: 0.45, d: 0.5 })
      campChair(b, { x: 2.4, z: 1.0, ry: Math.PI + 0.6, color: '#3a6a5a' })
      I.beds.push({ x: -1.9, y: 0.1, z: -0.2, face: 0 }, { x: -0.9, y: 0.1, z: -0.2, face: 0 }, { x: 0.95, y: 0.1, z: -0.2, face: 0 }, { x: 1.95, y: 0.1, z: -0.2, face: 0 })
      return
    }
    if (L === 2) {
      // a timber cabin with a porch
      const cw = 5.4
      const cd = 3.4
      const h = 2.5
      const z0 = -0.6
      b.at({ z: z0 }, () => {
        b.box(cw, 0.3, cd, { mat: 'concrete', color: '#c8c4bc', y: 0.15 })
        boardWall(b, -cw / 2, cw / 2, cd / 2, h, { y0: 0.3, holes: [[-1.95, -1.15, 0.9, 1.9], [-0.45, 0.45, 0, 2.1], [1.15, 1.95, 0.9, 1.9]], seed: 3 })
        boardWall(b, -cw / 2, cw / 2, -cd / 2, h, { y0: 0.3, seed: 4 })
        b.at({ ry: Math.PI / 2 }, () => {
          boardWall(b, -cd / 2, cd / 2, cw / 2, h, { y0: 0.3, seed: 5, holes: [[-0.4, 0.4, 0.9, 1.8]] })
          boardWall(b, -cd / 2, cd / 2, -cw / 2, h, { y0: 0.3, seed: 6 })
        })
        for (const x of [-1.55, 1.55]) windowPane(b, { x, y: 1.7, z: cd / 2 + 0.03, w: 0.75, h: 0.75, shutters: '#5a7a5a' })
        windowPane(b, { x: cw / 2 + 0.03, y: 1.65, z: 0, w: 0.75, h: 0.65, ry: Math.PI / 2 })
        door(b, { z: cd / 2 + 0.02, y: 0.3, w: 0.88, h: 1.95, color: '#8a5a3a' })
        roof(b, I, (r) => gableRoof(r, { w: cw, d: cd, y: h + 0.3, rise: 1.1, over: 0.35, mat: 'roofmetal', color: '#7a3a2a', gableColor: '#e0d0b8' }))
        // stove pipe
        b.cyl(0.08, 0.08, 1.6, { mat: 'metal', color: '#3a3a3a', x: 1.8, y: h + 1.4, z: -0.6 })
        b.cyl(0.14, 0.1, 0.12, { mat: 'metal', color: '#3a3a3a', x: 1.8, y: h + 2.25, z: -0.6 })
        I.emitters.push({ kind: 'chimney', x: 1.8, y: h + 2.3, z: z0 - 0.6, when: 'night' })
      })
      // porch
      deck(b, cw, 1.3, 0.3, { z: z0 + cd / 2 + 0.65, color: '#e8d8bc' })
      for (const sx of [-1, 1]) b.box(0.1, 2.3, 0.1, { mat: 'wood', color: COL.woodGrey, x: sx * (cw / 2 - 0.1), y: 1.45, z: z0 + cd / 2 + 1.2 })
      b.box(cw + 0.2, 0.025, 1.6, { mat: 'corrugated', color: '#b8b0a4', y: 2.62, z: z0 + cd / 2 + 0.75, rx: 0.18 })
      bench(b, { x: -1.4, z: z0 + cd / 2 + 0.35, len: 1.4 })
      I.seats.push({ x: -1.75, z: z0 + cd / 2 + 0.4, face: 0, y: 0.3 }, { x: -1.05, z: z0 + cd / 2 + 0.4, face: 0, y: 0.3 })
      lantern(b, { x: 0.65, y: 2.05, z: z0 + cd / 2 + 1.1 })
      I.lights.push({ x: 0.65, y: 2.15, z: z0 + cd / 2 + 1.1, color: '#ffb060', intensity: 2.5, dist: 8, when: 'night' })
      barrel(b, { x: cw / 2 + 0.2, z: z0 - cd / 2 + 0.3, color: '#3a5a7a', open: true, contents: '#3a5a6a', fill: 0.9 })
      b.box(0.08, 0.08, 1.4, { mat: 'metal', color: '#8a9094', x: cw / 2 + 0.1, y: 2.6, z: z0 - 0.9 })
      firewood(b, { x: -cw / 2 - 0.05, z: z0 - 0.3, ry: Math.PI / 2, len: 1.6, h: 0.9 })
      I.beds.push({ x: -1.5, y: 0.35, z: z0, face: 0 }, { x: 0, y: 0.35, z: z0, face: 0 }, { x: 1.5, y: 0.35, z: z0, face: 0 })
      return
    }
    // L3: a two-storey bunkhouse with an outside stair and balcony
    const cw = 5.6
    const cd = 3.2
    const z0 = -0.75
    const fh = 2.6
    b.at({ z: z0 }, () => {
      slab(b, I, { w: cw + 0.3, d: cd + 0.3, h: 0.15 })
      for (let f = 0; f < 2; f++) {
        const y0 = 0.15 + f * fh
        b.box(cw, fh - 0.1, cd, { mat: 'siding', color: f ? '#c8d4d0' : '#d8d0bc', y: y0 + (fh - 0.1) / 2 })
        b.box(cw + 0.06, 0.12, cd + 0.06, { mat: 'wood', color: '#e8e4dc', y: y0 + fh - 0.06 })
        for (const x of [-2.05, -0.7, 0.7, 2.05]) windowPane(b, { x, y: y0 + 1.5, z: cd / 2 + 0.02, w: 0.7, h: 0.8, frame: '#f0ece4' })
        for (const z of [-0.6, 0.6]) windowPane(b, { x: cw / 2 + 0.02, y: y0 + 1.5, z, w: 0.6, h: 0.75, ry: Math.PI / 2, frame: '#f0ece4' })
      }
      door(b, { x: 0, z: cd / 2 + 0.02, y: 0.15, color: '#5a6a7a', w: 0.9 })
      door(b, { x: -1.4, z: cd / 2 + 0.02, y: 0.15 + fh, color: '#5a6a7a', w: 0.85 })
      roof(b, I, (r) => gableRoof(r, { w: cw, d: cd, y: 0.15 + fh * 2, rise: 0.9, over: 0.3, mat: 'shingles', color: '#ffffff', gableColor: '#c8d4d0', gableMat: 'siding' }))
      b.cyl(0.07, 0.07, 1.2, { mat: 'metal', color: '#4a4a4a', x: -2, y: fh * 2 + 1.0, z: -0.5 })
      I.emitters.push({ kind: 'chimney', x: -2, y: fh * 2 + 1.65, z: z0 - 0.5, when: 'night' })
    })
    // balcony along the front of the upper floor
    const bz = z0 + cd / 2 + 0.6
    b.box(cw, 0.1, 1.2, { mat: 'wood', color: '#d8ccb8', y: 0.15 + fh, z: bz })
    for (const x of [-cw / 2 + 0.1, -0.9, 0.9, cw / 2 - 0.1]) b.box(0.1, fh + 0.1, 0.1, { mat: 'wood', color: '#c8bca8', x, y: (fh + 0.25) / 2, z: bz + 0.5 })
    for (let i = 0; i <= 22; i++) b.box(0.035, 0.9, 0.035, { mat: 'wood', color: '#e8e4dc', x: -cw / 2 + 0.12 + i * ((cw - 0.24) / 22), y: 0.15 + fh + 0.5, z: bz + 0.56 })
    b.box(cw, 0.06, 0.08, { mat: 'wood', color: '#e8e4dc', y: 0.15 + fh + 0.97, z: bz + 0.56 })
    // stairs up the right side
    const steps = 12
    for (let i = 0; i < steps; i++) b.box(0.9, 0.05, 0.3, { mat: 'wood', color: '#d0c4ae', x: cw / 2 - 0.5, y: 0.2 + (i * fh) / steps, z: bz + 1.75 - i * 0.12 - 0.3, ry: 0 })
    b.beam([cw / 2 - 0.05, 0.1, bz + 1.6], [cw / 2 - 0.05, fh + 0.15, bz + 0.2], 0.06, 0.2, { mat: 'wood', color: '#b8ac98' })
    b.beam([cw / 2 - 0.95, 0.1, bz + 1.6], [cw / 2 - 0.95, fh + 0.15, bz + 0.2], 0.06, 0.2, { mat: 'wood', color: '#b8ac98' })
    b.beam([cw / 2 - 0.02, 1.0, bz + 1.6], [cw / 2 - 0.02, fh + 1.05, bz + 0.2], 0.05, 0.05, { mat: 'wood', color: '#e8e4dc' })
    // flower boxes, laundry, lights
    for (const x of [-2.05, 0.7]) {
      b.box(0.7, 0.18, 0.2, { mat: 'wood', color: '#8a5a3a', x, y: 0.15 + fh + 0.12, z: bz + 0.45 })
      for (let k = 0; k < 4; k++) b.ico(0.1, { mat: 'leaf', color: k % 2 ? '#c83a5a' : '#5a8a3e', x: x - 0.25 + k * 0.17, y: 0.15 + fh + 0.28, z: bz + 0.45, detail: 0 })
    }
    stringLights(b, [[-cw / 2 + 0.1, fh + 0.0, bz + 0.5], [cw / 2 - 0.1, fh + 0.0, bz + 0.5]], { sag: 0.12, spacing: 0.45 })
    I.lights.push({ x: 0, y: fh - 0.2, z: bz + 0.5, color: '#ffc070', intensity: 3, dist: 9, when: 'night' })
    bench(b, { x: -1.2, z: bz + 0.1, len: 1.4 })
    I.seats.push({ x: -1.5, z: bz + 0.15, face: 0, y: 0 }, { x: -0.9, z: bz + 0.15, face: 0, y: 0 })
    I.beds.push({ x: -1.6, y: 0.4, z: z0, face: 0 }, { x: 0, y: 0.4, z: z0, face: 0 }, { x: 1.6, y: 0.4, z: z0, face: 0 })
  },

  storage(b, L, I) {
    const { w, d } = I
    if (L === 1) {
      // stacks under a big tarp
      roof(b, I, (r) => tarp(b, { rb: r, w: 4.4, d: 3.2, corners: [2.1, 2.1, 1.6, 1.6], color: COL.tarpBlue, z: -0.2, seed: 7 }))
      pallet(b, { x: -1.3, z: -0.8 })
      for (let i = 0; i < 3; i++) crate(b, { x: -1.6 + (i % 2) * 0.62, y: 0.13 + Math.floor(i / 2) * 0.5, z: -0.8 + (i % 2) * 0.05, ry: i * 0.1 })
      pallet(b, { x: 0.1, z: -0.8 })
      for (let i = 0; i < 6; i++) sack(b, { x: -0.1 + (i % 2) * 0.45, y: 0.13 + Math.floor(i / 2) * 0.24, z: -0.8, ry: Math.PI / 2 + (i % 3) * 0.05, color: i % 3 ? '#d8c8a0' : '#c8b48c' })
      barrel(b, { x: 1.4, z: -1.0, color: '#3a5878' })
      barrel(b, { x: 1.55, z: -0.35, color: '#7a3a2a' })
      jerrycan(b, { x: 0.9, z: 0.4, ry: 0.4 })
      crate(b, { x: 1.4, z: 0.6, ry: -0.2, w: 0.55, h: 0.45, d: 0.55 })
      sign(b, 'STORES', { x: 0, y: 1.25, z: 0.95, w: 0.9, h: 0.24, bg: '#2a2e26' })
      I.fill = [[-1.6, -0.2, 0.6], [0.6, 0.2, 0.0]]
      return
    }
    if (L === 2) {
      // open-front shed full of shelving
      const sw = 4.6
      const sd = 3.2
      b.box(sw, 0.12, sd, { mat: 'planks', color: '#e0d4c0', y: 0.06, z: -0.3 })
      frame(b, sw - 0.1, sd - 0.1, 2.4, { hFront: 2.6, z: -0.3, braces: false, zs: [-sd / 2 + 0.05 - 0.3, sd / 2 - 0.05 - 0.3] })
      boardWall(b, -sw / 2, sw / 2, -sd / 2 - 0.3, 2.4, { y0: 0.12, seed: 11, color: '#d8ccb6' })
      b.at({ ry: Math.PI / 2 }, () => {
        boardWall(b, -sd / 2 + 0.3, sd / 2 + 0.3, sw / 2, 2.4, { y0: 0.12, seed: 12, color: '#d8ccb6' })
        boardWall(b, -sd / 2 + 0.3, sd / 2 + 0.3, -sw / 2, 2.4, { y0: 0.12, seed: 13, color: '#d8ccb6' })
      })
      roof(b, I, (r) => corrRoof(r, { w: sw + 0.4, d: sd + 0.5, y: 2.75, drop: 0.35, z: -0.3 }))
      for (let i = 0; i < 3; i++) shelf(b, { x: -1.5 + i * 1.5, z: -1.55, w: 1.35, h: 2.0, d: 0.5, seed: 30 + i, kind: i === 1 ? 'jars' : 'mixed', fill: 0.9 })
      shelf(b, { x: -2.0, z: -0.4, ry: Math.PI / 2, w: 1.4, h: 1.8, d: 0.45, seed: 40, kind: 'boxes' })
      // hand truck with sacks
      b.at({ x: 1.6, z: 0.9, ry: -0.3 }, () => {
        for (const sx of [-1, 1]) b.box(0.03, 1.2, 0.03, { mat: 'paint', color: '#c83a2a', x: sx * 0.2, y: 0.6, z: -0.1, rx: 0.15 })
        b.box(0.45, 0.02, 0.25, { mat: 'steel', color: '#8a8e92', y: 0.02 })
        for (const sx of [-1, 1]) b.torus(0.1, 0.035, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.26, y: 0.1, z: -0.2, ry: Math.PI / 2 })
        sack(b, { y: 0.03, ry: 0, w: 0.38, d: 0.55, h: 0.22, rz: 0 })
        sack(b, { y: 0.25, ry: 0, w: 0.38, d: 0.55, h: 0.22, color: '#c8b48c' })
      })
      I.fill = [[-1.4, 0.6, 0.6], [0.2, 0.6, 0.6]]
      lantern(b, { x: 0, y: 2.2, z: 0.6 })
      I.lights.push({ x: 0, y: 2.3, z: 0.6, color: '#ffb060', intensity: 2.2, dist: 7, when: 'night' })
      return
    }
    // L3: a converted shipping container with a lean-to
    slab(b, I, { h: 0.12 })
    b.at({ z: -0.55, x: -0.2 }, () => {
      const cl = 4.4
      const ch = 2.4
      const cdd = 2.3
      b.box(cl, ch, 0.04, { mat: 'corrugated', color: '#2f6a8a', y: ch / 2 + 0.12, z: -cdd / 2 })
      b.box(0.04, ch, cdd, { mat: 'corrugated', color: '#2f6a8a', x: -cl / 2, y: ch / 2 + 0.12 })
      b.box(cl, 0.08, cdd, { mat: 'paint', color: '#2a5a78', y: ch + 0.12 })
      b.box(cl, 0.1, cdd, { mat: 'paint', color: '#2a2a2a', y: 0.17 })
      for (const z of [-cdd / 2, cdd / 2]) for (const y of [0.17, ch + 0.1]) b.box(cl, 0.12, 0.08, { mat: 'paint', color: '#25506a', y, z })
      for (const x of [-cl / 2, cl / 2]) for (const z of [-cdd / 2, cdd / 2]) b.box(0.15, ch, 0.15, { mat: 'paint', color: '#25506a', x, y: ch / 2 + 0.12, z })
      // open doors swung back against the side walls
      for (const s of [-1, 1]) {
        b.at({ x: cl / 2 + 0.02, z: (s * cdd) / 2, ry: s * 1.4 }, () => {
          b.box(0.04, ch - 0.1, cdd / 2, { mat: 'corrugated', color: '#2f6a8a', y: ch / 2 + 0.12, z: (-s * cdd) / 4 })
          for (const k of [-0.15, 0.15]) b.cyl(0.015, 0.015, ch - 0.2, { mat: 'steel', color: '#8a8e92', x: 0.04, y: ch / 2 + 0.12, z: (-s * cdd) / 4 + k })
        })
      }
      // shelving inside, visible from the open end and the camera side
      for (let i = 0; i < 3; i++) shelf(b, { x: -1.4 + i * 1.25, z: -cdd / 2 + 0.3, w: 1.15, h: 2.1, d: 0.45, seed: 60 + i, kind: i === 2 ? 'chem' : 'mixed', metal: true, fill: 0.95 })
      b.box(cl - 0.3, 0.06, 0.02, { mat: 'plain', color: '#e8e0c8', y: 1.9, z: cdd / 2 + 0.03, ao: 0 })
    })
    // lean-to with pallets on the open side
    frame(b, 4.6, 1.4, 2.3, { z: 1.25, hFront: 2.1, braces: false, metal: true, color: '#5a6066' })
    roof(b, I, (r) => corrRoof(r, { w: 4.8, d: 1.6, y: 2.4, drop: 0.25, z: 1.25, color: '#a8b0b4' }))
    for (let i = 0; i < 2; i++) {
      pallet(b, { x: -1.4 + i * 1.5, z: 1.25 })
      for (let k = 0; k < 4; k++) crate(b, { x: -1.7 + i * 1.5 + (k % 2) * 0.6, y: 0.13 + Math.floor(k / 2) * 0.5, z: 1.25, w: 0.56, h: 0.48, d: 0.56, color: k % 2 ? '#e8d8be' : '#d0c0a4', stencil: k === 0 ? '#2a2a2a' : null })
    }
    // pallet jack
    b.at({ x: 1.7, z: 1.3, ry: -0.5 }, () => {
      for (const sx of [-1, 1]) b.box(0.16, 0.06, 1.1, { mat: 'paint', color: '#d8a020', x: sx * 0.25, y: 0.05 })
      b.box(0.55, 0.3, 0.18, { mat: 'paint', color: '#d8a020', y: 0.2, z: -0.6 })
      b.beam([0, 0.3, -0.62], [0, 1.15, -0.9], 0.04, 0.04, { mat: 'steel', color: '#2a2a2a', round: true })
      b.box(0.3, 0.04, 0.04, { mat: 'rubber', color: '#1a1a1a', y: 1.16, z: -0.92 })
    })
    b.box(0.5, 0.35, 0.05, { mat: 'paint', color: '#f0f0e8', x: 1.9, y: 1.7, z: -0.55 + 1.17, ao: 0 })
    I.lights.push({ x: 0, y: 2.1, z: 1.0, color: '#fff0d0', intensity: 3, dist: 8, when: 'night' })
    bulb(b, { x: 0, y: 2.15, z: 1.0, r: 0.05 })
    I.fill = [[-1.9, 1.25, 0.5], [0.4, 1.25, 0.5]]
  },

  kitchen(b, L, I) {
    if (L === 1) {
      // a stone hearth with a grill and a prep table under a tarp
      roof(b, I, (r) => tarp(b, { rb: r, w: 4.2, d: 3.0, corners: [2.2, 2.2, 1.9, 1.9], color: COL.tarpGreen, seed: 13 }))
      b.at({ x: -1.1, z: -0.5 }, () => {
        for (let i = 0; i < 12; i++) {
          const a = (i / 12) * TAU
          rock(b, { x: Math.cos(a) * 0.55, z: Math.sin(a) * 0.4, s: 0.16, seed: 40 + i })
        }
        b.cyl(0.5, 0.5, 0.05, { mat: 'plain', color: '#2a2420', y: 0.04, sz: 0.75 })
        for (let i = 0; i < 9; i++) b.box(0.03, 0.02, 0.8, { mat: 'steel', color: '#3a3a3a', x: -0.4 + i * 0.1, y: 0.42 })
        b.box(0.9, 0.03, 0.03, { mat: 'steel', color: '#3a3a3a', y: 0.42, z: 0.4 })
        b.box(0.9, 0.03, 0.03, { mat: 'steel', color: '#3a3a3a', y: 0.42, z: -0.4 })
        b.lathe([[0.2, 0], [0.24, 0.04], [0.25, 0.3], [0.24, 0.32]], { mat: 'metal', color: '#4a4a48', y: 0.44, x: -0.12, seg: 16 })
        b.cyl(0.14, 0.14, 0.04, { mat: 'metal', color: '#2a2a2a', y: 0.46, x: 0.25, z: 0.1 })
        b.box(0.04, 0.02, 0.25, { mat: 'wood', color: '#5a3a2a', y: 0.47, x: 0.25, z: 0.33 })
      })
      I.flames.push({ x: -1.1, y: 0.05, z: -0.5, w: 0.6, h: 0.5, when: 'active' })
      I.lights.push({ x: -1.1, y: 0.7, z: -0.5, color: '#ff9040', intensity: 3, dist: 7, flicker: true, when: 'active' })
      I.emitters.push({ kind: 'steam', x: -1.22, y: 0.8, z: -0.5, when: 'active' }, { kind: 'smoke', x: -1.1, y: 0.6, z: -0.5, when: 'active' })
      table(b, { x: 0.9, z: -0.4, w: 1.5, d: 0.75, h: 0.82 })
      b.box(0.45, 0.03, 0.3, { mat: 'wood', color: '#e8d0a8', x: 0.7, y: 0.84, z: -0.4 })
      for (let i = 0; i < 5; i++) b.sphere(0.045, { mat: 'gloss', color: ['#d8401a', '#e8a020', '#6a9a3a', '#c8b060', '#8a3a6a'][i], x: 1.1 + (i % 3) * 0.1, y: 0.87, z: -0.5 + Math.floor(i / 3) * 0.12, ws: 8, hs: 6 })
      sack(b, { x: 1.7, z: 0.6, ry: 0.4, color: '#c8a870' })
      barrel(b, { x: -1.9, z: 0.8, color: '#3a5878', open: true, contents: '#3a5a6a' })
      bucket(b, { x: 0.2, z: 0.5, water: true })
      for (let i = 0; i < 4; i++) hangingTools(b, 0.5 + i * 0.25, 2.1, -1.4)
      I.spots.push({ x: -1.1, z: 0.25, face: FACE_BACK, anim: 'stir' }, { x: 0.9, z: 0.25, face: FACE_BACK, anim: 'saw' })
      return
    }
    if (L === 2) {
      // open pavilion with a wood range and a long table
      b.box(4.8, 0.1, 3.8, { mat: 'planks', color: '#e8dcc6', y: 0.05 })
      frame(b, 4.6, 3.6, 2.6, { hFront: 2.8, braces: true })
      roof(b, I, (r) => corrRoof(r, { w: 5.0, d: 4.0, y: 2.95, drop: 0.4, color: '#a8a49c', gutter: true }))
      // range against the back
      b.at({ x: -1.2, z: -1.35 }, () => {
        b.box(1.4, 0.8, 0.7, { mat: 'metal', color: '#2a2a2c', y: 0.5 })
        b.box(1.44, 0.04, 0.74, { mat: 'steel', color: '#5a5a5a', y: 0.92 })
        for (const sx of [-0.35, 0.35]) b.cyl(0.18, 0.18, 0.02, { mat: 'steel', color: '#1a1a1a', x: sx, y: 0.94 })
        b.box(0.5, 0.35, 0.02, { mat: 'metal', color: '#1a1a1a', y: 0.45, z: 0.36 })
        b.box(0.3, 0.12, 0.02, { mat: 'embers', color: '#ffffff', y: 0.32, z: 0.37 })
        b.cyl(0.08, 0.08, 2.5, { mat: 'metal', color: '#3a3a3a', x: 0.5, y: 2.1, z: -0.15 })
        b.lathe([[0.22, 0], [0.26, 0.05], [0.26, 0.32], [0.25, 0.34]], { mat: 'metal', color: '#7a7a78', x: -0.35, y: 0.95, seg: 16 })
        b.cyl(0.15, 0.13, 0.08, { mat: 'metal', color: '#2a2a2a', x: 0.35, y: 0.99 })
      })
      I.emitters.push({ kind: 'chimney', x: -0.7, y: 3.4, z: -1.5, when: 'active' }, { kind: 'steam', x: -1.55, y: 1.35, z: -1.35, when: 'active' })
      I.lights.push({ x: -1.2, y: 0.6, z: -0.9, color: '#ff8a3a', intensity: 2, dist: 5, flicker: true, when: 'active' })
      shelf(b, { x: 1.1, z: -1.55, w: 1.6, h: 1.7, d: 0.4, seed: 70, kind: 'jars', fill: 0.85 })
      for (let i = 0; i < 6; i++) hangingTools(b, -1.85 + i * 0.22, 2.5, -1.7)
      // long eating table with benches
      table(b, { x: 0.3, z: 0.6, w: 2.6, d: 0.9, h: 0.76, color: '#e0ccae' })
      for (const sz of [-1, 1]) bench(b, { x: 0.3, z: 0.6 + sz * 0.75, len: 2.4 })
      for (let i = 0; i < 4; i++) {
        b.cyl(0.11, 0.09, 0.03, { mat: 'metal', color: '#c8c8c0', x: -0.6 + i * 0.6, y: 0.78, z: 0.4 + (i % 2) * 0.35 })
        b.cyl(0.035, 0.035, 0.1, { mat: 'metal', color: '#8a8e92', x: -0.4 + i * 0.6, y: 0.81, z: 0.55 })
      }
      for (const x of [-0.6, 0.3, 1.2]) I.seats.push({ x, z: 0.6 + 0.75, face: FACE_BACK, y: 0 }, { x, z: 0.6 - 0.75, face: 0, y: 0 })
      lantern(b, { x: 0.3, y: 2.2, z: 0.6 })
      I.lights.push({ x: 0.3, y: 2.3, z: 0.6, color: '#ffb060', intensity: 2.6, dist: 8, when: 'night' })
      sack(b, { x: 2.1, z: -0.9, ry: 0.2 })
      sack(b, { x: 2.1, z: -0.5, ry: -0.1, y: 0.25, color: '#c8a870' })
      I.spots.push({ x: -1.45, z: -0.6, face: FACE_BACK, anim: 'stir' }, { x: 1.1, z: -0.95, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: mess hall front with a serving hatch and a pergola
    slab(b, I, { h: 0.12 })
    b.at({ z: -1.3 }, () => {
      b.box(4.8, 2.8, 1.2, { mat: 'brick', color: '#ffffff', y: 1.52 })
      b.box(2.0, 0.9, 0.1, { mat: 'plain', color: '#2a2420', y: 1.5, z: 0.58 })
      b.box(2.2, 0.08, 0.5, { mat: 'steel', color: '#c8ccd0', y: 1.04, z: 0.75 })
      b.box(2.4, 0.06, 0.8, { mat: 'paint', color: '#c83a2a', y: 2.15, z: 0.85, rx: -0.25 })
      for (let i = 0; i < 6; i++) b.box(0.4, 0.06, 0.8, { mat: 'paint', color: i % 2 ? '#e8e4dc' : '#c83a2a', x: -1.0 + i * 0.4, y: 2.16, z: 0.86, rx: -0.25 })
      // chimney stack
      b.box(0.6, 3.5, 0.6, { mat: 'brick', color: '#d8c8c0', x: 1.8, y: 2.0, z: -0.2 })
      b.box(0.7, 0.12, 0.7, { mat: 'concrete', color: '#c8c4bc', x: 1.8, y: 3.8, z: -0.2 })
      sign(b, 'MESS HALL', { y: 2.55, z: 0.62, w: 1.6, h: 0.3, bg: '#3a2a1e', fg: '#f0d8a0' })
      b.box(1.2, 0.5, 0.08, { mat: 'glass', color: '#e8f0f0', x: -1.6, y: 1.6, z: 0.6 })
      windowPane(b, { x: -1.6, y: 1.6, z: 0.62, w: 0.9, h: 0.6 })
      // pots and food glimpsed inside the hatch
      for (let i = 0; i < 3; i++) b.cyl(0.16, 0.14, 0.2, { mat: 'steel', color: '#b8bcc0', x: -0.6 + i * 0.6, y: 1.18, z: 0.65 })
    })
    I.emitters.push({ kind: 'chimney', x: 1.8, y: 3.95, z: -1.5, when: 'active' }, { kind: 'steam', x: 0, y: 1.6, z: -0.6, when: 'active' })
    I.lights.push({ x: 0, y: 1.6, z: -0.5, color: '#ffc070', intensity: 3, dist: 7, when: 'night' })
    // pergola with string lights over two tables
    frame(b, 4.6, 2.2, 2.5, { z: 0.85, braces: false, color: '#c8b8a0' })
    for (let i = 0; i < 8; i++) b.box(0.06, 0.12, 2.4, { mat: 'wood', color: '#c8b8a0', x: -2.2 + i * 0.63, y: 2.56, z: 0.85 })
    stringLights(b, [[-2.3, 2.45, -0.25], [2.3, 2.45, 1.95]], { sag: 0.2 })
    stringLights(b, [[2.3, 2.45, -0.25], [-2.3, 2.45, 1.95]], { sag: 0.2 })
    I.lights.push({ x: 0, y: 2.0, z: 0.85, color: '#ffc070', intensity: 3.5, dist: 10, when: 'night' })
    for (const x of [-1.2, 1.2]) {
      table(b, { x, z: 0.95, w: 1.6, d: 0.8, h: 0.76, color: '#d8c8b0' })
      for (const sz of [-1, 1]) bench(b, { x, z: 0.95 + sz * 0.68, len: 1.5 })
      for (const k of [-0.4, 0.4]) I.seats.push({ x: x + k, z: 0.95 + 0.68, face: FACE_BACK, y: 0 }, { x: x + k, z: 0.95 - 0.68, face: 0, y: 0 })
    }
    // smoker drum
    b.at({ x: 2.1, z: -0.05, ry: Math.PI / 2 }, () => {
      b.cyl(0.3, 0.3, 0.9, { mat: 'metal', color: '#2a2a2a', y: 0.75, rz: Math.PI / 2, seg: 16 })
      for (const sx of [-1, 1]) b.beam([sx * 0.35, 0, -0.25], [sx * 0.3, 0.6, 0], 0.04, 0.04, { mat: 'steel', color: '#2a2a2a', round: true })
      b.cyl(0.05, 0.05, 0.5, { mat: 'metal', color: '#2a2a2a', x: 0.45, y: 1.2 })
    })
    I.emitters.push({ kind: 'smoke', x: 2.1, y: 1.5, z: 0.4, when: 'active' })
    I.spots.push({ x: -0.6, z: -0.35, face: FACE_BACK, anim: 'stir' }, { x: 0.6, z: -0.35, face: FACE_BACK, anim: 'stir' })
  },

  infirmary(b, L, I) {
    if (L === 1) {
      // white medical tent with a red cross, one cot outside under the fly
      b.at({ x: -0.6, z: -0.4 }, () => {
        const tw = 3.6
        const td = 2.8
        const wallH = 1.3
        const peak = 2.4
        for (const sz of [-1, 1]) b.box(tw, wallH, 0.03, { mat: 'canvas', color: '#e8e6e0', y: wallH / 2, z: (sz * td) / 2 })
        b.box(0.03, wallH, td, { mat: 'canvas', color: '#e8e6e0', x: -tw / 2, y: wallH / 2 })
        const slope = Math.atan2(peak - wallH, td / 2)
        const len = Math.hypot(td / 2, peak - wallH)
        roof(b, I, (r) => {
          for (const s of [-1, 1]) {
            r.box(tw + 0.1, 0.02, len + 0.15, { mat: 'canvas', color: '#f0eee8', y: (peak + wallH) / 2, z: (s * td) / 4, rx: s * slope })
            r.box(1.0, 0.02, 1.0, { mat: 'plain', color: '#c82a24', y: (peak + wallH) / 2 + 0.03, z: (s * td) / 4, rx: s * slope, sx: 0.3, ao: 0 })
            r.box(1.0, 0.02, 1.0, { mat: 'plain', color: '#c82a24', y: (peak + wallH) / 2 + 0.031, z: (s * td) / 4, rx: s * slope, sz: 0.3, ao: 0 })
          }
        })
        b.wedge(td, peak - wallH, 0.03, { mat: 'canvas', color: '#e8e6e0', x: -tw / 2, y: wallH, ry: Math.PI / 2 })
        // front end rolled open
        b.cyl(0.08, 0.08, td * 0.9, { mat: 'canvas', color: '#d8d4cc', x: tw / 2, y: wallH + 0.1, rx: Math.PI / 2 })
        for (const x of [-tw / 2, 0, tw / 2]) b.cyl(0.035, 0.035, peak, { mat: 'steel', color: '#8a9094', x, y: peak / 2 })
        cot(b, { x: -0.5, z: -0.2, ry: Math.PI / 2, blanket: '#5a7aa0' })
        b.box(tw, 0.02, td, { mat: 'canvas', color: '#7a7060', y: 0.01 })
      })
      cot(b, { x: 1.95, z: -0.2, ry: 0, blanket: '#7a5a9a' })
      I.beds.push({ x: -1.1, y: 0.5, z: -0.6, face: Math.PI / 2 }, { x: 1.95, y: 0.5, z: -0.2, face: 0 })
      // IV stand and supplies
      b.cyl(0.012, 0.012, 1.7, { mat: 'chrome', color: '#d0d4d8', x: 2.45, y: 0.85, z: -0.8 })
      b.box(0.3, 0.02, 0.02, { mat: 'chrome', color: '#d0d4d8', x: 2.45, y: 1.7, z: -0.8 })
      b.box(0.1, 0.16, 0.04, { mat: 'glass', color: '#e8f0e8', x: 2.37, y: 1.58, z: -0.8 })
      b.cyl(0.16, 0.2, 0.03, { mat: 'chrome', color: '#d0d4d8', x: 2.45, y: 0.02, z: -0.8, seg: 5 })
      crate(b, { x: 1.4, z: 1.1, w: 0.6, h: 0.45, d: 0.45, color: '#e8e8e0', stencil: '#c82a24' })
      table(b, { x: 0.3, z: 1.1, w: 1.1, d: 0.6, h: 0.75, color: '#e0e0d8' })
      b.box(0.3, 0.1, 0.2, { mat: 'plastic', color: '#e8e8e0', x: 0.1, y: 0.8, z: 1.1 })
      b.box(0.06, 0.06, 0.06, { mat: 'plain', color: '#c82a24', x: 0.1, y: 0.86, z: 1.21 })
      for (let i = 0; i < 4; i++) b.cyl(0.025, 0.025, 0.09, { mat: 'glass', color: '#d8a040', x: 0.45 + i * 0.07, y: 0.8, z: 1.05 })
      I.lights.push({ x: 0, y: 1.8, z: -0.4, color: '#fff0d8', intensity: 2, dist: 6, when: 'night' })
      I.spots.push({ x: 1.95, z: 0.85, face: FACE_BACK, anim: 'search', medic: true }, { x: 0.3, z: 1.6, face: FACE_BACK, anim: 'type' })
      return
    }
    if (L === 2) {
      // platform tent-cabin with three cots and curtains
      deck(b, 5.6, 3.6, 0.25, { color: '#e0d6c4' })
      frame(b, 5.4, 3.4, 2.3, { hFront: 2.5, braces: false, color: '#c8c0b0' })
      roof(b, I, (r) => r.cloth(5.8, 3.9, { mat: 'canvas', color: '#e8e4dc', corners: [2.75, 2.75, 2.6, 2.6], sag: 0.15, y: 0, seed: 21 }))
      b.box(5.4, 1.6, 0.03, { mat: 'canvas', color: '#e0dcd4', y: 1.05, z: -1.7 })
      for (const sx of [-1, 1]) b.box(0.03, 1.6, 3.4, { mat: 'canvas', color: '#e0dcd4', x: sx * 2.7, y: 1.05 })
      b.box(1.4, 1.4, 0.02, { mat: 'plain', color: '#c82a24', y: 1.3, z: -1.68, sx: 0.28, ao: 0 })
      b.box(1.4, 1.4, 0.02, { mat: 'plain', color: '#c82a24', y: 1.3, z: -1.68, sy: 0.28, ao: 0 })
      for (let i = 0; i < 3; i++) {
        const x = -1.8 + i * 1.8
        cot(b, { x, y: 0.25, z: -0.6, blanket: ['#5a7aa0', '#7a8a5a', '#9a6a5a'][i] })
        I.beds.push({ x, y: 0.75, z: -0.6, face: 0 })
        if (i < 2) {
          b.cyl(0.012, 0.012, 1.8, { mat: 'chrome', color: '#d0d4d8', x: x + 0.9, y: 1.15, z: -0.6 })
          b.cloth(0.04, 1.9, { mat: 'cloth', color: '#a8c8c0', corners: [1.95, 1.95, 1.95, 1.95], sag: 0, x: x + 0.9, y: 0 })
          b.box(0.02, 1.4, 1.8, { mat: 'cloth', color: '#a8c8c0', x: x + 0.9, y: 1.15, z: -0.6 })
        }
      }
      shelf(b, { x: 2.0, z: 1.2, ry: -Math.PI / 2, w: 1.0, h: 1.6, d: 0.4, seed: 80, kind: 'bottles', metal: true, color: '#e8e8e0' })
      b.box(0.6, 0.85, 0.45, { mat: 'paint', color: '#e8e8e0', x: -2.2, y: 0.67, z: 1.15 })
      b.cyl(0.18, 0.15, 0.1, { mat: 'steel', color: '#d0d4d8', x: -2.2, y: 1.12, z: 1.15 })
      lantern(b, { x: 0, y: 2.1, z: 0.6 })
      I.lights.push({ x: 0, y: 2.2, z: 0.6, color: '#fff0d8', intensity: 3, dist: 8, when: 'night' })
      I.spots.push({ x: -1.8, z: 0.4, face: FACE_BACK, anim: 'search', medic: true }, { x: 0, z: 0.4, face: FACE_BACK, anim: 'search', medic: true })
      return
    }
    // L3: prefab clinic with an awning ward in front
    slab(b, I, { h: 0.12 })
    b.at({ z: -1.15 }, () => {
      b.box(5.6, 2.8, 1.5, { mat: 'siding', color: '#eceae4', y: 1.52 })
      b.box(5.7, 0.15, 1.6, { mat: 'paint', color: '#c8ccd0', y: 2.98 })
      for (const x of [-2.0, 2.0]) windowPane(b, { x, y: 1.7, z: 0.76, w: 0.9, h: 0.8, frame: '#f8f8f4' })
      door(b, { x: -0.8, z: 0.76, y: 0.12, color: '#d8dcd8', w: 0.95 })
      b.box(1.5, 1.5, 0.03, { mat: 'paint', color: '#f8f8f4', x: 0.9, y: 1.8, z: 0.77 })
      b.box(1.2, 0.34, 0.02, { mat: 'plain', color: '#c82a24', x: 0.9, y: 1.8, z: 0.79, ao: 0 })
      b.box(0.34, 1.2, 0.02, { mat: 'plain', color: '#c82a24', x: 0.9, y: 1.8, z: 0.79, ao: 0 })
      b.box(0.8, 0.6, 0.5, { mat: 'metal', color: '#c8ccd0', x: 2.2, y: 3.35, z: -0.2 })
    })
    frame(b, 5.6, 2.2, 2.6, { z: 0.85, braces: false, metal: true, color: '#c8ccd0', zs: [-0.25, 1.95] })
    roof(b, I, (r) => r.cloth(5.9, 2.5, { mat: 'canvas', color: '#5a8ab0', corners: [2.75, 2.75, 2.45, 2.45], sag: 0.08, z: 0.85, seed: 33 }))
    for (let i = 0; i < 4; i++) {
      const x = -2.1 + i * 1.4
      b.at({ x, z: 0.75 }, () => {
        b.box(0.8, 0.12, 1.9, { mat: 'paint', color: '#e8e8e4', y: 0.55 })
        b.box(0.75, 0.1, 1.85, { mat: 'cloth', color: '#f0f0ec', y: 0.66 })
        b.box(0.7, 0.1, 1.0, { mat: 'cloth', color: ['#7a9ac0', '#a0b8c8', '#7aa098', '#b89aa0'][i], y: 0.73, z: 0.35 })
        for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.02, 0.02, 0.5, { mat: 'chrome', color: '#d0d4d8', x: sx * 0.36, y: 0.25, z: sz * 0.85 })
        b.box(0.8, 0.4, 0.04, { mat: 'chrome', color: '#d0d4d8', y: 0.85, z: -0.95 })
      })
      I.beds.push({ x, y: 0.72, z: 0.75, face: 0 })
    }
    gasBottle(b, { x: 2.65, z: 1.85, color: '#3a7a3a' })
    // wheelchair
    b.at({ x: -2.6, z: 1.7, ry: 0.8 }, () => {
      for (const sx of [-1, 1]) b.torus(0.3, 0.02, { mat: 'chrome', color: '#c0c4c8', x: sx * 0.3, y: 0.3, ry: Math.PI / 2 })
      b.box(0.5, 0.05, 0.45, { mat: 'cloth', color: '#2a2a2a', y: 0.5 })
      b.box(0.5, 0.45, 0.05, { mat: 'cloth', color: '#2a2a2a', y: 0.75, z: -0.22 })
    })
    I.lights.push({ x: 0, y: 2.3, z: 0.8, color: '#f0f8ff', intensity: 4, dist: 9, when: 'night' })
    bulb(b, { x: 0, y: 2.35, z: 0.8, r: 0.06, glow: 'nightGlow' })
    I.spots.push({ x: -1.4, z: 1.95, face: FACE_BACK, anim: 'search', medic: true }, { x: 0.7, z: 1.95, face: FACE_BACK, anim: 'search', medic: true })
  },

  training(b, L, I) {
    const dummy = (x, z, ry = 0, armed = false) => {
      b.at({ x, z, ry }, () => {
        b.box(0.1, 1.5, 0.1, { mat: 'wood', color: COL.woodGrey, y: 0.75 })
        b.box(0.5, 0.06, 0.06, { mat: 'wood', color: COL.woodGrey, y: 0.05 })
        b.box(0.06, 0.06, 0.5, { mat: 'wood', color: COL.woodGrey, y: 0.05 })
        b.capsule(0.2, 0.45, { mat: 'cloth', color: '#d8b860', y: 1.2, seg: 10 })
        b.sphere(0.14, { mat: 'cloth', color: '#d8c8a0', y: 1.7 })
        for (const y of [1.0, 1.35]) b.torus(0.2, 0.012, { mat: 'cloth', color: COL.rope, y, rx: Math.PI / 2 })
        if (armed) for (const sx of [-1, 1]) b.cyl(0.04, 0.04, 0.5, { mat: 'wood', color: '#c8a070', x: sx * 0.15, y: 1.3, z: 0.2, rx: Math.PI / 2 })
      })
    }
    const target = (x, z, ry = 0, s = 1) => {
      b.at({ x, z, ry, s }, () => {
        for (const sx of [-1, 1]) b.beam([sx * 0.35, 0, -0.25], [sx * 0.3, 1.4, 0], 0.06, 0.06, { mat: 'wood', color: COL.woodGrey })
        b.cyl(0.5, 0.5, 0.06, { mat: 'cloth', color: '#d8c8a0', y: 1.15, z: 0.03, rx: Math.PI / 2, seg: 20 })
        const rings = ['#e8e4dc', '#2a2a2a', '#3a6ab0', '#c82a24', '#e8c030']
        rings.forEach((c, i) => b.cyl(0.46 - i * 0.09, 0.46 - i * 0.09, 0.005, { mat: 'plain', color: c, y: 1.15, z: 0.064 + i * 0.002, rx: Math.PI / 2, seg: 20, ao: 0 }))
      })
    }
    if (L === 1) {
      dummy(-1.8, -0.8)
      dummy(-0.6, -0.9, 0.3)
      target(1.6, -1.6, 0)
      for (let i = 0; i < 3; i++) hayBale(b, { x: 1.6 + (i - 1) * 0.95, z: -2.05, ry: 0 })
      hayBale(b, { x: 1.6, y: 0.45, z: -2.05 })
      // weapon rack
      b.at({ x: 2.4, z: 0.6, ry: -Math.PI / 2 }, () => {
        for (const sx of [-1, 1]) b.box(0.08, 1.2, 0.08, { mat: 'wood', color: COL.woodGrey, x: sx * 0.6, y: 0.6 })
        b.box(1.3, 0.08, 0.1, { mat: 'wood', color: COL.woodGrey, y: 1.05 })
        b.box(1.3, 0.08, 0.2, { mat: 'wood', color: COL.woodGrey, y: 0.15 })
        for (let i = 0; i < 4; i++) b.cyl(0.03, 0.03, 1.1, { mat: 'wood', color: ['#c89a60', '#8a6a4a', '#a87a50', '#6a5a4a'][i], x: -0.45 + i * 0.3, y: 0.6, rz: 0.1 })
      })
      b.box(5.4, 0.015, 0.06, { mat: 'plain', color: '#e8e0c8', y: 0.008, z: 0.4, ao: 0 })
      I.spots.push({ x: -1.8, z: -0.2, face: FACE_BACK, anim: 'swing', train: 'melee' }, { x: -0.5, z: -0.25, face: FACE_BACK + 0.3, anim: 'punch', train: 'melee' }, { x: 1.4, z: 1.4, face: FACE_BACK, anim: 'aim', train: 'ranged' }, { x: 0.6, z: 1.4, face: FACE_BACK - 0.2, anim: 'aim', train: 'ranged' })
      return
    }
    if (L === 2) {
      dummy(-2.0, -1.1, 0, true)
      dummy(-0.8, -1.2, 0.2, true)
      // heavy bag on a frame
      b.at({ x: -1.4, z: 0.9 }, () => {
        for (const sx of [-1, 1]) b.beam([sx * 0.8, 0, 0], [sx * 0.6, 2.3, 0], 0.1, 0.1, { mat: 'wood', color: COL.woodGrey })
        b.box(1.4, 0.12, 0.12, { mat: 'wood', color: COL.woodGrey, y: 2.3 })
        b.rope([[0, 2.24, 0], [0, 1.7, 0]], 0.01, { mat: 'steel', color: '#5a5a5a', sag: 0, steps: 2 })
        b.capsule(0.2, 0.7, { mat: 'cloth', color: '#8a3a2a', y: 1.25, seg: 12 })
      })
      // shooting lane with metal plates
      b.box(2.4, 0.9, 0.12, { mat: 'wood', color: '#c8b498', x: 1.5, y: 0.45, z: 1.4 })
      sandbags(b, { x: 1.5, z: 1.6, len: 2.4, rows: 2, seed: 4 })
      for (let i = 0; i < 3; i++) {
        b.at({ x: 0.9 + i * 0.6, z: -2.0 }, () => {
          b.box(0.06, 0.8, 0.06, { mat: 'steel', color: '#4a4e52', y: 0.4 })
          b.cyl(0.18, 0.18, 0.02, { mat: 'paint', color: '#e8e4dc', y: 0.95, rx: Math.PI / 2, seg: 14 })
        })
      }
      target(2.4, -1.9, -0.2, 0.8)
      b.box(5.6, 0.015, 0.06, { mat: 'plain', color: '#e8e0c8', y: 0.008, z: 0.3, ao: 0 })
      I.spots.push({ x: -2.0, z: -0.5, face: FACE_BACK, anim: 'swing', train: 'melee' }, { x: -1.4, z: 1.6, face: FACE_BACK, anim: 'punch', train: 'melee' }, { x: 1.1, z: 1.0, face: FACE_BACK, anim: 'aim', train: 'ranged' }, { x: 1.9, z: 1.0, face: FACE_BACK, anim: 'aim', train: 'ranged' })
      return
    }
    // L3: sparring ring, pull-up rig, steel targets, climbing wall
    slab(b, I, { w: 2.6, d: 2.6, x: -1.5, z: -0.9, h: 0.25 })
    b.box(2.5, 0.06, 2.5, { mat: 'cloth', color: '#3a5a8a', x: -1.5, y: 0.26, z: -0.9 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.05, 0.05, 1.3, { mat: 'paint', color: '#c83a2a', x: -1.5 + sx * 1.2, y: 0.85, z: -0.9 + sz * 1.2 })
    for (const y of [0.6, 0.9, 1.2])
      for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]])
        b.rope([[-1.5 + ax * 1.2, y, -0.9 + az * 1.2], [-1.5 + bx * 1.2, y, -0.9 + bz * 1.2]], 0.015, { mat: 'cloth', color: '#e8e4dc', sag: 0.02, steps: 4 })
    b.at({ x: 1.6, z: -1.6 }, () => {
      for (const sx of [-1, 1]) b.box(0.1, 2.5, 0.1, { mat: 'paint', color: '#3a3e42', x: sx * 0.9, y: 1.25 })
      b.cyl(0.025, 0.025, 1.9, { mat: 'chrome', color: '#c0c4c8', y: 2.3, rz: Math.PI / 2 })
      b.cyl(0.025, 0.025, 1.9, { mat: 'chrome', color: '#c0c4c8', y: 1.6, rz: Math.PI / 2 })
    })
    for (let i = 0; i < 4; i++) {
      b.at({ x: 0.5 + i * 0.6, z: 1.7 }, () => {
        b.box(0.06, 0.9, 0.06, { mat: 'steel', color: '#4a4e52', y: 0.45 })
        b.box(0.35, 0.5, 0.02, { mat: 'paint', color: '#f0f0e8', y: 1.1, rz: (i % 2) * 0.08 })
      })
    }
    tireStack(b, { x: -2.5, z: 1.6, n: 2 })
    tireStack(b, { x: -1.8, z: 1.75, n: 1 })
    I.lights.push({ x: 0, y: 3, z: 0, color: '#fff0d0', intensity: 4, dist: 10, when: 'night' })
    lampPost(b, { x: 0.2, z: -0.4, h: 3.1, ry: Math.PI })
    I.spots.push({ x: -1.9, z: -0.9, y: 0.26, face: Math.PI / 2, anim: 'punch', train: 'melee' }, { x: -1.1, z: -0.9, y: 0.26, face: -Math.PI / 2, anim: 'punch', train: 'melee' }, { x: 1.1, z: 0.6, face: 0, anim: 'aim', train: 'ranged' }, { x: 1.9, z: 0.6, face: 0, anim: 'aim', train: 'ranged' })
  },

  radio(b, L, I) {
    if (L === 1) {
      // a guyed wooden pole with a CB antenna and a radio table
      b.box(0.16, 7, 0.16, { mat: 'wood', color: COL.woodGrey, y: 3.5, x: -0.6, z: -0.6 })
      b.cyl(0.015, 0.015, 2.2, { mat: 'chrome', color: '#c8c8c8', x: -0.6, y: 8.1, z: -0.6 })
      for (let i = 0; i < 4; i++) b.cyl(0.006, 0.006, 0.8, { mat: 'chrome', color: '#c8c8c8', x: -0.6, y: 7.1, z: -0.6, rz: Math.PI / 2 - 0.3, ry: (i * Math.PI) / 2 })
      for (const [gx, gz] of [[-1.45, -1.45], [1.35, -1.4], [-1.4, 1.4]]) {
        b.rope([[-0.6, 6.2, -0.6], [gx, 0.05, gz]], 0.006, { mat: 'steel', color: '#8a8a8a', sag: 0.01, steps: 2 })
        b.box(0.05, 0.25, 0.05, { mat: 'steel', color: '#5a5a5a', x: gx, y: 0.1, z: gz })
      }
      b.rope([[-0.6, 1.6, -0.5], [0.4, 0.82, 0.2]], 0.01, { mat: 'rubber', color: '#1a1a1a', sag: 0.1 })
      table(b, { x: 0.4, z: 0.35, w: 1.1, d: 0.6, h: 0.74, color: '#d8ccb6' })
      radioSet(b, { x: 0.4, y: 0.76, z: 0.25 })
      carBattery(b, { x: 0.95, y: 0, z: 0.1 })
      stool(b, { x: 0.4, z: 0.95, h: 0.46 })
      roof(b, I, (r) => r.cloth(1.8, 1.6, { mat: 'canvas', color: COL.canvasOlive, corners: [2.0, 2.0, 1.8, 1.8], sag: 0.08, x: 0.4, z: 0.4 }))
      for (const [x, z] of [[-0.45, -0.4], [1.25, -0.4], [1.25, 1.2], [-0.45, 1.2]]) b.cyl(0.03, 0.03, 2.0, { mat: 'wood', color: COL.woodGrey, x, y: 1.0, z })
      I.blink = [{ x: -0.6, y: 9.2, z: -0.6 }]
      I.spots.push({ x: 0.4, z: 0.92, face: FACE_BACK, anim: 'type', sit: 0.46 })
      return
    }
    const H = L === 2 ? 10 : 14
    // steel lattice tower
    const lattice = (h, base) => {
      for (let i = 0; i < 4; i++) {
        const a = (i / 4) * TAU + Math.PI / 4
        b.beam([Math.cos(a) * base, 0, Math.sin(a) * base], [Math.cos(a) * 0.18, h, Math.sin(a) * 0.18], 0.06, 0.06, { mat: 'paint', color: i % 2 ? '#c83a2a' : '#e8e4dc' })
      }
      const segs = Math.floor(h / 1.2)
      for (let s = 0; s < segs; s++) {
        const y0 = s * 1.2
        const y1 = y0 + 1.2
        const r0 = base + (0.18 - base) * (y0 / h)
        const r1 = base + (0.18 - base) * (y1 / h)
        for (let i = 0; i < 4; i++) {
          const a0 = (i / 4) * TAU + Math.PI / 4
          const a1 = ((i + 1) / 4) * TAU + Math.PI / 4
          b.beam([Math.cos(a0) * r0, y0, Math.sin(a0) * r0], [Math.cos(a1) * r1, y1, Math.sin(a1) * r1], 0.025, 0.025, { mat: 'steel', color: '#9aa0a6' })
          b.beam([Math.cos(a0) * r1, y1, Math.sin(a0) * r1], [Math.cos(a1) * r1, y1, Math.sin(a1) * r1], 0.025, 0.025, { mat: 'steel', color: '#9aa0a6' })
        }
      }
    }
    b.at({ x: -0.45, z: -0.45 }, () => {
      b.box(1.5, 0.3, 1.5, { mat: 'concrete', color: '#c8c4bc', y: 0.1 })
      lattice(H, 0.65)
      b.cyl(0.03, 0.03, 3, { mat: 'chrome', color: '#d0d0d0', y: H + 1.5 })
      for (let i = 0; i < 3; i++) b.box(0.04, 1.6, 0.06, { mat: 'paint', color: '#e8e4dc', x: Math.cos(i * 2.1) * 0.35, y: H - 1.2, z: Math.sin(i * 2.1) * 0.35 })
      if (L === 3) {
        b.pivot('dish', { y: H - 3.2 }, (p) => {
          p.box(0.08, 0.6, 0.08, { mat: 'steel', color: '#9aa0a6', x: 0.25, y: 0 })
          p.lathe([[0.001, 0], [0.3, 0.05], [0.55, 0.18], [0.62, 0.26]], { mat: 'paint', color: '#e8e8e4', x: 0.55, rz: -Math.PI / 2, seg: 18 })
          p.cyl(0.02, 0.02, 0.5, { mat: 'steel', color: '#9aa0a6', x: 0.8, rz: Math.PI / 2 })
        })
        I.anims.push({ name: 'dish', kind: 'yaw', speed: 0.25, swing: 1.2, when: 'active' })
      }
    })
    I.blink = [{ x: -0.45, y: H + 3.05, z: -0.45 }]
    b.sphere(0.07, { mat: 'glowRed', color: '#ffffff', x: -0.45, y: H + 3.05, z: -0.45 })
    // operator shack
    b.at({ x: 0.55, z: 0.75 }, () => {
      const sw = 1.8
      const sd = 1.4
      b.box(sw, 2.1, sd, { mat: L === 3 ? 'corrugated' : 'planks', color: L === 3 ? '#a8b0a0' : '#e0d4c0', y: 1.05 })
      b.box(sw + 0.2, 0.08, sd + 0.3, { mat: 'corrugated', color: '#9a9a92', y: 2.15, rx: -0.08 })
      b.box(0.9, 0.55, 0.02, { mat: 'window', color: '#20262a', y: 1.35, z: sd / 2 + 0.01 })
      b.box(1.0, 0.65, 0.04, { mat: 'wood', color: '#e8e4dc', y: 1.35, z: sd / 2 - 0.005 })
      b.plane(0.9, 0.55, { material: screenMat('amber'), y: 1.35, z: sd / 2 + 0.025 })
      b.box(0.04, 1.2, 0.04, { mat: 'steel', color: '#8a8a8a', x: sw / 2 - 0.2, y: 2.7, z: -0.3 })
      b.rope([[-1.0, 2.5, -1.2], [-0.2, 2.1, -0.3]], 0.012, { mat: 'rubber', color: '#1a1a1a', sag: 0.2 })
    })
    I.lights.push({ x: 0.55, y: 1.4, z: 1.6, color: '#ffb040', intensity: 1.5, dist: 4, when: 'night' })
    stool(b, { x: 0.55, z: 1.75 })
    I.spots.push({ x: 0.55, z: 1.75, face: FACE_BACK, anim: 'type', sit: 0.5 })
  },

  // ---------------------------------------------------------------- production
  farm(b, L, I) {
    const rnd = seeded(201)
    const kinds = L === 1 ? ['cabbage', 'carrot', 'tomato'] : L === 2 ? ['corn', 'cabbage', 'potato', 'tomato', 'beans'] : ['corn', 'cabbage', 'tomato', 'potato', 'carrot', 'beans']
    if (L === 1) {
      // three raised beds
      for (let i = 0; i < 3; i++) {
        const z = -1.8 + i * 1.6
        b.at({ z }, () => {
          for (const sz of [-1, 1]) b.box(4.6, 0.3, 0.06, { mat: 'wood', color: '#c8b498', y: 0.15, z: sz * 0.5 })
          for (const sx of [-1, 1]) b.box(0.06, 0.3, 1.0, { mat: 'wood', color: '#c8b498', x: sx * 2.3, y: 0.15 })
          b.box(4.5, 0.26, 0.94, { mat: 'dirt', color: '#6a5444', y: 0.13 })
        })
        b.pivot('crop' + i, { z, y: 0.26 }, (p) => cropRow(p, { kind: kinds[i], len: 4.3, seed: 300 + i }))
        I.anims.push({ name: 'crop' + i, kind: 'grow', when: 'always' })
      }
      scarecrow(b, { x: 2.6, z: 2.4, ry: -0.5 })
      bucket(b, { x: -2.6, z: 2.5 })
      b.at({ x: -2.3, z: 2.6, ry: 0.4 }, () => {
        b.lathe([[0.1, 0], [0.12, 0.2], [0.08, 0.24]], { mat: 'metal', color: '#4a7a5a', seg: 12 })
        b.beam([0.1, 0.18, 0], [0.4, 0.3, 0], 0.025, 0.025, { mat: 'metal', color: '#4a7a5a', round: true })
      })
      b.at({ x: 2.7, z: -2.6 }, () => {
        b.box(0.04, 1.4, 0.04, { mat: 'wood', color: '#c8a070', rz: 0.15, y: 0.7 })
        b.box(0.25, 0.04, 0.2, { mat: 'steel', color: '#7a7a7a', x: -0.1, y: 0.05, rz: 0.15 })
      })
      I.spots.push({ x: -1.2, z: -1.0, face: FACE_BACK, anim: 'hoe' }, { x: 1.0, z: 0.6, face: FACE_BACK, anim: 'hoe' }, { x: 0.2, z: 2.2, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // tilled rows inside a low picket fence, drip hose, compost bin
      b.box(5.6, 0.04, 5.6, { mat: 'dirt', color: '#6e5848', y: 0.0 })
      for (let i = 0; i < 5; i++) {
        const z = -2.2 + i * 1.1
        b.cyl(0.32, 0.32, 5.0, { mat: 'dirt', color: '#5e4a3c', y: -0.12, z, rz: Math.PI / 2, ts: 0, tl: Math.PI, seg: 8 })
        b.pivot('crop' + i, { z, y: 0.12, x: -0.2 }, (p) => cropRow(p, { kind: kinds[i], len: 4.6, seed: 320 + i }))
        I.anims.push({ name: 'crop' + i, kind: 'grow', when: 'always' })
        b.cyl(0.012, 0.012, 4.8, { mat: 'rubber', color: '#1a1a1a', x: -0.2, y: 0.2, z: z + 0.22, rz: Math.PI / 2 })
      }
      for (let i = 0; i <= 26; i++) {
        const t = i / 26
        for (const [ax, az, bx, bz] of [[-2.9, 2.9, 2.9, 2.9]]) {
          const x = ax + (bx - ax) * t
          if (Math.abs(x) < 0.5) continue
          b.box(0.06, 0.7, 0.025, { mat: 'wood', color: '#ece6da', x, y: 0.35, z: az })
          b.cone(0.045, 0.08, { mat: 'wood', color: '#ece6da', x, y: 0.74, z: az, seg: 4 })
        }
      }
      b.box(5.8, 0.06, 0.03, { mat: 'wood', color: '#e0dad0', y: 0.5, z: 2.92 })
      b.box(5.8, 0.06, 0.03, { mat: 'wood', color: '#e0dad0', y: 0.2, z: 2.92 })
      // compost bin and rain barrel
      b.at({ x: 2.35, z: -2.3 }, () => {
        for (const sx of [-1, 1]) boardWall(b, -0.5, 0.5, sx * 0.5, 0.8, { seed: 12, color: '#b8a888' })
        b.at({ ry: Math.PI / 2 }, () => boardWall(b, -0.5, 0.5, -0.5, 0.8, { seed: 13, color: '#b8a888' }))
        b.box(0.9, 0.5, 0.9, { mat: 'dirt', color: '#4a3a2a', y: 0.3 })
      })
      barrel(b, { x: -2.5, z: -2.5, color: '#3a5a3a', open: true, contents: '#3a4a3a' })
      wheelbarrow(b, { x: -2.2, z: 2.3, ry: 0.6 })
      I.spots.push({ x: -1.5, z: -1.6, face: FACE_BACK, anim: 'hoe' }, { x: 0.8, z: 0.6, face: FACE_BACK, anim: 'hoe' }, { x: -0.5, z: 1.7, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: polytunnel greenhouse plus outdoor rows and a sprinkler
    b.box(5.8, 0.04, 5.8, { mat: 'dirt', color: '#6e5848', y: 0.0 })
    b.at({ z: -1.3 }, () => {
      const len = 5.4
      for (let i = 0; i <= 6; i++) {
        const x = -len / 2 + (i * len) / 6
        b.torus(1.45, 0.025, { mat: 'steel', color: '#c8ccd0', x, ry: Math.PI / 2, arc: Math.PI, ts2: 24 })
      }
      b.cyl(1.47, 1.47, len, { mat: 'glass', color: '#e8f4ec', rz: Math.PI / 2, ts: 0, tl: Math.PI, open: true, seg: 20, shadow: false, ry: 0, y: 0 })
      for (let i = 0; i < 2; i++) {
        b.box(len - 0.4, 0.2, 0.8, { mat: 'dirt', color: '#5e4a3c', y: 0.1, z: -0.55 + i * 1.1 })
        b.pivot('cropg' + i, { z: -0.55 + i * 1.1, y: 0.2 }, (p) => cropRow(p, { kind: i ? 'tomato' : 'beans', len: len - 0.6, seed: 340 + i }))
        I.anims.push({ name: 'cropg' + i, kind: 'grow', when: 'always' })
      }
    })
    for (let i = 0; i < 3; i++) {
      const z = 0.9 + i * 0.75
      b.cyl(0.28, 0.28, 5.0, { mat: 'dirt', color: '#5e4a3c', y: -0.1, z, rz: Math.PI / 2, ts: 0, tl: Math.PI, seg: 8 })
      b.pivot('crop' + i, { z, y: 0.12 }, (p) => cropRow(p, { kind: ['corn', 'cabbage', 'potato'][i], len: 4.6, seed: 350 + i }))
      I.anims.push({ name: 'crop' + i, kind: 'grow', when: 'always' })
    }
    // sprinkler with a spinning head
    b.cyl(0.02, 0.02, 0.9, { mat: 'steel', color: '#8a9094', x: 2.6, y: 0.45, z: 1.6 })
    b.pivot('sprinkler', { x: 2.6, y: 0.92, z: 1.6 }, (p) => {
      p.box(0.5, 0.03, 0.03, { mat: 'chrome', color: '#c8ccd0' })
      p.cyl(0.03, 0.03, 0.05, { mat: 'chrome', color: '#c8ccd0' })
    })
    I.anims.push({ name: 'sprinkler', kind: 'spin', speed: 2.5, when: 'active' })
    I.emitters.push({ kind: 'mist', x: 2.6, y: 1.0, z: 1.6, when: 'active' })
    waterTank(b, { x: -2.4, z: 2.4, r: 0.5, h: 1.4, color: '#2a4a6a' })
    I.spots.push({ x: -1.0, z: -0.95, face: Math.PI / 2, anim: 'search' }, { x: 0.6, z: 1.3, face: FACE_BACK, anim: 'hoe' }, { x: -0.8, z: 2.05, face: FACE_BACK, anim: 'hoe' })
  },

  collector(b, L, I) {
    if (L === 1) {
      // two blue barrels under a tarp funnel
      for (const x of [-0.45, 0.45]) barrel(b, { x, z: 0.1, color: '#2a5aa0', open: true, contents: '#4a6a7a', fill: 0.85, rust: 0 })
      b.cloth(2.4, 2.2, { mat: 'canvas', color: COL.tarpBlue, corners: [1.9, 1.9, 1.9, 1.9], sag: 0.75, seed: 9 })
      for (const [x, z] of [[-1.2, -1.1], [1.2, -1.1], [1.2, 1.1], [-1.2, 1.1]]) b.cyl(0.035, 0.035, 1.95, { mat: 'wood', color: COL.woodGrey, x, y: 0.975, z })
      b.cyl(0.04, 0.04, 0.4, { mat: 'plastic', color: '#2a5aa0', y: 1.0 })
      bucket(b, { x: 1.0, z: 0.9, water: true })
      return
    }
    if (L === 2) {
      ibcTote(b, { x: 0, z: 0.3, level: 0.75 })
      b.at({ z: -0.2 }, () => {
        b.cloth(2.8, 2.4, { mat: 'canvas', color: '#d8d0c0', corners: [2.6, 2.6, 2.2, 2.2], sag: 0.5, seed: 4 })
        for (const [x, z, h] of [[-1.4, -1.2, 2.6], [1.4, -1.2, 2.6], [1.4, 1.2, 2.2], [-1.4, 1.2, 2.2]]) b.cyl(0.04, 0.04, h, { mat: 'wood', color: COL.woodGrey, x, y: h / 2, z })
      })
      pipe(b, [[0, 1.7, -0.2], [0, 1.25, 0.3]], 0.04, { color: '#e8e8e0', mat: 'plastic' })
      bucket(b, { x: 0.9, z: 1.25, water: true })
      return
    }
    // L3: a corrugated catchment roof, gutters and two tanks
    slab(b, I, { h: 0.1 })
    frame(b, 2.7, 2.6, 2.5, { hFront: 2.2, braces: false, metal: true, color: '#7a8086' })
    roof(b, I, (r) => corrRoof(r, { w: 3.0, d: 2.8, y: 2.6, drop: 0.4, gutter: true, color: '#c0c4c4' }))
    waterTank(b, { x: -0.6, z: -0.3, r: 0.62, h: 1.6, color: '#3a5a3e' })
    ibcTote(b, { x: 0.7, z: 0.5, ry: Math.PI / 2, level: 0.9 })
    pipe(b, [[1.45, 2.15, 1.45], [1.45, 1.5, 1.45], [1.0, 1.5, 0.9]], 0.045, { color: '#8a9094' })
    pipe(b, [[-1.4, 2.15, 1.45], [-1.4, 2.1, -0.3], [-1.0, 2.05, -0.3]], 0.045, { color: '#8a9094' })
  },

  filter(b, L, I) {
    if (L === 1) {
      // stone well with a hand pump, and a bucket sand filter
      b.at({ x: -0.9, z: -0.2 }, () => {
        for (let i = 0; i < 14; i++) {
          const a = (i / 14) * TAU
          rock(b, { x: Math.cos(a) * 0.55, z: Math.sin(a) * 0.55, s: 0.18, seed: 60 + i, color: '#8a8478' })
        }
        b.cyl(0.42, 0.42, 0.04, { mat: 'water', color: '#2a4a5a', y: 0.2 })
        b.cyl(0.08, 0.1, 1.0, { mat: 'paint', color: '#3a5a3a', y: 0.6 })
        b.cyl(0.12, 0.12, 0.3, { mat: 'paint', color: '#3a5a3a', y: 1.15 })
        b.beam([0, 1.1, 0], [0.1, 1.0, 0.25], 0.04, 0.04, { mat: 'paint', color: '#3a5a3a', round: true })
        b.pivot('handle', { y: 1.3, x: -0.1 }, (p) => p.beam([0, 0, 0], [-0.75, 0.15, 0], 0.04, 0.04, { mat: 'paint', color: '#3a5a3a', round: true }))
        I.anims.push({ name: 'handle', kind: 'pump', axis: 'z', amp: 0.35, speed: 4, when: 'active' })
        bucket(b, { x: 0.12, z: 0.42, water: true })
      })
      b.at({ x: 0.95, z: 0.1 }, () => {
        for (let i = 0; i < 3; i++) bucket(b, { y: i * 0.27, color: ['#d8d8d0', '#c8a040', '#d8d8d0'][i], mat: 'plastic' })
        b.box(0.6, 0.06, 0.6, { mat: 'wood', color: COL.woodGrey, y: -0.03 })
      })
      for (let i = 0; i < 4; i++) b.lathe([[0.08, 0], [0.08, 0.25], [0.03, 0.32], [0.03, 0.36]], { mat: 'plastic', color: '#c8e0e8', x: 1.5 + (i % 2) * 0.2, z: -0.6 + Math.floor(i / 2) * 0.2, seg: 10 })
      I.spots.push({ x: -1.75, z: -0.2, face: Math.PI / 2, anim: 'pump' })
      return
    }
    if (L === 2) {
      deck(b, 3.6, 2.4, 0.3, { color: '#d8ccb6' })
      b.at({ x: -1.0, z: -0.3, y: 0.3 }, () => {
        b.cyl(0.1, 0.12, 1.1, { mat: 'paint', color: '#2a4a7a', y: 0.55 })
        b.cyl(0.14, 0.14, 0.35, { mat: 'paint', color: '#2a4a7a', y: 1.2 })
        b.pivot('handle', { y: 1.35, x: -0.12 }, (p) => p.beam([0, 0, 0], [-0.9, 0.18, 0], 0.045, 0.045, { mat: 'paint', color: '#2a4a7a', round: true }))
        I.anims.push({ name: 'handle', kind: 'pump', axis: 'z', amp: 0.35, speed: 4, when: 'active' })
      })
      for (let i = 0; i < 3; i++) barrel(b, { x: -0.2 + i * 0.7, y: 0.3, z: -0.55, color: ['#e8e8e0', '#3a6a9a', '#3a6a9a'][i], rust: 0.2 })
      pipe(b, [[-0.9, 1.4, -0.3], [-0.2, 1.4, -0.55], [0.5, 1.3, -0.55], [1.2, 1.2, -0.55]], 0.03, { color: '#1a1a1a', mat: 'rubber' })
      ibcTote(b, { x: 1.1, z: 0.65, y: 0.3, ry: Math.PI / 2, level: 0.6 })
      b.cyl(0.03, 0.03, 0.2, { mat: 'chrome', color: '#c0c4c8', x: 0.55, y: 0.6, z: 0.5, rz: Math.PI / 2 })
      bucket(b, { x: 0.4, y: 0.3, z: 0.7, water: true })
      I.spots.push({ x: -1.95, z: -0.3, y: 0, face: Math.PI / 2, anim: 'pump' }, { x: 0.3, z: 1.4, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: electric pump house, steel filter vessels with gauges, glowing UV unit
    slab(b, I, { h: 0.12 })
    b.at({ x: -1.3, z: -0.35 }, () => {
      b.box(1.2, 1.5, 1.4, { mat: 'corrugated', color: '#8a9aa8', y: 0.85 })
      b.box(1.35, 0.06, 1.55, { mat: 'paint', color: '#5a6470', y: 1.62 })
      b.box(0.5, 0.35, 0.02, { mat: 'metal', color: '#e8e8e0', y: 1.0, z: 0.71 })
      b.box(0.08, 0.08, 0.02, { mat: 'glowGreen', color: '#ffffff', x: 0.15, y: 1.25, z: 0.72 })
    })
    for (let i = 0; i < 2; i++) {
      b.at({ x: 0.1 + i * 0.8, z: -0.5 }, () => {
        b.capsule(0.3, 1.1, { mat: 'steel', color: '#c8ccd0', y: 0.95, seg: 14 })
        for (const sx of [-1, 1]) b.box(0.06, 0.3, 0.06, { mat: 'steel', color: '#7a7e82', x: sx * 0.2, y: 0.25 })
        gauge(b, { y: 1.3, z: 0.31 })
        valveWheel(b, { y: 0.55, z: 0.36, r: 0.08, color: '#2a6ab0' })
      })
    }
    pipe(b, [[-0.7, 0.7, -0.35], [-0.2, 0.7, -0.5], [0.9, 0.7, -0.5]], 0.05, { flanges: true })
    pipe(b, [[0.9, 1.6, -0.5], [1.5, 1.6, -0.5], [1.5, 0.5, 0.4]], 0.05, { flanges: true })
    b.at({ x: 1.35, z: 0.6 }, () => {
      b.box(0.3, 0.9, 0.3, { mat: 'steel', color: '#c8ccd0', y: 0.55 })
      b.box(0.04, 0.6, 0.02, { mat: 'glowBlue', color: '#ffffff', y: 0.55, z: 0.16 })
    })
    I.lights.push({ x: 1.35, y: 0.6, z: 0.9, color: '#58b0ff', intensity: 1.2, dist: 3, when: 'active' })
    ibcTote(b, { x: 0.1, z: 0.75, ry: 0, level: 0.8 })
    I.spots.push({ x: -1.3, z: 0.75, face: FACE_BACK, anim: 'type' }, { x: 0.5, z: 0.15, face: FACE_BACK, anim: 'search' })
  },

  lumber(b, L, I) {
    if (L === 1) {
      stump(b, { x: -1.0, z: 0.3, r: 0.32, h: 0.5 })
      b.at({ x: -1.0, y: 0.5, z: 0.3 }, () => {
        b.cyl(0.08, 0.08, 0.3, { mat: 'wood', color: '#e8cfa0', y: 0.15 })
        b.box(0.03, 0.75, 0.03, { mat: 'wood', color: '#c8884a', x: 0.15, y: 0.35, rz: -0.5 })
        b.box(0.18, 0.1, 0.03, { mat: 'steel', color: '#5a5e62', x: 0.0, y: 0.06, rz: -0.5 })
      })
      firewood(b, { x: -1.4, z: -1.7, len: 2.2, h: 1.0 })
      logPile(b, { x: 1.2, z: -1.0, len: 3.0, rows: 3 })
      sawhorse(b, { x: 1.2, z: 1.2, len: 1.0 })
      sawhorse(b, { x: 1.2, z: 1.9, len: 1.0 })
      log(b, { x: 1.2, y: 0.85, z: 1.55, len: 1.8, r: 0.13, rz: Math.PI / 2, ry: Math.PI / 2 })
      b.at({ x: 1.6, y: 0.95, z: 1.5, rz: 0.15 }, () => {
        b.torus(0.32, 0.015, { mat: 'paint', color: '#c83a2a', arc: Math.PI, rx: 0 })
        b.box(0.66, 0.05, 0.005, { mat: 'steel', color: '#c0c4c8', y: 0.0 })
      })
      for (let i = 0; i < 18; i++) b.box(0.06, 0.02, 0.04, { mat: 'wood', color: '#f0d8b0', x: -1 + (Math.sin(i * 7.3) * 0.6), y: 0.01, z: 0.3 + Math.cos(i * 3.1) * 0.6, ry: i, shadow: false })
      I.spots.push({ x: -1.0, z: 1.0, face: FACE_BACK, anim: 'swing', tool: 'axe' }, { x: 0.55, z: 1.55, face: Math.PI / 2, anim: 'saw' }, { x: -0.2, z: -0.5, face: FACE_BACK, anim: 'carry' })
      return
    }
    if (L === 2) {
      // trestle saw platform under a lean-to
      frame(b, 4.0, 2.4, 2.6, { x: 0, z: -0.8, hFront: 2.8, zs: [-2.0, 0.4] })
      roof(b, I, (r) => corrRoof(r, { w: 4.4, d: 2.8, y: 2.95, drop: 0.35, z: -0.8 }))
      b.at({ z: -0.8 }, () => {
        for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.12, 1.2, 0.12, { mat: 'wood', color: COL.woodGrey, x: sx * 1.4, y: 0.6, z: sz * 0.4 })
        for (const sz of [-1, 1]) b.box(3.0, 0.14, 0.14, { mat: 'wood', color: COL.woodGrey, y: 1.25, z: sz * 0.4 })
        log(b, { y: 1.45, len: 3.4, r: 0.2, rz: Math.PI / 2 })
        b.box(0.02, 1.4, 0.3, { mat: 'steel', color: '#b8bcc0', y: 1.2, x: 0.3 })
        b.box(0.05, 0.3, 0.05, { mat: 'wood', color: '#8a5a3a', y: 2.0, x: 0.3 })
      })
      plankStack(b, { x: -1.5, z: 1.4, len: 2.6, w: 0.9, layers: 6, ry: 0 })
      logPile(b, { x: 1.5, z: 1.5, len: 2.4, rows: 3, ry: 0 })
      for (let i = 0; i < 30; i++) b.box(0.05, 0.015, 0.03, { mat: 'wood', color: '#f0d8b0', x: 0.3 + Math.sin(i * 5.1) * 0.5, y: 0.01, z: -0.8 + Math.cos(i * 2.3) * 0.5, ry: i, shadow: false })
      stump(b, { x: 2.4, z: -1.7 })
      I.spots.push({ x: 0.3, z: -0.05, face: FACE_BACK, anim: 'saw' }, { x: 2.3, z: -1.0, face: FACE_BACK, anim: 'swing', tool: 'axe' }, { x: -1.5, z: 0.6, face: 0, anim: 'carry' })
      return
    }
    // L3: a powered sawmill with a spinning blade
    slab(b, I, { h: 0.1 })
    b.at({ z: -0.6 }, () => {
      b.box(4.6, 0.75, 0.9, { mat: 'paint', color: '#4a5a6a', y: 0.45 })
      b.box(4.7, 0.05, 1.0, { mat: 'steel', color: '#9aa0a6', y: 0.85 })
      for (let i = 0; i < 10; i++) b.cyl(0.04, 0.04, 0.9, { mat: 'steel', color: '#c0c4c8', x: -2.1 + i * 0.47, y: 0.9, rx: Math.PI / 2 })
      log(b, { x: -1.2, y: 1.1, len: 2.0, r: 0.22, rz: Math.PI / 2 })
      b.box(0.9, 0.9, 1.1, { mat: 'paint', color: '#d8a020', x: 0.8, y: 1.3 })
      b.pivot('blade', { x: 0.3, y: 0.95, z: 0 }, (p) => {
        p.cyl(0.55, 0.55, 0.01, { mat: 'chrome', color: '#d0d4d8', rx: Math.PI / 2, rz: 0, seg: 28, ry: Math.PI / 2 })
        for (let i = 0; i < 16; i++) {
          const a = (i / 16) * TAU
          p.box(0.05, 0.05, 0.012, { mat: 'steel', color: '#5a5e62', y: Math.cos(a) * 0.55, z: Math.sin(a) * 0.55, rx: a, ry: Math.PI / 2 })
        }
      })
      I.anims.push({ name: 'blade', kind: 'spin', axis: 'x', speed: 22, when: 'active' })
      b.box(0.5, 0.4, 0.5, { mat: 'paint', color: '#2a6a3a', x: 1.6, y: 1.05 })
      b.cyl(0.18, 0.18, 0.4, { mat: 'paint', color: '#2a6a3a', x: 1.6, y: 1.05, z: 0.4, rx: Math.PI / 2 })
      b.box(0.3, 0.35, 0.05, { mat: 'paint', color: '#d8d8d0', x: 2.1, y: 1.1, z: 0.46 })
      b.box(0.06, 0.06, 0.02, { mat: 'glowGreen', color: '#ffffff', x: 2.1, y: 1.2, z: 0.49 })
    })
    I.emitters.push({ kind: 'sawdust', x: 0.3, y: 1.0, z: -0.4, when: 'active' })
    b.cone(0.9, 0.5, { mat: 'dirt', color: '#e8c890', x: 0.6, z: 0.4, y: 0.25, seg: 14, sy: 1 })
    plankStack(b, { x: -1.5, z: 1.6, len: 2.6, w: 1.0, layers: 7 })
    plankStack(b, { x: 1.7, z: 1.6, len: 2.2, w: 0.8, layers: 5, seed: 12 })
    I.lights.push({ x: 0, y: 2.6, z: 0, color: '#fff0d0', intensity: 3, dist: 9, when: 'night' })
    lampPost(b, { x: -2.6, z: 0.7, h: 3.0 })
    I.spots.push({ x: -1.0, z: 0.25, face: FACE_BACK, anim: 'pump' }, { x: 2.1, z: 0.3, face: FACE_BACK, anim: 'type' }, { x: 0.2, z: 1.5, face: 0, anim: 'carry' })
  },

  scrapyard(b, L, I) {
    const wreck = (x, z, ry, stripped, color) => carWreck(b, { x, z, ry, stripped, color })
    if (L === 1) {
      scrapPile(b, { x: -1.4, z: -1.0, r: 1.3, seed: 2 })
      scrapPile(b, { x: 1.6, z: 1.3, r: 0.9, seed: 3, n: 10 })
      wreck(1.0, -0.9, 0.3, 0.6, '#6a7a8a')
      for (let i = 0; i < 3; i++) barrel(b, { x: -2.3 + i * 0.65, z: 1.7, color: ['#5a5a5a', '#7a4a2a', '#4a5a3a'][i], open: true, contents: '#3a3530' })
      b.box(0.04, 0.9, 0.04, { mat: 'wood', color: '#c8a070', x: -0.3, y: 0.45, z: 1.2, rz: 0.3 })
      b.box(0.14, 0.12, 0.25, { mat: 'steel', color: '#3a3e42', x: -0.42, y: 0.06, z: 1.2 })
      I.spots.push({ x: 0.2, z: -0.1, face: Math.PI / 2 + 0.3, anim: 'hammer' }, { x: -1.3, z: 0.6, face: FACE_BACK, anim: 'search' }, { x: 1.0, z: 0.7, face: FACE_BACK, anim: 'carry' })
      return
    }
    if (L === 2) {
      wreck(-1.3, -0.9, 0.1, 0.4, '#8a3a2a')
      wreck(1.5, -1.2, -0.25, 0.8, '#3a5a7a')
      // engine hoist over the red car
      b.at({ x: -1.3, z: -0.9 }, () => {
        for (const sx of [-1, 1]) b.beam([sx * 1.1, 0, 0.9], [0, 2.6, 0.2], 0.1, 0.1, { mat: 'paint', color: '#c8a020' })
        for (const sx of [-1, 1]) b.beam([sx * 1.1, 0, -0.5], [0, 2.6, 0.2], 0.1, 0.1, { mat: 'paint', color: '#c8a020' })
        b.rope([[0, 2.55, 0.2], [0, 1.4, 0.2]], 0.015, { mat: 'steel', color: '#4a4a4a', sag: 0, steps: 2 })
        b.box(0.55, 0.45, 0.5, { mat: 'metal', color: '#3a3a3a', y: 1.15, z: 0.2 })
      })
      scrapPile(b, { x: 1.9, z: 1.5, r: 0.9, seed: 6, n: 10 })
      table(b, { x: -1.5, z: 1.6, w: 1.6, d: 0.7, h: 0.85, color: '#a8a090', mat: 'metal' })
      b.at({ x: -1.9, y: 0.86, z: 1.55 }, () => {
        b.box(0.2, 0.15, 0.2, { mat: 'paint', color: '#3a5a3a', y: 0.08 })
        for (const sx of [-1, 1]) b.cyl(0.08, 0.08, 0.03, { mat: 'steel', color: '#8a8e92', x: sx * 0.15, y: 0.12, rz: Math.PI / 2 })
      })
      I.emitters.push({ kind: 'sparks', x: -1.75, y: 1.0, z: 1.55, when: 'active' })
      for (let i = 0; i < 4; i++) barrel(b, { x: 0.2 + (i % 2) * 0.6, z: 1.3 + Math.floor(i / 2) * 0.6, color: ['#5a5a5a', '#7a4a2a', '#4a5a3a', '#3a5878'][i], open: true, contents: '#3a3530' })
      tireStack(b, { x: 2.6, z: -2.0, n: 4 })
      I.spots.push({ x: -1.3, z: 0.4, face: FACE_BACK, anim: 'hammer' }, { x: -1.75, z: 2.2, face: FACE_BACK, anim: 'search' }, { x: 1.4, z: 0.0, face: FACE_BACK, anim: 'saw' })
      return
    }
    // L3: hydraulic crusher, cubes of crushed cars, a swinging crane
    slab(b, I, { h: 0.12 })
    b.at({ x: -1.2, z: -0.9 }, () => {
      b.box(2.6, 0.5, 1.6, { mat: 'paint', color: '#d8a020', y: 0.3 })
      for (const sx of [-1, 1]) b.box(0.3, 2.2, 0.3, { mat: 'paint', color: '#d8a020', x: sx * 1.1, y: 1.3 })
      b.box(2.6, 0.4, 0.4, { mat: 'paint', color: '#d8a020', y: 2.4 })
      b.pivot('ram', { y: 1.6 }, (p) => {
        p.box(2.0, 0.3, 1.2, { mat: 'steel', color: '#5a5e62' })
        p.cyl(0.12, 0.12, 0.8, { mat: 'chrome', color: '#c8ccd0', y: 0.5 })
      })
      I.anims.push({ name: 'ram', kind: 'press', axis: 'y', amp: 0.6, speed: 0.8, when: 'active' })
      b.box(1.8, 0.5, 1.0, { mat: 'rust', color: '#ffffff', y: 0.75 })
    })
    for (let i = 0; i < 5; i++) {
      const x = 1.2 + (i % 3) * 0.75
      const y = Math.floor(i / 3) * 0.6
      b.box(0.7, 0.58, 0.7, { mat: 'rust', color: ['#ffffff', '#c8b8a8', '#b8c0c8'][i % 3], x, y: y + 0.3, z: 1.2 + (i % 2) * 0.1, ry: i * 0.2 })
      b.box(0.71, 0.1, 0.71, { mat: 'paint', color: ['#8a3a2a', '#3a5a7a', '#5a6a3a'][i % 3], x, y: y + 0.35, z: 1.2 + (i % 2) * 0.1, ry: i * 0.2 })
    }
    b.at({ x: 2.2, z: -1.6 }, () => {
      b.cyl(0.25, 0.3, 0.4, { mat: 'paint', color: '#4a5a6a', y: 0.2 })
      b.box(0.2, 3.2, 0.2, { mat: 'paint', color: '#d8a020', y: 1.8 })
      b.pivot('crane', { y: 3.3 }, (p) => {
        p.beam([0.3, 0, 0], [-2.6, 0.6, 0], 0.18, 0.18, { mat: 'paint', color: '#d8a020' })
        p.box(0.6, 0.5, 0.4, { mat: 'concrete', color: '#a8a4a0', x: 0.6, y: -0.1 })
        p.rope([[-2.5, 0.55, 0], [-2.5, -1.4, 0]], 0.015, { mat: 'steel', color: '#3a3a3a', sag: 0, steps: 2 })
        p.cyl(0.35, 0.35, 0.12, { mat: 'paint', color: '#3a3a3a', x: -2.5, y: -1.5 })
      })
      I.anims.push({ name: 'crane', kind: 'yaw', speed: 0.3, swing: 0.9, when: 'active' })
    })
    I.emitters.push({ kind: 'dust', x: -1.2, y: 0.9, z: -0.3, when: 'active' })
    I.lights.push({ x: 0, y: 2.8, z: 0.4, color: '#fff0d0', intensity: 3, dist: 9, when: 'night' })
    I.spots.push({ x: -1.2, z: 0.3, face: FACE_BACK, anim: 'type' }, { x: 0.6, z: 1.5, face: Math.PI / 2, anim: 'carry' }, { x: 2.2, z: -0.7, face: FACE_BACK, anim: 'lookout' })
  },
}

// ---------------------------------------------------------------- build entry
const cache = new Map()
export function stationModel(type, level) {
  const key = type + ':' + level
  let base = cache.get(key)
  if (!base) {
    const def = STATIONS[type]
    const [w, d] = def.size
    const I = { w, d, lights: [], emitters: [], flames: [], spots: [], beds: [], seats: [], anims: [], blink: [], roofs: [] }
    const b = new Builder()
    const fn = HD[type] || M[type] || STATIONS2[type]
    if (fn) fn(b, Math.max(1, level), I)
    else b.box(w - 0.4, 1, d - 0.4, { mat: 'wood', y: 0.5 })
    const g = b.build()
    g.userData.info = I
    base = g
    cache.set(key, base)
  }
  const g = base.clone()
  g.userData.info = base.userData.info
  // re-resolve pivots on the clone
  const pv = {}
  g.traverse((o) => {
    if (o.name && base.userData.pivots[o.name]) pv[o.name] = o
  })
  g.userData.pivots = pv
  return g
}
export function clearStationCache() {
  cache.clear()
}

// Construction scaffolding drawn over a site.
export function scaffold(w, d, progress = 0) {
  const b = new Builder()
  const h = 2.6
  const rnd = seeded(Math.floor(w * 7 + d * 13))
  for (const x of [-w / 2 + 0.2, w / 2 - 0.2]) for (const z of [-d / 2 + 0.2, d / 2 - 0.2]) b.cyl(0.035, 0.035, h, { mat: 'steel', color: '#a8aeb4', x, y: h / 2, z, seg: 6 })
  for (const y of [1.0, 2.0]) {
    for (const z of [-d / 2 + 0.2, d / 2 - 0.2]) b.cyl(0.03, 0.03, w - 0.4, { mat: 'steel', color: '#a8aeb4', y, z, rz: Math.PI / 2, seg: 6 })
    for (const x of [-w / 2 + 0.2, w / 2 - 0.2]) b.cyl(0.03, 0.03, d - 0.4, { mat: 'steel', color: '#a8aeb4', y, x, rx: Math.PI / 2, seg: 6 })
  }
  b.box(w - 0.4, 0.04, 0.5, { mat: 'wood', color: '#d8c8a8', y: 2.0, z: d / 2 - 0.35 })
  b.box(w - 0.4, 0.04, 0.5, { mat: 'wood', color: '#d8c8a8', y: 1.0, z: -d / 2 + 0.35 })
  plankStack(b, { x: -w / 2 + 1.2, z: d / 2 + 0.55, len: 1.8, w: 0.5, layers: 3 })
  for (let i = 0; i < 3; i++) b.cyl(0.04, 0.04, 1.8, { mat: 'steel', color: '#8a9096', x: w / 2 - 0.8 + i * 0.1, y: 0.05 + (i % 2) * 0.08, z: d / 2 + 0.6, rz: Math.PI / 2, seg: 6 })
  cinderBlocks(b, { x: w / 2 - 0.6, z: -d / 2 - 0.5, n: 5 })
  sawhorse(b, { x: 0.3, z: d / 2 + 0.6, len: 0.9, ry: rnd() * 0.3 })
  // the outline of the future building
  for (const z of [-d / 2 + 0.05, d / 2 - 0.05]) b.box(w - 0.1, 0.04, 0.04, { mat: 'paint', color: '#e8c030', y: 0.02, z, ao: 0 })
  for (const x of [-w / 2 + 0.05, w / 2 - 0.05]) b.box(0.04, 0.04, d - 0.1, { mat: 'paint', color: '#e8c030', y: 0.02, x, ao: 0 })
  return b.build()
}
