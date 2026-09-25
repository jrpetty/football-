/**
 * The day strip under the island: one column per day (morning, afternoon,
 * evening, night), the current moment highlighted, and icons for the key
 * events (found water, poison berries, shelter, signal fire, ship, rescue,
 * death). Future events are dimmed; clicking one jumps the replay there.
 */
import type { IslandEventTag } from '../../types.ts';
import { cx } from '../ui.tsx';
import { SimIcon, type SimIconName } from './SimIcon.tsx';
import { ISLAND_EVENTS, type TimelineMark } from './simStory.ts';

export const EVENT_ICON: Partial<Record<IslandEventTag, SimIconName>> = {
  rescued: 'flag',
  died: 'skull',
  survived: 'check',
  poison: 'skull',
  'ship-ack': 'ship',
  'ship-pass': 'ship',
  'found-water': 'spring',
  'signal-lit': 'signal-lit',
  'signal-built': 'signal',
  shelter: 'shelter',
  campfire: 'campfire',
  spear: 'spear',
  bottle: 'bottle',
};

export function IslandTimeline({ maxDays, day, phase, marks, idx, onSeek }: { maxDays: number; day: number; phase: number; marks: TimelineMark[]; idx: number; onSeek?: (frame: number) => void }) {
  const days = Array.from({ length: maxDays }, (_, i) => i + 1);
  const cur = (day - 1) * 4 + Math.max(0, phase);
  return (
    <div className="island-timeline" style={{ ['--days' as string]: maxDays }} aria-label={`Day ${day} of ${maxDays}`}>
      {days.map((d) => {
        const here = marks.filter((m) => m.day === d);
        const seen = new Set<string>();
        const uniq = here.filter((m) => (seen.has(m.tag) ? false : (seen.add(m.tag), true)));
        return (
          <div key={d} className={cx('it-day', d === day && 'current', d < day && 'past')}>
            <div className="it-marks">
              {uniq.slice(0, 3).map((m) => {
                const icon = EVENT_ICON[m.tag];
                if (!icon) return null;
                const future = m.frame > idx;
                return (
                  <button
                    key={`${m.tag}-${m.frame}`}
                    type="button"
                    className={cx('it-mark', `tone-${ISLAND_EVENTS[m.tag].tone}`, future && 'future')}
                    onClick={() => onSeek?.(m.frame)}
                    title={`Day ${d}: ${ISLAND_EVENTS[m.tag].label}`}
                    aria-label={`Jump to day ${d}: ${ISLAND_EVENTS[m.tag].label}`}
                  >
                    <SimIcon name={icon} size="100%" />
                  </button>
                );
              })}
            </div>
            <div className="it-bar">
              {[0, 1, 2, 3].map((p) => {
                const q = (d - 1) * 4 + p;
                return <i key={p} className={cx(p === 3 && 'night', q < cur && 'done', q === cur && 'now')} />;
              })}
            </div>
            <span className="it-label tnum">{d}</span>
          </div>
        );
      })}
    </div>
  );
}
