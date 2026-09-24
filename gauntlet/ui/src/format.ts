/** Number / date formatting helpers. Every helper tolerates null/undefined/NaN and returns an em dash. */

export const DASH = '—';

const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

/** `$0.0042`, `$0.052`, `$1.23`, `$1,234` */
export function fmtCost(usd: number | null | undefined): string {
  if (!isNum(usd)) return DASH;
  if (usd === 0) return '$0.00';
  const sign = usd < 0 ? '-' : '';
  const v = Math.abs(usd);
  if (v >= 1000) return `${sign}$${Math.round(v).toLocaleString('en-US')}`;
  if (v >= 1) return `${sign}$${v.toFixed(2)}`;
  if (v >= 0.1) return `${sign}$${v.toFixed(2)}`;
  if (v < 0.000001) return `${sign}<$0.000001`;
  // Two significant figures for small amounts: 0.052, 0.0042, 0.00013
  const digits = Math.min(6, Math.max(2, 1 - Math.floor(Math.log10(v))));
  return `${sign}$${v.toFixed(digits)}`;
}

/** Compact cost for axis ticks: `$0.01`, `$1`, `$10`, `$1k`. */
export function fmtCostTick(usd: number): string {
  if (usd >= 1000) return `$${+(usd / 1000).toFixed(1)}k`;
  if (usd >= 1) return `$${+usd.toFixed(2)}`;
  return `$${+usd.toPrecision(1)}`;
}

/** `840 ms`, `12.4 s`, `3m 05s`, `1h 02m` */
export function fmtMs(ms: number | null | undefined): string {
  if (!isNum(ms)) return DASH;
  if (ms < 0) ms = 0;
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const totalSec = Math.round(ms / 1000);
  if (totalSec < 3600) {
    const m = Math.floor(totalSec / 60);
    const s = totalSec % 60;
    return `${m}m ${String(s).padStart(2, '0')}s`;
  }
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  return `${h}h ${String(m).padStart(2, '0')}m`;
}

/** Clock-style elapsed time: `04:12` or `1:04:12`. */
export function fmtClock(ms: number | null | undefined): string {
  if (!isNum(ms) || ms < 0) return '00:00';
  const t = Math.floor(ms / 1000);
  const h = Math.floor(t / 3600);
  const m = Math.floor((t % 3600) / 60);
  const s = t % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** `842`, `12.3k`, `1.2M` */
export function fmtTokens(n: number | null | undefined): string {
  if (!isNum(n)) return DASH;
  const v = Math.abs(n);
  if (v < 1000) return String(Math.round(n));
  if (v < 1_000_000) return `${+(n / 1000).toFixed(v < 10_000 ? 1 : v < 100_000 ? 1 : 0)}k`;
  if (v < 1_000_000_000) return `${+(n / 1_000_000).toFixed(1)}M`;
  return `${+(n / 1_000_000_000).toFixed(1)}B`;
}

/** Integer with thousands separators. */
export function fmtInt(n: number | null | undefined): string {
  if (!isNum(n)) return DASH;
  return Math.round(n).toLocaleString('en-US');
}

/** Fraction 0..1 → `42%` (digits = decimals). */
export function fmtPct(x: number | null | undefined, digits = 0): string {
  if (!isNum(x)) return DASH;
  return `${(x * 100).toFixed(digits)}%`;
}

/** Gauntlet Index (0..100) to one decimal. */
export function fmtIndex(x: number | null | undefined): string {
  if (!isNum(x)) return DASH;
  return x.toFixed(1);
}

/** Case / test score (0..1) → `0.83`, or `83%` with mode 'pct'. */
export function fmtScore(x: number | null | undefined, mode: 'dec' | 'pct' = 'dec'): string {
  if (!isNum(x)) return DASH;
  return mode === 'pct' ? `${Math.round(x * 100)}%` : x.toFixed(2);
}

/** Score 0..1 as a 0–100 integer string, for dense heatmap cells. */
export function fmtScore100(x: number | null | undefined): string {
  if (!isNum(x)) return DASH;
  return String(Math.round(x * 100));
}

export function fmtRate(tokPerSec: number | null | undefined): string {
  if (!isNum(tokPerSec)) return DASH;
  return `${tokPerSec >= 100 ? Math.round(tokPerSec) : tokPerSec.toFixed(1)} tok/s`;
}

export function fmtPricePerM(usd: number | null | undefined): string {
  if (!isNum(usd)) return DASH;
  if (usd === 0) return '$0';
  return `$${usd < 1 ? usd.toFixed(3).replace(/0+$/, '').replace(/\.$/, '') : usd.toFixed(2).replace(/\.00$/, '')}`;
}

export function fmtBytes(n: number | null | undefined): string {
  if (!isNum(n)) return DASH;
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

function toDate(d: string | number | Date | null | undefined): Date | null {
  if (d === null || d === undefined || d === '') return null;
  const date = d instanceof Date ? d : new Date(d);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function fmtDate(d: string | number | Date | null | undefined): string {
  const date = toDate(d);
  if (!date) return DASH;
  return date.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
}

export function fmtDateTime(d: string | number | Date | null | undefined): string {
  const date = toDate(d);
  if (!date) return DASH;
  return date.toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function fmtRelative(d: string | number | Date | null | undefined, now = Date.now()): string {
  const date = toDate(d);
  if (!date) return DASH;
  const diff = (now - date.getTime()) / 1000;
  const abs = Math.abs(diff);
  const suffix = diff >= 0 ? 'ago' : 'from now';
  if (abs < 45) return 'just now';
  if (abs < 3600) return `${Math.round(abs / 60)}m ${suffix}`;
  if (abs < 86400) return `${Math.round(abs / 3600)}h ${suffix}`;
  if (abs < 86400 * 14) return `${Math.round(abs / 86400)}d ${suffix}`;
  return fmtDate(date);
}

export function shortHash(h: string | null | undefined, n = 8): string {
  if (!h) return DASH;
  return h.replace(/^sha256[:-]/, '').slice(0, n);
}

export function durationBetween(a?: string | null, b?: string | null): number | null {
  const da = toDate(a);
  const db = toDate(b);
  if (!da || !db) return null;
  return db.getTime() - da.getTime();
}

/** Plain-language status for a score (used with status colours + icon). */
export function scoreTone(score: number | null | undefined): 'good' | 'mid' | 'bad' | 'none' {
  if (!isNum(score)) return 'none';
  if (score >= 0.8) return 'good';
  if (score >= 0.4) return 'mid';
  return 'bad';
}

export function pluralize(n: number, one: string, many = `${one}s`): string {
  return `${fmtInt(n)} ${n === 1 ? one : many}`;
}

export function slugify(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48);
}

export function prettyJson(v: unknown): string {
  if (v === undefined) return '';
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}
