/**
 * The standard work-world tools: mail, calendars, contacts, notes, bookable services, messages to the user.
 *
 * A scenario picks the tool sets it needs (and can add its own Tool objects: an accounts ledger, a refund
 * button, a query box …). Each tool reads its arguments from the parsed ACTION line, changes the world and
 * returns the text the model sees, a short form for older turns and a one-line outcome for the replay.
 * Tools never throw on bad input: they return { ok: false } with a plain explanation, and the action still
 * counts against the budget.
 */
import { fmtDay, fmtRange, fmtTime, fmtWhen, overlaps, parseDay, parseRange, type Parsed } from './time.ts';
import type { ParsedAction } from './protocol.ts';
import type { CalEvent, Email, Sent, WorkWorld } from './world.ts';

export interface ToolOutcome {
  ok: boolean;
  /** Full result shown to the model right after the action. */
  text: string;
  /** Short form shown once the exchange is old (default: the first line of text). */
  brief?: string;
  /** One line for the replay. */
  outcome?: string;
  tone?: 'good' | 'bad' | 'neutral';
  /** Ends the case (FINISH). */
  finish?: boolean;
}

export interface Tool<W extends WorkWorld = WorkWorld> {
  name: string;
  aliases?: string[];
  /** Help lines for the system prompt ("NAME args   what it does"). */
  usage: string[];
  /** Read-only tools are safe suggestions for the Random Baseline's menu. */
  readOnly?: boolean;
  /** Tools whose text goes on the lines after the ACTION line (a reply cut off at the output limit is not applied). */
  takesBody?: boolean;
  run(world: W, call: ParsedAction, n: number): ToolOutcome;
}

const fail = (text: string): ToolOutcome => ({ ok: false, text, outcome: text, tone: 'bad' });

function oneLine(text: string, max = 90): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1)}…` : t;
}

function parseEmailId(text: string): number | null {
  const m = /^(?:email\s*|message\s*|msg\s*|e)?#?\s*(\d{1,4})\b/i.exec(text.trim());
  return m ? Number(m[1]) : null;
}

const EMAIL_RE = /[a-z0-9._%+'-]+@[a-z0-9.-]+\.[a-z]{2,}/gi;

/** Addresses from free text: "a@x.com, Tom Hughes; Mum" → addresses (names are looked up in the contacts). */
export function resolveRecipients(world: WorkWorld, text: string): Parsed<string[]> {
  const out: string[] = [];
  const parts = text
    .split(/[,;]|\s+and\s+|\s*&\s*/i)
    .map((p) => p.trim())
    .filter(Boolean);
  if (!parts.length) return { ok: false, error: 'No recipient given.' };
  for (const p of parts) {
    const emails = p.match(EMAIL_RE);
    if (emails) {
      for (const e of emails) out.push(e.toLowerCase().replace(/\.$/, ''));
      continue;
    }
    const name = p.replace(/^(?:to|cc)\s*:\s*/i, '').replace(/[<>"]/g, '').trim();
    const hits = world.findContactsByName(name).filter((c) => c.email);
    if (hits.length === 1) out.push(hits[0]!.email!.toLowerCase());
    else if (hits.length > 1) return { ok: false, error: `"${name}" matches more than one contact (${hits.map((h) => `${h.name} <${h.email}>`).join(', ')}). Use the email address.` };
    else return { ok: false, error: `"${name}" is not an email address or a contact with an email address. Use CONTACTS to look people up.` };
  }
  return { ok: true, value: [...new Set(out)] };
}

function addrText(a: { name: string; email: string }): string {
  return a.name ? `${a.name} <${a.email}>` : a.email;
}

function threadOf(world: WorkWorld, e: Email): Email[] {
  return world.emails.filter((x) => x.thread === e.thread);
}

function emailText(world: WorkWorld, e: Email): string {
  const lines = [
    `Email #${e.id}`,
    `From: ${addrText(e.from)}`,
    `To: ${e.to.map(addrText).join(', ')}`,
    ...(e.cc?.length ? [`Cc: ${e.cc.map(addrText).join(', ')}`] : []),
    `Date: ${fmtWhen(world.week, e.day, e.time)}`,
    `Subject: ${e.subject}`,
    ...(e.attachments?.length ? [`Attachments: ${e.attachments.join(', ')}`] : []),
  ];
  const thread = threadOf(world, e);
  if (thread.length > 1) lines.push(`(Part of a conversation of ${thread.length} emails: ${thread.map((t) => `#${t.id}`).join(', ')}. THREAD ${e.id} shows them all.)`);
  return `${lines.join('\n')}\n\n${e.body.trim()}`;
}

function listLine(world: WorkWorld, e: Email): string {
  const thread = threadOf(world, e);
  const from = e.from.name || e.from.email;
  return `#${String(e.id).padEnd(3)} ${fmtWhen(world.week, e.day, e.time).padEnd(16)}  ${from.slice(0, 26).padEnd(26)}  ${e.subject}${thread.length > 1 ? `  [conversation of ${thread.length}]` : ''}${e.attachments?.length ? '  [attachment]' : ''}`;
}

function newestFirst(list: Email[]): Email[] {
  return list.slice().sort((a, b) => b.day - a.day || b.time - a.time || b.id - a.id);
}

function recordSend<W extends WorkWorld>(world: W, n: number, s: Omit<Sent, 'n'>): Sent {
  const sent: Sent = { n, ...s };
  world.sent.push(sent);
  world.effects.push({ n, type: 'send', sent });
  return sent;
}

function needBody(call: ParsedAction, minArgs: number): string {
  if (call.body.trim()) return call.body.trim();
  // Body written inline as the last "|" argument.
  if (call.args.length > minArgs) return call.args.slice(minArgs).join(' | ').trim();
  return '';
}

// ───────────────────────────────── Mail ─────────────────────────────────

export function mailTools(): Tool[] {
  return [
    {
      name: 'INBOX',
      aliases: ['LIST_INBOX', 'EMAILS', 'MAIL', 'LIST_EMAILS', 'CHECK_INBOX'],
      usage: ['INBOX                                   list every email, newest first'],
      readOnly: true,
      run(world) {
        const list = newestFirst(world.emails);
        return {
          ok: true,
          text: `INBOX: ${list.length} emails, newest first\n${list.map((e) => listLine(world, e)).join('\n')}`,
          brief: `(the inbox list of ${list.length} emails was shown here; INBOX shows it again)`,
          outcome: `Listed ${list.length} emails.`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'SEARCH',
      aliases: ['SEARCH_INBOX', 'FIND', 'SEARCH_EMAILS'],
      usage: ['SEARCH <words>                          emails whose sender, subject or text contain all the words'],
      readOnly: true,
      run(world, call) {
        const words = call.argText.toLowerCase().split(/\s+/).filter(Boolean);
        if (!words.length) return fail('SEARCH needs some words, e.g. SEARCH invoice March.');
        const hits = newestFirst(world.emails.filter((e) => words.every((w) => `${e.from.name} ${e.from.email} ${e.subject} ${e.body}`.toLowerCase().includes(w))));
        return {
          ok: true,
          text: hits.length ? `${hits.length} email${hits.length === 1 ? '' : 's'} match "${call.argText}":\n${hits.map((e) => listLine(world, e)).join('\n')}` : `No emails match "${call.argText}".`,
          brief: `(search for "${oneLine(call.argText, 40)}": ${hits.length} match${hits.length === 1 ? '' : 'es'}: ${hits.map((h) => `#${h.id}`).join(', ') || 'none'})`,
          outcome: `${hits.length} match${hits.length === 1 ? '' : 'es'} for "${oneLine(call.argText, 40)}".`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'READ',
      aliases: ['OPEN', 'READ_EMAIL', 'OPEN_EMAIL', 'VIEW', 'SHOW'],
      usage: ['READ <email#>                           open one email, e.g. READ 12'],
      readOnly: true,
      run(world, call, n) {
        const id = parseEmailId(call.argText);
        const e = id === null ? undefined : world.email(id);
        if (!e) return fail(`There is no email ${call.argText ? `"${call.argText}"` : 'number given'}. Use the numbers from INBOX, e.g. READ 3.`);
        world.opened.add(e.id);
        world.effects.push({ n, type: 'open', ids: [e.id] });
        return {
          ok: true,
          text: emailText(world, e),
          brief: `(email #${e.id} from ${e.from.name || e.from.email}, "${e.subject}", was shown here; READ ${e.id} shows it again)`,
          outcome: `Opened #${e.id} from ${e.from.name || e.from.email}: “${oneLine(e.subject, 60)}”.`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'THREAD',
      aliases: ['READ_THREAD', 'OPEN_THREAD', 'CONVERSATION'],
      usage: ['THREAD <email#>                         open the whole conversation that email belongs to'],
      readOnly: true,
      run(world, call, n) {
        const id = parseEmailId(call.argText);
        const e = id === null ? undefined : world.email(id);
        if (!e) return fail(`There is no email ${call.argText ? `"${call.argText}"` : 'number given'}. Use the numbers from INBOX, e.g. THREAD 3.`);
        const thread = threadOf(world, e);
        for (const t of thread) world.opened.add(t.id);
        world.effects.push({ n, type: 'open', ids: thread.map((t) => t.id) });
        return {
          ok: true,
          text: `Conversation "${e.subject.replace(/^(re|fwd?):\s*/i, '')}" (${thread.length} email${thread.length === 1 ? '' : 's'}, oldest first)\n\n${thread.map((t) => emailText(world, t).replace(/\n\(Part of a conversation[^\n]*\)/, '')).join('\n\n──────────\n\n')}`,
          brief: `(the conversation ${thread.map((t) => `#${t.id}`).join(', ')} was shown here; THREAD ${e.id} shows it again)`,
          outcome: `Opened the conversation ${thread.map((t) => `#${t.id}`).join(', ')}.`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'SEND',
      aliases: ['SEND_EMAIL', 'EMAIL', 'COMPOSE', 'WRITE_EMAIL', 'NEW_EMAIL'],
      usage: ['SEND <to> | <subject>                   send a new email; write the message on the lines after the ACTION line (<to>: addresses or contact names, comma-separated)'],
      takesBody: true,
      run(world, call, n) {
        if (call.args.length < 2) return fail('SEND needs recipients and a subject: SEND name@example.com | Subject, then the message on the lines below.');
        const to = resolveRecipients(world, call.args[0]!);
        if (!to.ok) return fail(to.error);
        const body = needBody(call, 2);
        if (!body) return fail('The email has no text. Write the message on the lines after the ACTION line.');
        const subject = call.args[1]!;
        recordSend(world, n, { kind: 'send', to: to.value, subject, body, fullText: `${subject}\n${body}` });
        return { ok: true, text: `Sent "${subject}" to ${to.value.join(', ')}.`, outcome: `Sent “${oneLine(subject, 50)}” to ${to.value.join(', ')}.`, tone: 'neutral' };
      },
    },
    {
      name: 'REPLY',
      aliases: ['REPLY_TO', 'RESPOND', 'ANSWER', 'REPLY_EMAIL'],
      usage: ['REPLY <email#>                          reply to the sender of that email; write the message on the lines after the ACTION line'],
      takesBody: true,
      run(world, call, n) {
        const id = parseEmailId(call.args[0] ?? '');
        const e = id === null ? undefined : world.email(id);
        if (!e) return fail(`There is no email ${call.args[0] ? `"${call.args[0]}"` : 'number given'} to reply to. Use the numbers from INBOX, e.g. REPLY 3.`);
        const body = needBody(call, 1);
        if (!body) return fail('The reply has no text. Write the message on the lines after the ACTION line.');
        const subject = /^re:/i.test(e.subject) ? e.subject : `Re: ${e.subject}`;
        const to = [e.from.email.toLowerCase()];
        recordSend(world, n, { kind: 'reply', to, subject, body, refId: e.id, fullText: `${subject}\n${body}` });
        return { ok: true, text: `Replied to ${addrText(e.from)}: "${subject}".`, outcome: `Replied to ${e.from.name || e.from.email} (#${e.id}).`, tone: 'neutral' };
      },
    },
    {
      name: 'FORWARD',
      aliases: ['FWD', 'FORWARD_EMAIL'],
      usage: ['FORWARD <email#> | <to>                 forward that email (attachments included); optional covering text on the lines after'],
      takesBody: true,
      run(world, call, n) {
        const id = parseEmailId(call.args[0] ?? '');
        const e = id === null ? undefined : world.email(id);
        if (!e) return fail(`There is no email ${call.args[0] ? `"${call.args[0]}"` : 'number given'} to forward. Use FORWARD 3 | name@example.com.`);
        let toText = call.args[1] ?? '';
        if (!toText) {
          // "FORWARD 12 to a@b.com"
          const m = /\bto\s+(.+)$/i.exec(call.argText);
          if (m) toText = m[1]!;
        }
        const to = resolveRecipients(world, toText.replace(/^to\s+/i, ''));
        if (!to.ok) return fail(`FORWARD needs an email number and recipients: FORWARD ${e.id} | name@example.com. ${to.error}`);
        const covering = needBody(call, 2);
        const subject = /^fwd?:/i.test(e.subject) ? e.subject : `Fwd: ${e.subject}`;
        const fullText = `${subject}\n${covering}\n\n---------- Forwarded message ----------\n${emailText(world, e)}`;
        recordSend(world, n, { kind: 'forward', to: to.value, subject, body: covering, refId: e.id, fullText });
        return {
          ok: true,
          text: `Forwarded #${e.id} "${e.subject}"${e.attachments?.length ? ` (with ${e.attachments.join(', ')})` : ''} to ${to.value.join(', ')}.`,
          outcome: `Forwarded #${e.id} to ${to.value.join(', ')}.`,
          tone: 'neutral',
        };
      },
    },
  ];
}

// ───────────────────────────────── Calendars ─────────────────────────────────

function eventLine(world: WorkWorld, e: CalEvent): string {
  const who = e.attendees.length ? ` · with ${e.attendees.map((a) => world.contacts.find((c) => c.email?.toLowerCase() === a)?.name ?? a).join(', ')}` : '';
  return `  ${e.id.padEnd(4)} ${fmtRange(e.start, e.end)}  ${e.title}${e.location ? ` · ${e.location}` : ''}${who}`;
}

function calendarOf(world: WorkWorld, token: string): string | null {
  const t = token.trim().toLowerCase().replace(/\s*calendar$/, '');
  const hit = world.calendars.find((c) => c.id === t || c.label.toLowerCase() === t || c.label.toLowerCase().startsWith(t));
  return hit && t ? hit.id : null;
}

function findEvent(world: WorkWorld, text: string): CalEvent | undefined {
  const id = text.trim().replace(/^#/, '').toUpperCase();
  return world.events.find((e) => e.id.toUpperCase() === id && !e.cancelled);
}

const RANGE_TAIL = /(\d{1,2}(?:[:.]\d{2})?\s*(?:am|pm)?\s*(?:-|–|—|to|until)\s*\d{1,2}(?:[:.]\d{2})?\s*(?:am|pm)?|\d{1,2}(?:[:.]\d{2})?\s*(?:am|pm)?\s*(?:for|\+)\s*\d{1,3}\s*(?:m|min|mins|minutes)?)\s*$/i;

/** "Wed 11 Mar 14:00-14:30" in one argument → [day text, range text]. */
function splitDayRange(text: string): [string, string] | null {
  const m = RANGE_TAIL.exec(text.trim());
  if (!m) return null;
  return [text.trim().slice(0, m.index).trim(), m[1]!];
}

function clashes(world: WorkWorld, ev: { day: number; start: number; end: number; cal: string; id?: string }): CalEvent[] {
  return world.liveEvents(ev.cal).filter((x) => x.id !== ev.id && overlaps(x, ev));
}

function dayAndRange(world: WorkWorld, args: string[]): Parsed<{ day: number; start: number; end: number; used: number }> {
  let dayText = args[0] ?? '';
  let rangeText = args[1] ?? '';
  let used = 2;
  const joined = splitDayRange(dayText);
  if (joined && joined[0]) {
    [dayText, rangeText] = joined;
    used = 1;
  }
  const d = parseDay(dayText, world.week, world.bookableDays);
  if (!d.ok) return d;
  const r = parseRange(rangeText);
  if (!r.ok) return r;
  const [start, end] = r.value;
  if (end > 24 * 60) return { ok: false, error: 'An event must end on the day it starts.' };
  if (!world.isFuture(d.value, start)) return { ok: false, error: `${world.whenText(d.value, start)} is in the past (it is now ${world.whenText(world.now.day, world.now.time)}).` };
  return { ok: true, value: { day: d.value, start, end, used } };
}

export function calendarTools(): Tool[] {
  return [
    {
      name: 'CALENDAR',
      aliases: ['CAL', 'SHOW_CALENDAR', 'LIST_EVENTS', 'EVENTS', 'VIEW_CALENDAR'],
      usage: ['CALENDAR <calendar> [day]              list a calendar\'s events for the week (or one day), e.g. CALENDAR work Thu'],
      readOnly: true,
      run(world, call) {
        const words = call.argText.replace(/\|/g, ' ').trim().split(/\s+/).filter(Boolean);
        let cal = world.calendars[0]!.id;
        let note = '';
        if (words.length && calendarOf(world, words[0]!)) cal = calendarOf(world, words.shift()!)!;
        else if (words.length && calendarOf(world, words.slice(0, 2).join(' '))) {
          cal = calendarOf(world, words.slice(0, 2).join(' '))!;
          words.splice(0, 2);
        } else note = `(No calendar named, so this is the ${world.calendars[0]!.label} calendar. Calendars: ${world.calendars.map((c) => c.id).join(', ')}.)\n`;
        let days: number[] = [];
        for (let d = world.bookableDays[0]; d <= world.bookableDays[1]; d++) days.push(d);
        if (words.length) {
          const d = parseDay(words.join(' '), world.week, world.bookableDays);
          if (!d.ok) return fail(d.error);
          days = [d.value];
        }
        const info = world.calendars.find((c) => c.id === cal)!;
        const body = days
          .map((d) => {
            const evs = world.liveEvents(cal).filter((e) => e.day === d).sort((a, b) => a.start - b.start || a.end - b.end);
            return `${fmtDay(world.week, d)}\n${evs.length ? evs.map((e) => eventLine(world, e)).join('\n') : '  (nothing)'}`;
          })
          .join('\n');
        const span = days.length === 1 ? fmtDay(world.week, days[0]!) : `${fmtDay(world.week, days[0]!)} to ${fmtDay(world.week, days[days.length - 1]!)}`;
        return {
          ok: true,
          text: `${note}${info.label.toUpperCase()} CALENDAR, ${span} (UK time)\n${body}`,
          brief: `(the ${info.label} calendar for ${span} was shown here; CALENDAR ${cal}${days.length === 1 ? ` ${fmtDay(world.week, days[0]!).split(' ')[0]}` : ''} shows it again)`,
          outcome: `Looked at the ${info.label} calendar (${span}).`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'CREATE_EVENT',
      aliases: ['ADD_EVENT', 'NEW_EVENT', 'SCHEDULE', 'CREATE_MEETING', 'INVITE', 'SEND_INVITE', 'BOOK_MEETING'],
      usage: ['CREATE_EVENT <calendar> | <day> | <start>-<end> | <title> [| <invitees>]   add an event; invitees (addresses or contact names, comma-separated) get an invitation'],
      run(world, call, n) {
        let args = call.args.slice();
        let cal = calendarOf(world, args[0] ?? '');
        if (cal) args = args.slice(1);
        else cal = world.calendars[0]!.id;
        const when = dayAndRange(world, args);
        if (!when.ok) return fail(`${when.error} Use: CREATE_EVENT work | Thu | 14:00-14:30 | Title | invitee@example.com`);
        const rest = args.slice(when.value.used);
        const title = (rest[0] ?? '').trim();
        if (!title) return fail('The event needs a title: CREATE_EVENT work | Thu | 14:00-14:30 | Title');
        let attendees: string[] = [];
        if (rest[1]?.trim()) {
          const r = resolveRecipients(world, rest[1]);
          if (!r.ok) return fail(r.error);
          attendees = r.value.filter((a) => !world.ownerAddresses.has(a));
        }
        const ev: CalEvent = { id: world.nextEventId(cal), cal, day: when.value.day, start: when.value.start, end: when.value.end, title, attendees, createdBy: n };
        const clash = clashes(world, ev);
        world.events.push(ev);
        world.effects.push({ n, type: 'event', op: 'create', event: { ...ev, attendees: [...attendees] } });
        if (attendees.length) world.sent.push({ n, kind: 'invite', to: attendees, subject: `Invitation: ${title}`, body: `${title} ${world.whenText(ev.day, ev.start)}`, fullText: `Invitation: ${title}\n${fmtWhen(world.week, ev.day, ev.start)}-${fmtTime(ev.end)}` });
        const info = world.calendars.find((c) => c.id === cal)!;
        return {
          ok: true,
          text: `Created ${ev.id} on the ${info.label} calendar: ${fmtDay(world.week, ev.day)} ${fmtRange(ev.start, ev.end)} "${title}".${attendees.length ? ` Invitations sent to ${attendees.join(', ')}.` : ''}${clash.length ? `\nNote: it overlaps ${clash.map((c) => `${c.id} "${c.title}" ${fmtRange(c.start, c.end)}`).join(', ')}.` : ''}`,
          outcome: `Created ${ev.id} “${oneLine(title, 40)}” ${fmtDay(world.week, ev.day)} ${fmtRange(ev.start, ev.end)}${attendees.length ? `, invited ${attendees.join(', ')}` : ''}.`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'MOVE_EVENT',
      aliases: ['RESCHEDULE', 'MOVE', 'RESCHEDULE_EVENT', 'UPDATE_EVENT', 'MOVE_MEETING', 'EDIT_EVENT'],
      usage: ['MOVE_EVENT <event id> | <day> | <start>-<end>                    move an event (its invitees are told)'],
      run(world, call, n) {
        const ev = findEvent(world, call.args[0] ?? '');
        if (!ev) return fail(`There is no event "${call.args[0] ?? ''}". Use the ids from CALENDAR, e.g. MOVE_EVENT W4 | Thu | 10:00-10:30.`);
        const when = dayAndRange(world, call.args.slice(1));
        if (!when.ok) return fail(`${when.error} Use: MOVE_EVENT ${ev.id} | Thu | 10:00-10:30`);
        const before = { ...ev, attendees: [...ev.attendees] };
        ev.day = when.value.day;
        ev.start = when.value.start;
        ev.end = when.value.end;
        const clash = clashes(world, ev);
        world.effects.push({ n, type: 'event', op: 'move', event: { ...ev, attendees: [...ev.attendees] }, before });
        if (ev.attendees.length) world.sent.push({ n, kind: 'invite', to: [...ev.attendees], subject: `Updated: ${ev.title}`, body: `${ev.title} moved`, fullText: `Updated invitation: ${ev.title}\n${fmtWhen(world.week, ev.day, ev.start)}-${fmtTime(ev.end)}` });
        return {
          ok: true,
          text: `Moved ${ev.id} "${ev.title}" from ${fmtDay(world.week, before.day)} ${fmtRange(before.start, before.end)} to ${fmtDay(world.week, ev.day)} ${fmtRange(ev.start, ev.end)}.${ev.attendees.length ? ` Updated invitations sent to ${ev.attendees.join(', ')}.` : ''}${clash.length ? `\nNote: it now overlaps ${clash.map((c) => `${c.id} "${c.title}" ${fmtRange(c.start, c.end)}`).join(', ')}.` : ''}`,
          outcome: `Moved ${ev.id} “${oneLine(ev.title, 40)}” to ${fmtDay(world.week, ev.day)} ${fmtRange(ev.start, ev.end)}.`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'CANCEL_EVENT',
      aliases: ['DELETE_EVENT', 'REMOVE_EVENT', 'CANCEL_MEETING'],
      usage: ['CANCEL_EVENT <event id>                                          cancel an event (its invitees are told)'],
      run(world, call, n) {
        const ev = findEvent(world, call.args[0] ?? '');
        if (!ev) return fail(`There is no event "${call.args[0] ?? ''}". Use the ids from CALENDAR.`);
        ev.cancelled = true;
        world.effects.push({ n, type: 'event', op: 'cancel', event: { ...ev, attendees: [...ev.attendees] } });
        if (ev.attendees.length) world.sent.push({ n, kind: 'invite', to: [...ev.attendees], subject: `Cancelled: ${ev.title}`, body: `${ev.title} cancelled`, fullText: `Cancelled: ${ev.title}` });
        return { ok: true, text: `Cancelled ${ev.id} "${ev.title}" (${fmtDay(world.week, ev.day)} ${fmtRange(ev.start, ev.end)}).`, outcome: `Cancelled ${ev.id} “${oneLine(ev.title, 40)}”.`, tone: 'neutral' };
      },
    },
  ];
}

// ───────────────────────────────── Contacts & notes ─────────────────────────────────

export function contactTools(): Tool[] {
  return [
    {
      name: 'CONTACTS',
      aliases: ['CONTACT', 'LOOKUP', 'LOOK_UP', 'FIND_CONTACT', 'ADDRESS_BOOK', 'SEARCH_CONTACTS'],
      usage: ['CONTACTS [name or word]                 look up contacts (all of them, or those matching)'],
      readOnly: true,
      run(world, call) {
        const q = call.argText.trim().toLowerCase();
        const list = q ? world.contacts.filter((c) => [c.name, c.email, c.role, c.notes, c.address].filter(Boolean).join(' ').toLowerCase().includes(q)) : world.contacts;
        const text = list
          .map((c) => `${c.name} · ${c.role}${c.email ? ` · ${c.email}` : ''}${c.phone ? ` · ${c.phone}` : ''}${c.address ? `\n    address: ${c.address}` : ''}${c.notes ? `\n    note: ${c.notes}` : ''}`)
          .join('\n');
        return {
          ok: true,
          text: list.length ? `CONTACTS${q ? ` matching "${call.argText.trim()}"` : ''} (${list.length})\n${text}` : `No contacts match "${call.argText.trim()}".`,
          brief: `(contacts${q ? ` matching "${oneLine(call.argText, 30)}"` : ''} were shown here: ${list.map((c) => c.name).join(', ').slice(0, 160) || 'none'})`,
          outcome: q ? `Looked up “${oneLine(call.argText, 30)}” in contacts (${list.length}).` : `Opened the contacts (${list.length}).`,
          tone: 'neutral',
        };
      },
    },
  ];
}

export function noteTools(): Tool[] {
  return [
    {
      name: 'NOTES',
      aliases: ['READ_NOTES', 'SHOW_NOTES', 'LIST_NOTES', 'OPEN_NOTES'],
      usage: ["NOTES                                   show the owner's notes"],
      readOnly: true,
      run(world) {
        return {
          ok: true,
          text: world.notes.length ? `NOTES (${world.notes.length})\n\n${world.notes.map((n) => `## ${n.title}\n${n.body.trim()}`).join('\n\n')}` : 'There are no notes.',
          brief: `(the notes were shown here: ${world.notes.map((n) => n.title).join(', ')}; NOTES shows them again)`,
          outcome: `Read the notes (${world.notes.length}).`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'WRITE_NOTE',
      aliases: ['ADD_NOTE', 'NEW_NOTE', 'SAVE_NOTE', 'CREATE_NOTE'],
      usage: ['WRITE_NOTE <title>                      save a note (text on the lines after the ACTION line); same title replaces your earlier note'],
      takesBody: true,
      run(world, call, n) {
        const title = (call.args[0] ?? '').trim();
        const body = needBody(call, 1);
        if (!title || !body) return fail('WRITE_NOTE needs a title and some text: WRITE_NOTE Title, then the text on the lines below.');
        const own = world.notes.find((x) => x.title.toLowerCase() === title.toLowerCase() && x.writtenBy !== undefined);
        if (own) {
          own.body = body;
          own.writtenBy = n;
        } else world.notes.push({ title, body, writtenBy: n });
        world.effects.push({ n, type: 'note', title });
        return { ok: true, text: `Saved the note "${title}".`, outcome: `Saved a note “${oneLine(title, 40)}”.`, tone: 'neutral' };
      },
    },
  ];
}

// ───────────────────────────────── Services ─────────────────────────────────

export function bookingTools(): Tool[] {
  const serviceOf = (world: WorkWorld, token: string) => {
    const t = token.trim().toLowerCase();
    if (!t) return null;
    return [...world.services.values()].find((s) => s.id === t || s.label.toLowerCase() === t || t.startsWith(s.id) || s.label.toLowerCase().includes(t)) ?? null;
  };
  const list = (world: WorkWorld) => [...world.services.values()].map((s) => s.id).join(', ');
  return [
    {
      name: 'AVAILABILITY',
      aliases: ['CHECK_AVAILABILITY', 'AVAILABLE', 'SERVICES', 'SLOTS', 'OPTIONS'],
      usage: ['AVAILABILITY <service> [day]            what a service can book (services are listed in the next line)'],
      readOnly: true,
      run(world, call) {
        const words = call.argText.replace(/\|/g, ' ').trim().split(/\s+/).filter(Boolean);
        const s = serviceOf(world, words[0] ?? '');
        if (!s) return fail(`Name a service: ${list(world)}. For example AVAILABILITY ${[...world.services.keys()][0]} Thu.`);
        let day: number | null = null;
        if (words.length > 1) {
          const d = parseDay(words.slice(1).join(' '), world.week, world.bookableDays);
          if (!d.ok) return fail(d.error);
          day = d.value;
        }
        return {
          ok: true,
          text: s.availability(world, day),
          brief: `(availability for ${s.label}${day !== null ? ` on ${fmtDay(world.week, day)}` : ''} was shown here)`,
          outcome: `Checked ${s.label}${day !== null ? ` on ${fmtDay(world.week, day)}` : ''}.`,
          tone: 'neutral',
        };
      },
    },
    {
      name: 'BOOK',
      aliases: ['BOOK_SERVICE', 'RESERVE', 'ORDER', 'MAKE_BOOKING'],
      usage: ['BOOK <service> | …                      make a booking; the arguments for each service are in the next line'],
      run(world, call, n) {
        let args = call.args.slice();
        let s = serviceOf(world, args[0] ?? '');
        if (!s && args[0]) {
          // "BOOK restaurant Bistro Lune | Thu | …": the service name glued to the first argument.
          const first = args[0].split(/\s+/);
          const s2 = serviceOf(world, first[0]!);
          if (s2) {
            s = s2;
            args = [first[0]!, first.slice(1).join(' '), ...args.slice(1)];
          }
        }
        if (!s) return fail(`Name a service first: BOOK <service> | … Services: ${list(world)}.`);
        const r = s.book(world, args.slice(1).map((a) => a.trim()));
        if (!r.ok) return fail(`${r.error} Use: BOOK ${s.id} | ${s.bookUsage}`);
        const booking = { ...r.booking, ref: world.nextRef(s.id), service: s.id, n };
        world.bookings.push(booking);
        world.effects.push({ n, type: 'booking', op: 'book', booking: { ...booking } });
        return { ok: true, text: `Booked: ${booking.summary}. Reference ${booking.ref}.`, outcome: `Booked ${booking.summary} (${booking.ref}).`, tone: 'neutral' };
      },
    },
    {
      name: 'CANCEL_BOOKING',
      aliases: ['CANCEL_ORDER', 'CANCEL_RESERVATION'],
      usage: ['CANCEL_BOOKING <reference>              cancel one of your bookings'],
      run(world, call, n) {
        const ref = call.argText.trim().toUpperCase();
        const b = world.bookings.find((x) => x.ref.toUpperCase() === ref && !x.cancelled);
        if (!b) return fail(`There is no booking "${call.argText.trim()}". Your bookings: ${world.liveBookings().map((x) => x.ref).join(', ') || 'none'}.`);
        b.cancelled = true;
        world.effects.push({ n, type: 'booking', op: 'cancel', booking: { ...b } });
        return { ok: true, text: `Cancelled ${b.ref}: ${b.summary}.`, outcome: `Cancelled booking ${b.ref}.`, tone: 'neutral' };
      },
    },
    {
      name: 'BOOKINGS',
      aliases: ['MY_BOOKINGS', 'LIST_BOOKINGS'],
      usage: ['BOOKINGS                                list the bookings you have made'],
      readOnly: true,
      run(world) {
        const live = world.liveBookings();
        return { ok: true, text: live.length ? `Your bookings:\n${live.map((b) => `${b.ref}: ${b.summary}`).join('\n')}` : 'You have made no bookings.', outcome: `Listed bookings (${live.length}).`, tone: 'neutral' };
      },
    },
  ];
}

// ───────────────────────────────── The user ─────────────────────────────────

export function userTools(ownerFirst: string): Tool[] {
  return [
    {
      name: 'NOTE_TO_USER',
      aliases: ['NOTE_TO_OWNER', 'MESSAGE_USER', 'TELL_USER', 'ASK_USER', 'FLAG', 'NOTIFY_USER', 'NOTE_FOR_USER', `NOTE_TO_${ownerFirst.toUpperCase()}`, `TELL_${ownerFirst.toUpperCase()}`, `MESSAGE_${ownerFirst.toUpperCase()}`],
      usage: [`NOTE_TO_USER                            leave ${ownerFirst} a message (text on the lines after); ${ownerFirst} reads them at the end of the week and cannot reply`],
      takesBody: true,
      run(world, call, n) {
        const text = (call.body.trim() || call.argText.trim()).trim();
        if (!text) return fail(`The note is empty. Write the message to ${ownerFirst} on the lines after the ACTION line.`);
        world.userNotes.push({ n, text });
        world.effects.push({ n, type: 'user-note', text });
        return { ok: true, text: `Left ${ownerFirst} a message (${world.userNotes.length} so far). ${ownerFirst} will see it at the end of the week.`, outcome: `Left ${ownerFirst} a note: “${oneLine(text, 70)}”`, tone: 'neutral' };
      },
    },
    {
      name: 'FINISH',
      aliases: ['DONE', 'END', 'STOP', 'SUBMIT', 'FINISHED', 'COMPLETE'],
      usage: ['FINISH                                  you are done for the week'],
      run() {
        return { ok: true, text: 'Finished.', outcome: 'Finished for the week.', tone: 'neutral', finish: true };
      },
    },
  ];
}

/** Name → canonical name, for the parser. */
export function toolNames(tools: readonly Tool[]): Map<string, string> {
  const m = new Map<string, string>();
  for (const t of tools) for (const a of [t.name, ...(t.aliases ?? [])]) if (!m.has(a.toUpperCase())) m.set(a.toUpperCase(), t.name);
  return m;
}
