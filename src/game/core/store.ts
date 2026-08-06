// Everything that should survive a refresh.
//
// The game had no persistence at all: your sensitivity sliders, your view, the
// name you play under and every drill score you had ever set went away the
// moment you reloaded the page. A training mode you cannot measure yourself
// against is most of the point of a training mode gone.
//
// One key, one JSON blob, read once at boot. Nothing here is worth a schema
// migration, so a version that doesn't match is simply discarded — losing your
// bests is a shame, not a bug report, and it beats trying to interpret a shape
// from a build that no longer exists.

const KEY = 'open-pitch'
const VERSION = 1

export interface DrillBest {
  score: number
  detail: string // whatever the drill wants to remember about that run
}

export interface Saved {
  v: number
  name: string
  view: '2d' | '3d'
  quality: 'low' | 'medium' | 'high'
  position: string
  teamSize: number
  halfLength: number
  singleKeeper: boolean
  heightSens: number
  curveSens: number
  muted: boolean
  binds: Record<string, string>
  bests: Record<string, DrillBest>
  relayUrl: string
  relayRoom: string
}

const DEFAULTS: Saved = {
  v: VERSION,
  name: '',
  view: '3d',
  quality: 'medium',
  position: 'FWD',
  teamSize: 4,
  halfLength: 120,
  singleKeeper: false,
  heightSens: 2,
  curveSens: 1,
  muted: false,
  binds: {},
  bests: {},
  relayUrl: 'ws://localhost:8787',
  relayRoom: 'PITCH',
}

// A page opened from a file:// URL in some browsers, or a private window with
// storage disabled, throws on access rather than returning null. None of that
// should stop you playing, so every path here falls back to memory.
function read(): Saved {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return { ...DEFAULTS }
    const o = JSON.parse(raw) as Partial<Saved>
    if (o.v !== VERSION) return { ...DEFAULTS }
    return sane({ ...DEFAULTS, ...o, binds: { ...o.binds }, bests: { ...o.bests } })
  } catch {
    return { ...DEFAULTS }
  }
}

// The blob is user-writable — it is a string in their own browser — and it is
// also whatever an older half-finished write left behind. A field that came
// back the wrong shape used to go straight into the menu: `view: "flat"` picks
// neither button, and a sensitivity of NaN silently kills every kick's shape.
// Anything that fails to be what it claims falls back to the default.
function sane(s: Saved): Saved {
  const num = (v: unknown, lo: number, hi: number, def: number) =>
    typeof v === 'number' && Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : def
  const oneOf = <T extends string | number>(v: unknown, allowed: readonly T[], def: T): T =>
    allowed.includes(v as T) ? (v as T) : def
  return {
    ...s,
    name: typeof s.name === 'string' ? s.name.slice(0, 14) : '',
    view: oneOf(s.view, ['2d', '3d'] as const, DEFAULTS.view),
    quality: oneOf(s.quality, ['low', 'medium', 'high'] as const, DEFAULTS.quality),
    position: oneOf(s.position, ['GK', 'DEF', 'MID', 'FWD'] as const, DEFAULTS.position),
    teamSize: oneOf(s.teamSize, [3, 4, 5, 6] as const, DEFAULTS.teamSize),
    halfLength: oneOf(s.halfLength, [60, 120, 180] as const, DEFAULTS.halfLength),
    singleKeeper: s.singleKeeper === true,
    muted: s.muted === true,
    heightSens: num(s.heightSens, 0.4, 5, DEFAULTS.heightSens),
    curveSens: num(s.curveSens, 0.2, 3, DEFAULTS.curveSens),
    relayUrl: typeof s.relayUrl === 'string' ? s.relayUrl.slice(0, 200) : DEFAULTS.relayUrl,
    relayRoom: typeof s.relayRoom === 'string' ? s.relayRoom.slice(0, 24) : DEFAULTS.relayRoom,
    binds: Object.fromEntries(
      Object.entries(s.binds ?? {}).filter(([, v]) => typeof v === 'string'),
    ) as Record<string, string>,
    bests: Object.fromEntries(
      Object.entries(s.bests ?? {}).filter(
        ([, v]) => v && typeof (v as DrillBest).score === 'number' && Number.isFinite((v as DrillBest).score),
      ),
    ) as Record<string, DrillBest>,
  }
}

class Store {
  private data: Saved = read()
  private dirty = false
  private timer = 0
  // True when the browser actually took the write. The settings screen says so,
  // because "my bests keep vanishing" is otherwise a mystery.
  available = true

  get<K extends keyof Saved>(k: K): Saved[K] {
    return this.data[k]
  }

  set<K extends keyof Saved>(k: K, v: Saved[K]) {
    if (this.data[k] === v) return
    this.data[k] = v
    this.queue()
  }

  // Drill bests are the one thing here that is written mid-play, so they go
  // through a helper that only keeps an improvement.
  recordBest(drill: string, score: number, detail: string): boolean {
    const cur = this.data.bests[drill]
    if (cur && cur.score >= score) return false
    this.data.bests[drill] = { score, detail }
    this.queue()
    return true
  }

  best(drill: string): DrillBest | null {
    return this.data.bests[drill] ?? null
  }

  bindings(): Record<string, string> {
    return { ...this.data.binds }
  }

  setBindings(b: Record<string, string>) {
    this.data.binds = { ...b }
    this.queue()
  }

  clear() {
    this.data = { ...DEFAULTS }
    try {
      localStorage.removeItem(KEY)
    } catch {
      /* nothing to remove */
    }
  }

  // Writing is debounced: a drill can beat its own best several times in a
  // second, and localStorage is synchronous.
  private queue() {
    this.dirty = true
    if (this.timer) return
    this.timer = setTimeout(() => {
      this.timer = 0
      this.flush()
    }, 250) as unknown as number
  }

  flush() {
    if (!this.dirty) return
    this.dirty = false
    try {
      localStorage.setItem(KEY, JSON.stringify(this.data))
      this.available = true
    } catch {
      this.available = false
    }
  }
}

export const store = new Store()

// A refresh can happen mid-drill; don't lose the last few seconds of it. The
// tests run this module under Node with a stub window, so the listener is only
// attached if there is really something to attach it to.
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('beforeunload', () => store.flush())
}
