/** Unknown or unsupported files: what we can tell about them, a hex preview of the first bytes, and a download link. */
import { detectKind, fmtSize, hexRows, LIMITS } from './detect.ts';
import type { ViewerProps } from './types.ts';

export default function BinaryView({ file, content }: ViewerProps) {
  const b = content.bytes;
  const d = detectKind({ name: file.name, mime: file.mime, bytes: b });
  if (content.tooBig) return <div className="uv-empty">This file is {fmtSize(file.size)}, bigger than the {fmtSize(LIMITS.fetch)} the viewer previews. Download it to open it.</div>;
  return (
    <div className="stack tight">
      <div className="uv-media-bar top">
        <span className="badge outline">{d.mime}</span>
        <span className="muted tnum">{fmtSize(b?.length ?? file.size)}</span>
        <span className="muted">No built-in preview for this type: here are its first {Math.min(LIMITS.hex, b?.length ?? 0)} bytes.</span>
      </div>
      {b ? (
        <pre className="uv-hex">
          {hexRows(b).map((r) => (
            <div key={r.offset}>
              <span className="uv-hex-o">{r.offset}</span>
              <span className="uv-hex-h">{r.hex.padEnd(47, ' ')}</span>
              <span className="uv-hex-a">{r.ascii}</span>
            </div>
          ))}
        </pre>
      ) : (
        <div className="uv-empty">Loading…</div>
      )}
    </div>
  );
}
