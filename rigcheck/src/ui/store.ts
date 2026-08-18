import { createContext, useContext } from 'react';
import gpusJson from '../../data/catalogue/gpus.json';
import cpusJson from '../../data/catalogue/cpus.json';
import gamesJson from '../../data/catalogue/games.json';
import refsJson from '../../data/catalogue/references.json';
import { buildEngineData, ANCHOR_RAM } from '../core/catalogue.ts';
import type { EngineData } from '../core/engine.ts';
import type { Build, RamConfig, Resolution } from '../core/types.ts';

export const engineData: EngineData = buildEngineData({
  gpus: gpusJson as never,
  cpus: cpusJson as never,
  games: gamesJson as never,
  references: refsJson as never,
});

export const DEFAULT_RAM: RamConfig = ANCHOR_RAM;

let seq = 0;
export function newBuildId(): string {
  seq += 1;
  return `b${Date.now().toString(36)}${seq}`;
}

export function makeBuild(over: Partial<Build> = {}): Build {
  return {
    id: newBuildId(),
    cpuId: 'amd-ryzen-5-3600',
    gpuId: 'nvidia-geforce-rtx-3060-12gb',
    ram: { ...DEFAULT_RAM },
    storage: 'nvme-gen3',
    target: { resolution: '1440p', refreshHz: 144 },
    ...over,
  };
}

/** Builds are shareable by URL, so they must survive a round trip through the hash. */
export function encodeState(builds: Build[], games: string[], resolutions: Resolution[]): string {
  const payload = {
    b: builds.map((b) => ({
      i: b.id,
      l: b.label,
      c: b.cpuId,
      g: b.gpuId,
      r: [b.ram.totalGB, b.ram.channels, b.ram.speedMTs],
      s: b.storage,
      t: [b.target.resolution, b.target.refreshHz],
      p: b.pricing?.components,
    })),
    g: games,
    x: resolutions,
  };
  try {
    return btoa(encodeURIComponent(JSON.stringify(payload)));
  } catch {
    return '';
  }
}

export function decodeState(
  hash: string,
): { builds: Build[]; games: string[]; resolutions: Resolution[] } | null {
  try {
    const raw = JSON.parse(decodeURIComponent(atob(hash))) as never as {
      b: { i: string; l?: string; c: string; g: string; r: [number, 1 | 2 | 4 | 8, number]; s: Build['storage']; t: [Resolution, number]; p?: Build['pricing'] extends infer P ? (P extends { components: infer C } ? C : never) : never }[];
      g: string[];
      x: Resolution[];
    };
    const builds: Build[] = raw.b.map((b) => ({
      id: b.i,
      label: b.l,
      cpuId: b.c,
      gpuId: b.g,
      ram: { totalGB: b.r[0], channels: b.r[1], speedMTs: b.r[2], type: DEFAULT_RAM.type, timings: DEFAULT_RAM.timings },
      storage: b.s,
      target: { resolution: b.t[0], refreshHz: b.t[1] },
    }));
    return { builds, games: raw.g, resolutions: raw.x };
  } catch {
    return null;
  }
}

export interface AppState {
  builds: Build[];
  setBuilds: (b: Build[]) => void;
  games: string[];
  setGames: (g: string[]) => void;
  resolutions: Resolution[];
  setResolutions: (r: Resolution[]) => void;
  data: EngineData;
}

export const AppCtx = createContext<AppState | null>(null);

export function useApp(): AppState {
  const v = useContext(AppCtx);
  if (!v) throw new Error('useApp outside provider');
  return v;
}

/** The 12-game core loop, used as the default selection. */
export const CORE_LOOP = [...engineData.games.values()].filter((g) => g.coreLoop).map((g) => g.id);

export function gameName(id: string): string {
  return engineData.games.get(id)?.name ?? id;
}
export function partName(id: string): string {
  return engineData.gpus.get(id)?.fullName ?? engineData.cpus.get(id)?.fullName ?? id;
}
