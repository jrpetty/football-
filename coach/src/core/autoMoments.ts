// Automatic snapshots at key moments: each death in a shooter (the killcam or
// death screen the coach read it from) and each fight lost in a strategy game
// (the fight's last frame on screen), with the circumstances read for it. The
// picture is the frame already sampled for screen reading, so nothing extra is
// captured or sent; it is kept in memory for this session only, like a marked
// moment, and never written to disk.

import type { GameProfile } from './profile/schema.ts'
import type { GameEvent } from './types.ts'

export interface AutoMomentInfo {
  /** The event it was taken for (the pack's loss event, e.g. "death", "fight_lost"). */
  readonly event: string
  readonly label: string
  /** The circumstances read for it, in the pack's words ("Cover when you died: In the open"). */
  readonly tags: readonly string[]
}

const humanize = (v: string) => v.replace(/_/g, ' ')

/** What to keep for an event, or null when it is not a key moment for this pack. */
export function autoMomentFor(profile: GameProfile, ev: GameEvent): AutoMomentInfo | null {
  const factors = (profile.factors ?? []).filter((f) => f.lossEvent === ev.ruleId)
  if (!factors.length || ev.manual) return null
  const tags: string[] = []
  for (const f of factors) {
    const v = f.attribute ? ev.attributes?.[f.attribute] : undefined
    if (v === undefined || v === null || v === '' || v === 'unknown') continue
    const label = f.values.find((x) => x.value === String(v))?.label ?? humanize(String(v))
    tags.push(`${f.label}: ${label}`)
  }
  return { event: ev.ruleId, label: ev.label, tags }
}

/** The last few sampled frames (by capture time), to find the one an event was read from. */
export class RecentFrames<T> {
  private readonly cap: number
  private items: { at: number; item: T }[] = []

  constructor(cap = 12) {
    this.cap = cap
  }

  keep(at: number, item: T): T | null {
    this.items.push({ at, item })
    return this.items.length > this.cap ? this.items.shift()!.item : null
  }

  /** The frame taken closest to `at`, within `withinMs`; it is removed (each frame makes one snapshot). */
  take(at: number, withinMs = 1500): T | null {
    let best = -1
    for (let i = 0; i < this.items.length; i++) {
      const d = Math.abs(this.items[i].at - at)
      if (d <= withinMs && (best < 0 || d < Math.abs(this.items[best].at - at))) best = i
    }
    if (best < 0) return null
    return this.items.splice(best, 1)[0].item
  }

  clear(): T[] {
    const all = this.items.map((x) => x.item)
    this.items = []
    return all
  }
}
