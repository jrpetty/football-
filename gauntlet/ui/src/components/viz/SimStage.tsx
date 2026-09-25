/**
 * Purpose-built replay stages for the agent & social simulations. The replay
 * player renders this instead of its generic tile grid whenever the replay
 * carries `sim` data (results recorded before that field existed keep the
 * generic view). The Presenter reuses it for its best-moment slides.
 */
import type { ReplayData } from '../../types.ts';
import { cx } from '../ui.tsx';
import { EscapeReplay } from './EscapeReplay.tsx';
import { IslandReplay } from './IslandReplay.tsx';
import { LiarsReplay } from './LiarsReplay.tsx';
import { SimHeadline } from './SimHeadline.tsx';
import { StartupReplay } from './StartupReplay.tsx';
import { simStory } from './simStory.ts';
import '../../styles/sim-replay.css';

export interface SimStageProps {
  replay: ReplayData;
  idx: number;
  /** Broadcast or full screen. */
  video: boolean;
  /** How long a piece takes to move between steps (0 = instant). */
  moveMs: number;
  /** Jump the replay to a frame (timeline markers). */
  onSeek?: (frame: number) => void;
  /** Model name for the narration. */
  model: string;
  score?: number | null;
  /** Don't draw the finale card on the last step (the Presenter shows its own). */
  hideFinale?: boolean;
}

/** Can this replay be drawn by a purpose-built stage? */
export function hasSimStage(replay: ReplayData | undefined): boolean {
  const k = replay?.sim?.kind;
  return k === 'island' || k === 'escape' || k === 'startup' || k === 'liars';
}

export function SimStage(props: SimStageProps) {
  const { replay, idx, video, model } = props;
  const world = replay.sim;
  if (!world || !replay.frames[idx]) return null;
  const story = simStory(replay, idx, model);
  // The Startup's launch and closing frames carry no month data; its stage copes with that itself.
  const hasFrameSim = world.kind === 'startup' || !!replay.frames[idx]?.sim;
  return (
    <div className={cx('sim-stage', `sim-${world.kind}-stage`, video && 'video')}>
      {story && <SimHeadline story={story} stepKey={idx} />}
      {!hasFrameSim ? (
        <div className="chart-empty">This step has no scene data.</div>
      ) : world.kind === 'island' ? (
        <IslandReplay {...props} />
      ) : world.kind === 'escape' ? (
        <EscapeReplay {...props} />
      ) : world.kind === 'startup' ? (
        <StartupReplay {...props} />
      ) : (
        <LiarsReplay {...props} />
      )}
    </div>
  );
}
