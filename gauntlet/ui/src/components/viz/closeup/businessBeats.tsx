/** Business ladders (and the SQL ladder) as three beats: the pile of work handed over, the answer per record, what was wrong. */
import { useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { businessNoun, businessQuestion, businessSentence, businessVisual, humanize, sectionLabel, type BizWrongGroup, type BusinessQuestion, type BusinessVisual } from '../../../../../src/presenter/visuals/business.ts';
import { cx } from '../../ui.tsx';
import { HighlightedCode } from '../HighlightedCode.tsx';
import { BeatLayout, Chip, More, type Beat } from './parts.tsx';
import type { KindProps } from './types.ts';

const pct = (x: number) => Math.round(x * 100);

function Envelope({ q }: { q: BusinessQuestion }) {
  return (
    <div className="lc-envelope">
      <svg viewBox="0 0 24 24" aria-hidden="true" className="lc-env-ic">
        <path d="M3 6h18v12H3z M3 6l9 7 9-7" />
      </svg>
      <div>
        {q.subject && <b className="lc-env-subj">{q.subject}</b>}
        {q.from && <div className="lc-env-from">from {q.from.replace(/<[^>]+>/, '').trim()}</div>}
        <p className="lc-env-open">{q.opener}</p>
      </div>
    </div>
  );
}

function Piles({ q }: { q: BusinessQuestion }) {
  return (
    <div className={cx('lc-piles', q.piles.length > 1 && 'two')}>
      {q.piles.map((pile) => (
        <div key={pile.label} className="lc-pile">
          <div className="lc-pile-head">
            <span className="lc-stack" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            <b className="tnum">{pile.count.toLocaleString('en-GB')}</b> {pile.label.toLowerCase()}
          </div>
          <ul className="lc-cards">
            {pile.cards.map((c, i) => (
              <li key={c.id} className="lc-card" style={{ ['--i' as string]: i } as CSSProperties}>
                <span className="lc-card-id">{c.id}</span>
                {c.cells ? (
                  <span className="lc-card-cells">
                    {c.cells.map((x, k) => (
                      <span key={k}>{x}</span>
                    ))}
                  </span>
                ) : (
                  <span className="lc-card-main">
                    <span className="lc-card-t">{c.text}</span>
                    {c.sub && <span className="lc-card-sub">{c.sub}</span>}
                  </span>
                )}
              </li>
            ))}
          </ul>
          <More n={pile.count - pile.cards.length} noun={pile.noun} />
        </div>
      ))}
    </div>
  );
}

function HeatGrid({ v }: { v: BusinessVisual }) {
  const sections = v.sections.filter((s) => v.entities.some((e) => e.section === s.key));
  const loose = v.sections.filter((s) => !v.entities.some((e) => e.section === s.key));
  return (
    <div className="lc-heat">
      {sections.map((s) => {
        const ents = v.entities.filter((e) => e.section === s.key);
        return (
          <div key={s.key} className="lc-heat-sec">
            <div className="lc-k">
              {s.label} · {ents.length.toLocaleString('en-GB')}
            </div>
            <div className={cx('lc-heat-cells', ents.length > 160 && 'tiny', ents.length > 400 && 'micro')}>
              {ents.map((e) => (
                <span key={e.id} className={cx('lc-hc', e.missing ? 'miss' : e.wrong === 0 ? 'good' : e.wrong === e.fields ? 'bad' : 'half')} title={`${e.id}: ${e.wrong === 0 ? 'all right' : e.missing ? 'missing' : `${e.wrong} of ${e.fields} wrong`}`} />
              ))}
            </div>
          </div>
        );
      })}
      {loose.length > 0 && (
        <div className="lc-heat-loose">
          {loose.map((s) => (
            <Chip key={s.key} tone={s.wrong ? 'bad' : 'good'}>
              {s.wrong ? '✕' : '✓'} {s.label}
            </Chip>
          ))}
        </div>
      )}
      <div className="lc-tile-key">
        <span>
          <i className="lc-hc good" /> all right
        </span>
        <span>
          <i className="lc-hc half" /> partly wrong
        </span>
        <span>
          <i className="lc-hc bad" /> wrong
        </span>
        <span>
          <i className="lc-hc miss" /> left out
        </span>
      </div>
    </div>
  );
}

function SectionBars({ v }: { v: BusinessVisual }) {
  return (
    <div className="lc-secbars">
      {v.sections.map((s) => {
        const share = s.points ? s.earned / s.points : 0;
        return (
          <div key={s.key} className={cx('lc-secbar', share >= 0.9995 ? 'good' : share >= 0.9 ? 'warn' : 'bad')}>
            <span className="lc-secbar-l">{s.label}</span>
            <span className="lc-secbar-track">
              <i style={{ width: `${Math.max(1.5, share * 100)}%` }} />
            </span>
            <b className="tnum">{pct(share)}%</b>
          </div>
        );
      })}
    </div>
  );
}

function shown(x: string | null): string {
  if (x === null) return '—';
  if (x === 'null') return 'nothing';
  return x;
}

function WrongCards({ groups, max = 5 }: { groups: BizWrongGroup[]; max?: number }) {
  // Name the part of the answer only for records outside the main part ("Documents" under every DOC id is noise).
  const counts = new Map<string, number>();
  for (const g of groups) counts.set(g.section, (counts.get(g.section) ?? 0) + 1);
  const main = [...counts].sort((a, b) => b[1] - a[1])[0]?.[0];
  return (
    <>
      <ul className="lc-wrongs">
        {groups.slice(0, max).map((g) => (
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
            {g.fields.length > 4 && <div className="lc-more">+ {g.fields.length - 4} more fields</div>}
          </li>
        ))}
      </ul>
      <More n={groups.length - max} noun={groups.length - max === 1 ? 'record with a mistake' : 'records with mistakes'} />
    </>
  );
}

/** Counts of the answer key's categorical fields ("outcome: approve 31 · hold-price 11 …"). */
function keySummary(expected: unknown): Array<{ field: string; counts: Array<[string, number]> }> {
  const by = new Map<string, Map<string, number>>();
  const walk = (v: unknown, field: string) => {
    if (Array.isArray(v)) v.forEach((x) => walk(x, field));
    else if (v && typeof v === 'object') for (const [k, x] of Object.entries(v)) walk(x, k);
    else if (typeof v === 'string' && v.length <= 32 && !/^\d{4}-\d{2}-\d{2}/.test(v)) {
      const m = by.get(field) ?? new Map<string, number>();
      m.set(v, (m.get(v) ?? 0) + 1);
      by.set(field, m);
    }
  };
  walk(expected, '');
  return [...by]
    .filter(([, m]) => m.size >= 2 && m.size <= 12 && [...m.values()].reduce((a, b) => a + b, 0) >= 4)
    .slice(0, 3)
    .map(([field, m]) => ({ field, counts: [...m].sort((a, b) => b[1] - a[1]) }));
}

function CodeBox({ code, label }: { code: string; label: string }) {
  const [all, setAll] = useState(false);
  const lines = code.split('\n').length;
  return (
    <div className="lc-codebox" aria-label={label}>
      <HighlightedCode code={code} maxLines={all ? undefined : 18} />
      {lines > 18 && (
        <button type="button" className="btn xs ghost" onClick={() => setAll((a) => !a)} aria-expanded={all}>
          {all ? 'Show less' : `Show all ${lines} lines`}
        </button>
      )}
    </div>
  );
}

export function BusinessBeats(p: KindProps) {
  const sql = p.q.testId === 'work.sql-ladder';
  const q = useMemo(() => businessQuestion(p.q.testId, p.prompt), [p.q.testId, p.prompt]);
  const v = useMemo(() => {
    if (!p.a) return null;
    try {
      return businessVisual(p.a);
    } catch {
      return null;
    }
  }, [p.a]);
  const noun = businessNoun(p.q.testId);
  const rungChips = (p.rung ?? '').split(' · ').filter(Boolean);
  const question: Beat = sql
    ? {
        sub: rungChips.join(' · ') || 'One SQL query',
        alt: `${q.ask?.who ?? 'A colleague'} asks: “${q.ask?.quote ?? ''}” ${q.ask?.task ?? ''}`,
        node: (
          <div className="lc-sql-q">
            <div className="lc-speech">
              <span className="lc-speech-who">{q.ask?.who ?? 'A colleague'} asks</span>
              <p>“{q.ask?.quote}”</p>
            </div>
            <p className="lc-lead">{q.ask?.task}</p>
            <div className="lc-chiprow">
              {rungChips.map((c) => (
                <Chip key={c}>{c}</Chip>
              ))}
              <Chip tone="accent">one SQL query · checked on 4 databases</Chip>
            </div>
          </div>
        ),
      }
    : {
        sub: q.piles.length ? q.piles.map((x) => `${x.count.toLocaleString('en-GB')} ${x.noun}`).join(' · ') : undefined,
        alt: `${q.subject ? `An email: “${q.subject}”. ` : ''}${q.opener} The model was handed ${q.piles.map((x) => `${x.count} ${x.noun}`).join(' and ')}, and must give ${q.answerFields.join(', ')}.`,
        node: (
          <div className="lc-biz-q">
            <Envelope q={q} />
            <div className="lc-chiprow">
              {rungChips.map((c) => (
                <Chip key={c}>{c}</Chip>
              ))}
            </div>
            <Piles q={q} />
            {q.answerFields.length > 0 && (
              <div className="lc-fields">
                <span className="lc-k">It must answer with</span>
                {q.answerFields.map((f) => (
                  <Chip key={f} tone="muted">
                    {f}
                  </Chip>
                ))}
              </div>
            )}
          </div>
        ),
      };
  const keySum = useMemo(() => (sql ? [] : keySummary(p.q.expected)), [sql, p.q.expected]);
  const keyBeat: Beat | null = sql
    ? typeof (p.q.expected as { reference?: unknown } | undefined)?.reference === 'string'
      ? { sub: 'The reference query (checked by an independent solver)', alt: 'The reference SQL query.', node: <HighlightedCode code={(p.q.expected as { reference: string }).reference} maxLines={30} /> }
      : null
    : keySum.length
      ? {
          sub: 'What the answer key says, in counts',
          alt: keySum.map((k) => `${humanize(k.field)}: ${k.counts.map(([v, n]) => `${v} ${n}`).join(', ')}`).join('. '),
          node: (
            <div className="lc-keysum">
              {keySum.map((k) => (
                <div key={k.field} className="lc-keysum-f">
                  <div className="lc-k">{humanize(k.field)}</div>
                  <div className="lc-keysum-bars">
                    {k.counts.map(([val, n]) => (
                      <div key={val} className="lc-keysum-row">
                        <span>{val}</span>
                        <i style={{ width: `${(n / k.counts[0]![1]) * 100}%` }} />
                        <b className="tnum">{n}</b>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          ),
        }
      : null;
  let answer: Beat | null = null;
  let result: Beat | null = null;
  let sentence: string | null = null;
  let interactiveAnswer = null;
  if (p.a) {
    if (v && v.kind === 'sql') {
      answer = { sub: 'Its SQL query', alt: `The model wrote a ${v.query?.split('\n').length ?? 0}-line SQL query.`, node: <p className="lc-lead">The query it wrote, run on the sample database and three hidden copies:</p> };
      interactiveAnswer = v.query ? <CodeBox code={v.query} label="The model’s SQL query" /> : null;
    } else if (v) {
      const bad = v.entities.filter((e) => e.wrong > 0).length;
      answer = {
        sub: `${v.entities.length.toLocaleString('en-GB')} ${noun} in its answer · one square each`,
        alt: `The model’s answer covers ${v.entities.length} ${noun}; ${bad} have at least one wrong field.`,
        node: <HeatGrid v={v} />,
      };
    } else {
      answer = { alt: 'No answer we could read.', node: <p className="lc-empty">No JSON answer could be read from the reply.</p> };
    }
    if (v) {
      result = {
        alt: `${v.sections.map((s) => `${s.label}: ${pct(s.points ? s.earned / s.points : 0)}%`).join('; ')}. ${v.wrong
          .slice(0, 5)
          .map((g) => `${g.entity || g.section}: ${g.fields.map((f) => `${f.field} ${f.got ?? ''} should be ${f.expected ?? ''}`).join(', ')}`)
          .join('; ')}`,
        node: (
          <div className="lc-biz-res">
            <div className="lc-k">{v.kind === 'sql' ? 'Rows right, database by database' : 'Points right, part by part'}</div>
            <SectionBars v={v} />
            {v.wrong.length > 0 && v.kind !== 'sql' && (
              <>
                <div className="lc-k">Where it went wrong · given → correct</div>
                <WrongCards groups={v.wrong} />
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
        ),
      };
      sentence = businessSentence(v, p.a.score, p.passMark, noun);
    }
  }
  return (
    <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={result} verdict={p.verdict} sentence={sentence} answerHead={p.answerHead} keyBeat={keyBeat} interactiveAnswer={interactiveAnswer} />
  );
}
