// The Mind scheduler: decides which people get to think, sends their context
// to Claude, and turns the reply into speech, thoughts and a plan. Requests run
// concurrently under a rate limit and a spending cap. While a person waits for
// a thought — or when the AI is off — their instinct keeps them alive.

import type { World } from '../sim/world.ts'
import type { Agent, PlanStep } from '../sim/types.ts'
import { ACTION_KINDS } from '../sim/types.ts'
import { HOUR, DAY, yearOf, YEAR } from '../sim/time.ts'
import {
  callClaude, costOf, parseJson, loadSettings, saveSettings, modelInfo, AiError,
  type AiSettings, type Usage, type Effort,
} from './client.ts'
import {
  SYSTEM_PROMPT, DECISION_SCHEMA, buildContext, REFLECT_SYSTEM, REFLECT_SCHEMA, buildReflection,
  INTERVIEW_SYSTEM, CHRONICLE_SYSTEM, CHRONICLE_SCHEMA, type Decision, type Reflection,
} from './prompts.ts'

export interface FeedItem {
  id: number
  realT: number
  t: number
  agentId: number
  name: string
  tribe: string
  color: string
  kind: 'thought' | 'reflection' | 'error'
  thought: string
  say: string
  sayTo: string
  plan: string
  model: string
}

export interface MindStats {
  calls: number
  ok: number
  errors: number
  inFlight: number
  usage: Usage
  cost: number
  byModel: Record<string, { calls: number; cost: number }>
  lastError: string
  lastErrorT: number
  pausedReason: string
}

const emptyUsage = (): Usage => ({ input: 0, output: 0, cacheRead: 0, cacheWrite: 0 })

export class MindManager {
  settings: AiSettings
  world: World | null = null
  feed: FeedItem[] = []
  stats: MindStats = { calls: 0, ok: 0, errors: 0, inFlight: 0, usage: emptyUsage(), cost: 0, byModel: {}, lastError: '', lastErrorT: 0, pausedReason: '' }
  private recent: number[] = [] // real timestamps of calls in last minute
  private backoffUntil = 0
  private minds = new Set<number>()
  private mindsT = -1e9
  private lastChronicleYear = 0
  private feedId = 1
  private aborts = new Set<AbortController>()
  listeners = new Set<() => void>()

  constructor() {
    this.settings = loadSettings()
  }

  attach(w: World) {
    for (const c of this.aborts) c.abort()
    this.aborts.clear()
    this.world = w
    this.minds.clear()
    this.mindsT = -1e9
    this.lastChronicleYear = yearOf(w.time)
    for (const a of w.agents) a.aiPending = false
    this.stats.inFlight = 0
    w.aiActive = (a) => this.isMind(a)
  }

  update(s: Partial<AiSettings>) {
    this.settings = { ...this.settings, ...s }
    saveSettings(this.settings)
    this.mindsT = -1e9
    this.stats.pausedReason = ''
    this.emit()
  }

  get ready(): boolean {
    return this.settings.enabled && this.settings.apiKey.trim().length > 20 && this.stats.cost < this.settings.budgetUsd
  }

  isMind(a: Agent): boolean {
    return this.ready && this.minds.has(a.id)
  }

  modelFor(a: Agent): string {
    if (a.model) return a.model
    const t = this.world?.tribeById(a.tribeId)
    return t?.model || this.settings.model
  }

  private emit() {
    for (const l of this.listeners) l()
  }

  /** Choose which adults get a Claude mind (leaders and elders first, then by curiosity). */
  private refreshMinds(w: World) {
    const adults = w.living.filter((a) => w.isAdult(a))
    const leaders = new Set(w.tribes.map((t) => t.leaderId))
    adults.sort((a, b) => {
      const pa = (leaders.has(a.id) ? 1000 : 0) + (this.minds.has(a.id) ? 500 : 0) + a.aiDecisions
      const pb = (leaders.has(b.id) ? 1000 : 0) + (this.minds.has(b.id) ? 500 : 0) + b.aiDecisions
      return pb - pa
    })
    // keep every tribe represented
    const chosen = new Set<number>()
    for (const t of w.tribes) {
      if (!t.alive) continue
      const first = adults.find((a) => a.tribeId === t.id)
      if (first) chosen.add(first.id)
    }
    for (const a of adults) {
      if (chosen.size >= this.settings.maxMinds) break
      chosen.add(a.id)
    }
    this.minds = chosen
    this.mindsT = w.time
  }

  /** Called every frame. Launches new requests when there's capacity. */
  tick() {
    const w = this.world
    if (!w) return
    if (!this.ready) {
      if (this.settings.enabled && this.settings.apiKey && this.stats.cost >= this.settings.budgetUsd) this.stats.pausedReason = 'Budget reached — raise it in Settings to continue.'
      return
    }
    const now = Date.now()
    if (now < this.backoffUntil) return
    if (w.time - this.mindsT > 2 * HOUR || this.minds.size === 0) this.refreshMinds(w)
    this.recent = this.recent.filter((t) => now - t < 60_000)
    let slots = Math.min(this.settings.maxConcurrent - this.stats.inFlight, this.settings.maxPerMinute - this.recent.length)
    if (slots <= 0) return

    // chronicle once a year
    if (this.settings.chronicler && yearOf(w.time) > this.lastChronicleYear && slots > 0) {
      this.lastChronicleYear = yearOf(w.time)
      void this.writeChronicle(w, this.lastChronicleYear - 1)
      slots--
    }

    const every = this.settings.thinkEveryHours * HOUR
    const cands: { a: Agent; p: number; kind: 'decide' | 'reflect' }[] = []
    for (const id of this.minds) {
      const a = w.agentById.get(id)
      if (!a || !a.alive || a.aiPending || !w.isAdult(a)) continue
      const since = w.time - a.aiLastT
      const heardNew = a.heard.some((h) => h.t > a.aiLastT)
      const idle = a.plan.length === 0 && (!a.task || !a.task.fromPlan)
      let p = -1
      if (a.aiDecisions === 0) p = 100
      else if (heardNew && since > 0.5 * HOUR) p = 80
      else if (a.urgentThink && since > 1 * HOUR) p = 60
      else if (idle && since > 1.5 * HOUR) p = 40 + since / HOUR
      else if (since > every) p = 20 + since / HOUR
      if (p > 0) cands.push({ a, p, kind: 'decide' })
      else if (this.settings.reflections && w.time - a.lastReflectT > 2 * DAY && a.memories.length >= 12 && since > HOUR) cands.push({ a, p: 5, kind: 'reflect' })
    }
    cands.sort((x, y) => y.p - x.p)
    for (const c of cands.slice(0, slots)) {
      if (c.kind === 'decide') void this.decide(w, c.a)
      else void this.reflect(w, c.a)
    }
  }

  private account(model: string, u: Usage) {
    this.stats.usage.input += u.input
    this.stats.usage.output += u.output
    this.stats.usage.cacheRead += u.cacheRead
    this.stats.usage.cacheWrite += u.cacheWrite
    const c = costOf(model, u)
    this.stats.cost += c
    const m = (this.stats.byModel[model] ??= { calls: 0, cost: 0 })
    m.calls++
    m.cost += c
  }

  private async run(model: string, system: string, user: string, schema: Record<string, unknown> | undefined, effort: Effort, maxTokens = 8000): Promise<string> {
    const ctrl = new AbortController()
    this.aborts.add(ctrl)
    this.stats.calls++
    this.stats.inFlight++
    this.recent.push(Date.now())
    this.emit()
    try {
      const r = await callClaude({ key: this.settings.apiKey.trim(), model, effort, system, user, schema, maxTokens, signal: ctrl.signal })
      this.account(model, r.usage)
      this.stats.ok++
      return r.text
    } catch (e) {
      const usage = (e as { usage?: Usage }).usage
      if (usage) this.account(model, usage)
      this.stats.errors++
      const err = e instanceof AiError ? e : new AiError('other', String(e))
      this.stats.lastError = err.message
      this.stats.lastErrorT = Date.now()
      if (err.kind === 'auth') { this.settings.enabled = false; this.stats.pausedReason = err.message }
      if (err.kind === 'rate') this.backoffUntil = Date.now() + 20_000
      if (err.kind === 'overloaded' || err.kind === 'network') this.backoffUntil = Date.now() + 8_000
      throw err
    } finally {
      this.stats.inFlight--
      this.aborts.delete(ctrl)
      this.emit()
    }
  }

  private async decide(w: World, a: Agent) {
    a.aiPending = true
    a.urgentThink = false
    const asked = w.time
    const model = this.modelFor(a)
    try {
      const text = await this.run(model, SYSTEM_PROMPT, buildContext(w, a), DECISION_SCHEMA, this.settings.effort)
      if (this.world !== w || !a.alive) return
      const d = parseJson<Decision>(text)
      if (!d) throw new AiError('bad', 'Unreadable decision')
      this.apply(w, a, d, model)
      a.aiDecisions++
      a.aiLastT = asked
    } catch (e) {
      a.aiErrors++
      a.aiLastT = asked // don't hammer: wait a normal interval after a failure
      this.pushFeed(w, a, 'error', (e as Error).message, '', '', '', model)
    } finally {
      a.aiPending = false
    }
  }

  apply(w: World, a: Agent, d: Decision, model: string) {
    a.thought = (d.thought || '').slice(0, 400)
    a.feeling = (d.feeling || a.feeling).slice(0, 40)
    if (d.goal && d.goal.length > 3) a.goal = d.goal.slice(0, 200)
    a.journal.push({ t: w.time, text: a.thought, say: d.say || undefined, model })
    if (a.journal.length > 30) a.journal.shift()

    const steps: PlanStep[] = (Array.isArray(d.plan) ? d.plan : [])
      .slice(0, 6)
      .filter((s) => s && typeof s.action === 'string')
      .map((s) => ({
        action: (ACTION_KINDS as string[]).includes(s.action) ? (s.action as PlanStep['action']) : 'rest',
        target: String(s.target ?? '').slice(0, 80),
        amount: Math.max(0, Math.min(40, Math.round(Number(s.amount) || 0))),
        text: String(s.text ?? '').slice(0, 1200),
      }))

    // speech: to someone close → say now; to someone far → walk over first
    const say = (d.say || '').trim()
    if (say) {
      const to = d.say_to && !/^(everyone|all|nobody|none|)$/i.test(d.say_to.trim()) ? w.findAgentByName(d.say_to, a) : undefined
      if (!to) w.say(a, say, null)
      else if (Math.hypot(to.x - a.x, to.y - a.y) < 6) {
        w.say(a, say, to)
        w.remember(to, `${a.name} said to me: "${say.slice(0, 160)}"`, 4)
      } else steps.unshift({ action: 'talk', target: to.name, amount: 0, text: say })
    }

    // replace the plan; drop instinct busywork, but don't interrupt survival
    a.plan = steps
    const tk = a.task
    if (tk && !tk.fromPlan && tk.action !== 'drink' && tk.action !== 'eat' && tk.action !== 'sleep' && tk.text !== 'survival' && tk.text !== 'warmth') {
      a.task = null
      a.moving = false
    }
    const tribe = w.tribeById(a.tribeId)
    this.pushFeed(w, a, 'thought', a.thought, say, d.say_to || '', steps.map((s) => `${s.action}${s.target ? ' ' + s.target : ''}`).join(' → '), model, tribe?.name, tribe?.color)
  }

  private async reflect(w: World, a: Agent) {
    a.aiPending = true
    a.lastReflectT = w.time
    const model = this.modelFor(a)
    try {
      const text = await this.run(model, REFLECT_SYSTEM, buildReflection(w, a), REFLECT_SCHEMA, 'low', 4000)
      if (this.world !== w || !a.alive) return
      const r = parseJson<Reflection>(text)
      if (!r) return
      for (const ins of (r.insights ?? []).slice(0, 3)) {
        if (!ins) continue
        a.reflections.push(ins.slice(0, 200))
        w.remember(a, `I realised: ${ins.slice(0, 200)}`, 6)
      }
      if (a.reflections.length > 12) a.reflections.splice(0, a.reflections.length - 12)
      if (r.goal) a.goal = r.goal.slice(0, 200)
      if (r.self_image) a.selfImage = r.self_image.slice(0, 240)
      if (r.beliefs) a.beliefs = r.beliefs.slice(0, 300)
      this.pushFeed(w, a, 'reflection', (r.insights ?? []).join(' '), '', '', '', model)
    } catch {
      /* reflections are optional */
    } finally {
      a.aiPending = false
    }
  }

  /** Ask a person something. Pure observation: they won't remember it. */
  async interview(a: Agent, question: string): Promise<string> {
    const w = this.world
    if (!w) throw new Error('No world')
    if (!this.settings.apiKey) throw new AiError('auth', 'Add your Anthropic API key in Settings to listen to minds.')
    const ctx = buildContext(w, a).replace(/What do you think, say and do next\?$/, '')
    return this.run(this.modelFor(a), INTERVIEW_SYSTEM, `${ctx}\n\nThe observer asks: "${question.slice(0, 600)}"`, undefined, 'low', 3000)
  }

  async writeChronicle(w: World, year: number): Promise<void> {
    const from = (year - 1) * YEAR, to = year * YEAR
    const entries = w.chronicle.filter((e) => e.t >= from && e.t < to && e.kind !== 'speech')
    if (entries.length < 3) return
    const speech = w.chronicle.filter((e) => e.t >= from && e.t < to && e.kind === 'speech').slice(-12)
    const tribes = w.tribes.filter((t) => t.alive).map((t) => `${t.name}: ${w.members(t.id).length} people; values "${t.values || '—'}"; beliefs "${t.beliefs || '—'}"; laws ${t.laws.length}`).join('\n')
    const prev = w.chapters.slice(-2).map((c) => `${c.title}: ${c.text.slice(0, 400)}`).join('\n')
    const user = `Year ${year} of the world of ${w.cfg.name}.\n\nPeoples:\n${tribes}\n\nPrevious chapters (for continuity):\n${prev || 'This is the first chapter.'}\n\nRecords of the year:\n${entries.slice(-120).map((e) => `- ${e.text}`).join('\n')}\n\nThings people said:\n${speech.map((e) => `- ${e.text}`).join('\n')}`
    try {
      const text = await this.run(this.settings.model, CHRONICLE_SYSTEM, user, CHRONICLE_SCHEMA, 'low', 6000)
      const c = parseJson<{ title: string; text: string }>(text)
      if (c && this.world === w) {
        w.chapters.push({ t: to, title: `Year ${year}: ${c.title}`, text: c.text })
        w.log('ai', `The historian completed a chapter: “${c.title}”.`, '📖')
      }
    } catch {
      /* optional */
    }
  }

  private pushFeed(w: World, a: Agent, kind: FeedItem['kind'], thought: string, say: string, sayTo: string, plan: string, model: string, tribe?: string, color?: string) {
    const t = w.tribeById(a.tribeId)
    this.feed.push({
      id: this.feedId++, realT: Date.now(), t: w.time, agentId: a.id, name: a.name, tribe: tribe ?? t?.name ?? '', color: color ?? t?.color ?? '#aaa',
      kind, thought, say, sayTo, plan, model: modelInfo(model).label,
    })
    if (this.feed.length > 300) this.feed.splice(0, 100)
    this.emit()
  }

  /** Rough $/hour estimate at current settings, for the settings panel. */
  estimateHourly(speed: number): number {
    const m = modelInfo(this.settings.model)
    // ~5.5k cached system + ~2.2k context in, ~700 out incl. thinking at low effort
    const perCall = (5500 * m.cacheRead + 2200 * m.input + 700 * m.output) / 1e6
    const realSecPerGameHour = 2.5 / Math.max(0.25, speed)
    const decisionsPerMindPerRealHour = 3600 / (realSecPerGameHour * Math.max(1.5, this.settings.thinkEveryHours * 0.6))
    const wanted = decisionsPerMindPerRealHour * this.settings.maxMinds
    const calls = Math.min(wanted, this.settings.maxPerMinute * 60)
    return calls * perCall
  }
}

export const mind = new MindManager()
