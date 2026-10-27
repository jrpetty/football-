// On-disk storage for the Electron shell. Everything written here is small,
// structured JSON. No frames, thumbnails, audio, OCR text or transcripts are
// ever written.
//
//   settings.json            coach settings (not personal)
//   cloud.json               cloud on/off, models, spend cap (not personal)
//   secret.bin               API key, OS-encrypted (see cloud/keystore.ts)
//   tendencies/<pack>.json   opt-in pattern counts            ┐ player data:
//   history/<pack>.json      opt-in match records             │ encrypted with
//   notes/<pack>.json        opt-in notes and goals           ┘ safeStorage when available
//   research/<pack>.json     cited research briefs (public web content)
//
// Inputs come from the renderer and are re-validated here.

import { mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { isMatchRecord, type MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { deletePattern, migrateSnapshot, setVerdict, type PatternSnapshot, type PatternVerdict } from '../src/core/patterns.ts'
import { DEFAULT_SETTINGS, sanitizeSettings, type CoachSettings } from '../src/core/settings.ts'
import { MODELS } from '../src/core/vision/cost.ts'
import type { Cipher } from './cloud/keystore.ts'
import { DEFAULT_CLOUD, type CloudSettings, type ResearchBrief } from '../shared/cloud.ts'

const PROFILE_ID = /^[a-z0-9][a-z0-9_-]{0,63}$/
const MAX_PROFILE_BYTES = 512 * 1024
const MAX_PROFILES = 50
const MAX_MATCHES = 200
const MAX_NOTES_CHARS = 4000

export interface PlayerNotes {
  readonly goals: string
  readonly notes: string
  readonly updatedAt: string
}

export class Storage {
  readonly root: string
  readonly profilesDir: string
  private readonly cipher: Cipher | null

  constructor(root: string, cipher: Cipher | null = null) {
    this.root = root
    this.profilesDir = join(root, 'profiles')
    this.cipher = cipher?.available ? cipher : null
  }

  get encrypted(): boolean {
    return this.cipher !== null
  }

  async init(): Promise<void> {
    for (const d of ['profiles', 'tendencies', 'history', 'notes', 'research']) await mkdir(join(this.root, d), { recursive: true })
  }

  // --- Settings ------------------------------------------------------------------------

  async loadSettings(): Promise<CoachSettings> {
    return sanitizeSettings(await this.readJson(join(this.root, 'settings.json')), DEFAULT_SETTINGS)
  }

  async saveSettings(input: unknown): Promise<CoachSettings> {
    const clean = sanitizeSettings(input, await this.loadSettings())
    await this.writeJson(join(this.root, 'settings.json'), clean)
    return clean
  }

  async loadCloud(): Promise<CloudSettings> {
    return sanitizeCloud(await this.readJson(join(this.root, 'cloud.json')), DEFAULT_CLOUD)
  }

  async saveCloud(input: unknown): Promise<CloudSettings> {
    const clean = sanitizeCloud(input, await this.loadCloud())
    await this.writeJson(join(this.root, 'cloud.json'), clean)
    return clean
  }

  // --- Long-term memory (opt-in) -----------------------------------------------------------

  async loadTendencies(profileId: unknown): Promise<PatternSnapshot | null> {
    if (!isProfileId(profileId)) return null
    const v = migrateSnapshot(await this.readPrivate(this.path('tendencies', profileId)))
    return v && v.profileId === profileId ? v : null
  }

  /** Refuses (returns false) unless the player has opted in to long-term memory. */
  async saveTendencies(snapshot: unknown): Promise<boolean> {
    if (!(await this.optedIn())) return false
    const s = migrateSnapshot(snapshot)
    if (!s || !isProfileId(s.profileId)) return false
    await this.writePrivate(this.path('tendencies', s.profileId), s)
    return true
  }

  async setPatternVerdict(profileId: unknown, episodeId: unknown, verdict: unknown): Promise<PatternSnapshot | null> {
    const s = await this.loadTendencies(profileId)
    if (!s || typeof episodeId !== 'string' || episodeId.length > 64) return s
    const v: PatternVerdict | null = verdict === 'dismissed' || verdict === 'disputed' ? verdict : null
    const next = setVerdict(s, episodeId, v, new Date())
    await this.writePrivate(this.path('tendencies', s.profileId), next)
    return next
  }

  async deletePattern(profileId: unknown, episodeId: unknown): Promise<PatternSnapshot | null> {
    const s = await this.loadTendencies(profileId)
    if (!s || typeof episodeId !== 'string' || episodeId.length > 64) return s
    const next = deletePattern(s, episodeId, new Date())
    await this.writePrivate(this.path('tendencies', s.profileId), next)
    return next
  }

  async loadMatches(profileId: unknown): Promise<MatchRecord[]> {
    if (!isProfileId(profileId)) return []
    const v = await this.readPrivate(this.path('history', profileId))
    return Array.isArray(v) ? v.filter(isMatchRecord).filter((m) => m.gameProfileId === profileId) : []
  }

  async saveMatch(record: unknown): Promise<boolean> {
    if (!(await this.optedIn()) || !isMatchRecord(record) || !isProfileId(record.gameProfileId)) return false
    const list = (await this.loadMatches(record.gameProfileId)).filter((m) => m.id !== record.id)
    list.push(record)
    await this.writePrivate(this.path('history', record.gameProfileId), list.slice(-MAX_MATCHES))
    return true
  }

  async deleteMatch(profileId: unknown, id: unknown): Promise<void> {
    if (!isProfileId(profileId)) return
    const list = (await this.loadMatches(profileId)).filter((m) => typeof id === 'string' && m.id !== id)
    await this.writePrivate(this.path('history', profileId), list)
  }

  async loadNotes(profileId: unknown): Promise<PlayerNotes | null> {
    if (!isProfileId(profileId)) return null
    const v = (await this.readPrivate(this.path('notes', profileId))) as Partial<PlayerNotes> | null
    return v && typeof v.goals === 'string' && typeof v.notes === 'string' ? { goals: v.goals, notes: v.notes, updatedAt: String(v.updatedAt ?? '') } : null
  }

  async saveNotes(profileId: unknown, notes: unknown): Promise<boolean> {
    if (!isProfileId(profileId) || !(await this.optedIn())) return false
    const n = notes as Partial<PlayerNotes> | null
    if (!n || typeof n.goals !== 'string' || typeof n.notes !== 'string') return false
    await this.writePrivate(this.path('notes', profileId), {
      goals: n.goals.slice(0, MAX_NOTES_CHARS),
      notes: n.notes.slice(0, MAX_NOTES_CHARS),
      updatedAt: new Date().toISOString(),
    })
    return true
  }

  /** Delete long-term player data for one pack, or for every pack when null. */
  async forgetPlayerData(profileId: unknown): Promise<void> {
    for (const dir of ['tendencies', 'history', 'notes']) {
      if (profileId === null) {
        await rm(join(this.root, dir), { recursive: true, force: true })
        await mkdir(join(this.root, dir), { recursive: true })
      } else if (isProfileId(profileId)) {
        await rm(this.path(dir, profileId), { force: true })
      }
    }
  }

  /** Every piece of stored player data, decrypted, as one JSON document (for the player to keep). */
  async exportPlayerData(): Promise<string> {
    const out: Record<string, unknown> = { exportedAt: new Date().toISOString(), encryptedAtRest: this.encrypted }
    for (const dir of ['tendencies', 'history', 'notes']) {
      const entries: Record<string, unknown> = {}
      for (const name of await readdir(join(this.root, dir)).catch(() => [] as string[])) {
        if (!name.endsWith('.json')) continue
        entries[name.slice(0, -5)] = await this.readPrivate(join(this.root, dir, name))
      }
      out[dir] = entries
    }
    return JSON.stringify(out, null, 2)
  }

  // --- Research briefs (public web content, cited) -------------------------------------------

  async loadResearch(profileId: unknown): Promise<ResearchBrief | null> {
    if (!isProfileId(profileId)) return null
    const v = (await this.readJson(this.path('research', profileId))) as Partial<ResearchBrief> | null
    return v && typeof v.text === 'string' && Array.isArray(v.sources) ? (v as ResearchBrief) : null
  }

  async saveResearch(profileId: unknown, brief: ResearchBrief): Promise<void> {
    if (!isProfileId(profileId)) return
    await this.writeJson(this.path('research', profileId), brief)
  }

  async deleteResearch(profileId: unknown): Promise<void> {
    if (isProfileId(profileId)) await rm(this.path('research', profileId), { force: true })
  }

  // --- User packs --------------------------------------------------------------------------

  /** Raw JSON of user-installed packs (validated before use). */
  async listUserProfiles(): Promise<unknown[]> {
    const names = (await readdir(this.profilesDir).catch(() => [] as string[])).filter((n) => n.endsWith('.json')).slice(0, MAX_PROFILES)
    const out: unknown[] = []
    for (const name of names) {
      const path = join(this.profilesDir, name)
      const info = await stat(path).catch(() => null)
      if (!info?.isFile() || info.size > MAX_PROFILE_BYTES) continue
      const v = await this.readJson(path)
      if (v !== null) out.push(v)
    }
    return out
  }

  // --- Internals -------------------------------------------------------------------------------

  private async optedIn(): Promise<boolean> {
    return (await this.loadSettings()).saveTendencies
  }

  private path(dir: string, profileId: string): string {
    return join(this.root, dir, `${profileId}.json`)
  }

  private async readJson(path: string): Promise<unknown> {
    try {
      return JSON.parse(await readFile(path, 'utf8'))
    } catch {
      return null
    }
  }

  /** Reads both encrypted envelopes and (older or unencrypted) plain JSON. */
  private async readPrivate(path: string): Promise<unknown> {
    const v = (await this.readJson(path)) as { enc?: unknown; data?: unknown } | null
    if (v && v.enc === 'safeStorage' && typeof v.data === 'string') {
      if (!this.cipher) return null // encrypted by an OS key we cannot use here
      try {
        return JSON.parse(this.cipher.decrypt(Buffer.from(v.data, 'base64')))
      } catch {
        return null
      }
    }
    return v
  }

  private async writePrivate(path: string, value: unknown): Promise<void> {
    if (!this.cipher) return this.writeJson(path, value)
    await this.writeJson(path, { enc: 'safeStorage', data: this.cipher.encrypt(JSON.stringify(value)).toString('base64') })
  }

  /** Write-then-rename, so a crash never leaves a half-written file. */
  private async writeJson(path: string, value: unknown): Promise<void> {
    const tmp = `${path}.${process.pid}.tmp`
    await writeFile(tmp, JSON.stringify(value, null, 2), 'utf8')
    await rename(tmp, path)
  }
}

export function isProfileId(v: unknown): v is string {
  return typeof v === 'string' && PROFILE_ID.test(v)
}

export function sanitizeCloud(input: unknown, base: CloudSettings): CloudSettings {
  if (typeof input !== 'object' || input === null) return base
  const o = input as Record<string, unknown>
  const model = (v: unknown, fallback: string) => (typeof v === 'string' && MODELS.some((m) => m.id === v) ? v : fallback)
  const cap = typeof o.sessionCapUsd === 'number' && Number.isFinite(o.sessionCapUsd) ? Math.min(100, Math.max(0.1, o.sessionCapUsd)) : base.sessionCapUsd
  return {
    enabled: typeof o.enabled === 'boolean' ? o.enabled : base.enabled,
    visionModel: model(o.visionModel, base.visionModel),
    answerModel: model(o.answerModel, base.answerModel),
    sessionCapUsd: Math.round(cap * 100) / 100,
  }
}
