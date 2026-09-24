/** Minimal hash router: `#/path/segments?query`. */
import { useEffect, useState } from 'react';
import type { AnchorHTMLAttributes, ReactNode } from 'react';

export interface Route {
  path: string;
  parts: string[];
  query: URLSearchParams;
}

function parse(): Route {
  const raw = window.location.hash.replace(/^#/, '') || '/';
  const [p, q = ''] = raw.split('?');
  const path = p.startsWith('/') ? p : `/${p}`;
  const parts = path
    .split('/')
    .filter(Boolean)
    .map((s) => {
      try {
        return decodeURIComponent(s);
      } catch {
        return s;
      }
    });
  return { path, parts, query: new URLSearchParams(q) };
}

export function useRoute(): Route {
  const [route, setRoute] = useState(parse);
  useEffect(() => {
    const on = () => setRoute(parse());
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return route;
}

/** Build a hash href. Segments are URL-encoded. */
export function href(path: string, query?: Record<string, string | number | undefined | null>): string {
  const qs = query
    ? Object.entries(query)
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
        .join('&')
    : '';
  return `#${path}${qs ? `?${qs}` : ''}`;
}

/** Path builder that encodes each dynamic segment. */
export function pathOf(...segments: string[]): string {
  return '/' + segments.map((s) => encodeURIComponent(s)).join('/');
}

export function navigate(path: string, query?: Record<string, string | number | undefined | null>, replace = false): void {
  const h = href(path, query);
  if (replace) {
    const url = `${window.location.pathname}${window.location.search}${h}`;
    window.history.replaceState(null, '', url);
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  } else {
    window.location.hash = h.slice(1);
  }
}

/** Update the query of the current hash route without adding history entries. */
export function setQuery(patch: Record<string, string | number | undefined | null>): void {
  const r = parse();
  const q = new URLSearchParams(r.query);
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined || v === null || v === '') q.delete(k);
    else q.set(k, String(v));
  }
  const obj: Record<string, string> = {};
  q.forEach((v, k) => (obj[k] = v));
  navigate(r.path, obj, true);
}

export function Link({ to, children, ...rest }: { to: string; children: ReactNode } & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'>) {
  return (
    <a href={to.startsWith('#') ? to : `#${to}`} {...rest}>
      {children}
    </a>
  );
}
