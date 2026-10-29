// What playing with the coach watching costs, per game: an hour and a typical
// match at the player's own screen-reading settings and model. Measured from
// their own looks in that game once there are enough, otherwise estimated
// from list prices. And the warnings as spending nears the session cap or
// the monthly budget.

import type { GameProfile } from './profile/schema.ts'
import { visionPrefsFor, type CoachSettings } from './settings.ts'
import { measuredLook, type LookAverage, type UsageSummary } from './usage.ts'
import { cachedPromptTokens, costOf, estimateRange } from './vision/cost.ts'
import { DETAIL_SHARE_ASSUMED, FAST_VISION_MODEL } from './vision/tiers.ts'

/** Typical minutes of one match, by pack (else 20). */
const MATCH_MINUTES: Readonly<Record<string, number>> = {
  aoe4: 30,
  'aoe2-de': 35,
  'cod-mw4': 10,
  // A Premier or Competitive match: up to 24 rounds of about 1:45 each, with buy time and half-time.
  cs2: 40,
  'ea-fc': 15,
  'synthetic-arena': 10,
}

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
  /** Smart reading: the share of looks also read by the chosen model (measured this session, else assumed). */
  readonly smart: { readonly share: number; readonly measuredShare: boolean } | null
}

export function playEstimate(
  profile: Pick<GameProfile, 'id' | 'vision' | 'knowledge'>,
  settings: Pick<CoachSettings, 'vision'>,
  cloud: { readonly visionModel: string; readonly visionThinking: 'auto' | 'off'; readonly smartReading?: boolean },
  summary: Pick<UsageSummary, 'looks'> | null | undefined,
  model = cloud.visionModel,
  /** Smart reading: this session's share of looks sent on to the chosen model, once measured. */
  detailShare: number | null = null,
): PlayEstimate | null {
  if (!profile.vision) return null
  const v = visionPrefsFor(profile, settings)
  const cached = cachedPromptTokens(profile)
  const shape = { intervalMs: v.intervalMs, width: v.maxWidth, height: Math.round((v.maxWidth * 9) / 16), cachedTokens: cached }
  const single = estimateRange(model, { ...shape, thinkingOff: cloud.visionThinking === 'off' })
  const smart = cloud.smartReading && model !== FAST_VISION_MODEL ? { share: detailShare ?? DETAIL_SHARE_ASSUMED, measuredShare: detailShare !== null } : null
  // Smart reading: every look by the fast model, the share sent on by the chosen one as well.
  const fast = smart ? estimateRange(FAST_VISION_MODEL, { ...shape, thinkingOff: true }) : null
  const r = fast && smart ? { ...single, perLook: fast.perLook + smart.share * single.perLook, low: fast.low + smart.share * single.low, high: fast.high + smart.share * single.high } : single
  const mFast = smart ? measuredLook(summary, profile.id, FAST_VISION_MODEL) : null
  const mDetail = measuredLook(summary, profile.id, model, smart ? 5 : undefined)
  const m = smart ? (mFast ? { ...mFast, usdPerLook: mFast.usdPerLook + smart.share * (mDetail?.usdPerLook ?? single.perLook) } : null) : mDetail
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
    smart,
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
