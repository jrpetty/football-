/** Shortest Plans (four pegs) and Exact Maths (snakes and ladders) levels as three beats. */
import { useMemo, useRef } from 'react';
import { fractionCompare, pegsSentence, snakesBoard, snakesSentence, statedLine } from '../../../../../src/presenter/visuals/closeup.ts';
import { parsePlanPuzzle, planVisual, type PlanFrame, type PlanPuzzle, type PlanVisual } from '../../../../../src/presenter/visuals/planning.ts';
import { PlanPuzzleScene } from '../PlanPuzzleScene.tsx';
import { StepControls, useStepPlayer } from '../StepScrubber.tsx';
import { cx } from '../../ui.tsx';
import { BeatLayout, Chip, type Beat } from './parts.tsx';
import { DecimalCompare, FractionView, SnakesBoardStrip, SnakesLegend } from './SnakesBoard.tsx';
import type { KindProps } from './types.ts';
import { TextBeats } from './otherBeats.tsx';

function safe<T>(f: () => T): T | null {
  try {
    return f();
  } catch {
    return null;
  }
}

// ───────────────────────────── pegs ─────────────────────────────

type Hanoi = Extract<PlanPuzzle, { kind: 'hanoi' }>;

function PlanReplay({ puzzle, frames, autoPlay, label }: { puzzle: PlanPuzzle; frames: PlanFrame[]; autoPlay?: boolean; label: string }) {
  const root = useRef<HTMLDivElement>(null);
  const p = useStepPlayer(frames.length, root, { baseMs: frames.length > 30 ? 520 : 900, autoPlay });
  const f = frames[p.idx]!;
  const last = p.idx === frames.length - 1;
  return (
    <div className="lc-replay" ref={root} tabIndex={0} onKeyDown={p.onKeyDown} aria-label={label}>
      <div className="lc-replay-narr" aria-live="polite">
        <span className={cx('lc-replay-step', last && 'done')}>{p.idx === 0 ? 'Start' : last ? 'Solved' : `Move ${p.idx}`}</span>
        <span>{f.action ?? 'Press play to watch the shortest plan, move by move.'}</span>
        <span className="tnum lc-replay-n">
          {p.idx} / {frames.length - 1}
        </span>
      </div>
      <div className="lc-scene">
        <PlanPuzzleScene puzzle={puzzle} frames={frames} idx={p.idx} />
      </div>
      <StepControls p={p} tones={frames.map((_, i) => (i === frames.length - 1 ? 'good' : null))} />
    </div>
  );
}

function MoveBars({ said, best }: { said: number | null; best: number }) {
  const max = Math.max(best, said ?? 0, 1);
  return (
    <div className="lc-bars">
      <div className={cx('lc-bar-row', said === best ? 'good' : 'bad')}>
        <span>Model said</span>
        <i style={{ width: `${((said ?? 0) / max) * 100}%` }} />
        <b className="tnum">{said ?? '—'}</b>
      </div>
      <div className="lc-bar-row key">
        <span>Shortest plan</span>
        <i style={{ width: `${(best / max) * 100}%` }} />
        <b className="tnum">{best}</b>
      </div>
    </div>
  );
}

export function PegBeats(p: KindProps) {
  const puzzle = useMemo(() => safe(() => parsePlanPuzzle(p.prompt)), [p.prompt]);
  const key: PlanVisual | null = useMemo(() => safe(() => planVisual(p.q)), [p.q]);
  const v: PlanVisual | null = useMemo(() => (p.a ? safe(() => planVisual(p.a!)) : null), [p.a]);
  if (!puzzle || puzzle.kind !== 'hanoi') return <TextBeats {...p} />;
  const z = puzzle as Hanoi;
  const goal = z.pegs[z.target];
  const startFrame: PlanFrame[] = [{ state: { kind: 'hanoi', v: z.start }, action: null, mark: [], total: 0 }];
  const where = z.start.map((d, i) => `peg ${z.pegs[i]}: ${d.length ? d.join(', ') : 'empty'}`).join('; ');
  const question: Beat = {
    sub: (
      <>
        {z.disks} disks · {z.pegs.length} pegs
      </>
    ),
    alt: `${z.disks} disks scattered over ${z.pegs.length} pegs (bottom to top) — ${where}. Goal: all on peg ${goal}, moving one disk at a time and never onto a smaller disk. What is the fewest number of moves?`,
    node: (
      <div className="lc-pegs-q">
        <div className="lc-scene">
          <PlanPuzzleScene puzzle={z} frames={startFrame} idx={0} />
        </div>
        <div className="lc-goal">
          <Chip tone="accent">Goal</Chip> all {z.disks} disks on peg {goal}
        </div>
        <ul className="lc-rules">
          <li>Move one disk at a time, from the top of a peg.</li>
          <li>Never put a disk on a smaller one.</li>
        </ul>
        <p className="lc-ask">What is the fewest number of moves?</p>
      </div>
    ),
  };
  const best = key?.optimum ?? (typeof p.q.expected === 'number' ? p.q.expected : Number(p.q.expected));
  const keyFrames = key?.frames && key.puzzle ? key.frames : null;
  const keyBeat: Beat = {
    sub: 'Proven by searching every position',
    alt: `The shortest plan takes ${best} moves.`,
    node: (
      <div className="lc-said-big">
        <b className="tnum">{best}</b>
        <span>moves is the minimum</span>
      </div>
    ),
  };
  let answer: Beat | null = null;
  let result: Beat | null = null;
  let sentence: string | null = null;
  if (p.a) {
    const said = v?.claimed ?? null;
    answer = {
      sub: 'Its claimed minimum',
      alt: said === null ? `No number of moves found (it wrote “${statedLine(p.a)}”).` : `The model said ${said} moves.`,
      node: (
        <div className="lc-said-big">
          <span>said</span>
          <b className="tnum">{said ?? '—'}</b>
          <span>moves</span>
        </div>
      ),
    };
    result = {
      alt: said === null ? `No number given; the shortest plan takes ${best} moves.` : `${said} moves against a shortest plan of ${best} moves.`,
      node: (
        <div>
          <div className="lc-res-k">{said === null ? 'No number given' : said === best ? `${said} moves: exactly the shortest` : `${said} moves, shortest is ${best}`}</div>
          <MoveBars said={said} best={best} />
          {keyFrames && <div className="lc-k">One shortest plan, from the answer key’s notes, replayed below</div>}
        </div>
      ),
    };
    sentence = pegsSentence(said, best, p.a.score, p.passMark);
  }
  const replay = keyFrames ? <PlanReplay puzzle={key!.puzzle!} frames={keyFrames} autoPlay={p.mode === 'slide'} label={`One shortest plan, ${keyFrames.length - 1} moves, step by step`} /> : null;
  return <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={result} verdict={p.verdict} sentence={sentence} answerHead={p.answerHead} keyBeat={keyBeat} interactiveResult={replay} interactiveKey={replay} />;
}

// ───────────────────────────── snakes and ladders ─────────────────────────────

export function SnakesBeats(p: KindProps) {
  const board = useMemo(() => safe(() => snakesBoard(p.prompt)), [p.prompt]);
  const key = useMemo(() => safe(() => fractionCompare(p.q)), [p.q]);
  const c = useMemo(() => (p.a ? safe(() => fractionCompare(p.a!)) : null), [p.a]);
  if (!board) return <TextBeats {...p} />;
  const question: Beat = {
    sub: (
      <>
        {board.finish} squares · spinner 1–{board.faces}
      </>
    ),
    alt: `A board of squares 0 to ${board.finish}; reach ${board.finish} or beyond to win. Spinner 1 to ${board.faces}. Ladders: ${board.ladders.map(([f, t]) => `${f} up to ${t}`).join(', ') || 'none'}. Snakes: ${board.snakes.map(([h, t]) => `${h} down to ${t}`).join(', ') || 'none'}. Traps (you lose): ${board.traps.join(', ') || 'none'}. What is the exact probability of winning?`,
    node: (
      <div className="lc-snakes-q">
        <SnakesBoardStrip b={board} />
        <SnakesLegend b={board} />
        <p className="lc-ask">What is the exact chance of reaching square {board.finish}? (As a fraction: a decimal scores 0.)</p>
      </div>
    ),
  };
  const keyBeat: Beat | null = key
    ? {
        sub: 'Computed twice, by two exact methods',
        alt: `The exact probability is ${key.keyText}, about ${key.keyDecimal.slice(0, 8)}.`,
        node: (
          <div className="lc-frac-box">
            <FractionView f={key.key} tone="key" />
            <span className="lc-approx">
              ≈ <DecimalCompare text={key.keyDecimal} same={99} tone="key" />
            </span>
          </div>
        ),
      }
    : null;
  let answer: Beat | null = null;
  let result: Beat | null = null;
  let sentence: string | null = null;
  if (p.a && c) {
    answer = {
      sub: 'Its exact fraction',
      alt: c.given ? `The model answered ${c.givenText}, about ${c.givenDecimal!.slice(0, 8)}.` : `The model answered “${c.stated}”, not an exact fraction.`,
      node: c.given ? (
        <div className="lc-frac-box">
          <FractionView f={c.given} tone="plain" />
          <span className="lc-approx">≈ {c.givenDecimal!.slice(0, 10)}…</span>
        </div>
      ) : (
        <p className="lc-said">{c.stated ? `“${c.stated.slice(0, 80)}”` : 'No final answer'}</p>
      ),
    };
    result = {
      alt: c.equal ? `Exactly the key, ${c.keyText}.` : `Model about ${c.givenDecimal?.slice(0, 10) ?? 'nothing'}; key ${c.keyText}, about ${c.keyDecimal.slice(0, 10)}.`,
      node: (
        <div className="lc-snakes-res">
          <div className="lc-dec-rows">
            <div>
              <span className="lc-k">Model</span>
              {c.givenDecimal ? <DecimalCompare text={c.givenDecimal} same={c.equal ? 99 : c.sameDecimals} tone={c.equal ? 'good' : 'bad'} /> : <span className="lc-empty">no fraction</span>}
            </div>
            <div>
              <span className="lc-k">Key</span>
              <DecimalCompare text={c.keyDecimal} same={99} tone="key" />
            </div>
          </div>
          {!c.equal && (
            <div className="lc-frac-key">
              <span className="lc-k">The exact answer</span>
              <FractionView f={c.key} tone="key" size="md" />
            </div>
          )}
        </div>
      ),
    };
    sentence = snakesSentence(c, p.a.score, p.passMark);
  } else if (p.a) {
    const said = statedLine(p.a);
    answer = { alt: said ? `The model answered: ${said}` : 'No answer recorded.', node: <p className="lc-said">{said || 'No final answer'}</p> };
  }
  return <BeatLayout question={question} prompt={p.prompt} answer={answer} reply={p.reply} result={result} verdict={p.verdict} sentence={sentence} answerHead={p.answerHead} keyBeat={keyBeat} />;
}
