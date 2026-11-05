/**
 * Check the Payslips (work.payroll-check) and its ladder (work.payroll-ladder): the bureau's draft payslips
 * drawn as payslips. Where a draft figure differs from the correct one (the answer key) it is circled with the
 * correct figure beside it; the model's verdict is a stamp (CAUGHT / MISSED / FALSE ALARM) with its own
 * corrections ticked or crossed. The ladder adds a months × staff calendar of every payslip.
 */
import { useMemo, useState } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, Fact, Impact, Paper, Stamp, Tick, type Tone } from '../kit.tsx';
import { gbp } from '../model/common.ts';
import { SLIP_FIELDS, SLIP_LABEL, gapText, payrollAnswer, payrollQuestion, payrollSentence, type PayrollAnswer, type PayrollQuestion, type SlipField, type SlipVerdict } from '../model/payroll.ts';
import './payroll.css';

type Show = 'draft' | 'key' | 'model';

function verdictStamp(v: SlipVerdict, show: Show) {
  if (show === 'draft') return null;
  if (show === 'key') return v.keyWrong ? <Stamp tone="bad">Bureau error</Stamp> : <Stamp tone="good">Correct ✓</Stamp>;
  if (v.flagged === null) return <Stamp tone="muted">No answer</Stamp>;
  if (v.keyWrong && v.flagged) return <Stamp tone={v.right ? 'good' : 'warn'} sub={v.right ? 'fixed to the penny' : 'a figure still wrong'}>Caught {v.right ? '✓' : '!'}</Stamp>;
  if (v.keyWrong) return <Stamp tone="bad" sub="passed a wrong payslip">Missed ✕</Stamp>;
  if (v.flagged) return <Stamp tone="bad" sub="this one was right">False alarm ✕</Stamp>;
  return <Stamp tone="good">Passed ✓</Stamp>;
}

/** One payslip: payments, deductions, net pay; circled where the draft is wrong. */
export function PayslipPaper({ v, show, compact }: { v: SlipVerdict; show: Show; compact?: boolean }) {
  const s = v.slip;
  const fig = (f: SlipField) => {
    const draft = s.draft[f];
    const wrong = show !== 'draft' && v.keyWrong && v.correct[f] !== null && draft !== null && Math.abs(v.correct[f]! - draft) >= 0.005;
    const given = show === 'model' && v.flagged && v.given ? v.given[f] : null;
    const ok = show === 'model' ? v.fieldOk[f] : null;
    return (
      <span className={cx('pm-fig', wrong && 'circled')}>
        <span className="draft">{gbp(draft).replace('£', '')}</span>
        {wrong && <span className="fix">{gbp(v.correct[f])}</span>}
        {show === 'model' && v.keyWrong && v.flagged && (wrong || ok === false) && (
          <span className={cx('given', ok ? 'ok' : 'no')}>
            <Tick ok={ok} /> {given === null ? 'not given' : gbp(given)}
          </span>
        )}
      </span>
    );
  };
  const row = (label: string, f: SlipField) => (
    <div className="pm-ps-row">
      <span>{label}</span>
      {fig(f)}
    </div>
  );
  return (
    <Paper className={cx('pm-ps', compact && 'compact', show !== 'draft' && v.keyWrong && 'has-err')} label={`Payslip of ${s.name}`}>
      <div className="pm-ps-head">
        <div>
          <small>{s.company}</small>
          <b>Payslip · {s.name}</b>
          <span>
            {s.id} · {s.period} · paid {s.payDate}
          </span>
        </div>
        <div className="pm-ps-codes">
          <span>
            Tax code <b>{s.taxCode}</b>
          </span>
          <span>
            NI <b>{s.niCat}</b>
          </span>
        </div>
      </div>
      <div className="pm-ps-body">
        <div className="pm-ps-col">
          <h6>Payments</h6>
          {s.payments.map((p) => (
            <div key={p.label} className="pm-ps-row">
              <span>{p.label}</span>
              <span className="pm-fig">
                <span className="draft">{gbp(p.amount).replace('£', '')}</span>
              </span>
            </div>
          ))}
          <div className="pm-ps-row total">
            <span>Gross pay</span>
            {fig('gross')}
          </div>
        </div>
        <div className="pm-ps-col">
          <h6>Deductions</h6>
          {row('PAYE tax', 'tax')}
          {row('Employee NI', 'ni')}
          {row('Pension', 'pension')}
          {row('Student loan', 'student_loan')}
        </div>
      </div>
      <div className="pm-ps-net">
        <span>Net pay</span>
        {fig('net')}
      </div>
      {!compact && <div className="pm-ps-ytd">{s.ytd}</div>}
      {show !== 'draft' && <div className="pm-ps-stamp">{verdictStamp(v, show)}</div>}
    </Paper>
  );
}

function cellTone(v: SlipVerdict, show: Show): string {
  if (show === 'draft') return 'plain';
  if (show === 'key') return v.keyWrong ? 'err' : 'ok';
  if (v.flagged === null) return 'plain';
  return v.right ? (v.keyWrong ? 'caught' : 'ok') : 'wrong';
}

/** Months × staff: one square per payslip. */
function Calendar({ q, a, show, sel, onSel }: { q: PayrollQuestion; a: PayrollAnswer; show: Show; sel: string; onSel: (k: string) => void }) {
  const staff = [...new Set(q.months.flatMap((m) => m.slips.map((s) => s.id)))];
  return (
    <div className="pm-scroll">
      <table className="pm-cal" aria-label="Every payslip, month by month">
        <thead>
          <tr>
            <th scope="col">Staff</th>
            {a.months.map((m) => (
              <th key={m.month} scope="col">
                {m.month.replace(/ \d{4}$/, '').slice(0, 3)}
                <small>{m.month.slice(-4)}</small>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {staff.map((id) => {
            const name = q.staff.find((x) => x.id === id)?.name ?? q.months.flatMap((m) => m.slips).find((s) => s.id === id)?.name ?? id;
            return (
              <tr key={id}>
                <th scope="row">
                  <b>{id}</b> {name}
                </th>
                {a.months.map((m) => {
                  const v = m.slips.find((x) => x.slip.id === id);
                  if (!v) return <td key={m.month} className="none" />;
                  const k = `${m.month}|${id}`;
                  const tone = cellTone(v, show);
                  return (
                    <td key={m.month}>
                      <button
                        type="button"
                        className={cx('pm-cal-c', tone, sel === k && 'on')}
                        aria-pressed={sel === k}
                        aria-label={`${name}, ${m.month}: ${v.keyWrong ? 'bureau draft wrong' : 'bureau draft right'}${show === 'model' ? (v.right ? ', model right' : ', model wrong') : ''}. Open the payslip.`}
                        onClick={() => onSel(k)}
                      >
                        {tone === 'caught' ? '✓' : tone === 'wrong' ? '✕' : tone === 'err' ? '!' : ''}
                      </button>
                    </td>
                  );
                })}
              </tr>
            );
          })}
          <tr className="ni">
            <th scope="row">Employer NI</th>
            {a.months.map((m) => (
              <td key={m.month} className={cx(show === 'model' && (m.niOk ? 'ok' : 'no'))}>
                {show === 'model' ? (m.niOk ? <Tick ok /> : <Tick ok={false} />) : show === 'key' ? gbp(m.keyNi, false) : ''}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
      <div className="pm-legend pm-cal-key">
        {show === 'model' ? (
          <>
            <span>
              <i className="pm-cal-c caught">✓</i> bureau error caught and fixed
            </span>
            <span>
              <i className="pm-cal-c ok" /> right payslip passed
            </span>
            <span>
              <i className="pm-cal-c wrong">✕</i> model wrong (missed, false alarm or a figure off)
            </span>
          </>
        ) : show === 'key' ? (
          <>
            <span>
              <i className="pm-cal-c err">!</i> bureau draft wrong
            </span>
            <span>
              <i className="pm-cal-c ok" /> draft right
            </span>
          </>
        ) : null}
      </div>
    </div>
  );
}

function SlipGrid({ a, show, max, sel, onSel }: { a: PayrollAnswer; show: Show; max: number; sel?: string; onSel?: (k: string) => void }) {
  const all = a.months[0]!.slips;
  // The interesting ones first: wrong drafts and the model's mistakes, then the rest.
  const order = [...all].sort((x, y) => Number(!x.right && show === 'model') * -2 + Number(x.keyWrong) * -1 - (Number(!y.right && show === 'model') * -2 + Number(y.keyWrong) * -1));
  const shown = show === 'draft' ? all.slice(0, max) : order.slice(0, max);
  return (
    <div className="pm-pslist">
      {shown.map((v) =>
        onSel ? (
          <button key={v.slip.id} type="button" className={cx('pm-ps-btn', sel === `${v.month}|${v.slip.id}` && 'on')} onClick={() => onSel(`${v.month}|${v.slip.id}`)} aria-label={`Payslip of ${v.slip.name}`}>
            <PayslipPaper v={v} show={show} compact />
          </button>
        ) : (
          <PayslipPaper key={v.slip.id} v={v} show={show} compact />
        ),
      )}
    </div>
  );
}

function impactOf(a: PayrollAnswer) {
  if (!a.answered) return null;
  const all = a.months.flatMap((m) => m.slips);
  const missed = all.filter((s) => s.keyWrong && !s.flagged && s.netGap !== null && Math.abs(s.netGap) >= 0.005);
  if (missed.length) {
    const under = missed.filter((s) => s.netGap! > 0).reduce((t, s) => t + s.netGap!, 0);
    const over = missed.filter((s) => s.netGap! < 0).reduce((t, s) => t - s.netGap!, 0);
    const first = missed[0]!;
    return (
      <Impact tone="bad">
        Let {missed.length === 1 ? `${first.slip.name}’s payslip` : `${missed.length} wrong payslips`} go through: {missed.length === 1 ? gapText(first.netGap!) : [under ? `${gbp(under)} underpaid` : '', over ? `${gbp(over)} overpaid` : ''].filter(Boolean).join(' and ')}
        {missed.length === 1 && a.months.length > 1 ? ` in ${first.month}` : ''}.
      </Impact>
    );
  }
  const ni = a.months.find((m) => m.niOk === false);
  if (ni && ni.modelNi !== null && ni.keyNi !== null)
    return (
      <Impact tone="warn">
        Employer NI for {ni.month} reported as {gbp(ni.modelNi)} instead of {gbp(ni.keyNi)}
        {ni.bureauNi !== null && Math.abs(ni.bureauNi - ni.modelNi) < 0.005 ? ' (the bureau’s own wrong total)' : ''}: the payment to HMRC would be off by {gbp(Math.abs(ni.modelNi - ni.keyNi))}.
      </Impact>
    );
  if (all.every((s) => s.right)) return <Impact tone="good">Every payslip would go out right: nobody underpaid, nobody overpaid.</Impact>;
  return null;
}

function Problems({ a }: { a: PayrollAnswer }) {
  const bad = a.months.flatMap((m) => m.slips).filter((s) => !s.right && s.flagged !== null);
  if (!bad.length) return null;
  return (
    <ul className="pm-problems">
      {bad.slice(0, 6).map((s) => (
        <li key={`${s.month}|${s.slip.id}`} className={s.keyWrong && s.flagged ? 't-warn' : 't-bad'}>
          <Tick ok={false} />
          <span>
            <span className="pm-tag">
              {s.slip.id} {s.slip.name}
              {a.months.length > 1 ? ` · ${s.month}` : ''}
            </span>
            {s.keyWrong && !s.flagged
              ? `Missed the bureau’s mistake${s.netGap ? `: ${s.slip.name.split(' ')[0]} would be ${gapText(s.netGap)}` : ''}.`
              : !s.keyWrong
                ? 'Flagged a payslip that was right.'
                : `Caught it, but ${SLIP_FIELDS.filter((f) => s.fieldOk[f] === false)
                    .map((f) => `${SLIP_LABEL[f].toLowerCase()} ${s.given?.[f] === null || s.given?.[f] === undefined ? 'missing' : gbp(s.given[f])} (correct ${gbp(s.correct[f])})`)
                    .join(', ')}.`}
          </span>
        </li>
      ))}
      {bad.length > 6 && <li className="pm-more">+ {bad.length - 6} more payslips with a mistake</li>}
    </ul>
  );
}

function Viewer({ q, a, show, sel, onSel }: { q: PayrollQuestion; a: PayrollAnswer; show: Show; sel: string; onSel: (k: string) => void }) {
  const [m, id] = sel.split('|');
  const v = a.months.find((x) => x.month === m)?.slips.find((x) => x.slip.id === id) ?? a.months[0]!.slips[0]!;
  if (q.ladder || a.months.length > 1)
    return (
      <div className="pm-calview">
        <Calendar q={q} a={a} show={show} sel={sel} onSel={onSel} />
        <PayslipPaper v={v} show={show} />
      </div>
    );
  return <SlipGrid a={a} show={show} max={60} sel={sel} onSel={onSel} />;
}

function defaultSel(a: PayrollAnswer, show: Show): string {
  const all = a.months.flatMap((m) => m.slips);
  const v = (show === 'model' ? all.find((s) => !s.right) : undefined) ?? all.find((s) => s.keyWrong) ?? all[0]!;
  return `${v.month}|${v.slip.id}`;
}

export function PayrollBeats(p: KindProps) {
  const q = useMemo(() => payrollQuestion(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyA = useMemo(() => (q ? payrollAnswer(q, p.q) : null), [q, p.q]);
  const a = useMemo(() => (q && p.a ? payrollAnswer(q, p.a) : null), [q, p.a]);
  const [qs, setQs] = useState<string | null>(null);
  const [ks, setKs] = useState<string | null>(null);
  const [as, setAs] = useState<string | null>(null);
  if (!q || !keyA) return <p className="lc-empty">These payslips could not be drawn.</p>;
  const n = q.months.reduce((t, m) => t + m.slips.length, 0);
  const wrongN = keyA.months.reduce((t, m) => t + m.slips.filter((s) => s.keyWrong).length, 0);
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={a ? payrollSentence(a) : null}
        impact={a ? impactOf(a) : null}
        wideAnswer
        question={{
          title: 'The question',
          sub: `${q.company}: ${n} draft payslips from the bureau${q.months.length > 1 ? ` over ${q.months.length} months (${q.months[0]!.month} – ${q.months[q.months.length - 1]!.month})` : ` for ${q.months[0]!.month}`}. Which are wrong, and what should they say?`,
          alt: `${q.staff.length} employees; the bureau's draft payslips${q.months.length > 1 ? ` for ${q.months.length} months` : ''}, each with gross pay, PAYE tax, employee NI, pension, student loan and net pay. Changes this month: ${q.months.flatMap((m) => m.changes).slice(0, 6).join('; ') || 'none'}.`,
          node: (
            <div className="pm-pile">
              {q.months[0]!.changes.length > 0 && (
                <div className="pm-changes">
                  <span className="pm-k">Changes {q.months.length > 1 ? `in ${q.months[0]!.month}` : 'this month'}</span>
                  {q.months[0]!.changes.slice(0, 8).map((c) => (
                    <span key={c}>{c}</span>
                  ))}
                </div>
              )}
              <Viewer q={q} a={keyA} show="draft" sel={qs ?? `${q.months[0]!.month}|${q.months[0]!.slips[0]!.id}`} onSel={setQs} />
            </div>
          ),
        }}
        keyBeat={{
          title: 'The correct payslips',
          sub: `${wrongN} of ${n} drafts are wrong; circled figures show the correct amount`,
          alt: keyA.months
            .flatMap((m) => m.slips.filter((s) => s.keyWrong).map((s) => `${m.month} ${s.slip.name}: net should be ${gbp(s.correct.net)} not ${gbp(s.slip.draft.net)}`))
            .join('. '),
          node: <Viewer q={q} a={keyA} show="key" sel={ks ?? defaultSel(keyA, 'key')} onSel={setKs} />,
        }}
        answer={
          a
            ? {
                title: 'The model’s answer',
                sub: a.answered ? 'Its verdict stamped on each payslip; circles mark the bureau’s wrong figures, with the model’s correction ticked or crossed' : 'No answer could be read from the reply',
                alt: a.months
                  .flatMap((m) => m.slips.filter((s) => !s.right).map((s) => `${m.month} ${s.slip.name}: ${s.keyWrong ? (s.flagged ? 'caught, a figure wrong' : 'missed') : 'false alarm'}`))
                  .join('. '),
                node: (
                  <div className="pm-pile">
                    <div className="pm-facts">
                      <Fact k="wrong drafts caught" v={`${a.months.flatMap((m) => m.slips).filter((s) => s.keyWrong && s.flagged).length}/${wrongN}`} tone={a.months.flatMap((m) => m.slips).every((s) => !s.keyWrong || s.flagged) ? 'good' : 'bad'} />
                      <Fact k="payslips fully right" v={`${a.months.flatMap((m) => m.slips).filter((s) => s.right).length}/${n}`} />
                      {a.months.length === 1 && <Fact k={`employer NI (correct ${gbp(a.months[0]!.keyNi)})`} v={gbp(a.months[0]!.modelNi)} tone={a.months[0]!.niOk ? 'good' : 'bad'} />}
                    </div>
                    <Viewer q={q} a={a} show="model" sel={as ?? defaultSel(a, 'model')} onSel={setAs} />
                  </div>
                ),
              }
            : null
        }
        result={a && a.months.some((m) => m.slips.some((s) => !s.right)) ? { title: 'The result', alt: 'The payslips the model got wrong.', node: <Problems a={a} /> } : null}
      />
    </div>
  );
}

export function PayrollInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const q = payrollQuestion(input.turns[0] ?? '');
  if (!q) return null;
  const a = payrollAnswer(q, input);
  const all = a.months.flatMap((m) => m.slips);
  const right = all.filter((s) => s.right).length;
  const tone: Tone = right === all.length ? 'good' : right >= all.length * 0.8 ? 'warn' : 'bad';
  return (
    <VizFrame
      mode={mode}
      tone={!a.answered ? 'bad' : tone === 'good' ? 'good' : tone === 'warn' ? 'half' : 'bad'}
      className="pm-inline"
      eyebrow={`Check the Payslips · ${q.company}`}
      headline={payrollSentence(a)}
      big={`${right}/${all.length}`}
      bigSub="payslips fully right"
      legend={[
        { tone: 'bad', label: 'Circled: the bureau’s wrong figure, correct one beside it' },
        { tone: 'good', label: 'Model’s correction right' },
      ]}
    >
      <div className="pm-doc-root pm-pile">
        {impactOf(a)}
        {a.months.length > 1 ? <Calendar q={q} a={a} show="model" sel="" onSel={() => undefined} /> : <SlipGrid a={a} show="model" max={mode === 'slide' ? 4 : 8} />}
      </div>
    </VizFrame>
  );
}
