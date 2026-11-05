/**
 * Read the Spreadsheet drawn as a spreadsheet: column letters, row numbers, the cells each question needs
 * highlighted in that question's colour, the sheet's traps marked (a repeated row, a subtotal row, a correction),
 * and each answer of the model against the key — with the shortcut it took when its answer matches a known one.
 */
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Fix, Impact, Paper, Tally, Tick, docVisual, type Tone } from './parts.tsx';
import { money, plural, show } from './kit.ts';
import { parseSheet, sheetAnswer, type SheetDoc, type SheetMark } from './sheet.ts';
import './crm.css';
import './sheet.css';

const Q_HUES = [190, 40, 300, 140, 0];
const MAX_ROWS = 34;
const TRAP: Record<string, string> = { repeat: 'repeat — count once', subtotal: 'subtotal — not an order', correction: 'correction — replaces the earlier row', replaced: 'replaced by a correction below' };

function fmtAnswer(q: string, v: unknown): string {
  if (typeof v === 'number' && /£|value|average|net sales|Total/.test(q)) return money(v);
  return show(v);
}

function Grid({ doc }: { doc: SheetDoc }) {
  const read = new Map<number, number[]>();
  doc.questions.forEach((q, qi) => q.rows.forEach((n) => read.set(n, [...(read.get(n) ?? []), qi])));
  const want = new Set<number>();
  for (const r of doc.rows) if (r.trap) want.add(r.n);
  // Each question's rows, a fair share each, up to the cap.
  const per = Math.max(4, Math.floor(MAX_ROWS / Math.max(1, doc.questions.length)));
  for (const q of doc.questions) q.rows.slice(0, per).forEach((n) => want.add(n));
  const shown = doc.rows.filter((r) => want.has(r.n)).slice(0, MAX_ROWS + 6);
  const letters = doc.header.map((_, i) => String.fromCharCode(65 + i));
  const out: ReactNode[] = [];
  let prev = 1;
  for (const r of shown) {
    if (r.n > prev + 1)
      out.push(
        <tr key={`gap${r.n}`} className="cd-gap">
          <td className="cd-rn">⋮</td>
          <td colSpan={doc.header.length + 1}>{plural(r.n - prev - 1, 'row')} not needed</td>
        </tr>,
      );
    const qs = read.get(r.n) ?? [];
    out.push(
      <tr key={r.n} className={cx(r.trap && `trap-${r.trap}`, qs.length > 0 && 'read')}>
        <td className="cd-rn">{r.n}</td>
        {r.cells.map((c, ci) => {
          const q = qs.find((qi) => doc.questions[qi]!.col === ci);
          return (
            <td key={ci} className={cx(q !== undefined && 'hit', /Total|Qty|Unit/.test(doc.header[ci] ?? '') && 'num')} style={q !== undefined ? ({ ['--h' as string]: Q_HUES[q % Q_HUES.length] } as CSSProperties) : undefined}>
              {c}
            </td>
          );
        })}
        <td className="cd-trapcell">
          {qs.map((qi) => (
            <span key={qi} className="cd-qdot" style={{ ['--h' as string]: Q_HUES[qi % Q_HUES.length] } as CSSProperties}>
              {doc.questions[qi]!.id}
            </span>
          ))}
          {r.trap && <span className={cx('cd-trap', r.trap)}>{TRAP[r.trap]}</span>}
        </td>
      </tr>,
    );
    prev = r.n;
  }
  const last = doc.rows[doc.rows.length - 1]?.n ?? prev;
  if (last > prev)
    out.push(
      <tr key="tail" className="cd-gap">
        <td className="cd-rn">⋮</td>
        <td colSpan={doc.header.length + 1}>{plural(last - prev, 'more row')}</td>
      </tr>,
    );
  return (
    <Paper className="cd-sheet">
      <div className="cd-sheet-bar">
        <span className="cd-sheet-icon" aria-hidden="true" />
        <b>sales_orders.csv</b>
        <span className="muted">
          {plural(doc.rows.filter((r) => r.trap !== 'subtotal').length, 'row')} · only the rows the questions need are shown
        </span>
      </div>
      <div className="cd-scroll">
        <table className="cd-grid cd-xls">
          <thead>
            <tr>
              <th className="cd-rn" />
              {letters.map((l) => (
                <th key={l}>{l}</th>
              ))}
              <th />
            </tr>
            <tr className="cd-hdr">
              <td className="cd-rn">1</td>
              {doc.header.map((h) => (
                <td key={h}>{h}</td>
              ))}
              <td />
            </tr>
          </thead>
          <tbody>{out}</tbody>
        </table>
      </div>
    </Paper>
  );
}

function Questions({ doc, marks }: { doc: SheetDoc; marks: SheetMark[] | null }) {
  return (
    <Paper className="cd-qs" title={marks ? 'The model’s answers' : 'The questions'}>
      <ol>
        {doc.questions.map((q, i) => {
          const m = marks?.find((x) => x.id === q.id);
          return (
            <li key={q.id} className={cx(m && (m.ok ? 't-good' : 't-bad'))}>
              <span className="cd-qdot" style={{ ['--h' as string]: Q_HUES[i % Q_HUES.length] } as CSSProperties}>
                {q.id}
              </span>
              <div>
                <div>{q.text}</div>
                {m ? (
                  <div className="cd-ans">
                    <b className={cx(!m.ok && 'no')}>{fmtAnswer(q.text, m.model)}</b> <Tick ok={m.ok} />
                    {!m.ok && <Fix>{fmtAnswer(q.text, m.key)}</Fix>}
                    {m.trap && <div className="cd-why">The model {m.trap}.</div>}
                  </div>
                ) : marks === null && q.key !== undefined ? null : null}
                <div className="muted cd-small">reads {plural(q.rows.length, 'row')}</div>
              </div>
            </li>
          );
        })}
      </ol>
    </Paper>
  );
}

export const SHEET_LOOKUP = docVisual<SheetDoc, SheetMark[]>({
  family: 'extraction',
  caption: 'The spreadsheet with the cells each question needs highlighted in its colour and the traps marked; the model’s answers ticked or crossed against the key.',
  doc: (input) => parseSheet(input.turns[0] ?? '', input.expected, input.notes),
  answer: (input, doc) => sheetAnswer(input, doc),
  sub: (doc) => `${plural(doc.rows.length, 'row')} · ${plural(doc.questions.length, 'question')} · ${plural(doc.rows.filter((r) => r.trap).length, 'trap row')}`,
  Question: ({ doc }) => (
    <>
      <Questions doc={doc} marks={null} />
      <Grid doc={doc} />
    </>
  ),
  Key: ({ doc }) => (
    <Paper className="cd-qs" title="The answers">
      <ol>
        {doc.questions.map((q) => (
          <li key={q.id}>
            <b>{q.id}</b> {fmtAnswer(q.text, q.key)}
          </li>
        ))}
      </ol>
    </Paper>
  ),
  Answer: ({ doc, ans }) => (
    <>
      <Questions doc={doc} marks={ans} />
      <Grid doc={doc} />
    </>
  ),
  Result: ({ doc, ans }) => {
    const lines: Array<{ tone: Tone; text: ReactNode }> = [];
    for (const m of ans.filter((x) => !x.ok)) {
      const q = doc.questions.find((x) => x.id === m.id)!;
      const diff = typeof m.model === 'number' && typeof m.key === 'number' && /£|value|average|net sales/.test(q.text) ? ` (${money(Math.abs(m.model - m.key))} ${m.model > m.key ? 'too high' : 'too low'})` : '';
      lines.push({ tone: 'bad', text: <>{m.id}: reported {fmtAnswer(q.text, m.model)}, the sheet says {fmtAnswer(q.text, m.key)}{diff}.</> });
    }
    return (
      <>
        <Tally items={[{ n: ans.filter((m) => m.ok).length, of: ans.length, label: 'answers right' }]} />
        <Impact lines={lines} />
      </>
    );
  },
  sentence: (doc, ans) => {
    const wrong = ans.filter((m) => !m.ok);
    if (!wrong.length) return `Answered all ${doc.questions.length} questions exactly, dodging the repeated, subtotal and corrected rows.`;
    const t = wrong.find((m) => m.trap);
    return `Got ${ans.length - wrong.length} of ${ans.length} answers right${t ? `; on ${t.id} it ${t.trap}` : ''}. A report built on this would carry the wrong figure.`;
  },
  headline: (_doc, ans) => `${ans.filter((m) => m.ok).length} of ${ans.length} answers right`,
  alt: {
    question: (doc) => `A sales-orders spreadsheet of ${doc.rows.length} rows and ${doc.questions.length} questions: ${doc.questions.map((q) => q.text).join(' ')}`,
    key: (doc) => doc.questions.map((q) => `${q.id}: ${fmtAnswer(q.text, q.key)}`).join('; '),
    answer: (doc, ans) => ans.map((m) => `${m.id}: ${fmtAnswer(doc.questions.find((q) => q.id === m.id)!.text, m.model)} (${m.ok ? 'right' : 'wrong'})`).join('; '),
  },
});
