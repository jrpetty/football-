/** JSON: pretty-printed with colours, and (when there is an answer key) a field-by-field diff against the key. */
import { useMemo, useState } from 'react';
import { cx } from '../ui.tsx';
import { CodeBlock } from './CodeView.tsx';
import { LIMITS, fmtSize } from './detect.ts';
import { jsonDiff } from './jsonDiff.ts';
import type { ViewerProps } from './types.ts';

function show(v: unknown): string {
  if (v === undefined) return '—';
  return typeof v === 'string' ? v : JSON.stringify(v);
}

export default function JsonView({ file, content, height }: ViewerProps) {
  const text = content.text ?? '';
  const parsed = useMemo(() => {
    if (text.length > LIMITS.json) return { error: `Too large to pretty-print (${fmtSize(text.length)}).` };
    try {
      return { value: JSON.parse(text) as unknown };
    } catch (e) {
      return { error: (e as Error).message };
    }
  }, [text]);
  const hasKey = file.compareTo !== undefined && 'value' in parsed;
  const [chosen, setTab] = useState<'diff' | 'pretty' | null>(null);
  const tab = chosen ?? (hasKey ? 'diff' : 'pretty');
  const rows = useMemo(() => (hasKey && 'value' in parsed ? jsonDiff(file.compareTo, parsed.value) : []), [hasKey, parsed, file.compareTo]);
  if ('error' in parsed) {
    return (
      <div className="stack tight">
        <div className="uv-note bad">Not valid JSON: {parsed.error}</div>
        <CodeBlock text={text} lang="json" height={height - 40} />
      </div>
    );
  }
  const good = rows.filter((r) => r.state === 'match').length;
  const scored = rows.filter((r) => r.state !== 'extra').length;
  return (
    <div className="stack tight">
      {hasKey && (
        <div className="uv-media-bar top">
          <div className="seg sm" role="group" aria-label="JSON view">
            <button type="button" aria-pressed={tab === 'diff'} onClick={() => setTab('diff')}>
              Answer vs key
            </button>
            <button type="button" aria-pressed={tab === 'pretty'} onClick={() => setTab('pretty')}>
              Pretty JSON
            </button>
          </div>
          <span className="uv-tally">
            <b className="tnum">{good}</b> of <b className="tnum">{scored}</b> fields match the key
          </span>
          <span className="uv-legend">
            <i className="lg match" /> matches <i className="lg wrong" /> different <i className="lg missing" /> missing <i className="lg extra" /> not in key
          </span>
        </div>
      )}
      {tab === 'diff' && hasKey ? (
        <div className="uv-table-wrap" style={{ maxHeight: height - 50 }}>
          <table className="uv-table uv-diff-table">
            <thead>
              <tr>
                <th>Field</th>
                <th>Model’s answer</th>
                <th>Answer key</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.path} className={cx('jd', r.state)}>
                  <td className="mono">{r.path}</td>
                  <td className={cx('mono', r.state !== 'match' && 'strike-soft')}>{show(r.actual)}</td>
                  <td className="mono">{show(r.expected)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <CodeBlock text={JSON.stringify(parsed.value, null, 2)} lang="json" height={height - (hasKey ? 50 : 0)} />
      )}
    </div>
  );
}
