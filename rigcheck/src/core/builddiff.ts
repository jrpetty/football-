/**
 * What changed between two builds, in terms someone would actually say.
 *
 * Two parts lists side by side is a spot-the-difference puzzle. What a person
 * wants is the sentence: "same machine, but 32GB instead of 16 and a 4070
 * instead of a 3060". This produces that, and grades each change by whether it
 * is likely to matter — a storage-class change is worth mentioning, a change of
 * label is not.
 */
import type { Build } from './types.ts';

export type ChangeWeight = 'major' | 'minor' | 'cosmetic';

export interface BuildChange {
  field: string;
  label: string;
  from: string;
  to: string;
  weight: ChangeWeight;
}

const fmtRam = (b: Build) =>
  `${b.ram.totalGB}GB ${b.ram.type ?? ''} ${b.ram.speedMTs}MT/s ${b.ram.channels}ch`.replace(/\s+/g, ' ').trim();

export function diffBuilds(a: Build, b: Build, nameOf: (id: string) => string): BuildChange[] {
  const out: BuildChange[] = [];
  const push = (field: string, label: string, from: string, to: string, weight: ChangeWeight) => {
    if (from !== to) out.push({ field, label, from, to, weight });
  };

  push('cpuId', 'CPU', nameOf(a.cpuId), nameOf(b.cpuId), 'major');
  push('gpuId', 'GPU', nameOf(a.gpuId), nameOf(b.gpuId), 'major');
  push('ram', 'memory', fmtRam(a), fmtRam(b), a.ram.channels !== b.ram.channels || a.ram.totalGB !== b.ram.totalGB ? 'major' : 'minor');
  push('storage', 'storage', a.storage, b.storage, 'minor');
  push('resolution', 'resolution', a.target.resolution, b.target.resolution, 'major');
  push('refreshHz', 'refresh', `${a.target.refreshHz}Hz`, `${b.target.refreshHz}Hz`, 'minor');
  push('label', 'label', a.label ?? '—', b.label ?? '—', 'cosmetic');
  return out;
}

/** One line summarising a diff, for a list where a table would be too much. */
export function summariseDiff(changes: BuildChange[]): string {
  const real = changes.filter((c) => c.weight !== 'cosmetic');
  if (!real.length) return changes.length ? 'no functional change' : 'identical';
  if (real.length === 1) return `${real[0].label}: ${real[0].from} → ${real[0].to}`;
  const major = real.filter((c) => c.weight === 'major');
  return major.length
    ? `${major.map((c) => c.label).join(' and ')} changed${real.length > major.length ? `, plus ${real.length - major.length} smaller change${real.length - major.length === 1 ? '' : 's'}` : ''}`
    : `${real.length} changes: ${real.map((c) => c.label).join(', ')}`;
}
