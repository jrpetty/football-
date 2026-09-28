/**
 * A one-shot confetti burst drawn on a canvas (no images, no dependencies):
 * two cannons fire from the lower corners of the 1920×1080 stage, the paper
 * flutters down and the canvas clears itself after a few seconds. Nothing is
 * drawn when the viewer prefers reduced motion.
 */
import { useEffect, useRef } from 'react';
import { usePrefersReducedMotion } from '../../hooks.ts';

interface Piece {
  x: number;
  y: number;
  vx: number;
  vy: number;
  w: number;
  h: number;
  rot: number;
  vr: number;
  flip: number;
  vf: number;
  color: string;
  round: boolean;
}

/** Deterministic PRNG so every take of a recording looks the same. */
function rng(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

export function Confetti({ colors, width = 1920, height = 1080, count = 220, durationMs = 5200, seed = 7 }: { colors: string[]; width?: number; height?: number; count?: number; durationMs?: number; seed?: number }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const reduced = usePrefersReducedMotion();
  const key = colors.join(',');
  useEffect(() => {
    if (reduced) return;
    const canvas = ref.current;
    const g = canvas?.getContext('2d');
    if (!canvas || !g) return;
    const r = rng(seed);
    const palette = colors.length ? colors : ['#f2c14e'];
    const pieces: Piece[] = [];
    for (let i = 0; i < count; i++) {
      const left = i % 2 === 0;
      const angle = (left ? -64 : -116) + (r() - 0.5) * 30;
      const speed = 24 + r() * 16;
      pieces.push({
        x: left ? 60 : width - 60,
        y: height + 10,
        vx: Math.cos((angle * Math.PI) / 180) * speed,
        vy: Math.sin((angle * Math.PI) / 180) * speed,
        w: 10 + r() * 12,
        h: 6 + r() * 8,
        rot: r() * Math.PI * 2,
        vr: (r() - 0.5) * 0.3,
        flip: r() * Math.PI * 2,
        vf: 0.08 + r() * 0.16,
        color: palette[Math.floor(r() * palette.length)]!,
        round: r() < 0.2,
      });
    }
    let raf = 0;
    let last = performance.now();
    const start = last;
    const frame = (now: number) => {
      const dt = Math.min(2, (now - last) / 16.67);
      last = now;
      const t = now - start;
      g.clearRect(0, 0, width, height);
      const fade = t > durationMs - 900 ? Math.max(0, (durationMs - t) / 900) : 1;
      g.globalAlpha = fade;
      for (const p of pieces) {
        p.vy += 0.42 * dt;
        p.vx *= Math.pow(0.985, dt);
        p.vy *= Math.pow(0.985, dt);
        if (p.vy > 5) p.vy = 5 + (p.vy - 5) * 0.9;
        p.x += (p.vx + Math.sin(p.flip) * 0.8) * dt;
        p.y += p.vy * dt;
        p.rot += p.vr * dt;
        p.flip += p.vf * dt;
        if (p.y > height + 40) continue;
        g.save();
        g.translate(p.x, p.y);
        g.rotate(p.rot);
        g.scale(1, Math.cos(p.flip));
        g.fillStyle = p.color;
        if (p.round) {
          g.beginPath();
          g.arc(0, 0, p.h * 0.6, 0, Math.PI * 2);
          g.fill();
        } else g.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
        g.restore();
      }
      if (t < durationMs) raf = requestAnimationFrame(frame);
      else g.clearRect(0, 0, width, height);
    };
    raf = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(raf);
  }, [reduced, key, width, height, count, durationMs, seed]);
  if (reduced) return null;
  return <canvas ref={ref} className="confetti" width={width} height={height} aria-hidden="true" />;
}
