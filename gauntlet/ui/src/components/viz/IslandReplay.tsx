/**
 * Survival Island replay stage: the illustrated map, the castaway's five
 * stats as meters, the inventory as icons, the camp and rescue status, a day
 * timeline with the key events, and the finale card on the last step.
 */
import type { IslandSimFrame, IslandSimWorld } from '../../types.ts';
import { cx } from '../ui.tsx';
import { IslandMap } from './IslandMap.tsx';
import { IslandTimeline } from './IslandTimeline.tsx';
import { SimFinaleCard } from './SimFinaleCard.tsx';
import { SimIcon, itemIcon, itemName, type SimIconName } from './SimIcon.tsx';
import { StatMeter } from './StatMeter.tsx';
import { islandFinale, islandHappened, islandTimeline, islandTrail, phaseName } from './simStory.ts';
import type { SimStageProps } from './SimStage.tsx';

const METERS: Array<{ key: string; label: string; icon: SimIconName }> = [
  { key: 'health', label: 'Health', icon: 'health' },
  { key: 'water', label: 'Water', icon: 'water' },
  { key: 'food', label: 'Food', icon: 'food' },
  { key: 'energy', label: 'Energy', icon: 'energy' },
  { key: 'warmth', label: 'Warmth', icon: 'warmth' },
];

const WEATHER_ICON: Record<string, SimIconName> = { clear: 'sun', cloudy: 'cloud', hot: 'hot', rain: 'rain', storm: 'storm' };

export function IslandReplay({ replay, idx, moveMs, onSeek, score, hideFinale }: SimStageProps) {
  const world = replay.sim as IslandSimWorld;
  const frames = replay.frames;
  const frame = frames[idx]!;
  const sim = frame.sim as IslandSimFrame;
  const prevFrame = idx > 0 ? frames[idx - 1] : undefined;
  const atEnd = idx >= frames.length - 1;
  const trail = islandTrail(frames, idx);
  const marks = islandTimeline(frames);
  const ev = sim.events;
  const ship = ev.includes('rescued') ? 'rescue' : ev.includes('ship-ack') ? 'ack' : ev.includes('ship-pass') ? 'pass' : null;
  const weather = world.weather[sim.day] ?? 'clear';
  const inv = Object.entries(sim.inventory).sort(([a], [b]) => a.localeCompare(b));
  const held = inv.reduce((a, [, n]) => a + n, 0);
  const fire = sim.fires.reduce((a, f) => Math.max(a, f[2]), 0);
  const num = (k: string, f = frame) => (typeof f?.stats?.[k] === 'number' ? (f.stats[k] as number) : undefined);

  return (
    <div className="sim-body sim-island">
      <div className="sim-main">
        <div className="island-wrap">
          <IslandMap world={world} frame={sim} trail={trail} ship={ship} poisonKnown={atEnd || islandHappened(frames, idx, 'poison')} reveal={atEnd} moveMs={moveMs} />
          <div className={cx('im-badge', sim.phase === 3 && 'night')}>
            <SimIcon name={sim.phase === 3 ? 'moon' : (WEATHER_ICON[weather] ?? 'sun')} size="1.4em" />
            <span>
              <b>Day {sim.day}</b> · {phaseName(sim.phase)} · {weather}
            </span>
          </div>
          {atEnd && <div className="im-reveal">Fog lifted: the whole island</div>}
        </div>
        <div className="sim-legend" aria-label="Map legend">
          <span>
            <i className="lg-dot you" /> castaway
          </span>
          <span>
            <i className="lg-trail" /> path walked
          </span>
          <span>
            <i className="lg-fog" /> unexplored (fog)
          </span>
          <span>
            <SimIcon name="spring" size="1.1em" /> spring
          </span>
          <span>
            <SimIcon name="campfire" size="1.1em" /> fire
          </span>
          <span>
            <SimIcon name="skull" size="1.1em" /> poison bush
          </span>
        </div>
      </div>

      <aside className="sim-side">
        {atEnd && !hideFinale ? (
          <SimFinaleCard finale={islandFinale(world, frames)} score={score} />
        ) : (
          <>
            <div className="sim-panel">
              <div className="sp-h">Castaway</div>
              <div className="meters">
                {METERS.map((m) => (
                  <StatMeter key={m.key} label={m.label} icon={m.icon} value={num(m.key) ?? 0} prev={prevFrame ? num(m.key, prevFrame) : undefined} />
                ))}
              </div>
            </div>
            <div className="sim-panel">
              <div className="sp-h">
                Carrying <span className="muted tnum">{held}/{world.inventoryCap}</span>
              </div>
              {inv.length || sim.spear ? (
                <div className="inv-grid">
                  {sim.spear && (
                    <span className="inv-item" title="spear (tool)">
                      <SimIcon name="spear" size="1.6em" />
                      <span>spear</span>
                    </span>
                  )}
                  {inv.map(([k, n]) => {
                    const ic = itemIcon(k);
                    return (
                      <span key={k} className="inv-item" title={`${n} ${itemName(k)}`}>
                        <SimIcon name={ic.name} colour={ic.colour} size="1.6em" />
                        <b className="tnum">{n}</b>
                        <span>{itemName(k)}</span>
                      </span>
                    );
                  })}
                </div>
              ) : (
                <p className="muted sp-empty">Empty-handed</p>
              )}
            </div>
            <div className="sim-panel camp">
              <div className="sp-h">Camp &amp; rescue</div>
              <div className="camp-row">
                <span className={cx('camp-chip', sim.signal !== 'none' && 'on', sim.signal === 'lit' && 'hot')}>
                  <SimIcon name={sim.signal === 'lit' ? 'signal-lit' : 'signal'} size="1.3em" />
                  {sim.signal === 'lit' ? 'Signal BLAZING' : sim.signal === 'built' ? 'Signal pile ready' : 'No signal pile'}
                </span>
                <span className={cx('camp-chip', fire > 0 && 'on')}>
                  <SimIcon name="campfire" size="1.3em" />
                  {fire > 0 ? `Campfire (${fire} night${fire === 1 ? '' : 's'} of fuel)` : 'No campfire'}
                </span>
                <span className={cx('camp-chip', sim.shelters.length > 0 && 'on')}>
                  <SimIcon name="shelter" size="1.3em" />
                  {sim.shelters.length ? 'Shelter built' : 'No shelter'}
                </span>
                <span className={cx('camp-chip', sim.sightings > 0 && 'on')}>
                  <SimIcon name="ship" size="1.3em" />
                  Ship saw signal {sim.sightings}/{world.signalsNeeded}
                </span>
              </div>
            </div>
          </>
        )}
      </aside>

      <IslandTimeline maxDays={world.maxDays} day={sim.day} phase={sim.phase} marks={marks} idx={idx} onSeek={onSeek} />
    </div>
  );
}
