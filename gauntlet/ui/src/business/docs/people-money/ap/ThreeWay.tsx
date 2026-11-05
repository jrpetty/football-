/**
 * Invoices to Pay (work.invoice-matching) and its ladder (work.invoices-ladder): the AP inbox as a wall of
 * documents, and one document at a time drawn as a three-way match: the purchase order, the goods received and
 * the supplier's invoice side by side, line by line, with the matching and mismatching lines linked. The model's
 * decision is a stamp on the invoice (PAY £… / HOLD — price / REJECT — duplicate) beside the correct one.
 */
import { useMemo, useState } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, Fact, Impact, Stamp, Tick, type Tone } from '../kit.tsx';
import { gbp, isObj, keyOf, niceDate, num, pick, readAnswer } from '../model/common.ts';
import './ap.css';
import { OUTCOME_LABEL, apAnswer, apImpact, apQuestion, apSentence, docMatch, type ApAnswer, type ApDecision, type ApDoc, type ApQuestion, type ApRow, type DocMatch } from '../model/ap.ts';

const shortDate = (s: string | null) => (s ? niceDate(s).replace(/ \d{4}$/, '') : '');

export function DecisionStamp({ d, tone, small }: { d: ApDecision | null; tone: Tone; small?: boolean }) {
  if (!d) return <Stamp tone="muted" small={small}>No answer</Stamp>;
  if (d.outcome === 'approve')
    return (
      <Stamp tone={tone} small={small} sub={d.payBy ? `by ${shortDate(d.payBy)}` : undefined}>
        {(d.amount ?? 0) < 0 ? 'Credit' : 'Pay'} {gbp(Math.abs(d.amount ?? 0))}
      </Stamp>
    );
  const [verb, why] = (OUTCOME_LABEL[d.outcome] ?? d.outcome).split(' — ');
  return (
    <Stamp tone={tone} small={small} sub={why}>
      {verb}
    </Stamp>
  );
}

const keyTone = (d: ApDecision): Tone => (d.outcome === 'approve' ? 'good' : d.outcome.startsWith('reject') ? 'bad' : 'warn');

function supplierShort(s: string) {
  const t = s.toLowerCase().replace(/(^|\s)\S/g, (m) => m.toUpperCase());
  return t.replace(/\s(Ltd|Co|Plc|Llp)\.?$/i, '').replace(/ \+ /g, ' & ');
}

/** The inbox as mini documents: supplier, number, total; with stamps once there is a decision. */
function InboxWall({ ap, a, show, sel, onSel }: { ap: ApQuestion; a: ApAnswer | null; show: 'none' | 'model' | 'key'; sel: string; onSel: (id: string) => void }) {
  return (
    <div className="pm-inbox" role="list" aria-label="Documents in the AP inbox">
      {ap.docs.map((d) => {
        const r = a?.rows.find((x) => x.id === d.id);
        const dec = show === 'model' ? (r?.model ?? null) : show === 'key' ? (r?.key ?? null) : null;
        const wrong = show === 'model' && r && !r.right;
        return (
          <button
            key={d.id}
            type="button"
            role="listitem"
            className={cx('pm-mini', sel === d.id && 'on', wrong && (r.outcomeOk ? 'wrongish' : 'wrong'), show === 'model' && r?.right && 'right')}
            aria-pressed={sel === d.id}
            aria-label={`${d.id}, ${d.kind === 'credit' ? 'credit note' : 'invoice'} ${d.number} from ${d.supplier}, ${gbp(d.total)}${dec ? `: ${OUTCOME_LABEL[dec.outcome] ?? dec.outcome}${show === 'model' ? (r?.right ? ', right' : `, wrong (correct: ${OUTCOME_LABEL[r!.key.outcome] ?? r!.key.outcome})`) : ''}` : ''}. Open it.`}
            onClick={() => onSel(d.id)}
          >
            <span className="pm-mini-id">{d.id.replace('DOC-', '#')}</span>
            <b>{supplierShort(d.supplier)}</b>
            <span className="pm-mini-n">
              {d.kind === 'credit' ? 'Credit note' : 'Invoice'} · {gbp(d.total)}
            </span>
            {dec && (
              <span className="pm-mini-stamp">
                <DecisionStamp d={dec} tone={show === 'key' ? keyTone(dec) : r?.right ? 'good' : 'bad'} small />
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

function Link({ tone, label }: { tone: Tone; label: string }) {
  return (
    <span className={cx('pm-3w-link', `t-${tone}`)} title={label}>
      <i />
      <b>{tone === 'good' ? '✓' : tone === 'bad' ? '≠' : tone === 'warn' ? '!' : '·'}</b>
    </span>
  );
}

/** One document as a three-way match: PO · goods received · invoice, line by line. */
export function ThreeWayMatch({ m, model, keyD, right, ap }: { m: DocMatch; model?: ApDecision | null; keyD?: ApDecision | null; right?: boolean; ap: ApQuestion }) {
  const d = m.doc;
  const items = m.rows;
  return (
    <div className="pm-3w-wrap">
      <div className="pm-3w-checks">
        <span className={cx('pm-chk', m.toUs ? 'ok' : 'no')}>
          <Tick ok={m.toUs} /> Addressed to {m.toUs ? ap.company : d.to.split(',')[0]}
        </span>
        <span className={cx('pm-chk', m.bankOk === false ? 'no' : m.bankOk ? 'ok' : '')}>
          <Tick ok={m.bankOk} /> Bank details {m.bankOk === false ? `differ from the master file (${m.master?.sort} / ${m.master?.account})` : m.bankOk ? 'match the master file' : 'not checked'}
        </span>
        <span className={cx('pm-chk', m.po ? 'ok' : 'no')}>
          <Tick ok={!!m.po} /> {m.po ? `${m.po.id} on the PO list` : `PO ${d.poRef ?? '(none quoted)'} not on the PO list`}
        </span>
        {m.earlier.length > 0 && <span className="pm-chk info">Earlier on this PO: {m.earlier.join(', ')}</span>}
        {m.master && <span className="pm-chk info">Terms: {m.master.terms}</span>}
      </div>
      <div className="pm-scroll">
        <div className="pm-3w" role="table" aria-label={`Three-way match of ${d.id}`}>
          <div className="pm-3w-h po" role="columnheader">
            <small>Purchase order</small>
            <b>{m.po?.id ?? 'No PO found'}</b>
            {m.po && (
              <span>
                {supplierShort(m.po.supplier)} · raised {m.po.raised} by {m.po.by}
              </span>
            )}
          </div>
          <span />
          <div className="pm-3w-h grn" role="columnheader">
            <small>Goods received</small>
            <b>{m.grns.length ? m.grns.map((g) => g.id).join(', ') : '—'}</b>
            {m.grns[0] && <span>signed {m.grns[0].signed}</span>}
          </div>
          <span />
          <div className="pm-3w-h inv" role="columnheader">
            <small>{d.kind === 'credit' ? 'Credit note' : 'Supplier’s invoice'}</small>
            <b>{d.supplier}</b>
            <span>
              {d.number} · {d.date}
              {d.against ? ` · against ${d.against}` : ''}
            </span>
            {(model !== undefined || keyD) && (
              <span className="pm-3w-stamps">
                {model !== undefined && <DecisionStamp d={model} tone={right ? 'good' : 'bad'} />}
                {keyD && model !== undefined && !right && (
                  <span className="pm-3w-correct">
                    correct: <DecisionStamp d={keyD} tone={keyTone(keyD)} small />
                  </span>
                )}
                {keyD && model === undefined && <DecisionStamp d={keyD} tone={keyTone(keyD)} />}
              </span>
            )}
          </div>
          {items.map((r) => {
            const recTone: Tone = r.received === null || !r.po ? 'muted' : r.received >= r.po.ordered ? 'good' : 'warn';
            const qtyTone: Tone = r.received === null ? 'muted' : r.qtyOver ? 'bad' : 'good';
            const priceTone: Tone = r.priceDiff === null ? 'muted' : Math.abs(r.priceDiff) < 0.005 ? 'good' : 'warn';
            return (
              <div key={r.code} className="pm-3w-row" role="row">
                <div className="pm-3w-c po" role="cell">
                  <span className="pm-code">{r.code}</span> {r.po ? r.po.desc : r.desc}
                  {r.po && (
                    <span className="q">
                      ordered <b>{r.po.ordered}</b> @ {gbp(r.po.price)}
                      {r.po.invoicedToDate ? ` · ${r.po.invoicedToDate} billed before` : ''}
                    </span>
                  )}
                </div>
                <Link tone={recTone} label={recTone === 'warn' ? 'part delivery' : 'received in full'} />
                <div className={cx('pm-3w-c grn', recTone === 'warn' && 'warn')} role="cell">
                  <span className="q">
                    received <b>{r.received ?? '—'}</b>
                  </span>
                </div>
                <Link tone={qtyTone === 'bad' ? 'bad' : priceTone === 'warn' ? 'warn' : qtyTone} label={qtyTone === 'bad' ? 'billed more than received' : priceTone === 'warn' ? 'price differs from the PO' : 'matches'} />
                <div className={cx('pm-3w-c inv', (qtyTone === 'bad' || priceTone === 'warn') && 'flag')} role="cell">
                  <span className="q">
                    <b className={cx(qtyTone === 'bad' && 'bad')}>{r.item.qty}</b> × <span className={cx(priceTone === 'warn' && 'warn')}>{gbp(r.item.price)}</span> · VAT {r.item.vatPct}% = <b>{gbp(r.item.value)}</b>
                  </span>
                  {qtyTone === 'bad' && <span className="pm-3w-note bad">billed {r.item.qty}, only {Math.max(0, (r.received ?? 0) - (r.po?.invoicedToDate ?? 0))} received and not yet billed</span>}
                  {priceTone === 'warn' && r.priceDiff !== null && <span className="pm-3w-note warn">{gbp(Math.abs(r.priceDiff))} {r.priceDiff > 0 ? 'above' : 'below'} the PO price</span>}
                </div>
              </div>
            );
          })}
          <div className="pm-3w-f po" role="cell">
            {m.po && <span>for {m.po.site}</span>}
          </div>
          <span />
          <div className="pm-3w-f grn" role="cell">
            {m.grns.map((g) => (
              <span key={g.id}>
                {g.date}
                {g.note ? ` · ${g.note}` : ''}
              </span>
            ))}
          </div>
          <span />
          <div className="pm-3w-f inv" role="cell">
            <span className="tot">
              Net {gbp(d.net)} · VAT {gbp(d.vat)} · <b>Total {gbp(d.total)}</b>
            </span>
            {d.sort && (
              <span className={cx(m.bankOk === false && 'bad')}>
                Pay to {d.sort} / {d.account}
              </span>
            )}
            {d.reason && <span>Reason: {d.reason}</span>}
          </div>
        </div>
      </div>
      {m.emails.length > 0 && (
        <div className="pm-3w-emails">
          <span className="pm-k">Emails since Monday about this</span>
          {m.emails.slice(0, 3).map((e, i) => (
            <div key={i} className="pm-email">
              <b>{e.subject}</b>
              <span className="muted">
                {e.from.replace(/<[^>]+>/, '').trim()} · {e.sent}
              </span>
              <p>{e.body.length > 260 ? `${e.body.slice(0, 257)}…` : e.body}</p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function firstWrong(a: ApAnswer | null): string | null {
  if (!a) return null;
  return (a.rows.find((r) => r.outcomeOk === false && r.key.outcome !== 'approve' && r.model?.outcome === 'approve') ?? a.rows.find((r) => r.outcomeOk === false) ?? a.rows.find((r) => !r.right))?.id ?? null;
}

function moneyImpact(ap: ApQuestion, a: ApAnswer) {
  if (!a.answered) return null;
  let paid = 0;
  let held = 0;
  for (const r of a.rows) {
    const x = apImpact(
      r,
      ap.docs.find((d) => d.id === r.id),
    );
    if (x) {
      paid += x.paidWrongly;
      held += x.heldWrongly;
    }
  }
  const wrongN = a.rows.filter((r) => !r.right).length;
  if (!wrongN && a.totalOk) return <Impact tone="good">The payment run of {gbp(a.keyTotal)} is exactly right: every invoice that should be paid is paid, and nothing else.</Impact>;
  const parts: string[] = [];
  if (paid > 0) parts.push(`would have paid ${gbp(paid)} that the policy holds or rejects`);
  if (held > 0) parts.push(`held back ${gbp(held)} that suppliers are owed`);
  if (a.modelTotal !== null && a.keyTotal !== null && !a.totalOk) parts.push(`payment run total ${gbp(a.modelTotal)} instead of ${gbp(a.keyTotal)}`);
  return parts.length ? (
    <Impact tone={paid > 0 ? 'bad' : 'warn'}>
      {parts[0]!.charAt(0).toUpperCase() + parts[0]!.slice(1)}
      {parts.length > 1 ? `; ${parts.slice(1).join('; ')}` : ''}.
    </Impact>
  ) : null;
}

function Problems({ ap, a }: { ap: ApQuestion; a: ApAnswer }) {
  const wrong = a.rows.filter((r) => !r.right);
  return (
    <ul className="pm-problems">
      {wrong.slice(0, 6).map((r) => {
        const x = apImpact(
          r,
          ap.docs.find((d) => d.id === r.id),
        );
        return (
          <li key={r.id} className={r.outcomeOk ? 't-warn' : 't-bad'}>
            <Tick ok={false} />
            <span>
              <span className="pm-tag">
                {r.id} · said {OUTCOME_LABEL[r.model?.outcome ?? ''] ?? r.model?.outcome ?? 'nothing'} · correct {OUTCOME_LABEL[r.key.outcome] ?? r.key.outcome}
              </span>
              {x?.text ?? 'Amount or pay-by date wrong.'}
            </span>
          </li>
        );
      })}
      {wrong.length > 6 && <li className="pm-more">+ {wrong.length - 6} more documents with a mistake</li>}
    </ul>
  );
}

function Remaining({ input }: { input: CaseVisualInput }) {
  const key = keyOf(input);
  const kr = key && isObj(key.remaining_received) ? key.remaining_received : null;
  if (!kr) return null;
  const ans = input.replies.length ? readAnswer(input).parsed : undefined;
  const mr = isObj(ans) ? pick(ans, 'remaining_received') : undefined;
  return (
    <div className="pm-mini-table">
      <span className="pm-k">Quantity received but not yet billed, per pool</span>
      <table>
        <tbody>
          {Object.entries(kr).map(([k, v]) => {
            const got = isObj(mr) ? num(pick(mr, k)) : null;
            const ok = got === num(v);
            return (
              <tr key={k}>
                <th scope="row">{k}</th>
                <td>{ans !== undefined ? <>{ok ? <Tick ok /> : <s>{got ?? '—'}</s>}</> : null}</td>
                <td>
                  <b>{String(v)}</b>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export function ApBeats(p: KindProps) {
  const ap = useMemo(() => apQuestion(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyA = useMemo(() => apAnswer(p.q), [p.q]);
  const a = useMemo(() => (p.a ? apAnswer(p.a) : null), [p.a]);
  const [qSel, setQSel] = useState<string | null>(null);
  const [kSel, setKSel] = useState<string | null>(null);
  const [aSel, setASel] = useState<string | null>(null);
  if (!ap) return <p className="lc-empty">These documents could not be drawn.</p>;
  const docOf = (id: string | null) => ap.docs.find((d) => d.id === id) ?? ap.docs[0]!;
  const qd = docOf(qSel);
  const kd = docOf(kSel ?? (keyA.rows.find((r) => r.key.outcome !== 'approve')?.id ?? null));
  const ad = docOf(aSel ?? firstWrong(a));
  const rowOf = (x: ApAnswer, id: string): ApRow | undefined => x.rows.find((r) => r.id === id);
  const counts = keyA.rows.reduce<Record<string, number>>((acc, r) => ((acc[r.key.outcome] = (acc[r.key.outcome] ?? 0) + 1), acc), {});
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={a ? apSentence(a) : null}
        impact={a ? moneyImpact(ap, a) : null}
        question={{
          title: 'The question',
          sub: `${ap.company}: ${ap.docs.length} documents to check against ${ap.pos.length} purchase orders and ${ap.grns.length} goods-received notes before the ${ap.run} payment run`,
          alt: `An accounts-payable inbox of ${ap.docs.length} supplier documents for ${ap.company}. Each must be checked against its purchase order and goods-received notes under a written policy and approved, held or rejected. Shown: ${qd.id}, ${qd.kind} ${qd.number} from ${qd.supplier} for ${gbp(qd.total)}.`,
          node: (
            <div className="pm-pile">
              <div className="pm-facts">
                <Fact k="documents in the inbox" v={ap.docs.length} />
                <Fact k="purchase orders" v={ap.pos.length} />
                <Fact k="goods-received notes" v={ap.grns.length} />
                <Fact k="emails since Monday" v={ap.emails.length} />
                <Fact k="payment run" v={ap.run.replace(/^\w+ /, '')} />
              </div>
              <ThreeWayMatch ap={ap} m={docMatch(ap, qd)} />
            </div>
          ),
          controls: <InboxWall ap={ap} a={null} show="none" sel={qd.id} onSel={setQSel} />,
        }}
        keyBeat={{
          title: 'The correct decisions',
          sub: `${Object.entries(counts)
            .map(([k, n]) => `${n} ${(OUTCOME_LABEL[k] ?? k).toLowerCase()}`)
            .join(' · ')} · payment run ${gbp(keyA.keyTotal)}`,
          alt: keyA.rows.map((r) => `${r.id}: ${OUTCOME_LABEL[r.key.outcome] ?? r.key.outcome}${r.key.outcome === 'approve' ? ` ${gbp(r.key.amount)} by ${r.key.payBy}` : ''}`).join('. '),
          node: <ThreeWayMatch ap={ap} m={docMatch(ap, kd)} keyD={rowOf(keyA, kd.id)?.key ?? null} />,
          controls: <InboxWall ap={ap} a={keyA} show="key" sel={kd.id} onSel={setKSel} />,
        }}
        answer={
          a && p.a
            ? {
                title: 'The model’s answer',
                sub: a.answered ? 'Its stamp on every document; red outline: wrong. Pick one to see it matched up' : 'No answer could be read from the reply',
                alt: a.rows.map((r) => `${r.id}: ${r.model ? (OUTCOME_LABEL[r.model.outcome] ?? r.model.outcome) : 'nothing'}${r.right ? '' : `, wrong (correct ${OUTCOME_LABEL[r.key.outcome] ?? r.key.outcome})`}`).join('. '),
                node: (
                  <div className="pm-pile">
                    <div className="pm-facts">
                      <Fact k="outcomes right" v={`${a.rows.filter((r) => r.outcomeOk).length}/${a.rows.length}`} tone={a.rows.every((r) => r.outcomeOk) ? 'good' : 'bad'} />
                      <Fact k="payment run total" v={gbp(a.modelTotal)} tone={a.totalOk ? 'good' : 'bad'} />
                      {!a.totalOk && <Fact k="correct" v={gbp(a.keyTotal)} />}
                    </div>
                    <ThreeWayMatch ap={ap} m={docMatch(ap, ad)} model={rowOf(a, ad.id)?.model ?? null} keyD={rowOf(a, ad.id)?.key ?? null} right={rowOf(a, ad.id)?.right} />
                  </div>
                ),
                controls: <InboxWall ap={ap} a={a} show="model" sel={ad.id} onSel={setASel} />,
              }
            : null
        }
        result={
          a && p.a && (a.rows.some((r) => !r.right) || (p.q.expected && isObj(p.q.expected) && 'remaining_received' in p.q.expected))
            ? {
                title: 'The result',
                alt: a.rows
                  .filter((r) => !r.right)
                  .map((r) => apImpact(r, ap.docs.find((d) => d.id === r.id))?.text ?? `${r.id} wrong`)
                  .join(' '),
                node: (
                  <div className="pm-pile">
                    <Problems ap={ap} a={a} />
                    <Remaining input={p.a} />
                  </div>
                ),
              }
            : null
        }
      />
    </div>
  );
}

export function ApInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const ap = apQuestion(input.turns[0] ?? '');
  if (!ap) return null;
  const a = apAnswer(input);
  const id = firstWrong(a);
  const d: ApDoc | undefined = ap.docs.find((x) => x.id === id);
  const right = a.rows.filter((r) => r.outcomeOk).length;
  const row = a.rows.find((r) => r.id === d?.id);
  return (
    <VizFrame
      mode={mode}
      tone={!a.answered ? 'bad' : right === a.rows.length && a.totalOk ? 'good' : right >= a.rows.length * 0.8 ? 'half' : 'bad'}
      className="pm-inline"
      eyebrow={`Invoices to Pay · ${ap.docs.length} documents, ${ap.company}`}
      headline={apSentence(a)}
      big={`${right}/${a.rows.length}`}
      bigSub="outcomes right"
      legend={[
        { tone: 'good', label: 'Stamp matches the policy' },
        { tone: 'bad', label: 'Wrong stamp (correct one beside it)' },
      ]}
    >
      <div className="pm-doc-root pm-pile">
        {moneyImpact(ap, a)}
        <InboxWallStatic ap={ap} a={a} />
        {d && row && <ThreeWayMatch ap={ap} m={docMatch(ap, d)} model={row.model} keyD={row.key} right={row.right} />}
      </div>
    </VizFrame>
  );
}

function InboxWallStatic({ ap, a }: { ap: ApQuestion; a: ApAnswer }) {
  return (
    <div className="pm-inbox static">
      {ap.docs.map((d) => {
        const r = a.rows.find((x) => x.id === d.id);
        return (
          <div key={d.id} className={cx('pm-mini', r && !r.right && (r.outcomeOk ? 'wrongish' : 'wrong'), r?.right && 'right')}>
            <span className="pm-mini-id">{d.id.replace('DOC-', '#')}</span>
            <b>{supplierShort(d.supplier)}</b>
            <span className="pm-mini-n">{gbp(d.total)}</span>
            {r && (
              <span className="pm-mini-stamp">
                <DecisionStamp d={r.model} tone={r.right ? 'good' : 'bad'} small />
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}
