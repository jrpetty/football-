// ---------------------------------------------------------------------------
// What next week might take.
//
// A pub's takings are mostly two things: which day of the week it is, and what
// the weather is doing. Friday is Friday, and a warm dry Saturday in a garden
// is a different business from a wet one. So the estimate is built in that
// order — a baseline per weekday from what this pub actually takes, then an
// adjustment for weather, but only once there is enough history to have earned
// one.
//
// The gates matter more than the arithmetic. Fitting weather to six nights of
// trade produces a confident-looking number that is noise, and a forecast that
// is wrong twice stops being read. So:
//
//   - under MIN_FOR_WEATHER nights with weather recorded, no weather
//     adjustment is made at all and the screen says the baseline is all it has;
//   - the adjustment is capped, so a freak forecast cannot swing the estimate
//     into nonsense;
//   - every estimate carries a range, because a single figure implies a
//     precision that does not exist.
//
// It is a steer, not a budget, and the interface says so.
// ---------------------------------------------------------------------------

import { weekdayOf } from './date.ts'

/** Nights with weather recorded, below which weather is not used at all. */
export const MIN_FOR_WEATHER = 12

/** Nights of the same weekday, below which the overall average is used. */
const MIN_PER_WEEKDAY = 2

/** The most weather may move an estimate, as a share of the baseline. */
const MAX_SWING = 0.35

export interface DayWeather {
  date: string
  /** Daytime high, in whole degrees C. */
  tempC: number
  /** Rain over the day, in whole millimetres. */
  rainMm: number
}

export interface TradedNight {
  date: string
  takingsPence: number
}

export interface Prediction {
  date: string
  weekday: string
  /** What this weekday usually takes. */
  basePence: number
  /** The baseline after weather, which is the estimate. */
  estimatePence: number
  lowPence: number
  highPence: number
  weather: DayWeather | null
  /** False when there was not enough history to let weather move anything. */
  weatherApplied: boolean
}

export interface WeekForecast {
  days: Prediction[]
  totalPence: number
  lowPence: number
  highPence: number
  /** Nights of trade behind the baseline. */
  nightsUsed: number
  /** Nights that also had weather, behind the adjustment. */
  weatherNights: number
  /** Pence per degree, and per mm of rain — null when not enough to fit. */
  perDegreePence: number | null
  perMmRainPence: number | null
}

function mean(values: readonly number[]): number {
  return values.length === 0 ? 0 : values.reduce((a, v) => a + v, 0) / values.length
}

/**
 * Ordinary least squares on two predictors, solved directly.
 *
 * Two variables and a handful of nights does not want a matrix library: the
 * normal equations for two predictors are a 2x2 system, and solving it by hand
 * keeps the whole thing inspectable.
 *
 * The system goes singular whenever one of the two never varied — a fortnight
 * without rain does it, which in a British summer is not rare. That must not
 * throw away the other variable with it: a dry spell is exactly when the
 * temperature effect is most worth having. So a singular system falls back to
 * fitting whichever variable actually moved, and only gives up when neither
 * did, where any slope at all would be invented rather than measured.
 */
function fitTwo(
  xs: readonly number[],
  ys: readonly number[],
  zs: readonly number[],
): { a: number; b: number } | null {
  const mx = mean(xs)
  const my = mean(ys)
  const mz = mean(zs)

  let sxx = 0
  let syy = 0
  let sxy = 0
  let sxz = 0
  let syz = 0
  for (let i = 0; i < xs.length; i++) {
    const dx = (xs[i] as number) - mx
    const dy = (ys[i] as number) - my
    const dz = (zs[i] as number) - mz
    sxx += dx * dx
    syy += dy * dy
    sxy += dx * dy
    sxz += dx * dz
    syz += dy * dz
  }

  const det = sxx * syy - sxy * sxy
  if (Number.isFinite(det) && Math.abs(det) > 1e-9) {
    return { a: (syy * sxz - sxy * syz) / det, b: (sxx * syz - sxy * sxz) / det }
  }
  if (sxx > 1e-9) return { a: sxz / sxx, b: 0 }
  if (syy > 1e-9) return { a: 0, b: syz / syy }
  return null
}

function stdev(values: readonly number[]): number {
  if (values.length < 2) return 0
  const m = mean(values)
  return Math.sqrt(values.reduce((a, v) => a + (v - m) ** 2, 0) / (values.length - 1))
}

/**
 * Next week, night by night.
 *
 * `upcoming` is the seven dates being forecast, with whatever weather is known
 * for them. Weather may be missing for any or all of them — a forecast only
 * runs a week or so ahead — and a night with none simply gets the baseline.
 */
export function forecastWeek(
  history: readonly TradedNight[],
  weather: readonly DayWeather[],
  upcoming: ReadonlyArray<{ date: string; weather?: DayWeather }>,
): WeekForecast {
  const byDate = new Map(weather.map((w) => [w.date, w]))
  const traded = history.filter((n) => n.takingsPence > 0)

  // 1. What each weekday usually takes.
  const perWeekday = new Map<string, number[]>()
  for (const night of traded) {
    const day = weekdayOf(night.date)
    const found = perWeekday.get(day)
    if (found) found.push(night.takingsPence)
    else perWeekday.set(day, [night.takingsPence])
  }
  const overall = mean(traded.map((n) => n.takingsPence))
  const baseFor = (date: string): number => {
    const values = perWeekday.get(weekdayOf(date)) ?? []
    return values.length >= MIN_PER_WEEKDAY ? mean(values) : overall
  }

  // 2. What is left over after the weekday is accounted for, against weather.
  const withWeather = traded
    .map((n) => ({ night: n, w: byDate.get(n.date) }))
    .filter((r): r is { night: TradedNight; w: DayWeather } => !!r.w)

  const residuals = withWeather.map((r) => r.night.takingsPence - baseFor(r.night.date))
  const temps = withWeather.map((r) => r.w.tempC)
  const rains = withWeather.map((r) => r.w.rainMm)

  const enough = withWeather.length >= MIN_FOR_WEATHER
  const fit = enough ? fitTwo(temps, rains, residuals) : null
  const meanTemp = mean(temps)
  const meanRain = mean(rains)

  // The spread of what is left after everything the model knows about — the
  // honest width of the estimate.
  const unexplained = fit
    ? residuals.map((r, i) => r - (fit.a * ((temps[i] as number) - meanTemp) + fit.b * ((rains[i] as number) - meanRain)))
    : traded.map((n) => n.takingsPence - baseFor(n.date))
  const spread = Math.round(stdev(unexplained))

  const days: Prediction[] = upcoming.map(({ date, weather: w }) => {
    const basePence = Math.round(baseFor(date))
    let estimatePence = basePence
    let weatherApplied = false

    if (fit && w) {
      const raw = fit.a * (w.tempC - meanTemp) + fit.b * (w.rainMm - meanRain)
      // Capped: a forecast of thirty degrees must not treble a Tuesday.
      const cap = basePence * MAX_SWING
      estimatePence = Math.round(basePence + Math.max(-cap, Math.min(cap, raw)))
      weatherApplied = true
    }

    estimatePence = Math.max(0, estimatePence)
    return {
      date,
      weekday: weekdayOf(date),
      basePence,
      estimatePence,
      lowPence: Math.max(0, estimatePence - spread),
      highPence: estimatePence + spread,
      weather: w ?? null,
      weatherApplied,
    }
  })

  const totalPence = days.reduce((a, d) => a + d.estimatePence, 0)
  // Errors across seven independent nights do not simply add up; they partly
  // cancel. Widening by the root of the count rather than the count keeps the
  // weekly range from being uselessly broad.
  const weekSpread = Math.round(spread * Math.sqrt(days.length))

  return {
    days,
    totalPence,
    lowPence: Math.max(0, totalPence - weekSpread),
    highPence: totalPence + weekSpread,
    nightsUsed: traded.length,
    weatherNights: withWeather.length,
    perDegreePence: fit ? Math.round(fit.a) : null,
    perMmRainPence: fit ? Math.round(fit.b) : null,
  }
}
