/**
 * The Liar's Table deduction board: one row per suspect, one column per
 * half-hour slot. Each cell shows where the suspect says they were, how many
 * other sources back it up or deny it, and turns red the moment the model's
 * questions expose a contradiction (amber when it only involves someone who
 * admitted they might be misremembering). The theft slot is marked once the
 * door log (or someone next door) pins it down.
 */
import type { LiarsFact, LiarsSimWorld } from '../../types.ts';
import { cx } from '../ui.tsx';
import { SuspectPortrait } from './SuspectPortrait.tsx';
import type { LiarsBoard as Board } from './simStory.ts';

export function roomShort(room: string): string {
  return room.replace(/ Room$/, '');
}

export function sourceName(src: string): string {
  return ({ 'DOOR LOG': 'Door log', WITNESS: 'Witness', CCTV: 'CCTV', RECEIPT: 'Receipts' } as Record<string, string>)[src] ?? src;
}

function factText(f: LiarsFact): string {
  const who = sourceName(f.src);
  if (f.bought) return `${who}: bought a drink at the bar`;
  return `${who}: ${f.yes ? '' : 'NOT '}in ${f.room}${f.hedged ? ' (unsure)' : ''}`;
}

export function LiarsBoard({ world, board, reveal, activeWho }: { world: LiarsSimWorld; board: Board; reveal: boolean; activeWho?: string }) {
  const theft = new Set(board.door);
  return (
    <div className="liars-board" role="table" aria-label="Deduction board: where each suspect was at each time">
      <div className="lb-row lb-head" role="row" style={{ ['--slots' as string]: world.slots.length }}>
        <span role="columnheader" className="lb-corner">
          Suspect
        </span>
        {world.slots.map((t, i) => (
          <span key={t} role="columnheader" className={cx('lb-slot', theft.has(i) && 'theft', reveal && i === world.theftSlot && 'truth')}>
            {t} pm
            {theft.has(i) && <small>{board.door.length > 1 ? 'door opened?' : 'door opened'}</small>}
          </span>
        ))}
      </div>
      {world.suspects.map((s) => {
        const cells = board.cells[s.name] ?? [];
        const culprit = reveal && s.name === world.culprit;
        return (
          <div key={s.name} role="row" className={cx('lb-row', culprit && 'culprit', activeWho === s.name && 'active')} style={{ ['--slots' as string]: world.slots.length }}>
            <span role="rowheader" className="lb-who">
              <SuspectPortrait name={s.name} size="1.9em" />
              <b>{s.name}</b>
            </span>
            {cells.map((c, i) => {
              const said = c.said;
              const confirm = said ? c.others.filter((f) => f.yes && f.room === said.room).length : 0;
              const fresh = board.newAt.some((n) => n.who === s.name && n.slot === i);
              const conflict = c.conflict;
              const placed = !said ? c.others.find((f) => f.yes && !f.bought) : undefined;
              const all = [...(said ? [said] : []), ...c.others];
              const title = all.length ? all.map(factText).join('\n') : 'Nothing known yet';
              return (
                <span
                  key={i}
                  role="cell"
                  className={cx('lb-cell', conflict && (conflict.hard ? 'conflict' : 'soft'), fresh && 'fresh', !all.length && 'empty', theft.has(i) && 'theft')}
                  title={title}
                >
                  {said ? (
                    <span className={cx('lb-said', said.hedged && 'hedged')}>
                      {roomShort(said.room)}
                      {said.hedged ? '?' : ''}
                    </span>
                  ) : placed ? (
                    <span className="lb-placed">
                      {roomShort(placed.room)} <small>per {sourceName(placed.src)}</small>
                    </span>
                  ) : all.length ? (
                    <span className="lb-q">–</span>
                  ) : (
                    <span className="lb-q">·</span>
                  )}
                  {conflict ? (
                    <span className="lb-why">
                      ✗ {sourceName(conflict.b.src === s.name ? conflict.a.src : conflict.b.src)}:{' '}
                      {(() => {
                        const other = conflict.b.src === s.name ? conflict.a : conflict.b;
                        return other.src === 'RECEIPT' && !other.yes ? 'no receipt' : `${other.yes ? '' : 'not '}${roomShort(other.room)}`;
                      })()}
                    </span>
                  ) : confirm > 0 ? (
                    <span className="lb-ok">✓ {confirm}</span>
                  ) : null}
                  {culprit && i === world.theftSlot && <span className="lb-truth">really: {roomShort(world.objectRoom)}</span>}
                </span>
              );
            })}
          </div>
        );
      })}
    </div>
  );
}
