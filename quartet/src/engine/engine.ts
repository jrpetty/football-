// One run of a scenario, told in three acts.
//
// Each beat: the act may turn; a hard group choice (a dilemma) may come up,
// or something may simply happen to them; all four minds decide at once (AI
// or instinct) — what to say, where to go, what to do, and how to vote; then
// everything is resolved. Work succeeds or fails by chance, energy,
// temperament and teamwork. People can comfort, confront, persuade, help and
// give to each other; secrets can be confessed or dragged into the open;
// items can be used. Trust between them is tracked and the group's cohesion
// shapes how well they work together. A narrator (optional AI director) ties
// each beat together, and the ending weighs the stakes, the people and the
// bonds between them.

import type { Banner, Dilemma, Ending, Fx, ItemUse, Line, Link, Person, Scenario, SelfFx, Move } from './types.ts'
import { buildDirectorPrompt, buildPersonPrompt, buildEpiloguePrompt, DECISION_SCHEMA, DIRECTOR_SCHEMA, EPILOGUE_SCHEMA, systemFor, DIRECTOR_SYSTEM, EPILOGUE_SYSTEM } from './prompts.ts'

export interface Decision {
  thought: string
  feeling: string
  say: string
  say_to: string
  move: string
  go_to: string
  target: string
  detail: string
  stance: string
  vote: string
  opinions: { name: string; delta: number; why: string }[]
}

/** How the run reaches its minds — implemented by the app (AI providers) and by tests. */
export interface MindHost {
  canThink(p: Person): boolean
  think(p: Person, system: string, user: string, schema: Record<string, unknown>): Promise<string>
  canDirect(): boolean
  direct(system: string, user: string, schema: Record<string, unknown>): Promise<string>
}

/** Things anyone can do to or with someone else, in every scenario. */
export const SOCIAL = ['comfort', 'confront', 'persuade', 'help', 'confess', 'give', 'use'] as const

const clamp = (v: number, a: number, b: number) => (v < a ? a : v > b ? b : v)
const sign = (v: number) => (v >= 0 ? `+${Math.round(v)}` : `−${Math.round(-v)}`)

function parse<T>(text: string): T | null {
  const t = text.replace(/<think>[\s\S]*?<\/think>/gi, '').replace(/^```(?:json)?\s*|\s*```$/g, '').trim()
  try { return JSON.parse(t) as T } catch { /* fall through */ }
  const m = /\{[\s\S]*\}/.exec(t)
  if (m) { try { return JSON.parse(m[0]) as T } catch { /* nope */ } }
  return null
}

export class Run {
  sc: Scenario
  people: Person[]
  stats: Record<string, number> = {}
  /** the stats at the start of the latest beat (to show what changed) */
  prev: Record<string, number> = {}
  beat = 0
  act = 1
  lines: Line[] = []
  narrations: string[] = []
  highlights: string[] = []
  over = false
  ending: Ending | null = null
  busy = false
  /** an open group decision being voted on this beat */
  pending: Dilemma | null = null
  /** cards for the stage, in order (acts, events, choices, votes, secrets) */
  banners: Banner[] = []
  /** how many cards the latest beat produced (the stage lingers on them) */
  stepBanners = 0
  fx: Fx[] = []
  links: Link[] = []
  private fxId = 1
  private used = new Set<string>()
  private doneThisBeat = new Map<string, number>()
  private helping = new Map<number, number[]>()
  version = 0

  constructor(sc: Scenario, people: Person[]) {
    this.sc = sc
    this.people = people
    for (const s of sc.stats) this.stats[s.id] = s.start
    this.prev = { ...this.stats }
    for (const p of people) {
      if (sc.stances) p.stance = sc.stances[sc.stances.length - 1]
      if (sc.score) p.score = sc.score.start
      p.secretOut = p.secretOut ?? false
      p.secretHow = p.secretHow ?? ''
      p.contribution = p.contribution ?? 0
      p.vote = ''
      // first impressions: chemistry of temperaments, plus luck
      for (const o of people) if (o !== p) {
        const chem = (p.traits.agreeableness + o.traits.agreeableness) / 2 - 0.5 - Math.abs(p.traits.extraversion - o.traits.extraversion) * 0.25 - Math.abs(p.traits.conscientiousness - o.traits.conscientiousness) * 0.2
        p.opinions[o.id] = { trust: Math.round(chem * 40 + (Math.random() - 0.45) * 36), note: 'first impressions' }
      }
    }
    this.push({ beat: 0, kind: 'system', text: `Act I · ${this.actName(1)}` })
    this.push({ beat: 0, kind: 'system', text: sc.clock(0) })
    this.push({ beat: 0, kind: 'narration', text: sc.premise })
    this.show({ kind: 'act', title: `Act I · ${this.actName(1)}`, text: sc.tagline, at: Date.now() })
  }

  get clock(): string {
    return this.sc.clock(this.beat)
  }
  actName(n: number): string {
    return this.sc.acts?.[n - 1] ?? ['The situation', 'Complications', 'The reckoning'][n - 1]
  }
  alive(p: Person): boolean {
    return p.health > 0
  }
  get living(): Person[] {
    return this.people.filter((p) => this.alive(p))
  }
  byName(name: string, not?: Person): Person | undefined {
    const n = name.trim().toLowerCase()
    if (!n) return undefined
    return this.people.find((p) => p !== not && p.name.toLowerCase() === n) ?? this.people.find((p) => p !== not && (n.includes(p.name.toLowerCase()) || p.role.title.toLowerCase().includes(n)))
  }
  placeName(id: string): string {
    return this.sc.places.find((p) => p.id === id)?.name ?? id
  }
  /** -100..100: how much the living trust each other on average. */
  get cohesion(): number {
    let t = 0, n = 0
    for (const p of this.living) for (const o of this.living) if (o !== p) { t += p.opinions[o.id]?.trust ?? 0; n++ }
    return n ? t / n : 0
  }
  /** 0..1: how close the end feels — act, danger and bad blood. */
  get tension(): number {
    let danger = 0
    for (const s of this.sc.stats) if (s.critical) danger = Math.max(danger, 1 - this.frac(s))
    const stress = this.living.reduce((a, p) => a + p.stress, 0) / Math.max(1, this.living.length) / 100
    return clamp(0.15 + (this.act - 1) * 0.15 + danger * 0.35 + stress * 0.2 + Math.max(0, -this.cohesion) / 300, 0, 1)
  }
  /** what an item in someone's hands can do */
  itemUse(p: Person): { item: string; use: ItemUse } | null {
    for (const item of p.items) {
      const use = this.sc.items?.find((u) => item.toLowerCase().startsWith(u.item.toLowerCase()))
      if (use) return { item, use }
    }
    return null
  }

  private push(l: Line) {
    this.lines.push(l)
    this.version++
  }
  private remember(p: Person, text: string) {
    p.memory.push(`[${this.clock}] ${text}`)
    if (p.memory.length > 40) p.memory.shift()
  }
  get banner(): Banner | null {
    return this.banners[this.banners.length - 1] ?? null
  }
  private show(b: Banner) {
    this.banners.push(b)
    this.stepBanners++
  }
  private highlight(text: string) {
    this.highlights.push(text)
  }
  private emit(text: string, kind: Fx['kind'], who?: number, place?: string) {
    this.fx.push({ id: this.fxId++, text, kind, who, place, at: Date.now() })
    if (this.fx.length > 60) this.fx.splice(0, this.fx.length - 60)
  }
  private link(a: Person, b: Person, kind: Link['kind']) {
    this.links.push({ a: a.id, b: b.id, kind, at: Date.now() })
    if (this.links.length > 20) this.links.shift()
  }
  private trust(p: Person, o: Person, d: number, note?: string) {
    const cur = p.opinions[o.id] ?? { trust: 0, note: '' }
    cur.trust = clamp(cur.trust + d, -100, 100)
    if (note) cur.note = note
    p.opinions[o.id] = cur
  }
  private statIcon(id: string): string {
    return this.sc.stats.find((s) => s.id === id)?.icon ?? ''
  }
  /** human-readable chips for a set of stat changes */
  private chips(fx: Record<string, number>): string[] {
    return Object.entries(fx).filter(([, v]) => Math.abs(v) >= 0.5).map(([k, v]) => `${sign(v)} ${this.statIcon(k)}`)
  }
  private tones(fx: Record<string, number>): ('up' | 'down')[] {
    return Object.entries(fx).filter(([, v]) => Math.abs(v) >= 0.5).map(([k, v]) => ((this.sc.stats.find((s) => s.id === k)?.good === 'high') === v > 0 ? 'up' : 'down'))
  }

  // ---------------------------------------------------------------- a beat

  async step(host: MindHost, onUpdate: () => void = () => {}): Promise<void> {
    if (this.over || this.busy) return
    this.busy = true
    try {
      this.beat++
      this.stepBanners = 0
      this.prev = { ...this.stats }
      this.doneThisBeat.clear()
      this.helping.clear()
      const act = this.beat / this.sc.beats < 0.34 ? 1 : this.beat / this.sc.beats < 0.75 ? 2 : 3
      if (act !== this.act) {
        this.act = act
        const title = `Act ${['I', 'II', 'III'][act - 1]} · ${this.actName(act)}`
        this.push({ beat: this.beat, kind: 'system', text: title })
        this.show({ kind: 'act', title, text: act === 3 ? 'Everything comes to a head.' : 'Things are getting harder.', at: Date.now() })
      }
      this.push({ beat: this.beat, kind: 'system', text: this.clock })
      const due = (this.sc.dilemmas ?? []).find((d) => !this.used.has(d.id) && this.beat >= Math.max(2, Math.round(d.at * this.sc.beats)))
      if (due) this.openDilemma(due)
      else this.maybeEvent()
      this.maybeClash()
      onUpdate()

      const active = this.living
      for (const p of active) p.thinking = host.canThink(p)
      onUpdate()
      const decisions = await Promise.all(active.map(async (p) => {
        let d: Decision | null = null
        if (host.canThink(p)) {
          try {
            const text = await host.think(p, systemFor(this.sc), buildPersonPrompt(this, p), DECISION_SCHEMA)
            d = parse<Decision>(text)
            if (!d) p.errors++
          } catch {
            p.errors++
          }
        }
        p.thinking = false
        onUpdate()
        return { p, d: d ? sanitize(d) : this.instinct(p) }
      }))

      // who is helping whom (helpers lend their hands before the work is done)
      for (const { p, d } of decisions) {
        if (d.move !== 'help') continue
        const t = this.byName(d.target || d.say_to, p)
        if (t && this.alive(t)) this.helping.set(t.id, [...(this.helping.get(t.id) ?? []), p.id])
      }
      // talkative people get their words in first
      decisions.sort((a, b) => b.p.traits.extraversion - a.p.traits.extraversion)
      const summary: string[] = []
      for (const { p, d } of decisions) summary.push(this.resolve(p, d))
      if (this.pending) summary.push(this.closeDilemma())
      this.upkeep()
      onUpdate()

      if (host.canDirect()) {
        try {
          const text = await host.direct(DIRECTOR_SYSTEM, buildDirectorPrompt(this, summary), DIRECTOR_SCHEMA)
          const r = parse<{ narration: string; adjustments: { stat: string; delta: number }[]; personal: { name: string; mood: number; stress: number }[] }>(text)
          if (r?.narration) {
            this.narrations.push(r.narration)
            this.push({ beat: this.beat, kind: 'narration', text: r.narration })
            for (const p of this.people) this.remember(p, `(What happened) ${r.narration.slice(0, 200)}`)
          }
          for (const adj of r?.adjustments ?? []) if (adj && adj.stat in this.stats) this.bump(adj.stat, clamp(Number(adj.delta) || 0, -8, 8))
          for (const pc of r?.personal ?? []) {
            const p = pc && this.byName(String(pc.name ?? ''))
            if (!p) continue
            p.mood = clamp(p.mood + clamp(Number(pc.mood) || 0, -10, 10), 0, 100)
            p.stress = clamp(p.stress + clamp(Number(pc.stress) || 0, -10, 10), 0, 100)
          }
        } catch { /* narration is optional */ }
      }
      this.checkEnd(host)
    } finally {
      this.busy = false
      this.version++
      onUpdate()
    }
  }

  private maybeEvent() {
    const p = [0.2, 0.32, 0.42][this.act - 1]
    if (this.beat < 2 || Math.random() > p) return
    const deck = this.sc.events.filter((e) => !this.used.has(e.id) && (e.from ?? 0) <= this.beat)
    if (!deck.length) return
    const e = deck[Math.floor(Math.random() * deck.length)]
    this.used.add(e.id)
    this.push({ beat: this.beat, kind: 'event', text: e.text })
    const applied: Record<string, number> = {}
    for (const k in e.effects ?? {}) applied[k] = this.bump(k, e.effects![k])
    for (const p of this.living) {
      if (e.all) this.selfFx(p, e.all)
      this.remember(p, `EVENT: ${e.text}`)
      p.emote = '❗'
      p.emoteUntil = Date.now() + 3500
    }
    this.show({ kind: 'event', title: 'Something happens', text: e.text, at: Date.now(), chips: this.chips(applied), tones: this.tones(applied) })
  }

  /** When bad blood runs deep, it boils over. */
  private maybeClash() {
    if (this.act < 2 || Math.random() > 0.3) return
    let worst: [Person, Person] | null = null, wt = 1e9
    for (const p of this.living) for (const o of this.living) {
      if (o.id <= p.id) continue
      const t = (p.opinions[o.id]?.trust ?? 0) + (o.opinions[p.id]?.trust ?? 0)
      if (t < wt) { wt = t; worst = [p, o] }
    }
    if (!worst || wt > -45) return
    const [a, b] = worst
    const text = `Tempers snap between ${a.name} and ${b.name}: a row that everyone hears.`
    this.push({ beat: this.beat, kind: 'event', text })
    for (const p of [a, b]) this.selfFx(p, { stress: 9, mood: -5 })
    this.trust(a, b, -5)
    this.trust(b, a, -5)
    this.link(a, b, 'hot')
    for (const p of this.living) this.remember(p, text)
    this.emit('💥', 'bad', a.id)
    this.emit('💥', 'bad', b.id)
    this.highlight(`${a.name} and ${b.name} had a blazing row (${this.clock}).`)
  }

  // ---------------------------------------------------------------- dilemmas

  private openDilemma(d: Dilemma) {
    this.used.add(d.id)
    this.pending = d
    for (const p of this.people) p.vote = ''
    const text = `${d.finale ? 'THE FINAL CHOICE — ' : 'A CHOICE — '}${d.title}: ${d.text} (${d.options.map((o) => o.label).join(' / ')})`
    this.push({ beat: this.beat, kind: 'event', text })
    for (const p of this.living) {
      this.remember(p, text)
      p.emote = '⚖️'
      p.emoteUntil = Date.now() + 4000
    }
    this.show({ kind: 'dilemma', title: d.title, text: d.text, at: Date.now(), chips: d.options.map((o) => o.label) })
  }

  private matchOption(d: Dilemma, v: string): string {
    const t = v.trim().toLowerCase()
    if (!t) return ''
    return (d.options.find((o) => o.id === t) ?? d.options.find((o) => o.label.toLowerCase() === t) ?? d.options.find((o) => t.includes(o.label.toLowerCase().split(' ').slice(0, 2).join(' ')) || o.label.toLowerCase().includes(t)))?.id ?? ''
  }

  private instinctVote(p: Person, d: Dilemma): string {
    let best = d.options[0].id, bs = -1e9
    for (const o of d.options) {
      let s = 0
      for (const k in o.effects ?? {}) {
        const def = this.sc.stats.find((x) => x.id === k)
        if (!def) continue
        const need = 1 - this.frac(def)
        s += (def.good === 'high' ? 1 : -1) * o.effects![k] * (0.4 + need) * (def.critical ? 1.4 : 1) * 0.15
      }
      for (const [tr, w] of Object.entries(o.lean ?? {})) s += (p.traits[tr as keyof Person['traits']] - 0.5) * (w ?? 0) * 6
      s += (o.all?.mood ?? 0) * 0.05 - (o.all?.stress ?? 0) * p.traits.neuroticism * 0.08
      s += Math.random() * 1.5
      if (s > bs) { bs = s; best = o.id }
    }
    // the agreeable drift toward whoever they trust most
    if (Math.random() < p.traits.agreeableness * 0.4) {
      const friend = this.living.filter((o) => o !== p && o.vote).sort((a, b) => (p.opinions[b.id]?.trust ?? 0) - (p.opinions[a.id]?.trust ?? 0))[0]
      if (friend && (p.opinions[friend.id]?.trust ?? 0) > 20) return friend.vote
    }
    return best
  }

  private closeDilemma(): string {
    const d = this.pending!
    this.pending = null
    const voters = this.living
    for (const p of voters) if (!p.vote || !d.options.some((o) => o.id === p.vote)) p.vote = this.instinctVote(p, d)
    // a vote counts a little more when the voter is trusted
    const weight = (p: Person) => 1 + voters.filter((o) => o !== p).reduce((a, o) => a + (o.opinions[p.id]?.trust ?? 0), 0) / Math.max(1, voters.length - 1) / 400
    const tally = d.options.map((o) => ({ o, ids: voters.filter((p) => p.vote === o.id).map((p) => p.id), w: voters.filter((p) => p.vote === o.id).reduce((a, p) => a + weight(p), 0) }))
    tally.sort((a, b) => b.ids.length - a.ids.length || b.w - a.w)
    const win = tally[0].o
    const split = tally.map((t) => t.ids.length).filter((n) => n > 0).join('–')
    const applied: Record<string, number> = {}
    // a single decision can swing things, but it can't win the game on its own
    for (const k in win.effects ?? {}) applied[k] = this.bump(k, win.effects![k] * (this.sc.win?.stat === k && win.effects![k] > 0 ? 0.5 : 1))
    if (win.all) for (const p of voters) this.selfFx(p, win.all)
    const losers = voters.filter((p) => p.vote !== win.id)
    const unanimous = losers.length === 0
    const text = `They chose: ${win.label}${unanimous ? ' — unanimously' : ` (${split})`}. ${win.outcome}`
    this.push({ beat: this.beat, kind: 'event', text })
    for (const p of voters) {
      this.remember(p, `${d.title}: I voted "${d.options.find((o) => o.id === p.vote)?.label}". The group chose "${win.label}".`)
      if (p.vote !== win.id) { this.selfFx(p, { stress: 6, mood: -4 }); p.emote = '😤' } else { this.selfFx(p, { mood: 2 }); p.emote = unanimous ? '🤝' : '✊' }
      p.emoteUntil = Date.now() + 3500
      for (const o of voters) {
        if (o === p) continue
        if (o.vote === p.vote) this.trust(p, o, 4)
        else this.trust(p, o, -Math.round(4 + (1 - p.traits.agreeableness) * 6), `voted against me on "${d.title}"`)
      }
    }
    if (unanimous) for (const p of voters) for (const o of voters) if (o !== p) this.trust(p, o, 3)
    this.show({
      kind: 'vote', title: d.title, text: win.outcome, at: Date.now(), chips: this.chips(applied), tones: this.tones(applied),
      votes: d.options.map((o) => ({ option: o.id, label: o.label, ids: voters.filter((p) => p.vote === o.id).map((p) => p.id), chosen: o.id === win.id })),
    })
    this.highlight(`${d.finale ? 'The final choice' : d.title}: ${win.label}${unanimous ? ', all together' : `, ${split}${losers.length ? ` — ${losers.map((p) => p.name).join(' and ')} outvoted` : ''}`}.`)
    for (const p of voters) p.vote = ''
    return `GROUP DECISION on "${d.title}": ${win.label} (${split})`
  }

  // ---------------------------------------------------------------- stats & people

  /** change a shared stat; returns the change actually made */
  private bump(stat: string, d: number): number {
    const def = this.sc.stats.find((s) => s.id === stat)
    if (!def) return 0
    const before = this.stats[stat]
    this.stats[stat] = clamp(Math.round((before + d) * 10) / 10, 0, def.max)
    return this.stats[stat] - before
  }

  private selfFx(p: Person, fx: SelfFx) {
    if (fx.energy) p.energy = clamp(p.energy + fx.energy, 0, 100)
    if (fx.mood) p.mood = clamp(p.mood + fx.mood, 0, 100)
    if (fx.stress) p.stress = clamp(p.stress + fx.stress, 0, 100)
    if (fx.health) p.health = clamp(p.health + fx.health, 0, 100)
    if (fx.score && this.sc.score) p.score = clamp(p.score + fx.score, 0, 100)
  }

  /** A secret comes out — told, or torn out. */
  private reveal(p: Person, how: 'confessed' | 'exposed', by?: Person) {
    if (p.secretOut) return
    p.secretOut = true
    p.secretHow = how
    const secret = thirdPerson(p.role.secret, p.name)
    const text = how === 'confessed' ? `${p.name} confesses: ${secret}` : `${by ? `${by.name} drags it out of ${p.name}` : `It comes out about ${p.name}`}: ${secret}`
    this.push({ beat: this.beat, kind: 'event', who: p.id, text })
    for (const o of this.living) {
      if (o === p) continue
      const d = how === 'confessed' ? Math.round((o.traits.agreeableness - 0.4) * 22) : -Math.round(10 + (1 - o.traits.agreeableness) * 10)
      this.trust(o, p, d, how === 'confessed' ? 'told us the truth about themselves' : 'was hiding something from us')
      if (by && o !== by && how === 'exposed') this.trust(o, by, o.traits.agreeableness > 0.6 ? -4 : 2)
      this.remember(o, text)
      o.emote = how === 'confessed' ? '😮' : '😱'
      o.emoteUntil = Date.now() + 3500
    }
    this.remember(p, how === 'confessed' ? 'I told them my secret.' : 'My secret is out. Everyone knows.')
    if (how === 'confessed') this.selfFx(p, { stress: -20, mood: 6 })
    else this.selfFx(p, { stress: 18, mood: -12 })
    this.emit(how === 'confessed' ? '🤫 confessed' : '🤫 exposed!', 'social', p.id)
    this.show({ kind: 'reveal', title: how === 'confessed' ? `${p.name}'s confession` : `${p.name}'s secret is out`, text: secret, at: Date.now() })
    this.highlight(how === 'confessed' ? `${p.name} confessed: ${secret}` : `${p.name}'s secret was exposed${by ? ` by ${by.name}` : ''}: ${secret}`)
  }

  private resolve(p: Person, d: Decision): string {
    p.thought = d.thought
    p.feeling = d.feeling || p.feeling
    const parts: string[] = []
    if (this.pending && d.vote) p.vote = this.matchOption(this.pending, d.vote)
    // stance
    if (this.sc.stances && d.stance) {
      const s = this.sc.stances.find((x) => x.toLowerCase() === d.stance.toLowerCase()) ?? this.sc.stances.find((x) => d.stance.toLowerCase().includes(x.toLowerCase().split(' ')[0]))
      if (s && s !== p.stance) {
        p.stance = s
        this.push({ beat: this.beat, kind: 'action', who: p.id, text: `${p.name} now stands at: ${s}.` })
        parts.push(`changed position to "${s}"`)
      }
    }
    const social = (SOCIAL as readonly string[]).includes(d.move)
    const target = social ? this.pickTarget(p, d) : undefined
    // movement: a move tied to a place takes you there; reaching out to someone takes you to them
    const move = this.sc.moves.find((m) => m.id === d.move)
    let dest = ''
    if (move?.at && !move.at.includes(p.place)) dest = move.at.includes(d.go_to) ? d.go_to : move.at[0]
    else if (target && ['comfort', 'confront', 'persuade', 'help', 'give'].includes(d.move)) dest = target.place
    else if ((d.move === 'go' || (!move && !social)) && d.go_to && this.sc.places.some((x) => x.id === d.go_to)) dest = d.go_to
    if (dest && dest !== p.place) {
      p.place = dest
      parts.push(`went to the ${this.placeName(dest)}`)
    }
    // speech
    if (d.say) {
      const to = d.say_to && !/^(everyone|all|nobody|)$/i.test(d.say_to.trim()) ? this.byName(d.say_to, p) : undefined
      p.lastSay = d.say.slice(0, 280)
      p.lastSayTo = to?.name ?? ''
      p.bubbleUntil = Date.now() + 5500 + d.say.length * 45
      this.push({ beat: this.beat, kind: 'say', who: p.id, to: to ? to.id : 'all', place: p.place, text: p.lastSay })
      for (const o of this.people) {
        if (o === p || !this.alive(o)) continue
        if (o.place === p.place || o === to) this.remember(o, `${p.name} said${to ? ` to ${to === o ? 'me' : to.name}` : ''}: "${p.lastSay}"`)
      }
      this.remember(p, `I said${to ? ` to ${to.name}` : ''}: "${p.lastSay}"`)
      parts.push(`said "${p.lastSay}"${to ? ` to ${to.name}` : ''}`)
    } else p.lastSay = ''
    // opinions
    for (const op of d.opinions ?? []) {
      const o = this.byName(op.name ?? '', p)
      if (!o) continue
      this.trust(p, o, clamp(Number(op.delta) || 0, -25, 25), op.why ? String(op.why).slice(0, 120) : undefined)
    }
    // action
    if (move) parts.push(this.work(p, move, d.detail))
    else if (social) parts.push(this.socialAct(p, d.move, target))
    else if (d.move === 'rest') {
      this.selfFx(p, { energy: 22, stress: -6 })
      p.lastAction = 'rested'
      parts.push('rested')
      this.emit('💤', 'info', p.id)
    } else {
      p.lastAction = d.move === 'go' ? 'moved' : 'talked'
      if (d.say) this.selfFx(p, { mood: 2 })
    }
    if (this.pending && p.vote) parts.push(`voted "${this.pending.options.find((o) => o.id === p.vote)?.label}"`)
    return `${p.name} (${p.role.title}): ${parts.join('; ') || 'did little'}${d.detail ? ` — "${d.detail.slice(0, 140)}"` : ''}`
  }

  private pickTarget(p: Person, d: Decision): Person | undefined {
    const named = this.byName(d.target || d.say_to, p)
    if (named && this.alive(named)) return named
    if (d.move === 'confess' || d.move === 'use') return undefined
    const here = this.living.filter((o) => o !== p && o.place === p.place)
    const pool = here.length ? here : this.living.filter((o) => o !== p)
    return pool[Math.floor(Math.random() * pool.length)]
  }

  private socialAct(p: Person, kind: string, t: Person | undefined): string {
    p.emoteUntil = Date.now() + 3000
    switch (kind) {
      case 'comfort': {
        if (!t) break
        const ok = Math.random() < 0.55 + p.traits.agreeableness * 0.35 + (t.opinions[p.id]?.trust ?? 0) / 300
        p.lastAction = `comforted ${t.name}`
        this.link(p, t, 'warm')
        if (ok) {
          this.selfFx(t, { mood: 9, stress: -12 })
          this.selfFx(p, { mood: 3, energy: -3 })
          this.trust(t, p, 9, 'was there for me')
          this.trust(p, t, 3)
          p.contribution += 0.6
          p.emote = '🤗'
          this.emit('💗 −12 stress', 'social', t.id)
          this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: `${p.name} comforts ${t.name}.` })
          this.remember(t, `${p.name} comforted me.`)
          return `comforted ${t.name}`
        }
        this.trust(t, p, -2)
        p.emote = '😶'
        this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: `${p.name} tries to comfort ${t.name}, who pulls away.` })
        return `tried to comfort ${t.name} (rebuffed)`
      }
      case 'confront': {
        if (!t) break
        p.lastAction = `confronted ${t.name}`
        this.selfFx(t, { stress: 11, mood: -4 })
        this.selfFx(p, { stress: 5 })
        this.trust(t, p, -10, 'came at me')
        this.trust(p, t, -3)
        this.link(p, t, 'hot')
        p.emote = '😠'
        t.emote = '😣'
        t.emoteUntil = Date.now() + 3000
        this.emit('⚡', 'bad', t.id)
        this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: `${p.name} confronts ${t.name}.` })
        this.remember(t, `${p.name} confronted me.`)
        // pressure can crack a secret open
        if (!t.secretOut && Math.random() < 0.08 + t.stress / 220 + p.traits.openness * 0.08 + p.traits.extraversion * 0.06) {
          this.reveal(t, 'exposed', p)
          return `confronted ${t.name} — and exposed their secret`
        }
        return `confronted ${t.name}`
      }
      case 'persuade': {
        if (!t) break
        p.lastAction = `worked on ${t.name}`
        this.link(p, t, 'sway')
        if (!this.sc.stances || t.stance === p.stance) {
          this.trust(t, p, 2)
          return `talked ${t.name} round`
        }
        const chance = 0.18 + p.traits.extraversion * 0.22 + Math.max(0, t.opinions[p.id]?.trust ?? 0) / 220 + t.traits.agreeableness * 0.12 - t.traits.conscientiousness * 0.08
        if (Math.random() < clamp(chance, 0.05, 0.85)) {
          t.stance = p.stance
          p.emote = '🗣️'
          this.emit(`→ ${p.stance}`, 'social', t.id)
          this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: `${p.name} wins ${t.name} over: ${t.name} now stands at ${p.stance}.` })
          this.remember(t, `${p.name} changed my mind: ${p.stance}.`)
          this.highlight(`${p.name} persuaded ${t.name} to "${p.stance}".`)
          return `persuaded ${t.name} to "${p.stance}"`
        }
        this.trust(t, p, -3)
        this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: `${p.name} argues hard with ${t.name}, who will not budge.` })
        return `tried to persuade ${t.name} (failed)`
      }
      case 'help': {
        if (!t) break
        p.lastAction = `helped ${t.name}`
        this.link(p, t, 'help')
        this.selfFx(p, { energy: -6 })
        this.trust(t, p, 4, 'pitched in')
        p.emote = '🤝'
        return `helped ${t.name}`
      }
      case 'confess': {
        p.lastAction = 'confessed'
        if (!p.secretOut) { this.reveal(p, 'confessed'); return 'confessed their secret' }
        return 'opened up'
      }
      case 'give': {
        if (!t || !p.items.length) break
        const item = p.items.shift()!
        t.items.push(item)
        p.lastAction = `gave ${t.name} the ${item}`
        this.trust(t, p, 8, `gave me their ${item}`)
        this.link(p, t, 'warm')
        p.emote = '🎁'
        this.emit(`🎁 ${item}`, 'social', t.id)
        this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: `${p.name} gives ${t.name} the ${item}.` })
        return `gave ${t.name} the ${item}`
      }
      case 'use': {
        const u = this.itemUse(p)
        if (!u) break
        const applied: Record<string, number> = {}
        for (const k in u.use.effects) applied[k] = this.bump(k, u.use.effects[k])
        if (u.use.self) this.selfFx(p, u.use.self)
        if (u.use.heal) for (const o of this.living) if (o.place === p.place) { this.selfFx(o, { health: u.use.heal, stress: -4 }); if (o !== p) this.trust(o, p, 5, 'patched me up') }
        if (u.use.once) p.items = p.items.filter((x) => x !== u.item)
        p.lastAction = u.use.verb
        p.contribution += 1
        p.emote = '✨'
        for (const c of this.chips(applied)) this.emit(c, 'good', p.id)
        if (u.use.heal) this.emit(`❤️ +${u.use.heal}`, 'good', p.id)
        this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: `${p.name} ${u.use.verb}.` })
        this.remember(p, `I ${u.use.verb}.`)
        if (u.use.once) this.highlight(`${p.name} ${u.use.verb}.`)
        return u.use.verb
      }
    }
    p.lastAction = 'talked'
    return 'hesitated'
  }

  private work(p: Person, m: Move, detail: string): string {
    let chance = m.chance ?? 0.92
    chance *= 0.65 + p.energy / 280
    if (m.trait) chance += (p.traits[m.trait] - 0.5) * (m.trait === 'neuroticism' ? -0.3 : 0.3)
    if (p.stress > 75) chance -= 0.1
    // working alongside someone helps — more so in a close group
    const coh = 1 + this.cohesion / 300
    const side = this.people.filter((o) => o !== p && this.alive(o) && o.place === p.place && o.lastAction === m.verb).length
    const helpers = (this.helping.get(p.id) ?? []).map((id) => this.people.find((o) => o.id === id)!).filter((o) => o && this.alive(o))
    chance += (side * 0.07 + helpers.length * 0.1) * coh
    const ok = Math.random() < clamp(chance, 0.05, 0.98)
    // four people doing the same thing don't achieve four times as much
    const same = this.doneThisBeat.get(m.id) ?? 0
    this.doneThisBeat.set(m.id, same + 1)
    const scale = ((0.7 + Math.random() * 0.6) / (1 + same * 0.7)) * (1 + helpers.length * 0.18 * coh)
    if (ok) {
      const applied: Record<string, number> = {}
      for (const k in m.effects) {
        const towardWin = this.sc.win?.stat === k && m.effects[k] > 0
        applied[k] = this.bump(k, m.effects[k] * scale * (towardWin ? 0.6 : 1))
      }
      if (m.self) this.selfFx(p, m.self)
      p.emote = m.effects && Object.values(m.effects).some((v) => v > 8) ? '✨' : '✔️'
      p.contribution += 1
      for (const h of helpers) { h.contribution += 0.5; this.trust(p, h, 3) }
      let shown = 0
      for (const [k, v] of Object.entries(applied)) {
        if (Math.abs(v) < 0.5) continue
        const def = this.sc.stats.find((s) => s.id === k)!
        this.emit(`${sign(v)} ${def.icon}`, (def.good === 'high') === v > 0 ? 'good' : 'bad', p.id)
        shown++
      }
      if (!shown) this.emit('✔', 'good', p.id)
    } else {
      for (const k in m.fail ?? {}) this.bump(k, m.fail![k])
      this.selfFx(p, { energy: m.self?.energy ?? -6, ...(m.failSelf ?? {}), mood: (m.failSelf?.mood ?? 0) - 4 })
      p.emote = '💢'
      this.emit('✗', 'bad', p.id)
      for (const o of this.living) if (o !== p && o.place === p.place && o.traits.agreeableness < 0.45) this.trust(o, p, -3, `messed up when they ${m.verb}`)
    }
    p.emoteUntil = Date.now() + 3000
    p.lastAction = m.verb
    const with_ = helpers.length ? ` with ${helpers.map((h) => h.name).join(' and ')}'s help` : ''
    const text = `${p.name} ${m.verb}${with_}${ok ? '' : ' — but it went badly'}.`
    this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: detail ? `${text} (${detail.slice(0, 120)})` : text })
    this.remember(p, ok ? `I ${m.verb}${with_}. It worked.` : `I ${m.verb}, but it went badly.`)
    return `${m.verb}${with_} (${ok ? 'success' : 'failed'})`
  }

  private upkeep() {
    for (const k in this.sc.upkeep) this.bump(k, this.sc.upkeep[k] * (this.act === 3 ? 1.15 : 1))
    for (const p of this.people) {
      if (!this.alive(p)) continue
      this.selfFx(p, { energy: -4 })
      // the shared situation weighs on everyone
      let pressure = 0
      for (const s of this.sc.stats) {
        const bad = 1 - this.frac(s)
        if (s.critical && bad > 0.75) pressure += 4
        if (s.critical && bad >= 0.999) {
          // a critical stat at its worst: people get hurt
          if (this.sc.category === 'Survival') this.selfFx(p, { health: -14 })
          pressure += 4
        }
      }
      // strain frays tempers: the stressed and prickly lose patience with those around them
      if (p.stress > 55) for (const o of this.living) if (o !== p && o.place === p.place && Math.random() < 0.5) this.trust(p, o, -Math.round((1 - p.traits.agreeableness) * 3.2 * Math.random() + (p.traits.neuroticism > 0.65 ? 1 : 0)))
      // a kept secret gnaws at the anxious
      if (!p.secretOut && p.traits.neuroticism > 0.55) pressure += 1
      // a close group carries its members; a broken one wears them down
      const coh = this.cohesion
      this.selfFx(p, { stress: pressure - 1 - (coh > 25 ? 1 : 0) + (coh < -25 ? 1 : 0), mood: -Math.round(pressure / 2) + (p.stress < 40 ? 1 : 0) })
      if (p.energy < 10) this.selfFx(p, { health: -4, mood: -3 })
      if (p.health <= 0) {
        const text = this.sc.category === 'Survival' ? `${p.name} has collapsed and can no longer go on.` : `${p.name} has had enough and walks out.`
        this.push({ beat: this.beat, kind: 'event', who: p.id, text })
        this.highlight(text)
        this.show({ kind: 'event', title: this.sc.category === 'Survival' ? 'A collapse' : 'A walk-out', text, at: Date.now() })
        for (const o of this.people) if (o !== p) { this.remember(o, `${p.name} is gone.`); o.mood = clamp(o.mood - 15, 0, 100) }
      }
    }
  }

  private instinct(p: Person): Decision {
    const here = this.living.filter((o) => o !== p && o.place === p.place)
    const others = this.living.filter((o) => o !== p)
    const base: Decision = { stance: '', thought: '', feeling: p.stress > 70 ? 'strained' : p.mood > 65 ? 'hopeful' : 'focused', say: '', say_to: '', move: 'rest', go_to: '', target: '', detail: '', vote: '', opinions: [] }
    if (this.pending) base.vote = this.instinctVote(p, this.pending)
    // pick the move that helps the worst stat, flavoured by temperament
    let best: Move | null = null
    let bestScore = -1e9
    for (const m of this.sc.moves) {
      let s = 0
      for (const k in m.effects) {
        const def = this.sc.stats.find((x) => x.id === k)
        if (!def) continue
        const need = 1 - this.frac(def)
        s += (def.good === 'high' ? m.effects[k] : -m.effects[k]) * (0.5 + need) * (def.critical ? 1.5 : 1)
      }
      if (m.trait) s += (p.traits[m.trait] - 0.5) * 6
      if ((m.self?.energy ?? 0) < -15 && p.energy < 30) s -= 20
      // people split the work rather than all doing the same thing
      s -= this.people.filter((o) => o !== p && o.lastAction === m.verb).length * 4
      s += Math.random() * 12
      if (s > bestScore) { bestScore = s; best = m }
    }
    let move = p.energy < 18 ? 'rest' : best?.id ?? 'rest'
    let target = ''
    // people, not just work
    const r = Math.random()
    const struggling = here.filter((o) => o.stress > 55).sort((a, b) => b.stress - a.stress)[0]
    const enemy = others.filter((o) => (p.opinions[o.id]?.trust ?? 0) < -18).sort((a, b) => (p.opinions[a.id]?.trust ?? 0) - (p.opinions[b.id]?.trust ?? 0))[0]
    const u = this.itemUse(p)
    const busy = others.filter((o) => this.sc.moves.some((m) => m.verb === o.lastAction))
    if (move !== 'rest') {
      if (struggling && r < 0.15 + p.traits.agreeableness * 0.3) { move = 'comfort'; target = struggling.name }
      else if (enemy && r < 0.12 + (1 - p.traits.agreeableness) * 0.2) { move = 'confront'; target = enemy.name }
      else if (this.sc.stances && !/undecided/i.test(p.stance) && r < 0.12 + p.traits.extraversion * 0.25) {
        const other = others.filter((o) => o.stance !== p.stance).sort((a, b) => (b.opinions[p.id]?.trust ?? 0) - (a.opinions[p.id]?.trust ?? 0))[0]
        if (other) { move = 'persuade'; target = other.name }
      } else if (!p.secretOut && p.stress > 62 && r < 0.1 + p.traits.agreeableness * 0.12) move = 'confess'
      else if (u && r < 0.35) {
        const helpful = Object.entries(u.use.effects).some(([k, v]) => { const def = this.sc.stats.find((s) => s.id === k); return def && (def.good === 'high' ? v > 0 : v < 0) && this.frac(def) < 0.65 })
        if (helpful || u.use.heal) move = 'use'
      } else if (busy.length && r < 0.1 + p.traits.agreeableness * 0.08) { move = 'help'; target = busy[Math.floor(Math.random() * busy.length)].name }
    }
    const listener = here[Math.floor(Math.random() * here.length)]
    const talk = here.length > 0 && Math.random() < 0.35 + p.traits.extraversion * 0.4
    const line = this.sc.lines[Math.floor(Math.random() * this.sc.lines.length)].replace('{name}', listener?.name ?? 'everyone')
    let stance = ''
    if (this.sc.stances && Math.random() < 0.16 - p.traits.conscientiousness * 0.08) {
      // instinct leans one way or the other — and sometimes follows the room
      const decided = this.sc.stances.filter((x) => !/undecided/i.test(x))
      const room = here.map((o) => o.stance).filter((x) => decided.includes(x))
      stance = room.length && Math.random() < p.traits.agreeableness ? room[Math.floor(Math.random() * room.length)] : decided[Math.floor(Math.random() * decided.length)]
    }
    return {
      ...base, stance, move, target,
      say: talk ? line : '', say_to: talk && line.includes(listener?.name ?? '§') ? listener!.name : '',
      opinions: listener && Math.random() < 0.3 ? [{ name: listener.name, delta: Math.round((p.traits.agreeableness - 0.4) * 10), why: 'how they have been lately' }] : [],
    }
  }

  // ---------------------------------------------------------------- endings

  private checkEnd(host: MindHost) {
    const sc = this.sc
    let tier: Ending['tier'] | null = null
    let title = ''
    if (sc.win && this.stats[sc.win.stat] >= sc.win.at) { tier = 'triumph'; title = sc.win.text }
    for (const s of sc.stats) {
      if (!s.critical || tier) continue
      const atWorst = s.good === 'high' ? this.stats[s.id] <= 0 : this.stats[s.id] >= s.max
      if (atWorst) { tier = 'disaster'; title = `${s.label} ran out.` }
    }
    if (!tier && this.people.every((p) => !this.alive(p))) { tier = 'disaster'; title = 'No one is left.' }
    if (!tier && sc.stances) {
      const s0 = this.living.map((p) => p.stance)
      if (this.beat >= Math.ceil(sc.beats * 0.5) && s0.length && s0.every((x) => x === s0[0]) && !/undecided/i.test(s0[0])) { tier = this.score() > 0.62 && this.cohesion > -10 ? 'success' : this.score() > 0.45 ? 'mixed' : 'failure'; title = `Unanimous: ${s0[0]}.` }
    }
    if (!tier && this.beat >= sc.beats) {
      const score = this.score()
      tier = score > 0.74 ? 'success' : score > 0.55 ? 'mixed' : score > 0.38 ? 'failure' : 'disaster'
      title = sc.stances ? (new Set(this.living.map((p) => p.stance)).size > 1 ? 'Time ran out without agreement.' : `Time ran out — all agreed: ${this.living[0]?.stance}.`) : 'Time is up.'
    }
    if (!tier) return
    this.over = true
    this.pending = null
    const mvp = [...this.living].sort((a, b) => b.contribution - a.contribution)[0] ?? null
    const fates = this.people.map((p) => ({ id: p.id, text: this.ruleFate(p, mvp) }))
    this.ending = { tier, title, text: this.ruleSummary(tier, mvp), fates, score: Math.round(this.score() * 100), mvp: mvp?.id ?? null, cohesion: Math.round(this.cohesion), highlights: this.highlights.slice(-8) }
    this.push({ beat: this.beat, kind: 'event', text: `THE END — ${title}` })
    if (host.canDirect()) void this.epilogue(host)
  }

  /** 0..1: how well things stand across all stakes. */
  overall(): number {
    let t = 0
    for (const s of this.sc.stats) t += this.frac(s)
    return t / this.sc.stats.length
  }
  /** 0..1: the whole outcome — stakes, the goal, the bonds and who is still here. */
  score(): number {
    const win = this.sc.win ? clamp(this.stats[this.sc.win.stat] / this.sc.win.at, 0, 1) : this.overall()
    const alive = this.living.length / this.people.length
    return clamp(this.overall() * 0.55 + win * 0.2 + ((this.cohesion + 100) / 200) * 0.15 + alive * 0.1, 0, 1)
  }

  private ruleSummary(tier: Ending['tier'], mvp: Person | null): string {
    const best = [...this.sc.stats].sort((a, b) => this.frac(b) - this.frac(a))[0]
    const worst = [...this.sc.stats].sort((a, b) => this.frac(a) - this.frac(b))[0]
    const words = { triumph: 'Against the odds, they made it.', success: 'They came through it.', mixed: 'They got through — just about, and not without cost.', failure: 'It did not go the way they hoped.', disaster: 'It ended badly.' }[tier]
    const coh = this.cohesion
    const bond = coh > 30 ? 'They came out of it closer than they went in.' : coh < -20 ? 'By the end they could barely stand each other.' : 'They leave as something between strangers and friends.'
    const secrets = this.people.filter((p) => p.secretOut)
    return `${words} Their greatest strength in the end was ${best.label.toLowerCase()}; their weakest, ${worst.label.toLowerCase()}. ${bond}${mvp ? ` ${mvp.name} carried more than their share.` : ''}${secrets.length ? ` ${secrets.length === 1 ? 'One secret' : `${secrets.length} secrets`} came out along the way.` : ''}`
  }
  private frac(s: Scenario['stats'][0]): number {
    return s.good === 'high' ? this.stats[s.id] / s.max : 1 - this.stats[s.id] / s.max
  }
  private ruleFate(p: Person, mvp: Person | null): string {
    if (!this.alive(p)) return this.sc.category === 'Survival' ? 'Did not make it through.' : 'Walked away before the end.'
    const friends = Object.entries(p.opinions).filter(([, o]) => o.trust > 30).map(([id]) => this.people.find((x) => x.id === +id)?.name).filter(Boolean)
    const foes = Object.entries(p.opinions).filter(([, o]) => o.trust < -35).map(([id]) => this.people.find((x) => x.id === +id)?.name).filter(Boolean)
    const mood = p.mood > 65 ? 'Leaves changed for the better' : p.mood < 35 ? 'Leaves shaken' : 'Leaves tired but whole'
    const secret = p.secretHow === 'confessed' ? '; telling the truth cost less than they feared' : p.secretHow === 'exposed' ? '; the secret that came out will follow them home' : !p.secretOut ? '; their secret is still their own' : ''
    return `${mood}${p === mvp ? ', having held the group together' : ''}${friends.length ? `, with a new bond with ${friends.join(' and ')}` : ''}${foes.length ? `, and no love lost with ${foes.join(' or ')}` : ''}${secret}${this.sc.stances ? `. Final position: ${p.stance}` : ''}.`
  }

  private async epilogue(host: MindHost) {
    try {
      const text = await host.direct(EPILOGUE_SYSTEM, buildEpiloguePrompt(this), EPILOGUE_SCHEMA)
      const r = parse<{ title: string; story: string; fates: { name: string; fate: string }[] }>(text)
      if (!r || !this.ending) return
      this.ending = {
        ...this.ending,
        title: r.title || this.ending.title,
        text: r.story || this.ending.text,
        fates: this.people.map((p) => ({ id: p.id, text: r.fates?.find((f) => f.name?.toLowerCase().includes(p.name.toLowerCase()))?.fate ?? this.ending!.fates.find((f) => f.id === p.id)!.text })),
      }
      this.version++
    } catch { /* keep the rule-based ending */ }
  }

  /** The whole run as a play script. */
  script(): string {
    const out = [`# ${this.sc.emoji} ${this.sc.title}`, '', this.sc.premise, '', '## Cast', ...this.people.map((p) => `- **${p.name}** (${p.age}), ${p.role.title}`), '']
    for (const l of this.lines) {
      const who = l.who !== undefined ? this.people.find((p) => p.id === l.who) : undefined
      if (l.kind === 'system') out.push(`\n### ${l.text}\n`)
      else if (l.kind === 'say') out.push(`**${who?.name}**${typeof l.to === 'number' ? ` (to ${this.people.find((p) => p.id === l.to)?.name})` : ''}: ${l.text}`)
      else out.push(`*${l.text}*`)
    }
    if (this.ending) {
      out.push('', `## ${this.ending.title}`, '', this.ending.text, '', ...this.ending.fates.map((f) => `- **${this.people.find((p) => p.id === f.id)?.name}**: ${f.text}`))
      if (this.ending.highlights.length) out.push('', '### Moments', ...this.ending.highlights.map((h) => `- ${h}`))
    }
    return out.join('\n')
  }
}

/** "You are hiding…" → "Mateo is hiding…" — a secret retold about someone. */
export function thirdPerson(text: string, name: string): string {
  const IRREG: Record<string, string> = { are: 'is', were: 'was', have: 'has', do: 'does', go: 'goes', "can't": "can't", can: 'can', will: 'will', would: 'would', could: 'could', should: 'should', might: 'might', must: 'must', did: 'did', had: 'had' }
  const PAST = /^(lost|saw|knew|told|made|took|got|gave|left|felt|kept|ran|wrote|went|came|broke|burnt|sold|paid|found|thought|bought|slept|said|put|set|let|hid|forgot|fell|grew|drove|was|ate|spent|sent|met|won|began|became|flew|threw|swam|sang|stole|hit|cut|chose|froze)$/i
  const verb = (v: string) => IRREG[v.toLowerCase()] ?? (/(ed|n't)$/i.test(v) || PAST.test(v) ? v : /(s|sh|ch|x|o)$/i.test(v) ? v + 'es' : /[^aeiou]y$/i.test(v) ? v.slice(0, -1) + 'ies' : v + 's')
  const ADV = /^(already|almost|secretly|never|still|really|just|once|quietly|recently|also|always|nearly)$/i
  const lead = (t: string) => t
    .replace(/^You and\b/, `${name} and`)
    .replace(/^You (\w+) (\w+(?:'t)?)/, (m, a: string, v: string) => (ADV.test(a) ? `${name} ${a} ${verb(v)}` : m))
    .replace(/^You (\w+(?:'t)?)/, (_, v: string) => `${name} ${verb(v)}`)
    .replace(/^Your\b/, `${name}'s`)
  let out = text.split(/(?<=[.;!?]\s)/).map(lead).join('')
  if (/^You /.test(text)) out = out.replace(/\band have\b/, 'and has').replace(/\band are\b/, 'and is')
  out = out.replace(/\byou (\w+)\b/gi, (m, v: string) => (/ed$/i.test(v) || PAST.test(v) ? `they ${v}` : m))
  out = out.replace(/\b(where|when|that|if|because|while|so|but|what) you\b/gi, '$1 they')
  // later mentions: subject "you" before a verb, object "you" otherwise
  out = out.replace(/\byou (are|were|have|had|did|do|will|would|can|could|never|still|really|just|feel|knew|know|think|saw|want|own|[a-z]+ed)\b/gi, (_, v: string) => `they ${v}`)
  out = out.replace(/\byourself\b/gi, 'themselves').replace(/\byour\b/gi, 'their').replace(/\byou\b/gi, 'them')
  return out
}

function sanitize(d: Decision): Decision {
  return {
    thought: String(d.thought ?? '').slice(0, 500),
    feeling: String(d.feeling ?? '').slice(0, 40),
    say: String(d.say ?? '').slice(0, 300),
    say_to: String(d.say_to ?? ''),
    move: String(d.move ?? 'talk').trim().toLowerCase(),
    go_to: String(d.go_to ?? '').trim().toLowerCase(),
    target: String(d.target ?? ''),
    detail: String(d.detail ?? '').slice(0, 200),
    stance: String(d.stance ?? ''),
    vote: String(d.vote ?? ''),
    opinions: Array.isArray(d.opinions) ? d.opinions.slice(0, 3) : [],
  }
}

export type { Move }
