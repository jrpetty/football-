/**
 * Find a Slot (work.meeting-scheduler): everyone's calendar read from the prompt (working hours, meetings, focus
 * time, in-person travel, recurring meetings, holidays, the late messages), turned into busy blocks on London time,
 * and a check of any proposed slot against the written rules — to say why the model's slot clashes, or that an
 * earlier one was free. Pure; tested in test/customers-data-docs.test.ts.
 *
 * Times are minutes since 1970-01-01 00:00 UTC; a person's offset comes from the prompt ("UTC+1 all this week") or
 * from the clock-change rules it states (applied by date).
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { pick, replyJson, sameValue } from './kit.ts';

const DAY = 1440;
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DOW = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

export interface Busy {
  /** UTC minutes. */
  from: number;
  to: number;
  label: string;
  kind: 'off' | 'meeting' | 'focus' | 'travel' | 'holiday' | 'booked';
}

export interface Person {
  name: string;
  city: string;
  first: string;
  /** Fixed offset in minutes, or null to use the clock-change rules. */
  fixed: number | null;
  work: [number, number];
  busy: Busy[];
  gap: number;
}

export interface Meeting {
  id: string;
  minutes: number;
  who: string[];
  room: boolean;
  notBefore: number | null;
}

export interface MeetingDoc {
  /** London dates of the booking window (UTC midnight minutes of each date). */
  days: number[];
  people: Person[];
  room: Busy[];
  meetings: Meeting[];
  inOrder: boolean;
  londonFixed: number | null;
  std: Record<string, number>;
  key: Record<string, string | null> | null;
}

const dateMin = (y: number, m: number, d: number) => Date.UTC(y, m, d) / 60000;
const hm = (s: string) => {
  const [h, m] = s.split(':').map(Number);
  return h! * 60 + m!;
};

function parseDay(s: string, year: number): number | null {
  const m = s.match(/(\d{1,2}) ([A-Z][a-z]{2})/);
  if (!m) return null;
  const mo = MON.indexOf(m[2]!);
  return mo < 0 ? null : dateMin(year, mo, Number(m[1]));
}

function nthSunday(y: number, m: number, n: number): number {
  const first = new Date(Date.UTC(y, m, 1)).getUTCDay();
  return dateMin(y, m, 1 + ((7 - first) % 7) + (n - 1) * 7);
}
function lastSunday(y: number, m: number): number {
  const last = new Date(Date.UTC(y, m + 1, 0));
  return dateMin(y, m, last.getUTCDate() - last.getUTCDay());
}

/** UTC offset (minutes) of a city on a date (UTC-midnight minutes), by the clock-change rules in the prompt. */
export function offsetOn(city: string, date: number, std: Record<string, number>): number {
  const y = new Date(date * 60000).getUTCFullYear();
  const base = std[city] ?? 0;
  if (/London|Lisbon|Warsaw/.test(city)) return base + (date >= lastSunday(y, 2) && date < lastSunday(y, 9) ? 60 : 0);
  if (/New York|Toronto/.test(city)) return base + (date >= nthSunday(y, 2, 2) && date < nthSunday(y, 10, 1) ? 60 : 0);
  if (/Sydney/.test(city)) return base + (date < nthSunday(y, 3, 1) || date >= nthSunday(y, 9, 1) ? 60 : 0);
  return base;
}

function weekdaysIn(text: string): number[] {
  if (/every weekday/i.test(text)) return [1, 2, 3, 4, 5];
  return DOW.map((d, i) => (new RegExp(`\\b${d}s?\\b`).test(text) ? i : -1)).filter((i) => i >= 0);
}

export function parseMeeting(prompt: string, expected?: unknown): MeetingDoc | null {
  const win = prompt.match(/booking window is \w{3} (\d{1,2} \w{3}) (\d{4}) to \w{3} (\d{1,2} \w{3}) (\d{4})/);
  if (!win) return null;
  const year = Number(win[2]);
  const d0 = parseDay(win[1]!, year)!;
  const d1 = parseDay(win[3]!, Number(win[4]))!;
  const days: number[] = [];
  for (let d = d0; d <= d1; d += DAY) {
    const dow = new Date(d * 60000).getUTCDay();
    if (dow !== 0 && dow !== 6) days.push(d);
  }
  const std: Record<string, number> = {};
  const stdLine = prompt.match(/Standard offsets from UTC:\s*([^\n]+)/)?.[1] ?? '';
  for (const part of stdLine.split(/,\s*/)) {
    const m = part.match(/^(.+?)\s+([+-]\d+)(?::(\d+))?\.?$/);
    if (!m) continue;
    const off = Number(m[2]) * 60 + (m[2]!.startsWith('-') ? -1 : 1) * Number(m[3] ?? 0);
    for (const c of m[1]!.split(/\s+and\s+/)) std[c.trim()] = off;
  }
  const londonFixed = /London is UTC\+1 \(BST\) all this week/.test(prompt) ? 60 : /London is UTC\+0/.test(prompt) ? 0 : null;
  const cal = prompt.split(/\nCalendars[^\n]*\n/)[1]?.split(/\n\n/)[0] ?? '';
  const people: Person[] = [];
  let cur: Person | null = null;
  const pending: Array<{ p: Person; line: string }> = [];
  for (const line of cal.split('\n')) {
    const head = line.match(/^(\S.*?) — ([A-Za-z ]+?)(?: — UTC([+-]\d+)(?::(\d+))? all this week)?\. Works (\d{2}:\d{2})–(\d{2}:\d{2})/);
    if (head) {
      const fixed = head[3] ? Number(head[3]) * 60 + (head[3].startsWith('-') ? -1 : 1) * Number(head[4] ?? 0) : null;
      cur = { name: head[1]!, city: head[2]!.trim(), first: head[1]!.split(' ')[0]!, fixed, work: [hm(head[5]!), hm(head[6]!)], busy: [], gap: 0 };
      people.push(cur);
    } else if (cur && /^\s+- /.test(line)) pending.push({ p: cur, line: line.replace(/^\s+- /, '').trim() });
  }
  if (!people.length) return null;
  const offOf = (p: Person, date: number) => p.fixed ?? offsetOn(p.city, date, std);
  const londonOff = (date: number) => londonFixed ?? offsetOn('London', date, std);
  const span = { from: d0 - 2 * DAY, to: d1 + 2 * DAY };
  const holidays = new Map<string, Set<number>>();
  const add = (p: Person, date: number, a: number, b: number, label: string, kind: Busy['kind']) => p.busy.push({ from: date + a - offOf(p, date), to: date + b - offOf(p, date), label, kind });
  for (const { p, line } of pending) {
    let m: RegExpMatchArray | null;
    if ((m = line.match(/^\w{3} (\d{1,2} \w{3}): public holiday/))) {
      const d = parseDay(m[1]!, year)!;
      (holidays.get(p.name) ?? holidays.set(p.name, new Set()).get(p.name)!).add(d);
    } else if ((m = line.match(/^Needs at least (\d+) minutes between/))) p.gap = Number(m[1]);
    else if ((m = line.match(/^Focus time[^:]*:\s*(\d{2}:\d{2})–(\d{2}:\d{2})\s+(.+)$/))) {
      const wd = weekdaysIn(m[3]!);
      for (let d = span.from; d <= span.to; d += DAY) if (wd.includes(new Date(d * 60000).getUTCDay())) add(p, d, hm(m[1]!), hm(m[2]!), 'Focus time', 'focus');
    } else if ((m = line.match(/^Every (.+?) (\d{2}:\d{2})–(\d{2}:\d{2}) (.+?)(?: \(not on \w{3} (\d{1,2} \w{3})\))?$/))) {
      const wd = weekdaysIn(m[1]!.replace(/(\w+day)\b/g, '$1s'));
      const skip = m[5] ? parseDay(m[5], year) : null;
      for (let d = span.from; d <= span.to; d += DAY) if (wd.includes(new Date(d * 60000).getUTCDay()) && d !== skip) add(p, d, hm(m[2]!), hm(m[3]!), m[4]!, 'meeting');
    } else if ((m = line.match(/^\w{3} (\d{1,2} \w{3}) (\d{2}:\d{2})–(\d{2}:\d{2}) (.+?)(?: \(in person\) — needs (\d+) min travel before and after)?$/))) {
      const d = parseDay(m[1]!, year)!;
      add(p, d, hm(m[2]!), hm(m[3]!), m[4]!, 'meeting');
      if (m[5]) {
        add(p, d, hm(m[2]!) - Number(m[5]), hm(m[2]!), `Travel to ${m[4]}`, 'travel');
        add(p, d, hm(m[3]!), hm(m[3]!) + Number(m[5]), `Travel back from ${m[4]}`, 'travel');
      }
    }
  }
  // Late messages: added, moved and cancelled entries.
  for (const m of prompt.matchAll(/^Message \d+ from (.+?), received[^"]*"([^"]+)"/gm)) {
    const p = people.find((x) => x.name === m[1]);
    if (!p) continue;
    const t = m[2]!;
    let x: RegExpMatchArray | null;
    if ((x = t.match(/add an? (.+?) on \w{3} (\d{1,2} \w{3}) (\d{2}:\d{2})–(\d{2}:\d{2})/))) add(p, parseDay(x[2]!, year)!, hm(x[3]!), hm(x[4]!), `${x[1]} (late message)`, 'meeting');
    else if ((x = t.match(/my (.+?) on \w{3} (\d{1,2} \w{3}) \((\d{2}:\d{2})–(\d{2}:\d{2})\) has (moved to \w{3} (\d{1,2} \w{3}) (\d{2}:\d{2})–(\d{2}:\d{2})|been cancelled)/))) {
      const d = parseDay(x[2]!, year)!;
      const from = d + hm(x[3]!) - offOf(p, d);
      p.busy = p.busy.filter((b) => !(b.from === from && b.label === x![1]));
      if (x[6]) add(p, parseDay(x[6], year)!, hm(x[7]!), hm(x[8]!), `${x[1]} (moved)`, 'meeting');
    }
  }
  // Outside working hours, weekends and holidays.
  for (const p of people) {
    for (let d = span.from; d <= span.to; d += DAY) {
      const dow = new Date(d * 60000).getUTCDay();
      if (dow === 0 || dow === 6) add(p, d, 0, DAY, 'Weekend', 'off');
      else if (holidays.get(p.name)?.has(d)) add(p, d, 0, DAY, 'Public holiday', 'holiday');
      else {
        add(p, d, 0, p.work[0], 'Not working yet', 'off');
        add(p, d, p.work[1], DAY, 'Finished work', 'off');
      }
    }
  }
  const room: Busy[] = [];
  const roomBlock = prompt.match(/Fleet Room \(London office\) bookings, London time:\n([\s\S]*?)\n\n/)?.[1] ?? '';
  for (const l of roomBlock.split('\n')) {
    const m = l.match(/- \w{3} (\d{1,2} \w{3}) (\d{2}:\d{2})–(\d{2}:\d{2}) (.+)$/);
    if (m) {
      const d = parseDay(m[1]!, year)!;
      room.push({ from: d + hm(m[2]!) - londonOff(d), to: d + hm(m[3]!) - londonOff(d), label: m[4]!, kind: 'meeting' });
    }
  }
  const meetings: Meeting[] = [];
  for (const m of prompt.matchAll(/^\s+\(([a-h])\) (\d+) minutes with (.+?)(?: in the Fleet Room| \(video call\))(.*)$/gm)) {
    const nb = m[4]!.match(/not starting before \w{3} (\d{1,2} \w{3}) (\d{2}:\d{2})/);
    const nd = nb ? parseDay(nb[1]!, year)! : null;
    meetings.push({ id: m[1]!, minutes: Number(m[2]), who: m[3]!.split(/,\s*/).map((s) => s.trim()), room: /Fleet Room/.test(m[0]), notBefore: nd !== null ? nd + hm(nb![2]!) - londonOff(nd) : null });
  }
  return { days, people, room, meetings, inOrder: /Book the meetings in order/.test(prompt), londonFixed, std, key: expected && typeof expected === 'object' ? (expected as Record<string, string | null>) : null };
}

export function londonOffset(doc: MeetingDoc, date: number): number {
  return doc.londonFixed ?? offsetOn('London', date, doc.std);
}

/** "2026-06-16 12:30" (London) → UTC minutes; null if unreadable. */
export function slotUtc(doc: MeetingDoc, s: unknown): number | null {
  if (typeof s !== 'string') return null;
  const m = s.trim().match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{2})/);
  if (!m) return null;
  const d = dateMin(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  return d + Number(m[4]) * 60 + Number(m[5]) - londonOffset(doc, d);
}

/** The London date (UTC-midnight minutes) and London minute of a UTC moment. */
export function toLondon(doc: MeetingDoc, utc: number): { date: number; min: number } {
  const date = Math.floor(utc / DAY) * DAY;
  let local = utc + londonOffset(doc, date);
  let d = Math.floor(local / DAY) * DAY;
  local = utc + londonOffset(doc, d);
  d = Math.floor(local / DAY) * DAY;
  return { date: d, min: local - d };
}

export interface Clash {
  who: string;
  text: string;
}

const clock = (min: number) => `${String(Math.floor((((min % DAY) + DAY) % DAY) / 60)).padStart(2, '0')}:${String((((min % DAY) + DAY) % DAY) % 60).padStart(2, '0')}`;

/** Everything a slot breaks (empty: allowed by every rule this picture checks). `booked`: earlier meetings already in calendars. */
export function clashes(doc: MeetingDoc, mt: Meeting, start: number, booked: Array<{ m: Meeting; at: number }> = []): Clash[] {
  const end = start + mt.minutes;
  const out: Clash[] = [];
  const lon = toLondon(doc, start);
  if (!doc.days.includes(lon.date)) out.push({ who: '', text: 'outside the booking window' });
  if (lon.min % 15 !== 0) out.push({ who: '', text: 'meetings start on the hour or at :15, :30 or :45' });
  if (mt.notBefore !== null && start < mt.notBefore) out.push({ who: '', text: `it may not start before ${clock(toLondon(doc, mt.notBefore).min)} London time` });
  for (const name of mt.who) {
    const p = doc.people.find((x) => x.name === name);
    if (!p) continue;
    for (const b of p.busy) {
      if (b.from < end && start < b.to) {
        const off = p.fixed ?? offsetOn(p.city, Math.floor(b.from / DAY) * DAY, doc.std);
        const local = `${clock(b.from + off)}–${clock(b.to + off)}`;
        const where = p.city === 'London' ? '' : ` ${p.city} time`;
        out.push({ who: p.first, text: b.kind === 'off' ? `${p.first} is not working then (works ${clock(p.work[0])}–${clock(p.work[1])}${where})` : b.kind === 'holiday' ? `${p.first} is on a public holiday` : `${p.first} has “${b.label}” ${local}${where}` });
        break;
      }
    }
    // Friday afternoons: no meeting may run past 12:00 local on a Friday.
    const off = p.fixed ?? offsetOn(p.city, Math.floor(start / DAY) * DAY, doc.std);
    const ls = start + off;
    const ld = Math.floor(ls / DAY) * DAY;
    if (new Date(ld * 60000).getUTCDay() === 5 && ls + mt.minutes - ld > 12 * 60) out.push({ who: p.first, text: `it runs past 12:00 on a Friday for ${p.first} (no Friday-afternoon meetings)` });
  }
  if (mt.room) for (const b of doc.room) if (b.from < end && start < b.to) out.push({ who: 'Room', text: `the Fleet Room is booked (“${b.label}”)` });
  for (const { m, at } of booked) {
    const shared = m.who.filter((w) => mt.who.includes(w));
    const pad = Math.max(0, ...shared.map((w) => doc.people.find((p) => p.name === w)?.gap ?? 0));
    if (shared.length && at - pad < end && start < at + m.minutes + pad && !(at < end && start < at + m.minutes)) out.push({ who: '', text: `it leaves less than ${pad} minutes after or before meeting (${m.id}) for someone who needs a gap` });
    if (at < end && start < at + m.minutes && shared.length) out.push({ who: '', text: `it overlaps meeting (${m.id}), already booked then` });
    else if (m.room && mt.room && at < end && start < at + m.minutes) out.push({ who: 'Room', text: `the Fleet Room is taken by meeting (${m.id})` });
  }
  return out;
}

export interface SlotMark {
  id: string;
  model: string | null;
  key: string | null;
  ok: boolean;
  /** What the model's slot breaks, or, if nothing, that an earlier slot was free. */
  why: string | null;
}

export function meetingAnswer(input: CaseVisualInput, doc: MeetingDoc): SlotMark[] | null {
  const j = replyJson(input);
  if (!j || !doc.key) return null;
  const booked: Array<{ m: Meeting; at: number }> = [];
  return doc.meetings.map((mt) => {
    const raw = pick(j, mt.id);
    const model = raw == null ? null : String(raw);
    const key = doc.key![mt.id] ?? null;
    const ok = sameValue(key, raw ?? null);
    let why: string | null = null;
    const at = slotUtc(doc, model);
    const keyAt = slotUtc(doc, key);
    if (!ok) {
      if (at === null) why = model ? 'Not a single London date and time.' : 'No time given.';
      else {
        const c = clashes(doc, mt, at, doc.inOrder ? booked : []);
        if (c.length) why = `Clashes: ${[...new Set(c.map((x) => x.text))].slice(0, 2).join('; ')}.`;
        else if (keyAt !== null && at > keyAt) why = 'This time works, but an earlier one was free: the task asked for the earliest.';
        else if (keyAt !== null && at < keyAt) why = 'Too early: a rule rules this time out (the earliest time that keeps every rule is the one beside it).';
      }
    }
    if (keyAt !== null) booked.push({ m: mt, at: keyAt });
    return { id: mt.id, model, key, ok, why };
  });
}
