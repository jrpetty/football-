/**
 * The Startup replay stage, a business dashboard: market-event banners, cash
 * and equity with the gap to the oracle, the month's four decisions as cards
 * (with the change from last month and the oracle's choice), demand against
 * what was sold / missed / left unsold, the competitor's price, and the cash
 * chart against the oracle and the autopilot.
 */
import type { StartupSimFrame, StartupSimWorld } from '../../types.ts';
import { cx } from '../ui.tsx';
import { SimFinaleCard } from './SimFinaleCard.tsx';
import { StartupChart } from './StartupChart.tsx';
import { money, signedMoney, startupFinale } from './simStory.ts';
import type { SimStageProps } from './SimStage.tsx';

interface Dec {
  price: number;
  produce: number;
  marketing: number;
  hire: number;
}

function Arrow({ now, prev, fmt, invert = false }: { now: number; prev: number | undefined; fmt: (n: number) => string; invert?: boolean }) {
  if (prev === undefined || Math.abs(now - prev) < 0.005) return <span className="dc-delta same">no change</span>;
  const up = now > prev;
  return (
    <span className={cx('dc-delta', up !== invert ? 'up' : 'down')}>
      {up ? '▲' : '▼'} {fmt(Math.abs(now - prev))}
    </span>
  );
}

function DecisionCard({ label, value, prev, oracle, fmt, sub, arrow = true }: { label: string; value: number; prev?: number; oracle?: number; fmt: (n: number) => string; sub?: string; arrow?: boolean }) {
  return (
    <div className="dec-card">
      <span className="dc-label">{label}</span>
      <b className="dc-value tnum">{fmt(value)}</b>
      {arrow && <Arrow now={value} prev={prev} fmt={fmt} />}
      {sub && <span className="dc-sub">{sub}</span>}
      {oracle !== undefined && <span className="dc-oracle">oracle: {fmt(oracle)}</span>}
    </div>
  );
}

const price = (n: number) => `$${n.toFixed(2)}`;
const units = (n: number) => `${Math.round(n).toLocaleString('en-US')}`;
const hireFmt = (n: number) => (n > 0 ? `+${n}` : n < 0 ? `−${-n}` : '0');

export function StartupReplay({ replay, idx, model, score, hideFinale }: SimStageProps) {
  const world = replay.sim as StartupSimWorld;
  const frames = replay.frames;
  const months: StartupSimFrame[] = [];
  for (let i = 0; i <= idx && i < frames.length; i++) {
    const s = frames[i]!.sim as StartupSimFrame | undefined;
    if (s?.kind === 'startup') months.push(s);
  }
  const s = months[months.length - 1];
  const prev = months[months.length - 2];
  const atEnd = idx >= frames.length - 1;
  const m = s?.month ?? 0;
  const prevDec: Dec | undefined = prev ? prev.decisions : s ? world.opening : undefined;
  const oracleDec = world.oraclePlan.find((p) => p.month === m);
  const orc = world.oracle.find((p) => p.month === m);
  const auto = world.autopilot.find((p) => p.month === m);
  const cash = s?.cash ?? world.cash0;
  const equity = s?.equity ?? world.cash0;
  const gap = orc ? equity - orc.equity : 0;
  const modelSeries = [{ month: 0, cash: world.cash0 }, ...months.map((x) => ({ month: x.month, cash: x.cash }))];
  const wanted = s ? Math.max(s.demand, s.sold + s.missed) : 0;
  const scaleMax = s ? Math.max(1, wanted, s.sold + s.inventory) : 1;
  const pct = (n: number) => `${(Math.max(0, n) / scaleMax) * 100}%`;

  return (
    <div className="sim-body sim-startup">
      {s && s.news.length > 0 && (
        <div className="su-news" key={`n${m}`}>
          {s.news.map((n) => (
            <span key={n.type} className={cx('su-banner', `n-${n.type}`)}>
              {n.text}
            </span>
          ))}
        </div>
      )}

      <div className="su-kpis">
        <div className="kpi">
          <span className="kpi-k">{s ? `Month ${s.month} of ${world.months} · ${s.calendar}` : 'Launch'}</span>
          <b className="kpi-v tnum">{money(cash)}</b>
          <span className="kpi-s">cash{s ? ` · month ${s.net >= 0 ? 'profit' : 'loss'} ${signedMoney(s.net)}` : ` · ${world.staff0} staff · selling ${world.product}s`}</span>
        </div>
        <div className="kpi">
          <span className="kpi-k">Equity (what the score counts)</span>
          <b className="kpi-v tnum">{money(equity)}</b>
          <span className="kpi-s">cash + unsold stock − loan</span>
        </div>
        <div className={cx('kpi gap', gap >= 0 ? 'ahead' : 'behind')}>
          <span className="kpi-k">vs the oracle, same month</span>
          <b className="kpi-v tnum">{orc ? signedMoney(gap) : '—'}</b>
          <span className="kpi-s">
            oracle {orc ? money(orc.equity) : '—'} · autopilot {auto ? money(auto.equity) : '—'}
          </span>
          {orc && (
            <span className="gap-bar" aria-hidden="true">
              <i style={{ width: `${Math.max(2, Math.min(100, (Math.max(0, equity) / Math.max(1, orc.equity, equity)) * 100))}%` }} />
            </span>
          )}
        </div>
      </div>

      <div className="su-grid">
        <div className="sim-panel">
          <div className="sp-h">{s ? `${model}’s decisions for ${s.calendar}` : 'The launch plan'}</div>
          <div className="dec-cards">
            <DecisionCard label="Price" value={s?.decisions.price ?? world.opening.price} prev={s ? prevDec?.price : undefined} oracle={oracleDec?.price} fmt={price} sub={s ? `rivals ~${price(s.competitor)}` : undefined} />
            <DecisionCard label="Production" value={s?.decisions.produce ?? world.opening.produce} prev={s ? prevDec?.produce : undefined} oracle={oracleDec?.produce} fmt={units} sub={s ? `capacity ${units(s.capacity)}` : undefined} />
            <DecisionCard label="Marketing" value={s?.decisions.marketing ?? world.opening.marketing} prev={s ? prevDec?.marketing : undefined} oracle={oracleDec?.marketing} fmt={money} sub={s ? `awareness ${s.awareness}%` : undefined} />
            <DecisionCard label="Hiring" value={s?.decisions.hire ?? 0} oracle={oracleDec?.hire} fmt={hireFmt} arrow={false} sub={`${s?.staff ?? world.staff0} staff${s?.decisions.loan ? ' · took loan' : ''}`} />
          </div>
          {s?.requested && (
            <p className="su-adjust">
              Asked for {units(s.requested.produce)} units / {money(s.requested.marketing)} marketing — cut to what cash and staff allowed.
            </p>
          )}
        </div>

        <div className="sim-panel">
          <div className="sp-h">Customers this month</div>
          {s ? (
            <>
              <div className="su-demand">
                <div className="sd-row">
                  <span className="sd-k">Wanted</span>
                  <span className="sd-track">
                    <i className="sd-wanted" style={{ width: pct(wanted) }} />
                  </span>
                  <b className="tnum">{units(wanted)}</b>
                </div>
                <div className="sd-row">
                  <span className="sd-k">Sold</span>
                  <span className="sd-track">
                    <i className="sd-sold" style={{ width: pct(s.sold) }} />
                    {s.missed > 0 && <i className="sd-missed" style={{ width: pct(s.missed) }} />}
                  </span>
                  <b className="tnum">{units(s.sold)}</b>
                </div>
                <div className="sd-row">
                  <span className="sd-k">Unsold stock</span>
                  <span className="sd-track">
                    <i className="sd-unsold" style={{ width: pct(s.inventory) }} />
                  </span>
                  <b className="tnum">{units(s.inventory)}</b>
                </div>
              </div>
              <div className="sd-legend">
                <span>
                  <i className="sd-sold" /> sold
                </span>
                <span>
                  <i className="sd-missed" /> turned away (sold out)
                </span>
                <span>
                  <i className="sd-unsold" /> left on the shelf
                </span>
              </div>
              <p className="su-verdict">
                {s.missed > 0
                  ? `Sold out: about ${Math.max(10, Math.round(s.missed / 10) * 10)} customers left empty-handed.`
                  : s.inventory > 0
                    ? `${units(s.inventory)} units left over, costing storage.`
                    : 'Sold exactly what was made.'}{' '}
                Price {price(s.decisions.price)} vs rivals ~{price(s.competitor)}.
              </p>
            </>
          ) : (
            <p className="muted sp-empty">No sales yet — the first month starts now.</p>
          )}
        </div>
      </div>

      {atEnd && !hideFinale && <SimFinaleCard className="su-finale" finale={startupFinale(world, frames)} score={score} />}
      <div className="su-bottom">
        <StartupChart world={world} model={modelSeries} month={m} modelLabel={model} />
      </div>
    </div>
  );
}
