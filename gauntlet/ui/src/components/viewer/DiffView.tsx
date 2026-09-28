/** Unified diffs / patches: file headers, hunks, green additions and red removals with a +/− tally. */
import { useMemo } from 'react';
import { cx } from '../ui.tsx';
import type { ViewerProps } from './types.ts';

export default function DiffView({ content, height }: ViewerProps) {
  const lines = useMemo(() => (content.text ?? '').replace(/\r\n?/g, '\n').split('\n').slice(0, 8000), [content.text]);
  const add = lines.filter((l) => l.startsWith('+') && !l.startsWith('+++')).length;
  const del = lines.filter((l) => l.startsWith('-') && !l.startsWith('---')).length;
  const files = lines.filter((l) => l.startsWith('+++ ')).length;
  return (
    <div className="stack tight">
      <div className="uv-media-bar top">
        <span className="good-text tnum">+{add}</span>
        <span className="bad-text tnum">−{del}</span>
        <span className="muted">
          {files || 1} file{files === 1 ? '' : 's'} changed
        </span>
        <span className="uv-legend">
          <i className="lg match" /> added <i className="lg wrong" /> removed
        </span>
      </div>
      <pre className="uv-code uv-diff" style={{ maxHeight: height - 50 }}>
        {lines.map((l, i) => {
          const k = l.startsWith('+++') || l.startsWith('---') || l.startsWith('diff ') || l.startsWith('index ') ? 'file' : l.startsWith('@@') ? 'hunk' : l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : 'ctx';
          return (
            <div key={i} className={cx('uv-dl', k)}>
              <span className="uv-dm" aria-hidden="true">
                {k === 'add' ? '+' : k === 'del' ? '−' : ''}
              </span>
              <span>{k === 'add' || k === 'del' ? l.slice(1) || ' ' : l || ' '}</span>
            </div>
          );
        })}
      </pre>
    </div>
  );
}
