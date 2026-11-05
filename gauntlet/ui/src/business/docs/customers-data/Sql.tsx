/**
 * Ask the Database drawn as a colleague's chat message, a small schema diagram (tables as boxes, join lines), the
 * model's query nicely set out, and its result table against the correct one with wrong rows marked, plus the
 * scorer's verdict on each database ("right on 3 of 4 databases").
 */
import type { CSSProperties, ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Impact, Paper, Tally, Tick, docVisual, type Tone } from './parts.tsx';
import { hueOf, initials, plural } from './kit.ts';
import { matchRows, parseSql, sqlAnswer, tablesIn, type Cell, type ResultTable, type SqlAnswer, type SqlDoc } from './sql.ts';
import { layoutSql, tokenizeSql } from './sqlExtract.ts';
import sqlResults from './sqlResults.json';
import './sheet.css';
import './sql.css';

function Chat({ doc }: { doc: SqlDoc }) {
  const name = doc.asker.split(/\s|\(/)[0] ?? doc.asker;
  return (
    <div className="cd-chat">
      <span className="cd-avatar" style={{ ['--h' as string]: hueOf(name) } as CSSProperties} aria-hidden="true">
        {initials(name)}
      </span>
      <div className="cd-bubble">
        <div className="cd-bubble-from">
          {doc.asker}
          {doc.company && <span className="muted"> · {doc.company}</span>}
        </div>
        <div className="cd-bubble-q">{doc.question}</div>
        <div className="cd-bubble-detail">{doc.detail}</div>
      </div>
    </div>
  );
}

const BOX_W = 168;
const ROW_H = 15;
const HEAD_H = 22;
const GAP_X = 46;
const GAP_Y = 30;
const PER_ROW = 3;
const MAX_COLS = 9;

function SchemaDiagram({ doc, sql }: { doc: SqlDoc; sql: string | null }) {
  const used = [...new Set([...tablesIn(doc.reference, doc.tables), ...(sql ? tablesIn(sql, doc.tables) : [])])];
  const tables = (used.length ? used : doc.tables.slice(0, 4).map((t) => t.name)).map((n) => doc.tables.find((t) => t.name === n)!).filter(Boolean).slice(0, 6);
  if (!tables.length) return null;
  const words = new Set(`${doc.reference} ${sql ?? ''}`.toLowerCase().match(/\w+/g) ?? []);
  const boxes = tables.map((t, i) => {
    const cols = [...t.columns.filter((c) => words.has(c.name.toLowerCase()) || c.ref), ...t.columns.filter((c) => !(words.has(c.name.toLowerCase()) || c.ref))].slice(0, MAX_COLS);
    return { t, cols, x: (i % PER_ROW) * (BOX_W + GAP_X), y: 0, h: HEAD_H + cols.length * ROW_H + 6, row: Math.floor(i / PER_ROW) };
  });
  const rowH = new Map<number, number>();
  for (const b of boxes) rowH.set(b.row, Math.max(rowH.get(b.row) ?? 0, b.h));
  let acc = 0;
  const rowY = new Map<number, number>();
  for (const [r, h] of [...rowH.entries()].sort((a, b) => a[0] - b[0])) {
    rowY.set(r, acc);
    acc += h + GAP_Y;
  }
  for (const b of boxes) b.y = rowY.get(b.row)!;
  const W = Math.min(PER_ROW, boxes.length) * BOX_W + (Math.min(PER_ROW, boxes.length) - 1) * GAP_X;
  const H = acc - GAP_Y;
  const lines: ReactNode[] = [];
  boxes.forEach((b) =>
    b.cols.forEach((c, ci) => {
      if (!c.ref) return;
      const to = boxes.find((x) => x.t.name === c.ref);
      if (!to || to === b) return;
      const y1 = b.y + HEAD_H + ci * ROW_H + ROW_H / 2;
      const x1 = to.x > b.x ? b.x + BOX_W : to.x < b.x ? b.x : b.x + BOX_W;
      const x2 = to.x > b.x ? to.x : to.x < b.x ? to.x + BOX_W : to.x + BOX_W;
      const y2 = to.y + HEAD_H / 2;
      const mx = to.x === b.x ? x1 + 22 : (x1 + x2) / 2;
      lines.push(<path key={`${b.t.name}.${c.name}`} className="cd-join" d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`} />);
    }),
  );
  return (
    <Paper className="cd-schema" title="The tables this question needs">
      <div className="cd-scroll">
        <svg viewBox={`-4 -4 ${W + 8} ${H + 8}`} width={W + 8} style={{ maxWidth: '100%', height: 'auto' }} role="img" aria-label={`Tables: ${tables.map((t) => t.name).join(', ')}`}>
          {boxes.map((b) => (
            <g key={b.t.name} transform={`translate(${b.x},${b.y})`}>
              <rect className="cd-tbox" width={BOX_W} height={b.h} rx="6" />
              <rect className="cd-thead" width={BOX_W} height={HEAD_H} rx="6" />
              <text className="cd-tname" x={8} y={15}>
                {b.t.name}
              </text>
              {b.cols.map((c, i) => (
                <text key={c.name} className={cx('cd-tcol', words.has(c.name.toLowerCase()) && 'used', c.ref && 'fk')} x={8} y={HEAD_H + i * ROW_H + 11}>
                  {c.ref ? '↗ ' : ''}
                  {c.name}
                </text>
              ))}
              {b.t.columns.length > b.cols.length && (
                <text className="cd-tcol more" x={BOX_W - 8} y={b.h - 6} textAnchor="end">
                  +{b.t.columns.length - b.cols.length}
                </text>
              )}
            </g>
          ))}
          {lines}
        </svg>
      </div>
    </Paper>
  );
}

function Query({ sql, title }: { sql: string; title: string }) {
  const toks = tokenizeSql(layoutSql(sql).replace(/\t/g, '  '));
  return (
    <Paper className="cd-query" title={title}>
      <pre className="cd-sql">
        {toks.map((t, i) => (t.k === 'ws' || t.k === 'id' ? <span key={i}>{t.v}</span> : <span key={i} className={`s-${t.k}`}>{t.v}</span>))}
      </pre>
    </Paper>
  );
}

const fmt = (v: Cell) => (v === null ? 'NULL' : typeof v === 'number' ? (Number.isInteger(v) ? v.toLocaleString('en-GB') : v.toLocaleString('en-GB', { maximumFractionDigits: 2 })) : v);

function ResultGrid({ t, title, ok, missing }: { t: ResultTable; title: string; ok?: boolean[]; missing?: number[] }) {
  return (
    <div className="cd-res">
      <div className="cd-k">{title}</div>
      <div className="cd-scroll">
        <table className="cd-grid">
          <thead>
            <tr>
              {t.columns.map((c) => (
                <th key={c}>{c}</th>
              ))}
              {ok && <th />}
            </tr>
          </thead>
          <tbody>
            {t.rows.map((r, i) => (
              <tr key={i} className={cx(ok && (ok[i] ? 'r-ok' : 'r-bad'), missing?.includes(i) && 'r-miss')}>
                {r.map((v, j) => (
                  <td key={j} className={cx(typeof v === 'number' && 'num')}>
                    {fmt(v)}
                  </td>
                ))}
                {ok && (
                  <td>
                    <Tick ok={ok[i]!} />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {t.total > t.rows.length && <div className="cd-more">+ {plural(t.total - t.rows.length, 'more row')}</div>}
    </div>
  );
}

function Results({ doc, ans }: { doc: SqlDoc; ans: SqlAnswer | null }) {
  if (!doc.correct) return null;
  if (!ans?.rows) return <ResultGrid t={doc.correct} title="Correct result on the live database" />;
  const { gotOk, missing } = matchRows(doc.correct.rows, ans.rows.rows);
  return (
    <div className="cd-results">
      <ResultGrid t={ans.rows} title="The model’s result (live database)" ok={gotOk} />
      <ResultGrid t={doc.correct} title="Correct result" missing={missing} />
    </div>
  );
}

function Dbs({ ans }: { ans: SqlAnswer }) {
  return (
    <div className="cd-dbs">
      {ans.dbs.map((d) => (
        <div key={d.label} className={cx('cd-db', d.passed ? 't-good' : 't-bad')}>
          <span className="cd-db-icon" aria-hidden="true" />
          <div>
            <b>{d.label.replace(' (sampled in the prompt)', '')}</b>
            <div className="cd-small">{d.detail}</div>
          </div>
          <Tick ok={d.passed} />
        </div>
      ))}
    </div>
  );
}

const sqlSpec = () =>
  docVisual<SqlDoc, SqlAnswer>({
    family: 'business',
    caption: 'The colleague’s question, the tables it needs, the model’s query and its result against the correct one, and the verdict on each of the four databases it was run on.',
    doc: (input) => parseSql(input, sqlResults),
    answer: (input) => sqlAnswer(input),
    sub: (doc) => `From ${doc.asker} · ${plural(doc.tables.length, 'table')} in the database`,
    Question: ({ doc }) => (
      <>
        <Chat doc={doc} />
        <SchemaDiagram doc={doc} sql={null} />
      </>
    ),
    Key: ({ doc }) => (
      <>
        <Query sql={doc.reference} title="A correct query" />
        <Results doc={doc} ans={null} />
      </>
    ),
    Answer: ({ doc, ans }) => (
      <>
        {ans.sql ? <Query sql={ans.sql} title="The model’s query" /> : <p className="cd-empty">No query found in the reply.</p>}
        <Results doc={doc} ans={ans} />
        {!ans.rows && <p className="cd-why">The model’s own result rows are not stored for real runs: the scorer keeps its verdict on each database, below.</p>}
      </>
    ),
    Result: ({ doc, ans }) => {
      const lines: Array<{ tone: Tone; text: ReactNode }> = [];
      if (ans.right < ans.dbs.length && doc.slips.length) lines.push({ tone: 'warn', text: <>The usual slips on this question: {doc.slips.slice(0, 3).join('; ')}.</> });
      if (ans.right < ans.dbs.length) lines.push({ tone: 'bad', text: <>A report built on this query would show {ans.right === 0 ? 'wrong figures' : 'figures that are right on some data and wrong on other data'}.</> });
      return (
        <>
          <Tally items={[{ n: ans.right, of: ans.dbs.length || 4, label: 'databases exactly right' }]} />
          <Dbs ans={ans} />
          <Impact lines={lines} />
        </>
      );
    },
    sentence: (_doc, ans) =>
      ans.right === ans.dbs.length
        ? `Right on all ${ans.dbs.length} databases, including the hidden copies it never saw: the query really answers the question.`
        : `Right on ${ans.right} of ${ans.dbs.length} databases${ans.right > 0 ? ': it matched some data but not the rules behind it' : ''}.`,
    headline: (_doc, ans) => `Right on ${ans.right} of ${ans.dbs.length} databases`,
    alt: {
      question: (doc) => `${doc.asker} asks: “${doc.question}” The database has tables ${doc.tables.map((t) => t.name).join(', ')}.`,
      key: (doc) => `A correct query reads ${tablesIn(doc.reference, doc.tables).join(', ')}; the correct result has ${doc.correct?.total ?? '?'} rows.`,
      answer: (_doc, ans) => `The model’s query was right on ${ans.right} of ${ans.dbs.length} databases: ${ans.dbs.map((d) => `${d.label} ${d.passed ? 'right' : `wrong (${d.detail})`}`).join('; ')}.`,
    },
  });

export const SQL_QUESTIONS = sqlSpec();
export const SQL_HARD = sqlSpec();
export const SQL_LADDER = sqlSpec();
