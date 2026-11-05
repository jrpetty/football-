/**
 * Reconcile the Account (work.bank-reconciliation) and its ladder (work.reconciliation-ladder): the bank
 * statement and the cash book side by side with match lines drawn between them. In the answer, each line the
 * model drew is green when the key has it and red when not; links the model missed are dashed. Unmatched items
 * carry their reason as a chip (the model's, ticked or crossed, with the correct one beside a wrong one).
 */
import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, DocPicker, Fact, Impact, Tick } from '../kit.tsx';
import { gbp } from '../model/common.ts';
import { REASON_LABEL, recAnswer, recQuestion, recSentence, type BankLine, type BookLine, type Link, type RecAnswer, type RecQuestion, type SideVerdict } from '../model/bankrec.ts';
import './bankrec.css';

type Show = 'none' | 'key' | 'model';

interface Edge {
  b: string;
  l: string;
  tone: 'key' | 'good' | 'bad' | 'missed';
}

function edgesOf(a: RecAnswer, show: Show, bankIds: Set<string>, bookIds: Set<string>): Edge[] {
  if (show === 'none') return [];
  const out: Edge[] = [];
  const has = (link: Link | null, id: string) => !!link && link.ids.includes(id);
  for (const v of a.bank.values()) {
    if (!bankIds.has(v.id)) continue;
    if (show === 'key') {
      for (const l of v.key?.ids ?? []) if (bookIds.has(l)) out.push({ b: v.id, l, tone: 'key' });
      continue;
    }
    for (const l of v.model?.ids ?? []) if (bookIds.has(l)) out.push({ b: v.id, l, tone: has(v.key, l) ? 'good' : 'bad' });
    for (const l of v.key?.ids ?? []) if (bookIds.has(l) && !has(v.model, l)) out.push({ b: v.id, l, tone: 'missed' });
  }
  return out;
}

function Reason({ v, show }: { v: SideVerdict | undefined; show: Show }) {
  if (!v || show === 'none') return null;
  const k = v.key;
  const m = v.model;
  if (show === 'key') return k?.reason ? <span className="pm-rr key">{REASON_LABEL[k.reason] ?? k.reason}</span> : null;
  if (m?.reason || k?.reason)
    return (
      <span className={cx('pm-rr', v.ok ? 'ok' : 'no')}>
        <Tick ok={v.ok} />
        {m?.reason ? (REASON_LABEL[m.reason] ?? m.reason) : m?.ids.length ? `matched ${m.ids.join(', ')}` : 'left out'}
        {!v.ok && k && <em> · correct: {k.reason ? (REASON_LABEL[k.reason] ?? k.reason) : `matches ${k.ids.join(', ')}`}</em>}
      </span>
    );
  return null;
}

const amt = (out: number | null, inn: number | null) => (out !== null ? <span className="out">−{gbp(out).replace('£', '')}</span> : <span className="in">+{gbp(inn).replace('£', '')}</span>);

function Sheet({ q, a, show, month, focusOnly }: { q: RecQuestion; a: RecAnswer; show: Show; month: string | null; focusOnly: boolean }) {
  const box = useRef<HTMLDivElement>(null);
  const [paths, setPaths] = useState<Array<Edge & { d: string }>>([]);
  const bankAll = q.bank.filter((b) => !month || b.month === month);
  // The cash book: this month's entries, plus earlier entries or last month's items that this month's lines clear.
  const linkedBook = new Set(bankAll.flatMap((b) => [...(a.bank.get(b.id)?.key?.ids ?? []), ...(a.bank.get(b.id)?.model?.ids ?? [])]));
  const bookAll = q.book.filter((l) => (month ? l.month === month || linkedBook.has(l.id) : true));
  const interesting = (v: SideVerdict | undefined) => !!v && (v.ok === false || !!v.key?.reason || (v.key?.ids.length ?? 0) > 1 || /^P/.test(v.key?.ids[0] ?? ''));
  const keepB = focusOnly ? bankAll.filter((b) => interesting(a.bank.get(b.id))) : bankAll;
  const keepIdsB = new Set(keepB.map((b) => b.id));
  const keepL = focusOnly ? bookAll.filter((l) => interesting(a.book.get(l.id)) || keepB.some((b) => a.bank.get(b.id)?.key?.ids.includes(l.id) || a.bank.get(b.id)?.model?.ids.includes(l.id))) : bookAll;
  const bankRows: BankLine[] = focusOnly ? bankAll.filter((b) => keepIdsB.has(b.id) || bookAll.some((l) => keepL.includes(l) && (a.bank.get(b.id)?.key?.ids.includes(l.id) || a.bank.get(b.id)?.model?.ids.includes(l.id)))) : bankAll;
  const bookRows: BookLine[] = keepL;
  const edges = edgesOf(
    a,
    show,
    new Set(bankRows.map((b) => b.id)),
    new Set(bookRows.map((l) => l.id)),
  );
  const sig = edges.map((e) => `${e.b}-${e.l}-${e.tone}`).join('|') + bankRows.length + bookRows.length;
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const draw = () => {
      const r0 = el.getBoundingClientRect();
      const gutter = el.querySelector<HTMLElement>('.pm-rec-gutter');
      if (!gutter) return;
      const g = gutter.getBoundingClientRect();
      const y = (sel: string) => {
        const row = el.querySelector<HTMLElement>(sel);
        if (!row) return null;
        const r = row.getBoundingClientRect();
        return r.top + r.height / 2 - r0.top;
      };
      const x1 = g.left - r0.left;
      const x2 = g.right - r0.left;
      setPaths(
        edges
          .map((e) => {
            const a1 = y(`[data-b="${e.b}"]`);
            const a2 = y(`[data-l="${e.l}"]`);
            if (a1 === null || a2 === null) return null;
            const mx = (x1 + x2) / 2;
            return { ...e, d: `M${x1} ${a1} C${mx} ${a1} ${mx} ${a2} ${x2} ${a2}` };
          })
          .filter((x): x is Edge & { d: string } => !!x),
      );
    };
    draw();
    const ro = new ResizeObserver(draw);
    ro.observe(el);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sig]);
  const close = month ? q.closing[month] : q.closing[q.months[0] ?? ''];
  return (
    <div className="pm-scroll">
      <div className="pm-rec" ref={box}>
        <div className="pm-rec-doc bank">
          <div className="pm-rec-h">
            <small>Bank statement{month ? ` · ${month}` : ''}</small>
            <b>Business Current Account</b>
          </div>
          {bankRows.map((b) => {
            const v = a.bank.get(b.id);
            return (
              <div key={b.id} data-b={b.id} className={cx('pm-rec-r', show === 'model' && v?.ok === false && 'bad')}>
                <span className="id">{b.id}</span>
                <span className="d">{b.date.replace(/^\w+ /, '')}</span>
                <span className="t">
                  {b.desc}
                  <Reason v={v} show={show} />
                </span>
                <span className="a">{amt(b.out, b.inn)}</span>
              </div>
            );
          })}
          <div className="pm-rec-f">Closing balance {gbp(close?.bank ?? null)}</div>
        </div>
        <div className="pm-rec-gutter" aria-hidden="true" />
        <div className="pm-rec-doc book">
          <div className="pm-rec-h">
            <small>Cash book{month ? ` · ${month}` : ''}</small>
            <b>{q.company}</b>
          </div>
          {bookRows.map((l) => {
            const v = a.book.get(l.id);
            return (
              <div key={l.id} data-l={l.id} className={cx('pm-rec-r', show === 'model' && v?.ok === false && 'bad', l.prior && 'prior', month && l.month !== month && !l.prior && 'earlier')}>
                <span className="id">{l.id}</span>
                <span className="d">{l.prior ? 'b/f' : l.month !== month && month ? l.month.slice(0, 3) : l.date.replace(/^\w+ /, '')}</span>
                <span className="t">
                  {l.details}
                  <Reason v={v} show={show} />
                </span>
                <span className="a">{amt(l.pay, l.rec)}</span>
              </div>
            );
          })}
          <div className="pm-rec-f">Cash-book closing balance {gbp(close?.book ?? null)}</div>
        </div>
        <svg className="pm-rec-lines" aria-hidden="true">
          {paths.map((p, i) => (
            <path key={i} d={p.d} className={`e-${p.tone}`} />
          ))}
        </svg>
      </div>
    </div>
  );
}

function Balances({ a }: { a: RecAnswer }) {
  return (
    <div className="pm-mini-table">
      <span className="pm-k">Adjusted balance</span>
      <table>
        <tbody>
          {a.balances.map((b) => (
            <tr key={b.month}>
              <th scope="row">{b.month}</th>
              <td>{a.answered ? b.ok ? <Tick ok /> : <s>{gbp(b.got)}</s> : null}</td>
              <td>
                <b>{gbp(b.want)}</b>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {a.diffs.length > 0 && (
        <>
          <span className="pm-k">Explained differences</span>
          <table>
            <tbody>
              {a.diffs.map((d) => (
                <tr key={d.id}>
                  <th scope="row">
                    {d.id} {d.want ? (REASON_LABEL[d.want.reason] ?? d.want.reason) : '(not a difference)'}
                  </th>
                  <td>{a.answered ? d.ok ? <Tick ok /> : <s>{d.got ? gbp(d.got.amount) : 'missed'}</s> : null}</td>
                  <td>
                    <b>{d.want ? gbp(d.want.amount) : '—'}</b>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

function Legend({ show }: { show: Show }) {
  if (show === 'none') return null;
  return (
    <div className="pm-legend pm-rec-key">
      {show === 'key' ? (
        <span>
          <i className="e-key" /> correct match
        </span>
      ) : (
        <>
          <span>
            <i className="e-good" /> model’s match, right
          </span>
          <span>
            <i className="e-bad" /> model’s match, wrong
          </span>
          <span>
            <i className="e-missed" /> match the model missed
          </span>
        </>
      )}
    </div>
  );
}

function impactOf(a: RecAnswer) {
  if (!a.answered) return null;
  const b = a.balances.find((x) => x.ok === false);
  if (b && b.got !== null && b.want !== null)
    return (
      <Impact tone="bad">
        Reported {b.month ? `${b.month}’s ` : ''}adjusted balance as {gbp(b.got)}: {gbp(Math.abs(b.got - b.want))} {b.got > b.want ? 'more' : 'less'} cash than the business really has.
      </Impact>
    );
  const missedDiff = a.diffs.find((d) => d.want && !d.got);
  if (missedDiff)
    return (
      <Impact tone="warn">
        Missed a {gbp(Math.abs(missedDiff.want!.amount ?? 0))} {missedDiff.want!.reason === 'keying_error' ? 'keying error (digits swapped in the cash book)' : missedDiff.want!.reason === 'fx_difference' ? 'exchange-rate difference' : missedDiff.want!.reason} on {missedDiff.id}: the cash book stays wrong by that much.
      </Impact>
    );
  if ([...a.bank.values(), ...a.book.values()].every((v) => v.ok) && a.balances.every((x) => x.ok)) return <Impact tone="good">The books reconcile to the penny: every line explained, every balance right.</Impact>;
  return null;
}

function SheetBlock({ q, a, show, monthState }: { q: RecQuestion; a: RecAnswer; show: Show; monthState: [string | null, (m: string) => void] }) {
  const big = q.bank.length + q.book.length > 70;
  const [all, setAll] = useState(!big);
  const [month, setMonth] = monthState;
  return (
    <div className="pm-pile">
      {q.months.length > 1 && <DocPicker items={q.months.map((m) => ({ id: m, label: m, tone: show === 'model' ? (a.balances.find((b) => b.month === m)?.ok === false || [...a.bank.values()].some((v) => v.ok === false && q.bank.find((x) => x.id === v.id)?.month === m) ? 'bad' : 'good') : undefined }))} value={month} onPick={setMonth} label="Month" />}
      <Legend show={show} />
      <Sheet q={q} a={a} show={show} month={q.months.length > 1 ? month : null} focusOnly={!all && show !== 'none'} />
      {big && show !== 'none' && (
        <button type="button" className="btn xs ghost" onClick={() => setAll((x) => !x)} aria-expanded={all}>
          {all ? 'Show only the lines that need care' : `Show every line (${q.bank.length + q.book.length})`}
        </button>
      )}
    </div>
  );
}

export function RecBeats(p: KindProps) {
  const q = useMemo(() => recQuestion(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyA = useMemo(() => (q ? recAnswer(q, p.q) : null), [q, p.q]);
  const a = useMemo(() => (q && p.a ? recAnswer(q, p.a) : null), [q, p.a]);
  const firstBadMonth = a && q ? (q.bank.find((b) => a.bank.get(b.id)?.ok === false)?.month ?? q.months[0]!) : (q?.months[0] ?? '');
  const qm = useState<string | null>(q?.months[0] ?? null);
  const km = useState<string | null>(q?.months[0] ?? null);
  const am = useState<string | null>(firstBadMonth);
  if (!q || !keyA) return <p className="lc-empty">This reconciliation could not be drawn.</p>;
  const n = q.bank.length + q.book.length;
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={a ? recSentence(a) : null}
        impact={a ? impactOf(a) : null}
        wideAnswer
        question={{
          title: 'The question',
          sub: `${q.company}: ${q.bank.length} statement lines and ${q.book.length} cash-book entries${q.months.length > 1 ? ` over ${q.months.length} months` : ` for ${q.months[0]}`}. Match every line, explain the rest, give the adjusted balance`,
          alt: `A bank statement and a cash book side by side${q.months.length > 1 ? `, ${q.months.join(', ')}` : ''}: ${n} lines to match or explain.`,
          node: <SheetBlock q={q} a={keyA} show="none" monthState={[qm[0], (m) => qm[1](m)]} />,
        }}
        keyBeat={{
          title: 'The correct reconciliation',
          sub: 'Lines join each statement line to its cash-book entries; chips give the reason for the rest',
          alt: [...keyA.bank.values()]
            .filter((v) => v.key?.reason)
            .map((v) => `${v.id}: ${REASON_LABEL[v.key!.reason!] ?? v.key!.reason}`)
            .join('. '),
          node: (
            <div className="pm-pile">
              <SheetBlock q={q} a={keyA} show="key" monthState={[km[0], (m) => km[1](m)]} />
              <Balances a={keyA} />
            </div>
          ),
        }}
        answer={
          a
            ? {
                title: 'The model’s answer',
                sub: a.answered ? 'Its matches drawn between the statement and the cash book (green right, red wrong, dashed: missed) and its reasons for the rest' : 'No answer could be read from the reply',
                alt: [...a.bank.values(), ...a.book.values()]
                  .filter((v) => v.ok === false)
                  .slice(0, 30)
                  .map((v) => `${v.id}: model ${v.model ? (v.model.reason ?? v.model.ids.join('+')) : 'nothing'}, correct ${v.key ? (v.key.reason ?? v.key.ids.join('+')) : 'nothing'}`)
                  .join('. '),
                node: (
                  <div className="pm-pile">
                    <div className="pm-facts">
                      <Fact k="lines right" v={`${[...a.bank.values(), ...a.book.values()].filter((v) => v.ok).length}/${n}`} tone={[...a.bank.values(), ...a.book.values()].every((v) => v.ok) ? 'good' : 'bad'} />
                      <Fact k={a.balances.length > 1 ? 'month balances right' : 'adjusted balance'} v={a.balances.length > 1 ? `${a.balances.filter((b) => b.ok).length}/${a.balances.length}` : gbp(a.balances[0]?.got ?? null)} tone={a.balances.every((b) => b.ok) ? 'good' : 'bad'} />
                    </div>
                    <div className="pm-cvq pm-xgrid">
                      <SheetBlock q={q} a={a} show="model" monthState={[am[0], (m) => am[1](m)]} />
                      <Balances a={a} />
                    </div>
                  </div>
                ),
              }
            : null
        }
        result={null}
      />
    </div>
  );
}

export function RecInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const q = recQuestion(input.turns[0] ?? '');
  if (!q) return null;
  const a = recAnswer(q, input);
  const all = [...a.bank.values(), ...a.book.values()];
  const right = all.filter((v) => v.ok).length;
  const month = q.bank.find((b) => a.bank.get(b.id)?.ok === false)?.month ?? q.months[0] ?? null;
  return (
    <VizFrame
      mode={mode}
      tone={!a.answered ? 'bad' : right === all.length && a.balances.every((b) => b.ok) ? 'good' : right >= all.length * 0.9 ? 'half' : 'bad'}
      className="pm-inline"
      eyebrow={`Reconcile the Account · ${q.company}`}
      headline={recSentence(a)}
      big={`${right}/${all.length}`}
      bigSub="lines matched or explained right"
      legend={[
        { tone: 'good', label: 'Model’s match, right' },
        { tone: 'bad', label: 'Model’s match or reason, wrong' },
      ]}
    >
      <div className="pm-doc-root pm-pile">
        {impactOf(a)}
        <Legend show="model" />
        <Sheet q={q} a={a} show="model" month={q.months.length > 1 ? month : null} focusOnly={all.length > 60} />
        <Balances a={a} />
      </div>
    </VizFrame>
  );
}
