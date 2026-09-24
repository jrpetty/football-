import type { CompletionRequest, CompletionResult, Contestant, ProviderConfig } from '../core/types.ts';

/** A single attempt at a completion. Retries are handled by the engine so every provider is retried identically. */
export interface ProviderAdapter {
  complete(req: CompletionRequest): Promise<Omit<CompletionResult, 'retries'>>;
}

export interface AdapterContext {
  provider: ProviderConfig;
  contestant: Contestant;
  apiKey: string | undefined;
}

/** Normalised provider error. `retryable` drives the engine's backoff policy. */
export class ProviderError extends Error {
  status: number | undefined;
  retryable: boolean;
  retryAfterMs: number | undefined;
  constructor(message: string, opts: { status?: number; retryable: boolean; retryAfterMs?: number; cause?: unknown }) {
    super(message, { cause: opts.cause });
    this.name = 'ProviderError';
    this.status = opts.status;
    this.retryable = opts.retryable;
    this.retryAfterMs = opts.retryAfterMs;
  }
}

export function isRetryableStatus(status: number | undefined): boolean {
  return status === undefined || status === 408 || status === 409 || status === 425 || status === 429 || status >= 500;
}

export function parseRetryAfter(value: string | null | undefined): number | undefined {
  if (!value) return undefined;
  const secs = Number(value);
  if (Number.isFinite(secs)) return Math.max(0, secs * 1000);
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - Date.now()) : undefined;
}

/** Deep-merge `extra` into `target` (objects merge, everything else replaces). */
export function deepMerge<T extends Record<string, unknown>>(target: T, extra: Record<string, unknown> | undefined): T {
  if (!extra) return target;
  for (const [k, v] of Object.entries(extra)) {
    const cur = (target as Record<string, unknown>)[k];
    if (v && typeof v === 'object' && !Array.isArray(v) && cur && typeof cur === 'object' && !Array.isArray(cur)) {
      deepMerge(cur as Record<string, unknown>, v as Record<string, unknown>);
    } else {
      (target as Record<string, unknown>)[k] = v;
    }
  }
  return target;
}

export function isAbortError(err: unknown): boolean {
  const e = err as { name?: string } | undefined;
  return e?.name === 'AbortError' || e?.name === 'APIUserAbortError';
}
