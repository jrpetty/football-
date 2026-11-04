/**
 * Work-world time: one simulated week, UK style ("Thu 12 Mar", "14:30").
 *
 * A moment is (day, minute): `day` counts from the week's Monday (0 = Mon, 6 = Sun; negative = earlier
 * weeks, used for old emails) and `minute` from midnight. Parsing is generous about style ("Thursday",
 * "Thu 12 Mar", "12/03", "2026-03-12", "2pm", "14.30", "2:30 pm to 3pm", "14:00 for 45 min"), strict about
 * content: a weekday that does not match the date is an error, never a guess.
 */

export interface Week {
  /** ISO date of the Monday, e.g. "2026-03-09". */
  monday: string;
}

const WD = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const WD_FULL = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTHS_FULL = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];

function mondayUtc(week: Week): number {
  const [y, m, d] = week.monday.split('-').map(Number);
  return Date.UTC(y!, m! - 1, d!);
}

/** The calendar date of a day of the week (UTC midnight, so no DST surprises). */
export function dateOf(week: Week, day: number): Date {
  return new Date(mondayUtc(week) + day * 86_400_000);
}

/** 0 = Mon … 6 = Sun, for any day offset (negative too). */
export function weekdayOf(day: number): number {
  return ((day % 7) + 7) % 7;
}

/** "Thu 12 Mar". */
export function fmtDay(week: Week, day: number): string {
  const d = dateOf(week, day);
  return `${WD[weekdayOf(day)]} ${d.getUTCDate()} ${MONTHS[d.getUTCMonth()]}`;
}

/** "Thursday 12 March". */
export function fmtDayLong(week: Week, day: number): string {
  const d = dateOf(week, day);
  const name = WD_FULL[weekdayOf(day)]!;
  const month = MONTHS_FULL[d.getUTCMonth()]!;
  return `${name[0]!.toUpperCase()}${name.slice(1)} ${d.getUTCDate()} ${month[0]!.toUpperCase()}${month.slice(1)}`;
}

/** 870 → "14:30". */
export function fmtTime(min: number): string {
  const m = ((Math.round(min) % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

export function fmtRange(start: number, end: number): string {
  return `${fmtTime(start)}–${fmtTime(end)}`;
}

/** "Thu 12 Mar 14:30". */
export function fmtWhen(week: Week, day: number, min: number): string {
  return `${fmtDay(week, day)} ${fmtTime(min)}`;
}

export type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

function monthIndex(token: string): number {
  const t = token.toLowerCase().replace(/\.$/, '');
  if (t.length < 3) return -1;
  return MONTHS_FULL.findIndex((m) => m.startsWith(t));
}

function weekdayIndex(token: string): number {
  const t = token.toLowerCase().replace(/\.$/, '');
  if (t.length < 2) return -1;
  const aliases: Record<string, number> = { tues: 1, weds: 2, thur: 3, thurs: 3 };
  if (t in aliases) return aliases[t]!;
  return WD_FULL.findIndex((w) => w.startsWith(t) && t.length >= 3);
}

/**
 * A day of this week (0 = Mon … 6 = Sun) from free text. `allowed` limits the range (default the whole week).
 */
export function parseDay(text: string, week: Week, allowed: [number, number] = [0, 6]): Parsed<number> {
  const raw = text.trim();
  if (!raw) return { ok: false, error: 'No day given.' };
  const s = raw
    .toLowerCase()
    .replace(/[,()]/g, ' ')
    .replace(/(\d+)(st|nd|rd|th)\b/g, '$1')
    .replace(/\bthe\b|\bof\b|\bthis\b|\bon\b/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const base = dateOf(week, 0);
  const year = base.getUTCFullYear();
  let weekday = -1;
  let date: Date | null = null;
  if (s === 'today') return inRange(0, week, allowed);
  if (s === 'tomorrow') return inRange(1, week, allowed);
  const iso = /\b(\d{4})-(\d{1,2})-(\d{1,2})\b/.exec(s);
  const slash = /\b(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?\b/.exec(s);
  if (iso) date = new Date(Date.UTC(Number(iso[1]), Number(iso[2]) - 1, Number(iso[3])));
  else if (slash) {
    const y = slash[3] ? (slash[3].length === 2 ? 2000 + Number(slash[3]) : Number(slash[3])) : year;
    date = new Date(Date.UTC(y, Number(slash[2]) - 1, Number(slash[1])));
  }
  for (const tok of s.split(' ')) {
    if (weekday < 0 && weekdayIndex(tok) >= 0) weekday = weekdayIndex(tok);
  }
  if (!date) {
    const dm = /\b(\d{1,2}) ([a-z]{3,9})\b/.exec(s);
    const md = /\b([a-z]{3,9}) (\d{1,2})\b/.exec(s);
    if (dm && monthIndex(dm[2]!) >= 0) date = new Date(Date.UTC(year, monthIndex(dm[2]!), Number(dm[1])));
    else if (md && monthIndex(md[1]!) >= 0) date = new Date(Date.UTC(year, monthIndex(md[1]!), Number(md[2])));
  }
  if (date) {
    const day = Math.round((date.getTime() - base.getTime()) / 86_400_000);
    if (weekday >= 0 && weekdayOf(day) !== weekday) return { ok: false, error: `"${raw}" does not add up: ${fmtDay(week, day)} is a ${WD_FULL[weekdayOf(day)]}.` };
    return inRange(day, week, allowed);
  }
  if (weekday >= 0) return inRange(weekday, week, allowed);
  return { ok: false, error: `"${raw}" is not a day I recognise. Use a day of this week, e.g. "Thu" or "Thu ${dateOf(week, 3).getUTCDate()} ${MONTHS[dateOf(week, 3).getUTCMonth()]}".` };
}

function inRange(day: number, week: Week, allowed: [number, number]): Parsed<number> {
  if (day < allowed[0] || day > allowed[1]) return { ok: false, error: `${fmtDay(week, day)} is outside the days you can use (${fmtDay(week, allowed[0])} to ${fmtDay(week, allowed[1])}).` };
  return { ok: true, value: day };
}

/** Minutes after midnight from "14:30", "2:30pm", "2pm", "14.30", "1430", "14h30", "noon". */
export function parseTime(text: string): Parsed<number> {
  const s = text.trim().toLowerCase().replace(/\s+/g, ' ').replace(/\.(?=m)/g, '').replace(/a\.m\.?|p\.m\.?/g, (m) => m.replace(/\./g, ''));
  if (!s) return { ok: false, error: 'No time given.' };
  if (/^(noon|midday|12 noon)$/.test(s)) return { ok: true, value: 720 };
  let m = /^(\d{1,2})(?:[:.h](\d{2}))?\s*(am|pm)?$/.exec(s);
  if (!m) {
    const compact = /^(\d{2})(\d{2})$/.exec(s);
    if (compact) m = [s, compact[1]!, compact[2]!, undefined] as unknown as RegExpExecArray;
  }
  if (!m) return { ok: false, error: `"${text.trim()}" is not a time I recognise. Use 24-hour times like 14:30.` };
  let h = Number(m[1]);
  const min = m[2] ? Number(m[2]) : 0;
  const ap = m[3];
  if (min > 59) return { ok: false, error: `"${text.trim()}" is not a valid time.` };
  if (ap) {
    if (h < 1 || h > 12) return { ok: false, error: `"${text.trim()}" is not a valid time.` };
    if (ap === 'pm' && h !== 12) h += 12;
    if (ap === 'am' && h === 12) h = 0;
  } else if (h > 24 || (h === 24 && min > 0)) return { ok: false, error: `"${text.trim()}" is not a valid time.` };
  return { ok: true, value: h * 60 + min };
}

/** "14:00-14:30", "2pm to 2:30pm", "14:00 – 15:00", "14:00 for 45 min", "14:00 (30 mins)". */
export function parseRange(text: string): Parsed<[number, number]> {
  const s = text.trim().replace(/[()]/g, ' ').replace(/\s+/g, ' ').trim();
  const dur = /^(.+?)\s*(?:for|\+)\s*(\d{1,3})\s*(?:m|min|mins|minutes)?$/i.exec(s) ?? /^(.+?)\s+(\d{1,3})\s*(?:m|min|mins|minutes)$/i.exec(s);
  if (dur) {
    const st = parseTime(dur[1]!);
    if (!st.ok) return st;
    const len = Number(dur[2]);
    if (len <= 0) return { ok: false, error: 'The length must be more than 0 minutes.' };
    return { ok: true, value: [st.value, st.value + len] };
  }
  const parts = s.split(/\s*(?:-|–|—|\bto\b|\buntil\b|\btill\b)\s*/i).filter(Boolean);
  if (parts.length !== 2) return { ok: false, error: `"${text.trim()}" is not a time range. Write it like 14:00-14:30.` };
  let a = parseTime(parts[0]!);
  const b = parseTime(parts[1]!);
  // "2-3pm": the am/pm of the end applies to the start too.
  if (!a.ok && /^\d{1,2}(?:[:.]\d{2})?$/.test(parts[0]!.trim()) && /pm$/i.test(parts[1]!.trim())) a = parseTime(`${parts[0]!.trim()}pm`);
  if (!a.ok) return a;
  if (!b.ok) return b;
  let start = a.value;
  if (/pm$/i.test(parts[1]!.trim()) && !/am|pm/i.test(parts[0]!) && start < 720 && start + 720 < b.value) start += 720;
  if (b.value <= start) return { ok: false, error: `The end (${fmtTime(b.value)}) must be after the start (${fmtTime(start)}).` };
  return { ok: true, value: [start, b.value] };
}

/** Half-open interval overlap on the same day. */
export function overlaps(a: { day: number; start: number; end: number }, b: { day: number; start: number; end: number }): boolean {
  return a.day === b.day && a.start < b.end && b.start < a.end;
}
