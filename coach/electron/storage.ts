// On-disk storage for the Electron shell. Everything written here is small,
// structured JSON: settings, and — only if the player opted in — tendency
// counts. No frames, thumbnails, audio, or text transcripts are ever written.
// Inputs come from the renderer and are re-validated here.

import { mkdir, readFile, readdir, rename, rm, stat, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { isPatternSnapshot, type PatternSnapshot } from '../src/core/patterns.ts'
import { DEFAULT_SETTINGS, sanitizeSettings, type CoachSettings } from '../src/core/settings.ts'

const PROFILE_ID = /^[a-z0-9][a-z0-9-]{0,63}$/
const MAX_PROFILE_BYTES = 512 * 1024
const MAX_PROFILES = 50

export class Storage {
  readonly root: string
  readonly profilesDir: string
  private readonly tendenciesDir: string

  constructor(root: string) {
    this.root = root
    this.profilesDir = join(root, 'profiles')
    this.tendenciesDir = join(root, 'tendencies')
  }

  async init(): Promise<void> {
    await mkdir(this.profilesDir, { recursive: true })
    await mkdir(this.tendenciesDir, { recursive: true })
  }

  async loadSettings(): Promise<CoachSettings> {
    return sanitizeSettings(await this.readJson(join(this.root, 'settings.json')), DEFAULT_SETTINGS)
  }

  async saveSettings(input: unknown): Promise<CoachSettings> {
    const clean = sanitizeSettings(input, await this.loadSettings())
    await this.writeJson(join(this.root, 'settings.json'), clean)
    return clean
  }

  async loadTendencies(profileId: unknown): Promise<PatternSnapshot | null> {
    if (!isProfileId(profileId)) return null
    const v = await this.readJson(this.tendencyPath(profileId))
    return isPatternSnapshot(v) && v.profileId === profileId ? v : null
  }

  /** Refuses (returns false) unless the player has opted in to saving tendencies. */
  async saveTendencies(snapshot: unknown): Promise<boolean> {
    if (!(await this.loadSettings()).saveTendencies) return false
    if (!isPatternSnapshot(snapshot) || !isProfileId(snapshot.profileId)) return false
    await this.writeJson(this.tendencyPath(snapshot.profileId), snapshot)
    return true
  }

  async forgetTendencies(profileId: unknown): Promise<void> {
    if (profileId === null) {
      await rm(this.tendenciesDir, { recursive: true, force: true })
      await mkdir(this.tendenciesDir, { recursive: true })
    } else if (isProfileId(profileId)) {
      await rm(this.tendencyPath(profileId), { force: true })
    }
  }

  /** Raw JSON of user-installed profiles (validated by the renderer before use). */
  async listUserProfiles(): Promise<unknown[]> {
    const names = (await readdir(this.profilesDir).catch(() => [] as string[]))
      .filter((n) => n.endsWith('.json'))
      .slice(0, MAX_PROFILES)
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

  private tendencyPath(profileId: string): string {
    return join(this.tendenciesDir, `${profileId}.json`)
  }

  private async readJson(path: string): Promise<unknown> {
    try {
      return JSON.parse(await readFile(path, 'utf8'))
    } catch {
      return null
    }
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
