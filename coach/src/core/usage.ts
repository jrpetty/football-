// What the cloud has cost, kept across sessions: every call's token counts and
// estimated USD, by day, feature (screen reading, answers, reviews, research,
// knowledge building), model and game. From that: today, this week, this
// month (with a projection to the month's end), all time, a daily history,
// and, per game and model, what one look actually costs on average, which
// makes the hourly and per-match estimates measured rather than assumed.
// Only numbers are kept: no prompts, images or answers.

export const USAGE_FEATURES = ['vision', 'answer', 'review', 'research', 'knowledge'] as const
export type UsageFeature = (typeof USAGE_FEATURES)[number]

export const FEATURE_LABEL: Readonly<Record<UsageFeature, string>> = {
  vision: 'Screen reading',
  answer: 'Answers',
  review: 'Reviews',
  research: 'Research',
  knowledge: 'Knowledge building',
}

export interface UsageCounts {
  calls: number
  usd: number
  input: number
  cacheRead: number
  cacheWrite: number
  output: number
  thinking: number
  webSearches: number
}

/** Persisted: day (local, YYYY-MM-DD) → "feature|model|pack" → counts. */
export interface UsageLedger {
  readonly version: 1
  days: Record<string, Record<string, UsageCounts>>
}

export interface UsageCall {
  readonly at: Date
  readonly feature: UsageFeature
  /** The model that served the call. */
  readonly model: string
  readonly pack: string | null
  readonly usd: number
  readonly input: number
  readonly cacheRead: number
  readonly cacheWrite: number
  readonly output: number
  readonly thinking: number
  readonly webSearches: number
}

/** Days of history kept. */
const KEEP_DAYS = 400

export function emptyCounts(): UsageCounts {
  return { calls: 0, usd: 0, input: 0, cacheRead: 0, cacheWrite: 0, output: 0, thinking: 0, webSearches: 0 }
}

export function emptyLedger(): UsageLedger {
  return { version: 1, days: {} }
}

/** The local calendar day of a moment. */
export function dayOf(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function add(into: UsageCounts, c: Omit<UsageCounts, 'calls'> & { calls?: number }): void {
  into.calls += c.calls ?? 1
  into.usd += c.usd
  into.input += c.input
  into.cacheRead += c.cacheRead
  into.cacheWrite += c.cacheWrite
  into.output += c.output
  into.thinking += c.thinking
  into.webSearches += c.webSearches
}

/** Records one call (and drops days older than the history kept). */
export function recordCall(ledger: UsageLedger, call: UsageCall): void {
  const day = dayOf(call.at)
  const key = `${call.feature}|${call.model}|${call.pack ?? ''}`
  const d = (ledger.days[day] ??= {})
  add((d[key] ??= emptyCounts()), call)
  const days = Object.keys(ledger.days).sort()
  for (const old of days.slice(0, Math.max(0, days.length - KEEP_DAYS))) delete ledger.days[old]
}

/** Accepts a stored ledger only if it has the expected shape (else a fresh one). */
export function cleanLedger(raw: unknown): UsageLedger {
  if (!raw || typeof raw !== 'object' || (raw as { version?: unknown }).version !== 1) return emptyLedger()
  const days: UsageLedger['days'] = {}
  for (const [day, rows] of Object.entries((raw as { days?: unknown }).days ?? {})) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day) || !rows || typeof rows !== 'object') continue
    for (const [key, c] of Object.entries(rows as Record<string, unknown>)) {
      if (typeof key !== 'string' || key.length > 200 || !c || typeof c !== 'object') continue
      const o = c as Record<string, unknown>
      const n = (k: keyof UsageCounts) => (typeof o[k] === 'number' && Number.isFinite(o[k]) && (o[k] as number) >= 0 ? (o[k] as number) : 0)
      ;(days[day] ??= {})[key] = { calls: n('calls'), usd: n('usd'), input: n('input'), cacheRead: n('cacheRead'), cacheWrite: n('cacheWrite'), output: n('output'), thinking: n('thinking'), webSearches: n('webSearches') }
    }
  }
  return { version: 1, days }
}

export interface LookAverage {
  readonly pack: string
  readonly model: string
  readonly looks: number
  readonly usdPerLook: number
  readonly input: number
  readonly cached: number
  readonly output: number
  readonly thinking: number
}

export interface UsageSummary {
  readonly today: UsageCounts
  readonly last7: UsageCounts
  readonly thisMonth: UsageCounts
  readonly allTime: UsageCounts
  /** This month's spend at its end, if the last 7 days' daily average continues. */
  readonly monthProjection: number
  /** The last 30 days, oldest first, with spend by feature. */
  readonly daily: readonly { readonly day: string; readonly usd: number; readonly byFeature: Readonly<Partial<Record<UsageFeature, number>>> }[]
  readonly byFeature: readonly { readonly feature: UsageFeature; readonly counts: UsageCounts }[]
  readonly byModel: readonly { readonly model: string; readonly counts: UsageCounts }[]
  readonly byPack: readonly { readonly pack: string; readonly counts: UsageCounts }[]
  /** Screen reading: what one look actually cost, by game and model (all time). */
  readonly looks: readonly LookAverage[]
  /** Calls by feature and model, all time (for what one answer or review costs on average). */
  readonly perFeatureModel: readonly { readonly feature: UsageFeature; readonly model: string; readonly counts: UsageCounts }[]
  /** The first day with any use, or null. */
  readonly since: string | null
}

function parse(key: string): { feature: UsageFeature; model: string; pack: string } {
  const [feature, model, pack] = key.split('|')
  return { feature: feature as UsageFeature, model: model ?? '', pack: pack ?? '' }
}

/** Everything the Usage view shows, as of `now` (local days). */
export function summarizeUsage(ledger: UsageLedger, now: Date): UsageSummary {
  const today = dayOf(now)
  const month = today.slice(0, 7)
  const dayAt = (back: number) => dayOf(new Date(now.getFullYear(), now.getMonth(), now.getDate() - back))
  const week = new Set(Array.from({ length: 7 }, (_, i) => dayAt(i)))
  const totals = { today: emptyCounts(), last7: emptyCounts(), thisMonth: emptyCounts(), allTime: emptyCounts() }
  const byFeature = new Map<UsageFeature, UsageCounts>()
  const byModel = new Map<string, UsageCounts>()
  const byPack = new Map<string, UsageCounts>()
  const looks = new Map<string, UsageCounts>()
  const perFeatureModel = new Map<string, UsageCounts>()
  let since: string | null = null
  for (const [day, rows] of Object.entries(ledger.days)) {
    for (const [key, c] of Object.entries(rows)) {
      const k = parse(key)
      if (c.calls > 0 && (since === null || day < since)) since = day
      add(totals.allTime, c)
      const fm = `${k.feature}|${k.model}`
      add(perFeatureModel.get(fm) ?? (perFeatureModel.set(fm, emptyCounts()), perFeatureModel.get(fm)!), c)
      if (day === today) add(totals.today, c)
      if (week.has(day)) add(totals.last7, c)
      if (day.startsWith(month)) {
        add(totals.thisMonth, c)
        add(byFeature.get(k.feature) ?? (byFeature.set(k.feature, emptyCounts()), byFeature.get(k.feature)!), c)
        add(byModel.get(k.model) ?? (byModel.set(k.model, emptyCounts()), byModel.get(k.model)!), c)
        if (k.pack) add(byPack.get(k.pack) ?? (byPack.set(k.pack, emptyCounts()), byPack.get(k.pack)!), c)
      }
      if (k.feature === 'vision' && k.pack) {
        const lk = `${k.pack}|${k.model}`
        add(looks.get(lk) ?? (looks.set(lk, emptyCounts()), looks.get(lk)!), c)
      }
    }
  }
  const daily = Array.from({ length: 30 }, (_, i) => dayAt(29 - i)).map((day) => {
    const by: Partial<Record<UsageFeature, number>> = {}
    let usd = 0
    for (const [key, c] of Object.entries(ledger.days[day] ?? {})) {
      const f = parse(key).feature
      by[f] = (by[f] ?? 0) + c.usd
      usd += c.usd
    }
    return { day, usd, byFeature: by }
  })
  const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()
  const daysLeft = daysInMonth - now.getDate()
  const sorted = <K>(m: Map<K, UsageCounts>) => [...m].sort((a, b) => b[1].usd - a[1].usd)
  return {
    ...totals,
    monthProjection: totals.thisMonth.usd + (totals.last7.usd / 7) * daysLeft,
    daily,
    byFeature: sorted(byFeature).map(([feature, counts]) => ({ feature, counts })),
    byModel: sorted(byModel).map(([model, counts]) => ({ model, counts })),
    byPack: sorted(byPack).map(([pack, counts]) => ({ pack, counts })),
    looks: sorted(looks)
      .filter(([, c]) => c.calls > 0)
      .map(([k, c]) => {
        const [pack, model] = k.split('|')
        const per = (v: number) => Math.round(v / c.calls)
        return { pack, model, looks: c.calls, usdPerLook: c.usd / c.calls, input: per(c.input), cached: per(c.cacheRead + c.cacheWrite), output: per(c.output), thinking: per(c.thinking) }
      }),
    perFeatureModel: sorted(perFeatureModel).map(([k, counts]) => {
      const [feature, model] = k.split('|')
      return { feature: feature as UsageFeature, model, counts }
    }),
    since,
  }
}

/** What one call of a feature has cost on average with a model (all time), once there are a few. */
export function averageCall(summary: Pick<UsageSummary, 'perFeatureModel'> | null | undefined, feature: UsageFeature, model: string, min = 3): { readonly calls: number; readonly usd: number } | null {
  const f = summary?.perFeatureModel.filter((x) => x.feature === feature && x.model.startsWith(model)) ?? []
  const calls = f.reduce((n, x) => n + x.counts.calls, 0)
  return calls >= min ? { calls, usd: f.reduce((n, x) => n + x.counts.usd, 0) / calls } : null
}

/** Looks of a game with a model before their measured average replaces the estimate. */
export const MEASURED_MIN_LOOKS = 20

/** The measured average look for a game and model, once there are enough looks to trust it. */
export function measuredLook(summary: Pick<UsageSummary, 'looks'> | null | undefined, pack: string, model: string, min = MEASURED_MIN_LOOKS): LookAverage | null {
  const l = summary?.looks.find((x) => x.pack === pack && x.model.startsWith(model))
  return l && l.looks >= min ? l : null
}
