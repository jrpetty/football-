/** Simulation replays: the existing replay player (scrub, speeds, full screen). */
import { ReplayPlayer } from '../ReplayPlayer.tsx';
import type { ViewerProps } from './types.ts';

export default function ReplayView({ file }: ViewerProps) {
  if (!file.replay) return <div className="uv-empty">No replay recorded for this result.</div>;
  return <ReplayPlayer replay={file.replay} context={file.replayContext} />;
}
