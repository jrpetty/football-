// Player-facing settings. Shared by the renderer, the engine and the Electron
// main process (which re-validates anything the renderer sends it).

import { isDiscordWebhook } from './notify.ts'
import type { CoachTone, CoachingMode, Verbosity } from './types.ts'

export interface CoachSettings {
  readonly mode: CoachingMode
  readonly tone: CoachTone
  readonly verbosity: Verbosity
  /** Minimum seconds between two spoken messages. */
  readonly cooldownSec: number
  readonly muted: boolean
  readonly voice: {
    /** A local voice's voiceURI, or null for the system default local voice. */
    readonly voiceURI: string | null
    /** 0.5–2 */
    readonly rate: number
    /** 0–1 */
    readonly volume: number
  }
  /** Opt-in: keep structured tendency counts, match history and notes between sessions. Never frames. */
  readonly saveTendencies: boolean
  /** Keep a snapshot of each death (shooters) and each fight lost (strategy), in memory for this session only. */
  readonly autoMoments: boolean
  /** Opt-in: spoken questions, recognised locally. With the talk key, the microphone is open only while talking. */
  readonly voiceInput: boolean
  /** How the player talks to the coach: the talk key (tap, or hold while speaking), or always listening. */
  readonly listen: ListenMode
  /** Always listening: only speech that starts with "coach" is a question. */
  readonly wakeWord: boolean
  /** The speech recognition model: tiny (fast) or base (bigger, slower, more robust). */
  readonly speechModel: 'tiny' | 'base'
  /** Tournament / event play: all coaching output is disabled (event rules can be stricter than publishers'). */
  readonly tournament: boolean
  /** For the generic pack: which game this is (a policy registry id such as "valorant"), or null if unlisted. */
  readonly gameId: string | null
  /** Pack-defined player context (e.g. which side of the scoreboard is yours), by pack id. */
  readonly context: Readonly<Record<string, Readonly<Record<string, string | number>>>>
  readonly profileId: string | null
  readonly profileModeId: string | null
  /** Global keys (work while the game has focus): toggle talking, mark a moment to review, stop capture. */
  readonly hotkeys: Hotkeys
  /** The first-run setup was completed or skipped. */
  readonly onboarded: boolean
  /** Running live commentary of what the coach sees, where the game and mode allow speech during play. */
  readonly commentary: boolean
  /** The player's own screen-reading choices, by pack id; a missing field uses the pack's value. */
  readonly vision: Readonly<Record<string, VisionPrefs>>
  /** What the coach learns across games, by pack id; a missing field uses the default. */
  readonly learning: Readonly<Record<string, LearningPrefs>>
  /** Where the minimap is on the player's screen, by pack id, when it differs from the pack's (normalised; set with Align minimap). */
  readonly minimap: Readonly<Record<string, MinimapBox>>
  /** Where each HUD number is on the player's screen, by pack id and reading (Settings → Read numbers on this PC). */
  readonly hud: Readonly<Record<string, Readonly<Record<string, MinimapBox>>>>
  /** Once every marked number is read on this PC, look at the screen with the cloud model half as often. */
  readonly hudSaves: boolean
  /** The player's AoE4World profile, to check the coach's readings against the real game after a match (opt-in). */
  readonly aoe4world: { readonly profileId: number | null; readonly name: string | null; readonly auto: boolean }
  /** A match summary to the player's own Discord channel (webhook) after each match (opt-in: off until a webhook is set). */
  readonly discord: { readonly webhook: string | null; readonly afterMatch: boolean }
  /** Counter-Strike 2: the player's SteamID64, to find them in a match demo (set when they pick themselves once). */
  readonly steamId: string | null
}

export type ListenMode = 'key' | 'always'
export const LISTEN_MODES: readonly ListenMode[] = ['key', 'always']

export interface MinimapBox {
  readonly x: number
  readonly y: number
  readonly w: number
  readonly h: number
  readonly round?: boolean
}

/** The player's choices about learning across games, for one game. */
export interface LearningPrefs {
  /** Keep what each side was seen doing in this game's match records (default on). */
  readonly enabled?: boolean
  /** Say what past games of the same kind showed when a game starts (default on; only where live coaching is allowed). */
  readonly recall?: boolean
  /** Games of that kind needed before anything is recalled (default 1). */
  readonly minGames?: number
}

export const LEARNING_MIN_GAMES = { min: 1, max: 10 } as const

/** The learning choices in effect for a pack. */
export function learningPrefsFor(packId: string, settings: Pick<CoachSettings, 'learning'>): { enabled: boolean; recall: boolean; minGames: number } {
  const own = settings.learning[packId] ?? {}
  return { enabled: own.enabled ?? true, recall: own.recall ?? true, minGames: own.minGames ?? 1 }
}

/** How often and how sharply the coach looks at the screen for one game. */
export interface VisionPrefs {
  /** Time between two looks. */
  readonly intervalMs?: number
  /** Width the frame is scaled down to before it is sent (more pixels: more detail, more cost). */
  readonly maxWidth?: number
  /** Looks allowed at once, so a short interval is kept even when a reply takes longer than it. */
  readonly maxInFlight?: number
}

export const VISION_LIMITS = { intervalMs: { min: 1000, max: 20_000 }, maxInFlight: { min: 1, max: 4 }, widths: [768, 1024, 1280, 1600] } as const

/** Typical time for one look to come back; used to keep a short interval with enough looks in flight. */
const TYPICAL_REPLY_MS = 3000

/** The screen-reading settings in effect for a pack: the player's choices over the pack's defaults. */
export function visionPrefsFor(
  pack: { readonly id: string; readonly vision?: { readonly sampleIntervalMs: number; readonly maxWidth: number; readonly maxInFlight?: number } },
  settings: Pick<CoachSettings, 'vision'>,
): { intervalMs: number; maxWidth: number; maxInFlight: number; custom: boolean } {
  const own = settings.vision[pack.id] ?? {}
  const intervalMs = own.intervalMs ?? pack.vision?.sampleIntervalMs ?? 3000
  const maxWidth = own.maxWidth ?? pack.vision?.maxWidth ?? 1280
  // Unless the player chose, allow enough looks at once to keep the interval while replies are out.
  const auto = Math.max(pack.vision?.maxInFlight ?? 1, Math.min(VISION_LIMITS.maxInFlight.max, Math.ceil(TYPICAL_REPLY_MS / intervalMs)))
  return { intervalMs, maxWidth, maxInFlight: own.maxInFlight ?? auto, custom: Object.keys(own).length > 0 }
}

export interface Hotkeys {
  readonly talk: string
  readonly mark: string
  readonly stop: string
}

/**
 * Accelerators accepted for global keys: optional modifiers plus one key.
 * Letters, digits and other everyday keys need a modifier so typing in a game
 * chat cannot trigger them; F-keys and a few rarely used keys may stand alone.
 */
const SOLO_KEYS = /^(F([1-9]|1[0-9]|2[0-4])|Pause|Scrolllock|Insert|Home|End|PageUp|PageDown|num[0-9]|numadd|numsub|nummult|numdiv|numdec)$/
const MOD_KEYS = /^([A-Z0-9]|Space|Tab|Backspace|Delete|Up|Down|Left|Right|Plus|-|=|\[|\]|;|'|,|\.|\/|`)$/
export function isValidHotkey(accel: unknown): accel is string {
  if (typeof accel !== 'string' || accel.length > 40) return false
  const parts = accel.split('+')
  const key = parts.pop()!
  const mods = parts
  const allowed = ['CommandOrControl', 'Ctrl', 'Alt', 'Shift']
  if (new Set(mods).size !== mods.length || !mods.every((m) => allowed.includes(m))) return false
  return SOLO_KEYS.test(key) || (mods.length > 0 && MOD_KEYS.test(key))
}

export const DEFAULT_HOTKEYS: Hotkeys = { talk: 'F8', mark: 'F9', stop: 'CommandOrControl+Shift+F12' }

export const COACHING_MODES: readonly CoachingMode[] = ['silent', 'post-round', 'post-match', 'training', 'competitive-safe']
export const TONES: readonly CoachTone[] = ['gentle', 'direct', 'analytical', 'high-energy']
export const VERBOSITIES: readonly Verbosity[] = ['minimal', 'balanced', 'detailed']

export const DEFAULT_SETTINGS: CoachSettings = {
  // The most conservative speaking mode is the default.
  mode: 'competitive-safe',
  tone: 'direct',
  verbosity: 'balanced',
  cooldownSec: 20,
  muted: false,
  voice: { voiceURI: null, rate: 1, volume: 0.8 },
  saveTendencies: false,
  autoMoments: true,
  voiceInput: false,
  listen: 'key',
  wakeWord: true,
  speechModel: 'tiny',
  tournament: false,
  gameId: null,
  context: {},
  profileId: null,
  profileModeId: null,
  hotkeys: DEFAULT_HOTKEYS,
  onboarded: false,
  commentary: false,
  vision: {},
  learning: {},
  minimap: {},
  hud: {},
  hudSaves: true,
  // Checking each match is on once a profile is linked (linking is the opt-in).
  aoe4world: { profileId: null, name: null, auto: true },
  discord: { webhook: null, afterMatch: true },
  steamId: null,
}

export const COOLDOWN_RANGE = { min: 5, max: 300 } as const

/** Coerce untrusted input into valid settings, falling back field by field. */
export function sanitizeSettings(input: unknown, base: CoachSettings = DEFAULT_SETTINGS): CoachSettings {
  if (typeof input !== 'object' || input === null) return base
  const o = input as Record<string, unknown>
  const voice = (typeof o.voice === 'object' && o.voice !== null ? o.voice : {}) as Record<string, unknown>
  const pick = <T extends string>(v: unknown, options: readonly T[], fallback: T): T =>
    typeof v === 'string' && options.includes(v as T) ? (v as T) : fallback
  const num = (v: unknown, lo: number, hi: number, fallback: number): number =>
    typeof v === 'number' && Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : fallback
  const id = (v: unknown, fallback: string | null): string | null =>
    v === null ? null : typeof v === 'string' && /^[a-z0-9][a-z0-9-]{0,63}$/.test(v) ? v : fallback

  return {
    mode: pick(o.mode, COACHING_MODES, base.mode),
    tone: pick(o.tone, TONES, base.tone),
    verbosity: pick(o.verbosity, VERBOSITIES, base.verbosity),
    cooldownSec: Math.round(num(o.cooldownSec, COOLDOWN_RANGE.min, COOLDOWN_RANGE.max, base.cooldownSec)),
    muted: typeof o.muted === 'boolean' ? o.muted : base.muted,
    voice: {
      voiceURI:
        voice.voiceURI === null
          ? null
          : typeof voice.voiceURI === 'string' && voice.voiceURI.length <= 300
            ? voice.voiceURI
            : base.voice.voiceURI,
      rate: num(voice.rate, 0.5, 2, base.voice.rate),
      volume: num(voice.volume, 0, 1, base.voice.volume),
    },
    saveTendencies: typeof o.saveTendencies === 'boolean' ? o.saveTendencies : base.saveTendencies,
    autoMoments: typeof o.autoMoments === 'boolean' ? o.autoMoments : base.autoMoments,
    voiceInput: typeof o.voiceInput === 'boolean' ? o.voiceInput : base.voiceInput,
    listen: pick(o.listen, LISTEN_MODES, base.listen),
    wakeWord: typeof o.wakeWord === 'boolean' ? o.wakeWord : base.wakeWord,
    speechModel: o.speechModel === 'base' || o.speechModel === 'tiny' ? o.speechModel : base.speechModel,
    tournament: typeof o.tournament === 'boolean' ? o.tournament : base.tournament,
    gameId: id(o.gameId, base.gameId),
    context: sanitizeContext(o.context, base.context),
    profileId: id(o.profileId, base.profileId),
    profileModeId: id(o.profileModeId, base.profileModeId),
    hotkeys: sanitizeHotkeys(o.hotkeys, base.hotkeys),
    onboarded: typeof o.onboarded === 'boolean' ? o.onboarded : base.onboarded,
    commentary: typeof o.commentary === 'boolean' ? o.commentary : base.commentary,
    vision: sanitizeVision(o.vision, base.vision),
    learning: sanitizeLearning(o.learning, base.learning),
    minimap: sanitizeMinimap(o.minimap, base.minimap),
    hud: sanitizeHud(o.hud, base.hud),
    hudSaves: typeof o.hudSaves === 'boolean' ? o.hudSaves : base.hudSaves,
    aoe4world: sanitizeAoe4world(o.aoe4world, base.aoe4world),
    discord: sanitizeDiscord(o.discord, base.discord),
    steamId: o.steamId === null ? null : typeof o.steamId === 'string' && /^7656\d{13}$/.test(o.steamId) ? o.steamId : base.steamId,
  }
}

function sanitizeDiscord(v: unknown, base: CoachSettings['discord']): CoachSettings['discord'] {
  if (typeof v !== 'object' || v === null) return base
  const o = v as Record<string, unknown>
  return { webhook: o.webhook === null ? null : isDiscordWebhook(o.webhook) ? o.webhook : base.webhook, afterMatch: typeof o.afterMatch === 'boolean' ? o.afterMatch : base.afterMatch }
}

function sanitizeAoe4world(v: unknown, base: CoachSettings['aoe4world']): CoachSettings['aoe4world'] {
  if (typeof v !== 'object' || v === null) return base
  const o = v as Record<string, unknown>
  const id = o.profileId === null ? null : typeof o.profileId === 'number' && Number.isInteger(o.profileId) && o.profileId > 0 && o.profileId < 1e10 ? o.profileId : base.profileId
  return {
    profileId: id,
    name: id === null ? null : typeof o.name === 'string' ? o.name.slice(0, 60) : base.name,
    auto: typeof o.auto === 'boolean' ? o.auto : base.auto,
  }
}

function sanitizeHud(v: unknown, base: CoachSettings['hud']): CoachSettings['hud'] {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return base
  const out: Record<string, Record<string, MinimapBox>> = {}
  for (const [pack, raw] of Object.entries(v).slice(0, 50)) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(pack) || typeof raw !== 'object' || raw === null || Array.isArray(raw)) continue
    const boxes = sanitizeHudBoxes(raw)
    if (Object.keys(boxes).length) out[pack] = boxes
  }
  return out
}

/** Number boxes: small (a HUD number is a sliver of the screen), inside the frame. */
function sanitizeHudBoxes(raw: object): Record<string, MinimapBox> {
  const out: Record<string, MinimapBox> = {}
  for (const [key, b] of Object.entries(raw).slice(0, 40)) {
    if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key) || typeof b !== 'object' || b === null) continue
    const o = b as Record<string, unknown>
    const n = (k: string) => (typeof o[k] === 'number' && Number.isFinite(o[k]) ? Math.round((o[k] as number) * 10000) / 10000 : NaN)
    const [x, y, w, h] = [n('x'), n('y'), n('w'), n('h')]
    if (![x, y, w, h].every((q) => q >= 0 && q <= 1) || w < 0.005 || h < 0.005 || w > 0.5 || h > 0.3 || x + w > 1.0001 || y + h > 1.0001) continue
    out[key] = { x, y, w, h }
  }
  return out
}

function sanitizeMinimap(v: unknown, base: CoachSettings['minimap']): CoachSettings['minimap'] {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return base
  const out: Record<string, MinimapBox> = {}
  for (const [pack, raw] of Object.entries(v).slice(0, 50)) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(pack) || typeof raw !== 'object' || raw === null) continue
    const o = raw as Record<string, unknown>
    const n = (k: string) => (typeof o[k] === 'number' && Number.isFinite(o[k]) ? Math.round((o[k] as number) * 10000) / 10000 : NaN)
    const [x, y, w, h] = [n('x'), n('y'), n('w'), n('h')]
    // A sensible box: inside the frame and not tiny.
    if (![x, y, w, h].every((q) => q >= 0 && q <= 1) || w < 0.03 || h < 0.03 || x + w > 1.0001 || y + h > 1.0001) continue
    out[pack] = { x, y, w, h, ...(o.round === true ? { round: true } : {}) }
  }
  return out
}

function sanitizeLearning(v: unknown, base: CoachSettings['learning']): CoachSettings['learning'] {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return base
  const out: Record<string, LearningPrefs> = {}
  for (const [pack, raw] of Object.entries(v).slice(0, 50)) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(pack) || typeof raw !== 'object' || raw === null) continue
    const o = raw as Record<string, unknown>
    const prefs: { enabled?: boolean; recall?: boolean; minGames?: number } = {}
    if (typeof o.enabled === 'boolean') prefs.enabled = o.enabled
    if (typeof o.recall === 'boolean') prefs.recall = o.recall
    if (typeof o.minGames === 'number' && Number.isInteger(o.minGames)) prefs.minGames = Math.min(LEARNING_MIN_GAMES.max, Math.max(LEARNING_MIN_GAMES.min, o.minGames))
    if (Object.keys(prefs).length) out[pack] = prefs
  }
  return out
}

function sanitizeVision(v: unknown, base: CoachSettings['vision']): CoachSettings['vision'] {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return base
  const out: Record<string, VisionPrefs> = {}
  for (const [pack, raw] of Object.entries(v).slice(0, 50)) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(pack) || typeof raw !== 'object' || raw === null) continue
    const o = raw as Record<string, unknown>
    const prefs: { intervalMs?: number; maxWidth?: number; maxInFlight?: number } = {}
    const L = VISION_LIMITS
    if (typeof o.intervalMs === 'number' && Number.isFinite(o.intervalMs)) prefs.intervalMs = Math.round(Math.min(L.intervalMs.max, Math.max(L.intervalMs.min, o.intervalMs)) / 100) * 100
    if (typeof o.maxWidth === 'number' && (L.widths as readonly number[]).includes(o.maxWidth)) prefs.maxWidth = o.maxWidth
    if (typeof o.maxInFlight === 'number' && Number.isInteger(o.maxInFlight)) prefs.maxInFlight = Math.min(L.maxInFlight.max, Math.max(L.maxInFlight.min, o.maxInFlight))
    if (Object.keys(prefs).length) out[pack] = prefs
  }
  return out
}

function sanitizeHotkeys(v: unknown, base: Hotkeys): Hotkeys {
  const o = (typeof v === 'object' && v !== null ? v : {}) as Record<string, unknown>
  const out = {
    talk: isValidHotkey(o.talk) ? o.talk : base.talk,
    mark: isValidHotkey(o.mark) ? o.mark : base.mark,
    stop: isValidHotkey(o.stop) ? o.stop : base.stop,
  }
  // One key, one job.
  return new Set(Object.values(out)).size === 3 ? out : base
}

/** Player context: small maps of short strings or finite numbers, by pack id. */
function sanitizeContext(v: unknown, base: CoachSettings['context']): CoachSettings['context'] {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) return base
  const out: Record<string, Record<string, string | number>> = {}
  for (const [pack, values] of Object.entries(v).slice(0, 50)) {
    if (!/^[a-z0-9][a-z0-9_-]{0,63}$/.test(pack) || typeof values !== 'object' || values === null) continue
    const clean: Record<string, string | number> = {}
    for (const [k, x] of Object.entries(values).slice(0, 20)) {
      if (!/^[a-zA-Z][a-zA-Z0-9]{0,40}$/.test(k)) continue
      if (typeof x === 'number' && Number.isFinite(x)) clean[k] = x
      else if (typeof x === 'string' && x.length <= 80) clean[k] = x
    }
    out[pack] = clean
  }
  return out
}

export const MODE_INFO: Record<CoachingMode, { label: string; summary: string; live: boolean }> = {
  silent: {
    label: 'Silent tracker',
    summary: 'Records visible patterns locally. Never speaks.',
    live: false,
  },
  'post-round': {
    label: 'Post-round review',
    summary: 'Holds feedback until a round visibly ends, then gives one short note.',
    live: false,
  },
  'post-match': {
    label: 'Post-match review',
    summary: 'Stays quiet until the match ends, then gives a spoken and written summary.',
    live: false,
  },
  training: {
    label: 'Training coach',
    summary: 'Brief live tips, only where the game profile says this mode allows them.',
    live: true,
  },
  'competitive-safe': {
    label: 'Competitive-safe',
    summary: 'Never gives live directions. Reflective notes at round or match end only.',
    live: false,
  },
}

export const TONE_INFO: Record<CoachTone, { label: string; sample: string }> = {
  gentle: { label: 'Gentle', sample: 'Something I noticed — no pressure.' },
  direct: { label: 'Direct', sample: 'Here is the pattern. Here is the fix.' },
  analytical: { label: 'Analytical', sample: 'Counts, rates, and how sure I am.' },
  'high-energy': { label: 'High-energy', sample: 'Big energy, short and punchy.' },
}
