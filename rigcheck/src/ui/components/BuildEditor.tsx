import { useState } from 'react';
import { PartPicker } from './Parts.tsx';
import { engineData } from '../store.ts';
import type { Build, RamConfig, Resolution } from '../../core/types.ts';
import { RESOLUTIONS } from '../../core/types.ts';

const STORAGE: Build['storage'][] = ['hdd', 'sata-ssd', 'nvme-gen3', 'nvme-gen4'];
const CHANNELS: RamConfig['channels'][] = [1, 2, 4];

export function BuildEditor({
  build,
  onChange,
  onRemove,
  compact,
}: {
  build: Build;
  onChange: (b: Build) => void;
  onRemove?: () => void;
  compact?: boolean;
}) {
  const set = (patch: Partial<Build>) => onChange({ ...build, ...patch });
  const setRam = (patch: Partial<RamConfig>) => onChange({ ...build, ram: { ...build.ram, ...patch } });

  /**
   * Memory, storage and refresh are folded away until asked for.
   *
   * The processor and the graphics card decide almost everything; the other
   * six fields matter at the margins and matter a great deal to the handful of
   * people who know why. Presenting all eight with equal weight meant somebody
   * who only wanted to know how a 4070 does had to walk past four memory
   * controls to find out.
   *
   * The VALUES stay on screen — a summary line, always visible. Only the
   * controls are hidden, so nothing is concealed and nothing has to be opened
   * to be checked. It opens itself when a value is unusual enough to be worth
   * seeing, which is the case where hiding it would actually cost something:
   * single-channel memory and a mechanical drive are two of the largest
   * silent losses this model knows about.
   */
  const unusual =
    build.ram.channels === 1 || build.storage === 'hdd' || build.ram.totalGB < 16;
  const [detail, setDetail] = useState(unusual);

  const cpu = engineData.cpus.get(build.cpuId);
  const gpu = engineData.gpus.get(build.gpuId);

  // Surface an incompatibility the moment it exists rather than at estimate time.
  const ramMismatch = cpu && build.ram.type && !cpu.memoryType.includes(build.ram.type);
  const channelMismatch = cpu && build.ram.channels > cpu.maxMemChannels;

  return (
    <div className="panel">
      <div className="panel-head">
        <input
          type="text"
          value={build.label ?? ''}
          placeholder="untitled build"
          onChange={(e) => set({ label: e.target.value })}
          style={{ width: 180, background: 'transparent', border: '1px solid transparent', padding: '2px 4px' }}
        />
        <span className="spacer" />
        {gpu?.vramGB != null && <span className="chip">{gpu.vramGB}GB VRAM</span>}
        {cpu && <span className="chip">{cpu.cores}C/{cpu.threads}T</span>}
        {onRemove && (
          <button className="btn danger" onClick={onRemove} title="remove build">
            remove
          </button>
        )}
      </div>
      <div className="panel-body">
        <PartPicker kind="cpu" label="CPU" value={build.cpuId} onChange={(cpuId) => set({ cpuId })} />
        <PartPicker kind="gpu" label="GPU" value={build.gpuId} onChange={(gpuId) => set({ gpuId })} />

        {!compact && (
          <>
            <button
              className="disclosure"
              aria-expanded={detail}
              onClick={() => setDetail((d) => !d)}
            >
              <span aria-hidden="true">{detail ? '▾' : '▸'}</span>
              <span className="k">memory, storage and screen</span>
              <span className="summary">
                {build.ram.totalGB}GB {build.ram.type ?? ''} {build.ram.channels}-channel ·{' '}
                {build.storage} · {build.target.resolution} at {build.target.refreshHz}Hz
              </span>
            </button>
          </>
        )}

        {!compact && detail && (
          <>
            <div className="field">
              <label>memory</label>
              <div style={{ display: 'flex', gap: 6 }}>
                <input
                  type="number"
                  min={2}
                  max={256}
                  value={build.ram.totalGB}
                  onChange={(e) => setRam({ totalGB: Number(e.target.value) })}
                  style={{ width: 70 }}
                  title="capacity GB"
                />
                <select
                  value={build.ram.channels}
                  onChange={(e) => setRam({ channels: Number(e.target.value) as RamConfig['channels'] })}
                  title="channels"
                >
                  {CHANNELS.map((c) => (
                    <option key={c} value={c}>{c}-channel</option>
                  ))}
                </select>
                <input
                  type="number"
                  step={100}
                  value={build.ram.speedMTs}
                  onChange={(e) => setRam({ speedMTs: Number(e.target.value) })}
                  style={{ width: 90 }}
                  title="MT/s"
                />
                <select
                  value={build.ram.type ?? 'DDR4'}
                  onChange={(e) => setRam({ type: e.target.value as RamConfig['type'] })}
                  title="memory type"
                >
                  <option>DDR3</option>
                  <option>DDR4</option>
                  <option>DDR5</option>
                </select>
              </div>
            </div>

            {(ramMismatch || channelMismatch) && (
              <div className="note bad" style={{ marginBottom: 10 }}>
                {ramMismatch && <div>{cpu!.fullName} is {cpu!.socket} and takes {cpu!.memoryType.join('/')}, not {build.ram.type}.</div>}
                {channelMismatch && <div>{cpu!.fullName} supports at most {cpu!.maxMemChannels} memory channels.</div>}
              </div>
            )}

            <div className="field">
              <label>storage</label>
              <select value={build.storage} onChange={(e) => set({ storage: e.target.value as Build['storage'] })}>
                {STORAGE.map((s) => (
                  <option key={s} value={s}>{s}</option>
                ))}
              </select>
            </div>
          </>
        )}

        {(compact || detail) && (
          <div className="field">
            <label>target</label>
            <div style={{ display: 'flex', gap: 6 }}>
              <select
                value={build.target.resolution}
                onChange={(e) => set({ target: { ...build.target, resolution: e.target.value as Resolution } })}
              >
                {RESOLUTIONS.map((r) => (
                  <option key={r} value={r}>{r}</option>
                ))}
              </select>
              <input
                type="number"
                value={build.target.refreshHz}
                onChange={(e) => set({ target: { ...build.target, refreshHz: Number(e.target.value) } })}
                style={{ width: 80 }}
                title="refresh Hz"
              />
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * Choosing which games to estimate against.
 *
 * This was fifty toggle buttons grouped by archetype, plus three bulk actions —
 * fifty-four of the sixty-six controls on the analyser, and the single biggest
 * reason that screen felt like a control panel rather than an answer. Fifty
 * things laid out at once is not a choice offered, it is a search the reader has
 * to perform by eye.
 *
 * It is a search box now, with what is chosen shown as removable chips. The
 * cost is real and worth naming: selecting several games takes more clicks than
 * it did, and the full list is no longer browsable at a glance. The two
 * presets stay for exactly that reason — almost nobody wants a bespoke set, and
 * the people who do can type.
 *
 * Chips sit ABOVE the box, not below it. The list opens downwards and stays
 * open so several games can be added in a row, which means anything underneath
 * the box is covered by it — with the chips there, removing the game you just
 * added meant closing the list first.
 */
export function GamePicker({
  selected,
  onChange,
}: {
  selected: string[];
  onChange: (ids: string[]) => void;
}) {
  const games = [...engineData.games.values()];
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [cursor, setCursor] = useState(0);

  const chosen = selected.map((id) => games.find((g) => g.id === id)).filter(Boolean) as typeof games;
  const matches = games
    .filter((g) => !selected.includes(g.id))
    .filter((g) => !q.trim() || g.name.toLowerCase().includes(q.trim().toLowerCase()))
    .slice(0, 40);

  const add = (id: string) => {
    onChange([...selected, id]);
    setQ('');
    setCursor(0);
  };

  return (
    <div>
      {chosen.length > 0 && (
        <div className="chip-row" style={{ marginTop: 0, marginBottom: 8 }}>
          {chosen.map((g) => (
            <span className="chip on" key={g.id}>
              {g.name}
              <button
                aria-label={`Remove ${g.name}`}
                onClick={() => onChange(selected.filter((x) => x !== g.id))}
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}
      <div className="combo">
        <input
          type="text"
          value={q}
          placeholder={selected.length ? 'add another game…' : 'search games…'}
          onFocus={() => setOpen(true)}
          // A click outside has to close this, and blur fires BEFORE the click
          // lands on an option — so the option is chosen on mousedown and the
          // close is deferred past it.
          onBlur={() => setTimeout(() => setOpen(false), 120)}
          onChange={(e) => { setQ(e.target.value); setOpen(true); setCursor(0); }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setCursor((c) => Math.min(c + 1, matches.length - 1)); }
            else if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(c - 1, 0)); }
            else if (e.key === 'Enter' && matches[cursor]) { e.preventDefault(); add(matches[cursor].id); }
            else if (e.key === 'Escape') setOpen(false);
            // Backspace on an empty box removes the last chip, which is what
            // every other chip input does and what fingers expect.
            else if (e.key === 'Backspace' && !q && selected.length) onChange(selected.slice(0, -1));
          }}
        />
        {open && matches.length > 0 && (
          <div className="combo-list">
            {matches.map((g, i) => (
              <div
                key={g.id}
                className={`combo-item${i === cursor ? ' cursor' : ''}`}
                onMouseDown={(e) => { e.preventDefault(); add(g.id); }}
                onMouseEnter={() => setCursor(i)}
              >
                <span className="label">{g.name}</span>
                <span className="disambig">
                  {g.archetype}
                  {g.builtInBenchmark ? ' · has a built-in benchmark' : ''}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>


      <div className="mini" style={{ marginTop: 8, display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <span>{selected.length} selected</span>
        <button className="btn tiny" onClick={() => onChange(games.filter((g) => g.coreLoop).map((g) => g.id))}>
          use the core 12
        </button>
        {selected.length > 0 && (
          <button className="btn tiny" onClick={() => onChange([])}>clear</button>
        )}
      </div>
    </div>
  );
}
