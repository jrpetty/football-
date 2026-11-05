/**
 * Find a Slot drawn as calendars on London time: the week with everyone's busy blocks (one thin lane per person),
 * and for each meeting the day it was booked: the model's slot drawn over the attendees' lanes beside the correct
 * slot, with why the model's slot clashes (or that an earlier one was free).
 */
import { useId, type ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Fix, Impact, Legend, Paper, Tally, Tick, docVisual, type Tone } from './parts.tsx';
import { niceDate, plural } from './kit.ts';
import { londonOffset, meetingAnswer, parseMeeting, slotUtc, toLondon, type Busy, type MeetingDoc, type SlotMark } from './meeting.ts';
import './meeting.css';

const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const dayLabel = (d: number) => {
  const x = new Date(d * 60000);
  return `${DOW[x.getUTCDay()]} ${x.getUTCDate()} ${MON[x.getUTCMonth()]}`;
};
const clock = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;

/** London-time span of a day worth drawing: from the earliest anyone starts to the latest anyone finishes. */
function span(doc: MeetingDoc, date: number): [number, number] {
  let lo = 24 * 60;
  let hi = 0;
  for (const p of doc.people)
    for (const b of p.busy) {
      if (b.kind !== 'off' || b.label === 'Weekend') continue;
      const l = toLondon(doc, b.from);
      if (l.date === date && b.label === 'Finished work') hi = Math.max(hi, l.min);
      const e = toLondon(doc, b.to);
      if (e.date === date && b.label === 'Not working yet') lo = Math.min(lo, e.min);
    }
  if (lo >= hi) return [7 * 60, 19 * 60];
  return [Math.max(0, Math.floor(lo / 60) * 60), Math.min(24 * 60, Math.ceil(hi / 60) * 60)];
}

interface Lane {
  name: string;
  blocks: Busy[];
  hue: number;
}

function lanesFor(doc: MeetingDoc, names: string[], room: boolean): Lane[] {
  const HUES = [205, 140, 330, 40, 265, 175, 10, 95, 300, 55];
  const lanes: Lane[] = doc.people.map((p, i) => ({ p, hue: HUES[i % HUES.length]! })).filter(({ p }) => names.includes(p.name)).map(({ p, hue }) => ({ name: p.first, blocks: p.busy, hue }));
  if (room) lanes.push({ name: 'Fleet Room', blocks: doc.room, hue: 30 });
  return lanes;
}

interface Slot {
  from: number;
  to: number;
  tone: 'good' | 'bad' | 'key';
  label: string;
}

/** One London day, a column per person: off hours shaded, busy blocks labelled, slots drawn on top. */
function DayView({ doc, date, lanes, slots, compact }: { doc: MeetingDoc; date: number; lanes: Lane[]; slots: Slot[]; compact?: boolean }) {
  const [lo, hi] = span(doc, date);
  const pid = useId().replace(/:/g, '');
  const H = compact ? 150 : 260;
  const top = compact ? 2 : 18;
  const left = compact ? 0 : 34;
  const laneW = compact ? 120 / Math.max(1, lanes.length) : Math.max(46, Math.min(92, 440 / Math.max(1, lanes.length)));
  const W = left + lanes.length * laneW;
  const y = (m: number) => top + ((m - lo) / (hi - lo)) * H;
  const dayStart = date - londonOffset(doc, date);
  const clip = (b: { from: number; to: number }) => {
    const a = Math.max(b.from, dayStart + lo);
    const z = Math.min(b.to, dayStart + hi);
    return z > a ? [a - dayStart, z - dayStart] : null;
  };
  const hours: number[] = [];
  for (let h = Math.ceil(lo / 60) * 60; h <= hi; h += compact ? 120 : 60) hours.push(h);
  return (
    <svg className="cd-cal" viewBox={`0 0 ${W + 2} ${top + H + 4}`} width={compact ? '100%' : W + 2} preserveAspectRatio={compact ? 'none' : undefined} style={compact ? { height: `${H}px` } : { maxWidth: '100%', height: 'auto' }} aria-hidden="true">
      <defs>
        <pattern id={`off${pid}`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width="6" height="6" className="cd-cal-offbg" />
          <line x1="0" y1="0" x2="0" y2="6" className="cd-cal-hatch" />
        </pattern>
      </defs>
      {!compact &&
        hours.map((h) => (
          <g key={h}>
            <line className="cd-cal-hr" x1={left} x2={W} y1={y(h)} y2={y(h)} />
            <text className="cd-cal-t" x={left - 4} y={y(h) + 3} textAnchor="end">
              {clock(h)}
            </text>
          </g>
        ))}
      {lanes.map((l, i) => {
        const x = left + i * laneW;
        return (
          <g key={l.name}>
            <rect className="cd-cal-lane" x={x + 1} y={top} width={laneW - 2} height={H} />
            {!compact && (
              <text className="cd-cal-name" x={x + laneW / 2} y={12} textAnchor="middle">
                {l.name.length > 9 ? `${l.name.slice(0, 8)}…` : l.name}
              </text>
            )}
            {l.blocks.map((b, k) => {
              const c = clip(b);
              if (!c) return null;
              const off = b.kind === 'off' || b.kind === 'holiday';
              const h = y(c[1]!) - y(c[0]!);
              return (
                <g key={k}>
                  <rect className={cx('cd-cal-b', off ? 'off' : b.kind)} x={x + (compact ? 0.6 : 2)} y={y(c[0]!)} width={laneW - (compact ? 1.2 : 4)} height={Math.max(1, h)} rx={off || compact ? 0 : 2} style={off ? { fill: `url(#off${pid})` } : { fill: `hsl(${l.hue} 65% 52% / 0.8)` }}>
                    <title>{`${l.name}: ${b.label}`}</title>
                  </rect>
                  {!compact && !off && h > 11 && (
                    <text className="cd-cal-bt" x={x + 5} y={y(c[0]!) + 9}>
                      {b.label.length > laneW / 5.5 ? `${b.label.slice(0, Math.floor(laneW / 5.5) - 1)}…` : b.label}
                    </text>
                  )}
                </g>
              );
            })}
          </g>
        );
      })}
      {slots.map((s, i) => {
        const c = clip(s);
        if (!c) return null;
        return (
          <g key={i}>
            <rect className={cx('cd-cal-slot', `t-${s.tone}`)} x={left + 0.5} y={y(c[0]!)} width={W - left - 1} height={Math.max(3, y(c[1]!) - y(c[0]!))} rx="3" />
            {!compact && (
              <text className={cx('cd-cal-slt', `t-${s.tone}`)} x={s.tone === 'bad' ? left + 4 : W - 4} y={s.tone === 'bad' ? y(c[1]!) + 9 : y(c[0]!) - 2} textAnchor={s.tone === 'bad' ? 'start' : 'end'}>
                {s.label}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

function Week({ doc }: { doc: MeetingDoc }) {
  const days = doc.days.slice(0, 10);
  const lanes = lanesFor(doc, doc.people.map((p) => p.name), doc.meetings.some((m) => m.room));
  return (
    <Paper className="cd-week" title={`The team’s week on London time · ${days.length > 5 ? `${days.length} working days` : 'Mon–Fri'}`}>
      <div className="cd-scroll">
        <div className="cd-week-grid" style={{ gridTemplateColumns: `repeat(${days.length}, minmax(${lanes.length * 9 + 4}px, 1fr))` }}>
          {days.map((d) => (
            <div key={d} className="cd-week-day">
              <div className="cd-week-dh">{dayLabel(d)}</div>
              <DayView doc={doc} date={d} lanes={lanes} slots={[]} compact />
            </div>
          ))}
        </div>
      </div>
      <ul className="cd-people">
        {lanes.map((l) => (
          <li key={l.name}>
            <i style={{ background: `hsl(${l.hue} 55% 50%)` }} />
            {l.name}
            <span className="muted"> {doc.people.find((p) => p.first === l.name)?.city ?? ''}</span>
          </li>
        ))}
      </ul>
    </Paper>
  );
}

function MeetingList({ doc }: { doc: MeetingDoc }) {
  return (
    <Paper className="cd-mlist2" title="Meetings to book — the earliest slot for each">
      <ul>
        {doc.meetings.map((m) => (
          <li key={m.id}>
            <b>({m.id})</b> {m.minutes} min · {m.who.map((w) => w.split(' ')[0]).join(', ')}
            {m.room && <span className="cd-room"> · Fleet Room</span>}
            {m.notBefore !== null && <span className="muted"> · not before {clock(toLondon(doc, m.notBefore).min)} {dayLabel(toLondon(doc, m.notBefore).date)}</span>}
          </li>
        ))}
      </ul>
    </Paper>
  );
}

function SlotView({ doc, mark }: { doc: MeetingDoc; mark: SlotMark }) {
  const mt = doc.meetings.find((m) => m.id === mark.id)!;
  const at = slotUtc(doc, mark.model);
  const keyAt = slotUtc(doc, mark.key);
  const lanes = lanesFor(doc, mt.who, mt.room);
  const slots = (date: number): Slot[] => {
    const out: Slot[] = [];
    if (keyAt !== null && toLondon(doc, keyAt).date === date) out.push({ from: keyAt, to: keyAt + mt.minutes, tone: 'key', label: 'correct' });
    if (at !== null && toLondon(doc, at).date === date && !mark.ok) out.push({ from: at, to: at + mt.minutes, tone: 'bad', label: 'model' });
    if (at !== null && toLondon(doc, at).date === date && mark.ok) out.push({ from: at, to: at + mt.minutes, tone: 'good', label: 'model ✓' });
    return out;
  };
  const dates = [...new Set([at, keyAt].filter((x): x is number => x !== null).map((x) => toLondon(doc, x).date))].sort((a, b) => a - b);
  return (
    <div className={cx('cd-slotcard', mark.ok ? 't-good' : 't-bad')}>
      <div className="cd-slot-head">
        <b>({mark.id})</b> {mt.minutes} min with {mt.who.map((w) => w.split(' ')[0]).join(', ')}
        {mt.room && ' · Fleet Room'}
        <span className="spacer" />
        <Tick ok={mark.ok} />
      </div>
      <div className="cd-slot-ans">
        <span className={cx('cd-slot-model', !mark.ok && 'no')}>{mark.model ? niceDate(mark.model) : 'no time'}</span>
        {!mark.ok && mark.key && <Fix>{niceDate(mark.key)}</Fix>}
      </div>
      {mark.why && <p className="cd-why">{mark.why}</p>}
      {!mark.ok && dates.length > 0 && (
        <div className="cd-slot-days">
          {dates.map((d) => (
            <div key={d} className="cd-slot-day">
              <div className="cd-week-dh">{dayLabel(d)} · London time</div>
              <DayView doc={doc} date={d} lanes={lanes} slots={slots(d)} />
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export const MEETING_SCHEDULER = docVisual<MeetingDoc, SlotMark[]>({
  family: 'extraction',
  caption: 'Everyone’s week on London time, then each meeting: the model’s slot over the attendees’ calendars beside the correct one, and what the model’s slot clashes with.',
  doc: (input) => parseMeeting(input.turns[0] ?? '', input.expected),
  answer: (input, doc) => meetingAnswer(input, doc),
  sub: (doc) => `${plural(doc.meetings.length, 'meeting')} · ${plural(doc.people.length, 'person', 'people')} in ${[...new Set(doc.people.map((p) => p.city))].join(', ')}`,
  Question: ({ doc }) => (
    <>
      <MeetingList doc={doc} />
      <Week doc={doc} />
    </>
  ),
  Key: ({ doc }) => (
    <Paper className="cd-mlist2" title="The earliest slot for each meeting (London time)">
      <ul>
        {doc.meetings.map((m) => (
          <li key={m.id}>
            <b>({m.id})</b> {doc.key?.[m.id] ? niceDate(doc.key[m.id]) : 'no slot'}
          </li>
        ))}
      </ul>
    </Paper>
  ),
  Answer: ({ doc, ans }) => (
    <>
      {[...ans.filter((m) => !m.ok), ...ans.filter((m) => m.ok)].map((m) => (
        <SlotView key={m.id} doc={doc} mark={m} />
      ))}
      <Legend
        items={[
          { tone: 'good', label: 'correct slot' },
          { tone: 'bad', label: 'the model’s slot' },
          { tone: 'neutral', label: 'not working' },
        ]}
      />
    </>
  ),
  Result: ({ ans }) => {
    const lines: Array<{ tone: Tone; text: ReactNode }> = [];
    for (const m of ans.filter((x) => !x.ok && x.why?.startsWith('Clashes'))) lines.push({ tone: 'bad', text: <>Meeting ({m.id}) booked on top of something: {m.why!.replace(/^Clashes: /, '')}</> });
    const late = ans.filter((x) => !x.ok && x.why?.startsWith('This time works')).length;
    if (late) lines.push({ tone: 'warn', text: <>{plural(late, 'meeting')} booked later than needed: an earlier slot was free for everyone.</> });
    return (
      <>
        <Tally items={[{ n: ans.filter((m) => m.ok).length, of: ans.length, label: 'meetings at the earliest slot' }]} />
        <Impact lines={lines.slice(0, 5)} />
      </>
    );
  },
  sentence: (_doc, ans) => {
    const wrong = ans.filter((m) => !m.ok);
    if (!wrong.length) return `Found the earliest workable slot for all ${ans.length} meetings, across time zones, travel and the Friday rule.`;
    const clash = wrong.filter((m) => m.why?.startsWith('Clashes')).length;
    return `Booked ${ans.length - wrong.length} of ${ans.length} meetings at the earliest slot${clash ? `; ${plural(clash, 'invite')} would land on top of someone’s existing commitment` : ''}.`;
  },
  headline: (_doc, ans) => `${ans.filter((m) => m.ok).length} of ${ans.length} meetings booked right`,
  alt: {
    question: (doc) => `Calendars of ${doc.people.map((p) => `${p.first} (${p.city})`).join(', ')}; book ${doc.meetings.length} meetings at the earliest slot.`,
    key: (doc) => doc.meetings.map((m) => `(${m.id}) ${niceDate(doc.key?.[m.id] ?? '')}`).join('; '),
    answer: (_doc, ans) => ans.map((m) => `(${m.id}) ${m.model ?? 'none'}: ${m.ok ? 'right' : `wrong, should be ${m.key}. ${m.why ?? ''}`}`).join('; '),
  },
});
