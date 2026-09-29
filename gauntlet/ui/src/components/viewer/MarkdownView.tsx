/** Markdown rendered safely (renderMarkdown only emits escaped text in a fixed set of tags; remote images are never fetched). */
import { useMemo } from 'react';
import { CodeBlock } from './CodeView.tsx';
import { LIMITS, fmtSize } from './detect.ts';
import { renderMarkdown } from './sanitize.ts';
import type { ViewerProps } from './types.ts';

export default function MarkdownView({ content, height, source }: ViewerProps) {
  const text = content.text ?? '';
  const html = useMemo(() => (text.length > LIMITS.markdown ? null : renderMarkdown(text)), [text]);
  if (source || html === null) {
    return (
      <div className="stack tight">
        {html === null && !source && <div className="uv-note">Over {fmtSize(LIMITS.markdown)}: shown as plain text.</div>}
        <CodeBlock text={text} lang="md" height={height} wrapDefault />
      </div>
    );
  }
  // eslint-disable-next-line react/no-danger -- built from escaped text only (see sanitize.ts)
  return <div className="uv-md" style={{ maxHeight: height }} dangerouslySetInnerHTML={{ __html: html || '<p class="muted">(empty)</p>' }} />;
}
