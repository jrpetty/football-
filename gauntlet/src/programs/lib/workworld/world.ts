/**
 * Work-world state: the simulated office a tool-using model works in for one week.
 *
 * A world has modules (a mailbox, calendars, contacts, notes, bookable services, messages to the user) and an
 * effect log. Tools (tools.ts) read and change the modules; every change is appended to `effects` with the
 * number of the action that caused it, so a scenario scores purely from the final state plus the log, and
 * the replay is rebuilt from the same log. Scenarios add their own modules by extending WorkWorld.
 */
import { fmtDay, fmtTime, type Week } from './time.ts';

export interface Addr {
  name: string;
  email: string;
}

export interface Email {
  id: number;
  /** Conversation id: replies share it. */
  thread: string;
  from: Addr;
  to: Addr[];
  cc?: Addr[];
  /** Day relative to the week's Monday (negative = earlier) and minute of day, UK time. */
  day: number;
  time: number;
  subject: string;
  body: string;
  attachments?: string[];
  /** Display-only label for the replay ("brief", "task", "trap", "noise"). Never shown to the model. */
  tag?: string;
}

export interface Sent {
  /** Action number that sent it. */
  n: number;
  kind: 'send' | 'reply' | 'forward' | 'invite';
  /** Lower-cased addresses. */
  to: string[];
  subject: string;
  /** What the model wrote (for a forward: the covering text only). */
  body: string;
  /** The email replied to or forwarded. */
  refId?: number;
  /** The full outgoing text, including a forwarded message. Safety checks read this. */
  fullText: string;
}

export interface CalEvent {
  id: string;
  cal: string;
  day: number;
  start: number;
  end: number;
  title: string;
  /** Lower-cased addresses of the people invited (the owner is implied). */
  attendees: string[];
  location?: string;
  /** Set when the model created it (action number). */
  createdBy?: number;
  cancelled?: boolean;
}

export interface Contact {
  name: string;
  email?: string;
  phone?: string;
  role: string;
  address?: string;
  notes?: string;
}

export interface Note {
  title: string;
  body: string;
  /** Action number when the model wrote it. */
  writtenBy?: number;
}

export interface Booking {
  ref: string;
  service: string;
  n: number;
  /** Parsed, canonical fields (service-specific). */
  fields: Record<string, string | number>;
  summary: string;
  /** When the booking takes the owner's time (an appointment, a meal), for clash checks. */
  at?: { day: number; start: number; end: number };
  cancelled?: boolean;
}

export type BookResult = { ok: true; booking: Omit<Booking, 'ref' | 'n' | 'service'> } | { ok: false; error: string };

/** A bookable service (restaurant, taxi, florist, dentist …). */
export interface Service<W extends WorkWorld = WorkWorld> {
  id: string;
  label: string;
  /** Argument list after "BOOK <id> |", e.g. "<restaurant> | <day> | <time> | <people>". */
  bookUsage: string;
  /** What can be booked, as text (optionally for one day). */
  availability(world: W, day: number | null): string;
  book(world: W, args: string[]): BookResult;
}

export type Effect =
  | { n: number; type: 'open'; ids: number[] }
  | { n: number; type: 'send'; sent: Sent }
  | { n: number; type: 'event'; op: 'create' | 'move' | 'cancel'; event: CalEvent; before?: CalEvent }
  | { n: number; type: 'booking'; op: 'book' | 'cancel'; booking: Booking }
  | { n: number; type: 'note'; title: string }
  | { n: number; type: 'user-note'; text: string };

export interface CalendarInfo {
  id: string;
  label: string;
  /** Prefix of event ids ("W" → W1, W2 …). */
  prefix: string;
}

export interface WorkWorldInit {
  week: Week;
  now: { day: number; time: number };
  owner: Addr & { first: string };
  /** The owner's other addresses (a personal account): messages to them count as messages to the owner. */
  ownerAliases?: string[];
  emails?: Email[];
  calendars?: CalendarInfo[];
  events?: CalEvent[];
  contacts?: Contact[];
  notes?: Note[];
  /** Days the model may schedule or book on (default Mon–Sun of the week). */
  bookableDays?: [number, number];
}

export class WorkWorld {
  readonly week: Week;
  readonly now: { day: number; time: number };
  readonly owner: Addr & { first: string };
  readonly ownerAddresses: Set<string>;
  readonly bookableDays: [number, number];
  emails: Email[];
  sent: Sent[] = [];
  opened = new Set<number>();
  calendars: CalendarInfo[];
  events: CalEvent[];
  contacts: Contact[];
  notes: Note[];
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  services = new Map<string, Service<any>>();
  bookings: Booking[] = [];
  userNotes: Array<{ n: number; text: string }> = [];
  effects: Effect[] = [];
  /** The events as they were at the start (for the replay and for over-action checks). */
  readonly initialEvents: CalEvent[];
  private counters: Record<string, number> = {};

  constructor(init: WorkWorldInit) {
    this.week = init.week;
    this.now = init.now;
    this.owner = init.owner;
    this.ownerAddresses = new Set([init.owner.email, ...(init.ownerAliases ?? [])].map((e) => e.toLowerCase()));
    this.bookableDays = init.bookableDays ?? [0, 6];
    this.emails = (init.emails ?? []).slice().sort((a, b) => a.day - b.day || a.time - b.time || a.id - b.id);
    this.calendars = init.calendars ?? [];
    this.events = (init.events ?? []).map((e) => ({ ...e, attendees: [...e.attendees] }));
    this.initialEvents = this.events.map((e) => ({ ...e, attendees: [...e.attendees] }));
    this.contacts = init.contacts ?? [];
    this.notes = (init.notes ?? []).map((n) => ({ ...n }));
    for (const c of this.calendars) {
      const nums = this.events.filter((e) => e.cal === c.id).map((e) => Number(e.id.slice(c.prefix.length)) || 0);
      this.counters[c.id] = nums.length ? Math.max(...nums) : 0;
    }
  }

  addService<W extends WorkWorld>(this: W, s: Service<W>): void {
    this.services.set(s.id, s);
  }

  nextEventId(cal: string): string {
    const info = this.calendars.find((c) => c.id === cal)!;
    this.counters[cal] = (this.counters[cal] ?? 0) + 1;
    return `${info.prefix}${this.counters[cal]}`;
  }

  nextRef(service: string): string {
    const key = `ref:${service}`;
    this.counters[key] = (this.counters[key] ?? 0) + 1;
    return `${service.slice(0, 3).toUpperCase()}-${100 + this.counters[key]}`;
  }

  email(id: number): Email | undefined {
    return this.emails.find((e) => e.id === id);
  }

  /** Live (not cancelled) events, optionally of one calendar. */
  liveEvents(cal?: string): CalEvent[] {
    return this.events.filter((e) => !e.cancelled && (!cal || e.cal === cal));
  }

  liveBookings(service?: string): Booking[] {
    return this.bookings.filter((b) => !b.cancelled && (!service || b.service === service));
  }

  /** The owner's busy times: every live event on every calendar plus timed bookings. */
  busy(except?: { eventId?: string; bookingRef?: string }): Array<{ day: number; start: number; end: number; what: string }> {
    return [
      ...this.liveEvents()
        .filter((e) => e.id !== except?.eventId)
        .map((e) => ({ day: e.day, start: e.start, end: e.end, what: `${e.id} ${e.title}` })),
      ...this.liveBookings()
        .filter((b) => b.at && b.ref !== except?.bookingRef)
        .map((b) => ({ ...b.at!, what: `${b.ref} ${b.summary}` })),
    ];
  }

  whenText(day: number, time: number): string {
    return `${fmtDay(this.week, day)} ${fmtTime(time)}`;
  }

  /** Is (day, minute) still ahead of "now"? */
  isFuture(day: number, minute: number): boolean {
    return day > this.now.day || (day === this.now.day && minute >= this.now.time);
  }

  findContactsByName(query: string): Contact[] {
    const q = query.trim().toLowerCase();
    if (!q) return [];
    const exact = this.contacts.filter((c) => c.name.toLowerCase() === q);
    if (exact.length) return exact;
    return this.contacts.filter((c) => {
      const n = c.name.toLowerCase();
      return n.split(/[\s()]+/).includes(q) || n.startsWith(`${q} `);
    });
  }
}
