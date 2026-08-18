
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
      </div>
    </div>
  );
}

export function GamePicker({
  selected,
  onChange,
}: {
  selected: string[];
  onChange: (ids: string[]) => void;
}) {
  const games = [...engineData.games.values()];
  const byArch = new Map<string, typeof games>();
  for (const g of games) {
    const arr = byArch.get(g.archetype) ?? [];
    arr.push(g);
    byArch.set(g.archetype, arr);
  }
  const toggle = (id: string) =>
    onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id]);

  return (
    <div>
      <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
        <button className="btn" onClick={() => onChange(games.filter((g) => g.coreLoop).map((g) => g.id))}>
          core 12
        </button>
        <button className="btn" onClick={() => onChange(games.map((g) => g.id))}>all 50</button>
        <button className="btn" onClick={() => onChange([])}>none</button>
      </div>
      {[...byArch.entries()].map(([arch, list]) => (
        <div key={arch} style={{ marginBottom: 8 }}>
          <div style={{ fontSize: 10, letterSpacing: '0.08em', color: 'var(--faint)', textTransform: 'uppercase', marginBottom: 4 }}>
            {arch}
          </div>
          <div className="toggle-row">
            {list.map((g) => (
              <button
                key={g.id}
                className={`toggle${selected.includes(g.id) ? ' on' : ''}`}
                onClick={() => toggle(g.id)}
                title={g.builtInBenchmark ? 'has a deterministic built-in benchmark' : 'no built-in benchmark'}
              >
                {g.name}
                {g.builtInBenchmark && <span style={{ color: 'var(--measured)', marginLeft: 4 }}>•</span>}
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
