/** Transcripts: every call exactly as recorded (the existing transcript view). */
import { TranscriptView } from '../Transcript.tsx';
import type { ViewerProps } from './types.ts';

export default function TranscriptFileView({ file, height }: ViewerProps) {
  if (!file.transcript?.length) return <div className="uv-empty">No transcript recorded.</div>;
  return (
    <div className="uv-scroll" style={{ maxHeight: height }}>
      <TranscriptView entries={file.transcript} />
    </div>
  );
}
