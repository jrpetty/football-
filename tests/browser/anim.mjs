// Read the skeleton out of the shipped build while each contact plays, so
// "the animation scales with power" is a measurement rather than a claim.
//
// Needs a browser: the rig builds its kit textures on a 2D canvas, so it can't
// be instantiated under bare Node like the simulation tests can. Run with
//   npm run build:single && node tests/browser/anim.mjs
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')

const b = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader'],
})
const pg = await b.newPage({ viewport: { width: 1000, height: 640 } })
pg.on('pageerror', (e) => console.log('pageerror:', e.message))
await pg.goto(`file://${process.cwd()}/open-pitch.html?debug`)
await pg.waitForTimeout(1200)
await pg.click('text=🎮 Immersive 3D')
await pg.click('text=🎯  Training')
await pg.waitForTimeout(2500)

const out = await pg.evaluate(() => {
  const game = window.__game
  const world = window.__world
  const p = world.getControlledPlayer()
  const scene = Object.values(game).find((v) => v && v.players instanceof Map && v.players.size)
  const rig = scene.players.get(p.id)
  game.running = false

  // Locate the joints structurally, so minified field names don't matter.
  const body = rig.group.children[0]
  const root = body.children[0]
  const hips = root.children.find((c) => c.type === 'Group')
  const torso = hips.children.find((c) => c.type === 'Group')
  // Leg hips are the Groups parented to `hips` other than the torso.
  const legHips = hips.children.filter((c) => c.type === 'Group' && c !== torso)

  const rows = []
  const play = (label, kind, power) => {
    p.x = 29
    p.y = 19
    p.vx = 0
    p.vy = 0
    p.heading = 0
    p.startKick(kind, power)
    const len = p.kickAnimLen
    let backMax = 0 // furthest the striking leg goes BEHIND (positive rot.x)
    let throughMax = 0 // furthest it carries in FRONT (negative rot.x)
    let trunkMax = 0
    let plantMax = 0
    const steps = 48
    for (let i = 0; i <= steps; i++) {
      p.kickTimer = len * (1 - i / steps)
      rig.pose(p, p.x, p.y, len / steps)
      const strikeHip = legHips[p.kickLeg].rotation.x
      const plantHip = legHips[1 - p.kickLeg].rotation.z
      backMax = Math.max(backMax, strikeHip)
      throughMax = Math.min(throughMax, strikeHip)
      trunkMax = Math.max(trunkMax, Math.abs(torso.rotation.y))
      plantMax = Math.max(plantMax, Math.abs(plantHip))
    }
    rows.push({
      label,
      len: +len.toFixed(3),
      back: +backMax.toFixed(2),
      through: +throughMax.toFixed(2),
      arc: +(backMax - throughMax).toFixed(2),
      trunk: +trunkMax.toFixed(2),
      plant: +plantMax.toFixed(2),
    })
  }

  play('touch  10%', 'touch', 0.1)
  play('touch 100%', 'touch', 1.0)
  play('strike  10%', 'strike', 0.1)
  play('strike  50%', 'strike', 0.5)
  play('strike 100%', 'strike', 1.0)

  // Residue check: run for a second after a full strike and confirm the gait
  // has taken every axis back.
  p.kickTimer = 0
  p.vx = 6
  for (let i = 0; i < 120; i++) rig.pose(p, p.x, p.y, 1 / 60)
  const residue = {
    legZ: legHips.map((h) => +h.rotation.z.toFixed(3)),
    rootZ: +root.rotation.z.toFixed(3),
    rootX: +root.rotation.x.toFixed(3),
  }
  return { rows, residue }
})

console.log('contact'.padEnd(13), 'length', ' back', 'through', '  arc', 'trunk', 'plant')
console.log('─'.repeat(66))
for (const r of out.rows) {
  console.log(
    r.label.padEnd(13),
    `${r.len.toFixed(2)}s`.padStart(6),
    String(r.back).padStart(5),
    String(r.through).padStart(7),
    String(r.arc).padStart(5),
    String(r.trunk).padStart(5),
    String(r.plant).padStart(5),
  )
}
console.log('\nafter a full strike, once running again:', JSON.stringify(out.residue))
await b.close()
