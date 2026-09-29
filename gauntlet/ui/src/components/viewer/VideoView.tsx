/** Video (MP4, WebM, OGG, MOV): the browser's player, sized to the stage; P plays / pauses, F goes full screen. */
import { useEffect, useMemo, useRef, useState } from 'react';
import { onViewerCommand } from './commands.ts';
import type { ViewerProps } from './types.ts';

export default function VideoView({ file, content, height }: ViewerProps) {
  const ref = useRef<HTMLVideoElement>(null);
  const [meta, setMeta] = useState<{ w: number; h: number; d: number } | null>(null);
  const [failed, setFailed] = useState(false);
  const blobUrl = useMemo(() => (!file.url && content.bytes ? URL.createObjectURL(new Blob([content.bytes as BlobPart], { type: file.mime || 'video/mp4' })) : null), [file.url, content.bytes, file.mime]);
  useEffect(() => () => void (blobUrl && URL.revokeObjectURL(blobUrl)), [blobUrl]);
  useEffect(
    () =>
      onViewerCommand((cmd) => {
        const v = ref.current;
        if (!v) return;
        if (cmd === 'play') void (v.paused ? v.play() : v.pause());
        if (cmd === 'restart') {
          v.currentTime = 0;
          void v.play();
        }
        if (cmd === 'fullscreen') void (document.fullscreenElement ? document.exitFullscreen() : v.requestFullscreen());
      }),
    [],
  );
  return (
    <div className="uv-media">
      <div className="uv-canvas bg-dark" style={{ height: Math.max(220, height - 46) }}>
        {failed ? (
          <div className="uv-empty">This browser can’t play this video format. Download it to watch it in another player.</div>
        ) : (
          <video
            ref={ref}
            controls
            playsInline
            preload="metadata"
            src={file.url ?? blobUrl ?? undefined}
            onLoadedMetadata={(e) => setMeta({ w: e.currentTarget.videoWidth, h: e.currentTarget.videoHeight, d: e.currentTarget.duration })}
            onError={() => setFailed(true)}
          />
        )}
      </div>
      <div className="uv-media-bar">
        <span className="muted tnum">{meta ? `${meta.w} × ${meta.h} · ${Number.isFinite(meta.d) ? `${meta.d.toFixed(1)} s` : 'streaming'}` : failed ? 'unsupported' : 'loading…'}</span>
        <span className="muted">
          <kbd>P</kbd> play / pause · <kbd>F</kbd> full screen
        </span>
      </div>
    </div>
  );
}
