/** CSV / TSV as a table with a sticky header and row numbers (first LIMITS.csvRows rows). */
import { useMemo, useState } from 'react';
import { LIMITS } from './detect.ts';
import { parseDelimited } from './sanitize.ts';
import { CodeBlock } from './CodeView.tsx';
import type { ViewerProps } from './types.ts';

export default function TableView({ file, content, height, source }: ViewerProps) {
  const [header, setHeader] = useState(true);
  const text = content.text ?? '';
  const parsed = useMemo(() => parseDelimited(text, /\.tsv$/i.test(file.name) ? '\t' : undefined, LIMITS.csvRows + 1), [text, file.name]);
  if (source) return <CodeBlock text={text} lang="text" height={height} />;
  const rows = parsed.rows.slice(0, LIMITS.csvRows + (header ? 1 : 0));
  const head = header ? rows[0] ?? [] : [];
  const body = header ? rows.slice(1) : rows;
  const cols = Math.max(0, ...rows.map((r) => r.length));
  return (
    <div className="stack tight">
      <div className="uv-media-bar top">
        <span className="muted tnum">
          {body.length.toLocaleString()} row{body.length === 1 ? '' : 's'} · {cols} column{cols === 1 ? '' : 's'} · separated by {parsed.delimiter === '\t' ? 'tabs' : parsed.delimiter === ';' ? 'semicolons' : 'commas'}
        </span>
        <label className="row" style={{ gap: 6 }}>
          <input type="checkbox" checked={header} onChange={(e) => setHeader(e.target.checked)} /> First row is a header
        </label>
      </div>
      <div className="uv-table-wrap" style={{ maxHeight: height - 50 }}>
        <table className="uv-table">
          {header && (
            <thead>
              <tr>
                <th className="rn">#</th>
                {Array.from({ length: cols }, (_, i) => (
                  <th key={i}>{head[i] ?? ''}</th>
                ))}
              </tr>
            </thead>
          )}
          <tbody>
            {body.map((r, i) => (
              <tr key={i}>
                <td className="rn tnum">{i + 1}</td>
                {Array.from({ length: cols }, (_, j) => (
                  <td key={j} className={/^-?\d+(\.\d+)?$/.test(r[j] ?? '') ? 'num tnum' : undefined}>
                    {r[j] ?? ''}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {parsed.truncated && <div className="uv-note">Showing the first {LIMITS.csvRows.toLocaleString()} rows. Download the file for the rest.</div>}
    </div>
  );
}
