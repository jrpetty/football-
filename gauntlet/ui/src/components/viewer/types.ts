/** Shapes shared by the universal output viewer and its per-type views. */
import type { ReplayData, TranscriptEntry } from '../../types.ts';
import type { ReplayContext } from '../ReplayPlayer.tsx';
import type { ViewerKind } from './detect.ts';

export type { ViewerKind };

/** One file to show. Content comes from `url` (fetched on demand), `text` or `bytes`. */
export interface ViewerFile {
  /** Stable key (tab id). */
  id: string;
  name: string;
  mime?: string;
  size?: number;
  /** http(s), data: or blob: URL. */
  url?: string;
  text?: string;
  bytes?: Uint8Array;
  /** Force a kind (replays, transcripts, or a type the caller already knows). */
  hint?: ViewerKind;
  /** Fence language of a code block ("python", "html"…). */
  lang?: string;
  /** What this file is, for the tab: "Model reply", "Game", "Screenshot", "Replay"… */
  role?: string;
  replay?: ReplayData;
  replayContext?: ReplayContext;
  transcript?: TranscriptEntry[];
  /** HTML games: screenshots the harness recorded while testing it. */
  screenshots?: ViewerFile[];
  /** JSON: the answer key to diff against. */
  compareTo?: unknown;
}

export interface LoadedContent {
  loading: boolean;
  error?: string;
  /** The file is larger than the viewer downloads (LIMITS.fetch). */
  tooBig?: boolean;
  text?: string;
  bytes?: Uint8Array;
}

export interface ViewerProps {
  file: ViewerFile;
  content: LoadedContent;
  /** Stage height in px (the viewer fills it). */
  height: number;
  /** Show the source instead of the rendering (HTML, SVG, Markdown). */
  source: boolean;
}
