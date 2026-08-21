import { useState } from 'react';
import { PartPicker } from './Parts.tsx';
import { useApp } from '../store.ts';
import type { MemoryType, PartInventory, RamConfig, Storage } from '../../core/types.ts';

/**
 * The parts you have on hand, edited the same way wherever you are asked for
 * them.
 *
 * Trade Desk and Inventory Optimiser both ask "what have you got" and both run
 * it through the same assembly search, but each had grown its own editor and
 * its own hidden arguments. Entering identical parts on the two screens
 * produced two different "best assembly" answers, with nothing on either to
 * say why:
 *
 *   Inventory Optimiser  all your games, your resolutions, your memory kits
 *   Trade Desk           the first six games, 1080p only, and a 16GB DDR4-3200
 *                        kit invented on the spot regardless of what you own
 *
 * Trade Desk ignoring the memory was the worst of it, on a screen whose whole
 * job is valuing a pile of parts. One component and one call site removes the
 * class of problem rather than reconciling the four arguments by hand.
 */
export interface PileEditorProps {
  pile: PartInventory;
  onChange: (p: PartInventory) => void;
  /** Storage is a fixed assumption on screens that do not ask about drives. */
  showStorage?: boolean;
  /**
   * Extra text per part, shown on its chip. Trade Desk uses it for the price it
   * has on record, which is the whole reason that screen shows a pile at all.
   */
  annotate?: (id: string) => string | null;
}

const MEMORY_TYPES: MemoryType[] = ['DDR5', 'DDR4', 'DDR3'];
const STORAGE_KINDS: Storage[] = ['nvme-gen4', 'nvme-gen3', 'sata-ssd', 'hdd'];
const DEFAULT_KIT: RamConfig = { totalGB: 32, channels: 2, speedMTs: 6000, type: 'DDR5' };

/** Keep a typed number inside the range the field advertises. */
const clamp = (v: number, lo: number, hi: number) => (Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : lo);

export function PilePicker({ pile, onChange, showStorage = true, annotate }: PileEditorProps) {
  const { data } = useApp();
  const [addCpu, setAddCpu] = useState('amd-ryzen-5-5600');
  const [addGpu, setAddGpu] = useState('nvidia-geforce-rtx-4060');
  const [kit, setKit] = useState<RamConfig>(DEFAULT_KIT);

  const set = (patch: Partial<PartInventory>) => onChange({ ...pile, ...patch });

  return (
    <>
      <PartPicker kind="cpu" label="add a processor" value={addCpu} onChange={setAddCpu} />
      <button
        className="btn"
        style={{ marginBottom: 12 }}
        disabled={pile.cpuIds.includes(addCpu)}
        onClick={() => set({ cpuIds: [...pile.cpuIds, addCpu] })}
      >
        {pile.cpuIds.includes(addCpu) ? 'already in the pile' : '+ add to pile'}
      </button>
      <ChipList
        ids={pile.cpuIds}
        nameOf={(id) => data.cpus.get(id)?.brand ?? id}
        fullNameOf={(id) => data.cpus.get(id)?.fullName ?? id}
        onRemove={(id) => set({ cpuIds: pile.cpuIds.filter((x) => x !== id) })}
        annotate={annotate}
        empty="No processors yet."
      />

      <PartPicker kind="gpu" label="add a graphics card" value={addGpu} onChange={setAddGpu} />
      <button
        className="btn"
        style={{ marginBottom: 12 }}
        disabled={pile.gpuIds.includes(addGpu)}
        onClick={() => set({ gpuIds: [...pile.gpuIds, addGpu] })}
      >
        {pile.gpuIds.includes(addGpu) ? 'already in the pile' : '+ add to pile'}
      </button>
      <ChipList
        ids={pile.gpuIds}
        nameOf={(id) => data.gpus.get(id)?.brand ?? id}
        fullNameOf={(id) => data.gpus.get(id)?.fullName ?? id}
        onRemove={(id) => set({ gpuIds: pile.gpuIds.filter((x) => x !== id) })}
        annotate={annotate}
        empty="No graphics cards yet."
      />

      {/* Memory is specified properly rather than added from a fixed button.
          Channels matter more than capacity on most of these machines, and a
          single-channel kit is one of the faults this whole tool exists to
          catch — so it has to be expressible. */}
      <div className="field">
        <label>memory kits on hand</label>
        {pile.ramKits.map((k, i) => (
          <div key={`${k.totalGB}-${k.speedMTs}-${k.type}-${i}`} style={{ display: 'flex', gap: 4, marginBottom: 4, alignItems: 'center' }}>
            <span className="chip on" style={{ flex: 1 }}>
              {k.totalGB}GB · {k.channels === 1 ? 'single channel' : `${k.channels} channel`} · {k.speedMTs} MT/s · {k.type}
            </span>
            <button
              className="btn danger"
              aria-label={`Remove the ${k.totalGB}GB ${k.type} kit`}
              onClick={() => set({ ramKits: pile.ramKits.filter((_, j) => j !== i) })}
            >
              ×
            </button>
          </div>
        ))}
        {!pile.ramKits.length && <span className="mini">No memory yet — add a kit for a buildable machine.</span>}
      </div>

      <div className="grid two" style={{ gap: 8, marginBottom: 8 }}>
        <div className="field">
          <label>capacity (GB)</label>
          <input
            type="number" min={2} max={256} value={kit.totalGB}
            /* Clamped in JS, not just declared: a browser does not enforce max
               on a typed value, and a 99999GB kit was accepted, stored in the
               shared pile and fed to the assembly search. */
            onChange={(e) => setKit({ ...kit, totalGB: clamp(Number(e.target.value), 2, 256) })}
          />
        </div>
        <div className="field">
          <label>speed (MT/s)</label>
          <input
            type="number" min={800} max={9000} step={100} value={kit.speedMTs}
            onChange={(e) => setKit({ ...kit, speedMTs: clamp(Number(e.target.value), 800, 9000) })}
          />
        </div>
        <div className="field">
          <label>channels</label>
          <select
            value={kit.channels}
            onChange={(e) => setKit({ ...kit, channels: Number(e.target.value) as RamConfig['channels'] })}
          >
            <option value={1}>1 — single</option>
            <option value={2}>2 — dual</option>
            <option value={4}>4 — quad</option>
          </select>
        </div>
        <div className="field">
          <label>generation</label>
          <select value={kit.type} onChange={(e) => setKit({ ...kit, type: e.target.value as MemoryType })}>
            {MEMORY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
      </div>
      <button className="btn" style={{ marginBottom: 14 }} onClick={() => set({ ramKits: [...pile.ramKits, kit] })}>
        + add this kit
      </button>

      {showStorage && (
        <div className="field">
          <label>drives available</label>
          <div className="toggle-row">
            {STORAGE_KINDS.map((s) => (
              <button
                key={s}
                className={`toggle${pile.storage.includes(s) ? ' on' : ''}`}
                onClick={() =>
                  set({
                    storage: pile.storage.includes(s)
                      ? pile.storage.filter((x) => x !== s)
                      : [...pile.storage, s],
                  })
                }
              >
                {s}
              </button>
            ))}
          </div>
          {!pile.storage.length && <span className="mini">Pick at least one, or nothing can be assembled.</span>}
        </div>
      )}
    </>
  );
}

function ChipList({
  ids, nameOf, fullNameOf, onRemove, empty, annotate,
}: {
  ids: string[];
  nameOf: (id: string) => string;
  fullNameOf: (id: string) => string;
  onRemove: (id: string) => void;
  empty: string;
  annotate?: (id: string) => string | null;
}) {
  if (!ids.length) return <div className="mini" style={{ marginBottom: 14 }}>{empty}</div>;
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 14 }}>
      {ids.map((id) => (
        <span className="chip on" key={id} title={fullNameOf(id)}>
          {nameOf(id)}
          {annotate?.(id) && <span className="mini" style={{ marginLeft: 4 }}>{annotate(id)}</span>}
          <button aria-label={`Remove ${fullNameOf(id)}`} onClick={() => onRemove(id)}>×</button>
        </span>
      ))}
    </div>
  );
}
