/**
 * The Escape Room replay stage: the floor plan, moves against par, every lock
 * with the model's attempts (wrong ones struck through in red), the clue it
 * read and — once the lock is open, or at the end — the correct answer side
 * by side with what the model entered, plus what the player carries.
 */
import type { EscapeSimFrame, EscapeSimWorld } from '../../types.ts';
import { cx } from '../ui.tsx';
import { EscapeFloorPlan } from './EscapeFloorPlan.tsx';
import { ParMeter } from './ParMeter.tsx';
import { SimFinaleCard } from './SimFinaleCard.tsx';
import { SimIcon, type SimIconName } from './SimIcon.tsx';
import { charDiff, clip, escapeFinale, escapeLocks, type LockState } from './simStory.ts';
import type { SimStageProps } from './SimStage.tsx';

const KIND_ICON: Record<string, SimIconName> = { key: 'key', code: 'lock', word: 'clue', colour: 'lock', tool: 'item', combo: 'item' };
const KIND_LABEL: Record<string, string> = { key: 'key lock', code: 'number code', word: 'word lock', colour: 'colour sequence', tool: 'needs a tool', combo: 'needs a combined item' };

function Attempt({ value, answer, ok, reveal }: { value: string; answer?: string; ok: boolean; reveal: boolean }) {
  if (ok) return <span className="att ok mono">{value}</span>;
  if (!reveal || !answer) return <span className="att bad mono">{value}</span>;
  return (
    <span className="att bad mono" title="Wrong: red characters differ from the answer">
      {charDiff(value, answer).map((c, i) => (
        <span key={i} className={c.ok ? 'same' : 'diff'}>
          {c.ch}
          {value.includes(' ') ? ' ' : ''}
        </span>
      ))}
    </span>
  );
}

function LockRow({ st, world, examined, reveal, flash }: { st: LockState; world: EscapeSimWorld; examined: string[]; reveal: boolean; flash: string | null }) {
  const l = st.lock;
  const showAnswer = (st.open || reveal) && !!l.answer;
  const clue = l.clues.find((c) => examined.includes(c) && world.clues[c]);
  return (
    <li className={cx('lock-row', st.open && 'open', flash)}>
      <div className="lr-head">
        <SimIcon name={st.open ? 'unlock' : 'lock'} size="1.3em" />
        <b className="lr-name">{l.name}</b>
        <span className="lr-kind">
          <SimIcon name={KIND_ICON[l.kind] ?? 'lock'} size="1em" /> {KIND_LABEL[l.kind] ?? l.kind}
        </span>
        <span className={cx('lr-state', st.open ? 'ok' : 'no')}>{st.open ? `opened · move ${st.openedAt}` : 'locked'}</span>
      </div>
      {(st.attempts.length > 0 || showAnswer || (l.item && (st.open || reveal))) && (
        <div className="lr-body">
          {st.attempts.length > 0 && (
            <span className="lr-tries">
              <span className="lr-k">Model entered</span>
              {st.attempts.map((a, i) => (
                <Attempt key={i} value={a.value} answer={l.answer} ok={a.ok} reveal={showAnswer} />
              ))}
            </span>
          )}
          {showAnswer && (
            <span className="lr-answer">
              <span className="lr-k">Correct</span>
              <span className="att ok mono">{l.answer}</span>
            </span>
          )}
          {l.item && (st.open || reveal) && (
            <span className="lr-answer">
              <span className="lr-k">Opens with</span>
              <span className="att neutral">{l.item}</span>
            </span>
          )}
        </div>
      )}
      {clue && !st.open && <p className="lr-clue">Clue read — {world.clues[clue]!.name}: “{clip(world.clues[clue]!.text, 130)}”</p>}
    </li>
  );
}

export function EscapeReplay({ replay, idx, moveMs, score, hideFinale }: SimStageProps) {
  const world = replay.sim as EscapeSimWorld;
  const frames = replay.frames;
  const sim = frames[idx]!.sim as EscapeSimFrame;
  const atEnd = idx >= frames.length - 1;
  const locks = escapeLocks(world, frames, idx);
  const e = sim.event;
  const flashOf = (id: string) => (e.target === id ? (e.type === 'unlock' || e.type === 'escape' ? 'pop' : e.type === 'wrong' || e.type === 'fail' ? 'shake' : null) : null);
  const byRoom = world.rooms.map((name, r) => ({ name, r, locks: locks.filter((l) => l.lock.room === r) }));
  return (
    <div className="sim-body sim-escape">
      <div className="es-plan">
        <EscapeFloorPlan world={world} frame={sim} locks={locks} moveMs={moveMs} />
        <div className="sim-legend" aria-label="Floor plan legend">
          <span>
            <SimIcon name="lock" size="1.1em" /> locked
          </span>
          <span>
            <SimIcon name="unlock" size="1.1em" /> open
          </span>
          <span>
            <i className="lg-dot you" /> player
          </span>
          <span>
            <i className="lg-box here" /> current room
          </span>
          <span>
            <span className="att bad mono sm">1234</span> wrong entry
          </span>
          <span>
            <span className="att ok mono sm">4321</span> correct answer
          </span>
        </div>
      </div>
      <div className="sim-main">
        {atEnd && !hideFinale && <SimFinaleCard finale={escapeFinale(world, frames)} score={score} />}
        <div className="sim-panel">
          <ParMeter used={sim.move} par={world.optimal} budget={world.budget} />
        </div>
        <div className="sim-panel">
          <div className="sp-h">
            Carrying <span className="muted tnum">{sim.inventory.length}</span>
          </div>
          {sim.inventory.length ? (
            <div className="chip-row">
              {sim.inventory.map((n) => (
                <span key={n} className="inv-chip">
                  <SimIcon name={/KEY$/.test(n) ? 'key' : 'item'} size="1.2em" />
                  {n}
                </span>
              ))}
            </div>
          ) : (
            <p className="muted sp-empty">Nothing yet</p>
          )}
        </div>
      </div>
      <aside className="sim-side">
        <div className="sim-panel">
          <div className="sp-h">
            Locks <span className="muted tnum">{locks.filter((l) => l.open).length}/{locks.length} open</span>
          </div>
          {byRoom.map((g) => (
            <div key={g.name} className={cx('lock-group', !sim.visited.includes(g.r) && !atEnd && 'unseen')}>
              <div className="lg-room">
                Room {g.r + 1} · {g.name}
              </div>
              {sim.visited.includes(g.r) || atEnd ? (
                <ul className="lock-list">
                  {g.locks.map((st) => (
                    <LockRow key={st.lock.id} st={st} world={world} examined={sim.examined} reveal={atEnd} flash={flashOf(st.lock.id)} />
                  ))}
                </ul>
              ) : (
                <p className="muted sp-empty">Not reached yet · {g.locks.length} locks</p>
              )}
            </div>
          ))}
        </div>
      </aside>
    </div>
  );
}
