/** The ranked leaderboard table: index with CI, category heatmap, medals, economics, speed, reliability. */
import { memo, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import type { CategoryInfo, Leaderboard, LeaderboardRow } from '../../types.ts';
import { fmtCost, fmtIndex, fmtMs, fmtPct, fmtRate, fmtScore100 } from '../../format.ts';
import { MedalsInline, ModelCell, SortHeader, cx } from '../ui.tsx';
import { errorRate, isBaseline, olympicCompare, shortCat } from './util.ts';

type Group = 'categories' | 'economics' | 'speed' | 'reliability';
type SortKey = string;

const GROUPS: Array<{ id: Group; label: string }> = [
  { id: 'categories', label: 'Categories' },
  { id: 'economics', label: 'Cost' },
  { id: 'speed', label: 'Speed' },
  { id: 'reliability', label: 'Reliability' },
];

function sortValue(row: LeaderboardRow, key: SortKey): number | string | null {
  switch (key) {
    case 'rank':
      return isBaseline(row) ? 1e9 : row.rank;
    case 'label':
      return row.label.toLowerCase();
    case 'index':
      return row.index;
    case 'cost':
      return row.totals?.costUsd ?? null;
    case 'cpp':
      return row.costPerPoint;
    case 'latency':
      return row.manual ? null : row.speed?.medianCaseMs ?? null;
    case 'ttft':
      return row.manual ? null : row.speed?.medianTtftMs ?? null;
    case 'tps':
      return row.manual ? null : row.speed?.outputTokensPerSec ?? null;
    case 'coverage':
      return row.coverage;
    case 'reliability':
      return errorRate(row);
    default:
      if (key.startsWith('cat:')) return row.categoryScores?.[key.slice(4)] ?? null;
      return null;
  }
}

const DEFAULT_DIR: Record<string, 1 | -1> = { rank: 1, label: 1, cost: 1, cpp: 1, latency: 1, ttft: 1, reliability: 1 };

function IndexCell({ row, maxCi }: { row: LeaderboardRow; maxCi: number }) {
  const v = row.index;
  const ci = row.indexCi95;
  const scale = (x: number) => `${Math.max(0, Math.min(100, (x / maxCi) * 100))}%`;
  return (
    <div className="index-cell" title={ci ? `95% CI ${fmtIndex(ci[0])} – ${fmtIndex(ci[1])}` : 'No confidence interval'}>
      <span className="index-value">{fmtIndex(v)}</span>
      <div className="index-track" aria-hidden="true">
        {typeof v === 'number' && <div className="index-fill" style={{ width: scale(v), background: row.color }} />}
        {ci && (
          <div className="index-ci" style={{ left: scale(ci[0]), width: `calc(${scale(ci[1])} - ${scale(ci[0])})` }}>
            <i />
          </div>
        )}
      </div>
      {ci && <span className="index-ci-text">±{fmtIndex((ci[1] - ci[0]) / 2)}</span>}
    </div>
  );
}

function heatStyle(score: number | null | undefined, cat: CategoryInfo): CSSProperties | undefined {
  if (typeof score !== 'number' || !Number.isFinite(score)) return undefined;
  const pct = Math.round(5 + Math.max(0, Math.min(1, score)) * 40);
  return { background: `color-mix(in srgb, ${cat.color} ${pct}%, transparent)` };
}

const Row = memo(function Row({
  row,
  cats,
  show,
  maxCi,
  best,
  onSelect,
  selected,
}: {
  row: LeaderboardRow;
  cats: CategoryInfo[];
  show: Record<Group, boolean>;
  maxCi: number;
  best: Record<string, number>;
  onSelect?: (id: string) => void;
  selected?: boolean;
}) {
  const baseline = isBaseline(row);
  const err = errorRate(row);
  return (
    <tr className={cx(baseline && 'is-baseline', onSelect && 'clickable', selected && 'selected')} onClick={onSelect ? () => onSelect(row.contestantId) : undefined}>
      <td className="c-rank sticky-1">
        <span className={cx('rank', !baseline && row.rank <= 3 && `r${row.rank}`)}>{baseline ? '–' : row.rank}</span>
      </td>
      <td className="c-model sticky-2">
        <ModelCell
          label={row.label}
          vendor={baseline ? 'Reference · random answers' : row.vendor}
          color={row.color}
          tag={
            row.manual ? (
              <span className="badge info manual-tag" title="Replies pasted in by hand — speed is human time and cost is user-entered, so they are not comparable">
                manual
              </span>
            ) : undefined
          }
        />
      </td>
      <td className="c-index">
        <IndexCell row={row} maxCi={maxCi} />
      </td>
      {show.categories &&
        cats.map((c) => {
          const v = row.categoryScores?.[c.id];
          const isBest = typeof v === 'number' && !baseline && best[c.id] === v;
          return (
            <td key={c.id} className="c-heat num" title={`${c.name}: ${typeof v === 'number' ? fmtPct(v, 1) : 'no score'}`}>
              <span className={cx('heat', isBest && 'best')} style={heatStyle(v, c)}>
                {fmtScore100(v)}
              </span>
            </td>
          );
        })}
      <td className="c-medals">
        <MedalsInline medals={row.medals} />
      </td>
      {show.economics && (
        <>
          <td className="num" title={row.manual ? 'User-entered cost (manual model)' : undefined}>
            {fmtCost(row.totals?.costUsd)}
            {row.manual ? <span className="muted">*</span> : null}
          </td>
          <td className="num g-cpp">{fmtCost(row.costPerPoint)}</td>
        </>
      )}
      {show.speed && (
        <>
          {row.manual ? (
            <>
              {[0, 1, 2].map((i) => (
                <td key={i} className="num g-speed not-comparable" title="Human-entered, not comparable">
                  —
                </td>
              ))}
            </>
          ) : (
            <>
              <td className="num g-speed">{fmtMs(row.speed?.medianCaseMs)}</td>
              <td className="num g-speed">{fmtMs(row.speed?.medianTtftMs)}</td>
              <td className="num g-speed">{fmtRate(row.speed?.outputTokensPerSec)}</td>
            </>
          )}
        </>
      )}
      {show.reliability && (
        <>
          <td className="num g-rel">
            <span className={cx(row.coverage < 0.999 && 'warn-text')}>{fmtPct(row.coverage)}</span>
          </td>
          <td className="num g-rel" title={`Errors ${fmtPct(row.reliability?.errorRate, 1)} · refusals ${fmtPct(row.reliability?.refusalRate, 1)}${row.reliability?.formatCompliance != null ? ` · format ${fmtPct(row.reliability.formatCompliance)}` : ''}`}>
            <span className={cx('rel', err >= 0.05 ? 'bad' : err > 0 ? 'mid' : 'good')}>
              <i aria-hidden="true" />
              {fmtPct(err, 1)}
            </span>
          </td>
        </>
      )}
    </tr>
  );
});

export function LeaderboardTable({
  lb,
  compact,
  onSelect,
  selectedId,
}: {
  lb: Leaderboard;
  compact?: boolean;
  onSelect?: (id: string) => void;
  selectedId?: string | null;
}) {
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'rank', dir: 1 });
  const [show, setShow] = useState<Record<Group, boolean>>({ categories: true, economics: true, speed: true, reliability: true });

  const cats = useMemo(() => {
    const used = new Set<string>();
    for (const r of lb.rows) for (const [k, v] of Object.entries(r.categoryScores ?? {})) if (v !== null && v !== undefined) used.add(k);
    for (const t of lb.tests ?? []) used.add(t.category);
    return (lb.categories ?? []).filter((c) => used.has(c.id));
  }, [lb]);

  const best = useMemo(() => {
    const out: Record<string, number> = {};
    for (const c of cats) {
      let m = -Infinity;
      for (const r of lb.rows) {
        if (isBaseline(r)) continue;
        const v = r.categoryScores?.[c.id];
        if (typeof v === 'number' && v > m) m = v;
      }
      if (m > -Infinity) out[c.id] = m;
    }
    return out;
  }, [cats, lb.rows]);

  const maxCi = 100;

  const rows = useMemo(() => {
    const list = [...(lb.rows ?? [])];
    if (sort.key === 'medals') {
      list.sort((a, b) => olympicCompare(a.medals, b.medals) * sort.dir);
      return list;
    }
    list.sort((a, b) => {
      const va = sortValue(a, sort.key);
      const vb = sortValue(b, sort.key);
      if (va === null && vb === null) return 0;
      if (va === null) return 1;
      if (vb === null) return -1;
      if (typeof va === 'string' || typeof vb === 'string') return String(va).localeCompare(String(vb)) * sort.dir;
      return (va - vb) * sort.dir;
    });
    return list;
  }, [lb.rows, sort]);

  const onSort = (key: SortKey) => setSort((s) => (s.key === key ? { key, dir: s.dir === 1 ? -1 : 1 } : { key, dir: DEFAULT_DIR[key] ?? -1 }));
  const S = (k: SortKey, label: string, title?: string) => (
    <SortHeader k={k} sort={sort} onSort={onSort} title={title}>
      {label}
    </SortHeader>
  );

  if (!lb.rows?.length) return null;

  return (
    <div className={cx('lb-table', compact && 'compact')}>
      <div className="lb-toolbar no-broadcast">
        <span className="eyebrow">Columns</span>
        <div className="chip-list">
          {GROUPS.map((g) => (
            <button key={g.id} type="button" className={cx('toggle-chip', show[g.id] && 'on')} aria-pressed={show[g.id]} onClick={() => setShow((s) => ({ ...s, [g.id]: !s[g.id] }))}>
              {g.label}
            </button>
          ))}
        </div>
        <span className="spacer" />
        <span className="muted" style={{ fontSize: '0.8rem' }}>
          Category cells: mean score ×100 · tinted by category · <span className="best-key">outlined</span> = category leader
        </span>
      </div>
      <div className="table-wrap scroll-shadow">
        <table className="table lb">
          <thead>
            <tr>
              <th className="c-rank sticky-1">{S('rank', '#', 'Rank by Gauntlet Index')}</th>
              <th className="c-model sticky-2">{S('label', 'Model')}</th>
              <th className="c-index">{S('index', 'Gauntlet Index', 'Weighted mean of category scores × 100, with 95% bootstrap CI')}</th>
              {show.categories &&
                cats.map((c) => (
                  <th key={c.id} className="c-heat num" title={`${c.name} (weight ${lb.categoryWeights?.[c.id] ?? c.weight})`}>
                    <span className="cat-head" style={{ ['--cat' as string]: c.color }}>
                      {S(`cat:${c.id}`, shortCat(c.id, c.name), `${c.name} — sort`)}
                    </span>
                  </th>
                ))}
              <th className="c-medals">{S('medals', 'Medals', 'Per-test gold / silver / bronze, sorted Olympic-style')}</th>
              {show.economics && (
                <>
                  <th className="num">{S('cost', 'Cost', 'Total contestant spend (judge cost excluded)')}</th>
                  <th className="num g-cpp">{S('cpp', '$/pt', 'USD per Gauntlet Index point (lower is better)')}</th>
                </>
              )}
              {show.speed && (
                <>
                  <th className="num g-speed">{S('latency', 'Latency', 'Median wall time per case')}</th>
                  <th className="num g-speed">{S('ttft', 'TTFT', 'Median time to first token')}</th>
                  <th className="num g-speed">{S('tps', 'Tok/s', 'Output tokens per second')}</th>
                </>
              )}
              {show.reliability && (
                <>
                  <th className="num g-rel">{S('coverage', 'Coverage', 'Share of suite tests with at least one scored result')}</th>
                  <th className="num g-rel">{S('reliability', 'Err/Ref', 'Error + refusal rate (lower is better)')}</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <Row key={r.contestantId} row={r} cats={cats} show={show} maxCi={maxCi} best={best} onSelect={onSelect} selected={selectedId === r.contestantId} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
