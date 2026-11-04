// Calendar. One in-game day lasts 60 real seconds at 1× speed; a season is
// three days and a year is twelve, so generations turn over within a session.

export const HOUR = 60
export const DAY = 24 * HOUR
export const DAYS_PER_SEASON = 3
export const SEASON_LEN = DAYS_PER_SEASON * DAY
export const YEAR = 4 * SEASON_LEN
export const SEASONS = ['Spring', 'Summer', 'Autumn', 'Winter'] as const
export const SEASON_ICONS = ['🌱', '☀️', '🍂', '❄️']
/** Real seconds per in-game hour at 1× speed. */
export const REAL_SEC_PER_HOUR = 2.5

export function hourOf(t: number): number {
  return (t % DAY) / HOUR
}
export function dayOf(t: number): number {
  return Math.floor(t / DAY)
}
export function seasonOf(t: number): number {
  return Math.floor(t / SEASON_LEN) % 4
}
export function yearOf(t: number): number {
  return Math.floor(t / YEAR) + 1
}
export function dayInSeason(t: number): number {
  return (dayOf(t) % DAYS_PER_SEASON) + 1
}
export function isNight(t: number): boolean {
  const h = hourOf(t)
  return h < 5.5 || h >= 20.5
}
/** 0 at midnight … 1 at noon, smooth. */
export function daylight(t: number): number {
  const h = hourOf(t)
  const v = Math.cos(((h - 12) / 24) * Math.PI * 2)
  return Math.max(0, Math.min(1, v * 0.9 + 0.45))
}
export function partOfDay(t: number): string {
  const h = hourOf(t)
  if (h < 5) return 'night'
  if (h < 8) return 'dawn'
  if (h < 12) return 'morning'
  if (h < 14) return 'midday'
  if (h < 18) return 'afternoon'
  if (h < 21) return 'evening'
  return 'night'
}
export function formatClock(t: number): string {
  const h = Math.floor(hourOf(t))
  const m = Math.floor(t % HOUR)
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}
export function formatDate(t: number): string {
  return `Year ${yearOf(t)}, ${SEASONS[seasonOf(t)]} day ${dayInSeason(t)}`
}
export function ageYears(birth: number, now: number): number {
  return (now - birth) / YEAR
}
