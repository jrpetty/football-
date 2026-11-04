import type { Inventory, Item } from './data.ts'
import type { BuildingType } from './buildings.ts'

export type ActionKind =
  | 'gather' | 'hunt' | 'fish' | 'farm' | 'eat' | 'drink' | 'sleep' | 'rest'
  | 'build' | 'craft' | 'research' | 'store' | 'take' | 'talk' | 'give' | 'teach'
  | 'court' | 'explore' | 'go_home' | 'found_tribe' | 'join_tribe' | 'decree'
  | 'write' | 'trade' | 'celebrate' | 'heal' | 'attack' | 'wander' | 'follow'

export const ACTION_KINDS: ActionKind[] = [
  'gather', 'hunt', 'fish', 'farm', 'eat', 'drink', 'sleep', 'rest', 'build', 'craft', 'research', 'store', 'take',
  'talk', 'give', 'teach', 'court', 'explore', 'go_home', 'found_tribe', 'join_tribe', 'decree', 'write', 'trade',
  'celebrate', 'heal', 'attack', 'wander', 'follow',
]

export interface PlanStep {
  action: ActionKind
  target: string
  amount: number
  text: string
}

export interface Task extends PlanStep {
  phase: 'init' | 'travel' | 'work'
  tx: number
  ty: number
  path: Int32Array | null
  pathIdx: number
  progress: number
  need: number
  ref: number // building / agent / animal id or tile index
  label: string
  fails: number
  started: number
  fromPlan: boolean
}

export interface Memory {
  t: number // sim minute
  text: string
  imp: number // importance 1..10
}

export interface Relationship {
  affinity: number // -100 .. 100
  familiarity: number // 0..100
  lastTalk: number
}

export interface Traits {
  openness: number
  conscientiousness: number
  extraversion: number
  agreeableness: number
  neuroticism: number
  intelligence: number
  strength: number
}

export type SkillName = 'gathering' | 'farming' | 'building' | 'crafting' | 'research' | 'social' | 'hunting'
export const SKILL_NAMES: SkillName[] = ['gathering', 'farming', 'building', 'crafting', 'research', 'social', 'hunting']

export interface Speech {
  text: string
  to: number | null
  until: number // real ms timestamp for bubble
  t: number
}

export interface Heard {
  t: number
  fromId: number
  from: string
  text: string
}

export interface Agent {
  id: number
  name: string
  sex: 'female' | 'male'
  birth: number // sim minute of birth (can be negative)
  x: number
  y: number
  dir: number // facing for rendering (-1 / 1)
  moving: boolean
  tribeId: number
  homeId: number
  partnerId: number
  parents: number[]
  children: number[]
  alive: boolean
  deathT: number
  deathCause: string
  generation: number

  health: number
  hunger: number // 0 full → 100 starving
  thirst: number
  energy: number // 100 rested
  warmth: number // 100 warm
  social: number // 100 fulfilled
  mood: number // 0..100 happiness
  sick: number // 0 healthy, >0 severity
  injured: number

  traits: Traits
  skills: Record<SkillName, number>
  toolTier: number
  toolWear: number
  clothing: number // durability 0..100
  inv: Inventory

  memories: Memory[]
  reflections: string[]
  goal: string
  selfImage: string
  beliefs: string
  thought: string
  feeling: string
  relations: Record<number, Relationship>
  heard: Heard[]
  journal: { t: number; text: string; say?: string; model?: string }[]

  plan: PlanStep[]
  task: Task | null
  speech: Speech | null

  // mind / AI scheduling
  aiPending: boolean
  aiLastT: number // sim minute of last decision
  aiDecisions: number
  aiErrors: number
  lastReflectT: number
  urgentThink: boolean
  model: string // assigned model id ('' = tribe default)

  stats: { gathered: number; built: number; research: number; crafted: number; talks: number; kids: number }
}

export interface Tribe {
  id: number
  name: string
  color: string
  emblem: string
  founderId: number
  leaderId: number
  foundedT: number
  values: string
  beliefs: string
  laws: string[]
  techs: string[]
  research: { tech: string; progress: number } | null
  storage: Inventory
  center: { x: number; y: number }
  relations: Record<number, number> // -100 hostile .. 100 allied
  model: string // AI model used by members ('' = default)
  alive: boolean
  history: { t: number; text: string }[]
  works: { t: number; author: string; title: string; text: string }[]
  launched: boolean
}

export interface Building {
  id: number
  type: BuildingType
  x: number
  y: number
  tribeId: number
  ownerId: number
  progress: number // labour hours done
  done: boolean
  hp: number
  crop: number // farms: 0..1 growth; pasture: herd size
  planted: boolean
  builtT: number
  name: string
}

export interface Animal {
  id: number
  kind: 'deer' | 'boar' | 'rabbit'
  x: number
  y: number
  tx: number
  ty: number
  alive: boolean
  dir: number
}

export interface ChronicleEntry {
  t: number
  kind: 'birth' | 'death' | 'tech' | 'build' | 'social' | 'tribe' | 'event' | 'speech' | 'milestone' | 'ai'
  text: string
  agentId?: number
  tribeId?: number
  icon: string
}

export interface Chapter {
  t: number
  title: string
  text: string
}

export interface WorldEvent {
  kind: 'drought' | 'harsh_winter' | 'plague' | 'bounty' | 'earthquake' | 'migration' | 'golden_age'
  start: number
  end: number
  label: string
}

export interface Sample {
  t: number
  pop: number
  tribes: number
  techs: number
  buildings: number
  food: number
  avgMood: number
  era: number
}

export type { Inventory, Item }
