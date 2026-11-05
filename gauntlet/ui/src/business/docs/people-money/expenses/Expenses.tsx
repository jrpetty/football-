/**
 * Expense Policy Check (work.expense-audit) and its ladder (work.expenses-ladder): each claim drawn as a claim
 * form, every line with its receipt slip (or the lack of one), and the policy column: the model's flag (rule and
 * amount to pay) stamped against the correct one. Every line of the batch is also a square on a wall, so a big
 * batch can be scanned at a glance and any claim opened.
 */
import { useMemo, useState } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, Fact, Impact, Paper, Stamp, Tick } from '../kit.tsx';
import { gbp } from '../model/common.ts';
import { RULE_LABEL, expensesAnswer, expensesQuestion, expensesSentence, type Claim, type ExpensesAnswer, type ExpensesQuestion, type LineVerdict } from '../model/expenses.ts';
import './expenses.css';

type Show = 'none' | 'key' | 'model';

function FlagStamp({ f, tone }: { f: { rule: string; pay: number | null } | null; tone: 'good' | 'bad' | 'warn' | 'muted' }) {
  if (!f) return <Stamp tone={tone} small>Pay in full</Stamp>;
  return (
    <Stamp tone={tone} small sub={`pay ${f.pay === null ? '?' : gbp(f.pay)}`}>
      {RULE_LABEL[f.rule] ?? f.rule}
    </Stamp>
  );
}

function Receipt({ l }: { l: LineVerdict['line'] }) {
  if (l.receipt === 'not needed') return <span className="pm-rcpt none">mileage log</span>;
  if (l.receipt === 'no')
    return (
      <span className="pm-rcpt missing">
        <b>No receipt</b>
      </span>
    );
  return (
    <span className="pm-rcpt">
      <b>{l.merchant.split(',')[0]}</b>
      <span>{l.date.replace(/^\w+ /, '')}</span>
      <span className="amt">TOTAL {l.amount}</span>
      {l.alcohol && <span>incl. alcohol {l.alcohol}</span>}
      {l.vat && <span>VAT {l.vat}</span>}
    </span>
  );
}

export function ClaimForm({ claim, verdicts, show, focus }: { claim: Claim; verdicts: LineVerdict[]; show: Show; focus?: string | null }) {
  return (
    <Paper className="pm-claim" label={`Expense claim ${claim.id}`}>
      <div className="pm-claim-head">
        <div>
          <small>Expense claim{claim.month ? ` · ${claim.month}` : ''}</small>
          <b>
            {claim.id} · {claim.who}
          </b>
          <span>
            {claim.dept} · submitted {claim.submitted}
          </span>
        </div>
        {claim.note && <span className="pm-claim-note">{claim.note}</span>}
      </div>
      <table className="pm-claim-t">
        <thead>
          <tr>
            <th>Line</th>
            <th>Receipt</th>
            <th>What</th>
            <th className="r">Claimed</th>
            {show !== 'none' && <th>{show === 'key' ? 'Policy says' : 'Model said'}</th>}
          </tr>
        </thead>
        <tbody>
          {verdicts.map((v) => {
            const l = v.line;
            const wrong = show === 'model' && v.right === false;
            return (
              <tr key={l.id} className={cx(wrong && 'bad', focus === l.id && 'focus')}>
                <td className="id">{l.id}</td>
                <td>
                  <Receipt l={l} />
                </td>
                <td>
                  <b>{l.what}</b>
                  <span className="sub">
                    {l.date} · {l.merchant}
                  </span>
                  {l.approval && <span className="sub">{l.approval}</span>}
                  {l.extra.map((x) => (
                    <span key={x} className="sub">
                      {x}
                    </span>
                  ))}
                </td>
                <td className="r amt">
                  {l.amount}
                  {l.alcohol && <span className="sub">alcohol {l.alcohol}</span>}
                </td>
                {show === 'key' && (
                  <td>
                    <FlagStamp f={v.key} tone={v.key ? 'warn' : 'muted'} />
                  </td>
                )}
                {show === 'model' && (
                  <td>
                    <FlagStamp f={v.model} tone={v.right ? (v.key ? 'good' : 'muted') : 'bad'} />
                    {wrong && (
                      <span className="pm-claim-fix">
                        correct: <FlagStamp f={v.key} tone="good" />
                      </span>
                    )}
                  </td>
                )}
              </tr>
            );
          })}
        </tbody>
      </table>
    </Paper>
  );
}

function tone(v: LineVerdict, show: Show): string {
  if (show === 'none') return 'plain';
  if (show === 'key') return v.key ? 'cut' : 'ok';
  if (v.right === null) return 'plain';
  return v.right ? (v.key ? 'caught' : 'ok') : 'wrong';
}

function Wall({ q, a, show, sel, onSel }: { q: ExpensesQuestion; a: ExpensesAnswer; show: Show; sel: string; onSel: (claim: string) => void }) {
  return (
    <div className="pm-xwall" role="group" aria-label="Every claim line">
      {q.claims.map((c) => {
        const vs = a.lines.filter((v) => v.claim.id === c.id);
        const bad = show === 'model' ? vs.filter((v) => v.right === false).length : show === 'key' ? vs.filter((v) => v.key).length : 0;
        return (
          <button key={c.id} type="button" className={cx('pm-xwall-c', sel === c.id && 'on', show === 'model' && bad > 0 && 'bad')} aria-pressed={sel === c.id} onClick={() => onSel(c.id)} aria-label={`Claim ${c.id}, ${c.who}${c.month ? `, ${c.month}` : ''}: ${vs.length} lines${show === 'model' ? `, ${bad} judged wrongly` : show === 'key' ? `, ${bad} break the policy` : ''}. Open the claim.`}>
            <span className="t">
              {c.id} · {c.who.split(' ')[0]}
              {c.month ? ` · ${c.month.slice(0, 3)}` : ''}
            </span>
            <span className="sq">
              {vs.map((v) => (
                <i key={v.line.id} className={tone(v, show)} />
              ))}
            </span>
          </button>
        );
      })}
    </div>
  );
}

function Totals({ a }: { a: ExpensesAnswer }) {
  const rows = a.totals;
  const shown = rows.filter((r) => r.ok === false).concat(rows.filter((r) => r.ok !== false)).slice(0, 12);
  return (
    <div className="pm-mini-table">
      <span className="pm-k">What to pay</span>
      <table>
        <tbody>
          {shown.map((r) => (
            <tr key={r.label}>
              <th scope="row">{r.label}</th>
              <td>{a.answered ? r.ok ? <Tick ok /> : <s>{r.label.startsWith('Miles') ? r.got : gbp(r.got)}</s> : null}</td>
              <td>
                <b>{r.label.startsWith('Miles') ? r.want?.toLocaleString('en-GB') : gbp(r.want)}</b>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.length > shown.length && <span className="pm-more">+ {rows.length - shown.length} more totals</span>}
    </div>
  );
}

function impactOf(a: ExpensesAnswer) {
  if (!a.answered) return null;
  const wrongPay = a.totals.filter((t) => t.ok === false && t.want !== null && t.got !== null && !t.label.startsWith('Miles') && !t.label.startsWith('VAT') && !t.label.startsWith('Total'));
  const missed = a.lines.filter((l) => l.key && !l.model);
  if (missed.length)
    return (
      <Impact tone="bad">
        {missed.length} line{missed.length > 1 ? 's' : ''} that break{missed.length === 1 ? 's' : ''} the policy would be paid in full ({[...new Set(missed.map((m) => (RULE_LABEL[m.key!.rule] ?? m.key!.rule).toLowerCase()))].slice(0, 3).join(', ')}).
      </Impact>
    );
  if (wrongPay.length) {
    const t = wrongPay[0]!;
    const d = t.got! - t.want!;
    return (
      <Impact tone="bad">
        {t.label.replace(/^Pay /, '')} would be paid {gbp(t.got)} instead of {gbp(t.want)}: {d > 0 ? `${gbp(d)} too much` : `${gbp(-d)} too little`}
        {wrongPay.length > 1 ? `, and ${wrongPay.length - 1} more payout${wrongPay.length > 2 ? 's' : ''} wrong` : ''}.
      </Impact>
    );
  }
  if (a.lines.every((l) => l.right) && a.totals.every((t) => t.ok)) return <Impact tone="good">Everyone would be paid exactly what the policy allows, and the VAT reclaim is right.</Impact>;
  return null;
}

function firstClaim(a: ExpensesAnswer, show: Show, q: ExpensesQuestion): string {
  const v = show === 'model' ? a.lines.find((l) => l.right === false) : show === 'key' ? a.lines.find((l) => l.key) : undefined;
  return v?.claim.id ?? q.claims[0]!.id;
}

function Viewer({ q, a, show, sel }: { q: ExpensesQuestion; a: ExpensesAnswer; show: Show; sel: string; onSel?: (c: string) => void }) {
  const claim = q.claims.find((c) => c.id === sel) ?? q.claims[0]!;
  return (
    <div className="pm-pile">
      <ClaimForm claim={claim} verdicts={a.lines.filter((v) => v.claim.id === claim.id)} show={show} />
    </div>
  );
}

export function ExpensesBeats(p: KindProps) {
  const q = useMemo(() => expensesQuestion(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyA = useMemo(() => (q ? expensesAnswer(q, p.q) : null), [q, p.q]);
  const a = useMemo(() => (q && p.a ? expensesAnswer(q, p.a) : null), [q, p.a]);
  const [qs, setQs] = useState<string | null>(null);
  const [ks, setKs] = useState<string | null>(null);
  const [as, setAs] = useState<string | null>(null);
  if (!q || !keyA) return <p className="lc-empty">These claims could not be drawn.</p>;
  const n = keyA.lines.length;
  const people = [...new Set(q.claims.map((c) => c.who))];
  const cut = keyA.lines.filter((l) => l.key).length;
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={a ? expensesSentence(a) : null}
        impact={a ? impactOf(a) : null}
        wideAnswer
        question={{
          title: 'The question',
          sub: `${n} claim lines from ${people.length} people in ${q.claims.length} claims${q.months.length ? ` over ${q.months.length} months` : ''}, checked against a written expenses policy`,
          alt: `Expense claims: ${q.claims.length} claims from ${people.join(', ')}. Each line has a date, merchant, what it was for, the amount and whether a receipt is attached.`,
          node: <Viewer q={q} a={keyA} show="none" sel={qs ?? q.claims[0]!.id} onSel={setQs} />,
          controls: <Wall q={q} a={keyA} show="none" sel={qs ?? q.claims[0]!.id} onSel={setQs} />,
        }}
        keyBeat={{
          title: 'The correct audit',
          sub: `${cut} of ${n} lines are paid less than claimed`,
          alt: keyA.lines
            .filter((l) => l.key)
            .slice(0, 40)
            .map((l) => `${l.line.id}: ${RULE_LABEL[l.key!.rule] ?? l.key!.rule}, pay ${gbp(l.key!.pay)}`)
            .join('. '),
          node: (
            <div className="pm-pile">
              <Viewer q={q} a={keyA} show="key" sel={ks ?? firstClaim(keyA, 'key', q)} onSel={setKs} />
              <Totals a={keyA} />
            </div>
          ),
          controls: <Wall q={q} a={keyA} show="key" sel={ks ?? firstClaim(keyA, 'key', q)} onSel={setKs} />,
        }}
        answer={
          a
            ? {
                title: 'The model’s answer',
                sub: a.answered ? 'Its flag on every line (rule and amount to pay); red: wrong, with the correct flag beside it. Squares: every line, pick a claim to open it' : 'No answer could be read from the reply',
                alt: a.lines
                  .filter((l) => l.right === false)
                  .slice(0, 40)
                  .map((l) => `${l.line.id}: model ${l.model ? `${l.model.rule} pay ${l.model.pay}` : 'paid in full'}, correct ${l.key ? `${l.key.rule} pay ${l.key.pay}` : 'paid in full'}`)
                  .join('. '),
                node: (
                  <div className="pm-pile">
                    <div className="pm-facts">
                      <Fact k="policy breaches flagged" v={`${a.lines.filter((l) => l.key && l.model).length}/${cut}`} tone={a.lines.every((l) => !l.key || l.model) ? 'good' : 'bad'} />
                      <Fact k="lines judged exactly right" v={`${a.lines.filter((l) => l.right).length}/${n}`} />
                    </div>
                    <div className="pm-cvq pm-xgrid">
                      <Viewer q={q} a={a} show="model" sel={as ?? firstClaim(a, 'model', q)} onSel={setAs} />
                      <Totals a={a} />
                    </div>
                  </div>
                ),
                controls: <Wall q={q} a={a} show="model" sel={as ?? firstClaim(a, 'model', q)} onSel={setAs} />,
              }
            : null
        }
        result={null}
      />
    </div>
  );
}

export function ExpensesInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const q = expensesQuestion(input.turns[0] ?? '');
  if (!q) return null;
  const a = expensesAnswer(q, input);
  const right = a.lines.filter((l) => l.right).length;
  const claim = q.claims.find((c) => c.id === firstClaim(a, 'model', q))!;
  return (
    <VizFrame
      mode={mode}
      tone={!a.answered ? 'bad' : right === a.lines.length && a.totals.every((t) => t.ok) ? 'good' : right >= a.lines.length * 0.9 ? 'half' : 'bad'}
      className="pm-inline"
      eyebrow={`Expense Policy Check · ${a.lines.length} claim lines`}
      headline={expensesSentence(a)}
      big={`${right}/${a.lines.length}`}
      bigSub="lines judged right"
      legend={[
        { tone: 'good', label: 'Flag matches the policy' },
        { tone: 'bad', label: 'Wrong flag (correct one beside it)' },
      ]}
    >
      <div className="pm-doc-root pm-pile">
        {impactOf(a)}
        <div className="pm-cvq pm-xgrid">
          <ClaimForm claim={claim} verdicts={a.lines.filter((v) => v.claim.id === claim.id)} show="model" />
          <Totals a={a} />
        </div>
      </div>
    </VizFrame>
  );
}
