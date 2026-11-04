/**
 * "How high each model climbs": the leaderboard panel of a ladder board (Horizon, Frontier Ladders, Business
 * Ladders, or any run with ladder tests). One row per model, one column per ladder test, each cell the height
 * reached (src/core/ladders.ts) with a ten-rung meter; ladders still being built show as "coming soon".
 */
import { useMemo, type CSSProperties } from 'react';
import type { Leaderboard } from '../../types.ts';
import { Link, pathOf } from '../../router.tsx';
import { cx } from '../ui.tsx';
import { RungMeter } from '../viz/DifficultyLadder.tsx';
import { ladderSpecOf, passMarkText } from '../../ladders.ts';
import { isBaseline } from './util.ts';
import { fmtHeight } from './LeaderboardTable.tsx';

/** "Atlas climbs highest: 6.4 rungs on average, 1.2 more than Kite." */
export function meanHeightsNote(rows: Array<{ label: string; h: number }>, n: number): string | null {
  const s = rows.slice().sort((a, b) => b.h - a.h);
  if (!s.length) return null;
  const top = s[0]!;
  const what = n > 1 ? `${fmtHeight(top.h, n)} rungs on average across ${n} ladders` : `level ${fmtHeight(top.h)}`;
  if (s.length === 1) return `${top.label} climbs to ${what}.`;
  const gap = Math.round((top.h - s[1]!.h) * 10) / 10;
  if (gap <= 0) return `${top.label} and ${s[1]!.label} climb equally high: ${what}.`;
  return `${top.label} climbs highest: ${what}, ${fmtHeight(gap, 2)} rung${gap === 1 ? '' : 's'} above ${s[1]!.label}.`;
}

export function LadderHeightsPanel({ lb, planned = [], demo }: { lb: Leaderboard; planned?: string[]; demo?: boolean }) {
  const cols = useMemo(() => {
    const built = (lb.tests ?? []).filter((t) => ladderSpecOf(t.id)).map((t) => ({ id: t.id, name: t.name, soon: false }));
    const soon = planned.filter((id) => !built.some((b) => b.id === id)).map((id) => ({ id, name: ladderSpecOf(id)?.name ?? id, soon: true }));
    return [...built, ...soon];
  }, [lb.tests, planned]);
  const rows = useMemo(
    () => [...(lb.rows ?? [])].sort((a, b) => Number(isBaseline(a)) - Number(isBaseline(b)) || (b.ladderHeight ?? -1) - (a.ladderHeight ?? -1) || (a.rank ?? 99) - (b.rank ?? 99)),
    [lb.rows],
  );
  if (!cols.length) return null;
  const builtN = cols.filter((c) => !c.soon).length;
  const note = meanHeightsNote(
    rows.filter((r) => !isBaseline(r) && typeof r.ladderHeight === 'number').map((r) => ({ label: r.label, h: r.ladderHeight! })),
    builtN,
  );
  const passMarks = [...new Set(cols.map((c) => ladderSpecOf(c.id)?.passMark ?? 1))];
  return (
    <section className="card lh-card" aria-label="Ladder heights">
      <div className="card-head">
        <div className="t">
          <span className="dl-eyebrow">Difficulty ladders{demo ? ' · demo heights' : ''}</span>
          <h2>How high each model climbs</h2>
          <div className="desc">
            Each ladder has ten levels, each harder than the last. The height is the highest level passed reliably with every level below it. {passMarks.map(passMarkText).join(' · ')}.
          </div>
        </div>
      </div>
      <div className="card-body">
        <div className="table-wrap scroll-shadow">
          <table className="table lh-table">
            <thead>
              <tr>
                <th className="lh-model">Model</th>
                {cols.map((c) => (
                  <th key={c.id} className={cx('lh-test', c.soon && 'soon')}>
                    {c.soon ? (
                      <span title={ladderSpecOf(c.id)?.grows}>{c.name}</span>
                    ) : (
                      <Link to={pathOf('tests', c.id)} title={ladderSpecOf(c.id)?.grows}>
                        {c.name}
                      </Link>
                    )}
                    {c.soon && <small className="lh-soon">coming soon</small>}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.contestantId} className={cx(isBaseline(r) && 'is-baseline')} style={{ ['--c' as string]: r.color } as CSSProperties}>
                  <td className="lh-model">
                    <span className="lh-who">
                      <i className="dl-sw" aria-hidden="true" />
                      {isBaseline(r) ? 'Random guessing' : r.label}
                    </span>
                  </td>
                  {cols.map((c) => {
                    const cell = r.tests?.[c.id]?.ladder;
                    if (c.soon && !cell) return <td key={c.id} className="lh-cell soon" aria-label="coming soon" />;
                    if (!cell) return <td key={c.id} className="lh-cell muted">—</td>;
                    const once = cell.onceSolved[cell.onceSolved.length - 1];
                    return (
                      <td key={c.id} className="lh-cell" title={`${r.label} · ${c.name}: level ${cell.height} of ${cell.levels}${once ? ` · once solved L${once}` : ''}${cell.borderline ? ' · borderline' : ''}${cell.justShort ? ` · just short of L${cell.height + 1}` : ''}`}>
                        <span className="lh-h tnum">
                          L{cell.height}
                          {cell.borderline && <span className="height-warn">~</span>}
                        </span>
                        <RungMeter height={cell.height} levels={cell.levels} onceSolved={cell.onceSolved} color={r.color} />
                        {once ? <small className="lh-once">once L{once}</small> : null}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {note && <p className="dl-note lh-note">{note}</p>}
        <p className="lh-key muted">
          <RungMeter height={4} onceSolved={[6]} color="var(--accent)" label="example meter" /> filled = climbed · outlined = passed once higher up, which does not count · ~ = a rung in the climb was within the noise
        </p>
      </div>
    </section>
  );
}
