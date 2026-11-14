// How often the coach looks at the screen, changeable on the Coach screen
// before and during capture (the full set is in Settings → Screen reading).
// During capture it also shows how long replies take, and says so when they
// are too slow for the chosen refresh time. The cost per hour is measured
// from the player's own looks once there are enough, else estimated.

import type { CloudStatus } from '../../../shared/cloud.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { playEstimate } from '../../core/estimate.ts'
import { visionPrefsFor, type CoachSettings } from '../../core/settings.ts'
import type { UsageSummary } from '../../core/usage.ts'
import type { VisionStatus } from '../../worker/protocol.ts'
import { usdRange } from '../format.ts'

const STEPS = [1000, 1500, 2000, 2500, 3000, 4000, 5000, 6000, 8000, 10_000, 15_000, 20_000]

export function RefreshControl({
  profile,
  settings,
  onSettings,
  cloud,
  vision,
  usage,
  compact = false,
}: {
  profile: GameProfile
  settings: CoachSettings
  onSettings: (patch: Partial<CoachSettings>) => void
  cloud: CloudStatus
  vision?: VisionStatus | null
  usage?: UsageSummary | null
  compact?: boolean
}) {
  if (!profile.vision) return null
  const v = visionPrefsFor(profile, settings)
  const packDefault = profile.vision.sampleIntervalMs
  const steps = [...new Set([...STEPS, v.intervalMs, packDefault])].sort((a, b) => a - b)
  const own = settings.vision[profile.id] ?? {}
  const askDefault = profile.vision.lookWhenAsked ?? false
  /** 0: look only when asked; otherwise every so many milliseconds. */
  const choose = (ms: number) => {
    const next = { ...settings.vision }
    const { intervalMs: _old, onDemand: _was, ...rest } = own
    const onDemand = ms === 0
    const prefs = { ...rest, ...(onDemand !== askDefault ? { onDemand } : {}), ...(!onDemand && ms !== packDefault ? { intervalMs: ms } : {}) }
    if (!Object.keys(prefs).length) delete next[profile.id]
    else next[profile.id] = prefs
    onSettings({ vision: next })
  }
  const est = playEstimate(profile, settings, cloud.settings, usage)!
  // Replies slower than the interval times the looks allowed at once: the coach cannot keep that pace.
  const slow = vision?.enabled && vision.latencyMs > 0 && vision.latencyMs > v.intervalMs * v.maxInFlight
  return (
    <div className={`refresh-control${compact ? ' compact' : ''}`}>
      <label>
        <span className="label">Look every</span>
        <select aria-label="Refresh time" value={v.onDemand ? 0 : v.intervalMs} onChange={(e) => choose(Number(e.target.value))}>
          <option value={0}>Only when I ask{askDefault ? ' (game default)' : ''}</option>
          {steps.map((ms) => (
            <option key={ms} value={ms}>
              {(ms / 1000).toFixed(ms % 1000 ? 1 : 0)} s{ms === packDefault && !askDefault ? ' (game default)' : ''}
            </option>
          ))}
        </select>
      </label>
      {!compact ? (
        <span className="muted small">
          {v.onDemand
            ? `Nothing spent while you don't ask; about ${usdRange(perLook(est, v.intervalMs).low, perLook(est, v.intervalMs).high)} a look when you do`
            : `${v.maxInFlight > 1 ? `${v.maxInFlight} looks at once · ` : ''}≈ ${usdRange(est.perHour.low, est.perHour.high)}/hour ${est.measured ? '(measured)' : '(estimate)'}`}
        </span>
      ) : null}
      {vision?.enabled && vision.latencyMs > 0 ? (
        <span className={`small ${slow ? 'warn-text' : 'muted'}`}>
          replies in {(vision.latencyMs / 1000).toFixed(1)} s{slow ? ': slower than this pace, raise “looks at once” in Settings or look less often' : ''}
        </span>
      ) : null}
    </div>
  )
}

/** The cost of one look, from the hourly estimate at this interval. */
export function perLook(est: { readonly perHour: { readonly low: number; readonly high: number } }, intervalMs: number): { low: number; high: number } {
  const looks = 3_600_000 / intervalMs
  return { low: est.perHour.low / looks, high: est.perHour.high / looks }
}
