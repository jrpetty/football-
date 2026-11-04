// Quartet's connection to AI minds. Settings (keys and model profiles) are
// shared with Genesis when both run on the same site, so a DeepSeek key or a
// local Ollama model set up in one is available in the other.

import { builtinProfiles, complete, profileCost, type MindProfile } from './providers.ts'
import { AiError, type Effort, type Usage } from './client.ts'
import type { MindHost } from '../engine/engine.ts'
import type { Person } from '../engine/types.ts'

export interface QSettings {
  apiKey: string
  profiles: MindProfile[]
  model: string // default profile for characters
  director: string // profile for the narrator ('' = no narrator)
  effort: Effort
  budgetUsd: number
}

const LS = 'quartet.settings'
const SHARED = 'genesis.ai.settings'

export function loadQ(): QSettings {
  const base: QSettings = { apiKey: '', profiles: [], model: 'claude-opus-5-5', director: 'claude-opus-5-5', effort: 'low', budgetUsd: 3 }
  try {
    const shared = JSON.parse(localStorage.getItem(SHARED) ?? 'null') as Partial<QSettings> | null
    const own = JSON.parse(localStorage.getItem(LS) ?? 'null') as Partial<QSettings> | null
    return { ...base, ...(shared ? { apiKey: shared.apiKey ?? '', profiles: shared.profiles ?? [] } : {}), ...(own ?? {}) }
  } catch {
    return base
  }
}

export function saveQ(s: QSettings) {
  try {
    localStorage.setItem(LS, JSON.stringify(s))
    // keep keys & profiles in sync with Genesis
    const shared = JSON.parse(localStorage.getItem(SHARED) ?? '{}') as Record<string, unknown>
    localStorage.setItem(SHARED, JSON.stringify({ ...shared, apiKey: s.apiKey, profiles: s.profiles }))
  } catch { /* storage unavailable */ }
}

export class Minds implements MindHost {
  s: QSettings = loadQ()
  cost = 0
  calls = 0
  errors = 0
  lastError = ''
  usage: Usage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
  listeners = new Set<() => void>()

  get profiles(): MindProfile[] {
    return [...builtinProfiles(), ...this.s.profiles]
  }
  profile(id: string): MindProfile | undefined {
    return this.profiles.find((p) => p.id === id)
  }
  label(id: string): string {
    return id ? this.profile(id)?.label ?? id : 'Instinct (no AI)'
  }
  usable(id: string): boolean {
    const p = this.profile(id)
    if (!p) return false
    if (p.kind === 'anthropic') return (p.apiKey || this.s.apiKey).trim().length > 20
    return true
  }
  get overBudget(): boolean {
    return this.cost >= this.s.budgetUsd
  }
  update(p: Partial<QSettings>) {
    this.s = { ...this.s, ...p }
    saveQ(this.s)
    this.emit()
  }
  emit() {
    for (const l of this.listeners) l()
  }

  canThink(p: Person): boolean {
    return !!p.model && this.usable(p.model) && !this.overBudget
  }
  canDirect(): boolean {
    return !!this.s.director && this.usable(this.s.director) && !this.overBudget
  }
  think(p: Person, system: string, user: string, schema: Record<string, unknown>): Promise<string> {
    return this.call(p.model, system, user, schema, 3000)
  }
  direct(system: string, user: string, schema: Record<string, unknown>): Promise<string> {
    return this.call(this.s.director, system, user, schema, 3000)
  }

  private async call(id: string, system: string, user: string, schema: Record<string, unknown>, maxTokens: number): Promise<string> {
    const prof = this.profile(id)
    if (!prof) throw new AiError('bad', 'Unknown mind')
    this.calls++
    try {
      const r = await complete(prof, { anthropicKey: this.s.apiKey, effort: this.s.effort, system, user, schema, maxTokens: Math.max(maxTokens, 8000) })
      this.account(prof, r.usage)
      return r.text
    } catch (e) {
      const u = (e as { usage?: Usage }).usage
      if (u) this.account(prof, u)
      this.errors++
      this.lastError = `${prof.label}: ${(e as Error).message}`
      throw e
    } finally {
      this.emit()
    }
  }

  private account(p: MindProfile, u: Usage) {
    this.usage.input += u.input
    this.usage.output += u.output
    this.usage.cacheRead += u.cacheRead
    this.cost += profileCost(p, u)
  }
}

export const minds = new Minds()
