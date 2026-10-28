// Counter-Strike 2 Game State Integration: the game's own data feed, which it
// posts to a local address named in a cfg file. While the player plays, it
// carries only their own state (health, armour, money, weapons, flash, kills
// this round, match stats) and the round and map: no positions, no other
// players, nothing hidden. Each update is merged into the last (updates can
// be partial), and turned into observations the engine already understands
// (the map, side, score, money, health, the weapon in hand, the phase) and
// events: the player's kills (with headshots) and deaths (with whether they
// were flashed or reloading), and the bomb. Round by round it keeps the
// player's economy: money at the start, what they spent, the buy it made, kills,
// damage, whether and when they died with grenades unused, and the result; and
// their grenades: what they carried, what they threw (a grenade that left
// their hands while alive), what they still held at death, and how often they
// were flashed blind or caught fire.

import type { ArsenalItem, GameMap } from '../profile/schema.ts'
import type { Observation } from '../types.ts'

export type Side = 'CT' | 'T'
export type BuyType = 'pistol' | 'eco' | 'force' | 'full'
/** Grenade kinds as the coach counts them (fire = a molotov or an incendiary). */
export const GRENADE_KINDS = ['smoke', 'flash', 'he', 'fire', 'decoy'] as const
export type GrenadeKind = (typeof GRENADE_KINDS)[number]
export type GrenadeCounts = Readonly<Partial<Record<GrenadeKind, number>>>
const NADE_KIND: Readonly<Record<string, GrenadeKind>> = {
  weapon_smokegrenade: 'smoke',
  weapon_flashbang: 'flash',
  weapon_hegrenade: 'he',
  weapon_molotov: 'fire',
  weapon_incgrenade: 'fire',
  weapon_decoy: 'decoy',
}
export const countNades = (c: GrenadeCounts | null | undefined): number => (c ? GRENADE_KINDS.reduce((n, k) => n + (c[k] ?? 0), 0) : 0)

export interface Cs2Round {
  /** 1-based round of the match. */
  readonly n: number
  readonly side: Side | null
  /** Money when freeze time began, before buying. */
  readonly startMoney: number | null
  /** Spent during freeze time. */
  readonly spent: number | null
  /** Equipment value when the round went live. */
  readonly equip: number | null
  readonly buy: BuyType | null
  readonly kills: number
  readonly headshots: number
  /** Damage dealt this round (as the game counts it). */
  readonly damage: number | null
  readonly died: boolean
  /** Seconds after the round went live. */
  readonly diedAt: number | null
  readonly blindAtDeath: boolean | null
  /** Grenades still held when the player died. */
  readonly utilityAtDeath: number | null
  /** Grenades carried this round (the most held at once while alive, so after buying). */
  readonly nades?: GrenadeCounts | null
  /** Grenades that left the player's hands while alive in the round (thrown; a dropped one counts too). */
  readonly thrown?: GrenadeCounts
  /** Grenades held at death, by kind. */
  readonly nadesAtDeath?: GrenadeCounts | null
  /** Times flashed blind (white-out at BLIND_AT or more) while alive, and the seconds spent blind. */
  readonly flashed?: number
  readonly blindSeconds?: number
  /** Times the player caught fire. */
  readonly burned?: number
  readonly result: 'won' | 'lost' | null
  /** How the round ended (elimination, bomb, defuse, time). */
  readonly reason: string | null
  readonly bomb: 'planted' | 'exploded' | 'defused' | null
}

export interface Cs2Rounds {
  readonly version: 1
  readonly map: string | null
  readonly mode: string | null
  readonly rounds: readonly Cs2Round[]
  /** Rounds won: the player's team, the other. */
  readonly score: { readonly ours: number; readonly theirs: number } | null
}

export interface GsiStatus {
  /** An update came in the last 15 seconds. */
  readonly connected: boolean
  readonly lastAt: number | null
  /** The player's own state, or someone else's (spectating after death, a demo). */
  readonly watching: 'self' | 'other' | null
  readonly map: string | null
  readonly mode: string | null
  readonly phase: string | null
  readonly round: number | null
  readonly side: Side | null
  readonly money: number | null
  readonly score: { readonly ours: number; readonly theirs: number } | null
  /** Grenades held now (the player's own state only). */
  readonly nades?: GrenadeCounts | null
}

/** Full buy: a rifle with armour and helmet (T: AK-47 $2,700 + $1,000; CT: M4 $2,900 + $1,000), with the starting pistol. */
export const FULL_BUY = { T: 3600, CT: 3800 } as const
/** Below this the player saved (a pistol upgrade, armour or a grenade at most). */
export const ECO_BELOW = 1300
/** Flash white-out (0 to 255) from which the player counts as blind. */
export const BLIND_AT = 120

type Obj = Record<string, unknown>
const obj = (v: unknown): Obj | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null)
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : null)
const str = (v: unknown): string | null => (typeof v === 'string' && v.length <= 200 ? v : null)

/** Deep merge of an update into the state (an update may carry only what changed). */
function merge(into: Obj, from: Obj, depth = 0): Obj {
  if (depth > 6) return into
  for (const [k, v] of Object.entries(from)) {
    if (k === 'previously' || k === 'added' || k === 'auth') continue
    // The weapon list comes whole: a grenade thrown or a gun dropped is simply missing from it.
    if (k === 'weapons') {
      into[k] = v
      continue
    }
    const o = obj(v)
    const cur = obj(into[k])
    into[k] = o && cur ? merge({ ...cur }, o, depth + 1) : v
  }
  return into
}

const MODE_NAMES: Readonly<Record<string, string>> = {
  competitive: 'Competitive',
  premier: 'Premier',
  scrimcomp2v2: 'Wingman',
  casual: 'Casual',
  deathmatch: 'Deathmatch',
  gungameprogressive: 'Arms Race',
  retakes: 'Retakes',
  rush: 'Rush',
}

const ROUND_REASON: Readonly<Record<string, string>> = {
  ct_win_elimination: 'elimination',
  t_win_elimination: 'elimination',
  ct_win_defuse: 'bomb defused',
  t_win_bomb: 'bomb exploded',
  ct_win_time: 'time ran out',
  ct_win_rescue: 'hostages rescued',
}

const key = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '')

export class Cs2Gsi {
  private readonly profileId: string
  private readonly mapNames: Map<string, string>
  private readonly weaponNames: Map<string, string>
  private state: Obj = {}
  private seq = 0
  private lastAt: number | null = null
  // The player's own last state, and when the round went live.
  private self: { health: number; kills: number; hs: number; flashed: number; burning: number; reloading: boolean; grenades: number; nades: GrenadeCounts; t: number } | null = null
  private liveAt: number | null = null
  private roundsList: Cs2Round[] = []
  private cur: (Omit<Cs2Round, 'n'> & { n: number }) | null = null
  private halfSide: Side | null = null
  private lastPhase: string | null = null

  constructor(opts: { readonly profileId: string; readonly maps?: readonly GameMap[]; readonly arsenal?: readonly ArsenalItem[] }) {
    this.profileId = opts.profileId
    this.mapNames = new Map()
    for (const m of opts.maps ?? []) for (const n of [m.id, m.name, ...(m.aliases ?? [])]) this.mapNames.set(key(n), m.name)
    this.weaponNames = new Map()
    for (const a of opts.arsenal ?? []) for (const n of [a.id, a.name, ...(a.aliases ?? [])]) this.weaponNames.set(key(n), a.name)
  }

  /** The game's map id (de_dust2) as the pack names it (Dust II). */
  mapName(id: string): string {
    const bare = id.replace(/^(de|cs|ar|dz)_/, '')
    return this.mapNames.get(key(bare)) ?? this.mapNames.get(key(id)) ?? bare.replace(/(^|_)([a-z])/g, (_, s: string, c: string) => (s ? ' ' : '') + c.toUpperCase())
  }

  /** The game's weapon id (weapon_m4a1_silencer) as the pack names it (M4A1-S). */
  weaponName(id: string): string {
    const bare = id.replace(/^weapon_/, '')
    const alias: Record<string, string> = { m4a1_silencer: 'm4a1s', usp_silencer: 'usps', hkp2000: 'p2000', cz75a: 'cz75auto', deagle: 'deserteagle', revolver: 'r8revolver', mp5sd: 'mp5sd', taser: 'zeusx27', incgrenade: 'incendiarygrenade', molotov: 'molotov', hegrenade: 'hegrenade', smokegrenade: 'smokegrenade', flashbang: 'flashbang', decoy: 'decoygrenade' }
    const k = key(alias[bare] ?? bare)
    return this.weaponNames.get(k) ?? this.weaponNames.get(key(bare)) ?? bare.toUpperCase()
  }

  /** One update from the game, received at `now`. */
  payload(body: unknown, now: number): Observation[] {
    const b = obj(body)
    if (!b) return []
    this.state = merge({ ...this.state }, b)
    this.lastAt = now
    const out: Observation[] = []
    const s = this.state
    const provider = obj(s.provider)
    const map = obj(s.map)
    const round = obj(s.round)
    const player = obj(s.player)
    const pstate = obj(player?.state)
    const me = str(provider?.steamid)
    const own = !!player && !!me && str(player.steamid) === me
    const side = own ? (str(player!.team) === 'CT' ? 'CT' : str(player!.team) === 'T' ? 'T' : null) : (this.cur?.side ?? null)

    const state = (k: string, value: string | number | boolean | null, field: string) => {
      if (value === null) return
      out.push(this.obs('state', k, now, { value, evidence: [field] }))
    }
    const event = (type: string, attributes: Record<string, string>, field: string) => out.push(this.obs('event', type, now, { attributes, evidence: [field] }))

    // The map, mode and score.
    const mapId = str(map?.name)
    if (mapId) state('mapName', this.mapName(mapId), 'map.name')
    const mode = str(map?.mode)
    if (mode) state('modeName', MODE_NAMES[mode] ?? mode, 'map.mode')
    const ct = num(obj(map?.team_ct)?.score)
    const t = num(obj(map?.team_t)?.score)
    if (side && ct !== null && t !== null) {
      state('playerScore', side === 'CT' ? ct : t, 'map.team_*.score')
      state('opponentScore', side === 'CT' ? t : ct, 'map.team_*.score')
    }
    if (own && side) state('side', side, 'player.team')

    // The phase, in the engine's terms, and how the round went.
    const mphase = str(map?.phase)
    const rphase = str(round?.phase)
    const phase = mphase === 'gameover' ? 'match_end' : mphase === 'intermission' ? 'round_end' : mphase === 'live' ? (rphase === 'freezetime' ? 'round' : rphase === 'live' ? 'combat' : rphase === 'over' ? 'round_end' : null) : mphase === 'warmup' ? 'menu' : null
    if (phase) state('phase', phase, 'map.phase / round.phase')
    const win = str(round?.win_team)
    if (phase === 'round_end' && side && (win === 'CT' || win === 'T')) state('visibleOutcome', win === side ? 'success' : 'failure', 'round.win_team')
    if (phase === 'match_end' && side && ct !== null && t !== null) {
      const ours = side === 'CT' ? ct : t
      const theirs = side === 'CT' ? t : ct
      state('visibleOutcome', ours > theirs ? 'success' : ours < theirs ? 'failure' : 'unknown', 'map.team_*.score')
    }

    // The player's own state.
    if (own && pstate) {
      const money = num(pstate.money)
      const health = num(pstate.health)
      state('money', money, 'player.state.money')
      state('health', health, 'player.state.health')
      const weapons = Object.values(obj(player!.weapons) ?? {}).map(obj).filter((w): w is Obj => !!w)
      const active = weapons.find((w) => str(w.state) === 'active' || str(w.state) === 'reloading')
      const name = str(active?.name)
      if (name && str(active?.type) !== 'Knife' && str(active?.type) !== 'C4') state('ownWeapon', this.weaponName(name), 'player.weapons')
      const grenades = weapons.filter((w) => str(w.type) === 'Grenade').reduce((n, w) => n + Math.max(1, num(w.ammo_reserve) ?? 1), 0)
      const nades: Partial<Record<GrenadeKind, number>> = {}
      for (const w of weapons) {
        const kind = NADE_KIND[str(w.name) ?? '']
        if (kind) nades[kind] = (nades[kind] ?? 0) + Math.max(1, num(w.ammo_reserve) ?? 1)
      }
      const kills = num(pstate.round_kills) ?? 0
      const hs = num(pstate.round_killhs) ?? 0
      const flashed = num(pstate.flashed) ?? 0
      const burning = num(pstate.burning) ?? 0
      const reloading = str(active?.state) === 'reloading'
      const prev = this.self
      this.utility(prev, nades, health, flashed, burning, rphase, now)
      // Kills this round going up: each one is a kill (round_kills resets to 0 each round).
      if (prev && kills > prev.kills && kills - prev.kills <= 5) {
        const newHs = Math.max(0, hs - prev.hs)
        for (let i = 0; i < kills - prev.kills; i++) event('kill', { headshot: i < newHs ? 'head' : 'body' }, 'player.state.round_kills')
      }
      // Health falling to zero: the player died (once).
      if (prev && prev.health > 0 && health === 0) {
        const blind = Math.max(prev.flashed, flashed) >= BLIND_AT
        event('death', { blind: blind ? 'blind' : 'clear', ...(prev.reloading || reloading ? { action: 'reloading' } : {}) }, 'player.state.health')
        if (this.cur && !this.cur.died) {
          this.cur = { ...this.cur, died: true, diedAt: this.liveAt !== null ? Math.round((now - this.liveAt) / 100) / 10 : null, blindAtDeath: blind, utilityAtDeath: prev.grenades, nadesAtDeath: prev.nades }
        }
      }
      this.self = { health: health ?? prev?.health ?? 100, kills, hs, flashed, burning, reloading, grenades, nades, t: now }
      // Match stats as a fallback count.
      const ms = obj(player!.match_stats)
      const mk = num(ms?.kills)
      const md = num(ms?.deaths)
      if (mk !== null && md !== null) {
        state('kills', mk, 'player.match_stats.kills')
        state('deaths', md, 'player.match_stats.deaths')
      }
    }

    // Watching a teammate mid-round straight after being alive: the player died and the update
    // with their health at zero was not seen.
    if (!own && player && this.self && this.self.health > 0 && mphase === 'live' && rphase === 'live') {
      const blind = this.self.flashed >= BLIND_AT
      event('death', { blind: blind ? 'blind' : 'clear', ...(this.self.reloading ? { action: 'reloading' } : {}) }, 'player (now spectating)')
      if (this.cur && !this.cur.died) this.cur = { ...this.cur, died: true, diedAt: this.liveAt !== null ? Math.round((now - this.liveAt) / 100) / 10 : null, blindAtDeath: blind, utilityAtDeath: this.self.grenades, nadesAtDeath: this.self.nades }
      this.self = { ...this.self, health: 0 }
    }

    // The bomb.
    const bomb = str(round?.bomb)
    if (bomb && this.cur && bomb !== this.cur.bomb && (bomb === 'planted' || bomb === 'exploded' || bomb === 'defused')) {
      if (bomb === 'planted') event('bomb_planted', {}, 'round.bomb')
      if (bomb === 'defused') event('bomb_defused', {}, 'round.bomb')
      this.cur = { ...this.cur, bomb }
    }

    this.rounds(map, round, own ? pstate : null, side, now)
    return out
  }

  /**
   * Grenades while alive: the most carried at once, a count falling as a throw (only once the round
   * is live: in freeze time a fall is a sale), and flashes and fire as they start.
   */
  private utility(prev: Cs2Gsi['self'], nades: GrenadeCounts, health: number | null, flashed: number, burning: number, rphase: string | null, now: number): void {
    const c = this.cur
    if (!c || c.died || c.result !== null || (health ?? 0) <= 0) return
    const carried: Partial<Record<GrenadeKind, number>> = { ...(c.nades ?? {}) }
    for (const k of GRENADE_KINDS) if ((nades[k] ?? 0) > (carried[k] ?? 0)) carried[k] = nades[k]
    let thrown = c.thrown ?? {}
    let { flashed: times = 0, blindSeconds = 0, burned = 0 } = c
    const live = rphase === 'live' || rphase === 'over'
    if (prev && prev.health > 0 && live) {
      const t: Partial<Record<GrenadeKind, number>> = { ...thrown }
      for (const k of GRENADE_KINDS) {
        const drop = (prev.nades[k] ?? 0) - (nades[k] ?? 0)
        if (drop > 0 && drop <= 2) t[k] = (t[k] ?? 0) + drop
      }
      thrown = t
      if (flashed >= BLIND_AT && prev.flashed < BLIND_AT) times++
      if (prev.flashed >= BLIND_AT) blindSeconds += Math.min(1, Math.max(0, now - prev.t) / 1000)
      if (burning > 0 && prev.burning <= 0) burned++
    }
    this.cur = { ...c, nades: carried, thrown, flashed: times, blindSeconds: Math.round(blindSeconds * 10) / 10, burned }
  }

  private rounds(map: Obj | null, round: Obj | null, pstate: Obj | null, side: Side | null, now: number): void {
    const mphase = str(map?.phase)
    const rphase = str(round?.phase)
    const ct = num(obj(map?.team_ct)?.score) ?? 0
    const t = num(obj(map?.team_t)?.score) ?? 0
    const n = ct + t + 1
    const phase = mphase === 'live' ? rphase : mphase
    const changed = phase !== this.lastPhase
    this.lastPhase = phase
    if (phase === 'freezetime' && (changed || !this.cur || this.cur.n !== n)) {
      // A new round: its money before buying. The first round of a half is a pistol round.
      if (this.cur && this.cur.result === null && this.cur.n !== n) this.close(map, side)
      const firstOfHalf = n === 1 || (side !== null && this.halfSide !== null && side !== this.halfSide)
      if (side) this.halfSide = side
      this.cur = {
        n,
        side,
        startMoney: num(pstate?.money),
        spent: null,
        equip: null,
        buy: firstOfHalf && (num(pstate?.money) ?? 0) <= 1000 ? 'pistol' : null,
        kills: 0,
        headshots: 0,
        damage: null,
        died: false,
        diedAt: null,
        blindAtDeath: null,
        utilityAtDeath: null,
        result: null,
        reason: null,
        bomb: null,
        nades: null,
        thrown: {},
        nadesAtDeath: null,
        flashed: 0,
        blindSeconds: 0,
        burned: 0,
      }
      this.liveAt = null
      if (this.self) this.self = { ...this.self, kills: 0, hs: 0 }
      return
    }
    if (!this.cur) return
    if (pstate) {
      const kills = num(pstate.round_kills)
      const hs = num(pstate.round_killhs)
      const dmg = num(pstate.round_totaldmg)
      this.cur = { ...this.cur, ...(kills !== null ? { kills: Math.max(this.cur.kills, kills) } : {}), ...(hs !== null ? { headshots: Math.max(this.cur.headshots, hs) } : {}), ...(dmg !== null ? { damage: Math.max(this.cur.damage ?? 0, dmg) } : {}) }
    }
    if (phase === 'live' && changed) {
      this.liveAt = now
      const money = num(pstate?.money)
      const equip = num(pstate?.equip_value)
      const s = this.cur.side ?? side
      const buy = this.cur.buy === 'pistol' ? 'pistol' : equip === null || !s ? null : equip >= FULL_BUY[s] ? 'full' : equip < ECO_BELOW ? 'eco' : 'force'
      this.cur = { ...this.cur, equip, buy, spent: this.cur.startMoney !== null && money !== null ? Math.max(0, this.cur.startMoney - money) : null }
    }
    if ((phase === 'over' || phase === 'gameover' || phase === 'intermission') && this.cur.result === null) this.close(map, side)
  }

  private close(map: Obj | null, side: Side | null): void {
    if (!this.cur) return
    const wins = obj(map?.round_wins)
    const why = str(wins?.[String(this.cur.n)])
    const s = this.cur.side ?? side
    const winner = why?.startsWith('ct_') ? 'CT' : why?.startsWith('t_') ? 'T' : str(obj(this.state.round)?.win_team)
    const result = s && (winner === 'CT' || winner === 'T') ? (winner === s ? 'won' : 'lost') : null
    const done: Cs2Round = { ...this.cur, result, reason: why ? (ROUND_REASON[why] ?? why) : null }
    this.roundsList = [...this.roundsList.filter((r) => r.n !== done.n), done].sort((a, b) => a.n - b.n)
    this.cur = { ...done }
  }

  /** The rounds of this match so far (the one being played included). */
  snapshot(): Cs2Rounds | null {
    const list = [...this.roundsList]
    if (this.cur && this.cur.result === null && !list.some((r) => r.n === this.cur!.n)) list.push(this.cur)
    if (!list.length) return null
    const map = obj(this.state.map)
    const side = list[list.length - 1].side
    const ct = num(obj(map?.team_ct)?.score)
    const t = num(obj(map?.team_t)?.score)
    return {
      version: 1,
      map: str(map?.name) ? this.mapName(str(map!.name)!) : null,
      mode: str(map?.mode) ? (MODE_NAMES[str(map!.mode)!] ?? str(map!.mode)) : null,
      rounds: list,
      score: side && ct !== null && t !== null ? { ours: side === 'CT' ? ct : t, theirs: side === 'CT' ? t : ct } : null,
    }
  }

  /** The match ended: its rounds, and a clean slate for the next. */
  finish(): Cs2Rounds | null {
    const r = this.snapshot()
    this.roundsList = []
    this.cur = null
    this.halfSide = null
    this.liveAt = null
    this.self = null
    return r
  }

  status(now: number): GsiStatus {
    const s = this.state
    const map = obj(s.map)
    const player = obj(s.player)
    const me = str(obj(s.provider)?.steamid)
    const own = !!player && !!me && str(player.steamid) === me
    const snap = this.snapshot()
    return {
      connected: this.lastAt !== null && now - this.lastAt < 15_000,
      lastAt: this.lastAt,
      watching: player ? (own ? 'self' : 'other') : null,
      map: str(map?.name) ? this.mapName(str(map!.name)!) : null,
      mode: str(map?.mode) ? (MODE_NAMES[str(map!.mode)!] ?? str(map!.mode)) : null,
      phase: str(map?.phase) === 'live' ? str(obj(s.round)?.phase) : str(map?.phase),
      round: this.cur?.n ?? null,
      side: own ? (str(player!.team) === 'CT' ? 'CT' : str(player!.team) === 'T' ? 'T' : null) : (this.cur?.side ?? null),
      money: own ? num(obj(player!.state)?.money) : null,
      score: snap?.score ?? null,
      nades: own && Number(obj(player!.state)?.health ?? 0) > 0 ? (this.self?.nades ?? null) : null,
    }
  }

  private obs(kind: 'state' | 'event', eventType: string, now: number, x: { value?: string | number | boolean; attributes?: Record<string, string>; evidence: string[] }): Observation {
    return {
      id: `gsi-${++this.seq}`,
      timestampMs: now,
      gameProfileId: this.profileId,
      sourceRegion: 'game-state',
      kind,
      eventType,
      ...(x.value !== undefined ? { value: x.value } : {}),
      ...(x.attributes && Object.keys(x.attributes).length ? { attributes: x.attributes } : {}),
      confidence: 0.99,
      freshnessMs: 0,
      evidence: ['Game State Integration', ...x.evidence],
      source: 'game-api',
    }
  }
}

/** Checks stored rounds (renderer input is untrusted on the way to disk). */
export function isCs2Rounds(v: unknown): v is Cs2Rounds {
  const o = obj(v)
  if (!o || o.version !== 1 || !Array.isArray(o.rounds) || o.rounds.length > 80) return false
  const n = (x: unknown) => x === null || (typeof x === 'number' && Number.isFinite(x))
  return (
    (o.map === null || (typeof o.map === 'string' && o.map.length <= 60)) &&
    (o.mode === null || (typeof o.mode === 'string' && o.mode.length <= 40)) &&
    (o.score === null || (!!obj(o.score) && n(obj(o.score)!.ours) && n(obj(o.score)!.theirs))) &&
    o.rounds.every((r) => {
      const x = obj(r)
      return (
        !!x &&
        typeof x.n === 'number' &&
        (x.side === null || x.side === 'CT' || x.side === 'T') &&
        ['startMoney', 'spent', 'equip', 'damage', 'diedAt', 'utilityAtDeath'].every((k) => n(x[k])) &&
        (x.buy === null || ['pistol', 'eco', 'force', 'full'].includes(x.buy as string)) &&
        typeof x.kills === 'number' &&
        typeof x.headshots === 'number' &&
        typeof x.died === 'boolean' &&
        (x.blindAtDeath === null || typeof x.blindAtDeath === 'boolean') &&
        (x.result === null || x.result === 'won' || x.result === 'lost') &&
        (x.reason === null || (typeof x.reason === 'string' && x.reason.length <= 40)) &&
        (x.bomb === null || ['planted', 'exploded', 'defused'].includes(x.bomb as string)) &&
        ['nades', 'nadesAtDeath'].every((k) => x[k] === undefined || x[k] === null || isCounts(x[k])) &&
        (x.thrown === undefined || isCounts(x.thrown)) &&
        ['flashed', 'blindSeconds', 'burned'].every((k) => x[k] === undefined || (typeof x[k] === 'number' && Number.isFinite(x[k]) && (x[k] as number) >= 0 && (x[k] as number) <= 600))
      )
    })
  )
}

function isCounts(v: unknown): boolean {
  const o = obj(v)
  return !!o && Object.entries(o).every(([k, n]) => (GRENADE_KINDS as readonly string[]).includes(k) && typeof n === 'number' && Number.isInteger(n) && n >= 0 && n <= 20)
}
