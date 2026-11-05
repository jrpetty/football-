/** Engineering: Ladder levels as three beats: the operations unlocked, the code and its hidden tests, what failed. */
import { useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { engineQuestion, engineSentence } from '../../../../../src/presenter/visuals/closeup.ts';
import { codeVisual, withTestNames, type CodeTile, type CodeVisual } from '../../../../../src/presenter/visuals/code.ts';
import { cx } from '../../ui.tsx';
import { HighlightedCode } from '../HighlightedCode.tsx';
import { BeatLayout, Chip, type Beat } from './parts.tsx';
import type { KindProps } from './types.ts';
import { TextBeats } from './otherBeats.tsx';
import { engineTestNames } from './engineNames.ts';

const STATUS: Record<CodeTile['status'], string> = { pass: 'passed', fail: 'wrong answer', timeout: 'too slow', error: 'crashed' };

function Tiles({ v }: { v: CodeVisual }) {
  return (
    <div className="lc-tiles">
      {v.tiles.map((t) => (
        <span key={t.index} className={cx('lc-tile', t.status)} title={`${t.name ?? `Test ${t.index}`}: ${STATUS[t.status]}`} style={{ ['--i' as string]: t.index } as CSSProperties}>
          <span className="tnum">{t.index}</span>
        </span>
      ))}
    </div>
  );
}

function CodeBox({ code }: { code: string }) {
  const [all, setAll] = useState(false);
  const lines = code.split('\n').length;
  return (
    <div className="lc-codebox">
      <HighlightedCode code={code} maxLines={all ? undefined : 16} />
      {lines > 16 && (
        <button type="button" className="btn xs ghost" onClick={() => setAll((a) => !a)} aria-expanded={all}>
          {all ? 'Show less code' : `Show all ${lines.toLocaleString('en-GB')} lines`}
        </button>
      )}
    </div>
  );
}

export function EngineBeats(p: KindProps) {
  const eq = useMemo(() => engineQuestion(p.prompt, p.q.notes), [p.prompt, p.q.notes]);
  const names = engineTestNames(p.q.caseId);
  const v = useMemo(() => {
    if (!p.a) return null;
    try {
      const c = codeVisual(p.a);
      return c ? withTestNames(c, names) : null;
    } catch {
      return null;
    }
  }, [p.a, names]);
  if (!eq) return <TextBeats {...p} />;
  const levels = [...new Set(eq.ops.map((o) => o.level))];
  const question: Beat = {
    sub: (
      <>
        Write <code className="lc-inline">{eq.functionName ?? 'the function'}()</code> · {eq.ops.length} operations
      </>
    ),
    alt: `Write a JavaScript function that keeps an array and applies range operations. Level ${eq.level} adds ${eq.ops.filter((o) => o.isNew).map((o) => o.name).join(', ')}; in all: ${eq.ops.map((o) => `${o.name} (${o.meaning})`).join('; ')}. Up to ${eq.maxN ?? '?'} elements and ${eq.maxOps ?? '?'} operations, about ${eq.timeLimitSec ?? '?'} seconds per test${eq.hiddenTests ? `, ${eq.hiddenTests} hidden tests` : ''}.`,
    node: (
      <div className="lc-engine-q">
        <p className="lc-lead">An array of numbers and a stream of operations on ranges of it. Each level adds one more kind.</p>
        <div className="lc-ops">
          {levels.map((lv) => (
            <div key={lv} className={cx('lc-op-level', lv === eq.level && 'new')}>
              <span className="lc-op-lv tnum">L{lv}</span>
              <div className="lc-op-chips">
                {eq.ops
                  .filter((o) => o.level === lv)
                  .map((o) => (
                    <span key={o.name} className={cx('lc-op', o.isNew && 'new', o.query && 'query')} title={o.meaning}>
                      <b>{o.name}</b>
                      <small>{o.query ? 'question' : 'update'}</small>
                    </span>
                  ))}
              </div>
            </div>
          ))}
        </div>
        <div className="lc-chiprow">
          {eq.maxN && <Chip>array up to {eq.maxN}</Chip>}
          {eq.maxOps && <Chip>{eq.maxOps} operations</Chip>}
          {eq.timeLimitSec && <Chip tone="warn">about {eq.timeLimitSec} s per test</Chip>}
          {eq.hiddenTests && <Chip tone="accent">{eq.hiddenTests} hidden tests</Chip>}
        </div>
        <ul className="lc-rules">
          {eq.ops
            .filter((o) => o.isNew)
            .map((o) => (
              <li key={o.name}>
                <b>New here:</b> {o.name} — {o.meaning}
              </li>
            ))}
        </ul>
      </div>
    ),
  };
  const keyBeat: Beat | null = names
    ? {
        sub: 'Its code must pass every one of these, each within the time limit',
        alt: `The hidden tests: ${names.join('; ')}.`,
        node: (
          <ol className="lc-testlist">
            {names.map((n, i) => (
              <li key={i}>
                <span className="lc-tile neutral">
                  <span className="tnum">{i + 1}</span>
                </span>
                {n}
              </li>
            ))}
          </ol>
        ),
      }
    : null;
  let answer: Beat | null = null;
  let result: Beat | null = null;
  let sentence: string | null = null;
  let interactiveAnswer = null;
  if (p.a) {
    if (v) {
      answer = {
        sub: `${v.code.split('\n').length.toLocaleString('en-GB')} lines of JavaScript, run on ${v.total} hidden tests`,
        alt: `The model wrote ${v.code.split('\n').length} lines of code. Hidden tests: ${v.passed} of ${v.total} passed.`,
        node: (
          <div className="lc-engine-a">
            <Tiles v={v} />
            <div className="lc-tile-key">
              <span>
                <i className="lc-tile pass" /> passed
              </span>
              <span>
                <i className="lc-tile fail" /> wrong answer
              </span>
              <span>
                <i className="lc-tile timeout" /> too slow
              </span>
            </div>
          </div>
        ),
      };
      interactiveAnswer = v.code ? <CodeBox code={v.code} /> : null;
      const failed = v.tiles.filter((t) => t.status !== 'pass');
      result = {
        alt: failed.length ? `Failed tests: ${failed.map((t) => `${t.name ?? `test ${t.index}`} (${STATUS[t.status]})`).join('; ')}.` : 'Every hidden test passed.',
        node: (
          <div className="lc-engine-res">
            <div className="lc-res-k">
              <span className="tnum lc-big">
                {v.passed}/{v.total}
              </span>{' '}
              hidden tests passed
            </div>
            {failed.length > 0 && (
              <ul className="lc-fails">
                {failed.slice(0, 4).map((t) => (
                  <li key={t.index} className={t.status}>
                    <span className={cx('lc-tile', t.status)}>
                      <span className="tnum">{t.index}</span>
                    </span>
                    <div>
                      <b>{t.name ? `‘${t.name}’` : `Test ${t.index}`}</b>
                      <div className="lc-fail-why">
                        {t.status === 'fail' && t.expected !== undefined ? (
                          <>
                            expected <code className="lc-inline ok">{JSON.stringify(t.expected)}</code>, got <code className="lc-inline bad">{t.got}</code>
                          </>
                        ) : (
                          STATUS[t.status] + (t.detail ? ` · ${t.detail}` : '')
                        )}
                      </div>
                    </div>
                  </li>
                ))}
                {failed.length > 4 && <li className="lc-more">+ {failed.length - 4} more failed</li>}
              </ul>
            )}
          </div>
        ),
      };
      sentence = engineSentence(
        v.tiles.map((t) => ({ index: t.index, name: t.name ?? `test ${t.index}`, status: t.status })),
        p.a.score,
        p.passMark,
        v.loadError,
      );
    } else {
      answer = { alt: 'No code found in the reply.', node: <p className="lc-empty">No code block was found in the reply.</p> };
      sentence = engineSentence([], p.a.score, p.passMark);
    }
  }
  return (
    <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={result} verdict={p.verdict} sentence={sentence} answerHead={p.answerHead} keyBeat={keyBeat} interactiveAnswer={interactiveAnswer} />
  );
}
