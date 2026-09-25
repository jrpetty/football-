/**
 * The Startup's cash chart: the model's cash month by month (drawn up to the
 * current month) against the oracle and the do-nothing autopilot, with the
 * market events shaded, a marker on the current month and the gap to the
 * oracle bracketed. One y-axis, direct labels, a legend, month tooltips.
 */
import type { StartupSimWorld } from '../../types.ts';
import { NEUTRAL, SERIES_PALETTE, niceTicks } from '../charts/scale.ts';
import { money } from './simStory.ts';

const W = 680;
const H = 290;
const M = { l: 64, r: 128, t: 30, b: 34 };

const MODEL = SERIES_PALETTE[0]!;
const ORACLE = SERIES_PALETTE[3]!;

function short(n: number): string {
  const a = Math.abs(n);
  const s = a >= 1000 ? `$${(a / 1000).toFixed(a >= 100000 ? 0 : 1)}k` : `$${Math.round(a)}`;
  return n < 0 ? `−${s}` : s;
}

export function StartupChart({ world, model, month, modelLabel }: { world: StartupSimWorld; model: Array<{ month: number; cash: number }>; month: number; modelLabel: string }) {
  const months = world.months;
  const all = [...model.map((p) => p.cash), ...world.oracle.map((p) => p.cash), ...world.autopilot.map((p) => p.cash)];
  const yt = niceTicks(Math.min(0, ...all), Math.max(1, ...all), 5);
  const x = (m: number) => M.l + (m / months) * (W - M.l - M.r);
  const y = (v: number) => H - M.b - ((v - yt.min) / (yt.max - yt.min || 1)) * (H - M.t - M.b);
  const path = (pts: Array<{ month: number; cash: number }>) => pts.map((p, i) => `${i ? 'L' : 'M'}${x(p.month).toFixed(1)},${y(p.cash).toFixed(1)}`).join(' ');
  const shown = model.filter((p) => p.month <= month);
  const cur = shown[shown.length - 1];
  const orc = world.oracle.find((p) => p.month === month) ?? world.oracle[world.oracle.length - 1];
  const auto = world.autopilot;
  const ev = world.events;
  const bands = [
    { from: ev.priceWar.start, to: ev.priceWar.start + ev.priceWar.months - 1, label: 'Price war', cls: 'war' },
    { from: ev.supplierSpike.start, to: ev.supplierSpike.start + ev.supplierSpike.months - 1, label: 'Supplier spike', cls: 'spike' },
    ...(ev.shock ? [{ from: ev.shock.month, to: ev.shock.month, label: 'Safety scare', cls: 'shock' }] : []),
  ].filter((b) => b.from <= months);
  // End labels, nudged apart so they never overlap.
  const ends = [
    { name: modelLabel, v: cur?.cash, c: MODEL, show: !!cur },
    { name: 'Oracle', v: world.oracle[world.oracle.length - 1]?.cash, c: ORACLE, show: true },
    { name: 'Autopilot', v: auto[auto.length - 1]?.cash, c: NEUTRAL, show: true },
  ]
    .filter((e) => e.show && typeof e.v === 'number')
    .map((e) => ({ ...e, ly: y(e.v!) }))
    .sort((a, b) => a.ly - b.ly);
  for (let i = 1; i < ends.length; i++) if (ends[i]!.ly - ends[i - 1]!.ly < 19) ends[i]!.ly = ends[i - 1]!.ly + 19;
  const endX = (e: (typeof ends)[number]) => (e.name === modelLabel && cur ? x(cur.month) : x(months));
  return (
    <figure className="startup-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Cash by month: ${modelLabel} ${cur ? money(cur.cash) : ''}, oracle ${money(world.oracle[world.oracle.length - 1]?.cash ?? 0)}, autopilot ${money(auto[auto.length - 1]?.cash ?? 0)}.`}>
        <defs>
          <pattern id="sc-hatch" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <path d="M0 0 V8" stroke="currentColor" strokeWidth="2" opacity="0.22" />
          </pattern>
        </defs>
        {bands.map((b) => (
          <g key={b.label} className={`sc-band ${b.cls}`}>
            <rect x={x(b.from - 1)} y={M.t} width={Math.max(4, x(b.to) - x(b.from - 1))} height={H - M.t - M.b} fill="url(#sc-hatch)" />
            <text x={x(b.from - 1) + 4} y={M.t - 8} className="sc-band-label">
              {b.label}
            </text>
          </g>
        ))}
        {yt.ticks.map((t) => (
          <g key={t}>
            <line x1={M.l} x2={W - M.r} y1={y(t)} y2={y(t)} className={t === 0 ? 'sc-zero' : 'sc-grid'} />
            <text x={M.l - 8} y={y(t) + 4} textAnchor="end" className="sc-tick">
              {short(t)}
            </text>
          </g>
        ))}
        {Array.from({ length: months + 1 }, (_, m) => m).map((m) => (
          <text key={m} x={x(m)} y={H - 12} textAnchor="middle" className="sc-tick">
            {m === 0 ? 'start' : m}
          </text>
        ))}
        <line x1={x(month)} x2={x(month)} y1={M.t} y2={H - M.b} className="sc-now" />
        <path d={path(auto)} fill="none" stroke={NEUTRAL} strokeWidth="2" strokeDasharray="2 5" strokeLinecap="round" />
        <path d={path(world.oracle)} fill="none" stroke={ORACLE} strokeWidth="2" strokeDasharray="7 5" />
        {shown.length > 0 && <path d={path(shown)} fill="none" stroke={MODEL} strokeWidth="3.2" strokeLinejoin="round" strokeLinecap="round" />}
        {shown.map((p) => (
          <circle key={p.month} cx={x(p.month)} cy={y(p.cash)} r={p.month === month ? 6 : 3.5} fill={MODEL} stroke="var(--surface-1)" strokeWidth="2" />
        ))}
        {cur && orc && month > 0 && Math.abs(orc.cash - cur.cash) > 1 && (
          <g className="sc-gap">
            <line x1={x(month) + 10} x2={x(month) + 10} y1={y(cur.cash)} y2={y(orc.cash)} />
            <line x1={x(month) + 5} x2={x(month) + 15} y1={y(cur.cash)} y2={y(cur.cash)} />
            <line x1={x(month) + 5} x2={x(month) + 15} y1={y(orc.cash)} y2={y(orc.cash)} />
          </g>
        )}
        {ends.map((e) => (
          <text key={e.name} x={endX(e) + 10} y={e.ly + 4} className="sc-end" fill="currentColor">
            <tspan fill={e.c}>■ </tspan>
            {e.name.length > 12 ? `${e.name.slice(0, 11)}…` : e.name}
          </text>
        ))}
        {Array.from({ length: months + 1 }, (_, m) => m).map((m) => {
          const mm = model.find((p) => p.month === m && m <= month);
          const o = world.oracle.find((p) => p.month === m);
          const a = auto.find((p) => p.month === m);
          return (
            <rect key={`h${m}`} x={x(m) - (W - M.l - M.r) / months / 2} y={M.t} width={(W - M.l - M.r) / months} height={H - M.t - M.b} fill="transparent">
              <title>{`Month ${m}: ${modelLabel} ${mm ? money(mm.cash) : '—'} · oracle ${o ? money(o.cash) : '—'} · autopilot ${a ? money(a.cash) : '—'}`}</title>
            </rect>
          );
        })}
      </svg>
      <figcaption className="sc-legend">
        <span>
          <i style={{ background: MODEL }} /> {modelLabel} cash
        </span>
        <span>
          <i className="dash" style={{ borderColor: ORACLE }} /> Oracle (knows the hidden market)
        </span>
        <span>
          <i className="dot" style={{ borderColor: NEUTRAL }} /> Autopilot (never changes the plan)
        </span>
        <span>
          <i className="hatch" /> market event
        </span>
      </figcaption>
    </figure>
  );
}
