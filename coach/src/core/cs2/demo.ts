// Counter-Strike 2 demo review. A match demo (.dem, the game's own recording)
// is read on the player's PC; this module turns what it holds (every kill,
// damage, flash, grenade and round, with positions) into a review for one
// player: their rounds, opening duels, trades, damage, utility, where they
// died, and the few things most worth working on.
//
// A demo has everything the game knew, including both teams' positions, so
// it is read only after the match, for review.

/** What the main process extracts from a demo (compact; positions in world units). */
export interface DemoData {
  readonly map: string
  readonly server: string | null
  readonly tickRate: number
  readonly players: readonly { readonly steamid: string; readonly name: string }[]
  /** Rounds in order: the tick each was decided, the winner (T or CT) and why. */
  readonly rounds: readonly { readonly endTick: number; readonly winner: 'T' | 'CT' | null; readonly reason: string | null }[]
  readonly deaths: readonly DemoDeath[]
  readonly hurts: readonly { readonly tick: number; readonly attacker: string | null; readonly victim: string; readonly dmg: number; readonly weapon: string }[]
  readonly blinds: readonly { readonly tick: number; readonly attacker: string | null; readonly victim: string; readonly seconds: number }[]
  readonly nades: readonly { readonly tick: number; readonly type: 'smoke' | 'flash' | 'he' | 'fire'; readonly thrower: string | null; readonly x: number; readonly y: number; readonly z: number }[]
  /** At each round's start (freeze time over): each player's side, equipment value and money. */
  readonly economy: readonly { readonly round: number; readonly steamid: string; readonly team: 2 | 3; readonly equip: number; readonly balance: number }[]
}

export interface DemoDeath {
  readonly tick: number
  readonly attacker: string | null
  readonly victim: string
  readonly assister: string | null
  readonly weapon: string
  readonly headshot: boolean
  readonly flashAssist: boolean
  readonly victimPos: readonly [number, number, number]
  readonly attackerPos: readonly [number, number, number] | null
  readonly victimPlace: string | null
  readonly attackerPlace: string | null
}

/** Within this long after a death, killing the killer is a trade. */
const TRADE_SEC = 5
/** Blinds shorter than this barely matter. */
const BLIND_MIN_SEC = 1

export interface ReviewRound {
  readonly n: number
  readonly side: 'T' | 'CT' | null
  readonly won: boolean | null
  readonly kills: number
  readonly died: boolean
  readonly assists: number
  readonly damage: number
  /** Who died first in the round: you killed them ("won"), you were it ("lost"), or neither. */
  readonly opening: 'won' | 'lost' | null
  /** Your death avenged by a teammate within 5 s. */
  readonly traded: boolean
  readonly equip: number | null
  /** Your team's average equipment that round. */
  readonly teamEquip: number | null
  readonly nades: number
  readonly deathPlace: string | null
  readonly killedBy: string | null
}

export interface DemoReview {
  readonly map: string
  readonly player: { readonly steamid: string; readonly name: string }
  readonly rounds: readonly ReviewRound[]
  readonly totals: {
    readonly kills: number
    readonly deaths: number
    readonly assists: number
    readonly adr: number
    /** Rounds with a kill, assist, survival or traded death, of all rounds. */
    readonly kast: number
    readonly headshotPct: number
    readonly openingWon: number
    readonly openingLost: number
    readonly tradeKills: number
    readonly deathsTraded: number
    readonly enemiesFlashed: number
    readonly flashSeconds: number
    readonly teammatesFlashed: number
    readonly nades: Readonly<Record<'smoke' | 'flash' | 'he' | 'fire', number>>
  }
  /** Where you died, most first. */
  readonly deathPlaces: readonly { readonly place: string; readonly deaths: number; readonly first: number }[]
  readonly weapons: readonly { readonly weapon: string; readonly kills: number }[]
  /** Your kills and deaths with positions, for the map. */
  readonly marks: readonly { readonly kind: 'kill' | 'death'; readonly round: number; readonly x: number; readonly y: number; readonly z: number; readonly place: string | null; readonly weapon: string }[]
  readonly findings: readonly string[]
}

const pct = (a: number, b: number) => (b ? Math.round((a / b) * 100) : 0)
const WEAPONS: Record<string, string> = {
  ak47: 'AK-47', m4a1: 'M4A4', m4a1_silencer: 'M4A1-S', m4a1_silencer_off: 'M4A1-S', awp: 'AWP', deagle: 'Desert Eagle', usp_silencer: 'USP-S', usp_silencer_off: 'USP-S', hkp2000: 'P2000', glock: 'Glock-18',
  p250: 'P250', fiveseven: 'Five-SeveN', tec9: 'Tec-9', cz75a: 'CZ75-Auto', elite: 'Dual Berettas', revolver: 'R8 Revolver', famas: 'FAMAS', galilar: 'Galil AR', mp9: 'MP9', mac10: 'MAC-10',
  mp7: 'MP7', mp5sd: 'MP5-SD', ump45: 'UMP-45', p90: 'P90', bizon: 'PP-Bizon', ssg08: 'SSG 08', aug: 'AUG', sg556: 'SG 553', scar20: 'SCAR-20', g3sg1: 'G3SG1', nova: 'Nova', xm1014: 'XM1014',
  mag7: 'MAG-7', sawedoff: 'Sawed-Off', negev: 'Negev', m249: 'M249', taser: 'Zeus x27', hegrenade: 'HE grenade', inferno: 'fire', molotov: 'fire', incgrenade: 'fire', flashbang: 'flashbang',
  smokegrenade: 'smoke', decoy: 'decoy', planted_c4: 'the bomb', world: 'the world',
}
const weaponName = (w: string) => {
  const k = w.replace(/^weapon_/, '')
  return /^knife|^bayonet/.test(k) ? 'knife' : (WEAPONS[k] ?? k)
}

/** Which round a tick belongs to (1-based): the first round decided at or after it. */
function roundOf(rounds: DemoData['rounds'], tick: number): number {
  for (let i = 0; i < rounds.length; i++) if (tick <= rounds[i].endTick) return i + 1
  return rounds.length + 1
}

export function reviewDemo(d: DemoData, steamid: string): DemoReview | null {
  const me = d.players.find((p) => p.steamid === steamid)
  if (!me || !d.rounds.length) return null
  const nRounds = d.rounds.length
  const teamOf = new Map<string, 2 | 3>()
  for (const e of d.economy) teamOf.set(`${e.round}:${e.steamid}`, e.team)
  const side = (r: number, id: string): 2 | 3 | null => teamOf.get(`${r}:${id}`) ?? null
  const rounds: ReviewRound[] = []
  const marks: DemoReview['marks'][number][] = []
  let tradeKills = 0
  let deathsTraded = 0
  let enemiesFlashed = 0
  let flashSeconds = 0
  let teammatesFlashed = 0
  const deathPlaces = new Map<string, { deaths: number; first: number }>()
  const weapons = new Map<string, number>()
  let headshots = 0
  const nades = { smoke: 0, flash: 0, he: 0, fire: 0 }
  const tr = d.tickRate || 64

  for (let n = 1; n <= nRounds; n++) {
    const inRound = <T extends { tick: number }>(xs: readonly T[]) => xs.filter((x) => roundOf(d.rounds, x.tick) === n)
    const deaths = inRound(d.deaths).sort((a, b) => a.tick - b.tick)
    const myTeam = side(n, steamid)
    const won = d.rounds[n - 1].winner && myTeam ? (d.rounds[n - 1].winner === 'T') === (myTeam === 2) : null
    const kills = deaths.filter((x) => x.attacker === steamid && x.victim !== steamid && (!myTeam || side(n, x.victim) !== myTeam))
    const myDeath = deaths.find((x) => x.victim === steamid) ?? null
    const assists = deaths.filter((x) => x.assister === steamid && x.attacker !== steamid).length
    // Damage dealt to enemies, at most 100 per enemy per round.
    const dmgBy = new Map<string, number>()
    for (const h of inRound(d.hurts)) if (h.attacker === steamid && h.victim !== steamid && (!myTeam || side(n, h.victim) !== myTeam)) dmgBy.set(h.victim, Math.min(100, (dmgBy.get(h.victim) ?? 0) + h.dmg))
    const damage = [...dmgBy.values()].reduce((a, b) => a + b, 0)
    const first = deaths[0]
    const opening = first ? (first.victim === steamid ? 'lost' : first.attacker === steamid ? 'won' : null) : null
    // Trades: your death avenged; your kill avenging a teammate.
    const traded = !!myDeath && !!myDeath.attacker && deaths.some((x) => x.victim === myDeath.attacker && x.tick > myDeath.tick && x.tick - myDeath.tick <= TRADE_SEC * tr && x.attacker !== steamid)
    if (traded) deathsTraded++
    for (const k of kills) if (deaths.some((x) => x.attacker === k.victim && x.victim !== steamid && myTeam && side(n, x.victim) === myTeam && k.tick > x.tick && k.tick - x.tick <= TRADE_SEC * tr)) tradeKills++
    for (const k of kills) {
      weapons.set(weaponName(k.weapon), (weapons.get(weaponName(k.weapon)) ?? 0) + 1)
      if (k.headshot) headshots++
      const p = k.victimPos
      marks.push({ kind: 'kill', round: n, x: p[0], y: p[1], z: p[2], place: k.victimPlace, weapon: weaponName(k.weapon) })
    }
    if (myDeath) {
      const place = myDeath.victimPlace ?? 'unknown place'
      const dp = deathPlaces.get(place) ?? { deaths: 0, first: 0 }
      dp.deaths++
      if (opening === 'lost') dp.first++
      deathPlaces.set(place, dp)
      marks.push({ kind: 'death', round: n, x: myDeath.victimPos[0], y: myDeath.victimPos[1], z: myDeath.victimPos[2], place: myDeath.victimPlace, weapon: weaponName(myDeath.weapon) })
    }
    for (const b of inRound(d.blinds)) {
      if (b.attacker !== steamid || b.victim === steamid || b.seconds < BLIND_MIN_SEC) continue
      if (myTeam && side(n, b.victim) === myTeam) teammatesFlashed++
      else {
        enemiesFlashed++
        flashSeconds += b.seconds
      }
    }
    const myNades = inRound(d.nades).filter((x) => x.thrower === steamid)
    for (const g of myNades) nades[g.type]++
    const eco = d.economy.filter((e) => e.round === n)
    const mine = eco.find((e) => e.steamid === steamid)
    const mates = myTeam ? eco.filter((e) => e.team === myTeam) : []
    rounds.push({
      n,
      side: myTeam === 2 ? 'T' : myTeam === 3 ? 'CT' : null,
      won,
      kills: kills.length,
      died: !!myDeath,
      assists,
      damage,
      opening,
      traded,
      equip: mine?.equip ?? null,
      teamEquip: mates.length ? Math.round(mates.reduce((a, e) => a + e.equip, 0) / mates.length) : null,
      nades: myNades.length,
      deathPlace: myDeath?.victimPlace ?? null,
      killedBy: myDeath ? weaponName(myDeath.weapon) : null,
    })
  }

  const k = rounds.reduce((a, r) => a + r.kills, 0)
  const dth = rounds.filter((r) => r.died).length
  const totals = {
    kills: k,
    deaths: dth,
    assists: rounds.reduce((a, r) => a + r.assists, 0),
    adr: Math.round(rounds.reduce((a, r) => a + r.damage, 0) / nRounds),
    kast: pct(rounds.filter((r) => r.kills || r.assists || !r.died || r.traded).length, nRounds),
    headshotPct: pct(headshots, k),
    openingWon: rounds.filter((r) => r.opening === 'won').length,
    openingLost: rounds.filter((r) => r.opening === 'lost').length,
    tradeKills,
    deathsTraded,
    enemiesFlashed,
    flashSeconds: Math.round(flashSeconds),
    teammatesFlashed,
    nades,
  }
  const places = [...deathPlaces].map(([place, v]) => ({ place, ...v })).sort((a, b) => b.deaths - a.deaths)
  return {
    map: d.map,
    player: me,
    rounds,
    totals,
    deathPlaces: places,
    weapons: [...weapons].map(([weapon, kills]) => ({ weapon, kills })).sort((a, b) => b.kills - a.kills),
    marks,
    findings: findings(rounds, totals, places),
  }
}

/** The few things most worth working on, each with its numbers. */
function findings(rounds: readonly ReviewRound[], t: DemoReview['totals'], places: DemoReview['deathPlaces']): string[] {
  const out: { score: number; text: string }[] = []
  const n = rounds.length
  const openings = t.openingWon + t.openingLost
  if (t.openingLost >= 3 && t.openingLost > t.openingWon) {
    const where = places.filter((p) => p.first >= 2).slice(0, 2)
    out.push({ score: 50 + t.openingLost * 5, text: `You died first in ${t.openingLost} of ${n} rounds (won ${t.openingWon} of ${openings} opening duels)${where.length ? `, ${where.map((p) => `${p.first} times at ${p.place}`).join(' and ')}` : ''}. Peek with a teammate ready to trade, or let them take first contact.` })
  }
  if (t.deaths >= 5 && t.deathsTraded / t.deaths < 0.3) out.push({ score: 40 + (t.deaths - t.deathsTraded) * 2, text: `Only ${t.deathsTraded} of your ${t.deaths} deaths were traded. Play closer to a teammate so a death costs the other side one too.` })
  const top = places[0]
  if (top && top.deaths >= 4 && top.deaths >= t.deaths * 0.3) out.push({ score: 35 + top.deaths * 3, text: `${top.deaths} of your ${t.deaths} deaths were at ${top.place}. Change how you take or hold it: a different angle, utility first, or a later timing.` })
  if (t.adr < 65 && n >= 8) out.push({ score: 30 + (65 - t.adr), text: `${t.adr} damage a round. Above 80 is a strong game; trade damage and finish fights you start.` })
  const thrown = t.nades.smoke + t.nades.flash + t.nades.he + t.nades.fire
  if (n >= 8 && thrown / n < 1) out.push({ score: 30, text: `${thrown} grenades in ${n} rounds. Most rounds you can buy and use at least one; utility wins space without risking a duel.` })
  if (t.teammatesFlashed >= 3 && t.teammatesFlashed >= t.enemiesFlashed / 2) out.push({ score: 25 + t.teammatesFlashed * 2, text: `Your flashes blinded ${t.teammatesFlashed} teammates and ${t.enemiesFlashed} enemies. Call your flashes, and throw them over or behind your team.` })
  const saveMismatch = rounds.filter((r) => r.equip !== null && r.teamEquip !== null && r.teamEquip >= 3500 && r.equip < r.teamEquip / 2).length
  if (saveMismatch >= 2) out.push({ score: 20 + saveMismatch * 3, text: `In ${saveMismatch} rounds your team bought and you did not (your equipment under half theirs). Buy with your team, or drop for each other.` })
  const strengths: string[] = []
  if (t.openingWon >= 3 && t.openingWon > t.openingLost) strengths.push(`won ${t.openingWon} of ${openings} opening duels`)
  if (t.kast >= 75) strengths.push(`a kill, assist, survival or trade in ${t.kast}% of rounds`)
  if (t.adr >= 85) strengths.push(`${t.adr} damage a round`)
  const lines = out.sort((a, b) => b.score - a.score).slice(0, 3).map((x) => x.text)
  if (strengths.length) lines.push(`Good: ${strengths.join('; ')}.`)
  return lines
}

/** Where a world position sits on the map's overview (0..1 each way), from the game's radar placement. */
export function onOverview(radar: { readonly posX: number; readonly posY: number; readonly scale: number; readonly imageSize?: number }, x: number, y: number): [number, number] {
  const size = radar.imageSize ?? 1024
  return [(x - radar.posX) / radar.scale / size, (radar.posY - y) / radar.scale / size]
}

export function isDemoData(v: unknown): v is DemoData {
  const o = v as DemoData | null
  return !!o && typeof o.map === 'string' && Array.isArray(o.players) && Array.isArray(o.rounds) && Array.isArray(o.deaths) && Array.isArray(o.hurts) && Array.isArray(o.economy)
}
