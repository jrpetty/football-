// The shape of a Quartet scenario and of the four people living through it.

export type Category = 'Survival' | 'Work' | 'Home & family' | 'Crisis' | 'Politics' | 'Adventure'

export interface Place {
  id: string
  name: string
  x: number // stage coordinates (0..1000 × 0..620) of the zone
  y: number
  w: number
  h: number
  props: string[] // emoji scattered in the zone
  desc: string
}

export interface StatDef {
  id: string
  label: string
  icon: string
  start: number
  max: number
  good: 'high' | 'low'
  /** the scenario ends badly if this hits its bad extreme */
  critical?: boolean
}

export interface Effects {
  [stat: string]: number
}

export interface Move {
  id: string
  label: string
  at?: string[] // place ids where this can be done (moving there is automatic)
  effects: Effects
  self?: { energy?: number; mood?: number; stress?: number; health?: number; score?: number }
  chance?: number // success probability before skill/energy modifiers
  fail?: Effects
  failSelf?: { energy?: number; mood?: number; stress?: number; health?: number }
  trait?: 'openness' | 'conscientiousness' | 'extraversion' | 'agreeableness' | 'neuroticism' // helps success
  verb: string // for the script: "fished in the lagoon"
}

export interface ScenarioEvent {
  id: string
  text: string
  effects?: Effects
  all?: { energy?: number; mood?: number; stress?: number; health?: number }
  from?: number // earliest beat
}

export interface RoleDef {
  title: string
  age: [number, number]
  brief: string
  goal: string
  secret: string
  item?: string
}

export type SelfFx = { energy?: number; mood?: number; stress?: number; health?: number; score?: number }
export type TraitId = 'openness' | 'conscientiousness' | 'extraversion' | 'agreeableness' | 'neuroticism'

/** A choice the whole group must make together — they vote. */
export interface Dilemma {
  id: string
  at: number // when it comes up, as a fraction of the run (0..1)
  finale?: boolean
  title: string
  text: string
  options: {
    id: string
    label: string
    effects?: Effects
    all?: SelfFx
    /** temperaments drawn to this option (instinct voting) */
    lean?: Partial<Record<TraitId, number>>
    outcome: string // what happens when the group chooses it
  }[]
}

/** What an item does when its holder uses it. */
export interface ItemUse {
  item: string // matches the start of an item's name
  label: string
  effects: Effects
  self?: SelfFx
  once?: boolean
  /** heals everyone in the same place */
  heal?: number
  verb: string
}

export interface Scenario {
  id: string
  title: string
  emoji: string
  category: Category
  tagline: string
  premise: string
  theme: { sky: [string, string]; ground: string; accent: string; night: boolean }
  places: Place[]
  roles: RoleDef[]
  stats: StatDef[]
  /** per-beat drift of shared stats (e.g. hunger eats food) */
  upkeep: Effects
  moves: Move[]
  events: ScenarioEvent[]
  beats: number
  clock: (beat: number) => string
  /** optional positions each person holds (verdicts, votes, decisions) */
  stances?: string[]
  /** optional personal score shown per person (popularity, polls…) */
  score?: { label: string; icon: string; start: number }
  win?: { stat: string; at: number; text: string }
  lines: string[] // instinct chatter, {name} = listener
  /** group decisions, scheduled across the acts (the last is the finale) */
  dilemmas?: Dilemma[]
  /** what the cast's items can do */
  items?: ItemUse[]
  /** names of the three acts */
  acts?: [string, string, string]
}

export interface Opinion {
  trust: number // -100..100
  note: string
}

export interface Person {
  id: number
  name: string
  age: number
  gender: 'woman' | 'man' | 'nonbinary'
  role: RoleDef
  traits: { openness: number; conscientiousness: number; extraversion: number; agreeableness: number; neuroticism: number }
  quirk: string
  backstory: string
  look: { skin: number; hair: number; style: number; beard: boolean; glasses: boolean; top: string; build: number }
  place: string
  health: number
  energy: number
  mood: number
  stress: number
  score: number
  stance: string
  items: string[]
  opinions: Record<number, Opinion>
  memory: string[]
  thought: string
  feeling: string
  lastSay: string
  lastSayTo: string
  lastAction: string
  model: string // mind profile id ('' = instinct)
  thinking: boolean
  errors: number
  bubbleUntil: number
  emote: string
  emoteUntil: number
  /** their secret is known to the others */
  secretOut: boolean
  /** how it came out: confessed or exposed */
  secretHow: '' | 'confessed' | 'exposed'
  /** good work done for the group (successful actions, help, comfort) */
  contribution: number
  /** current vote on an open dilemma */
  vote: string
}

/** Something on stage for the eyes: floating numbers, reactions. */
export interface Fx {
  id: number
  who?: number
  place?: string
  text: string
  kind: 'good' | 'bad' | 'social' | 'info'
  at: number
}

/** A relationship moment drawn between two people. */
export interface Link {
  a: number
  b: number
  kind: 'warm' | 'hot' | 'help' | 'sway'
  at: number
}

export interface Banner {
  kind: 'event' | 'dilemma' | 'act' | 'reveal' | 'vote'
  title: string
  text: string
  at: number
  votes?: { option: string; label: string; ids: number[]; chosen: boolean }[]
  chips?: string[]
  /** for each chip: good news or bad */
  tones?: ('up' | 'down')[]
}

export interface Line {
  beat: number
  kind: 'say' | 'narration' | 'event' | 'action' | 'system'
  who?: number
  to?: number | 'all'
  place?: string
  text: string
}

export interface Ending {
  tier: 'triumph' | 'success' | 'mixed' | 'failure' | 'disaster'
  title: string
  text: string
  fates: { id: number; text: string }[]
  score: number // 0..100
  mvp: number | null
  cohesion: number // -100..100
  highlights: string[]
}
