/**
 * Check the Delivery (work.delivery-note): the purchase order, the supplier's delivery note(s) and our goods-in
 * check side by side, one row per ordered product, so the matching and mismatching lines read across; then the
 * count per line (received · damaged · short/over · substitute) with the model's figure against the correct one.
 */
import { useMemo } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, Fact, Impact, Stamp, Tick } from '../kit.tsx';
import { DN_FIELDS, deliveryAnswer, deliveryQuestion, deliverySentence, linesFor, type DeliveryQuestion, type DnAnswer, type DnField, type DnRow } from '../model/delivery.ts';
import '../ap/ap.css';
import './delivery.css';

const shownV = (v: unknown) => (v === undefined ? '—' : v === null ? 'none' : String(v));

function Cell({ r, f, show }: { r: DnRow; f: DnField; show: 'model' | 'key' }) {
  const want = r.want[f];
  const got = r.got?.[f];
  if (show === 'key' || !r.got) return <td className={cx('pm-dn-a', (want === 0 || want === null) && 'zero')}>{shownV(show === 'key' ? want : got)}</td>;
  const ok = r.ok[f];
  return (
    <td className={cx('pm-dn-a', ok ? 'ok' : 'no', ok && (want === 0 || want === null) && 'zero')}>
      {ok ? (
        shownV(got)
      ) : (
        <>
          <s>{shownV(got)}</s> <b>{shownV(want)}</b>
        </>
      )}
    </td>
  );
}

function rowStamp(r: DnRow, show: 'model' | 'key') {
  const v = show === 'key' ? r.want : (r.got ?? r.want);
  const tone = show === 'key' ? 'muted' : r.right ? 'good' : 'bad';
  const parts: string[] = [];
  if (Number(v.short) > 0) parts.push(`short ${v.short}`);
  if (Number(v.over) > 0) parts.push(`over ${v.over}`);
  if (Number(v.damaged) > 0) parts.push(`${v.damaged} damaged`);
  if (v.substitute) parts.push(`sub ${v.substitute}`);
  return (
    <Stamp tone={tone} small sub={parts.join(' · ') || undefined}>
      {parts.length ? 'Query' : 'OK'} {show === 'model' ? (r.right ? '✓' : '✕') : ''}
    </Stamp>
  );
}

export function DeliverySheet({ q, a, show }: { q: DeliveryQuestion; a: DnAnswer | null; show: 'none' | 'model' | 'key' }) {
  return (
    <div className="pm-scroll">
      <table className="pm-dn">
        <thead>
          <tr>
            <th className="h po" scope="col">
              <small>Our purchase order</small>
              <b>{q.poId}</b>
              <span>
                {q.poDate} · {q.orderedBy}
              </span>
            </th>
            <th className="h dn" scope="col">
              <small>Supplier’s delivery note{q.notes.length > 1 ? 's' : ''}</small>
              <b>{q.notes.map((n) => n.id).join(' + ')}</b>
              <span>{q.notes[0]?.head.match(/Delivered: (.+)$/)?.[1]}</span>
            </th>
            <th className="h gi" scope="col">
              <small>Our goods-in check</small>
              <b>{q.check.length ? `${q.check.length} remark${q.check.length > 1 ? 's' : ''}` : 'no remarks'}</b>
              <span>it wins where it differs</span>
            </th>
            {show !== 'none' && (
              <>
                {DN_FIELDS.map((f) => (
                  <th key={f} className="h ans" scope="col">
                    {f === 'substitute' ? 'Sub.' : f}
                  </th>
                ))}
                <th className="h ans" scope="col">
                  {show === 'model' ? 'Model' : 'Correct'}
                </th>
              </>
            )}
          </tr>
        </thead>
        <tbody>
          {q.po.map((it) => {
            const { dn, check } = linesFor(q, it);
            const r = a?.rows.find((x) => x.item.sku === it.sku);
            return (
              <tr key={it.sku} className={cx(show === 'model' && r && !r.right && 'bad')}>
                <td className="c po">
                  <b>{it.item}</b>
                  <span>
                    {it.sku} · {it.pack} · ordered <b>{it.ordered}</b>
                  </span>
                </td>
                <td className="c dn">
                  {dn.length ? (
                    dn.map((l, i) => (
                      <span key={i} className={cx(l.sku !== it.sku && 'sub', l.flag && 'flag')}>
                        {l.sku !== it.sku ? `${l.sku} ${l.item}: ` : ''}
                        <b>{l.qty}</b>
                        {l.flag ? ` · ${l.flag}` : ''}
                        {q.notes.length > 1 ? <em> ({l.note})</em> : null}
                      </span>
                    ))
                  ) : (
                    <span className="muted">not on the note</span>
                  )}
                </td>
                <td className={cx('c gi', check.length > 0 && 'has')}>{check.length ? check.map((c, i) => <span key={i}>{c}</span>) : <span className="muted">—</span>}</td>
                {show !== 'none' && r && (
                  <>
                    {DN_FIELDS.map((f) => (
                      <Cell key={f} r={r} f={f} show={show} />
                    ))}
                    <td className="pm-dn-stamp">{rowStamp(r, show)}</td>
                  </>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
      {(() => {
        const extra = q.notes.flatMap((n) => n.lines).filter((l) => !q.po.some((p) => p.sku === l.sku) && !(l.flag ?? '').includes('SUBSTITUTE'));
        if (!extra.length && !a?.extraGot.length) return null;
        return (
          <div className="pm-dn-extra">
            <span className="pm-k">Delivered but not ordered</span>
            {extra.map((l) => (
              <span key={l.sku} className="pm-chk info">
                {l.sku} {l.item}: {l.qty}
              </span>
            ))}
            {show === 'model' && a && (
              <span className={cx('pm-chk', a.extraOk ? 'ok' : 'no')}>
                <Tick ok={a.extraOk} /> model listed: {a.extraGot.length ? a.extraGot.join(', ') : 'nothing'}
              </span>
            )}
          </div>
        );
      })()}
    </div>
  );
}

function impactOf(a: DnAnswer) {
  if (!a.answered) return null;
  const wrong = a.rows.filter((r) => !r.right);
  if (!wrong.length && a.extraOk) return <Impact tone="good">The goods-in record is exactly right: the supplier can be chased for precisely what is short, damaged or substituted.</Impact>;
  const overReceived = wrong.reduce((s, r) => s + Math.max(0, Number(r.got?.received ?? 0) - Number(r.want.received ?? 0)), 0);
  const missedShort = wrong.filter((r) => Number(r.want.short) > Number(r.got?.short ?? 0));
  const missedDamage = wrong.filter((r) => Number(r.want.damaged) > Number(r.got?.damaged ?? 0));
  const bits: string[] = [];
  if (overReceived > 0) bits.push(`${overReceived} unit${overReceived > 1 ? 's' : ''} booked in that never arrived (they would be paid for)`);
  if (missedShort.length) bits.push(`${missedShort.reduce((s, r) => s + Number(r.want.short) - Number(r.got?.short ?? 0), 0)} units short that would not be claimed from the supplier`);
  if (missedDamage.length) bits.push(`${missedDamage.reduce((s, r) => s + Number(r.want.damaged) - Number(r.got?.damaged ?? 0), 0)} damaged units not reported`);
  return <Impact tone="bad">{bits.length ? `${bits.join('; ')}.` : `${wrong.length} line${wrong.length > 1 ? 's' : ''} recorded wrongly: the stock count would not match the shelves.`}</Impact>;
}

export function DeliveryBeats(p: KindProps) {
  const q = useMemo(() => deliveryQuestion(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyA = useMemo(() => (q ? deliveryAnswer(q, p.q) : null), [q, p.q]);
  const a = useMemo(() => (q && p.a ? deliveryAnswer(q, p.a) : null), [q, p.a]);
  if (!q || !keyA) return <p className="lc-empty">This delivery could not be drawn.</p>;
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={a ? deliverySentence(a) : null}
        impact={a ? impactOf(a) : null}
        question={{
          title: 'The question',
          sub: `${q.site}: what actually arrived against order ${q.poId}, counted in single units`,
          alt: `Purchase order ${q.poId} with ${q.po.length} lines (${q.po.map((x) => `${x.item}: ${x.ordered}`).join('; ')}), the supplier's delivery note and our goods-in remarks: ${q.check.join(' ') || 'none'}.`,
          node: <DeliverySheet q={q} a={null} show="none" />,
        }}
        keyBeat={{
          title: 'The correct count',
          sub: 'Per ordered line, in single units',
          alt: keyA.rows.map((r) => `${r.item.item}: received ${shownV(r.want.received)}, damaged ${shownV(r.want.damaged)}, short ${shownV(r.want.short)}, over ${shownV(r.want.over)}, substitute ${shownV(r.want.substitute)}`).join('. '),
          node: <DeliverySheet q={q} a={keyA} show="key" />,
        }}
        answer={
          a
            ? {
                title: 'The model’s answer',
                sub: a.answered ? 'Its count per line: struck-out figures are wrong, the correct one beside them' : 'No answer could be read from the reply',
                alt: a.rows.map((r) => `${r.item.item}: ${r.right ? 'right' : DN_FIELDS.filter((f) => !r.ok[f]).map((f) => `${f} ${shownV(r.got?.[f])} should be ${shownV(r.want[f])}`).join(', ')}`).join('. '),
                node: (
                  <div className="pm-pile">
                    <div className="pm-facts">
                      <Fact k="lines exactly right" v={`${a.rows.filter((r) => r.right).length}/${a.rows.length}`} tone={a.rows.every((r) => r.right) ? 'good' : 'bad'} />
                    </div>
                    <DeliverySheet q={q} a={a} show="model" />
                  </div>
                ),
              }
            : null
        }
        result={null}
        wideAnswer
      />
    </div>
  );
}

export function DeliveryInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const q = deliveryQuestion(input.turns[0] ?? '');
  if (!q) return null;
  const a = deliveryAnswer(q, input);
  const right = a.rows.filter((r) => r.right).length;
  return (
    <VizFrame
      mode={mode}
      tone={!a.answered ? 'bad' : right === a.rows.length && a.extraOk ? 'good' : right >= a.rows.length * 0.7 ? 'half' : 'bad'}
      className="pm-inline"
      eyebrow={`Check the Delivery · order ${q.poId}`}
      headline={deliverySentence(a)}
      big={`${right}/${a.rows.length}`}
      bigSub="lines exactly right"
      legend={[
        { tone: 'good', label: 'Count right' },
        { tone: 'bad', label: 'Wrong count struck out, correct one beside it' },
      ]}
    >
      <div className="pm-doc-root pm-pile">
        {impactOf(a)}
        <DeliverySheet q={q} a={a} show="model" />
      </div>
    </VizFrame>
  );
}
