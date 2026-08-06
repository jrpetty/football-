import type { Vec2 } from './core/vec'

export type Team = 'home' | 'away'
export type Role = 'GK' | 'DEF' | 'MID' | 'FWD'

// 'touch' is the soft close-control nudge; 'strike' is the human's single
// continuous kick (a tap rolls a pass, a full charge is a shot) which the
// simulation classifies afterwards for the stat sheet. The rest are what the AI
// asks for, where intent is already known.
export type KickType = 'touch' | 'strike' | 'pass' | 'through' | 'shot' | 'clear'

// A request to release the ball, produced the moment a player kicks it.
export interface KickRequest {
  type: KickType
  power: number // 0..1, scaled by the kick model
  aim: Vec2 // unit direction on the pitch plane
  // Vertical shaping from the mouse flick: +1 is a full lift (chip/lofted
  // ball), 0 is a natural strike, −1 is driven hard into the turf with dip.
  loft: number
  spin: number // signed side-spin from a sideways flick — bends the flight
  // A close-control move, when the flick during a touch was decisive enough to
  // mean one. The same wrist vocabulary that shapes a strike shapes your feet.
  skill?: Skill
}

// Close-control moves, selected by which way you flicked during a touch.
export type Skill = 'drag' | 'roll' | 'lift'

// The per-tick control signal for a single player. Human input and every AI
// brain funnel into this identical shape, so the simulation never needs to know
// who's driving — the whole point of an input-driven design.
export interface Command {
  move: Vec2 // desired heading * throttle (magnitude 0..1)
  sprint: boolean
  walk: boolean // deliberate slow pace, for precise positioning
  aim: Vec2 // unit aim direction (where a kick would go)
  chargePass: boolean // holding the pass button (fills power meter)
  chargeShot: boolean // holding the shoot button
  kick: KickRequest | null // set on the tick a charged kick is released
  jump: boolean // leave the ground — everything you can reach comes with you
  shield: boolean // turn side-on and hold the ball behind your body
  slide: boolean // commit to a slide tackle
}

export const emptyCommand = (): Command => ({
  move: { x: 0, y: 0 },
  sprint: false,
  walk: false,
  aim: { x: 1, y: 0 },
  chargePass: false,
  chargeShot: false,
  kick: null,
  jump: false,
  shield: false,
  slide: false,
})

// The pitch is enclosed and the ball rebounds off the boards, so the only stoppage
// left is the kickoff after a goal or at the start of a half.
export type Restart = 'kickoff' | 'none'

export interface MatchConfig {
  teamSize: number // outfield + GK per side
  halfLength: number // seconds
  mode: 'match' | 'training'
  singleKeeper: boolean
  view: '2d' | '3d' // presentation: top-down 2D or first/third-person 3D
  position: Role // the one player you are for the whole match
  heightSens: number // Kick Height Sensitivity — flick-up → loft
  curveSens: number // Kick Curve Sensitivity — sideways flick → spin
  humanControlled?: boolean // false = both teams fully AI (testing / attract mode)
}

export interface Stats {
  goals: number
  shots: number
  onTarget: number
  passes: number
  passesCompleted: number
  tackles: number
  saves: number
  possessionTicks: number
}

export const emptyStats = (): Stats => ({
  goals: 0,
  shots: 0,
  onTarget: 0,
  passes: 0,
  passesCompleted: 0,
  tackles: 0,
  saves: 0,
  possessionTicks: 0,
})
