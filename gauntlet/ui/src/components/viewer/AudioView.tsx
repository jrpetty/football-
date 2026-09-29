/** Audio (WAV, MP3, OGG, WebM, FLAC…): the browser's player plus a waveform drawn from the decoded samples. */
import { useEffect, useMemo, useRef, useState } from 'react';
import { onViewerCommand } from './commands.ts';
import type { ViewerProps } from './types.ts';

function fmtDur(s: number): string {
  if (!Number.isFinite(s)) return '—';
  if (s < 60) return `${s.toFixed(1)} s`;
  return `${Math.floor(s / 60)} min ${String(Math.round(s % 60)).padStart(2, '0')} s`;
}

/** Sample rate and channels from a WAV header (decoded audio is resampled, so it can't tell us). */
function wavFormat(b: Uint8Array): { rate: number; channels: number } | null {
  if (b.length < 44 || String.fromCharCode(...b.slice(0, 4)) !== 'RIFF' || String.fromCharCode(...b.slice(8, 12)) !== 'WAVE') return null;
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return { channels: v.getUint16(22, true), rate: v.getUint32(24, true) };
}

export default function AudioView({ file, content, height }: ViewerProps) {
  const audioRef = useRef<HTMLAudioElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [info, setInfo] = useState<{ duration: number; rate: number; channels: number } | null>(null);
  const [peaks, setPeaks] = useState<number[] | null>(null);
  const [pos, setPos] = useState(0);
  const blobUrl = useMemo(() => (!file.url && content.bytes ? URL.createObjectURL(new Blob([content.bytes as BlobPart], { type: file.mime || 'audio/wav' })) : null), [file.url, content.bytes, file.mime]);
  useEffect(() => () => void (blobUrl && URL.revokeObjectURL(blobUrl)), [blobUrl]);

  useEffect(() => {
    if (!content.bytes) return;
    let alive = true;
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    ctx
      .decodeAudioData(content.bytes.slice().buffer as ArrayBuffer)
      .then((buf) => {
        if (!alive) return;
        const data = buf.getChannelData(0);
        const n = 160;
        const step = Math.max(1, Math.floor(data.length / n));
        const out: number[] = [];
        for (let i = 0; i < n; i++) {
          let peak = 0;
          for (let j = i * step; j < Math.min(data.length, (i + 1) * step); j += 8) peak = Math.max(peak, Math.abs(data[j]!));
          out.push(peak);
        }
        const max = Math.max(0.01, ...out);
        setPeaks(out.map((p) => p / max));
        const wav = content.bytes ? wavFormat(content.bytes) : null;
        setInfo({ duration: buf.duration, rate: wav?.rate ?? 0, channels: wav?.channels ?? buf.numberOfChannels });
      })
      .catch(() => alive && setPeaks(null))
      .finally(() => void ctx.close().catch(() => {}));
    return () => {
      alive = false;
    };
  }, [content.bytes]);

  useEffect(() => onViewerCommand((cmd) => cmd === 'play' && audioRef.current && (audioRef.current.paused ? void audioRef.current.play() : audioRef.current.pause())), []);

  useEffect(() => {
    const c = canvasRef.current;
    if (!c || !peaks) return;
    const dpr = window.devicePixelRatio || 1;
    const w = c.clientWidth;
    const h = c.clientHeight;
    c.width = w * dpr;
    c.height = h * dpr;
    const g = c.getContext('2d')!;
    g.scale(dpr, dpr);
    const css = getComputedStyle(c);
    const on = css.getPropertyValue('--wave-on').trim() || '#22d3ee';
    const off = css.getPropertyValue('--wave-off').trim() || '#445';
    const bw = w / peaks.length;
    peaks.forEach((p, i) => {
      const bh = Math.max(2, p * (h - 8));
      g.fillStyle = i / peaks.length <= pos ? on : off;
      g.fillRect(i * bw + 1, (h - bh) / 2, Math.max(1, bw - 2), bh);
    });
  }, [peaks, pos]);

  return (
    <div className="uv-audio" style={{ minHeight: Math.min(height, 320) }}>
      <canvas
        ref={canvasRef}
        className="uv-wave"
        aria-label="Waveform"
        onClick={(e) => {
          const a = audioRef.current;
          if (!a || !Number.isFinite(a.duration)) return;
          const r = e.currentTarget.getBoundingClientRect();
          a.currentTime = ((e.clientX - r.left) / r.width) * a.duration;
        }}
      />
      {!peaks && <div className="uv-note">{content.bytes ? 'Waveform unavailable for this format.' : 'Loading…'}</div>}
      <audio
        ref={audioRef}
        controls
        preload="metadata"
        src={file.url ?? blobUrl ?? undefined}
        onTimeUpdate={(e) => setPos(e.currentTarget.duration ? e.currentTarget.currentTime / e.currentTarget.duration : 0)}
      />
      {info && (
        <div className="uv-media-bar">
          <span className="muted tnum">{fmtDur(info.duration)}</span>
          {info.rate > 0 && <span className="muted tnum">{(info.rate / 1000).toFixed(info.rate % 1000 ? 2 : 0)} kHz</span>}
          <span className="muted">{info.channels === 1 ? 'mono' : info.channels === 2 ? 'stereo' : `${info.channels} channels`}</span>
          <span className="muted">
            <kbd>P</kbd> play / pause
          </span>
        </div>
      )}
    </div>
  );
}
