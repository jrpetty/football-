/**
 * Replay stage for work-world simulations (The Personal Assistant, and later sims on the same engine): the
 * inbox (opened emails dimmed, the one opened this step highlighted, traps marked for viewers), the calendar
 * week rebuilt step by step (new, moved and cancelled events), what was sent and booked, scorer flags on the
 * step (a safety violation, an over-action, a trap reported) and, at the end, the hidden checklist.
 * Display only: built from `replay.visual` (kind "workworld"); older results without it fall back to the
 * generic player.
 */
import { useMemo } from 'react';
import type { ReplayData, ReplayFrame } from '../../types.ts';
import { cx } from '../ui.tsx';
import { VizHeadline } from './VizHeadline.tsx';
import { visualOf } from './vizModel.ts';
import './viz.css';
import './workworld.css';

interface WWEvent {
  id: string;
  cal: string;
  title: string;
  day: number;
  start: number;
  end: number;
}
interface WWStep {
  f: number;
  open?: number[];
  sent?: { kind: string; to: string[]; subject: string };
  event?: WWEvent & { op: 'create' | 'move' | 'cancel' };
  booking?: { op: 'book' | 'cancel'; ref: string; summary: string };
  userNote?: string;
  flags?: Array<{ kind: string; text: string }>;
}
export interface WorkWorldData {
  scenario: string;
  owner: string;
  days: string[];
  hours: [number, number];
  calendars: Array<{ id: string; label: string }>;
  inbox: Array<{ id: number; from: string; subject: string; when: string; tag?: string }>;
  events: WWEvent[];
  steps: WWStep[];
  tasks: Array<{ label: string; level: string; outcome: string; value: number; detail: string }>;
  /** Title of the records card (scenarios without bookings: payments, postings, refunds …). */
  recordsLabel?: string;
}

export function workWorldData(replay: ReplayData): WorkWorldData | null {
  const d = visualOf(replay, 'workworld');
  if (!d || !Array.isArray(d.inbox) || !Array.isArray(d.events) || !Array.isArray(d.steps) || !Array.isArray(d.days)) return null;
  return d as unknown as WorkWorldData;
}

const hhmm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
const FLAG_LABEL: Record<string, string> = { severe: 'Safety violation', minor: 'Safety slip', 'over-action': 'Nobody asked', outdated: 'Cancelled request acted on', report: 'Trap reported' };

function stateAt(data: WorkWorldData, step: number) {
  const events = new Map<string, WWEvent & { cancelled?: boolean; from?: WWEvent }>(data.events.map((e) => [e.id, { ...e }]));
  const opened = new Set<number>();
  const sent: NonNullable<WWStep['sent']>[] = [];
  const bookings = new Map<string, string>();
  const notes: string[] = [];
  for (const s of data.steps) {
    if (s.f > step) break;
    for (const id of s.open ?? []) opened.add(id);
    if (s.sent) sent.push(s.sent);
    if (s.event) {
      const prev = events.get(s.event.id);
      if (s.event.op === 'cancel') events.set(s.event.id, { ...(prev ?? s.event), cancelled: true });
      else events.set(s.event.id, { ...s.event, ...(s.event.op === 'move' && prev && s.f === step ? { from: prev } : {}) });
    }
    if (s.booking) {
      if (s.booking.op === 'book') bookings.set(s.booking.ref, s.booking.summary);
      else bookings.delete(s.booking.ref);
    }
    if (s.userNote) notes.push(s.userNote);
  }
  return { events: [...events.values()], opened, sent, bookings: [...bookings.entries()], notes };
}

function Week({ data, events, current }: { data: WorkWorldData; events: ReturnType<typeof stateAt>['events']; current?: WWStep }) {
  const [h0, h1] = data.hours;
  const span = (h1 - h0) * 60;
  const pos = (e: { start: number; end: number }) => ({ top: `${(Math.max(0, e.start - h0 * 60) / span) * 100}%`, height: `${Math.max(2.2, ((Math.min(h1 * 60, e.end) - Math.max(h0 * 60, e.start)) / span) * 100)}%` });
  const hours = Array.from({ length: h1 - h0 + 1 }, (_, i) => h0 + i);
  return (
    <div className="vz-card ww-week" aria-label="Calendar week">
      <div className="vz-card-k">
        Calendar · {data.calendars.map((c, i) => (
          <span key={c.id} className={cx('ww-key', `cal-${i}`)}>
            {c.label}
          </span>
        ))}
      </div>
      <div className="ww-grid" style={{ ['--days' as string]: data.days.length }}>
        <div className="ww-hours">
          {hours.map((h) => (
            <span key={h} style={{ top: `${(((h - h0) * 60) / span) * 100}%` }}>{`${h}:00`}</span>
          ))}
        </div>
        {data.days.map((label, d) => (
          <div key={label} className="ww-day">
            <div className="ww-day-h">{label}</div>
            <div className="ww-day-b">
              {events
                .filter((e) => e.day === d && e.end > h0 * 60 && e.start < h1 * 60)
                .map((e) => {
                  const ci = Math.max(0, data.calendars.findIndex((c) => c.id === e.cal));
                  const now = current?.event?.id === e.id;
                  // Short events get one line (ellipsis); every event shows its full details on hover or keyboard focus.
                  const short = e.end - e.start <= 45;
                  const full = `${hhmm(e.start)}–${hhmm(e.end)} ${e.title}${e.cancelled ? ' (cancelled)' : ''}`;
                  return (
                    <div key={e.id} tabIndex={0} aria-label={full} className={cx('ww-ev', `cal-${ci}`, short && 'short', e.cancelled && 'cancelled', now && 'is-now')} style={pos(e)} title={`${e.id} ${full}`}>
                      <b>{hhmm(e.start)}</b> <span className="ww-ev-t">{e.title}</span>
                    </div>
                  );
                })}
              {events
                .filter((e) => e.from && e.from.day === d)
                .map((e) => (
                  <div key={`${e.id}-from`} className="ww-ev ghost" style={pos(e.from!)} aria-hidden="true" />
                ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function WorkWorldStage({ data, frames, idx, video }: { data: WorkWorldData; frames: ReplayFrame[]; idx: number; video: boolean }) {
  const frame = frames[Math.min(idx, frames.length - 1)]!;
  const st = useMemo(() => stateAt(data, frame.step), [data, frame.step]);
  const current = data.steps.find((s) => s.f === frame.step);
  const atEnd = idx >= frames.length - 1;
  const openNow = new Set(current?.open ?? []);
  return (
    <div className={cx('vz-stage ww', video && 'video')}>
      <VizHeadline eyebrow={`${frame.label ?? `Step ${frame.step}`} · ${data.owner}'s week`} title={frame.action ?? frame.outcome ?? ''} tone={frame.tone === 'good' ? 'good' : frame.tone === 'bad' ? 'bad' : 'neutral'} />
      {frame.action && frame.outcome && <p className="ww-outcome">{frame.outcome}</p>}
      {current?.flags?.length ? (
        <div className="ww-flags">
          {current.flags.map((f, i) => (
            <span key={i} className={cx('ww-flag', `k-${f.kind}`)}>
              <b>{FLAG_LABEL[f.kind] ?? f.kind}</b> {f.text}
            </span>
          ))}
        </div>
      ) : null}
      <div className={cx('ww-cols', data.calendars.length === 0 && 'solo')}>
        <div className="vz-card ww-inbox" aria-label="Inbox">
          <div className="vz-card-k">Inbox · {st.opened.size} of {data.inbox.length} opened</div>
          <ul>
            {data.inbox.map((m) => (
              <li key={m.id} className={cx(st.opened.has(m.id) && 'opened', openNow.has(m.id) && 'is-now', m.tag && `tag-${m.tag}`)}>
                <span className="ww-id">#{m.id}</span>
                <span className="ww-from">{m.from}</span>
                <span className="ww-subj">{m.subject}</span>
                {m.tag === 'trap' && <span className="ww-chip trap">trap</span>}
                {m.tag === 'brief' && <span className="ww-chip brief">the jobs</span>}
              </li>
            ))}
          </ul>
        </div>
        {data.calendars.length > 0 && <Week data={data} events={st.events} current={current} />}
      </div>
      <div className="ww-cols even">
        <div className="vz-card">
          <div className="vz-card-k">Sent · {st.sent.length}</div>
          {st.sent.length ? (
            <ul className="ww-list">
              {st.sent
                .slice(-6)
                .reverse()
                .map((s, i) => (
                  <li key={i} className={cx(i === 0 && current?.sent && 'is-now')}>
                    <span className="ww-chip">{s.kind}</span> {s.subject} <small>→ {s.to.join(', ')}</small>
                  </li>
                ))}
            </ul>
          ) : (
            <p className="vz-fine">Nothing sent yet.</p>
          )}
        </div>
        <div className="vz-card">
          <div className="vz-card-k">{data.recordsLabel ?? `Bookings and notes to ${data.owner.split(' ')[0]}`}</div>
          {st.bookings.length || st.notes.length ? (
            <ul className="ww-list">
              {st.bookings.map(([ref, s]) => (
                <li key={ref} className={cx(current?.booking?.ref === ref && 'is-now')}>
                  <span className="ww-chip">{ref}</span> {s}
                </li>
              ))}
              {st.notes.map((n, i) => (
                <li key={`n${i}`} className={cx(current?.userNote === n && 'is-now')}>
                  <span className="ww-chip">note</span> {n.length > 140 ? `${n.slice(0, 139)}…` : n}
                </li>
              ))}
            </ul>
          ) : (
            <p className="vz-fine">{data.recordsLabel ? 'Nothing recorded yet.' : 'No bookings or notes yet.'}</p>
          )}
        </div>
      </div>
      {atEnd && data.tasks.length > 0 && (
        <div className="vz-card ww-tasks">
          <div className="vz-card-k">The hidden checklist</div>
          <ul>
            {data.tasks.map((t) => (
              <li key={t.label} className={`o-${t.outcome}`}>
                <span className="ww-chip">{t.outcome}</span>
                <b>{t.label}</b> <small>({t.level})</small>
                <span className="ww-detail">{t.detail}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
