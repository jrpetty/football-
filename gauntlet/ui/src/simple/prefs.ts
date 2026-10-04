/**
 * Per-browser settings for simple mode, the first-visit page guides and the Home checklist.
 * Everything lives in localStorage and every read/write is wrapped in try/catch: with storage blocked
 * (private window, locked-down browser) the app still works, it just forgets.
 */
import { useEffect, useState } from 'react';

const K = {
  simple: 'gauntlet.simpleMode',
  dismissed: 'gauntlet.guides.dismissed',
  seenResults: 'gauntlet.seen.results',
  seenPresenter: 'gauntlet.seen.presenter',
  checklistHidden: 'gauntlet.checklist.hidden',
} as const;

const EVENT = 'gauntlet:simple-prefs';

function get(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function set(key: string, value: string | null): void {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
  } catch {
    /* storage blocked: keep going without remembering */
  }
  try {
    window.dispatchEvent(new Event(EVENT));
  } catch {
    /* not in a browser */
  }
}

/** Simple mode is the default; "Show all tools" turns it off. */
export const isSimpleMode = (): boolean => get(K.simple) !== '0';
export const setSimpleMode = (on: boolean): void => set(K.simple, on ? '1' : '0');

export function dismissedGuides(): Set<string> {
  try {
    const v = JSON.parse(get(K.dismissed) ?? '[]') as unknown;
    return new Set(Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
  } catch {
    return new Set();
  }
}
export function dismissGuide(key: string): void {
  const s = dismissedGuides();
  s.add(key);
  set(K.dismissed, JSON.stringify([...s]));
}

export type SeenFlag = 'results' | 'presenter';
export const hasSeen = (f: SeenFlag): boolean => get(f === 'results' ? K.seenResults : K.seenPresenter) === '1';
export function markSeen(f: SeenFlag): void {
  if (!hasSeen(f)) set(f === 'results' ? K.seenResults : K.seenPresenter, '1');
}

export const isChecklistHidden = (): boolean => get(K.checklistHidden) === '1';
export const setChecklistHidden = (hidden: boolean): void => set(K.checklistHidden, hidden ? '1' : null);

/** "Show tips again": every page guide comes back, and so does the Home checklist. */
export function resetTips(): void {
  set(K.dismissed, null);
  set(K.checklistHidden, null);
}

/** Re-render when any of these settings change (in this tab). */
export function useSimplePrefs<T>(read: () => T): T {
  const [v, setV] = useState(read);
  useEffect(() => {
    const on = () => setV(read());
    window.addEventListener(EVENT, on);
    window.addEventListener('storage', on);
    return () => {
      window.removeEventListener(EVENT, on);
      window.removeEventListener('storage', on);
    };
  }, []);
  return v;
}
