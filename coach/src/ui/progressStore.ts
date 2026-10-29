// Progress, practice and build orders for the session: the player's AoE4World
// history (synced slowly, kept with "Remember my games"), the practice plan
// (updated after every measured game), and the saved build orders with the one
// to train with for each civilisation. The session owns the state React reads.

import type { GamePlayerBuild, SightlineBridge } from '../../shared/bridge.ts'
import type { MatchRecord } from '../core/analyses/matchRecorder.ts'
import { findFaction } from '../core/analyses/intel.ts'
import { currentFocus, isPracticePlan, makePlan, measurements, practiceLine, recordGames, suggestFoci, type PracticePlan } from '../core/analyses/practice.ts'
import { addFacts, emptyHistory, isAoe4History, mergeGames, SUMMARIES_PER_SYNC, wantSummaries, type Aoe4History } from '../core/aoe4/history.ts'
import { compareSplits, isWorkerSplit, MAX_SPLITS, splitsFromReadings, type SplitCheck, type WorkerSplit } from '../core/aoe4/workers.ts'
import { buildFromGame, compareBuild, emptyLibrary, isBuildLibrary, MAX_BUILDS, playedFromScreen, type BuildLibrary, type BuildOrder, type PlayedBuild, type StepCheck } from '../core/aoe4/buildOrder.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import type { CoachSettings } from '../core/settings.ts'

/** A full sync is not repeated more often than this on its own. */
const SYNC_EVERY_MS = 6 * 3600_000

export interface SyncState {
  readonly status: 'idle' | 'syncing' | 'done' | 'error'
  /** Summaries fetched of those wanted, during a sync. */
  readonly done: number
  readonly total: number
  readonly message: string | null
}

export interface ProgressState {
  readonly aoe4: Aoe4History | null
  readonly sync: SyncState
  readonly practice: PracticePlan | null
  readonly builds: BuildLibrary
  /** False when "Remember my games" is off: the history and plan last for this session only. */
  readonly kept: boolean
}

export const NO_PROGRESS: ProgressState = { aoe4: null, sync: { status: 'idle', done: 0, total: 0, message: null }, practice: null, builds: emptyLibrary(), kept: false }

export interface ProgressHost {
  readonly bridge: SightlineBridge
  profile(): GameProfile | null
  settings(): CoachSettings | null
  /** Saved and this session's match records for the current pack. */
  matches(): readonly MatchRecord[]
  get(): ProgressState
  set(p: Partial<ProgressState>): void
}

const civName = (profile: GameProfile, id: string) => profile.knowledge.roster?.factions.find((f) => f.id === id)?.name ?? id

export class ProgressStore {
  private readonly host: ProgressHost
  private syncing = false

  constructor(host: ProgressHost) {
    this.host = host
  }

  /** The player's civilisation as the pack's id, from the Coach screen setting. */
  playerCiv(): string | null {
    const profile = this.host.profile()
    const roster = profile?.knowledge.roster
    const name = profile ? this.host.settings()?.context[profile.id]?.civ : null
    return roster && typeof name === 'string' ? (findFaction(roster, name)?.id ?? null) : null
  }

  async load(): Promise<void> {
    const profile = this.host.profile()
    if (!profile) return
    const id = profile.id
    const [aoe4, practice, builds] = await Promise.all([
      id === 'aoe4' ? this.host.bridge.loadDoc('aoe4world', id).catch(() => null) : Promise.resolve(null),
      this.host.bridge.loadDoc('practice', id).catch(() => null),
      this.host.bridge.loadDoc('builds', id).catch(() => null),
    ])
    if (this.host.profile()?.id !== id) return
    const linked = this.host.settings()?.aoe4world.profileId ?? null
    this.host.set({
      aoe4: isAoe4History(aoe4) && aoe4.profileId === linked ? aoe4 : null,
      practice: isPracticePlan(practice) && practice.foci.length ? practice : null,
      builds: isBuildLibrary(builds) ? builds : emptyLibrary(),
      kept: !!this.host.settings()?.saveTendencies,
      sync: NO_PROGRESS.sync,
    })
    this.refreshPlan()
    void this.syncIfDue()
  }

  /** Syncs when linked and the last sync is old (or never happened). */
  async syncIfDue(): Promise<void> {
    const h = this.host.get().aoe4
    const linked = this.host.settings()?.aoe4world.profileId
    if (this.host.profile()?.id !== 'aoe4' || !linked) return
    if (h && h.profileId === linked && h.syncedAt && Date.now() - Date.parse(h.syncedAt) < SYNC_EVERY_MS) return
    await this.sync()
  }

  /**
   * The player's games from AoE4World, then the summaries of the recent ones not fetched before (at
   * most 30 per sync, slowly). Kept with "Remember my games"; otherwise for this session.
   */
  async sync(): Promise<void> {
    const profile = this.host.profile()
    const pid = this.host.settings()?.aoe4world.profileId
    if (profile?.id !== 'aoe4' || !pid || this.syncing) return
    this.syncing = true
    const bridge = this.host.bridge
    const prev = this.host.get().aoe4
    let h = prev && prev.profileId === pid ? prev : emptyHistory(pid)
    this.host.set({ sync: { status: 'syncing', done: 0, total: 0, message: 'Reading your games list…' } })
    try {
      const list = await bridge.aoe4worldGames(pid)
      if (!list.ok) {
        this.host.set({ sync: { status: 'error', done: 0, total: 0, message: list.error } })
        return
      }
      h = { ...mergeGames(h, list.games), syncedAt: new Date().toISOString() }
      this.host.set({ aoe4: h })
      const want = wantSummaries(h, SUMMARIES_PER_SYNC)
      let done = 0
      let message: string | null = null
      while (want.length) {
        this.host.set({ sync: { status: 'syncing', done, total: done + want.length, message: 'Reading game summaries (one every second or two, so AoE4World is not overloaded)…' } })
        const batch = want.splice(0, 10)
        const r = await bridge.aoe4worldFacts(pid, batch)
        if (!r.ok) {
          message = r.error
          break
        }
        h = addFacts(h, r.facts, r.missing)
        done += batch.length
        this.host.set({ aoe4: h })
        if (r.busy) {
          message = 'AoE4World asked to slow down: the rest are read next time.'
          break
        }
      }
      const kept = await bridge.saveDoc('aoe4world', profile.id, h).catch(() => false)
      const games = h.games.length
      const withFacts = Object.keys(h.facts).length
      this.host.set({
        aoe4: h,
        kept,
        sync: { status: 'done', done, total: done, message: message ?? `${games} games read; ${withFacts} with their build and timings.${kept ? '' : ' Kept for this session only: turn on "Remember my games" to keep them.'}` },
      })
      this.refreshPlan()
    } finally {
      this.syncing = false
    }
  }

  async forgetHistory(): Promise<void> {
    const profile = this.host.profile()
    if (!profile) return
    this.host.set({ aoe4: null, sync: NO_PROGRESS.sync })
    // An empty history replaces the stored one.
    const pid = this.host.settings()?.aoe4world.profileId
    if (pid) await this.host.bridge.saveDoc('aoe4world', profile.id, emptyHistory(pid)).catch(() => false)
  }

  // --- Practice ------------------------------------------------------------------------------

  candidates() {
    const profile = this.host.profile()
    if (!profile) return []
    return suggestFoci(measurements(this.host.matches(), this.host.get().aoe4), profile, this.playerCiv())
  }

  /** Games with something measured (for the practice plan), and the player's usual numbers in them. */
  measured(): { games: number; usual: { villagers10: number | null; feudal: number | null } } {
    const ms = measurements(this.host.matches(), this.host.get().aoe4).slice(-10)
    const med = (xs: number[]) => (xs.length ? [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)] : null)
    const pick = (k: string) => ms.map((m) => m.values[k]).filter((x): x is number => typeof x === 'number')
    return { games: measurements(this.host.matches(), this.host.get().aoe4).length, usual: { villagers10: med(pick('villagers-10')), feudal: med(pick('feudal')) } }
  }

  async startPlan(foci = this.candidates().slice(0, 3)): Promise<PracticePlan | null> {
    const profile = this.host.profile()
    if (!profile || !foci.length) return null
    const plan = makePlan(profile.id, foci)
    this.host.set({ practice: plan })
    await this.host.bridge.saveDoc('practice', profile.id, plan).catch(() => false)
    return plan
  }

  async clearPlan(): Promise<void> {
    const profile = this.host.profile()
    this.host.set({ practice: null })
    if (profile) await this.host.bridge.saveDoc('practice', profile.id, { ...makePlan(profile.id, []), foci: [] }).catch(() => false)
  }

  /** Adds games measured since the plan started (after a match, or a sync). */
  refreshPlan(): void {
    const plan = this.host.get().practice
    const profile = this.host.profile()
    if (!plan || !profile || !plan.foci.length) return
    const next = recordGames(plan, measurements(this.host.matches(), this.host.get().aoe4))
    if (JSON.stringify(next) === JSON.stringify(plan)) return
    this.host.set({ practice: next })
    void this.host.bridge.saveDoc('practice', profile.id, next).catch(() => false)
  }

  practiceLine(): string | null {
    return practiceLine(this.host.get().practice)
  }

  currentFocus() {
    const p = this.host.get().practice
    return p ? currentFocus(p) : null
  }

  // --- Build orders ----------------------------------------------------------------------------

  /** The build to train with for a civilisation (the player's, when not given). */
  activeBuild(civ: string | null = this.playerCiv()): BuildOrder | null {
    const lib = this.host.get().builds
    const id = civ ? lib.active[civ] : undefined
    return (id && lib.builds.find((b) => b.id === id)) || null
  }

  private async saveLibrary(lib: BuildLibrary): Promise<void> {
    const profile = this.host.profile()
    this.host.set({ builds: lib })
    if (profile) await this.host.bridge.saveDoc('builds', profile.id, lib).catch(() => false)
  }

  async addBuild(build: BuildOrder, makeActive = true): Promise<void> {
    const lib = this.host.get().builds
    await this.saveLibrary({ ...lib, builds: [build, ...lib.builds.filter((b) => b.id !== build.id)].slice(0, MAX_BUILDS), active: makeActive ? { ...lib.active, [build.civ]: build.id } : lib.active })
  }

  async deleteBuild(id: string): Promise<void> {
    const lib = this.host.get().builds
    await this.saveLibrary({ ...lib, builds: lib.builds.filter((b) => b.id !== id), active: Object.fromEntries(Object.entries(lib.active).filter(([, v]) => v !== id)) })
  }

  /** The villagers on each resource the build wants, by time (replaces the build's split). */
  async setSplits(id: string, splits: readonly WorkerSplit[]): Promise<void> {
    const lib = this.host.get().builds
    const clean = [...splits].filter(isWorkerSplit).sort((a, b) => a.atSec - b.atSec).slice(0, MAX_SPLITS)
    await this.saveLibrary({ ...lib, builds: lib.builds.map((b) => (b.id === id ? { ...b, splits: clean } : b)) })
  }

  /** The games the coach watched with this civilisation, newest first. */
  private watched(civ: string): MatchRecord[] {
    const roster = this.host.profile()?.knowledge.roster
    return [...this.host.matches()].reverse().filter((m) => m.gameProfileId === 'aoe4' && roster && findFaction(roster, String(m.context?.playerFaction ?? ''))?.id === civ)
  }

  /** Split targets from the last game the coach watched with this civilisation where the numbers beside each resource were read. */
  splitsFromLastGame(civ: string, untilSec: number): { splits: WorkerSplit[]; endedAt: string } | null {
    for (const rec of this.watched(civ)) {
      const splits = splitsFromReadings(rec.readings, untilSec)
      if (splits.length) return { splits, endedAt: rec.endedAt }
    }
    return null
  }

  /** The build's split against the last watched game with its civilisation. */
  splitsAgainst(build: BuildOrder): { endedAt: string; checks: SplitCheck[] } | null {
    if (!build.splits?.length) return null
    const rec = this.watched(build.civ).find((r) => compareSplits(build.splits, r.readings).some((c) => c.had))
    return rec ? { endedAt: rec.endedAt, checks: compareSplits(build.splits, rec.readings) } : null
  }

  async setActive(civ: string, id: string | null): Promise<void> {
    const lib = this.host.get().builds
    const active = { ...lib.active }
    if (id) active[civ] = id
    else delete active[civ]
    await this.saveLibrary({ ...lib, active })
  }

  /** A build from one of the player's own AoE4World games. */
  async buildFromMyGame(gameId: number): Promise<BuildOrder | null> {
    const profile = this.host.profile()
    const h = this.host.get().aoe4
    const g = h?.games.find((x) => x.gameId === gameId)
    const f = h?.facts[gameId]
    if (!profile || !g || !f) return null
    const feudal = f.ages.feudal ? `, Feudal ${Math.floor(f.ages.feudal / 60)}:${String(f.ages.feudal % 60).padStart(2, '0')}` : ''
    const build = buildFromGame(
      { events: f.build, villagers: f.villagers, ages: f.ages },
      {
        civ: g.civ,
        name: `My ${civName(profile, g.civ)}${g.opponents.length === 1 ? ` vs ${civName(profile, g.opponents[0])}` : ''}${feudal}${g.result === 'win' ? ' (win)' : ''}`,
        source: 'mine',
        from: { gameId, player: 'you', rating: g.rating, opponentCiv: g.opponents[0], result: g.result },
        roster: profile.knowledge.roster,
      },
    )
    await this.addBuild(build)
    return build
  }

  /** A build from one player in an AoE4World game (a pasted link, or the suggestion). */
  async buildFromPlayer(gameId: number, p: GamePlayerBuild, rating: number | null = null): Promise<BuildOrder | null> {
    const profile = this.host.profile()
    if (!profile) return null
    const build = buildFromGame(
      { events: p.facts.build, villagers: p.facts.villagers, ages: p.facts.ages },
      {
        civ: p.civ,
        name: `${p.name}${rating ? ` (${rating})` : ''}: ${civName(profile, p.civ)}${p.opponentCiv ? ` vs ${civName(profile, p.opponentCiv)}` : ''}`,
        source: 'game',
        from: { gameId, player: p.name, rating, ...(p.opponentCiv ? { opponentCiv: p.opponentCiv } : {}), result: p.result },
        roster: profile.knowledge.roster,
      },
    )
    await this.addBuild(build)
    return build
  }

  /** A strong player's recent Ranked win with this civilisation, saved and set for it. Returns a message. */
  async suggestFromAoe4world(civ: string): Promise<string> {
    const profile = this.host.profile()
    const r = await this.host.bridge.aoe4worldSuggestBuild(civ).catch(() => ({ ok: false as const, error: 'Could not reach AoE4World.' }))
    if (!r.ok) return r.error
    const b = await this.buildFromPlayer(r.gameId, r.player, r.rating)
    return b && profile ? `Saved "${b.name}" from a recent Ranked win and set it for ${civName(profile, b.civ)}.` : 'That game had no build to use.'
  }

  /** The players in an AoE4World game, from its link, to make a build from one of them. */
  playersInGame(link: string) {
    return this.host.bridge.aoe4worldGameBuild(link).catch(() => ({ ok: false as const, error: 'Could not reach AoE4World.' }))
  }

  /** The player's most recent game with the build's civilisation, and how it went against the build. */
  lastGameAgainst(build: BuildOrder): { label: string; checks: StepCheck[] } | null {
    const profile = this.host.profile()
    if (!profile) return null
    const h = this.host.get().aoe4
    const fromHistory = h?.games.find((g) => g.civ === build.civ && h.facts[g.gameId] && g.gameId !== build.from?.gameId)
    const roster = profile.knowledge.roster
    const records = [...this.host.matches()].reverse().filter((m) => m.gameProfileId === 'aoe4' && roster && findFaction(roster, String(m.context?.playerFaction ?? ''))?.id === build.civ)
    const rec = records[0]
    const histAt = fromHistory ? fromHistory.startedAt * 1000 : -Infinity
    const recAt = rec ? Date.parse(rec.endedAt) : -Infinity
    if (fromHistory && histAt >= recAt - 3600_000) {
      const f = h!.facts[fromHistory.gameId]
      const played: PlayedBuild = { events: f.build, villagers: f.villagers, ages: f.ages }
      return { label: `Your last ${civName(profile, build.civ)} game on AoE4World (${new Date(histAt).toLocaleDateString()}${fromHistory.map ? `, ${fromHistory.map}` : ''})`, checks: compareBuild(build, played, { lastSec: fromHistory.durationSec }) }
    }
    if (rec) return { label: `Your last ${civName(profile, build.civ)} game the coach watched (${new Date(recAt).toLocaleDateString()}; times are when the screen first showed each thing)`, checks: compareBuild(build, playedFromScreen(rec), { lastSec: rec.durationSec }) }
    return null
  }
}
