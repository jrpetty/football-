/** Leaderboard companion panels: medal table, podium, speed, category comparison. */
import { useMemo, useState } from 'react';
import type { Leaderboard, LeaderboardRow } from '../../types.ts';
import { fmtCost, fmtIndex, fmtMs, fmtRate, fmtScore100 } from '../../format.ts';
import { Medal, ModelCell, Seg, cx } from '../ui.tsx';
import { ChartCard } from '../charts/ChartCard.tsx';
import { BarList } from '../charts/BarList.tsx';
import { Radar } from '../charts/Radar.tsx';
import { ScoreCostScatter } from '../charts/ScoreCostScatter.tsx';
import type { CostMode } from '../charts/ScoreCostScatter.tsx';
import { costOf } from '../charts/ScoreCostScatter.tsx';
import { isBaseline, olympicCompare, shortCat } from './util.ts';

// ───────────────────────────── Medal table ─────────────────────────────

export function MedalTable({ lb }: { lb: Leaderboard }) {
  const testName = useMemo(() => new Map((lb.tests ?? []).map((t) => [t.id, t.name])), [lb.tests]);
  const wins = useMemo(() => {
    const m = new Map<string, { gold: string[]; silver: string[]; bronze: string[] }>();
    const get = (id: string) => {
      let v = m.get(id);
      if (!v) m.set(id, (v = { gold: [], silver: [], bronze: [] }));
      return v;
    };
    for (const e of lb.medals ?? []) {
      const n = testName.get(e.testId) ?? e.testId;
      if (e.gold) get(e.gold).gold.push(n);
      if (e.silver) get(e.silver).silver.push(n);
      if (e.bronze) get(e.bronze).bronze.push(n);
    }
    return m;
  }, [lb.medals, testName]);

  const rows = useMemo(() => {
    const list = (lb.rows ?? []).filter((r) => !isBaseline(r)).map((r) => ({ row: r, m: r.medals ?? { gold: 0, silver: 0, bronze: 0 } }));
    list.sort((a, b) => olympicCompare(a.m, b.m) || (a.row.rank ?? 0) - (b.row.rank ?? 0));
    let rank = 0;
    let prev: (typeof list)[number] | null = null;
    return list.map((x, i) => {
      if (!prev || olympicCompare(prev.m, x.m) !== 0) rank = i + 1;
      prev = x;
      return { ...x, rank };
    });
  }, [lb.rows]);

  const events = (lb.medals ?? []).filter((e) => e.gold || e.silver || e.bronze).length;

  return (
    <ChartCard title="Medal table" desc={`Per-test podiums across ${events} test${events === 1 ? '' : 's'} · sorted Olympic-style (gold, then silver, then bronze)`}>
      {rows.length === 0 ? (
        <div className="chart-empty">No medals awarded yet.</div>
      ) : (
        <div className="table-wrap">
          <table className="table compact medal-table">
            <thead>
              <tr>
                <th className="center">#</th>
                <th>Model</th>
                <th className="center">
                  <Medal kind="gold" />
                </th>
                <th className="center">
                  <Medal kind="silver" />
                </th>
                <th className="center">
                  <Medal kind="bronze" />
                </th>
                <th className="num">Total</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ row, m, rank }) => {
                const w = wins.get(row.contestantId);
                const tip = (arr?: string[]) => (arr && arr.length ? arr.join('\n') : undefined);
                return (
                  <tr key={row.contestantId}>
                    <td className="center tnum muted">{rank}</td>
                    <td>
                      <ModelCell label={row.label} vendor={row.vendor} color={row.color} />
                    </td>
                    <td className={cx('center medal-count', !m.gold && 'zero')} title={tip(w?.gold)}>
                      {m.gold}
                    </td>
                    <td className={cx('center medal-count', !m.silver && 'zero')} title={tip(w?.silver)}>
                      {m.silver}
                    </td>
                    <td className={cx('center medal-count', !m.bronze && 'zero')} title={tip(w?.bronze)}>
                      {m.bronze}
                    </td>
                    <td className="num" style={{ fontWeight: 700 }}>
                      {m.gold + m.silver + m.bronze}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </ChartCard>
  );
}

// ───────────────────────────── Podium ─────────────────────────────

export function Podium({ rows, big }: { rows: LeaderboardRow[]; big?: boolean }) {
  const top = rows
    .filter((r) => !isBaseline(r) && typeof r.index === 'number')
    .sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99))
    .slice(0, 3);
  if (!top.length) return null;
  const order = [top[1], top[0], top[2]].filter(Boolean) as LeaderboardRow[];
  const kinds = ['gold', 'silver', 'bronze'] as const;
  return (
    <div className={cx('podium', big && 'big')} role="list" aria-label="Top three">
      {order.map((r) => {
        const place = top.indexOf(r);
        return (
          <div key={r.contestantId} className={cx('podium-step', `p${place + 1}`)} role="listitem" style={{ ['--c' as string]: r.color }}>
            <div className="podium-medal">
              <Medal kind={kinds[place]} lg />
            </div>
            <div className="podium-name" title={r.label}>
              {r.label}
            </div>
            <div className="podium-vendor">{r.vendor}</div>
            <div className="podium-index">{fmtIndex(r.index)}</div>
            <div className="podium-ci">{r.indexCi95 ? `95% CI ${fmtIndex(r.indexCi95[0])}–${fmtIndex(r.indexCi95[1])}` : 'Gauntlet Index'}</div>
            <div className="podium-bar" />
          </div>
        );
      })}
    </div>
  );
}

// ───────────────────────────── Score vs cost ─────────────────────────────

export function ScatterPanel({ lb, tall }: { lb: Leaderboard; tall?: boolean }) {
  const [mode, setMode] = useState<CostMode>('total');
  const plotted = lb.rows.filter((r) => !isBaseline(r));
  return (
    <ChartCard
      title="Score vs cost"
      desc="Gauntlet Index against spend (log scale). The frontier links models nobody beats on both price and score."
      controls={
        <Seg
          small
          label="Cost basis"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'total', label: 'Total' },
            { value: 'perCase', label: 'Per case' },
          ]}
        />
      }
      table={
        <table className="table compact">
          <thead>
            <tr>
              <th>Model</th>
              <th className="num">Index</th>
              <th className="num">95% CI</th>
              <th className="num">{mode === 'total' ? 'Total cost' : 'Cost / case'}</th>
              <th className="num">$/pt</th>
            </tr>
          </thead>
          <tbody>
            {plotted.map((r) => (
              <tr key={r.contestantId}>
                <td>
                  <ModelCell label={r.label} color={r.color} />
                </td>
                <td className="num">{fmtIndex(r.index)}</td>
                <td className="num">{r.indexCi95 ? `${fmtIndex(r.indexCi95[0])}–${fmtIndex(r.indexCi95[1])}` : '—'}</td>
                <td className="num">{fmtCost(costOf(r, mode))}</td>
                <td className="num">{fmtCost(r.costPerPoint)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      <ScoreCostScatter rows={lb.rows} mode={mode} tall={tall} />
    </ChartCard>
  );
}

// ───────────────────────────── Speed ─────────────────────────────

export function SpeedPanel({ lb }: { lb: Leaderboard }) {
  const rows = lb.rows.filter((r) => !isBaseline(r));
  return (
    <ChartCard
      title="Speed"
      desc="Median time to first token (lower is faster) and visible output throughput."
      table={
        <table className="table compact">
          <thead>
            <tr>
              <th>Model</th>
              <th className="num">TTFT</th>
              <th className="num">Case time</th>
              <th className="num">Tok/s</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.contestantId}>
                <td>
                  <ModelCell label={r.label} color={r.color} />
                </td>
                <td className="num">{fmtMs(r.speed?.medianTtftMs)}</td>
                <td className="num">{fmtMs(r.speed?.medianCaseMs)}</td>
                <td className="num">{fmtRate(r.speed?.outputTokensPerSec)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      <div className="speed-grid">
        <div>
          <div className="mini-title">Time to first token · median</div>
          <BarList
            ariaLabel="Median time to first token"
            sort="asc"
            data={rows.map((r) => ({
              id: r.contestantId,
              label: r.label,
              color: r.color,
              value: r.speed?.medianTtftMs ?? null,
              display: fmtMs(r.speed?.medianTtftMs),
              detail: `Median case time ${fmtMs(r.speed?.medianCaseMs)}`,
            }))}
          />
        </div>
        <div>
          <div className="mini-title">Output speed · tokens / second</div>
          <BarList
            ariaLabel="Output tokens per second"
            sort="desc"
            data={rows.map((r) => ({
              id: r.contestantId,
              label: r.label,
              color: r.color,
              value: r.speed?.outputTokensPerSec ?? null,
              display: fmtRate(r.speed?.outputTokensPerSec),
            }))}
          />
        </div>
      </div>
    </ChartCard>
  );
}

// ───────────────────────────── Category comparison ─────────────────────────────

const MAX_RADAR = 3;

export function CategoryPanel({ lb }: { lb: Leaderboard }) {
  const contenders = useMemo(() => lb.rows.filter((r) => !isBaseline(r)).sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99)), [lb.rows]);
  const [mode, setMode] = useState<'radar' | 'multiples'>('radar');
  const [picked, setPicked] = useState<string[]>(() => contenders.slice(0, MAX_RADAR).map((r) => r.contestantId));

  const cats = useMemo(() => (lb.categories ?? []).filter((c) => lb.rows.some((r) => typeof r.categoryScores?.[c.id] === 'number')), [lb]);
  const selected = contenders.filter((r) => picked.includes(r.contestantId));

  const toggle = (id: string) =>
    setPicked((p) => {
      if (p.includes(id)) return p.filter((x) => x !== id);
      const next = [...p, id];
      return next.length > MAX_RADAR ? next.slice(next.length - MAX_RADAR) : next;
    });

  return (
    <ChartCard
      title="Category profile"
      desc={mode === 'radar' ? `Compare up to ${MAX_RADAR} models across every category (0–100).` : 'Every model, category by category (0–100).'}
      controls={
        <Seg
          small
          label="Chart type"
          value={mode}
          onChange={setMode}
          options={[
            { value: 'radar', label: 'Radar' },
            { value: 'multiples', label: 'By category' },
          ]}
        />
      }
      table={
        <table className="table compact">
          <thead>
            <tr>
              <th>Model</th>
              {cats.map((c) => (
                <th key={c.id} className="num" title={c.name}>
                  {shortCat(c.id, c.name)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {lb.rows.map((r) => (
              <tr key={r.contestantId}>
                <td>
                  <ModelCell label={r.label} color={r.color} />
                </td>
                {cats.map((c) => (
                  <td key={c.id} className="num">
                    {fmtScore100(r.categoryScores?.[c.id])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      }
    >
      {mode === 'radar' ? (
        <>
          <div className="picker-row no-broadcast" role="group" aria-label="Models to compare">
            {contenders.map((r) => (
              <button key={r.contestantId} type="button" className={cx('toggle-chip', picked.includes(r.contestantId) && 'on')} aria-pressed={picked.includes(r.contestantId)} onClick={() => toggle(r.contestantId)}>
                <span className="sw" style={{ background: r.color }} />
                {r.label}
              </button>
            ))}
          </div>
          <Radar
            categories={cats}
            series={selected.map((r) => ({ id: r.contestantId, label: r.label, color: r.color, scores: r.categoryScores ?? {} }))}
          />
          <div className="chart-legend">
            {selected.map((r) => (
              <span key={r.contestantId} className="lg-item">
                <span className="lg-line" style={{ background: r.color, height: 3 }} />
                {r.label}
              </span>
            ))}
          </div>
        </>
      ) : (
        <div className="multiples">
          {cats.map((c) => (
            <div key={c.id} className="multiple">
              <div className="mini-title">
                <span className="cat-dot" style={{ background: c.color }} />
                {c.name}
              </div>
              <BarList
                ariaLabel={`${c.name} scores`}
                max={1}
                data={contenders.map((r) => ({
                  id: r.contestantId,
                  label: r.label,
                  color: r.color,
                  value: r.categoryScores?.[c.id] ?? null,
                  display: fmtScore100(r.categoryScores?.[c.id]),
                }))}
              />
            </div>
          ))}
        </div>
      )}
    </ChartCard>
  );
}
