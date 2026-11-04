// One run of a scenario. Each beat: maybe something happens to them, all four
// minds decide at once (AI or instinct), then speech, movement and actions are
// resolved — actions succeed or fail by chance, effort, energy and temperament
// — the clock moves on, and an optional AI director narrates the beat.

import type { Ending, Line, Person, Scenario, Move } from './types.ts'
import { buildDirectorPrompt, buildPersonPrompt, buildEpiloguePrompt, DECISION_SCHEMA, DIRECTOR_SCHEMA, EPILOGUE_SCHEMA, systemFor, DIRECTOR_SYSTEM, EPILOGUE_SYSTEM } from './prompts.ts'

export interface Decision {
  thought: string
  feeling: string
  say: string
  say_to: string
  move: string
  go_to: string
  detail: string
  stance: string
  opinions: { name: string; delta: number; why: string }[]
}

/** How the run reaches its minds — implemented by the app (AI providers) and by tests. */
export interface MindHost {
  canThink(p: Person): boolean
  think(p: Person, system: string, user: string, schema: Record<string, unknown>): Promise<string>
  canDirect(): boolean
  direct(system: string, user: string, schema: Record<string, unknown>): Promise<string>
}

const clamp = (v: number, a: number, b: number) => (v < a ? a : v > b ? b : v)

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
  beat = 0
  lines: Line[] = []
  narrations: string[] = []
  over = false
  ending: Ending | null = null
  busy = false
  private used = new Set<string>()
  private doneThisBeat = new Map<string, number>()
  version = 0

  constructor(sc: Scenario, people: Person[]) {
    this.sc = sc
    this.people = people
    for (const s of sc.stats) this.stats[s.id] = s.start
    for (const p of people) {
      if (sc.stances) p.stance = sc.stances[sc.stances.length - 1]
      if (sc.score) p.score = sc.score.start
      for (const o of people) if (o !== p) p.opinions[o.id] = { trust: Math.round((Math.random() - 0.4) * 30), note: 'first impressions' }
    }
    this.push({ beat: 0, kind: 'system', text: sc.clock(0) })
    this.push({ beat: 0, kind: 'narration', text: sc.premise })
  }

  get clock(): string {
    return this.sc.clock(this.beat)
  }
  alive(p: Person): boolean {
    return p.health > 0
  }
  byName(name: string, not?: Person): Person | undefined {
    const n = name.trim().toLowerCase()
    if (!n) return undefined
    return this.people.find((p) => p !== not && p.name.toLowerCase() === n) ?? this.people.find((p) => p !== not && (n.includes(p.name.toLowerCase()) || p.role.title.toLowerCase().includes(n)))
  }
  placeName(id: string): string {
    return this.sc.places.find((p) => p.id === id)?.name ?? id
  }
  private push(l: Line) {
    this.lines.push(l)
    this.version++
  }
  private remember(p: Person, text: string) {
    p.memory.push(`[${this.clock}] ${text}`)
    if (p.memory.length > 40) p.memory.shift()
  }

  // ---------------------------------------------------------------- a beat

  async step(host: MindHost, onUpdate: () => void = () => {}): Promise<void> {
    if (this.over || this.busy) return
    this.busy = true
    try {
      this.beat++
      this.doneThisBeat.clear()
      this.push({ beat: this.beat, kind: 'system', text: this.clock })
      this.maybeEvent()
      onUpdate()

      const active = this.people.filter((p) => this.alive(p))
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

      // talkative people get their words in first
      decisions.sort((a, b) => b.p.traits.extraversion - a.p.traits.extraversion)
      const summary: string[] = []
      for (const { p, d } of decisions) summary.push(this.resolve(p, d))
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
    if (this.beat < 2 || Math.random() > 0.3) return
    const deck = this.sc.events.filter((e) => !this.used.has(e.id) && (e.from ?? 0) <= this.beat)
    if (!deck.length) return
    const e = deck[Math.floor(Math.random() * deck.length)]
    this.used.add(e.id)
    this.push({ beat: this.beat, kind: 'event', text: e.text })
    for (const k in e.effects ?? {}) this.bump(k, e.effects![k])
    for (const p of this.people) {
      if (!this.alive(p)) continue
      if (e.all) this.selfFx(p, e.all)
      this.remember(p, `EVENT: ${e.text}`)
      p.emote = '❗'
      p.emoteUntil = Date.now() + 3500
    }
  }

  private bump(stat: string, d: number) {
    const def = this.sc.stats.find((s) => s.id === stat)
    if (!def) return
    this.stats[stat] = clamp(Math.round((this.stats[stat] + d) * 10) / 10, 0, def.max)
  }

  private selfFx(p: Person, fx: { energy?: number; mood?: number; stress?: number; health?: number; score?: number }) {
    if (fx.energy) p.energy = clamp(p.energy + fx.energy, 0, 100)
    if (fx.mood) p.mood = clamp(p.mood + fx.mood, 0, 100)
    if (fx.stress) p.stress = clamp(p.stress + fx.stress, 0, 100)
    if (fx.health) p.health = clamp(p.health + fx.health, 0, 100)
    if (fx.score && this.sc.score) p.score = clamp(p.score + fx.score, 0, 100)
  }

  private resolve(p: Person, d: Decision): string {
    p.thought = d.thought
    p.feeling = d.feeling || p.feeling
    const parts: string[] = []
    // stance
    if (this.sc.stances && d.stance) {
      const s = this.sc.stances.find((x) => x.toLowerCase() === d.stance.toLowerCase()) ?? this.sc.stances.find((x) => d.stance.toLowerCase().includes(x.toLowerCase().split(' ')[0]))
      if (s && s !== p.stance) {
        p.stance = s
        this.push({ beat: this.beat, kind: 'action', who: p.id, text: `${p.name} now stands at: ${s}.` })
        parts.push(`changed position to "${s}"`)
      }
    }
    // movement (a move tied to a place takes you there)
    const move = this.sc.moves.find((m) => m.id === d.move)
    let dest = ''
    if (move?.at && !move.at.includes(p.place)) dest = move.at.includes(d.go_to) ? d.go_to : move.at[0]
    else if ((d.move === 'go' || !move) && d.go_to && this.sc.places.some((x) => x.id === d.go_to)) dest = d.go_to
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
      const cur = p.opinions[o.id] ?? { trust: 0, note: '' }
      cur.trust = clamp(cur.trust + clamp(Number(op.delta) || 0, -25, 25), -100, 100)
      if (op.why) cur.note = String(op.why).slice(0, 120)
      p.opinions[o.id] = cur
    }
    // action
    if (move) {
      const r = this.act(p, move, d.detail)
      parts.push(r)
    } else if (d.move === 'rest') {
      this.selfFx(p, { energy: 22, stress: -6 })
      p.lastAction = 'rested'
      parts.push('rested')
    } else {
      p.lastAction = d.move === 'go' ? 'moved' : 'talked'
      if (d.say) this.selfFx(p, { mood: 2 })
    }
    return `${p.name} (${p.role.title}): ${parts.join('; ') || 'did little'}${d.detail ? ` — "${d.detail.slice(0, 140)}"` : ''}`
  }

  private act(p: Person, m: Move, detail: string): string {
    let chance = m.chance ?? 0.92
    chance *= 0.65 + p.energy / 280
    if (m.trait) chance += (p.traits[m.trait] - 0.5) * (m.trait === 'neuroticism' ? -0.3 : 0.3)
    if (p.stress > 75) chance -= 0.1
    // working alongside someone helps
    const helpers = this.people.filter((o) => o !== p && this.alive(o) && o.place === p.place && o.lastAction === m.verb).length
    chance += helpers * 0.08
    const ok = Math.random() < clamp(chance, 0.05, 0.98)
    // four people doing the same thing don't achieve four times as much
    const same = this.doneThisBeat.get(m.id) ?? 0
    this.doneThisBeat.set(m.id, same + 1)
    const scale = (0.7 + Math.random() * 0.6) / (1 + same * 0.7)
    if (ok) {
      for (const k in m.effects) {
        const towardWin = this.sc.win?.stat === k && m.effects[k] > 0
        this.bump(k, m.effects[k] * scale * (towardWin ? 0.6 : 1))
      }
      if (m.self) this.selfFx(p, m.self)
      p.emote = m.effects && Object.values(m.effects).some((v) => v > 8) ? '✨' : '✔️'
    } else {
      for (const k in m.fail ?? {}) this.bump(k, m.fail![k])
      this.selfFx(p, { energy: m.self?.energy ?? -6, ...(m.failSelf ?? {}), mood: (m.failSelf?.mood ?? 0) - 4 })
      p.emote = '💢'
    }
    p.emoteUntil = Date.now() + 3000
    p.lastAction = m.verb
    const text = `${p.name} ${m.verb}${ok ? '' : ' — but it went badly'}.`
    this.push({ beat: this.beat, kind: 'action', who: p.id, place: p.place, text: detail ? `${text} (${detail.slice(0, 120)})` : text })
    this.remember(p, ok ? `I ${m.verb}. It worked.` : `I ${m.verb}, but it went badly.`)
    return `${m.verb} (${ok ? 'success' : 'failed'})`
  }

  private upkeep() {
    for (const k in this.sc.upkeep) this.bump(k, this.sc.upkeep[k])
    for (const p of this.people) {
      if (!this.alive(p)) continue
      this.selfFx(p, { energy: -4 })
      // the shared situation weighs on everyone
      let pressure = 0
      for (const s of this.sc.stats) {
        const frac = this.stats[s.id] / s.max
        const bad = s.good === 'high' ? 1 - frac : frac
        if (s.critical && bad > 0.75) pressure += 4
        if (s.critical && bad >= 0.999) {
          // a critical stat at its worst: people get hurt
          if (this.sc.category === 'Survival') this.selfFx(p, { health: -14 })
          pressure += 4
        }
      }
      this.selfFx(p, { stress: pressure - 1, mood: -Math.round(pressure / 2) + (p.stress < 40 ? 1 : 0) })
      if (p.energy < 10) this.selfFx(p, { health: -4, mood: -3 })
      if (p.health <= 0) {
        this.push({ beat: this.beat, kind: 'event', who: p.id, text: this.sc.category === 'Survival' ? `${p.name} has collapsed and can no longer go on.` : `${p.name} has had enough and walks out.` })
        for (const o of this.people) if (o !== p) { this.remember(o, `${p.name} is gone.`); o.mood = clamp(o.mood - 15, 0, 100) }
      }
    }
  }

  private instinct(p: Person): Decision {
    const here = this.people.filter((o) => o !== p && this.alive(o) && o.place === p.place)
    // pick the move that helps the worst stat, flavoured by temperament
    let best: Move | null = null
    let bestScore = -1e9
    for (const m of this.sc.moves) {
      let s = 0
      for (const k in m.effects) {
        const def = this.sc.stats.find((x) => x.id === k)
        if (!def) continue
        const frac = this.stats[k] / def.max
        const need = def.good === 'high' ? 1 - frac : frac
        s += (def.good === 'high' ? m.effects[k] : -m.effects[k]) * (0.5 + need) * (def.critical ? 1.5 : 1)
      }
      if (m.trait) s += (p.traits[m.trait] - 0.5) * 6
      if ((m.self?.energy ?? 0) < -15 && p.energy < 30) s -= 20
      // people split the work rather than all doing the same thing
      s -= this.people.filter((o) => o !== p && o.lastAction === m.verb).length * 4
      s += Math.random() * 12
      if (s > bestScore) { bestScore = s; best = m }
    }
    const rest = p.energy < 18
    const listener = here[Math.floor(Math.random() * here.length)]
    const talk = here.length > 0 && Math.random() < 0.35 + p.traits.extraversion * 0.4
    const line = this.sc.lines[Math.floor(Math.random() * this.sc.lines.length)].replace('{name}', listener?.name ?? 'everyone')
    let stance = ''
    if (this.sc.stances && Math.random() < 0.3) {
      // instinct leans one way or the other — and sometimes follows the room
      const decided = this.sc.stances.filter((x) => !/undecided/i.test(x))
      const room = here.map((o) => o.stance).filter((x) => decided.includes(x))
      stance = room.length && Math.random() < p.traits.agreeableness ? room[Math.floor(Math.random() * room.length)] : decided[Math.floor(Math.random() * decided.length)]
    }
    return {
      stance,
      thought: '', feeling: p.stress > 70 ? 'strained' : p.mood > 65 ? 'hopeful' : 'focused',
      say: talk ? line : '', say_to: talk && line.includes(listener?.name ?? '§') ? listener!.name : '',
      move: rest ? 'rest' : best?.id ?? 'rest', go_to: '', detail: '',
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
      const s0 = this.people.filter((p) => this.alive(p)).map((p) => p.stance)
      if (this.beat >= 3 && s0.length && s0.every((x) => x === s0[0]) && !/undecided/i.test(s0[0])) { tier = 'success'; title = `Unanimous: ${s0[0]}.` }
    }
    if (!tier && this.beat >= sc.beats) {
      const score = this.overall()
      tier = score > 0.72 ? 'success' : score > 0.5 ? 'mixed' : score > 0.3 ? 'failure' : 'disaster'
      title = sc.stances ? (new Set(this.people.map((p) => p.stance)).size > 1 ? 'Time ran out without agreement.' : `Time ran out — all agreed: ${this.people[0].stance}.`) : 'Time is up.'
    }
    if (!tier) return
    this.over = true
    const fates = this.people.map((p) => ({ id: p.id, text: this.ruleFate(p) }))
    this.ending = { tier, title, text: this.ruleSummary(tier), fates }
    this.push({ beat: this.beat, kind: 'event', text: `THE END — ${title}` })
    if (host.canDirect()) void this.epilogue(host)
  }

  /** 0..1: how well things stand across all stakes. */
  overall(): number {
    let t = 0
    for (const s of this.sc.stats) t += s.good === 'high' ? this.stats[s.id] / s.max : 1 - this.stats[s.id] / s.max
    return t / this.sc.stats.length
  }

  private ruleSummary(tier: Ending['tier']): string {
    const best = [...this.sc.stats].sort((a, b) => this.frac(b) - this.frac(a))[0]
    const worst = [...this.sc.stats].sort((a, b) => this.frac(a) - this.frac(b))[0]
    const lead = [...this.people].sort((a, b) => b.mood - a.mood)[0]
    const words = { triumph: 'Against the odds, they made it.', success: 'They came through it.', mixed: 'They got through — just about, and not without cost.', failure: 'It did not go the way they hoped.', disaster: 'It ended badly.' }[tier]
    return `${words} Their greatest strength in the end was ${best.label.toLowerCase()}; their weakest, ${worst.label.toLowerCase()}. ${lead.name} came out of it in the best spirits.`
  }
  private frac(s: Scenario['stats'][0]): number {
    return s.good === 'high' ? this.stats[s.id] / s.max : 1 - this.stats[s.id] / s.max
  }
  private ruleFate(p: Person): string {
    if (!this.alive(p)) return this.sc.category === 'Survival' ? 'Did not make it through.' : 'Walked away before the end.'
    const friends = Object.entries(p.opinions).filter(([, o]) => o.trust > 30).map(([id]) => this.people.find((x) => x.id === +id)?.name).filter(Boolean)
    const mood = p.mood > 65 ? 'leaves changed for the better' : p.mood < 35 ? 'leaves shaken' : 'leaves tired but whole'
    return `${mood}${friends.length ? `, with a new bond with ${friends.join(' and ')}` : ''}${this.sc.stances ? `. Final position: ${p.stance}` : ''}.`
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
    if (this.ending) out.push('', `## ${this.ending.title}`, '', this.ending.text, '', ...this.ending.fates.map((f) => `- **${this.people.find((p) => p.id === f.id)?.name}**: ${f.text}`))
    return out.join('\n')
  }
}

function sanitize(d: Decision): Decision {
  return {
    thought: String(d.thought ?? '').slice(0, 500),
    feeling: String(d.feeling ?? '').slice(0, 40),
    say: String(d.say ?? '').slice(0, 300),
    say_to: String(d.say_to ?? ''),
    move: String(d.move ?? 'talk').trim().toLowerCase(),
    go_to: String(d.go_to ?? '').trim().toLowerCase(),
    detail: String(d.detail ?? '').slice(0, 200),
    stance: String(d.stance ?? ''),
    opinions: Array.isArray(d.opinions) ? d.opinions.slice(0, 3) : [],
  }
}

export type { Move }
