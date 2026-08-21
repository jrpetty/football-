import { useCallback, useState } from 'react';

/**
 * State that survives leaving the screen and coming back.
 *
 * React unmounts a route's component when you navigate away, so anything held
 * in `useState` is gone. On most screens that is fine — they are derived from
 * the shared build. On the two that ask a series of questions it is not: the
 * Build Wizard collects a screen, a game library, a budget and four
 * constraints across five steps, and glancing at another screen threw all of
 * it away with no warning. The System Health flow did the same, dropping you
 * back at the consent step having forgotten your specification.
 *
 * Backed by sessionStorage so it also survives a reload, and scoped to the tab
 * so two windows do not fight over one answer. Every access is wrapped:
 * private browsing and some file:// contexts throw on the accessor itself
 * rather than merely returning null, and a wizard that cannot remember a
 * budget is a far better outcome than a wizard that will not render.
 */
const memory = new Map<string, unknown>();

function read<T>(key: string, initial: T, validate?: (v: unknown) => boolean): T {
  const ok = (v: unknown): boolean => {
    if (!validate) return true;
    try {
      return validate(v);
    } catch {
      return false;
    }
  };
  if (memory.has(key)) {
    const v = memory.get(key) as T;
    return ok(v) ? v : initial;
  }
  try {
    const raw = sessionStorage.getItem(key);
    if (raw != null) {
      const parsed = JSON.parse(raw) as T;
      if (!ok(parsed)) return initial;
      memory.set(key, parsed);
      return parsed;
    }
  } catch {
    // No storage, or a half-written entry that will not parse. The in-memory
    // map still carries the value across a remount, which is the case this
    // exists for; only reload is lost.
  }
  return initial;
}

function write(key: string, value: unknown): void {
  memory.set(key, value);
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Full, disabled, or a value that will not serialise. Ignored on purpose.
  }
}

/**
 * @param validate Reject a restored value and fall back to `initial`.
 *
 * A restored value is INPUT, not state: it can come from a stale session
 * written by an older build of the app, from a hand-edited store, or from an
 * entry that was half-written. Trusting it stranded the Build Wizard — a step
 * index of 99 rendered the heading, the step rail and then nothing at all, with
 * no active step and no way to see what had gone wrong. Anything whose value
 * has a range worth respecting should say so here.
 */
export function useStickyState<T>(
  key: string,
  initial: T,
  validate?: (v: unknown) => boolean,
): [T, (v: T | ((prev: T) => T)) => void] {
  const [value, setValue] = useState<T>(() => read(key, initial, validate));
  const set = useCallback(
    (v: T | ((prev: T) => T)) => {
      setValue((prev) => {
        const next = typeof v === 'function' ? (v as (p: T) => T)(prev) : v;
        write(key, next);
        return next;
      });
    },
    [key],
  );
  return [value, set];
}

/** Forget one screen's answers — for a "start again" control. */
export function clearSticky(prefix: string): void {
  for (const k of [...memory.keys()]) if (k.startsWith(prefix)) memory.delete(k);
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const k = sessionStorage.key(i);
      if (k?.startsWith(prefix)) sessionStorage.removeItem(k);
    }
  } catch {
    // Nothing to clear if there is no storage.
  }
}
