/**
 * A number that glides from its previous value to the new one (pot sizes,
 * scores, test counts). Shows the target at once under reduced motion.
 */
import { useEffect, useRef, useState } from 'react';
import { usePrefersReducedMotion } from '../../hooks.ts';

export function useTween(target: number, durationMs = 520): number {
  const reduced = usePrefersReducedMotion();
  const [v, setV] = useState(target);
  const from = useRef(target);
  const cur = useRef(target);
  useEffect(() => {
    if (reduced || !Number.isFinite(target)) {
      cur.current = target;
      setV(target);
      return;
    }
    from.current = cur.current;
    const start = performance.now();
    let raf = 0;
    const step = (t: number) => {
      const p = Math.min(1, (t - start) / durationMs);
      const e = 1 - Math.pow(1 - p, 3);
      cur.current = from.current + (target - from.current) * e;
      setV(cur.current);
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [target, durationMs, reduced]);
  return v;
}

export function TweenNumber({ value, decimals = 0, prefix = '', suffix = '', className, durationMs }: { value: number; decimals?: number; prefix?: string; suffix?: string; className?: string; durationMs?: number }) {
  const v = useTween(value, durationMs);
  return (
    <span className={className}>
      {prefix}
      {v.toFixed(decimals)}
      {suffix}
    </span>
  );
}
