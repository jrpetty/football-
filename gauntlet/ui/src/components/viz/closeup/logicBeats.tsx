/** Deduction Grid and Knights & Knaves levels as three beats. */
import { useMemo } from 'react';
import type { CSSProperties } from 'react';
import { gridQuestion, gridSentence, islandSentence, statedLine } from '../../../../../src/presenter/visuals/closeup.ts';
import { gridVisual, type GridVisual } from '../../../../../src/presenter/visuals/grid.ts';
import { islandVisual, parseIsland, type IslandRole, type IslandVisual } from '../../../../../src/presenter/visuals/truth.ts';
import { cx } from '../../ui.tsx';
import { BeatLayout, Chip, type Beat } from './parts.tsx';
import type { KindProps } from './types.ts';
import { TextBeats } from './otherBeats.tsx';

function safe<T>(f: () => T): T | null {
  try {
    return f();
  } catch {
    return null;
  }
}

// ───────────────────────────── Deduction Grid ─────────────────────────────

function HouseIcon({ label }: { label: string }) {
  return (
    <span className="lc-house">
      <svg viewBox="0 0 40 30" aria-hidden="true">
        <path d="M3 29 V12 L20 2 L37 12 V29 Z" />
      </svg>
      <b className="tnum">{label}</b>
    </span>
  );
}

type CellState = 'plain' | 'good' | 'bad' | 'key';

interface Column {
  name: string;
  cells: Array<{ text: string; state: CellState; fix?: string }>;
}

function HouseTable({ positions, noun, columns }: { positions: string[]; noun: string; columns: Column[] }) {
  return (
    <div className="lc-scroll-x">
      <table className="lc-htable">
        <thead>
          <tr>
            <th scope="col">{noun[0]!.toUpperCase() + noun.slice(1)}</th>
            {columns.map((c) => (
              <th key={c.name} scope="col">
                {c.name}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {positions.map((p, i) => (
            <tr key={p} style={{ ['--i' as string]: i } as CSSProperties}>
              <th scope="row">
                <HouseIcon label={p} />
              </th>
              {columns.map((c) => {
                const cell = c.cells[i]!;
                return (
                  <td key={c.name} className={cx('lc-hcell', `s-${cell.state}`)}>
                    {cell.state === 'bad' ? (
                      <>
                        <s>{cell.text || 'nothing'}</s>
                        <span className="lc-fix">{cell.fix}</span>
                      </>
                    ) : (
                      <span>{cell.text || '—'}</span>
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function gridColumns(v: GridVisual, state: (ok: boolean | undefined) => CellState, useTruth = false): Column[] {
  return v.asked.map((ci) => ({
    name: v.categories[ci]!.name,
    cells: v.rows[ci]!.map((cell) => {
      if (useTruth) return { text: cell.truth, state: 'key' as CellState };
      const st = state(cell.ok);
      return { text: cell.model ?? '', state: st, fix: st === 'bad' ? cell.truth : undefined };
    }),
  }));
}

export function GridBeats(p: KindProps) {
  const gq = useMemo(() => safe(() => gridQuestion(p.prompt)), [p.prompt]);
  const key = useMemo(() => safe(() => gridVisual(p.q)), [p.q]);
  const gv = useMemo(() => (p.a ? safe(() => gridVisual(p.a!)) : null), [p.a]);
  if (!gq) return <TextBeats {...p} />;
  const compound = gq.clues.filter((c) => c.compound).length;
  const asked = new Set(gq.asked);
  const question: Beat = {
    sub: (
      <>
        {gq.positions.length} {gq.noun}s · {gq.categories.length} categories · {gq.clues.length} clues
      </>
    ),
    alt: `${gq.setting} ${gq.positions.length} ${gq.noun}s and ${gq.categories.length} categories: ${gq.categories.map((c) => `${c.name} (${c.values.join(', ')})`).join('; ')}. ${gq.clues.length} clues, ${compound} of them combining several facts. Asked: ${gq.question}`,
    node: (
      <div className="lc-grid-q">
        <p className="lc-lead">{gq.setting}</p>
        <div className="lc-house-row">
          {gq.positions.map((x) => (
            <HouseIcon key={x} label={x} />
          ))}
        </div>
        <div className="lc-cats">
          {gq.categories.map((c, i) => (
            <div key={c.name} className={cx('lc-cat', asked.has(i) && 'asked')}>
              <div className="lc-cat-name">
                {c.name}
                {asked.has(i) && <Chip tone="accent">asked</Chip>}
              </div>
              <div className="lc-cat-vals">
                {c.values.map((v) => (
                  <span key={v} className="lc-val">
                    {v}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
        <div className="lc-k">
          The clues{' '}
          <span className="lc-k-note">
            · <i className="lc-swatch combo" /> combines several facts ({compound})
          </span>
        </div>
        <ol className="lc-clues">
          {gq.clues.map((c) => (
            <li key={c.n} className={cx(c.compound && 'combo')}>
              <b className="tnum">{c.n}</b>
              <span>{c.text}</span>
            </li>
          ))}
        </ol>
        {gq.question && <p className="lc-ask">{gq.question}</p>}
      </div>
    ),
  };
  const keyBeat: Beat | null = key
    ? {
        sub: 'The only arrangement that fits every clue',
        alt: `The answer: ${key.asked.map((ci) => `${key.categories[ci]!.name}: ${key.rows[ci]!.map((c) => c.truth).join(', ')}`).join('; ')}.`,
        node: <HouseTable positions={key.positions} noun={key.noun} columns={gridColumns(key, () => 'key', true)} />,
      }
    : null;
  let answer: Beat | null = null;
  let result: Beat | null = null;
  let sentence: string | null = null;
  if (p.a) {
    if (gv) {
      answer = {
        sub: gv.answered ? `Its ${gv.asked.map((ci) => gv.categories[ci]!.name.toLowerCase()).join(' and ')}, ${gv.noun} by ${gv.noun}` : 'No grid we could read',
        alt: gv.answered ? `The model answered: ${gv.asked.map((ci) => `${gv.categories[ci]!.name}: ${gv.rows[ci]!.map((c) => c.model || 'nothing').join(', ')}`).join('; ')}.` : 'The model gave no readable answer.',
        node: gv.answered ? <HouseTable positions={gv.positions} noun={gv.noun} columns={gridColumns(gv, () => 'plain')} /> : <p className="lc-empty">{statedLine(p.a) ? `“${statedLine(p.a)}”` : 'No answer'}</p>,
      };
      const wrong: string[] = [];
      gv.asked.forEach((ci) => gv.rows[ci]!.forEach((c, i) => c.ok === false && wrong.push(`${gv.noun} ${gv.positions[i]}: ${c.model || 'nothing'} should be ${c.truth}`)));
      result = {
        sub: undefined,
        alt: wrong.length ? `Wrong cells: ${wrong.join('; ')}.` : 'Every cell matches the answer key.',
        node: (
          <div className="lc-res-grid">
            <div className="lc-res-k">
              <span className="tnum lc-big">
                {gv.right}/{gv.total}
              </span>{' '}
              cells right
            </div>
            <HouseTable positions={gv.positions} noun={gv.noun} columns={gridColumns(gv, (ok) => (ok ? 'good' : 'bad'))} />
          </div>
        ),
      };
      sentence = gridSentence(gv, p.a.score, p.passMark);
    } else {
      const said = statedLine(p.a);
      answer = { alt: said ? `The model answered: ${said}` : 'No answer recorded.', node: <p className="lc-said">{said || 'No final answer'}</p> };
    }
  }
  return <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={result} verdict={p.verdict} sentence={sentence} answerHead={p.answerHead} keyBeat={keyBeat} />;
}

// ───────────────────────────── Knights & Knaves ─────────────────────────────

const ROLE_LABEL: Record<IslandRole, string> = { knight: 'Knight', knave: 'Knave', spy: 'Spy', alternator: 'Alternator' };
const ROLE_HINT: Record<IslandRole, string> = { knight: 'always tells the truth', knave: 'always lies', spy: 'may say anything', alternator: 'true and false in turn' };

function Figure({ role, crossed }: { role: IslandRole | null; crossed?: boolean }) {
  return (
    <svg className={cx('lc-fig', role ? `r-${role}` : 'r-unknown')} viewBox="0 0 60 64" aria-hidden="true">
      <ellipse cx="30" cy="61" rx="17" ry="2.6" className="lc-fig-shadow" />
      <path className="lc-fig-body" d="M13 61 C13 44 20 34 30 34 C40 34 47 44 47 61 Z" />
      <circle className="lc-fig-head" cx="30" cy="19" r="11" />
      <circle cx="26.2" cy="18.5" r="1.5" className="lc-fig-eye" />
      <circle cx="33.8" cy="18.5" r="1.5" className="lc-fig-eye" />
      {!role && (
        <text x="30" y="55" textAnchor="middle" className="lc-fig-q">
          ?
        </text>
      )}
      {role === 'knight' && <path className="lc-fig-emb" d="M30 41 l7 2.6 v6 c0 4.6 -3.3 7.2 -7 9.1 c-3.7 -1.9 -7 -4.5 -7 -9.1 v-6 z" />}
      {role === 'knave' && <path className="lc-fig-emb" d="M21 45 c5.5 -2 12.5 -2 18 0 c0 4.6 -2.7 7.3 -5.4 7.3 c-2 0 -2.6 -2 -3.6 -2 c-1 0 -1.6 2 -3.6 2 c-2.7 0 -5.4 -2.7 -5.4 -7.3 z" />}
      {role === 'spy' && <path className="lc-fig-emb" d="M20 51 h20 v2.4 h-20 z M24.5 44 h11 l1.5 7 h-14 z" />}
      {role === 'alternator' && <path className="lc-fig-emb alt" d="M22 45 h12 l-3 -3 M38 53 h-12 l3 3" />}
      {crossed && <path className="lc-fig-x" d="M10 8 L50 58 M50 8 L10 58" />}
    </svg>
  );
}

function RoleTag({ role, struck }: { role: IslandRole | null; struck?: boolean }) {
  if (!role) return <span className="lc-role none">no verdict</span>;
  return <span className={cx('lc-role', `r-${role}`, struck && 'struck')}>{ROLE_LABEL[role]}</span>;
}

export function IslandBeats(p: KindProps) {
  const parsed = useMemo(() => safe(() => parseIsland(p.prompt)), [p.prompt]);
  const key = useMemo(() => safe(() => islandVisual(p.q)), [p.q]);
  const v: IslandVisual | null = useMemo(() => (p.a ? safe(() => islandVisual(p.a!)) : null), [p.a]);
  if (!parsed) return <TextBeats {...p} />;
  const nStatements = [...parsed.statements.values()].reduce((s, x) => s + x.length, 0);
  const dense = parsed.names.length > 12;
  const question: Beat = {
    sub: (
      <>
        {parsed.names.length} islanders · {nStatements} statements
      </>
    ),
    alt: `${parsed.names.length} islanders. ${parsed.rule ?? ''} Their statements: ${parsed.names.map((n) => `${n}: ${(parsed.statements.get(n) ?? []).map((s) => `“${s}”`).join(' ') || 'says nothing'}`).join(' ')}`,
    node: (
      <div className="lc-isl-q">
        <div className="lc-roles">
          {parsed.roles.map((r) => (
            <span key={r} className="lc-role-key">
              <RoleTag role={r} /> {ROLE_HINT[r]}
            </span>
          ))}
        </div>
        {parsed.rule && <p className="lc-lead">{parsed.rule}</p>}
        <ol className={cx('lc-isl', dense && 'dense')}>
          {parsed.names.map((n, i) => (
            <li key={n} style={{ ['--i' as string]: i } as CSSProperties}>
              <div className="lc-bubbles">
                {(parsed.statements.get(n) ?? []).map((s, k, all) => (
                  <p key={k} className="lc-bubble">
                    {all.length > 1 && <b className="tnum">{k + 1}</b>}
                    {s}
                  </p>
                ))}
                {!(parsed.statements.get(n) ?? []).length && <p className="lc-bubble quiet">(says nothing)</p>}
              </div>
              <Figure role={null} />
              <span className="lc-isl-name">{n}</span>
            </li>
          ))}
        </ol>
      </div>
    ),
  };
  const figures = (vv: IslandVisual, show: 'model' | 'truth') => (
    <ol className={cx('lc-isl', 'compact', dense && 'dense')}>
      {vv.people.map((x, i) => {
        const role = show === 'model' ? x.model : x.truth;
        return (
          <li key={x.name} style={{ ['--i' as string]: i } as CSSProperties}>
            <Figure role={role} />
            <span className="lc-isl-name">{x.name}</span>
            <RoleTag role={role} />
          </li>
        );
      })}
    </ol>
  );
  const keyBeat: Beat | null = key
    ? {
        sub: 'The only assignment consistent with every statement',
        alt: `The answer: ${key.people.map((x) => `${x.name} is a ${x.truth}`).join(', ')}.`,
        node: figures(key, 'truth'),
      }
    : null;
  let answer: Beat | null = null;
  let result: Beat | null = null;
  let sentence: string | null = null;
  if (p.a) {
    if (v && v.answered) {
      answer = {
        sub: 'Each islander in the colour of the type the model gave',
        alt: `The model said: ${v.people.map((x) => `${x.name} ${x.model ?? 'no verdict'}`).join(', ')}.`,
        node: figures(v, 'model'),
      };
      const wrong = v.people.filter((x) => !x.ok);
      result = {
        alt: wrong.length ? `Misjudged: ${wrong.map((x) => `${x.name}, called ${x.model ?? 'nothing'}, really a ${x.truth}`).join('; ')}.` : 'Every islander’s type is right.',
        node: (
          <div className="lc-isl-res">
            <div className="lc-res-k">
              <span className="tnum lc-big">
                {v.right}/{v.people.length}
              </span>{' '}
              islanders right
            </div>
            <div className="lc-dots" aria-hidden="true">
              {v.people.map((x) => (
                <span key={x.name} className={cx('lc-dot', x.ok ? 'good' : 'bad')} title={x.name}>
                  {x.name.slice(0, 2)}
                </span>
              ))}
            </div>
            {wrong.length > 0 && (
              <ul className="lc-wrong-isl">
                {wrong.slice(0, 4).map((x) => (
                  <li key={x.name}>
                    <Figure role={x.model} crossed />
                    <div>
                      <b>{x.name}</b>
                      <div className="lc-was">
                        said <RoleTag role={x.model} struck /> <span aria-hidden="true">→</span> really <RoleTag role={x.truth} />
                      </div>
                    </div>
                  </li>
                ))}
                {wrong.length > 4 && <li className="lc-more">+ {wrong.length - 4} more misjudged</li>}
              </ul>
            )}
          </div>
        ),
      };
      sentence = islandSentence(v, p.a.score, p.passMark);
    } else {
      const said = statedLine(p.a);
      answer = { alt: said ? `The model answered: ${said}` : 'No answer recorded.', node: <p className="lc-said">{said || 'No final answer'}</p> };
      sentence = v ? islandSentence(v, p.a.score, p.passMark) : null;
    }
  }
  return <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={result} verdict={p.verdict} sentence={sentence} answerHead={p.answerHead} keyBeat={keyBeat} />;
}
