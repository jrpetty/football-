/**
 * Mock-mode replays for the simulation tests (Survival Island, The Escape
 * Room, The Startup, The Liar's Table). They were recorded by playing the
 * real programs (verification/agent-replay/mock-replays.ts): a strong run by
 * the scripted test players and a weak one by the Random Baseline, so every
 * illustrated replay can be demoed with no API keys.
 */
import recorded from './simReplays.json';
import type { ReplayData } from '../types.ts';

type Rec = { replay: ReplayData; score: number; summary: string };
const RECORDED = recorded as unknown as Record<string, { good: Rec; weak: Rec }>;

/** The recorded replay for a program, strong when the mock score is ≥ 0.5 (null for other programs). */
export function simReplay(program: string, seed: number, score: number): ReplayData | null {
  const r = RECORDED[program];
  if (!r) return null;
  const pick = score >= 0.5 ? r.good : r.weak;
  return { ...pick.replay, title: pick.replay.title.replace(/seed \d+/, `seed ${seed}`) };
}
