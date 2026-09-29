/** SVG: sanitised, then shown through <img> (no scripts, no external loads), on a checkerboard; plus a source view. */
import { useMemo, useState } from 'react';
import { cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { CodeBlock } from './CodeView.tsx';
import { LIMITS, fmtSize } from './detect.ts';
import { sanitizeSvg, svgDataUrl } from './sanitize.ts';
import type { ViewerProps } from './types.ts';

export default function SvgView({ content, height, source }: ViewerProps) {
  const [bg, setBg] = useState<'check' | 'white' | 'dark'>('check');
  const text = content.text ?? '';
  const clean = useMemo(() => (text.length > LIMITS.svg ? null : sanitizeSvg(text)), [text]);
  if (source) return <CodeBlock text={text} lang="text" height={height} />;
  if (!clean) return <div className="uv-empty">This SVG is {fmtSize(text.length)}, over the {fmtSize(LIMITS.svg)} preview limit. Use Source or Download.</div>;
  return (
    <div className="uv-media">
      <div className={cx('uv-canvas', `bg-${bg}`)} style={{ height: Math.max(220, height - 46) }}>
        <img src={svgDataUrl(clean.svg)} alt="SVG drawn by the model" />
      </div>
      <div className="uv-media-bar">
        <div className="seg sm" role="group" aria-label="Background">
          {(['check', 'white', 'dark'] as const).map((b) => (
            <button key={b} type="button" aria-pressed={bg === b} onClick={() => setBg(b)}>
              {b === 'check' ? 'Checker' : b === 'white' ? 'White' : 'Dark'}
            </button>
          ))}
        </div>
        {clean.removed.length > 0 ? (
          <span className="uv-safety warn" title={clean.removed.join(', ')}>
            <Icon.Lock /> Made safe: removed {clean.removed.slice(0, 3).join(', ')}
            {clean.removed.length > 3 ? ` +${clean.removed.length - 3} more` : ''}
          </span>
        ) : (
          <span className="uv-safety">
            <Icon.Lock /> Shown as a picture: scripts never run
          </span>
        )}
      </div>
    </div>
  );
}
