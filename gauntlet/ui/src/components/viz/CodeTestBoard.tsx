/**
 * Coding tests: a results board (one tile per hidden test, grouped by input
 * size), the selected failing test's input / expected / got, and the model's
 * code with syntax colouring.
 */
import { useState } from 'react';
import type { CSSProperties } from 'react';
import { codeHeadline, type CodeTile, type CodeVisual } from '../../../../src/presenter/visuals/code.ts';
import { cx } from '../ui.tsx';
import { VizFrame, type VizMode } from './VizFrame.tsx';
import { HighlightedCode } from './HighlightedCode.tsx';

const STATUS_LABEL: Record<CodeTile['status'], string> = { pass: 'passed', fail: 'wrong output', timeout: 'too slow', error: 'crashed' };

function clip(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, max - 1)}… (${s.length.toLocaleString()} characters)` : s;
}

function TileDetail({ t, fn }: { t: CodeTile; fn: string | null }) {
  const input = t.args ? t.args.map((a) => JSON.stringify(a)).join(', ') : t.label.replace(/^test \d+:\s*/, '');
  return (
    <div className={cx('vz-tile-detail', t.status)}>
      <div className="vz-card-k">
        Test {t.index} · {STATUS_LABEL[t.status]}
      </div>
      <dl>
        <dt>Input</dt>
        <dd>
          <code>{clip(t.args ? `${fn ?? 'f'}(${input})` : input, 420)}</code>
        </dd>
        {t.expected !== undefined && (
          <>
            <dt>Expected</dt>
            <dd>
              <code className="ok">{clip(JSON.stringify(t.expected), 300)}</code>
            </dd>
          </>
        )}
        {t.status === 'pass' ? null : (
          <>
            <dt>{t.status === 'fail' ? 'Got' : 'What happened'}</dt>
            <dd>
              <code className="bad">{clip(t.got ?? t.detail, 300)}</code>
            </dd>
          </>
        )}
      </dl>
    </div>
  );
}

export function CodeTestBoard({ v, mode }: { v: CodeVisual; mode: VizMode }) {
  const [sel, setSel] = useState<CodeTile | null>(v.firstFail);
  const shown = sel ?? v.firstFail;
  const tone = v.loadError || v.passed === 0 ? 'bad' : v.passed === v.total ? 'good' : 'half';
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={`Hidden tests${v.functionName ? ` for ${v.functionName}()` : ''}`}
      headline={codeHeadline(v)}
      big={`${v.passed}/${v.total}`}
      bigSub="tests pass"
      legend={[
        { tone: 'good', label: 'Passed' },
        { tone: 'bad', label: 'Wrong output or crash' },
        { tone: 'half', label: 'Too slow (timed out)' },
      ]}
    >
      <div className={cx('vz-code-board', mode === 'slide' && 'slide')}>
        <div className="vz-board">
          {v.loadError && <div className="vz-missing bad">{v.loadError}</div>}
          {v.groups.map((g) => (
            <div key={g.name} className="vz-board-g">
              <div className="vz-board-k">
                {g.name}
                {g.hint && <small> · {g.hint}</small>}
                <span className="tnum">
                  {g.tiles.filter((t) => t.status === 'pass').length}/{g.tiles.length}
                </span>
              </div>
              <div className="vz-tiles-grid">
                {g.tiles.map((t) => (
                  <button
                    key={t.index}
                    type="button"
                    className={cx('vz-ttile', t.status, shown === t && 'sel')}
                    onClick={() => setSel(t)}
                    title={`Test ${t.index}: ${STATUS_LABEL[t.status]}`}
                    aria-label={`Test ${t.index}: ${STATUS_LABEL[t.status]}`}
                    style={{ ['--i' as string]: t.index } as CSSProperties}
                  >
                    <span className="tnum">{t.index}</span>
                  </button>
                ))}
              </div>
            </div>
          ))}
          {shown ? <TileDetail t={shown} fn={v.functionName} /> : v.total > 0 && <div className="vz-missing">Every hidden test passed.</div>}
        </div>
        <div className="vz-code-col">
          <div className="mini-title">The model’s code</div>
          {v.code ? <HighlightedCode code={v.code} maxLines={mode === 'slide' ? 26 : 400} /> : <div className="vz-missing">No code block was found in the reply.</div>}
        </div>
      </div>
    </VizFrame>
  );
}
