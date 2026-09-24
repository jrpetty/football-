/** App-wide state: server meta, theme, broadcast mode, toasts. */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { api } from './api.ts';
import type { CategoryInfo, Meta } from './types.ts';
import { cx } from './components/ui.tsx';

// ───────────────────────────── Meta ─────────────────────────────

interface MetaCtx {
  meta: Meta | null;
  error: Error | null;
  loading: boolean;
  reload: () => void;
  categories: CategoryInfo[];
  cat: (id: string) => CategoryInfo;
}

const MetaContext = createContext<MetaCtx | null>(null);

const FALLBACK_COLORS = ['#6366F1', '#0EA5E9', '#10B981', '#F59E0B', '#EF4444', '#8B5CF6', '#14B8A6', '#EC4899', '#F97316', '#84CC16', '#64748B'];

function titleCase(id: string) {
  return id.replace(/[-_.]+/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

export function MetaProvider({ children }: { children: ReactNode }) {
  const [meta, setMeta] = useState<Meta | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    api
      .meta()
      .then((m) => {
        if (!alive) return;
        setMeta(m);
        setError(null);
      })
      .catch((e: unknown) => alive && setError(e instanceof Error ? e : new Error(String(e))))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [nonce]);

  const value = useMemo<MetaCtx>(() => {
    const categories = meta?.categories ?? [];
    const map = new Map(categories.map((c) => [c.id, c]));
    const cat = (id: string): CategoryInfo => {
      const found = map.get(id);
      if (found) return found;
      let h = 0;
      for (const ch of id) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
      return { id, name: titleCase(id || 'uncategorised'), description: '', color: FALLBACK_COLORS[h % FALLBACK_COLORS.length], weight: 1 };
    };
    return { meta, error, loading, reload: () => setNonce((n) => n + 1), categories, cat };
  }, [meta, error, loading]);

  return <MetaContext.Provider value={value}>{children}</MetaContext.Provider>;
}

export function useMeta(): MetaCtx {
  const ctx = useContext(MetaContext);
  if (!ctx) throw new Error('useMeta outside MetaProvider');
  return ctx;
}

// ───────────────────────────── Preferences ─────────────────────────────

type Theme = 'dark' | 'light';

interface PrefsCtx {
  theme: Theme;
  setTheme: (t: Theme) => void;
  broadcast: boolean;
  setBroadcast: (b: boolean) => void;
  /** Viewer caption strip in broadcast mode (toggle with C). */
  captions: boolean;
  setCaptions: (b: boolean) => void;
}

const PrefsContext = createContext<PrefsCtx | null>(null);

function readTheme(): Theme {
  try {
    const t = window.localStorage.getItem('gauntlet.theme');
    if (t === 'light' || t === 'dark') return t;
  } catch {
    /* ignore */
  }
  return 'dark';
}

function readBroadcast(): boolean {
  try {
    const q = new URLSearchParams(window.location.search).get('broadcast');
    if (q === '1' || q === 'true') return true;
    return window.sessionStorage.getItem('gauntlet.broadcast') === '1';
  } catch {
    return false;
  }
}

function readCaptions(): boolean {
  try {
    return window.localStorage.getItem('gauntlet.captions') !== '0';
  } catch {
    return true;
  }
}

export function PrefsProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(readTheme);
  const [broadcast, setBroadcastState] = useState<boolean>(readBroadcast);
  const [captions, setCaptionsState] = useState<boolean>(readCaptions);

  useEffect(() => {
    try {
      window.localStorage.setItem('gauntlet.captions', captions ? '1' : '0');
    } catch {
      /* ignore */
    }
  }, [captions]);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    try {
      window.localStorage.setItem('gauntlet.theme', theme);
    } catch {
      /* ignore */
    }
  }, [theme]);

  useEffect(() => {
    if (broadcast) document.documentElement.setAttribute('data-broadcast', 'on');
    else document.documentElement.removeAttribute('data-broadcast');
    try {
      window.sessionStorage.setItem('gauntlet.broadcast', broadcast ? '1' : '0');
    } catch {
      /* ignore */
    }
  }, [broadcast]);

  const value = useMemo<PrefsCtx>(
    () => ({ theme, setTheme: setThemeState, broadcast, setBroadcast: setBroadcastState, captions, setCaptions: setCaptionsState }),
    [theme, broadcast, captions],
  );
  return <PrefsContext.Provider value={value}>{children}</PrefsContext.Provider>;
}

export function usePrefs(): PrefsCtx {
  const ctx = useContext(PrefsContext);
  if (!ctx) throw new Error('usePrefs outside PrefsProvider');
  return ctx;
}

// ───────────────────────────── Viewer captions ─────────────────────────────

/**
 * One-sentence "What you're seeing" captions for screen recordings. Screens
 * register a caption with useViewerCaption(); the most recently mounted one
 * wins (so an open replay drawer overrides the page underneath). The strip is
 * only drawn in broadcast mode with captions on.
 */
export interface ViewerCaptionEntry {
  text: string;
  /** Precise terminology in small print, e.g. "Whiskers: 95% bootstrap confidence interval". */
  fine?: string;
}

interface CaptionCtx {
  set: (id: number, e: ViewerCaptionEntry | null) => void;
  current: ViewerCaptionEntry | null;
}

const CaptionContext = createContext<CaptionCtx | null>(null);
let captionSeq = 0;

export function CaptionProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<Array<[number, ViewerCaptionEntry]>>([]);
  const set = useCallback((id: number, e: ViewerCaptionEntry | null) => {
    setEntries((xs) => {
      const rest = xs.filter(([k]) => k !== id);
      if (!e) return rest.length === xs.length ? xs : rest;
      const old = xs.find(([k]) => k === id)?.[1];
      if (old && old.text === e.text && old.fine === e.fine) return xs;
      return [...rest, [id, e] as [number, ViewerCaptionEntry]].sort((a, b) => a[0] - b[0]);
    });
  }, []);
  const current = entries.length ? entries[entries.length - 1][1] : null;
  const value = useMemo<CaptionCtx>(() => ({ set, current }), [set, current]);
  return <CaptionContext.Provider value={value}>{children}</CaptionContext.Provider>;
}

export function useViewerCaption(text: string | null | undefined, fine?: string): void {
  const ctx = useContext(CaptionContext);
  const idRef = useRef(0);
  if (!idRef.current) idRef.current = ++captionSeq;
  const set = ctx?.set;
  useEffect(() => {
    if (!set) return;
    const id = idRef.current;
    set(id, text ? { text, fine } : null);
  }, [set, text, fine]);
  useEffect(() => {
    const id = idRef.current;
    return () => set?.(id, null);
  }, [set]);
}

export function useCurrentCaption(): ViewerCaptionEntry | null {
  return useContext(CaptionContext)?.current ?? null;
}

// ───────────────────────────── Toasts ─────────────────────────────

type ToastKind = 'error' | 'success' | 'info';
interface ToastItem {
  id: number;
  kind: ToastKind;
  title?: string;
  message: string;
}
interface ToastApi {
  error: (message: unknown, title?: string) => void;
  success: (message: string, title?: string) => void;
  info: (message: string, title?: string) => void;
}

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(0);

  const push = useCallback((kind: ToastKind, message: string, title?: string) => {
    const id = ++seq.current;
    setItems((xs) => [...xs.slice(-3), { id, kind, message, title }]);
    window.setTimeout(() => setItems((xs) => xs.filter((t) => t.id !== id)), kind === 'error' ? 7000 : 3800);
  }, []);

  const api = useMemo<ToastApi>(
    () => ({
      error: (m, title) => push('error', m instanceof Error ? m.message : String(m), title ?? 'Error'),
      success: (m, title) => push('success', m, title),
      info: (m, title) => push('info', m, title),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className="toasts" aria-live="polite" aria-atomic="false">
        {items.map((t) => (
          <div key={t.id} className={cx('toast', t.kind)} role={t.kind === 'error' ? 'alert' : 'status'}>
            <span className="ico" aria-hidden="true">
              {t.kind === 'error' ? '!' : t.kind === 'success' ? '✓' : 'i'}
            </span>
            <div className="msg">
              {t.title && <strong>{t.title}</strong>}
              {t.message}
            </div>
            <button className="btn ghost icon xs" aria-label="Dismiss" onClick={() => setItems((xs) => xs.filter((x) => x.id !== t.id))}>
              ✕
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast outside ToastProvider');
  return ctx;
}
