/** ZIP archives as a file listing (read from the central directory; nothing is unpacked, so zip bombs are harmless). */
import { useMemo } from 'react';
import { fmtSize } from './detect.ts';
import { listZip } from './zip.ts';
import type { ViewerProps } from './types.ts';

export default function ZipView({ content, height }: ViewerProps) {
  const l = useMemo(() => (content.bytes ? listZip(content.bytes) : null), [content.bytes]);
  if (!l) return <div className="uv-empty">Loading…</div>;
  const files = l.entries.filter((e) => !e.dir);
  const total = files.reduce((s, e) => s + e.size, 0);
  return (
    <div className="stack tight">
      <div className="uv-media-bar top">
        <span className="muted tnum">
          {files.length} file{files.length === 1 ? '' : 's'} · {fmtSize(total)} unpacked
        </span>
        {l.total > l.entries.length && <span className="muted">first {l.entries.length.toLocaleString()} of {l.total.toLocaleString()} entries</span>}
        <span className="muted">Listed only: nothing inside is opened or run.</span>
      </div>
      {l.error && <div className="uv-note bad">{l.error}</div>}
      <div className="uv-table-wrap" style={{ maxHeight: height - 50 }}>
        <table className="uv-table">
          <thead>
            <tr>
              <th>Name</th>
              <th className="num">Size</th>
              <th className="num">Packed</th>
              <th>Method</th>
              <th>Modified</th>
            </tr>
          </thead>
          <tbody>
            {l.entries.map((e, i) => {
              const depth = (e.name.replace(/\/$/, '').match(/\//g) ?? []).length;
              return (
                <tr key={i}>
                  <td className="mono" style={{ paddingLeft: 10 + depth * 16 }}>
                    {e.dir ? '▸ ' : ''}
                    {e.name.replace(/\/$/, '').split('/').pop()}
                    {e.dir ? '/' : ''}
                  </td>
                  <td className="num tnum">{e.dir ? '' : fmtSize(e.size)}</td>
                  <td className="num tnum">{e.dir ? '' : fmtSize(e.compressedSize)}</td>
                  <td>{e.dir ? 'folder' : e.method}</td>
                  <td className="tnum">{e.modified ?? '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
