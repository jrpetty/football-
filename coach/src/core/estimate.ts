// What playing with the coach watching costs, per game: an hour and a typical
// match at the player's own screen-reading settings and model. Measured from
// their own looks in that game once there are enough, otherwise estimated
// from list prices. And the warnings as spending nears the session cap or
// the monthly budget.

import type { GameProfile } from './profile/schema.ts'
import { visionPrefsFor, type CoachSettings } from './settings.ts'
import { measuredLook, type LookAverage, type UsageSummary } from './usage.ts'
import { cachedPromptTokens, costOf, estimateRange } from './vision/cost.ts'

/** Typical minutes of one match, by pack (else 20). */
const MATCH_MINUTES: Readonly<Record<string, number>> = { aoe4: 30, 'aoe2-de': 35, 'cod-mw4': 10, 'ea-fc': 15, 'synthetic-arena': 10 }

export function matchMinutes(packId: string): number {
  return MATCH_MINUTES[packId] ?? 20
}

export interface PlayEstimate {
  readonly packId: string
  readonly model: string
  readonly intervalMs: number
  readonly width: number
  /** Looks an hour at most: still screens (menus, pauses) are skipped, so often fewer. */
  readonly looksPerHour: number
  /** From list prices at these settings: without thinking, and with the thinking allowance. */
  readonly listPerLook: { readonly low: number; readonly high: number }
  readonly listPerHour: { readonly low: number; readonly high: number }
  /** The player's own looks in this game with this model (all time), once there are enough; per hour at the current refresh. */
  readonly measured: (LookAverage & { readonly perHour: number }) | null
  /** The figures to show: measured when there is a measurement, else the list-price range. */
  readonly perHour: { readonly low: number; readonly high: number }
  readonly matchMinutes: number
  /** A typical match; estimates add filling the reference cache once. */
  readonly perMatch: { readonly low: number; readonly high: number }
  /** A typical match at list prices, whatever has been measured. */
  readonly listPerMatch: { readonly low: number; readonly high: number }
}

export function playEstimate(
  profile: Pick<GameProfile, 'id' | 'vision' | 'knowledge'>,
  settings: Pick<CoachSettings, 'vision'>,
  cloud: { readonly visionModel: string; readonly visionThinking: 'auto' | 'off' },
  summary: Pick<UsageSummary, 'looks'> | null | undefined,
  model = cloud.visionModel,
): PlayEstimate | null {
  if (!profile.vision) return null
  const v = visionPrefsFor(profile, settings)
  const cached = cachedPromptTokens(profile)
  const r = estimateRange(model, { intervalMs: v.intervalMs, width: v.maxWidth, height: Math.round((v.maxWidth * 9) / 16), cachedTokens: cached, thinkingOff: cloud.visionThinking === 'off' })
  const m = measuredLook(summary, profile.id, model)
  const measured = m ? { ...m, perHour: m.usdPerLook * r.looksPerHour } : null
  const minutes = matchMinutes(profile.id)
  const perHour = measured ? { low: measured.perHour, high: measured.perHour } : { low: r.low, high: r.high }
  // Measured looks already include their share of cache writes.
  const fillOnce = costOf(model, { input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: cached })
  const fill = measured ? 0 : fillOnce
  return {
    packId: profile.id,
    model,
    intervalMs: v.intervalMs,
    width: v.maxWidth,
    looksPerHour: r.looksPerHour,
    listPerLook: { low: r.perLook, high: r.high / r.looksPerHour },
    listPerHour: { low: r.low, high: r.high },
    measured,
    perHour,
    matchMinutes: minutes,
    perMatch: { low: (perHour.low * minutes) / 60 + fill, high: (perHour.high * minutes) / 60 + fill },
    listPerMatch: { low: (r.low * minutes) / 60 + fillOnce, high: (r.high * minutes) / 60 + fillOnce },
  }
}

export interface SpendAlert {
  readonly level: 'warn' | 'stop'
  readonly text: string
}

/** Share of the session cap or monthly budget at which the app starts to warn. */
export const WARN_AT = 0.8

/** Warnings as this session's spend nears its cap, and the month's nears its budget (most serious first). */
export function spendAlerts(
  spend: { readonly usd: number; readonly monthUsd?: number },
  s: { readonly enabled: boolean; readonly sessionCapUsd: number; readonly monthlyBudgetUsd: number; readonly stopAtBudget: boolean },
): SpendAlert[] {
  if (!s.enabled) return []
  const out: SpendAlert[] = []
  const money = (v: number) => `$${v.toFixed(2)}`
  const month = spend.monthUsd ?? 0
  if (s.monthlyBudgetUsd > 0 && month >= s.monthlyBudgetUsd) {
    out.push(
      s.stopAtBudget
        ? { level: 'stop', text: `This month’s budget of ${money(s.monthlyBudgetUsd)} is reached: cloud calls are paused until next month or until you raise it.` }
        : { level: 'warn', text: `Over this month’s budget: ${money(month)} of ${money(s.monthlyBudgetUsd)}. Calls continue (stopping at the budget is off).` },
    )
  } else if (s.monthlyBudgetUsd > 0 && month >= s.monthlyBudgetUsd * WARN_AT) {
    out.push({ level: 'warn', text: `${Math.round((month / s.monthlyBudgetUsd) * 100)}% of this month’s ${money(s.monthlyBudgetUsd)} budget used (${money(month)}).` })
  }
  if (spend.usd >= s.sessionCapUsd) {
    out.unshift({ level: 'stop', text: `This session’s cap of ${money(s.sessionCapUsd)} is reached: cloud calls are paused until the next session or until you raise the cap.` })
  } else if (spend.usd >= s.sessionCapUsd * WARN_AT) {
    out.push({ level: 'warn', text: `${Math.round((spend.usd / s.sessionCapUsd) * 100)}% of this session’s ${money(s.sessionCapUsd)} cap used.` })
  }
  return out
}

/** Minutes until the session cap at the current rate (null without a rate). */
export function minutesToCap(spend: { readonly usd: number; readonly ratePerHour?: number | null }, capUsd: number): number | null {
  if (!spend.ratePerHour || spend.ratePerHour <= 0) return null
  return Math.max(0, ((capUsd - spend.usd) / spend.ratePerHour) * 60)
}
