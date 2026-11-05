/**
 * Business ladders: a big JSON answer (up to about a thousand records) summarised instead of listed leaf by leaf —
 * points right per part of the answer, one square per record, and the first few wrong records as cards with the
 * model's value against the key's. Read from the scorer's own per-field verdicts.
 */
import type { CSSProperties } from 'react';
import { businessNoun, humanize, sectionLabel, type BusinessVisual } from '../../../../src/presenter/visuals/business.ts';
import { cx } from '../ui.tsx';
import { VizFrame, type VizMode } from './VizFrame.tsx';
import './closeup/closeup.css';

const pct = (x: number) => Math.round(x * 100);
const shown = (x: string | null) => (x === null ? '—' : x === 'null' ? 'nothing' : x);

export function BusinessAnswerVisual({ v, mode, score, testId }: { v: BusinessVisual; mode: VizMode; score: number | null; testId: string }) {
  const noun = businessNoun(testId);
  const bad = v.entities.filter((e) => e.wrong > 0).length;
  const counts = new Map<string, number>();
  for (const g of v.wrong) counts.set(g.section, (counts.get(g.section) ?? 0) + 1);
  const main = [...counts].sort((a, b) => b[1] - a[1])[0]?.[0];
  const share = score ?? (v.points ? v.earned / v.points : 0);
  const tone = !v.wrongFields ? 'good' : share >= 0.9 ? 'half' : 'bad';
  const headline = v.kind === 'sql' ? `${v.sections.filter((s) => s.wrong === 0).length} of ${v.sections.length} databases exactly right` : !v.wrongFields ? `Every field right across ${v.entities.length.toLocaleString('en-GB')} ${noun}` : `${bad.toLocaleString('en-GB')} of ${v.entities.length.toLocaleString('en-GB')} ${noun} with a mistake`;
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      className="lc-inline-root"
      eyebrow={v.kind === 'sql' ? 'One SQL query, run on four databases' : `The whole answer, summarised · ${v.fields.toLocaleString('en-GB')} fields checked`}
      headline={headline}
      big={`${pct(share)}%`}
      bigSub="of the points"
      legend={[
        { tone: 'good', label: 'Right' },
        { tone: 'half', label: 'Partly wrong' },
        { tone: 'bad', label: 'Wrong' },
      ]}
    >
      <div className="lc-biz-res">
        <div className="lc-k">{v.kind === 'sql' ? 'Rows right, database by database' : 'Points right, part by part'}</div>
        <div className="lc-secbars">
          {v.sections.map((s) => {
            const sh = s.points ? s.earned / s.points : 0;
            return (
              <div key={s.key} className={cx('lc-secbar', sh >= 0.9995 ? 'good' : sh >= 0.9 ? 'warn' : 'bad')}>
                <span className="lc-secbar-l">{s.label}</span>
                <span className="lc-secbar-track">
                  <i style={{ width: `${Math.max(1.5, sh * 100)}%` }} />
                </span>
                <b className="tnum">{pct(sh)}%</b>
              </div>
            );
          })}
        </div>
        {v.entities.length > 0 && (
          <div className="lc-heat-cells" aria-label={`${v.entities.length} ${noun}, ${bad} with a mistake`} role="img">
            {v.entities.map((e, i) => (
              <span key={`${e.section}|${e.id}`} className={cx('lc-hc', e.missing ? 'miss' : e.wrong === 0 ? 'good' : e.wrong === e.fields ? 'bad' : 'half')} title={e.id} style={{ ['--i' as string]: i } as CSSProperties} />
            ))}
          </div>
        )}
        {v.kind === 'json' && v.wrong.length > 0 && (
          <>
            <div className="lc-k">First wrong {noun} · given → correct</div>
            <ul className="lc-wrongs">
              {v.wrong.slice(0, 5).map((g) => (
                <li key={`${g.section}|${g.entity}`} className="lc-wrong">
                  <div className="lc-wrong-head">
                    <b>{g.entity || sectionLabel(g.section)}</b>
                    {g.entity && g.section !== main && <span className="muted">{sectionLabel(g.section)}</span>}
                  </div>
                  <table>
                    <tbody>
                      {g.fields.slice(0, 4).map((f, i) => (
                        <tr key={i}>
                          <th scope="row">{humanize(f.field)}</th>
                          {f.expected !== null || f.got !== null ? (
                            <>
                              <td className="lc-was-given">
                                <s>{shown(f.got)}</s>
                              </td>
                              <td className="lc-was-key">{shown(f.expected)}</td>
                            </>
                          ) : (
                            <td colSpan={2} className="lc-was-note">
                              {f.note ?? 'wrong'}
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </li>
              ))}
            </ul>
            {v.wrong.length > 5 && <div className="lc-more">+ {(v.wrong.length - 5).toLocaleString('en-GB')} more with mistakes</div>}
          </>
        )}
        {v.kind === 'sql' && v.wrong.length > 0 && (
          <ul className="lc-sqlwrong">
            {v.wrong.map((g) => (
              <li key={g.entity}>
                <b>{humanize(g.entity)}</b>: {g.fields[0]?.note}
              </li>
            ))}
          </ul>
        )}
      </div>
    </VizFrame>
  );
}
