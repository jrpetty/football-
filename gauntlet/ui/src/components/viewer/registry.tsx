/**
 * Viewer registry: one entry per file kind. Other features add a type with
 * `registerViewer({ kind, label, needs, Component })` — no change to the
 * viewer itself (see docs/ADDING_TESTS.md → "Showing a new output type").
 */
import type { ComponentType } from 'react';
import type { ViewerKind, ViewerProps } from './types.ts';
import HtmlView from './HtmlView.tsx';
import SvgView from './SvgView.tsx';
import ImageView from './ImageView.tsx';
import PdfView from './PdfView.tsx';
import AudioView from './AudioView.tsx';
import VideoView from './VideoView.tsx';
import JsonView from './JsonView.tsx';
import TableView from './TableView.tsx';
import MarkdownView from './MarkdownView.tsx';
import CodeView from './CodeView.tsx';
import DiffView from './DiffView.tsx';
import TextView from './TextView.tsx';
import ZipView from './ZipView.tsx';
import ReplayView from './ReplayView.tsx';
import TranscriptFileView from './TranscriptFileView.tsx';
import BinaryView from './BinaryView.tsx';

export interface ViewerPlugin {
  kind: ViewerKind | string;
  /** Plain name shown on the tab badge, e.g. "Game", "Picture", "Table". */
  label: string;
  /** What the view needs loaded: the file as text, as bytes, or nothing (it uses the URL / inline data itself). */
  needs: 'text' | 'bytes' | 'none';
  /** Has a "Source" toggle (the view renders `source` itself). */
  hasSource?: boolean;
  Component: ComponentType<ViewerProps>;
}

const plugins = new Map<string, ViewerPlugin>();

export function registerViewer(p: ViewerPlugin): void {
  plugins.set(p.kind, p);
}

export function viewerFor(kind: string): ViewerPlugin {
  return plugins.get(kind) ?? plugins.get('binary')!;
}

export function registeredKinds(): string[] {
  return [...plugins.keys()];
}

registerViewer({ kind: 'html', label: 'Web page / game', needs: 'text', hasSource: true, Component: HtmlView });
registerViewer({ kind: 'svg', label: 'SVG drawing', needs: 'text', hasSource: true, Component: SvgView });
registerViewer({ kind: 'image', label: 'Picture', needs: 'none', Component: ImageView });
registerViewer({ kind: 'pdf', label: 'PDF', needs: 'bytes', Component: PdfView });
registerViewer({ kind: 'audio', label: 'Audio', needs: 'bytes', Component: AudioView });
registerViewer({ kind: 'video', label: 'Video', needs: 'none', Component: VideoView });
registerViewer({ kind: 'json', label: 'JSON data', needs: 'text', Component: JsonView });
registerViewer({ kind: 'csv', label: 'Table', needs: 'text', hasSource: true, Component: TableView });
registerViewer({ kind: 'markdown', label: 'Formatted text', needs: 'text', hasSource: true, Component: MarkdownView });
registerViewer({ kind: 'code', label: 'Code', needs: 'text', Component: CodeView });
registerViewer({ kind: 'diff', label: 'Code changes', needs: 'text', Component: DiffView });
registerViewer({ kind: 'text', label: 'Text', needs: 'text', Component: TextView });
registerViewer({ kind: 'zip', label: 'ZIP archive', needs: 'bytes', Component: ZipView });
registerViewer({ kind: 'replay', label: 'Replay', needs: 'none', Component: ReplayView });
registerViewer({ kind: 'transcript', label: 'Transcript', needs: 'none', Component: TranscriptFileView });
registerViewer({ kind: 'binary', label: 'File', needs: 'bytes', Component: BinaryView });
