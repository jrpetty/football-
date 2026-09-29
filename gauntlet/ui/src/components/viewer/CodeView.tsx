/** Code with line numbers and syntax colouring (reuses viz/CodeSyntax). Also the "source" view of HTML, SVG and Markdown. */
import { memo, useMemo, useState } from 'react';
import { highlightLine } from '../viz/CodeSyntax.tsx';
import { CopyButton, cx } from '../ui.tsx';
import { codeLangOf } from './detect.ts';
import type { ViewerProps } from './types.ts';

const MAX_LINES = 6000;

export const CodeBlock = memo(function CodeBlock({ text, lang, height, wrapDefault = false }: { text: string; lang: string; height?: number; wrapDefault?: boolean }) {
  const [wrap, setWrap] = useState(wrapDefault);
  const lines = useMemo(() => text.replace(/\r\n?/g, '\n').split('\n'), [text]);
  const shown = lines.length > MAX_LINES ? lines.slice(0, MAX_LINES) : lines;
  const digits = String(shown.length).length;
  return (
    <div className="uv-code-wrap" style={height ? { maxHeight: height } : undefined}>
      <div className="uv-code-tools">
        <span className="muted tnum">
          {lines.length.toLocaleString()} line{lines.length === 1 ? '' : 's'}
        </span>
        <button type="button" className={cx('btn xs', wrap && 'on')} aria-pressed={wrap} onClick={() => setWrap(!wrap)}>
          Wrap
        </button>
        <CopyButton text={text} label="Copy" />
      </div>
      <pre className={cx('uv-code', wrap && 'wrap')} style={{ ['--gutter' as string]: `${digits + 1}ch` }}>
        {shown.map((l, i) => (
          <div key={i} className="uv-line">
            <span className="uv-ln" aria-hidden="true">
              {i + 1}
            </span>
            <span className="uv-lc">{lang === 'text' ? l || ' ' : highlightLine(l, lang)}</span>
          </div>
        ))}
      </pre>
      {lines.length > MAX_LINES && <div className="uv-note">Showing the first {MAX_LINES.toLocaleString()} of {lines.length.toLocaleString()} lines. Download the file to see the rest.</div>}
    </div>
  );
});

export default function CodeView({ file, content, height }: ViewerProps) {
  return <CodeBlock text={content.text ?? ''} lang={codeLangOf(file.name, file.lang)} height={height} />;
}
