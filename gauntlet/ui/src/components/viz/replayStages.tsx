/**
 * Test-specific replay stages plugged into the ReplayPlayer. The player still
 * owns playback (play/pause, step, 0.5×–4×, scrubbing, F for full screen);
 * a stage only draws the current step. Detection works on old results too:
 * it looks at the score detail shape, and each stage falls back to "not
 * recorded" notes for fields older runs lack.
 */
import { useMemo } from 'react';
import type { ReplayData, ReplayFrame } from '../../types.ts';
import { DrawStage } from './DrawStage.tsx';
import { NeedleStage } from './NeedleStage.tsx';
import { WhispersStage } from './WhispersStage.tsx';
import { drawModel, needleModel, visualOf, whispersModel } from './vizModel.ts';

export type StageKind = 'needle' | 'whispers' | 'draw';

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object';

export function stageKind(replay: ReplayData, detail: unknown): StageKind | null {
  const kind = replay.visual?.kind;
  if (!isObj(detail)) return null;
  if ((kind === 'needle-haystack' || /^Needle in a Haystack/.test(replay.title)) && Array.isArray(detail.needles)) return 'needle';
  if ((kind === 'chain-of-whispers' || /^Chain of Whispers/.test(replay.title)) && Array.isArray(detail.facts) && Array.isArray(detail.rounds)) return 'whispers';
  if ((kind === 'draw-it-blind' || /^Draw It Blind/.test(replay.title)) && Array.isArray(detail.scene) && Array.isArray(detail.matches)) return 'draw';
  return null;
}

export function CustomReplayStage({ kind, replay, frames, idx, video, detail }: { kind: StageKind; replay: ReplayData; frames: ReplayFrame[]; idx: number; video: boolean; detail: unknown }) {
  const model = useMemo(() => {
    if (kind === 'needle') return { needle: needleModel(detail, visualOf(replay, 'needle-haystack')) };
    if (kind === 'whispers') return { whispers: whispersModel(detail, visualOf(replay, 'chain-of-whispers')) };
    return { draw: drawModel(detail, replay) };
  }, [kind, replay, detail]);
  const frame = frames[Math.min(idx, frames.length - 1)];
  if (!frame) return null;
  if (model.needle) return <NeedleStage model={model.needle} frame={frame} frames={frames} idx={idx} video={video} />;
  if (model.whispers) return <WhispersStage model={model.whispers} idx={idx} video={video} fallbackText={frame.observation} />;
  if (model.draw) return <DrawStage model={model.draw} idx={idx} video={video} />;
  return null;
}
