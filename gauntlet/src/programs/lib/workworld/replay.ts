/**
 * Display-only replay data for the dashboard's work-world stage (ui/src/components/viz/WorkWorldStage.tsx):
 * the inbox, the calendar week as it was at the start, and per action what changed (an email opened, an
 * email sent, an event created / moved / cancelled, a booking, a flag). Never sent to the model or scored.
 */
import { fmtDay, fmtTime } from './time.ts';
import type { WorkWorld } from './world.ts';
import type { Outcome, Level } from './score.ts';

export interface WorkWorldStep {
  /** Frame index (= action number). */
  f: number;
  open?: number[];
  sent?: { kind: string; to: string[]; subject: string };
  event?: { op: 'create' | 'move' | 'cancel'; id: string; cal: string; title: string; day: number; start: number; end: number };
  booking?: { op: 'book' | 'cancel'; ref: string; summary: string };
  userNote?: string;
  /** A scorer flag on this action, for the timeline ("severe", "minor", "over-action", "report"). */
  flags?: Array<{ kind: string; text: string }>;
}

export interface WorkWorldVisual {
  kind: 'workworld';
  data: {
    scenario: string;
    owner: string;
    days: string[];
    hours: [number, number];
    calendars: Array<{ id: string; label: string }>;
    inbox: Array<{ id: number; from: string; subject: string; when: string; tag?: string }>;
    events: Array<{ id: string; cal: string; title: string; day: number; start: number; end: number }>;
    steps: WorkWorldStep[];
    tasks: Array<{ label: string; level: Level; outcome: Outcome; value: number; detail: string }>;
  };
}

export function buildVisual(
  world: WorkWorld,
  scenario: string,
  tasks: WorkWorldVisual['data']['tasks'],
  flags: Array<{ n: number; kind: string; text: string }>,
  days = 5,
): WorkWorldVisual {
  const steps = new Map<number, WorkWorldStep>();
  const at = (n: number) => {
    let s = steps.get(n);
    if (!s) steps.set(n, (s = { f: n }));
    return s;
  };
  for (const e of world.effects) {
    const s = at(e.n);
    if (e.type === 'open') s.open = [...(s.open ?? []), ...e.ids];
    else if (e.type === 'send') s.sent = { kind: e.sent.kind, to: e.sent.to, subject: e.sent.subject };
    else if (e.type === 'event') s.event = { op: e.op, id: e.event.id, cal: e.event.cal, title: e.event.title, day: e.event.day, start: e.event.start, end: e.event.end };
    else if (e.type === 'booking') s.booking = { op: e.op, ref: e.booking.ref, summary: e.booking.summary };
    else if (e.type === 'user-note') s.userNote = e.text.slice(0, 200);
  }
  for (const f of flags) at(f.n).flags = [...(at(f.n).flags ?? []), { kind: f.kind, text: f.text }];
  return {
    kind: 'workworld',
    data: {
      scenario,
      owner: world.owner.name,
      days: Array.from({ length: days }, (_, d) => fmtDay(world.week, d)),
      hours: [8, 21],
      calendars: world.calendars.map((c) => ({ id: c.id, label: c.label })),
      inbox: world.emails
        .slice()
        .sort((a, b) => b.day - a.day || b.time - a.time || b.id - a.id)
        .map((e) => ({ id: e.id, from: e.from.name || e.from.email, subject: e.subject, when: `${fmtDay(world.week, e.day)} ${fmtTime(e.time)}`, ...(e.tag ? { tag: e.tag } : {}) })),
      events: world.initialEvents.map((e) => ({ id: e.id, cal: e.cal, title: e.title, day: e.day, start: e.start, end: e.end })),
      steps: [...steps.values()].sort((a, b) => a.f - b.f),
      tasks,
    },
  };
}
