/**
 * What Changed in the Contract? drawn as a lawyer's redline: version 2 against version 1 with deletions struck
 * through in red and insertions in green, and the model's list of changes laid over the clauses — found, a wrong
 * detail, missed, or invented (a change that is only presentation, or not agreed on the ladder).
 */
import type { ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Impact, MiniWall, MoreLine, Paper, Tally, Tick, docVisual, type MiniItem, type Tone } from './parts.tsx';
import { plural, show } from './kit.ts';
import { changedClauses, clauseView, contractAnswer, describe, parseContract, type Change, type ChangeMark, type ClauseView, type ContractAnswer, type ContractDoc } from './contract.ts';
import './contract.css';

function Redline({ v }: { v: ClauseView }) {
  return (
    <span className="cd-redline">
      {v.pieces.map((p, i) =>
        p.op === 'same' ? (
          <span key={i}>{p.t}</span>
        ) : p.op === 'del' ? (
          <del key={i}>{p.t}</del>
        ) : (
          <ins key={i}>{p.t}</ins>
        ),
      )}
    </span>
  );
}

function ClauseLine({ v, children, tone }: { v: ClauseView; children?: ReactNode; tone?: Tone }) {
  return (
    <div className={cx('cd-clause', tone && `t-${tone}`)}>
      <span className="cd-clause-no">{v.no}</span>
      <div className="cd-clause-body">
        {v.v1 && v.v2 && v.v1.no !== v.v2.no && <span className="cd-moved">was {v.v1.no}</span>}
        <Redline v={v} />
        {children}
      </div>
    </div>
  );
}

const SHOW = 12;

function Talk({ doc, no }: { doc: ContractDoc; no: string }) {
  const t = doc.talk[no.toLowerCase().replace(/[\s_-]+/g, '')];
  if (!doc.ladder || !t?.length) return null;
  return (
    <ul className="cd-talk">
      {t.slice(0, 3).map((x, i) => (
        <li key={i}>{x}</li>
      ))}
    </ul>
  );
}

function Pages({ doc }: { doc: ContractDoc }) {
  const changed = changedClauses(doc);
  return (
    <Paper className="cd-contract">
      <div className="cd-contract-title">{doc.title2.replace(/\s*—\s*version 2.*$/i, '')}</div>
      <div className="cd-contract-sub">Version 2 marked up against version 1 · {plural(changed.length, 'clause')} with different wording out of {doc.v2.length}</div>
      {changed.slice(0, SHOW).map((v) => (
        <ClauseLine key={v.no + (v.v2 ? '' : '-del')} v={v} />
      ))}
      <MoreLine n={changed.length - SHOW} noun="marked-up clauses" />
      {doc.ladder && <p className="cd-why">Then the customer answered version 2 and the supplier sent a final note: only what both agreed counts.</p>}
    </Paper>
  );
}

function stateFlag(m: ChangeMark): { label: string; tone: Tone } {
  switch (m.state) {
    case 'right':
      return { label: 'found', tone: 'good' };
    case 'wrong':
      return { label: 'wrong detail', tone: 'warn' };
    case 'missed':
      return { label: 'missed', tone: 'bad' };
    default:
      return { label: 'invented', tone: 'bad' };
  }
}

function claim(c: Change | null): string {
  if (!c) return '—';
  return `${c.type || '?'}${c.old !== null || c.new !== null ? `: ${show(c.old)} → ${show(c.new)}` : ''}`;
}

function inventedWhy(doc: ContractDoc, m: ChangeMark): string {
  const v = clauseView(doc, m.no);
  if (doc.ladder) return v?.changed ? 'Changed in version 2, but not in the agreed version (rejected, withdrawn or only presentation).' : 'This clause’s wording is the same in the agreed version.';
  if (!v) return 'There is no such clause.';
  return v.changed ? 'Only the presentation changed here (numbering, a number written differently, a moved clause): not substantive.' : 'This clause’s wording did not change.';
}

function MarkCard({ doc, m }: { doc: ContractDoc; m: ChangeMark }) {
  const v = clauseView(doc, m.no);
  const f = stateFlag(m);
  return (
    <div className={cx('cd-chg', `t-${f.tone}`)}>
      <div className="cd-chg-head">
        <b>Clause {m.no}</b>
        <span className={cx('cd-flag', `t-${f.tone === 'good' ? 'good' : f.tone === 'warn' ? 'warn' : 'bad'}`)}>{f.label}</span>
        <span className="spacer" />
        <Tick ok={m.state === 'right'} />
      </div>
      {v && (
        <div className="cd-chg-text">
          <Redline v={v} />
        </div>
      )}
      <div className="cd-chg-claims">
        {m.model && (
          <span className={cx('cd-claim', m.state === 'right' ? 'ok' : 'no')}>
            <span className="cd-k">Model</span> {claim(m.model)}
          </span>
        )}
        {m.key && m.state !== 'right' && (
          <span className="cd-claim key">
            <span className="cd-k">Correct</span> {describe(m.key)}
          </span>
        )}
        {m.state === 'right' && m.key && <span className="muted">{describe(m.key)}</span>}
      </div>
      {m.state === 'invented' && <p className="cd-why">{inventedWhy(doc, m)}</p>}
      <Talk doc={doc} no={m.no} />
    </div>
  );
}

function marksToShow(ans: ContractAnswer): ChangeMark[] {
  const bad = ans.marks.filter((m) => m.state !== 'right');
  const good = ans.marks.filter((m) => m.state === 'right');
  return [...bad, ...good].slice(0, SHOW);
}

function wall(doc: ContractDoc, ans: ContractAnswer) {
  if (ans.marks.length <= SHOW) return null;
  const items: MiniItem[] = ans.marks.map((m) => ({ id: m.id, label: m.no, tone: m.state === 'right' ? 'good' : m.state === 'wrong' ? 'warn' : 'bad', title: `Clause ${m.no}: ${stateFlag(m).label}` }));
  return <MiniWall items={items} noun="clause" note={`Every clause in either list: green found, amber wrong detail, red missed or invented`} render={(id) => <MarkCard doc={doc} m={ans.marks.find((m) => m.id === id)!} />} />;
}

function impact(ans: ContractAnswer): Array<{ tone: Tone; text: ReactNode }> {
  const lines: Array<{ tone: Tone; text: ReactNode }> = [];
  const money = ['amount', 'percent', 'days', 'months', 'years', 'hours', 'number'];
  const missed = ans.marks.filter((m) => m.state === 'missed');
  for (const m of [...missed.filter((x) => money.includes(x.key!.type)), ...missed.filter((x) => !money.includes(x.key!.type))].slice(0, 4))
    lines.push({ tone: 'bad', text: <>Would have signed without spotting clause {m.no}: {describe(m.key!)}.</> });
  const inv = ans.marks.filter((m) => m.state === 'invented');
  if (inv.length) lines.push({ tone: 'warn', text: <>Flagged {plural(inv.length, 'clause')} that did not really change ({inv.slice(0, 4).map((m) => m.no).join(', ')}): time wasted arguing over nothing.</> });
  const wrong = ans.marks.filter((m) => m.state === 'wrong');
  for (const m of wrong.slice(0, 2)) lines.push({ tone: 'warn', text: <>Clause {m.no}: reported “{claim(m.model)}”, but it is {describe(m.key!)}.</> });
  return lines;
}

const contractSpec = () =>
  docVisual<ContractDoc, ContractAnswer>({
    family: 'business',
    caption: 'The contract redline (red struck out, green added) with the model’s list of changes laid over it: found, wrong detail, missed or invented.',
    doc: (input) => parseContract(input.turns[0] ?? '', input.expected),
    answer: (input, doc) => contractAnswer(input, doc),
    sub: (doc) => `${plural(doc.v1.length, 'clause')} in version 1 · ${plural(doc.v2.length, 'clause')} in version 2${doc.ladder ? ' · plus the customer’s response and the supplier’s note' : ''}`,
    Question: ({ doc }) => <Pages doc={doc} />,
    Key: ({ doc }) => (
      <Paper className="cd-contract" title={`${plural(Object.keys(doc.key ?? {}).length, 'substantive change')}${doc.ladder ? ' in the agreed version' : ''}`}>
        <ul className="cd-keylist">
          {Object.entries(doc.key ?? {}).map(([id, c]) => (
            <li key={id}>
              <b>{doc.keyNo[id]}</b> {describe(c)}
            </li>
          ))}
        </ul>
      </Paper>
    ),
    Answer: ({ doc, ans }) => {
      const shown = marksToShow(ans);
      return (
        <>
          {shown.map((m) => (
            <MarkCard key={m.id} doc={doc} m={m} />
          ))}
          <MoreLine n={ans.marks.length - shown.length} noun="clauses, all found correctly (open any one below)" />
        </>
      );
    },
    AnswerExtra: ({ doc, ans }) => wall(doc, ans),
    Result: ({ ans }) => (
      <>
        <Tally
          items={[
            { n: ans.found, of: ans.found + ans.missed, label: 'real changes spotted' },
            { n: ans.right, of: ans.found + ans.missed, label: 'described exactly right' },
          ]}
        />
        {ans.invented > 0 && <div className="cd-more">and {plural(ans.invented, 'invented change')} that should not be on the list</div>}
        <Impact lines={impact(ans)} />
      </>
    ),
    sentence: (doc, ans) => {
      const total = ans.found + ans.missed;
      if (!ans.missed && !ans.invented && ans.right === total) return `Spotted all ${total} real changes and nothing else: this redline review could be trusted before signing.`;
      const parts = [ans.missed ? `missed ${ans.missed}` : '', ans.invented ? `invented ${ans.invented}` : '', ans.found - ans.right ? `got the detail of ${ans.found - ans.right} wrong` : ''].filter(Boolean);
      return `Spotted ${ans.found} of ${total} real changes${doc.ladder ? ' in the agreed version' : ''}, but ${parts.join(', ')}${ans.missed ? ': a missed clause is a term you sign without knowing it.' : ans.invented ? ': a change that is not there costs as much as a missed one.' : '.'}`;
    },
    headline: (_doc, ans) => `${ans.found} of ${ans.found + ans.missed} changes spotted · ${ans.invented} invented`,
    alt: {
      question: (doc) => `Two versions of a services agreement, ${doc.v2.length} clauses; ${changedClauses(doc).length} clauses have different wording.`,
      key: (doc) => `The real changes: ${Object.entries(doc.key ?? {}).map(([id, c]) => `${doc.keyNo[id]} ${describe(c)}`).join('; ')}.`,
      answer: (_doc, ans) => ans.marks.map((m) => `Clause ${m.no}: ${stateFlag(m).label}`).join('; '),
    },
  });

export const CONTRACT_REDLINE = contractSpec();
export const CONTRACT_LADDER = contractSpec();
