/** Loads a viewer file's content (text or bytes) from its URL on demand, with the size limit and a small cache. */
import { useEffect, useState } from 'react';
import { LIMITS, dataUrlBytes } from './detect.ts';
import type { LoadedContent, ViewerFile } from './types.ts';

const cache = new Map<string, Promise<{ bytes?: Uint8Array; tooBig?: boolean; error?: string }>>();

function fetchBytes(url: string): Promise<{ bytes?: Uint8Array; tooBig?: boolean; error?: string }> {
  let p = cache.get(url);
  if (!p) {
    p = (async () => {
      if (url.startsWith('data:')) {
        const b = dataUrlBytes(url);
        return b ? { bytes: b } : { error: 'Unreadable data URL' };
      }
      const res = await fetch(url);
      if (!res.ok) return { error: `Could not load the file (HTTP ${res.status})` };
      const len = Number(res.headers.get('content-length') ?? 0);
      if (len > LIMITS.fetch) return { tooBig: true };
      const buf = new Uint8Array(await res.arrayBuffer());
      return buf.length > LIMITS.fetch ? { tooBig: true } : { bytes: buf };
    })().catch((e: unknown) => ({ error: (e as Error).message || 'Network error' }));
    cache.set(url, p);
    if (cache.size > 60) cache.delete(cache.keys().next().value!);
  }
  return p;
}

export function useFileContent(file: ViewerFile | null, needs: 'text' | 'bytes' | 'none'): LoadedContent {
  const [state, setState] = useState<LoadedContent>({ loading: false });
  useEffect(() => {
    if (!file) return;
    if (file.text !== undefined) {
      setState({ loading: false, text: file.text, bytes: needs === 'bytes' ? new TextEncoder().encode(file.text) : undefined });
      return;
    }
    if (file.bytes) {
      setState({ loading: false, bytes: file.bytes, text: needs === 'text' ? new TextDecoder().decode(file.bytes) : undefined });
      return;
    }
    if (!file.url || needs === 'none') {
      setState({ loading: false });
      return;
    }
    if ((file.size ?? 0) > LIMITS.fetch) {
      setState({ loading: false, tooBig: true });
      return;
    }
    let alive = true;
    setState({ loading: true });
    void fetchBytes(file.url).then((r) => {
      if (!alive) return;
      setState({ loading: false, error: r.error, tooBig: r.tooBig, bytes: r.bytes, text: r.bytes ? new TextDecoder().decode(r.bytes) : undefined });
    });
    return () => {
      alive = false;
    };
  }, [file, needs]);
  return state;
}
