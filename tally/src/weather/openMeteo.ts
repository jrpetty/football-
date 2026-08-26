// ---------------------------------------------------------------------------
// Where the weather comes from.
//
// Open-Meteo, because it is free, needs no key, allows browser requests, and
// keeps a historical archive as well as a forecast — and the archive is the
// half that matters. A forecast alone cannot tell you anything; the useful
// question is what THIS pub takes when it is warm, and answering it means
// knowing what the weather was on every night already recorded.
//
// Two endpoints, same shape: the archive for nights past, the forecast for
// nights ahead. Everything is asked for in the pub's own timezone so a day
// means the same thing here as it does in the takings.
//
// Nothing here is essential. A pub with no signal, or a household that would
// rather not call out at all, loses the weather column and keeps every other
// figure in the app.
// ---------------------------------------------------------------------------

import type { DayWeather } from '../core/forecast.ts'

const ARCHIVE = 'https://archive-api.open-meteo.com/v1/archive'
const FORECAST = 'https://api.open-meteo.com/v1/forecast'
const GEOCODE = 'https://geocoding-api.open-meteo.com/v1/search'

/** Where the pub is. Without one, no weather is fetched at all. */
export interface Place {
  name: string
  latitude: number
  longitude: number
}

interface DailyResponse {
  daily?: {
    time?: unknown
    temperature_2m_max?: unknown
    precipitation_sum?: unknown
  }
}

/**
 * Pull the three parallel arrays Open-Meteo returns into day records.
 *
 * Written defensively on purpose: this is the one place in the app that trusts
 * a third party's JSON, and a shape change upstream must lose the weather
 * column rather than take the app down with it. A day missing either figure is
 * skipped rather than defaulted — a rainy day recorded as 0mm would quietly
 * bias every fit that used it.
 */
export function readDaily(body: unknown): DayWeather[] {
  const daily = (body as DailyResponse | null)?.daily
  if (!daily) return []
  const dates = Array.isArray(daily.time) ? daily.time : []
  const temps = Array.isArray(daily.temperature_2m_max) ? daily.temperature_2m_max : []
  const rains = Array.isArray(daily.precipitation_sum) ? daily.precipitation_sum : []

  const out: DayWeather[] = []
  for (let i = 0; i < dates.length; i++) {
    const date = dates[i]
    const temp = temps[i]
    const rain = rains[i]
    if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) continue
    if (typeof temp !== 'number' || !Number.isFinite(temp)) continue
    if (typeof rain !== 'number' || !Number.isFinite(rain)) continue
    out.push({ date, tempC: Math.round(temp), rainMm: Math.round(rain) })
  }
  return out
}

async function get(url: string, signal?: AbortSignal): Promise<unknown> {
  const res = await fetch(url, signal ? { signal } : {})
  if (!res.ok) throw new Error(`WEATHER_${res.status}`)
  return res.json()
}

function params(place: Place, extra: Record<string, string>): string {
  return new URLSearchParams({
    latitude: String(place.latitude),
    longitude: String(place.longitude),
    daily: 'temperature_2m_max,precipitation_sum',
    timezone: 'auto',
    ...extra,
  }).toString()
}

/** What the weather was, between two dates. */
export async function fetchHistory(place: Place, from: string, to: string, signal?: AbortSignal): Promise<DayWeather[]> {
  return readDaily(await get(`${ARCHIVE}?${params(place, { start_date: from, end_date: to })}`, signal))
}

/** What the weather is going to be, for the next fortnight at most. */
export async function fetchForecast(place: Place, days = 14, signal?: AbortSignal): Promise<DayWeather[]> {
  return readDaily(await get(`${FORECAST}?${params(place, { forecast_days: String(Math.min(16, days)) })}`, signal))
}

/** Find a pub by the name of the town it is in. */
export async function findPlace(query: string, signal?: AbortSignal): Promise<Place[]> {
  const url = `${GEOCODE}?${new URLSearchParams({ name: query, count: '5', language: 'en', format: 'json' })}`
  const body = (await get(url, signal)) as { results?: unknown }
  if (!Array.isArray(body.results)) return []
  const out: Place[] = []
  for (const row of body.results as Array<Record<string, unknown>>) {
    const name = typeof row.name === 'string' ? row.name : ''
    const latitude = typeof row.latitude === 'number' ? row.latitude : NaN
    const longitude = typeof row.longitude === 'number' ? row.longitude : NaN
    if (!name || !Number.isFinite(latitude) || !Number.isFinite(longitude)) continue
    const admin = typeof row.admin1 === 'string' ? `, ${row.admin1}` : ''
    out.push({ name: `${name}${admin}`, latitude, longitude })
  }
  return out
}

/** A failure a person can act on, rather than a stack trace. */
export function describeWeatherError(err: unknown): string {
  const message = err instanceof Error ? err.message : String(err)
  if (/^WEATHER_4/.test(message)) return 'The weather service refused that request. Check the location in Settings.'
  if (/^WEATHER_5/.test(message)) return 'The weather service is down at the moment. It will try again later.'
  if (/Failed to fetch|NetworkError|Load failed/i.test(message)) {
    return 'Could not reach the weather service — no signal, or it is blocked on this connection.'
  }
  return `Could not fetch the weather: ${message}`
}
