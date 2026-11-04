/** The ranked leaderboard table: index with CI, category heatmap, medals, economics, speed, reliability. */
import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import type { CategoryInfo, Leaderboard, LeaderboardRow } from '../../types.ts';
import { fmtCost, fmtIndex, fmtMs, fmtPct, fmtRate, fmtScore100 } from '../../format.ts';
import { MedalsInline, ModelCell, SortHeader, cx } from '../ui.tsx';
import { errorRate, isBaseline, olympicCompare, shortCat } from './util.ts';
import { ScoreHint, ScoreScale } from '../clarity/Jargon.tsx';
import { copiedByHandText } from '../../../../src/manual-models/identity.ts';
import { useScoreName } from './scoreName.tsx';
import { RungMeter } from '../viz/DifficultyLadder.tsx';
import { ladderSpecOf } from '../../ladders.ts';

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
    case 'height':
      return row.ladderHeight ?? null;
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

/** Column groups folded away first when the table would not fit its width (until the viewer turns them on). */
const FOLD_ORDER: Group[] = ['reliability', 'speed'];

const DEFAULT_DIR: Record<string, 1 | -1> = { rank: 1, label: 1, cost: 1, cpp: 1, latency: 1, ttft: 1, reliability: 1 };

/** Opens the "Why these scores?" panel on this model (and category). */
export type ExplainHandler = (contestantId: string, categoryId: string | null) => void;

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

/** "4.2" for a mean over several ladders, "5" for one. */
export function fmtHeight(h: number | null | undefined, tests = 1): string {
  if (typeof h !== 'number') return '—';
  return tests > 1 && !Number.isInteger(h) ? h.toFixed(1) : String(Math.round(h * 10) / 10);
}

/** The ladder height cell: the mean height over this model's ladder tests, a ten-rung meter, and each test on hover. */
function HeightCell({ row, names }: { row: LeaderboardRow; names: Map<string, string> }) {
  const per = Object.values(row.tests ?? {}).filter((t) => t.ladder);
  const n = row.ladderTests ?? per.length;
  const title = per.length
    ? `${per.map((t) => `${names.get(t.testId) ?? t.testId}: level ${t.ladder!.height}${t.ladder!.onceSolved.length ? ` (once L${t.ladder!.onceSolved[t.ladder!.onceSolved.length - 1]})` : ''}`).join(' · ')}`
    : 'No ladder results';
  const borderline = per.some((t) => t.ladder!.borderline);
  return (
    <div className="height-cell" title={title}>
      <span className="height-value tnum">
        {typeof row.ladderHeight === 'number' ? <>L{fmtHeight(row.ladderHeight, n)}</> : '—'}
        {borderline && (
          <span className="height-warn" aria-label="includes a borderline rung">
            ~
          </span>
        )}
      </span>
      <RungMeter height={row.ladderHeight ?? null} color={row.color} label={title} />
      {n > 1 && <span className="height-sub">avg of {n}</span>}
    </div>
  );
}

const Row = memo(function Row({
  row,
  cats,
  show,
  maxCi,
  best,
  onSelect,
  selected,
  onExplain,
  heights,
}: {
  row: LeaderboardRow;
  cats: CategoryInfo[];
  show: Record<Group, boolean>;
  maxCi: number;
  best: Record<string, number>;
  onSelect?: (id: string) => void;
  selected?: boolean;
  onExplain?: ExplainHandler;
  /** Ladder boards: test id → name, for the Height column (absent: no column). */
  heights?: Map<string, string>;
}) {
  const baseline = isBaseline(row);
  const err = errorRate(row);
  const scoreName = useScoreName();
  return (
    <tr className={cx(baseline && 'is-baseline', onSelect && 'clickable', selected && 'selected')} onClick={onSelect ? () => onSelect(row.contestantId) : undefined}>
      <td className="c-rank sticky-1">
        <span className={cx('rank', !baseline && row.rank <= 3 && `r${row.rank}`)}>{baseline ? '–' : row.rank}</span>
      </td>
      <td className="c-model sticky-2">
        <ModelCell
          baseline={baseline}
          label={row.label}
          vendor={baseline ? 'Not an AI · pure guessing, for reference' : row.vendor}
          color={row.color}
          tag={
            row.manual ? (
              <span
                className="badge info manual-tag"
                title={`${row.manualModel ? `${copiedByHandText(row.manualModel)}. ` : ''}Replies pasted in by hand — speed is human time and cost is user-entered, so they are not comparable`}
              >
                {row.manualModel ? 'hand-copied' : 'manual'}
              </span>
            ) : undefined
          }
        />
      </td>
      <td className="c-index">
        {onExplain ? (
          <button
            type="button"
            className="why-cell"
            title={`Why ${fmtIndex(row.index)}? See how ${row.label}'s ${scoreName} was worked out`}
            onClick={(e) => {
              e.stopPropagation();
              onExplain(row.contestantId, null);
            }}
          >
            <IndexCell row={row} maxCi={maxCi} />
          </button>
        ) : (
          <IndexCell row={row} maxCi={maxCi} />
        )}
      </td>
      {heights && (
        <td className="c-height">
          <HeightCell row={row} names={heights} />
        </td>
      )}
      {show.categories &&
        cats.map((c) => {
          const v = row.categoryScores?.[c.id];
          const isBest = typeof v === 'number' && !baseline && best[c.id] === v;
          return (
            <td key={c.id} className="c-heat num" title={`${c.name}: ${typeof v === 'number' ? fmtPct(v, 1) : 'no score'}${onExplain ? ' — click to see why' : ''}`}>
              {onExplain ? (
                <button
                  type="button"
                  className={cx('heat', 'why-cell', isBest && 'best')}
                  style={heatStyle(v, c)}
                  aria-label={`Why did ${row.label} score ${fmtScore100(v)} in ${c.name}?`}
                  onClick={(e) => {
                    e.stopPropagation();
                    onExplain(row.contestantId, c.id);
                  }}
                >
                  {fmtScore100(v)}
                </button>
              ) : (
                <span className={cx('heat', isBest && 'best')} style={heatStyle(v, c)}>
                  {fmtScore100(v)}
                </span>
              )}
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
  onExplain,
}: {
  lb: Leaderboard;
  compact?: boolean;
  onSelect?: (id: string) => void;
  selectedId?: string | null;
  onExplain?: ExplainHandler;
}) {
  const [sort, setSort] = useState<{ key: SortKey; dir: 1 | -1 }>({ key: 'rank', dir: 1 });
  const scoreName = useScoreName();
  // Every group is on until the viewer chooses; groups the width can't hold fold away (lowest priority first).
  const [chosen, setChosen] = useState<Record<Group, boolean> | null>(null);
  const [folded, setFolded] = useState(0);
  const wrapRef = useRef<HTMLDivElement>(null);
  const autoHidden = new Set<Group>(chosen ? [] : FOLD_ORDER.slice(0, folded));
  const show: Record<Group, boolean> = chosen ?? { categories: true, economics: true, speed: !autoHidden.has('speed'), reliability: !autoHidden.has('reliability') };
  const toggle = (g: Group) => setChosen({ ...show, [g]: !show[g] });
  useLayoutEffect(() => {
    const el = wrapRef.current;
    if (!el || chosen || folded >= FOLD_ORDER.length) return;
    if (el.scrollWidth > el.clientWidth + 1) setFolded((n) => n + 1);
  });
  useEffect(() => {
    const el = wrapRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    let w = el.clientWidth;
    const ro = new ResizeObserver(() => {
      if (Math.abs(el.clientWidth - w) < 8) return;
      w = el.clientWidth;
      setFolded(0); // re-fit from the full set at the new width
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

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
  // Difficulty-ladder boards lead with the ladder height (src/core/ladders.ts), next to the headline score.
  const heights = useMemo(() => {
    const ladderTests = (lb.tests ?? []).filter((t) => ladderSpecOf(t.id));
    if (!ladderTests.length || !lb.rows.some((r) => typeof r.ladderHeight === 'number')) return undefined;
    return new Map(ladderTests.map((t) => [t.id, t.name]));
  }, [lb]);

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
            <button key={g.id} type="button" className={cx('toggle-chip', show[g.id] && 'on')} aria-pressed={show[g.id]} onClick={() => toggle(g.id)}>
              {g.label}
            </button>
          ))}
        </div>
        {autoHidden.size > 0 && (
          <span className="lb-folded" role="note">
            {GROUPS.filter((g) => autoHidden.has(g.id)).map((g) => g.label).join(' and ')} folded away to fit · turn {autoHidden.size === 1 ? 'it' : 'them'} on to see more
          </span>
        )}
        <span className="spacer" />
        <span className="muted" style={{ fontSize: '0.8rem' }}>
          Category cells: average test score out of 100 · <span className="best-key">outlined</span> = category leader{onExplain ? ' · click any score to see why' : ''}
        </span>
      </div>
      <ScoreScale className="lb-scale" what="full marks on every test in every category" baseline={lb.rows.find(isBaseline)?.index} />
      <div className="table-wrap scroll-shadow" ref={wrapRef}>
        <table className="table lb">
          <thead>
            <tr>
              <th className="c-rank sticky-1">{S('rank', '#', `Rank by ${scoreName}`)}</th>
              <th className="c-model sticky-2">{S('label', 'Model')}</th>
              <th className="c-index">{S('index', scoreName, 'Weighted mean of category scores × 100, with 95% bootstrap CI')}
                <ScoreHint text="The overall score out of 100: the average of the category scores. 100 would mean full marks on every test. The thin bracket shows how much the score could move by chance." />
              </th>
              {heights && (
                <th className="c-height">
                  {S('height', 'Height', 'Ladder height: the highest level passed reliably with every level below it (mean over the ladder tests)')}
                  <ScoreHint text="How high up the ten-rung difficulty ladders the model climbs: the highest level it passed reliably with every level below it. With several ladders, the average. ~ means a rung in the climb was within the noise." />
                </th>
              )}
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
                  <th className="num g-cpp">{S('cpp', '$/pt', `USD per ${scoreName} point (lower is better)`)}</th>
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
              <Row key={r.contestantId} row={r} cats={cats} show={show} maxCi={maxCi} best={best} onSelect={onSelect} selected={selectedId === r.contestantId} onExplain={onExplain} heights={heights} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
