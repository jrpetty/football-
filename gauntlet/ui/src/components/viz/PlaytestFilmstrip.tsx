/**
 * The playtest as a filmstrip: the five recorded screenshots in time order, each with how much of the picture
 * changed since the one before (a small "motion" bar) and whether the controls made a difference compared with
 * an untouched copy. A plain legend explains both bars. Recorded data only.
 */
import { cx } from '../ui.tsx';
import type { JamEntry } from './gameJamModel.ts';
import './game-jam.css';

const pct = (x: number | null) => (x === null ? '—' : `${x >= 0.1 ? Math.round(x * 100) : (x * 100).toFixed(1)}%`);
/** Bars use a square-root scale so a 1% change is visible next to a 90% one. */
const bar = (x: number | null) => `${Math.round(Math.sqrt(Math.max(0, Math.min(1, x ?? 0))) * 100)}%`;

export function PlaytestFilmstrip({ entry, urlFor, selected, onSelect }: { entry: JamEntry; urlFor: (file: string) => string; selected?: number; onSelect?: (i: number) => void }) {
  const infos = entry.detail?.playtest?.frames ?? [];
  if (!entry.frames.length) {
    return <div className="jam-film-empty">{entry.detail?.playtest === null ? 'Not playtested: no headless browser was available when this was scored.' : 'No playtest screenshots were recorded for this game.'}</div>;
  }
  return (
    <div className="jam-film">
      <ol className="jam-film-strip" style={{ gridTemplateColumns: `repeat(${entry.frames.length > 5 ? 4 : 5}, minmax(0, 1fr))` }}>
        {entry.frames.map((f, i) => {
          const info = infos.find((x) => x.t === f.t);
          return (
            <li key={f.art.file} className={cx(selected === i && 'sel', info?.blank && 'blank')}>
              <button type="button" onClick={() => onSelect?.(i)} aria-label={`Screenshot at ${f.label}`}>
                <img src={urlFor(f.art.file)} alt="" loading="lazy" />
              </button>
              <span className="jam-film-t">{f.label.replace('Before any input · ', 'Start · ')}</span>
              <span className="jam-film-bars">
                <span className="jb" title="Share of the picture that changed since the previous screenshot">
                  <i className="motion" style={{ width: bar(info?.changed ?? null) }} />
                  <em>{i === 0 ? 'title' : `moved ${pct(info?.changed ?? null)}`}</em>
                </span>
                <span className="jb" title="How different the screen is from a copy nobody touched">
                  <i className="react" style={{ width: bar(info?.vsIdle ?? null) }} />
                  <em>{i === 0 ? 'before input' : `input ${pct(info?.vsIdle ?? null)}`}</em>
                </span>
              </span>
            </li>
          );
        })}
      </ol>
      <div className="jam-film-legend">
        <span>
          <i className="motion" /> moved: share of the picture that changed since the previous shot
        </span>
        <span>
          <i className="react" /> input: difference from an identical copy that nobody touched
        </span>
      </div>
    </div>
  );
}
