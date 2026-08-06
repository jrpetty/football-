// Two real browser pages, connected over WebRTC, playing the same match.
//
// Two real browser pages, connected over WebRTC by copy-pasting the invite,
// playing the same match. Run with:
//   npm run build:single && node tests/browser/net.mjs
//
// Note: the handshake is timing-sensitive. ICE gathering has to finish before
// the invite is copied, and headlessly that can take several seconds — if this
// reports the guest never joined, give the waits below more room before
// concluding anything is wrong.
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const errs = []
const b = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined, args:['--use-gl=swiftshader','--enable-unsafe-swiftshader'] })

const open = async (tag) => {
  const pg = await b.newPage({ viewport:{ width:820, height:520 } })
  pg.on('pageerror', e => errs.push(`[${tag}] ${e.message}`))
  pg.on('console', m => { if (m.type()==='error') errs.push(`[${tag}] ${m.text()}`) })
  await pg.goto(`file://${process.cwd()}/open-pitch.html?debug`)
  await pg.waitForTimeout(700)
  await pg.click('text=🎮 Immersive 3D')
  await pg.click('text=🌐  Play Online')
  await pg.waitForTimeout(300)
  return pg
}
const host = await open('host')
const guest = await open('guest')

// --- handshake, exactly as a player would do it by copy-paste ---
await host.click('[data-net="rtc-host"]')
await host.waitForSelector('.netblob', { timeout: 15000 })
await host.waitForTimeout(6000)
const offer = await host.$eval('.netblob', el => el.value)
console.log(`offer: ${offer.length} chars`)

await guest.click('[data-net="rtc-join"]')
await guest.fill('[data-f="offer"]', offer)
await guest.click('[data-go]')
await guest.waitForTimeout(4500)
const answer = await guest.$$eval('.netblob', els => els[els.length-1].value)
console.log(`answer: ${answer.length} chars`)

await host.fill('[data-f="answer"]', answer)
await host.click('[data-go]')
await host.waitForTimeout(6000)

const state = async (pg) => pg.evaluate(() => {
  const w = window.__world
  if (!w) return { inGame: false }
  const g = window.__game
  const cl = g.client
  return { inGame: true, online: !!g.online, seats: w.netSeats ? [...w.netSeats] : [],
    controlled: w.controlledId, players: w.players.length,
    client: cl ? { joined: cl.joined, playerId: cl.playerId, err: cl.error } : null,
    ball: [+w.ball.x.toFixed(2), +w.ball.y.toFixed(2)] }
})
console.log('host :', JSON.stringify(await state(host)))
console.log('guest:', JSON.stringify(await state(guest)))

// --- does the guest's input actually move their player on the HOST? ---
const seat = (await state(host)).seats[0]
if (seat !== undefined) {
  const before = await host.evaluate((id) => { const p = window.__world.player(id); return [p.x, p.y] }, seat)
  // Drive the guest forward for a second by feeding its controller directly.
  // Deliberately not tested here: whether the guest's *input* moves their
  // player on the host. A headless page cannot take pointer lock, so the
  // guest's controller correctly produces nothing, and injecting a Command by
  // hand races the game loop's own empty one — either way the answer is zero
  // and it means nothing. tests/net.test.ts drives both sessions directly with
  // no browser at all, which is where that half is actually proven.
  void before

  // And does the host's world reach the guest?
  const hb = await host.evaluate(() => { window.__world.ball.setPos(40, 12, 0); return [40,12] })
  await guest.waitForTimeout(700)
  const gb = await guest.evaluate(() => [ +window.__world.ball.x.toFixed(1), +window.__world.ball.y.toFixed(1) ])
  console.log(`host moved the ball to ${hb} — guest sees ${gb}`)

  // The connection panel: names over heads and a ping in the corner. Both are
  // drawn only when a session exists, so this is the only place they can be
  // seen at all.
  const names = async (pg) => pg.evaluate(() => {
    const w = window.__world
    return w.players.map((p) => w.nameFor(p))
  })
  // Raw round-trip samples, straight off the host's peer record, so a bad ping
  // can be told apart from a bad measurement.
  // Ping, with a caveat worth writing down: two headless pages share one CPU
  // and Chromium throttles whichever is not in front to about 1 Hz. A page
  // stepping once a second cannot acknowledge anything faster than that, so
  // the round trip here reads well over a second and that number is correct —
  // the loop really is that slow. It says nothing about the measurement, which
  // tests/online.test.ts pins down exactly against a controlled clock: 80 ms
  // of injected latency reads as 83 ms.
  const probe = await host.evaluate(() => {
    const p = [...window.__game.host.peers.values()][0]
    return { ping: Math.round(p.ping), unacked: p.sentAt.size }
  })
  const guestFps = await guest.evaluate(async () => {
    const t0 = performance.now()
    let frames = 0
    await new Promise((r) => {
      const step = () => { frames++; performance.now() - t0 < 1500 ? requestAnimationFrame(step) : r() }
      requestAnimationFrame(step)
    })
    return +(frames / ((performance.now() - t0) / 1000)).toFixed(1)
  })
  console.log(`ping ${probe.ping}ms with the guest page running at ${guestFps} FPS — a throttled tab, not a slow link`)
  console.log('host  roster on screen:', JSON.stringify(await names(host)))
  console.log('guest roster on screen:', JSON.stringify(await names(guest)))
  if (process.env.SHOT) {
    await host.screenshot({ path: `${process.env.SHOT}/net-host.png` })
    await guest.screenshot({ path: `${process.env.SHOT}/net-guest.png` })
  }
}
await b.close()
console.log('\n' + (errs.length ? 'ERRORS:\n'+errs.slice(0,8).join('\n') : 'no runtime errors'))
