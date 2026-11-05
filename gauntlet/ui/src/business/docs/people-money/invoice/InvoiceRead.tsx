/**
 * Read an Invoice (work.invoice-read): the supplier's invoice as a document (letterhead, references, lines,
 * totals, amendments, bank details) beside the accounts-system form. Traps the case notes name are tagged on the
 * invoice; in the answer, numbered highlighter marks show where each box comes from, green right, red wrong.
 */
import { useMemo } from 'react';
import type { ReactNode } from 'react';
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import type { KindProps } from '../../../../components/viz/closeup/types.ts';
import { VizFrame, type VizMode } from '../../../../components/viz/VizFrame.tsx';
import { cx } from '../../../../components/ui.tsx';
import { DocBeats, Hl, Impact, Paper, Pin, type Tone } from '../kit.tsx';
import { FieldForm, Pins, marksFrom, type FormRow, type Marks } from '../forms.tsx';
import { INV_FIELDS, invValueText, invoiceDoc, invoiceRows, invoiceSentence, invoiceSources, type InvRow, type InvoiceDoc } from '../model/invoiceRead.ts';
import { gbp } from '../model/common.ts';
import './invoice.css';

const HINTS: Record<string, string> = {
  supplier: 'as registered',
  invoice_number: 'the supplier’s number',
  invoice_date: 'YYYY-MM-DD',
  due_date: 'stated, or from the terms',
  gross: 'this invoice only',
  supplier_vat_number: 'GB…',
  po_reference: 'or none',
  sort_code: 'the account to pay now',
};

function formRows(rows: InvRow[]): FormRow[] {
  return rows.map((r) => ({ n: r.n, label: r.label, got: invValueText(r.path, r.got), want: invValueText(r.path, r.want), ok: r.ok, why: r.why, hint: HINTS[r.path] ?? '' }));
}

export function InvoicePaper({ doc, marks, showTraps }: { doc: InvoiceDoc; marks?: Marks; showTraps?: boolean }) {
  const mark = (i: number, body: ReactNode) => {
    const m = marks?.get(i);
    return m ? (
      <Hl tone={m.tone}>
        <Pins pins={m.pins} />
        {body}
      </Hl>
    ) : (
      body
    );
  };
  const trap = (t: string | null) => (showTraps && t ? <span className="pm-trap">trap: {t}</span> : null);
  const head = doc.lines.filter((l) => l.kind === 'letterhead');
  const tableHead = doc.lines.find((l) => l.kind === 'table-head');
  const cols = tableHead ? tableHead.text.split('|').map((x) => x.trim()) : [];
  const out: ReactNode[] = [];
  let items: ReactNode[] = [];
  const flush = (k: number) => {
    if (!items.length) return;
    out.push(
      <table key={`t${k}`} className="pm-inv-table">
        <thead>
          <tr>
            {cols.map((c, i) => (
              <th key={i}>{c}</th>
            ))}
          </tr>
        </thead>
        <tbody>{items}</tbody>
      </table>,
    );
    items = [];
  };
  for (const l of doc.lines) {
    if (l.kind === 'letterhead' || l.kind === 'table-head') continue;
    if (l.kind === 'item') {
      const cells = l.text.split('|').map((x) => x.trim());
      const m = marks?.get(l.i);
      items.push(
        <tr key={l.i} className={cx(m && `hl-${m.tone}`)}>
          {cells.map((c, j) => (
            <td key={j}>{j === 0 && m ? <Pins pins={m.pins} /> : null}{c}</td>
          ))}
        </tr>,
      );
      continue;
    }
    flush(l.i);
    if (l.kind === 'blank') {
      out.push(<div key={l.i} className="pm-inv-gap" />);
      continue;
    }
    out.push(
      <p key={l.i} className={cx('pm-inv-line', `k-${l.kind}`)}>
        {mark(l.i, l.text)}
        {trap(l.trap)}
      </p>,
    );
  }
  flush(-1);
  return (
    <Paper className="pm-inv" label="The supplier’s invoice">
      <div className="pm-inv-letterhead">
        <b>{mark(0, head[0]?.text ?? '')}</b>
        {head.slice(1).map((l) => (
          <span key={l.i}>
            {mark(l.i, l.text)}
            {trap(l.trap)}
          </span>
        ))}
      </div>
      {out}
    </Paper>
  );
}

function marksFor(doc: InvoiceDoc, rows: InvRow[] | null): Marks {
  const src = invoiceSources(doc);
  return marksFrom(
    src,
    INV_FIELDS.map((f) => f.path),
    (path): Tone => (rows ? (rows.find((r) => r.path === path)?.ok === false ? 'bad' : 'good') : 'key'),
  );
}

function impactOf(rows: InvRow[], answered: boolean) {
  if (!answered) return null;
  const by = (p: string) => rows.find((r) => r.path === p);
  const gross = by('gross');
  const sort = by('sort_code');
  if (sort?.ok === false || by('account_number')?.ok === false)
    return <Impact tone="bad">Would have sent the payment to {String(sort?.got ?? '?')} / {String(by('account_number')?.got ?? '?')}: not the account the supplier asked to be paid into.</Impact>;
  if (gross?.ok === false && typeof gross.got === 'number' && typeof gross.want === 'number') {
    const d = gross.got - gross.want;
    return (
      <Impact tone="bad">
        Would have recorded {gbp(gross.got)} to pay instead of {gbp(gross.want)}: {d > 0 ? `${gbp(d)} too much` : `${gbp(-d)} too little`}.
      </Impact>
    );
  }
  if (rows.every((r) => r.ok)) return <Impact tone="good">Every box matches: this invoice could go straight into the accounts system.</Impact>;
  return null;
}

export function InvoiceReadBeats(p: KindProps) {
  const doc = useMemo(() => invoiceDoc(p.prompt || p.q.turns[0] || ''), [p.prompt, p.q]);
  const keyRows = useMemo(() => (doc ? invoiceRows(doc, p.q).rows : null), [doc, p.q]);
  const res = useMemo(() => (doc && p.a ? invoiceRows(doc, p.a) : null), [doc, p.a]);
  if (!doc || !keyRows) return <p className="lc-empty">This invoice could not be drawn.</p>;
  const traps = doc.lines.filter((l) => l.trap);
  const wrong = res?.rows.filter((r) => r.ok === false) ?? [];
  return (
    <div className="pm-closeup">
      <DocBeats
        prompt={p.prompt}
        reply={p.reply}
        answerHead={p.answerHead}
        verdict={p.verdict}
        sentence={res ? invoiceSentence(res.rows, res.answered) : null}
        impact={res ? impactOf(res.rows, res.answered) : null}
        question={{
          title: 'The question',
          sub: `A supplier invoice for ${doc.company}, copied from the PDF, and the ${INV_FIELDS.length}-box form for the accounts system`,
          alt: `A supplier invoice: ${doc.lines
            .filter((l) => l.kind !== 'item' && l.kind !== 'blank')
            .slice(0, 12)
            .map((l) => l.text)
            .join('; ')}. ${traps.length ? `Traps marked: ${traps.map((t) => t.trap).join(', ')}.` : ''}`,
          node: (
            <div className="pm-cvq">
              <FieldForm title="Accounts system · new supplier invoice" sub="Fill in from the invoice" rows={formRows(keyRows)} show="blank" />
              <InvoicePaper doc={doc} showTraps />
            </div>
          ),
        }}
        keyBeat={{
          title: 'The correct form',
          sub: 'Yellow marks show where each box comes from',
          alt: keyRows.map((r) => `${r.label}: ${invValueText(r.path, r.want)}`).join('. '),
          node: (
            <div className="pm-cvq">
              <FieldForm title="Accounts system · new supplier invoice" rows={formRows(keyRows)} show="key" />
              <InvoicePaper doc={doc} marks={marksFor(doc, null)} showTraps />
            </div>
          ),
        }}
        answer={
          res
            ? {
                title: 'The model’s answer',
                sub: res.answered ? 'The form as it filled it in; numbered marks on the invoice show where each box comes from' : 'No form could be read from the reply',
                alt: res.rows.map((r) => `${r.label}: ${invValueText(r.path, r.got)}${r.ok === false ? `, wrong; correct ${invValueText(r.path, r.want)}` : ''}`).join('. '),
                node: (
                  <div className="pm-cvq">
                    <FieldForm title="Accounts system · new supplier invoice" rows={formRows(res.rows)} show="model" />
                    <InvoicePaper doc={doc} marks={marksFor(doc, res.rows)} showTraps />
                  </div>
                ),
              }
            : null
        }
        result={
          res && wrong.length
            ? {
                title: 'The result',
                alt: wrong.map((r) => `${r.label}: gave ${invValueText(r.path, r.got)}, correct ${invValueText(r.path, r.want)}. ${r.why ?? ''}`).join(' '),
                node: (
                  <ul className="pm-problems">
                    {wrong.map((r) => (
                      <li key={r.path} className="t-bad">
                        <Pin tone="bad" n={r.n} />
                        <span>
                          <span className="pm-tag">{r.label}</span>
                          Gave <b>{invValueText(r.path, r.got)}</b>; correct: <b>{invValueText(r.path, r.want)}</b>. {r.why}
                        </span>
                      </li>
                    ))}
                  </ul>
                ),
              }
            : null
        }
      />
    </div>
  );
}

export function InvoiceReadInline({ input, mode }: { input: CaseVisualInput; mode: VizMode }) {
  const doc = invoiceDoc(input.turns[0] ?? '');
  if (!doc) return null;
  const { rows, answered } = invoiceRows(doc, input);
  const right = rows.filter((r) => r.ok).length;
  return (
    <VizFrame
      mode={mode}
      tone={!answered ? 'bad' : right === rows.length ? 'good' : right >= rows.length - 3 ? 'half' : 'bad'}
      className="pm-inline"
      eyebrow="Read an Invoice · the accounts-system form"
      headline={invoiceSentence(rows, answered)}
      big={`${right}/${rows.length}`}
      bigSub="boxes right"
      legend={[
        { tone: 'good', label: 'Box right · where it comes from' },
        { tone: 'bad', label: 'Box wrong · struck out, correct value beside it' },
      ]}
    >
      <div className="pm-doc-root pm-pile">
        {impactOf(rows, answered)}
        <div className="pm-cvq">
          <FieldForm title="Accounts system · new supplier invoice" rows={formRows(rows)} show="model" />
          <InvoicePaper doc={doc} marks={marksFor(doc, rows)} showTraps />
        </div>
      </div>
    </VizFrame>
  );
}

