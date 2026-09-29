/**
 * One Horizon rung, answer vs truth: where this level sits on the ladder, then the model's answer against
 * the double-verified key. Whole numbers are compared digit by digit, sliding-puzzle plans are replayed
 * (length against the proven minimum, the first illegal move marked), nonogram grids are drawn side by
 * side with every wrong cell in red. Everything shown comes from the recorded reply and the answer key.
 */
import type { CSSProperties } from 'react';
import { cx } from '../ui.tsx';
import { VizFrame, type VizMode, type VizTone } from './VizFrame.tsx';
import { rungHeadline, type GridCompare, type IntegerCompare, type PlanCompare, type RungModel } from '../../../../src/presenter/visuals/horizon.ts';
import './horizon.css';

const TEST_NAME: Record<string, string> = {
  'horizon.mind-runner': 'Run It In Your Head',
  'horizon.modpow-ladder': 'No Calculator',
  'horizon.sliding-ladder': 'The Sliding Ladder',
  'horizon.nonogram-ladder': 'The Picture Logic Ladder',
  'horizon.tiling-count': 'Count Every Tiling',
};

function toneOf(m: RungModel): VizTone {
  if (m.verdict === 'correct' || m.verdict === 'optimal') return 'good';
  if (m.verdict === 'suboptimal') return 'half';
  return 'bad';
}

function MiniLadder({ level, levels, tone }: { level: number; levels: number; tone: VizTone }) {
  return (
    <div className="hz-mini" aria-label={`Level ${level} of ${levels}`}>
      {Array.from({ length: levels }, (_, i) => (
        <span key={i} className={cx(i + 1 === level && `cur t-${tone}`, i + 1 < level && 'below')}>
          <b>{i + 1}</b>
          <i />
        </span>
      ))}
    </div>
  );
}

function Digits({ c }: { c: IntegerCompare }) {
  return (
    <>
      <div>
        <div className="hz-k">Model’s answer</div>
        {c.got === null ? (
          <div className="vz-missing bad">No whole number after FINAL ANSWER.</div>
        ) : (
          <div className="hz-digits" aria-label={`Model answered ${c.got}`}>
            {[...c.got].map((d, i) => (
              <i key={i} className={(c.wrongDigits >= 0 ? d === c.want[i] : c.firstDiff < 0 || i < c.firstDiff) ? 'ok' : 'no'}>
                {d}
              </i>
            ))}
          </div>
        )}
      </div>
      <div>
        <div className="hz-k">Answer key</div>
        <div className="hz-digits" aria-label={`Key ${c.want}`}>
          {[...c.want].map((d, i) => (
            <i key={i} className="key">
              {d}
            </i>
          ))}
        </div>
      </div>
      {c.got !== null && c.firstDiff >= 0 && (
        <div className="hz-note">
          {c.got.length !== c.want.length ? `The key has ${c.want.length} digits; the model gave ${c.got.length}. ` : ''}
          {c.wrongDigits > 0 && c.wrongDigits <= 3
            ? `${c.want.length - c.wrongDigits} of ${c.want.length} digits are in the right place, but one slip anywhere makes the whole answer wrong.`
            : c.firstDiff === 0 ? 'Wrong from the very first digit.' : c.firstDiff === 1 ? 'Only the first digit matches; after that the answers part ways.' : `The first ${c.firstDiff} digits match, then the answers part ways.`}
        </div>
      )}
    </>
  );
}

function Board({ cells, cols, title }: { cells: number[]; cols: number; title: string }) {
  return (
    <div>
      <div className="hz-k">{title}</div>
      <div className="hz-board-mini" style={{ gridTemplateColumns: `repeat(${cols}, auto)` }}>
        {cells.map((v, i) => (
          <i key={i} className={cx(v === 0 && 'gap', v !== 0 && v === i + 1 && 'home')}>
            {v === 0 ? '' : v}
          </i>
        ))}
      </div>
    </div>
  );
}

function Plan({ c, tone }: { c: PlanCompare; tone: VizTone }) {
  const max = Math.max(c.optimal, c.moves.length, 1);
  const shown = c.moves.slice(0, 90);
  return (
    <>
      <div className="hz-bars">
        <div className="hz-bar">
          <span>Proven minimum</span>
          <span>
            <em style={{ width: `${(100 * c.optimal) / max}%` }} />
          </span>
          <b>{c.optimal}</b>
        </div>
        <div className={cx('hz-bar model', `t-${tone}`)}>
          <span>Model’s plan</span>
          <span>
            <em style={{ width: `${(100 * c.moves.length) / max}%` }} />
          </span>
          <b>{c.moves.length}</b>
        </div>
      </div>
      <div className="hz-boards">
        <Board cells={c.start} cols={c.cols} title="Start" />
        {!c.solved && c.moves.length > 0 && <Board cells={c.end} cols={c.cols} title={c.illegalAt >= 0 ? `Board before move ${c.illegalAt + 1}` : 'Where its plan ends'} />}
      </div>
      {c.moves.length > 0 && (
        <div>
          <div className="hz-k">Model’s moves (tile numbers)</div>
          <div className="hz-moves">
            {shown.map((t, i) => (
              <i key={i} className={cx(i === c.illegalAt && 'bad', c.illegalAt >= 0 && i > c.illegalAt && 'after')} title={i === c.illegalAt ? 'Illegal: this tile is not next to the gap' : undefined}>
                {t}
              </i>
            ))}
            {c.moves.length > shown.length && <span className="hz-note">+{c.moves.length - shown.length} more</span>}
          </div>
        </div>
      )}
      {c.optimalPlan.length > 0 && (
        <div>
          <div className="hz-k">One shortest plan (from the answer key)</div>
          <div className="hz-moves key">
            {c.optimalPlan.map((t, i) => (
              <i key={i}>{t}</i>
            ))}
          </div>
        </div>
      )}
    </>
  );
}

function MiniGrid({ rows, other, title }: { rows: string[]; other?: string[] | null; title: string }) {
  const n = Math.max(rows.length, rows[0]?.length ?? 0);
  const cell = n > 40 ? '0.35em' : n > 30 ? '0.45em' : n > 20 ? '0.55em' : n > 14 ? '0.7em' : n > 9 ? '0.9em' : '1.2em';
  return (
    <div>
      <div className="hz-k">{title}</div>
      <div className="hz-grid" style={{ gridTemplateColumns: `repeat(${rows[0]?.length ?? 0}, auto)`, ['--cell' as string]: cell } as CSSProperties}>
        {rows.flatMap((row, r) =>
          [...row].map((ch, c) => {
            const bad = other && other[r]?.[c] !== ch;
            return <i key={`${r}-${c}`} className={cx(ch === '#' && (bad ? 'wf' : 'f'), ch !== '#' && bad && 'we')} />;
          }),
        )}
      </div>
    </div>
  );
}

function Grids({ c }: { c: GridCompare }) {
  return (
    <div className="hz-grids">
      {c.got ? <MiniGrid rows={c.got} other={c.want} title="Model’s grid" /> : <div className="vz-missing bad">No complete grid after FINAL ANSWER.</div>}
      <MiniGrid rows={c.want} title="The only solution" />
    </div>
  );
}

export function HorizonRungCard({ m, mode, levels = 10, modelLabel }: { m: RungModel; mode: VizMode; levels?: number; modelLabel?: string }) {
  const tone = toneOf(m);
  const c = m.compare;
  const big = c.kind === 'plan' && c.solved ? `${c.moves.length}` : c.kind === 'grid' && c.got ? (c.wrong === 0 ? '✓' : `${c.wrong}`) : undefined;
  const bigSub = c.kind === 'plan' && c.solved ? `moves · min ${c.optimal}` : c.kind === 'grid' && c.got ? (c.wrong === 0 ? 'all cells right' : 'cells wrong') : undefined;
  const legend =
    c.kind === 'integer'
      ? [
          { tone: 'good', label: 'Digit matches the key' },
          { tone: 'bad', label: 'Digit differs from the key' },
        ]
      : c.kind === 'grid'
        ? [
            { tone: 'bad', label: 'Wrong cell (red fill = filled by mistake, red outline = left empty by mistake)' },
          ]
        : [
            { tone: 'good', label: 'Proven minimum / tiles already home' },
            { tone: 'bad', label: 'Illegal move' },
          ];
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      className="hz-rung-card"
      eyebrow={`Horizon · ${TEST_NAME[m.testId] ?? m.testId} · level ${m.level} of ${levels}${m.caption ? ` · ${m.caption}` : ''}`}
      headline={rungHeadline(m, modelLabel)}
      big={big}
      bigSub={bigSub}
      legend={legend}
    >
      <div className="hz-rung-body">
        <MiniLadder level={m.level} levels={levels} tone={tone} />
        <div className="hz-cmp">
          {c.kind === 'integer' && <Digits c={c} />}
          {c.kind === 'plan' && <Plan c={c} tone={tone} />}
          {c.kind === 'grid' && <Grids c={c} />}
        </div>
      </div>
    </VizFrame>
  );
}
