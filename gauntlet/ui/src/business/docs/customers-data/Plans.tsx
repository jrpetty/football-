/**
 * Plan the Van Routes drawn as a map (laid out from the travel times) with the depot, every drop and each van's
 * route, beside the best plan, with broken rules flagged (a window missed, a van overloaded, back too late, driving
 * without a break). Build the Rota drawn as a staff × day grid with every broken rule marked on its cell, and the
 * wage bill against the cheapest legal rota.
 */
import type { ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Chip, Impact, Paper, Stamp, Tick, docVisual, type Tone } from './parts.tsx';
import { pence, plural } from './kit.ts';
import { hm } from '../../../../../src/scoring/verifiers/routes.ts';
import { bestReplays, parseRota, parseRoutes, personCost, rotaAnswer, rotaFlags, routesAnswer, type RotaAnswer, type RotaDoc, type RoutesAnswer, type RoutesDoc } from './plans.ts';
import './plans.css';

const VAN_HUES = [205, 25, 140, 300, 50];
const mins = (m: number) => (m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')} min`);

// ───────────────────────────── van routes ─────────────────────────────

function RouteMap({ doc, routes, late, title }: { doc: RoutesDoc; routes: Array<{ van: string; stops: string[] }>; late?: Set<string>; title: string }) {
  const S = 300;
  const P = 26;
  const xy = (i: number) => [P + doc.pos[i]![0] * (S - 2 * P), P + doc.pos[i]![1] * (S - 2 * P)] as const;
  const idx = new Map(doc.world.drops.map((d, i) => [d.id, i + 1]));
  const served = new Map<string, number>();
  routes.forEach((r, vi) => r.stops.forEach((s) => s !== 'BREAK' && served.set(s, vi)));
  const [dx, dy] = xy(0);
  return (
    <div className="cd-mapbox">
      <div className="cd-k">{title}</div>
      <svg viewBox={`0 0 ${S} ${S}`} className="cd-map" aria-hidden="true">
        <rect className="cd-map-bg" x="0" y="0" width={S} height={S} rx="10" />
        {routes.map((r, vi) => {
          const pts = [0, ...r.stops.filter((s) => s !== 'BREAK').map((s) => idx.get(s) ?? 0), 0].map((i) => xy(i).join(','));
          if (pts.length <= 2) return null;
          return <polyline key={r.van} className="cd-route" points={pts.join(' ')} style={{ stroke: `hsl(${VAN_HUES[vi % VAN_HUES.length]} 75% 55%)` }} />;
        })}
        {doc.world.drops.map((d, i) => {
          const [x, y] = xy(i + 1);
          const vi = served.get(d.id);
          const tight = d.open > doc.world.depotOpen || d.close < doc.world.depotClose;
          return (
            <g key={d.id}>
              {late?.has(d.id) && <circle cx={x} cy={y} r="13" className="cd-late-ring" />}
              <circle cx={x} cy={y} r="8.5" className="cd-drop" style={vi !== undefined ? { fill: `hsl(${VAN_HUES[vi % VAN_HUES.length]} 70% 45%)` } : undefined} />
              <text x={x} y={y + 3} textAnchor="middle" className="cd-drop-t">
                {d.id.replace(/^D0?/, '')}
              </text>
              <text x={x > S * 0.62 ? x - 11 : x + 11} y={y - 7} className="cd-place" textAnchor={x > S * 0.62 ? 'end' : 'start'}>
                {(d.name.split(',').pop() ?? d.name).trim()}
                {tight ? ' ⏱' : ''}
              </text>
            </g>
          );
        })}
        <rect x={dx - 9} y={dy - 9} width="18" height="18" rx="3" className="cd-depot" />
        <text x={dx} y={dy + 4} textAnchor="middle" className="cd-drop-t">
          ⌂
        </text>
      </svg>
    </div>
  );
}

function DropsTable({ doc }: { doc: RoutesDoc }) {
  const w = doc.world;
  return (
    <Paper className="cd-drops" title={`Depot ${w.depot.split(',').pop()?.trim()} · open ${hm(w.depotOpen)}–${hm(w.depotClose)} · ${plural(w.vans.length, 'van')}: ${w.vans.map((v) => `${v.name} ${v.capacity} kg`).join(', ')}`}>
      <div className="cd-scroll">
        <table className="cd-grid">
          <thead>
            <tr>
              <th>Drop</th>
              <th>Customer</th>
              <th className="num">kg</th>
              <th>Window</th>
            </tr>
          </thead>
          <tbody>
            {w.drops.map((d) => (
              <tr key={d.id}>
                <td>{d.id}</td>
                <td>{d.name}</td>
                <td className="num">{d.kg}</td>
                <td>{d.open <= w.depotOpen && d.close >= w.depotClose - 60 ? 'any time' : `${d.open > w.depotOpen ? hm(d.open) : ''}–${d.close < w.depotClose ? hm(d.close) : ''}`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="cd-why">Drivers: at most {w.driveLimit / 60} h of driving before a {w.breakMinutes}-minute break.</p>
    </Paper>
  );
}

function VanTable({ doc, rows }: { doc: RoutesDoc; rows: Array<{ van: string; stops: string[]; r: { back: number; kg: number; problems: string[] } }> }) {
  return (
    <table className="cd-grid cd-vans">
      <thead>
        <tr>
          <th>Van</th>
          <th>Route</th>
          <th className="num">Load</th>
          <th>Back</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((x, vi) => {
          const v = doc.world.vans.find((y) => y.id === x.van)!;
          const over = x.r.kg > v.capacity;
          return (
            <tr key={x.van} className={cx(x.r.problems.length > 0 && 'r-bad')}>
              <td>
                <i className="cd-vdot" style={{ background: `hsl(${VAN_HUES[vi % VAN_HUES.length]} 75% 55%)` }} /> {v.name}
              </td>
              <td className="wrap">{x.stops.length ? x.stops.join(' → ') : 'stays at the depot'}</td>
              <td className={cx('num', over && 'bad')}>
                {x.r.kg}/{v.capacity} kg
              </td>
              <td className={cx(x.r.back > doc.world.depotClose && 'bad')}>{x.stops.length ? hm(x.r.back) : '—'}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

export const DELIVERY_ROUTES = docVisual<RoutesDoc, RoutesAnswer>({
  family: 'business',
  caption: 'A map of the depot and drops laid out from the travel times: the model’s van routes beside the best plan, with any broken rule flagged and the total van time compared.',
  doc: (input) => parseRoutes(input),
  answer: (input, doc) => routesAnswer(input, doc),
  sub: (doc) => `${plural(doc.world.drops.length, 'drop')} · ${plural(doc.world.vans.length, 'van')}${doc.date ? ` · ${doc.date}` : ''}`,
  Question: ({ doc }) => (
    <>
      <RouteMap doc={doc} routes={[]} title="Depot and drops (⏱ = delivery window)" />
      <DropsTable doc={doc} />
    </>
  ),
  Key: ({ doc }) => {
    const best = bestReplays(doc);
    return (
      <>
        <RouteMap doc={doc} routes={best} title={`Best plan · ${mins(doc.optimum)} of van time`} />
        <VanTable doc={doc} rows={best} />
      </>
    );
  },
  Answer: ({ doc, ans }) => {
    const best = bestReplays(doc);
    const problems = ans.replays.flatMap((x) => x.r.problems);
    const feasible = ans.outcome.detail.verdict !== 'infeasible' && !ans.bad;
    return (
      <>
        <div className="cd-decide">
          <Stamp tone={!feasible ? 'bad' : ans.total === doc.optimum ? 'good' : 'warn'} sub={ans.total !== null ? `${mins(ans.total)} of van time` : undefined}>
            {!feasible ? 'Breaks the rules' : ans.total === doc.optimum ? 'Best possible' : 'Works, but slower'}
          </Stamp>
          {ans.total !== null && ans.total !== doc.optimum && <span className="cd-fix">best: {mins(doc.optimum)}</span>}
        </div>
        {ans.bad && <p className="cd-why">{ans.bad}</p>}
        <div className="cd-maps">
          <RouteMap doc={doc} routes={ans.replays} late={ans.late} title="The model’s routes" />
          <RouteMap doc={doc} routes={best} title="The best plan" />
        </div>
        {ans.replays.length > 0 && <VanTable doc={doc} rows={ans.replays} />}
        {problems.length > 0 && (
          <ul className="cd-rules">
            {problems.slice(0, 6).map((p, i) => (
              <li key={i}>
                <Tick ok={false} /> {p}
              </li>
            ))}
          </ul>
        )}
      </>
    );
  },
  Result: ({ doc, ans }) => {
    const items = (ans.outcome.detail.items ?? []) as Array<{ label: string; passed: boolean; detail?: string }>;
    const lines: Array<{ tone: Tone; text: ReactNode }> = [];
    if (ans.late.size) lines.push({ tone: 'bad', text: <>{plural(ans.late.size, 'customer')} ({[...ans.late].join(', ')}) would get their delivery after their window closed.</> });
    if (ans.total !== null && ans.total > doc.optimum && ans.outcome.detail.verdict !== 'infeasible') lines.push({ tone: 'warn', text: <>{mins(ans.total - doc.optimum)} more paid van time than the best plan, every day this route runs.</> });
    if (ans.stated !== null && ans.total !== null && ans.stated !== ans.total) lines.push({ tone: 'warn', text: <>Quoted a total of {mins(ans.stated)}; the routes really take {mins(ans.total)}.</> });
    return (
      <>
        <ul className="cd-rules">
          {items.map((it, i) => (
            <li key={i} className={it.passed ? 't-good' : 't-bad'}>
              <Tick ok={it.passed} /> {it.label}
              {!it.passed && it.detail && <small className="cd-why"> — {it.detail}</small>}
            </li>
          ))}
        </ul>
        <Impact lines={lines} />
      </>
    );
  },
  sentence: (doc, ans) => {
    if (ans.bad || ans.outcome.detail.verdict === 'infeasible') return 'This plan breaks a rule, so it could not be used: it scores nothing however short it is.';
    const quote = ans.stated !== null && ans.total !== null && ans.stated !== ans.total ? ` It also quoted the total as ${mins(ans.stated)}, so the score is halved.` : ans.stated === null ? ' It quoted no total, so the score is halved.' : '';
    if (ans.total === doc.optimum) return `A legal plan with the least van time possible (${mins(doc.optimum)}).${quote}`;
    return `A legal plan, but ${mins((ans.total ?? 0) - doc.optimum)} slower than the best one.${quote}`;
  },
  headline: (doc, ans) => (ans.bad || ans.outcome.detail.verdict === 'infeasible' ? 'Breaks the rules' : ans.total === doc.optimum ? 'Best possible plan' : `Legal, ${mins((ans.total ?? 0) - doc.optimum)} slower than the best`),
  alt: {
    question: (doc) => `A depot and ${doc.world.drops.length} drops to deliver with ${doc.world.vans.length} vans: ${doc.world.drops.map((d) => d.name).join('; ')}.`,
    key: (doc) => `Best plan: ${Object.entries(doc.best).map(([v, s]) => `${v}: ${s.join(', ') || 'stays'}`).join('; ')} (${mins(doc.optimum)}).`,
    answer: (_doc, ans) => `The model’s plan: ${ans.replays.map((x) => `${x.van}: ${x.stops.join(', ') || 'stays'}`).join('; ')}. ${ans.replays.flatMap((x) => x.r.problems).join('; ') || 'No broken rules.'}`,
  },
});

// ───────────────────────────── rota ─────────────────────────────

function RotaGrid({ doc, byShift, flags, compare }: { doc: RotaDoc; byShift: Map<string, string[]>; flags?: Pick<RotaAnswer, 'cells' | 'shifts' | 'hours' | 'paid'>; compare?: Map<string, string[]> }) {
  const w = doc.world;
  const f = flags ?? rotaFlags(w, byShift);
  const cellFlag = (pid: string, sid: string) => f.cells.filter((c) => c.pid === pid && c.sid === sid);
  const dayFlags = (d: number) => f.shifts.filter((x) => w.shifts.find((s) => s.id === x.sid)?.day === d);
  return (
    <div className="cd-scroll">
      <table className="cd-grid cd-rota">
        <thead>
          <tr>
            <th>Staff</th>
            {doc.days.map((d, i) => (
              <th key={d} className={cx(dayFlags(i).length > 0 && 'bad')} title={dayFlags(i).map((x) => `${x.sid}: ${x.why}`).join('\n')}>
                {d}
                {dayFlags(i).length > 0 && ' ⚠'}
              </th>
            ))}
            <th className="num">Hours</th>
          </tr>
        </thead>
        <tbody>
          {w.staff.map((p) => (
            <tr key={p.id}>
              <td className="cd-rota-who">
                <b>{p.name.split(' ')[0]}</b>
                {p.under18 && <Chip tone="warn">under 18</Chip>}
                <div className="muted cd-small">
                  {pence(p.rate)}/h · {p.skills.map((s) => w.skillNames[s] ?? s).join(', ') || 'no keys'}
                </div>
              </td>
              {doc.days.map((_, di) => {
                const mine = w.shifts.filter((s) => s.day === di && (byShift.get(s.id) ?? []).includes(p.id));
                const theirs = compare ? w.shifts.filter((s) => s.day === di && (compare.get(s.id) ?? []).includes(p.id)) : null;
                const bad = mine.flatMap((s) => cellFlag(p.id, s.id));
                const diff = theirs && (theirs.map((s) => s.id).join() !== mine.map((s) => s.id).join());
                return (
                  <td key={di} className={cx('cd-rota-c', bad.length > 0 && 'bad', diff && !bad.length && 'diff')} title={bad.map((b) => b.why).join('\n')}>
                    {mine.map((s) => (
                      <span key={s.id} className="cd-shift">
                        {s.id.split('-')[1]}
                      </span>
                    ))}
                    {bad.length > 0 && <span className="cd-cellwhy">{bad[0]!.why}</span>}
                    {(() => {
                      const today = w.shifts.filter((s) => s.day === di);
                      const off = today.filter((s) => p.off.includes(s.id));
                      if (!off.length || mine.length) return null;
                      return <span className="cd-offday">{off.length === today.length ? 'off' : `no ${off.map((s) => s.id.split('-')[1]).join('/')}`}</span>;
                    })()}
                  </td>
                );
              })}
              <td className={cx('num', f.hours[p.id] && 'bad')} title={f.hours[p.id]}>
                {f.paid[p.id] ?? 0}
                <span className="muted cd-small">
                  {' '}
                  / {p.min}–{p.max}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ShiftKey({ doc }: { doc: RotaDoc }) {
  const names = new Map<string, { name: string; start: number; end: number }>();
  for (const s of doc.world.shifts) names.set(s.id.split('-')[1]!, { name: s.name, start: s.start % 1440, end: s.end % 1440 });
  return (
    <div className="cd-legend cd-shiftkey">
      {[...names.entries()].map(([k, v]) => (
        <span key={k}>
          <span className="cd-shift">{k}</span> {v.name} {hm(v.start)}–{hm(v.end)}
        </span>
      ))}
    </div>
  );
}

export const SHIFT_ROTA = docVisual<RotaDoc, RotaAnswer>({
  family: 'business',
  caption: 'The week’s rota as a staff × day grid: every broken rule (rest, under-18, availability, skills, hours) marked on its cell, and the wage bill against the cheapest legal rota.',
  doc: (input) => parseRota(input),
  answer: (input, doc) => rotaAnswer(input, doc),
  sub: (doc) => `${doc.place}${doc.week ? ` · week of ${doc.week}` : ''} · ${plural(doc.world.staff.length, 'person', 'people')} · ${plural(doc.world.shifts.length, 'shift')}`,
  Question: ({ doc }) => (
    <Paper className="cd-rota-paper" title="Who can work (an empty rota to fill)">
      <RotaGrid doc={doc} byShift={new Map()} flags={{ cells: [], shifts: [], hours: {}, paid: {} }} />
      <ShiftKey doc={doc} />
      <p className="cd-why">Rules: {doc.world.rest} h rest between shifts ({doc.world.youngRest} h for under-18s), at most {doc.world.cap} h a week, contracted hours, skills on every shift, weekend shifts shared fairly.</p>
    </Paper>
  ),
  Key: ({ doc }) => (
    <Paper className="cd-rota-paper" title={`Cheapest legal rota · ${pence(doc.optimum)}`}>
      <RotaGrid doc={doc} byShift={doc.best} />
      <ShiftKey doc={doc} />
    </Paper>
  ),
  Answer: ({ doc, ans }) => {
    const legal = ans.outcome.detail.verdict !== 'infeasible' && !!ans.byShift;
    return (
      <>
        <div className="cd-decide">
          <Stamp tone={!legal ? 'bad' : ans.cost === doc.optimum ? 'good' : 'warn'} sub={ans.cost !== null ? `wage bill ${pence(ans.cost)}` : undefined}>
            {!legal ? 'Breaks the rules' : ans.cost === doc.optimum ? 'Cheapest legal rota' : 'Legal, but dearer'}
          </Stamp>
          <span className="cd-fix">cheapest: {pence(doc.optimum)}</span>
        </div>
        {ans.bad && <p className="cd-why">{ans.bad}</p>}
        {ans.byShift && (
          <Paper className="cd-rota-paper" title="The model’s rota (red: a broken rule · amber: differs from the cheapest rota)">
            <RotaGrid doc={doc} byShift={ans.byShift} flags={ans} compare={doc.best} />
            <ShiftKey doc={doc} />
          </Paper>
        )}
        {ans.shifts.length > 0 && (
          <ul className="cd-rules">
            {ans.shifts.slice(0, 5).map((s, i) => (
              <li key={i}>
                <Tick ok={false} /> {s.sid}: {s.why}
              </li>
            ))}
          </ul>
        )}
      </>
    );
  },
  Result: ({ doc, ans }) => {
    const items = (ans.outcome.detail.items ?? []) as Array<{ label: string; passed: boolean; detail?: string }>;
    const lines: Array<{ tone: Tone; text: ReactNode }> = [];
    if (ans.cost !== null && ans.cost > doc.optimum && ans.outcome.detail.verdict !== 'infeasible') lines.push({ tone: 'warn', text: <>{pence(ans.cost - doc.optimum)} more in wages this week than the cheapest legal rota.</> });
    if (ans.outcome.detail.verdict === 'infeasible') lines.push({ tone: 'bad', text: <>This rota could not be used: it breaks working-time or staffing rules (marked in red).</> });
    if (ans.stated !== null && ans.cost !== null && ans.stated !== ans.cost) lines.push({ tone: 'warn', text: <>Quoted the wage bill as {pence(ans.stated)}; the rota really costs {pence(ans.cost)}.</> });
    if (ans.byShift) {
      const dearest = doc.world.staff.map((p) => ({ p, d: personCost(doc.world, ans.byShift!, p.id) - personCost(doc.world, doc.best, p.id) })).sort((a, b) => b.d - a.d)[0];
      if (dearest && dearest.d > 0 && ans.cost !== doc.optimum) lines.push({ tone: 'warn', text: <>The biggest difference: {dearest.p.name.split(' ')[0]} costs {pence(dearest.d)} more than in the cheapest rota.</> });
    }
    return (
      <>
        <ul className="cd-rules">
          {items.map((it, i) => (
            <li key={i} className={it.passed ? 't-good' : 't-bad'}>
              <Tick ok={it.passed} /> {it.label}
              {!it.passed && it.detail && <small className="cd-why"> — {it.detail}</small>}
            </li>
          ))}
        </ul>
        <Impact lines={lines} />
      </>
    );
  },
  sentence: (doc, ans) => {
    if (ans.outcome.detail.verdict === 'infeasible' || !ans.byShift) return 'The rota breaks a rule (rest, under-18 hours, skills or cover), so it could not be used: it scores nothing.';
    const quote = ans.stated !== null && ans.cost !== null && ans.stated !== ans.cost ? ` But it quoted the wage bill as ${pence(ans.stated)}, so the score is halved.` : ans.stated === null ? ' But it quoted no wage total, so the score is halved.' : '';
    if (ans.cost === doc.optimum) return `A legal rota at the lowest possible wage bill (${pence(doc.optimum)}).${quote}`;
    return `A legal rota, but ${pence((ans.cost ?? 0) - doc.optimum)} dearer than the cheapest one.${quote}`;
  },
  headline: (doc, ans) => (ans.outcome.detail.verdict === 'infeasible' || !ans.byShift ? 'Breaks the rules' : ans.cost === doc.optimum ? 'Cheapest legal rota' : `Legal, ${pence((ans.cost ?? 0) - doc.optimum)} dearer`),
  alt: {
    question: (doc) => `A week's rota to fill for ${doc.place}: ${doc.world.staff.length} staff, ${doc.world.shifts.length} shifts.`,
    key: (doc) => `The cheapest legal rota costs ${pence(doc.optimum)}.`,
    answer: (_doc, ans) => `The model's rota: ${ans.cells.map((c) => `${c.sid} ${c.why}`).join('; ') || 'no broken cells'}; wage bill ${ans.cost !== null ? pence(ans.cost) : 'unknown'}.`,
  },
});

