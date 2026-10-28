// A synthetic Counter-Strike 2 match for the demo and tests: a short
// match (eight rounds a half, not the real twelve) with an economy that
// follows the game's rules (start money, win rewards, the loss bonus, kill
// rewards, half-time reset), the player's buys, kills, deaths and the round
// results, sent as the game's own Game State Integration updates would be,
// alongside what the screen reader would see (where the player is, the death
// panel's details a few seconds after each death). The scripted player dies
// early on T side, forces after lost rounds and often dies with grenades in
// hand, so the coach has something to find. Not a real match.

import { Builder, type DemoMap, type VisionReplay } from './visionReplay.ts'
import { mulberry32 } from './syntheticArena.ts'

const SELF = '76561190000000001'
const MATE = '76561190000000002'
const FREEZE = 4000
const OVER = 4000
const HALF = 8
const TO_WIN = 9

type Side = 'CT' | 'T'
type Weapon = { name: string; type: string; state: string; ammo_clip?: number; ammo_reserve?: number }

const LOSS = [1400, 1900, 2400, 2900, 3400]

export function cs2MatchReplay(opts: { readonly seed: number; readonly map: DemoMap; readonly mapId: string; readonly intervalMs?: number }): VisionReplay {
  const rnd = mulberry32(opts.seed)
  const b = new Builder('cs2', rnd)
  const gsi: { at: number; payload: unknown }[] = []
  const interval = opts.intervalMs ?? 2500
  const demo = opts.map
  const pick = <T>(xs: readonly T[]) => xs[Math.floor(rnd() * xs.length)]

  let t = 2000
  let money = 800
  let losses = 1
  const score = { CT: 0, T: 0 }
  const wins: Record<string, string> = {}
  const stats = { kills: 0, assists: 0, deaths: 0, mvps: 0, score: 0 }
  const truth = { rounds: 0, won: 0, kills: 0, deaths: 0, blindDeaths: 0, forces: 0, earlyDeaths: 0, utilityDeaths: 0, thrown: 0, flashed: 0, burned: 0, carried: 0, byBuy: { pistol: 0, eco: 0, force: 0, full: 0 } as Record<string, number> }
  let carry: Weapon[] | null = null
  let lastLost = false

  const send = (at: number, phase: { map: string; round: string | null; win?: Side; bomb?: string }, player: Record<string, unknown>) => {
    gsi.push({
      at,
      payload: {
        provider: { name: 'Counter-Strike: Global Offensive', appid: 730, version: 14000, steamid: SELF, timestamp: Math.round(at / 1000) },
        map: { mode: 'competitive', name: opts.mapId, phase: phase.map, round: score.CT + score.T, team_ct: { score: score.CT, consecutive_round_losses: 0 }, team_t: { score: score.T, consecutive_round_losses: 0 }, round_wins: { ...wins } },
        ...(phase.round ? { round: { phase: phase.round, ...(phase.win ? { win_team: phase.win } : {}), ...(phase.bomb ? { bomb: phase.bomb } : {}) } } : {}),
        player,
        auth: { token: 'demo' },
      },
    })
  }
  const me = (side: Side, state: Record<string, unknown>, weapons: Weapon[]) => ({
    steamid: SELF,
    name: 'You',
    team: side,
    activity: 'playing',
    state,
    weapons: Object.fromEntries(weapons.map((w, i) => [`weapon_${i}`, w])),
    match_stats: { ...stats },
  })
  const mate = (side: Side) => ({ steamid: MATE, name: 'Teammate', team: side, activity: 'playing', state: { health: 64, armor: 80, money: 1200, round_kills: 1 }, weapons: {}, match_stats: { kills: 9, deaths: 7 } })

  let n = 0
  while (score.CT < TO_WIN && score.T < TO_WIN && n < 2 * HALF + 1) {
    n++
    const side: Side = n <= HALF ? 'CT' : 'T'
    if (n === HALF + 1) {
      money = 800
      losses = 1
      carry = null
    }
    const pistol = n === 1 || n === HALF + 1
    // The buy: a full buy when affordable; after a lost round, a force half the time; else save.
    const full = side === 'CT' ? 4700 : 4300
    let buy: 'pistol' | 'eco' | 'force' | 'full'
    let spent: number
    let weapons: Weapon[]
    const pistolName = side === 'CT' ? 'weapon_usp_silencer' : 'weapon_glock'
    if (pistol) {
      buy = 'pistol'
      spent = Math.min(money, 650)
      weapons = [{ name: 'weapon_knife', type: 'Knife', state: 'holstered' }, { name: pistolName, type: 'Pistol', state: 'active', ammo_clip: 12 }]
    } else if (carry) {
      buy = 'full'
      spent = Math.min(money, 600)
      weapons = carry
    } else if (money >= full) {
      buy = 'full'
      spent = full - 200
      weapons = [
        { name: 'weapon_knife', type: 'Knife', state: 'holstered' },
        { name: pistolName, type: 'Pistol', state: 'holstered' },
        { name: side === 'CT' ? 'weapon_m4a1_silencer' : 'weapon_ak47', type: 'Rifle', state: 'active', ammo_clip: 25 },
        { name: 'weapon_smokegrenade', type: 'Grenade', state: 'holstered', ammo_reserve: 1 },
        { name: 'weapon_flashbang', type: 'Grenade', state: 'holstered', ammo_reserve: 2 },
      ]
    } else if (lastLost && money >= 2000 && rnd() < 0.7) {
      buy = 'force'
      // A force stays under a full buy's equipment (FULL_BUY in the GSI reader).
      spent = Math.min(money - 150, (side === 'CT' ? 3800 : 3600) - 250)
      weapons = [
        { name: 'weapon_knife', type: 'Knife', state: 'holstered' },
        { name: pistolName, type: 'Pistol', state: 'holstered' },
        { name: side === 'CT' ? 'weapon_famas' : 'weapon_galilar', type: 'Rifle', state: 'active', ammo_clip: 30 },
        { name: 'weapon_flashbang', type: 'Grenade', state: 'holstered', ammo_reserve: 1 },
      ]
    } else {
      buy = 'eco'
      spent = Math.min(money, rnd() < 0.5 ? 0 : 300)
      weapons = [{ name: 'weapon_knife', type: 'Knife', state: 'holstered' }, { name: spent ? 'weapon_p250' : pistolName, type: 'Pistol', state: 'active', ammo_clip: 13 }]
    }
    truth.byBuy[buy]++
    if (buy === 'force') truth.forces++
    const equip = buy === 'pistol' ? 200 + spent : buy === 'eco' ? 200 + spent : buy === 'force' ? 200 + spent : side === 'CT' ? 4700 : 4300
    const kept = carry
    carry = null
    // How the round goes.
    const winP = { pistol: 0.5, eco: 0.12, force: 0.3, full: side === 'CT' ? 0.62 : 0.55 }[buy]
    const won = rnd() < winP
    const kills = Math.min(4, Math.floor(rnd() * (won ? 3.2 : 1.8)))
    const died = rnd() < (won ? 0.3 : 0.8)
    const live = 14_000 + Math.round(rnd() * 14_000)
    // On T side the scripted player dies early, often with grenades left.
    const diedAt = died ? Math.round((side === 'T' ? 3 + rnd() * 12 : 6 + rnd() * (live / 1000 - 8)) * 1000) : null
    const blind = died && rnd() < 0.3
    const reloading = died && !blind && rnd() < 0.12
    truth.carried += weapons.filter((w) => w.type === 'Grenade').reduce((s, w) => s + (w.ammo_reserve ?? 1), 0)
    const t0 = t
    const tLive = t0 + FREEZE
    const tEnd = tLive + live
    const state0 = { health: 100, armor: buy === 'eco' && !kept ? 0 : 100, helmet: buy === 'full', flashed: 0, smoked: 0, burning: 0, money, round_kills: 0, round_killhs: 0, round_totaldmg: 0, equip_value: kept ? equip : 200 }
    send(t0, { map: 'live', round: 'freezetime' }, me(side, state0, kept ?? [{ name: 'weapon_knife', type: 'Knife', state: 'holstered' }, { name: pistolName, type: 'Pistol', state: 'active' }]))
    money -= spent
    send(tLive, { map: 'live', round: 'live' }, me(side, { ...state0, money, equip_value: equip }, weapons))
    // While alive, in time order: kills, grenades thrown, being flashed and catching fire. On CT the
    // scripted player uses most of their grenades; on T, few before dying.
    const alive = diedAt ?? live - 1500
    const held: Weapon[] = weapons.map((w) => ({ ...w }))
    const hand = () => held.map((w) => ({ ...w }))
    type Moment = { at: number; kind: 'kill' | 'throw' | 'flash' | 'burn'; name?: string; level?: number }
    const moments: Moment[] = []
    for (let k = 0; k < kills; k++) moments.push({ at: Math.round(((k + 1) / (kills + 1)) * alive), kind: 'kill' })
    const throwP = side === 'CT' ? 0.8 : 0.3
    for (const w of held) if (w.type === 'Grenade') for (let c = 0; c < (w.ammo_reserve ?? 1); c++) if (rnd() < throwP) moments.push({ at: 1500 + Math.round(rnd() * Math.max(500, alive - 3000)), kind: 'throw', name: w.name })
    // A flash fades over about two seconds; fire burns for under one. Each step is its own update.
    if (alive > 6000 && rnd() < 0.35) {
      const at = 2000 + Math.round(rnd() * (alive - 5000))
      ;[255, 170, 60, 0].forEach((level, i) => moments.push({ at: at + i * 600, kind: 'flash', level }))
    }
    if (alive > 5000 && rnd() < 0.15) {
      const at = 2000 + Math.round(rnd() * (alive - 4000))
      moments.push({ at, kind: 'burn', level: 255 }, { at: at + 800, kind: 'burn', level: 0 })
    }
    moments.sort((x, y) => x.at - y.at)
    const LIVE = { map: 'live', round: 'live' }
    let rk = 0
    let hs = 0
    let dmg = 0
    // The flash and fire levels carry into every update until they fade.
    let flash = 0
    let burn = 0
    const now = (extra: Record<string, unknown> = {}) => ({ ...state0, money: money + 300 * rk, equip_value: equip, round_kills: rk, round_killhs: hs, round_totaldmg: dmg, flashed: flash, burning: burn, ...extra })
    for (const m of moments) {
      const at = tLive + m.at
      if (m.kind === 'kill') {
        rk++
        if (rnd() < 0.5) hs++
        dmg += 100
        stats.kills++
        truth.kills++
        send(at, LIVE, me(side, now(), hand()))
        b.batch(at + 300, {}, [{ type: 'kill', attributes: { headshot: hs === rk ? 'head' : 'body', range: pick(['close', 'mid', 'mid', 'long']), movement: pick(['still', 'still', 'moving']), opening: rk === 1 && rnd() < 0.3 ? 'first_death' : 'later' }, evidence: 'kill feed' }])
      } else if (m.kind === 'throw') {
        const w = held.find((x) => x.name === m.name)
        if (!w) continue
        if ((w.ammo_reserve ?? 1) > 1) w.ammo_reserve = (w.ammo_reserve ?? 1) - 1
        else held.splice(held.indexOf(w), 1)
        truth.thrown++
        send(at, LIVE, me(side, now(), hand()))
      } else if (m.kind === 'flash') {
        if (m.level === 255) truth.flashed++
        flash = m.level ?? 0
        send(at, LIVE, me(side, now(), hand()))
      } else {
        if (m.level) truth.burned++
        burn = m.level ?? 0
        send(at, LIVE, me(side, now({ health: burn ? 92 : 84 }), hand()))
      }
    }
    if (died) {
      const at = tLive + diedAt!
      stats.deaths++
      truth.deaths++
      if (blind) truth.blindDeaths++
      if (diedAt! <= 20_000) truth.earlyDeaths++
      if (held.filter((w) => w.type === 'Grenade').reduce((s, w) => s + (w.ammo_reserve ?? 1), 0) >= 2) truth.utilityDeaths++
      const dying = now({ round_totaldmg: dmg + 40 })
      if (blind) send(at - 400, LIVE, me(side, { ...dying, flashed: 255 }, hand()))
      if (reloading) send(at - 300, LIVE, me(side, dying, hand().map((w) => (w.state === 'active' ? { ...w, state: 'reloading' } : w))))
      send(at, LIVE, me(side, { ...dying, health: 0, armor: 0, flashed: blind ? 200 : 0 }, hand()))
      send(at + 1200, { map: 'live', round: 'live' }, mate(side))
      // The screen: the death panel and freeze-cam a few seconds later.
      const killedBy = pick(['rifle', 'rifle', 'rifle', 'awp', 'smg', 'pistol'] as const)
      const killerWeapon = { rifle: side === 'CT' ? 'AK-47' : 'M4A1-S', awp: 'AWP', smg: 'MAC-10', pistol: 'Desert Eagle' }[killedBy]
      const area = demo.route[(n + 1) % demo.route.length]
      b.batch(
        at + 1500,
        {},
        [
          {
            type: 'death',
            attributes: {
              killedBy,
              traded: rnd() < 0.6 ? 'no' : 'yes',
              exposure: rnd() < 0.45 ? 'two_plus' : 'one_enemy',
              movement: rnd() < 0.35 ? 'moving' : 'still',
              range: killedBy === 'awp' ? 'long' : pick(['close', 'mid', 'mid', 'long']),
              support: rnd() < 0.55 ? 'alone' : 'with_teammate',
              opening: rnd() < 0.3 ? 'first_death' : 'later',
              utilityFirst: rnd() < 0.6 ? 'none' : 'own',
              action: pick(['peeking', 'holding', 'crossing']),
              numbers: pick(['ahead', 'even', 'behind']),
              headshot: rnd() < 0.55 ? 'head' : 'body',
            },
            evidence: 'death panel',
          },
        ],
        { killerWeapon, killerSpot: killedBy === 'awp' ? demo.heldFrom : demo.route[(n + 3) % demo.route.length], location: area },
      )
    }
    // The round ends.
    const winner: Side = won ? side : side === 'CT' ? 'T' : 'CT'
    score[winner]++
    const bomb = winner === 'T' && rnd() < 0.6 ? 'exploded' : side === 'T' && !won && rnd() < 0.4 ? 'defused' : undefined
    wins[String(n)] = winner === 'CT' ? (bomb === 'defused' ? 'ct_win_defuse' : rnd() < 0.2 ? 'ct_win_time' : 'ct_win_elimination') : bomb === 'exploded' ? 't_win_bomb' : 't_win_elimination'
    truth.rounds++
    if (won) truth.won++
    if (won) stats.mvps += kills >= 2 ? 1 : 0
    // Money: the win reward or the loss bonus, and kill rewards.
    money = Math.min(16000, money + 300 * kills + (won ? (bomb ? 3500 : 3250) : LOSS[Math.min(4, losses)]))
    losses = won ? Math.max(0, losses - 1) : Math.min(4, losses + 1)
    lastLost = !won
    // Surviving a won round keeps the gun.
    if (!died && (buy === 'full' || buy === 'force')) carry = hand()
    send(tEnd, { map: 'live', round: 'over', win: winner, ...(bomb ? { bomb } : {}) }, died ? mate(side) : me(side, { health: 100 - Math.round(rnd() * 60), armor: 50, money, round_kills: rk, round_killhs: hs, round_totaldmg: dmg, equip_value: equip }, hand()))
    // Where the player went this round, as the screen showed it.
    for (let x = tLive; x < (died ? tLive + diedAt! : tEnd); x += interval) {
      const area = demo.route[(Math.floor((x - tLive) / 6000) + n) % demo.route.length]
      b.batch(x, {}, [], { location: area, mapName: demo.name, side, ...(Math.floor(x / interval) % 5 === 2 ? { enemyPositions: `1 ${demo.heldFrom}` } : {}) })
    }
    t = tEnd + OVER
    if (n === HALF) t += 6000
  }
  send(t, { map: 'gameover', round: 'over' }, me(n <= HALF ? 'CT' : 'T', { health: 100, armor: 0, money, round_kills: 0, round_killhs: 0, equip_value: 0 }, []))
  return {
    profileId: 'cs2',
    batches: b.batches,
    gameState: [...gsi].sort((x, y) => x.at - y.at),
    truth: { ...truth, score: { ...score }, side: n <= HALF ? 'CT' : 'T' },
    endsAt: t + 2000,
  }
}
