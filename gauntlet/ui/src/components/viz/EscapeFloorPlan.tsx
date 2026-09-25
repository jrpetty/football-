/**
 * Floor plan of the three escape rooms: the doors between them (locked /
 * open), the exit, every lock inside each room (red = locked, green = open),
 * the room the player is in, and a pop / shake the moment a lock opens or a
 * wrong code is entered. Pure SVG.
 */
import type { EscapeSimFrame, EscapeSimWorld } from '../../types.ts';
import { cx } from '../ui.tsx';
import type { LockState } from './simStory.ts';

const RW = 222;
const RH = 250;
const GAP = 40;
const X0 = 16;
const Y0 = 34;

function short(name: string, max = 15): string {
  return name.length > max ? `${name.slice(0, max - 1)}…` : name;
}

function Padlock({ open }: { open: boolean }) {
  return (
    <g>
      <path d={open ? 'M-7 -2 V-8 a7 7 0 0 1 13 -3' : 'M-7 -2 V-8 a7 7 0 0 1 14 0 V-2'} fill="none" stroke={open ? '#7ee29a' : '#dfe5ee'} strokeWidth="3" />
      <rect x="-11" y="-3" width="22" height="17" rx="3.5" fill={open ? '#1f9d4d' : '#c9403c'} />
      <circle cx="0" cy="5" r="2.4" fill="rgba(0,0,0,.45)" />
    </g>
  );
}

export function EscapeFloorPlan({ world, frame, locks, moveMs }: { world: EscapeSimWorld; frame: EscapeSimFrame; locks: LockState[]; moveMs: number }) {
  const W = X0 * 2 + RW * 3 + GAP * 3 + 70;
  const H = Y0 + RH + 24;
  const e = frame.event;
  const flash = (id: string) => (e.target === id ? (e.type === 'unlock' || e.type === 'escape' ? 'pop' : e.type === 'wrong' ? 'shake' : e.type === 'fail' ? 'shake' : null) : null);
  const roomX = (r: number) => X0 + r * (RW + GAP);
  const tokenRoom = frame.escaped ? 3 : frame.room;
  const tokenX = frame.escaped ? roomX(2) + RW + GAP + 34 : roomX(frame.room) + RW / 2;
  return (
    <svg className="escape-plan" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Floor plan. ${frame.escaped ? 'Escaped.' : `In ${world.rooms[frame.room]}.`} ${frame.unlocked.length} of ${world.locks.length} locks open.`}>
      {world.rooms.map((name, r) => {
        const visited = frame.visited.includes(r);
        const here = !frame.escaped && frame.room === r;
        const inside = locks.filter((l) => !l.lock.door && l.lock.room === r);
        return (
          <g key={name} className={cx('ep-room', visited && 'visited', here && 'here')}>
            <rect className="ep-room-bg" x={roomX(r)} y={Y0} width={RW} height={RH} rx="14" />
            <text className="ep-num" x={roomX(r) + 14} y={Y0 - 10}>
              ROOM {r + 1}
            </text>
            <text className="ep-name" x={roomX(r) + RW / 2} y={Y0 + 30} textAnchor="middle">
              {short(name.replace(/^The /, ''), 18)}
            </text>
            {!visited && (
              <text className="ep-unknown" x={roomX(r) + RW / 2} y={Y0 + RH / 2 + 18} textAnchor="middle">
                ?
              </text>
            )}
            {visited &&
              inside.map((l, i) => {
                // One lock per line: padlock on the left, its name beside it.
                const cx0 = roomX(r) + 34;
                const cy0 = Y0 + 72 + i * 52;
                const fx = flash(l.lock.id);
                const wrong = l.attempts.filter((a) => !a.ok).length;
                return (
                  <g key={l.lock.id} className={cx('ep-lock', l.open && 'open', fx)} transform={`translate(${cx0} ${cy0})`}>
                    <title>{`${l.lock.name} (${l.lock.tag}) — ${l.open ? 'open' : 'locked'}`}</title>
                    <g className="ep-lock-ico">
                      <g transform="scale(1.35)">
                        <Padlock open={l.open} />
                      </g>
                    </g>
                    <text x="26" y="7" className="ep-lock-name">
                      {short(l.lock.name, wrong ? 11 : 14)}
                      {wrong > 0 && <tspan className="ep-wrong-count"> ✗{wrong}</tspan>}
                    </text>
                  </g>
                );
              })}
          </g>
        );
      })}

      {locks
        .filter((l) => l.lock.door)
        .map((l) => {
          const r = l.lock.room;
          const x = roomX(r) + RW + GAP / 2;
          const exit = l.lock.to === 'exit';
          const fx = flash(l.lock.id);
          return (
            <g key={l.lock.id} className={cx('ep-door', l.open && 'open', exit && 'exit', fx)} transform={`translate(${x} ${Y0 + RH / 2})`}>
              <title>{`${l.lock.name} (${l.lock.tag}) — ${l.open ? 'open' : 'locked'}`}</title>
              <rect x={-GAP / 2 - 2} y="-34" width={GAP + 4} height="68" rx="6" className="ep-door-frame" />
              <g className="ep-lock-ico">
                <Padlock open={l.open} />
              </g>
              <text y="52" textAnchor="middle" className="ep-door-name">
                {exit ? 'EXIT' : short(l.lock.name, 12)}
              </text>
            </g>
          );
        })}

      <g className={cx('ep-outside', frame.escaped && 'reached')} transform={`translate(${roomX(2) + RW + GAP + 8} ${Y0 + RH / 2})`}>
        <circle cx="26" cy="0" r="24" />
        <text x="26" y="-32" textAnchor="middle">
          OUTSIDE
        </text>
      </g>

      <g className="ep-token" style={{ transform: `translate(${tokenX}px, ${Y0 + RH - 38}px)`, transitionDuration: `${moveMs}ms` }} data-room={tokenRoom}>
        <circle r="17" fill="#ef4444" stroke="#fff" strokeWidth="3" />
        <circle cy="-5" r="4.5" fill="#fff" />
        <path d="M-8 11 q8 -16 16 0z" fill="#fff" />
      </g>
    </svg>
  );
}
