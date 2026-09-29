/** Plain text and logs: wrapped, with error / warning lines highlighted, cut at LIMITS.text with a notice. */
import { useMemo, useState } from 'react';
import { CopyButton, cx } from '../ui.tsx';
import { LIMITS, fmtSize } from './detect.ts';
import type { ViewerProps } from './types.ts';

export default function TextView({ file, content, height }: ViewerProps) {
  const [wrap, setWrap] = useState(true);
  const full = content.text ?? '';
  const text = full.length > LIMITS.text ? full.slice(0, LIMITS.text) : full;
  const isLog = /\.log$/i.test(file.name) || /^\[?\d{4}-\d\d-\d\d[ T]\d\d:\d\d/m.test(text.slice(0, 2000));
  const lines = useMemo(() => text.split('\n'), [text]);
  return (
    <div className="uv-code-wrap" style={{ maxHeight: height }}>
      <div className="uv-code-tools">
        <span className="muted tnum">
          {lines.length.toLocaleString()} line{lines.length === 1 ? '' : 's'} · {fmtSize(new TextEncoder().encode(full).length)}
        </span>
        <button type="button" className={cx('btn xs', wrap && 'on')} aria-pressed={wrap} onClick={() => setWrap(!wrap)}>
          Wrap
        </button>
        <CopyButton text={full} label="Copy" />
      </div>
      <pre className={cx('uv-text', wrap && 'wrap')}>
        {isLog
          ? lines.map((l, i) => (
              <div key={i} className={cx(/\b(error|fatal|exception|fail(ed)?)\b/i.test(l) ? 'lg-err' : /\bwarn(ing)?\b/i.test(l) ? 'lg-warn' : undefined)}>
                {l || ' '}
              </div>
            ))
          : text || '(empty)'}
      </pre>
      {full.length > LIMITS.text && <div className="uv-note">Showing the first {fmtSize(LIMITS.text)} of {fmtSize(full.length)}. Download the file for the rest.</div>}
    </div>
  );
}
