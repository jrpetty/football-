import type {
  AppData,
  Player,
  Match,
  Drill,
  PlayerMatchStat,
  MatchEvent,
  LineupSlot,
  PositionCode,
  TrainingSession,
  Lineup,
  VideoClip,
} from '../types'
import { FORMATIONS, getFormation, positionGroupOf } from './formations'

export const SCHEMA_VERSION = 1

// ---------------------------------------------------------------------------
// Deterministic pseudo-random number generator (so the demo data is stable).
// ---------------------------------------------------------------------------
function makeRng(seed: number) {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 4294967296
  }
}
type Rng = () => number
const ri = (r: Rng, min: number, max: number) => Math.floor(r() * (max - min + 1)) + min
const rf = (r: Rng, min: number, max: number) => r() * (max - min) + min
const pick = <T>(r: Rng, arr: T[]): T => arr[Math.floor(r() * arr.length)]
const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))
const round1 = (v: number) => Math.round(v * 10) / 10

// ---------------------------------------------------------------------------
// Squad — Riverside Athletic
// ---------------------------------------------------------------------------
const C = {
  red: '#ef4444', orange: '#f97316', amber: '#f59e0b', lime: '#84cc16',
  green: '#22c55e', teal: '#14b8a6', cyan: '#06b6d4', blue: '#3b82f6',
  indigo: '#6366f1', violet: '#8b5cf6', fuchsia: '#d946ef', pink: '#ec4899',
  rose: '#f43f5e', sky: '#0ea5e9', emerald: '#10b981', yellow: '#eab308',
}
const colorList = Object.values(C)

interface RawPlayer {
  name: string; number: number; position: PositionCode; age: number
  h: number; w: number; foot: 'Left' | 'Right' | 'Both'; nat: string
  attrs: [number, number, number, number, number, number] // pace, sho, pas, dri, def, phy
  alt?: PositionCode[]; value: number; joined: number
}

const RAW: RawPlayer[] = [
  { name: 'Marco Devlin', number: 1, position: 'GK', age: 29, h: 191, w: 84, foot: 'Right', nat: 'Ireland', attrs: [58, 32, 64, 50, 78, 80], value: 12, joined: 2019 },
  { name: 'Tom Aldridge', number: 13, position: 'GK', age: 22, h: 188, w: 80, foot: 'Right', nat: 'England', attrs: [62, 28, 58, 46, 70, 74], value: 4, joined: 2022 },
  { name: 'Luís Pereira', number: 2, position: 'RB', age: 26, h: 178, w: 73, foot: 'Right', nat: 'Portugal', attrs: [86, 58, 74, 76, 76, 74], alt: ['RWB', 'RM'], value: 28, joined: 2021 },
  { name: 'Samuel Okoye', number: 4, position: 'CB', age: 28, h: 190, w: 86, foot: 'Right', nat: 'Nigeria', attrs: [70, 44, 66, 58, 86, 88], alt: ['CDM'], value: 32, joined: 2020 },
  { name: 'Henrik Sørensen', number: 5, position: 'CB', age: 31, h: 187, w: 82, foot: 'Left', nat: 'Denmark', attrs: [64, 40, 72, 56, 88, 82], value: 22, joined: 2018 },
  { name: 'Diego Marín', number: 3, position: 'LB', age: 24, h: 176, w: 71, foot: 'Left', nat: 'Spain', attrs: [88, 52, 76, 80, 74, 70], alt: ['LWB', 'LM'], value: 30, joined: 2022 },
  { name: 'Jacob Reuben', number: 15, position: 'CB', age: 21, h: 185, w: 79, foot: 'Right', nat: 'England', attrs: [72, 38, 64, 54, 80, 79], alt: ['RB'], value: 14, joined: 2023 },
  { name: 'Yannick Traoré', number: 6, position: 'CDM', age: 27, h: 183, w: 78, foot: 'Right', nat: 'France', attrs: [74, 60, 80, 74, 84, 84], alt: ['CM', 'CB'], value: 38, joined: 2020 },
  { name: 'Adam Whitfield', number: 8, position: 'CM', age: 25, h: 180, w: 75, foot: 'Right', nat: 'England', attrs: [78, 70, 84, 82, 70, 76], alt: ['CAM'], value: 42, joined: 2021 },
  { name: 'Niko Petrović', number: 10, position: 'CAM', age: 27, h: 175, w: 70, foot: 'Left', nat: 'Croatia', attrs: [80, 82, 88, 90, 56, 66], alt: ['CM', 'LW'], value: 56, joined: 2019 },
  { name: 'Femi Adebayo', number: 14, position: 'CM', age: 23, h: 179, w: 74, foot: 'Right', nat: 'Nigeria', attrs: [82, 66, 80, 80, 72, 78], alt: ['CDM'], value: 26, joined: 2022 },
  { name: 'Eric Lindqvist', number: 18, position: 'RM', age: 24, h: 177, w: 72, foot: 'Right', nat: 'Sweden', attrs: [84, 68, 78, 82, 64, 70], alt: ['RW', 'CAM'], value: 24, joined: 2021 },
  { name: 'Rashid Karim', number: 7, position: 'RW', age: 23, h: 174, w: 68, foot: 'Left', nat: 'Morocco', attrs: [92, 80, 76, 90, 50, 64], alt: ['LW', 'ST'], value: 60, joined: 2022 },
  { name: 'Bruno Costa', number: 11, position: 'LW', age: 26, h: 176, w: 71, foot: 'Right', nat: 'Brazil', attrs: [90, 82, 78, 91, 52, 66], alt: ['RW', 'CAM'], value: 58, joined: 2020 },
  { name: 'Viktor Halász', number: 9, position: 'ST', age: 28, h: 186, w: 83, foot: 'Right', nat: 'Hungary', attrs: [82, 90, 72, 80, 46, 84], alt: ['CF'], value: 64, joined: 2019 },
  { name: 'Caleb Morrow', number: 19, position: 'ST', age: 20, h: 184, w: 78, foot: 'Right', nat: 'USA', attrs: [86, 80, 66, 78, 44, 76], alt: ['CF', 'RW'], value: 30, joined: 2023 },
  { name: 'Idris Cham', number: 22, position: 'LB', age: 22, h: 175, w: 70, foot: 'Left', nat: 'Gambia', attrs: [89, 48, 70, 78, 70, 68], alt: ['LWB', 'LM'], value: 16, joined: 2023 },
  { name: 'George Tindall', number: 16, position: 'CM', age: 30, h: 181, w: 77, foot: 'Right', nat: 'England', attrs: [70, 64, 82, 74, 76, 80], alt: ['CDM', 'CAM'], value: 18, joined: 2017 },
  { name: 'Mateo Rossi', number: 20, position: 'CF', age: 25, h: 182, w: 79, foot: 'Both', nat: 'Italy', attrs: [80, 84, 74, 82, 48, 78], alt: ['ST', 'CAM'], value: 40, joined: 2022 },
  { name: 'Kwame Asante', number: 21, position: 'RW', age: 19, h: 172, w: 66, foot: 'Right', nat: 'Ghana', attrs: [93, 74, 70, 88, 46, 60], alt: ['LW', 'ST'], value: 22, joined: 2024 },
]

function buildPlayers(): Player[] {
  return RAW.map((p, i): Player => {
    const [pace, shooting, passing, dribbling, defending, physical] = p.attrs
    // Most players available; a few flagged for realism, deterministically.
    let status: Player['status'] = 'Available'
    if (i === 6) status = 'Injured'
    else if (i === 16) status = 'Doubtful'
    else if (i === 11) status = 'Suspended'
    return {
      id: `p${i + 1}`,
      name: p.name,
      number: p.number,
      position: p.position,
      positionGroup: positionGroupOf(p.position),
      altPositions: p.alt ?? [],
      age: p.age,
      heightCm: p.h,
      weightKg: p.w,
      foot: p.foot,
      nationality: p.nat,
      status,
      color: colorList[i % colorList.length],
      attributes: { pace, shooting, passing, dribbling, defending, physical },
      notes:
        i === 9
          ? 'Creative hub — give him freedom between the lines. Set-piece taker.'
          : i === 12
            ? 'Electric 1v1. Needs to track back more diligently on transitions.'
            : '',
      marketValueM: p.value,
      joinedYear: p.joined,
    }
  })
}

// ---------------------------------------------------------------------------
// Match generation
// ---------------------------------------------------------------------------
const OPPONENTS = [
  'Northbridge City', 'Kingsford United', 'Harborough Town', 'Westfield Rovers',
  'Ashcombe FC', 'Bellview Wanderers', 'Crowmoor Athletic', 'Dunbarrow City',
  'Eastgate United', 'Fenwick Rangers', 'Granville Town', 'Holloway FC',
]

function chooseStarters(players: Player[], formation: string, r: Rng): LineupSlot[] {
  const preset = getFormation(formation) ?? FORMATIONS[0]
  const used = new Set<string>()
  const byGroup: Record<string, Player[]> = { GK: [], DEF: [], MID: [], FWD: [] }
  for (const p of players) byGroup[p.positionGroup].push(p)
  // Sort each group by a strength heuristic so the best XI tends to start.
  const strength = (p: Player) => {
    const a = p.attributes
    return a.pace + a.shooting + a.passing + a.dribbling + a.defending + a.physical
  }
  for (const g of Object.keys(byGroup)) byGroup[g].sort((a, b) => strength(b) - strength(a))

  const slots: LineupSlot[] = []
  for (const slot of preset.slots) {
    const group = positionGroupOf(slot.position)
    let candidate = byGroup[group].find((p) => !used.has(p.id) && p.status === 'Available')
    if (!candidate) candidate = byGroup[group].find((p) => !used.has(p.id))
    if (!candidate) {
      candidate = players.find((p) => !used.has(p.id))!
    }
    used.add(candidate.id)
    slots.push({
      playerId: candidate.id,
      position: slot.position,
      x: slot.x,
      y: slot.y,
      isStarter: true,
    })
    void r
  }
  return slots
}

function genTouches(r: Rng, baseX: number, baseY: number, n: number): { x: number; y: number }[] {
  const out: { x: number; y: number }[] = []
  for (let i = 0; i < n; i++) {
    const spreadX = rf(r, -18, 18)
    const spreadY = rf(r, -16, 16)
    out.push({
      x: clamp(Math.round(baseX + spreadX), 2, 98),
      y: clamp(Math.round(baseY + spreadY), 2, 98),
    })
  }
  return out
}

function buildMatch(
  index: number,
  players: Player[],
  r: Rng,
): Match {
  const formation = pick(r, ['4-3-3', '4-3-3', '4-2-3-1', '4-4-2', '3-5-2'])
  const venue = index % 2 === 0 ? 'Home' : 'Away'
  const competition = index % 5 === 4 ? 'Cup' : 'League'
  const opponent = OPPONENTS[index % OPPONENTS.length]

  // Date: weekly cadence ending recently (relative to 2026 season).
  const start = new Date(2025, 7, 16) // Aug 16 2025
  const d = new Date(start)
  d.setDate(start.getDate() + index * 7)
  const date = d.toISOString().slice(0, 10)

  const starters = chooseStarters(players, formation, r)
  const starterIds = new Set(starters.map((s) => s.playerId))

  // Score driven by a strength bias around a typical mid-table-to-good side.
  const goalsFor = clamp(Math.round(rf(r, 0, 3.4)), 0, 5)
  const goalsAgainst = clamp(Math.round(rf(r, 0, 2.6)), 0, 4)

  const shotsFor = ri(r, 8, 20)
  const shotsAgainst = ri(r, 4, 16)
  const possession = ri(r, 41, 64)
  const xgFor = round1(clamp(goalsFor * 0.7 + rf(r, 0.3, 1.4), 0.3, 4.2))
  const xgAgainst = round1(clamp(goalsAgainst * 0.7 + rf(r, 0.2, 1.1), 0.2, 3.4))
  const cornersFor = ri(r, 2, 11)
  const cornersAgainst = ri(r, 1, 8)

  // Attacking pool weighted toward forwards / attacking mids.
  const attackPool = starters
    .filter((s) => positionGroupOf(s.position) === 'FWD' || ['CAM', 'CM', 'RM', 'LM'].includes(s.position))
    .map((s) => s.playerId)
  const playmakerPool = starters
    .filter((s) => ['CAM', 'CM', 'RM', 'LM', 'RW', 'LW'].includes(s.position))
    .map((s) => s.playerId)

  // Distribute goals + assists into concrete scorers.
  const events: MatchEvent[] = []
  const goalsByPlayer: Record<string, number> = {}
  const assistsByPlayer: Record<string, number> = {}
  for (let g = 0; g < goalsFor; g++) {
    const scorer = attackPool.length ? pick(r, attackPool) : pick(r, [...starterIds])
    goalsByPlayer[scorer] = (goalsByPlayer[scorer] ?? 0) + 1
    const minute = ri(r, 3, 90)
    const slot = starters.find((s) => s.playerId === scorer)
    events.push({
      id: `m${index}-g${g}`,
      minute,
      type: 'goal',
      playerId: scorer,
      x: slot ? clamp(slot.x + ri(r, -8, 8), 4, 96) : 50,
      y: clamp(ri(r, 78, 96), 60, 99),
      onTarget: true,
    })
    if (r() > 0.28 && playmakerPool.length) {
      const assister = pick(r, playmakerPool.filter((id) => id !== scorer).length ? playmakerPool.filter((id) => id !== scorer) : playmakerPool)
      assistsByPlayer[assister] = (assistsByPlayer[assister] ?? 0) + 1
      events.push({ id: `m${index}-a${g}`, minute, type: 'assist', playerId: assister, relatedPlayerId: scorer })
    }
  }
  // Cards.
  const cardCount = ri(r, 0, 3)
  for (let c = 0; c < cardCount; c++) {
    const pid = pick(r, [...starterIds])
    events.push({ id: `m${index}-y${c}`, minute: ri(r, 20, 90), type: 'yellow', playerId: pid })
  }

  // Per-player stat lines for the starting XI.
  const playerStats: PlayerMatchStat[] = starters.map((slot): PlayerMatchStat => {
    const player = players.find((p) => p.id === slot.playerId)!
    const group = positionGroupOf(slot.position)
    const minutes = r() > 0.78 ? ri(r, 58, 88) : 90 // some subbed off
    const goals = goalsByPlayer[player.id] ?? 0
    const assists = assistsByPlayer[player.id] ?? 0
    const isAtt = group === 'FWD'
    const isMid = group === 'MID'
    const shots = isAtt ? ri(r, 1, 6) : isMid ? ri(r, 0, 3) : ri(r, 0, 1)
    const shotsOnTarget = clamp(Math.round(shots * rf(r, 0.3, 0.7)) + goals, 0, shots + goals)
    const passes = group === 'DEF' || slot.position === 'CDM' || slot.position === 'CM'
      ? ri(r, 45, 92) : group === 'GK' ? ri(r, 18, 38) : ri(r, 28, 64)
    const passAcc = rf(r, 0.74, 0.93)
    const tackles = group === 'DEF' ? ri(r, 2, 7) : group === 'MID' ? ri(r, 1, 5) : ri(r, 0, 2)
    const interceptions = group === 'DEF' ? ri(r, 1, 6) : group === 'MID' ? ri(r, 0, 4) : ri(r, 0, 1)
    const duelsTotal = ri(r, 4, 16)
    const duelsWon = Math.round(duelsTotal * rf(r, 0.4, 0.72))
    const distance = round1(rf(r, group === 'GK' ? 3.5 : 9.0, group === 'GK' ? 5.5 : 12.4))
    const xg = round1(clamp(shots * rf(r, 0.08, 0.2) + goals * 0.3, 0, 2.2))

    // Rating model: base + contributions + result swing.
    let rating = 6.2
    rating += goals * 0.9 + assists * 0.6
    rating += (shotsOnTarget - shots * 0.3) * 0.05
    rating += (tackles + interceptions) * 0.04
    rating += (goalsFor - goalsAgainst) * 0.12
    rating += rf(r, -0.5, 0.6)
    rating = round1(clamp(rating, 4.5, 9.7))

    return {
      playerId: player.id,
      position: slot.position,
      minutes,
      goals,
      assists,
      shots,
      shotsOnTarget,
      passes,
      passesCompleted: Math.round(passes * passAcc),
      keyPasses: isMid || isAtt ? ri(r, 0, 4) : ri(r, 0, 1),
      tackles,
      interceptions,
      duelsWon,
      duelsTotal,
      distanceKm: distance,
      xg,
      rating,
      yellowCards: events.filter((e) => e.type === 'yellow' && e.playerId === player.id).length,
      redCards: 0,
      avgX: slot.x + ri(r, -6, 6),
      avgY: clamp(slot.y + ri(r, -6, 10), 4, 96),
      touches: genTouches(r, slot.x, slot.y, ri(r, 12, 20)),
    }
  })

  const notesOptions = [
    'Controlled the tempo well in the first half; need to manage game state better when ahead.',
    'Aggressive press worked — won the ball high repeatedly. Final-third decisions still costing us.',
    'Compact and disciplined out of possession. Lacked a spark in the final third.',
    'Excellent transition play. Set pieces remain an area to sharpen.',
    'Second-half drop-off in intensity. Rotate the midfield next week.',
  ]

  return {
    id: `m${index + 1}`,
    date,
    opponent,
    venue,
    competition,
    formation,
    goalsFor,
    goalsAgainst,
    possession,
    xgFor,
    xgAgainst,
    shotsFor,
    shotsAgainst,
    cornersFor,
    cornersAgainst,
    lineup: starters,
    events: events.sort((a, b) => a.minute - b.minute),
    playerStats,
    notes: pick(r, notesOptions),
  }
}

function buildMatches(players: Player[], count: number): Match[] {
  const r = makeRng(98765)
  const matches: Match[] = []
  for (let i = 0; i < count; i++) matches.push(buildMatch(i, players, r))
  return matches
}

// ---------------------------------------------------------------------------
// Training drills
// ---------------------------------------------------------------------------
const DRILLS: Drill[] = [
  {
    id: 'd1', name: 'Rondo 5v2', category: 'Possession', durationMin: 15, players: '7',
    intensity: 'Medium',
    description: 'Five attackers keep possession inside a 10x10 grid against two defenders. Defenders swap in when they win the ball or force it out.',
    objectives: ['Quick one-touch passing', 'Body shape to receive', 'Pressing triggers'],
    equipment: ['Cones', '1 ball', 'Bibs'],
    focus: ['passing', 'dribbling'],
  },
  {
    id: 'd2', name: 'Finishing Carousel', category: 'Finishing', durationMin: 20, players: '8-12',
    intensity: 'High',
    description: 'Two feeding stations deliver cut-backs and through balls to strikers arriving into the box. Alternate near-post and far-post runs.',
    objectives: ['First-time finishing', 'Movement in the box', 'Composure 1v1 with keeper'],
    equipment: ['Cones', 'Balls', '2 goals', 'Keepers'],
    focus: ['shooting', 'pace'],
  },
  {
    id: 'd3', name: 'Pressing Trap', category: 'Defending', durationMin: 18, players: '10-14',
    intensity: 'High',
    description: 'Shape the press to force play into a wide channel, then collapse to win the ball. Reward the team for winning possession in the trap zone.',
    objectives: ['Coordinated pressing', 'Cover & balance', 'Counter-press on turnover'],
    equipment: ['Cones', 'Bibs', 'Balls'],
    focus: ['defending', 'physical'],
  },
  {
    id: 'd4', name: 'Build-Up From the Back', category: 'Possession', durationMin: 22, players: '11+',
    intensity: 'Medium',
    description: 'Play out from the keeper against a pressing front line. Centre-backs split, pivot drops, full-backs provide width to progress past the first line.',
    objectives: ['Playing through the lines', 'Pivot rotations', 'Goalkeeper distribution'],
    equipment: ['Full pitch third', 'Balls', 'Mannequins'],
    focus: ['passing', 'physical'],
  },
  {
    id: 'd5', name: 'Transition 4v4+2', category: 'Transition', durationMin: 16, players: '10',
    intensity: 'High',
    description: 'On every turnover, the team that wins the ball attacks the opposite goal immediately. Two neutral players support whoever is in possession.',
    objectives: ['Reacting to turnovers', 'Fast vertical play', 'Recovery runs'],
    equipment: ['2 small goals', 'Bibs', 'Balls'],
    focus: ['pace', 'passing'],
  },
  {
    id: 'd6', name: 'Crossing & Heading', category: 'Attacking', durationMin: 20, players: '8-12',
    intensity: 'Medium',
    description: 'Wide players overlap and deliver; central players attack near post, far post and the penalty spot in a coordinated three-run pattern.',
    objectives: ['Quality of delivery', 'Attacking the cross', 'Defensive heading clearances'],
    equipment: ['Cones', 'Balls', 'Goal', 'Keeper'],
    focus: ['physical', 'shooting'],
  },
  {
    id: 'd7', name: 'Dynamic Warm-Up & Activation', category: 'Warm-up', durationMin: 12, players: 'All',
    intensity: 'Low',
    description: 'Progressive mobility, neuromuscular activation and short accelerations to prepare the body for high-intensity work and reduce injury risk.',
    objectives: ['Raise core temperature', 'Activate posterior chain', 'Prime change of direction'],
    equipment: ['Mini-bands', 'Cones', 'Hurdles'],
    focus: ['physical', 'pace'],
  },
  {
    id: 'd8', name: 'Possession 8v8+3', category: 'Possession', durationMin: 24, players: '19',
    intensity: 'Medium',
    description: 'Large-area possession with three floaters. Switch the point of attack quickly; reward eight consecutive passes and a switch of play.',
    objectives: ['Keeping the ball under pressure', 'Switching play', 'Support angles'],
    equipment: ['Cones', 'Bibs', 'Balls'],
    focus: ['passing', 'dribbling'],
  },
  {
    id: 'd9', name: '1v1 Attacking Duels', category: 'Technical', durationMin: 15, players: '6-10',
    intensity: 'High',
    description: 'Attacker receives facing a defender in an isolated channel and must beat them to score in a mini-goal. Rotate roles each rep.',
    objectives: ['Change of pace & direction', 'Defensive jockeying', 'Decision in the duel'],
    equipment: ['Cones', '2 mini goals', 'Balls'],
    focus: ['dribbling', 'pace'],
  },
  {
    id: 'd10', name: 'Set-Piece Routines', category: 'Set Pieces', durationMin: 20, players: '11+',
    intensity: 'Low',
    description: 'Rehearse attacking corner and free-kick patterns plus defensive zonal/man assignments. Walk through, then execute at game speed.',
    objectives: ['Rehearsed delivery zones', 'Blocking & screening', 'Defensive accountability'],
    equipment: ['Balls', 'Goal', 'Mannequins'],
    focus: ['shooting', 'defending'],
  },
  {
    id: 'd11', name: 'Small-Sided Conditioning', category: 'Physical', durationMin: 18, players: '8-12',
    intensity: 'High',
    description: '3-minute small-sided games with full-effort work and short rest to develop repeated high-intensity capacity in a football context.',
    objectives: ['Repeated sprint ability', 'Game-realistic conditioning', 'Competitive intensity'],
    equipment: ['Small goals', 'Bibs', 'Balls'],
    focus: ['physical', 'pace'],
  },
  {
    id: 'd12', name: 'Counter-Attack Waves', category: 'Attacking', durationMin: 18, players: '10-14',
    intensity: 'High',
    description: 'Defensive unit wins the ball and launches a 4v2 break. Emphasise the first pass forward, supporting runs and finishing the move quickly.',
    objectives: ['First pass forward', 'Numerical advantage in transition', 'Clinical finishing'],
    equipment: ['Full pitch half', 'Goals', 'Balls', 'Keeper'],
    focus: ['pace', 'shooting'],
  },
]

function buildSessions(): TrainingSession[] {
  return [
    {
      id: 's1', date: '2026-06-29', title: 'Matchday -3: Possession & Press',
      focus: 'Building out and winning the ball high',
      drillIds: ['d7', 'd1', 'd4', 'd3'], notes: 'Sharp first session of the week. Keep volume controlled.',
    },
    {
      id: 's2', date: '2026-07-01', title: 'Matchday -1: Finishing & Set Pieces',
      focus: 'Final third sharpness and dead-ball routines',
      drillIds: ['d7', 'd2', 'd10'], notes: 'Light legs. Confidence in front of goal.',
    },
  ]
}

function buildLineups(matches: Match[]): Lineup[] {
  const last = matches[matches.length - 1]
  return [
    {
      id: 'l1', name: 'First-choice 4-3-3', formation: '4-3-3',
      slots: last ? last.lineup : [],
      notes: 'Our strongest available XI when fully fit.',
    },
  ]
}

// ---------------------------------------------------------------------------
// Video analysis — sample clips with tagged moments
// ---------------------------------------------------------------------------
function buildVideos(matches: Match[]): VideoClip[] {
  const featured = matches[matches.length - 1]
  return [
    {
      id: 'v1',
      title: `Match Review — vs ${featured?.opponent ?? 'Opponent'}`,
      source: 'url',
      // Public-domain sample clip so the player works out of the box.
      url: 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4',
      matchId: featured?.id,
      opponent: featured?.opponent,
      date: featured?.date,
      durationSec: 596,
      notes:
        'Full-match review. Focus on our build-up shape and the transition moments in the second half. Replace this sample with your own match footage URL or upload a file.',
      tags: [
        { id: 'vt1', time: 18, category: 'Build-up', title: 'Playing out from the back', note: 'CBs split well, pivot drops in to receive between the lines.', team: 'us', playerId: 'p8' },
        { id: 'vt2', time: 64, endTime: 72, category: 'Press', title: 'High press wins it back', note: 'Striker angles his run to cut the passing lane — textbook trigger.', team: 'us', playerId: 'p15' },
        { id: 'vt3', time: 132, category: 'Chance', title: 'Cut-back chance', note: 'Overload on the right, cut-back just behind the runner.', team: 'us', playerId: 'p13' },
        { id: 'vt4', time: 210, category: 'Defensive Error', title: 'Lost runner at the back post', note: 'Need to communicate the switch — back post left unmarked.', team: 'us', playerId: 'p6' },
        { id: 'vt5', time: 295, endTime: 308, category: 'Goal', title: 'Goal — quick transition', note: 'Three passes from regain to finish. Exactly the pattern we drilled.', team: 'us', playerId: 'p15' },
        { id: 'vt6', time: 372, category: 'Set Piece', title: 'Corner routine', note: 'Near-post flick-on screen worked; defender ball-watching.', team: 'us', playerId: 'p4' },
        { id: 'vt7', time: 455, category: 'Skill', title: '1v1 beat on the wing', note: 'Change of pace to get the cross away.', team: 'us', playerId: 'p13' },
      ],
    },
    {
      id: 'v2',
      title: 'Opposition Analysis — pressing patterns',
      source: 'youtube',
      // Creative-Commons sample (Big Buck Bunny) so embedding demonstrates cleanly.
      url: 'ScMzIvxBSi4',
      opponent: 'Next opponent',
      notes: 'Scout the opponent’s mid-block and where they can be played through. Tag their pressing triggers.',
      tags: [
        { id: 'vt8', time: 12, category: 'Press', title: 'Their press trigger', note: 'They jump the press on the back-pass to the keeper.', team: 'them' },
        { id: 'vt9', time: 88, category: 'Turnover', title: 'Where they’re vulnerable', note: 'Space in behind the left-back when he steps.', team: 'them' },
      ],
    },
  ]
}

// ---------------------------------------------------------------------------
// Assemble the seed dataset
// ---------------------------------------------------------------------------
export function buildSeedData(): AppData {
  const players = buildPlayers()
  const matches = buildMatches(players, 12)
  const sessions = buildSessions()
  const lineups = buildLineups(matches)
  const videos = buildVideos(matches)
  return {
    team: {
      name: 'Riverside Athletic',
      season: '2025/26',
      coach: 'J. Petty',
      primaryColor: '#0ea5e9',
      secondaryColor: '#0b1220',
    },
    players,
    matches,
    drills: DRILLS,
    sessions,
    lineups,
    videos,
    version: SCHEMA_VERSION,
  }
}
