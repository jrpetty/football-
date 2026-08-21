import { useEffect, useState } from 'react';

/**
 * A value that lags behind the one you pass in, settling once it stops moving.
 *
 * For controls whose every intermediate value triggers real work. The budget
 * slider is the case that forced this: it has 145 stops, and each one re-ran
 * `planBuild` — which screens the whole catalogue — synchronously on the main
 * thread. One step measured 811ms, and dragging across half the track locked
 * the page for eighteen seconds.
 *
 * The control itself stays on the immediate value, so the handle and its label
 * follow the finger exactly as before. Only the expensive consumer reads the
 * settled one, so the work happens once when you let go instead of 145 times
 * on the way there.
 */
export function useDebounced<T>(value: T, ms = 200): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    if (Object.is(settled, value)) return;
    const t = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(t);
  }, [value, ms, settled]);
  return settled;
}
