/** Shared UI primitives. */
import { useEffect, useId, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './icons.tsx';
import { copyText } from '../hooks.ts';
import { fmtScore, scoreTone, shortHash } from '../format.ts';
import type { Difficulty, ResultStatus, RunStatus } from '../types.ts';

export function cx(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ');
}

// ───────────────────────────── Layout ─────────────────────────────

export function PageHead({ eyebrow, title, sub, actions }: { eyebrow?: ReactNode; title: ReactNode; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="page-head">
      <div className="titles">
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {sub && <p className="sub">{sub}</p>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </header>
  );
}

export function Card({
  title,
  desc,
  tools,
  children,
  className,
  bodyClass,
  foot,
  style,
  id,
}: {
  title?: ReactNode;
  desc?: ReactNode;
  tools?: ReactNode;
  children?: ReactNode;
  className?: string;
  bodyClass?: string;
  foot?: ReactNode;
  style?: CSSProperties;
  id?: string;
}) {
  return (
    <section className={cx('card', className)} style={style} id={id}>
      {(title || tools) && (
        <div className="card-head">
          <div className="t">
            {title && <h2>{title}</h2>}
            {desc && <div className="desc">{desc}</div>}
          </div>
          {tools && <div className="tools">{tools}</div>}
        </div>
      )}
      {children !== undefined && <div className={cx('card-body', bodyClass)}>{children}</div>}
      {foot && <div className="card-foot">{foot}</div>}
    </section>
  );
}

// ───────────────────────────── Badges ─────────────────────────────

const RUN_STATUS: Record<RunStatus, { cls: string; label: string }> = {
  queued: { cls: 'info', label: 'Queued' },
  running: { cls: 'live', label: 'Running' },
  completed: { cls: 'good', label: 'Completed' },
  cancelled: { cls: '', label: 'Cancelled' },
  failed: { cls: 'bad', label: 'Failed' },
  interrupted: { cls: 'warn', label: 'Interrupted' },
};

export function RunStatusBadge({ status, lg }: { status: RunStatus | string | undefined; lg?: boolean }) {
  const s = RUN_STATUS[status as RunStatus] ?? { cls: '', label: String(status ?? 'Unknown') };
  return (
    <span className={cx('badge', s.cls, lg && 'lg')}>
      <span className="dot" aria-hidden="true" />
      {s.label}
    </span>
  );
}

const RESULT_STATUS: Record<ResultStatus, { cls: string; label: string }> = {
  ok: { cls: 'good', label: 'OK' },
  error: { cls: 'bad', label: 'Error' },
  timeout: { cls: 'bad', label: 'Out of time' },
  refusal: { cls: 'warn', label: 'Refusal' },
  'pending-human': { cls: 'info', label: 'Awaiting review' },
  cancelled: { cls: '', label: 'Cancelled' },
};

export function ResultStatusBadge({ status }: { status: ResultStatus | string | undefined }) {
  const s = RESULT_STATUS[status as ResultStatus] ?? { cls: '', label: String(status ?? '?') };
  return (
    <span className={cx('badge', s.cls)}>
      <span className="dot" aria-hidden="true" />
      {s.label}
    </span>
  );
}

/** Score with status colour + icon + value (never colour alone). */
export function ScorePill({ score, status, mode = 'pct', title }: { score: number | null | undefined; status?: ResultStatus; mode?: 'pct' | 'dec'; title?: string }) {
  if (status && status !== 'ok' && (score === null || score === undefined)) {
    const map: Partial<Record<ResultStatus, string>> = { error: 'ERR', timeout: 'T/O', refusal: 'REF', 'pending-human': 'HUMAN', cancelled: 'CXL' };
    const tone = status === 'pending-human' || status === 'cancelled' ? 'none' : 'bad';
    return (
      <span className={cx('score-pill', tone)} title={title ?? status}>
        <span className="ico" aria-hidden="true">
          {tone === 'bad' ? '!' : '…'}
        </span>
        {map[status] ?? status}
      </span>
    );
  }
  const tone = scoreTone(score);
  const icon = tone === 'good' ? '✓' : tone === 'mid' ? '~' : tone === 'bad' ? '✕' : '·';
  const label = tone === 'good' ? 'pass' : tone === 'mid' ? 'partial' : tone === 'bad' ? 'fail' : 'no score';
  return (
    <span className={cx('score-pill', tone)} title={title ?? label} aria-label={`${label} ${fmtScore(score, mode)}`}>
      <span className="ico" aria-hidden="true">
        {icon}
      </span>
      {fmtScore(score, mode)}
    </span>
  );
}

export function Medal({ kind, lg, children }: { kind: 'gold' | 'silver' | 'bronze'; lg?: boolean; children?: ReactNode }) {
  const labels = { gold: 'Gold 🥇', silver: 'Silver 🥈', bronze: 'Bronze 🥉' };
  return (
    <span className={cx('medal', kind, lg && 'lg')} role="img" aria-label={labels[kind]} title={labels[kind]}>
      {children ?? (kind === 'gold' ? '1' : kind === 'silver' ? '2' : '3')}
    </span>
  );
}

export function MedalsInline({ medals }: { medals: { gold: number; silver: number; bronze: number } | undefined }) {
  const m = medals ?? { gold: 0, silver: 0, bronze: 0 };
  return (
    <span className="medals-inline" aria-label={`${m.gold} gold, ${m.silver} silver, ${m.bronze} bronze`}>
      {(['gold', 'silver', 'bronze'] as const).map((k) => (
        <span key={k} className={cx('m', !m[k] && 'zero')}>
          <Medal kind={k} />
          {m[k] ?? 0}
        </span>
      ))}
    </span>
  );
}

const DIFF_LEVEL: Record<Difficulty, number> = { easy: 1, medium: 2, hard: 3, extreme: 4 };

export function DifficultyBadge({ difficulty }: { difficulty: Difficulty | string | undefined }) {
  const lvl = DIFF_LEVEL[difficulty as Difficulty] ?? 0;
  return (
    <span className={cx('difficulty', difficulty === 'extreme' && 'extreme')} title={`Difficulty: ${difficulty ?? 'unknown'}`}>
      <span className="pips" aria-hidden="true">
        {[1, 2, 3, 4].map((i) => (
          <i key={i} className={i <= lvl ? 'on' : ''} />
        ))}
      </span>
      {difficulty ?? '—'}
    </span>
  );
}

export function ModelChip({ label, color, pill, round, title }: { label: string; color?: string; pill?: boolean; round?: boolean; title?: string }) {
  return (
    <span className={cx('chip', pill && 'pill')} title={title ?? label}>
      <span className={cx('sw', round && 'round')} style={{ background: color || 'var(--text-3)' }} aria-hidden="true" />
      <span className="name">{label}</span>
    </span>
  );
}

export function ModelCell({ label, vendor, color, tag }: { label: string; vendor?: string; color?: string; tag?: ReactNode }) {
  return (
    <div className="model-cell">
      <span className="bar" style={{ background: color || 'var(--text-3)' }} aria-hidden="true" />
      <div className="names">
        <span className="label" title={label}>
          {label}
        </span>
        {(vendor || tag) && (
          <span className="vendor-row">
            {vendor && <span className="vendor">{vendor}</span>}
            {tag}
          </span>
        )}
      </div>
    </div>
  );
}

export function CategoryChip({ name, color }: { name: string; color?: string }) {
  return (
    <span className="chip" style={{ fontWeight: 600 }}>
      <span className="cat-dot" style={{ background: color || 'var(--text-3)' }} aria-hidden="true" />
      <span className="name">{name}</span>
    </span>
  );
}

export function HashTag({ value, label, n = 8 }: { value: string | undefined | null; label?: string; n?: number }) {
  const [copied, setCopied] = useState(false);
  if (!value) return <span className="hash">—</span>;
  return (
    <button
      type="button"
      className="hash"
      style={{ cursor: 'copy' }}
      title={`${label ? `${label}: ` : ''}${value} — click to copy`}
      aria-label={`Copy ${label ?? 'hash'} ${value}`}
      onClick={async () => {
        if (await copyText(value)) {
          setCopied(true);
          window.setTimeout(() => setCopied(false), 1200);
        }
      }}
    >
      {copied ? 'copied ✓' : shortHash(value, n)}
    </button>
  );
}

// ───────────────────────────── Controls ─────────────────────────────

export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return <button type="button" role="switch" className="switch" aria-checked={checked} aria-label={label} title={label} disabled={disabled} onClick={() => onChange(!checked)} />;
}

export function Seg<T extends string>({
  value,
  options,
  onChange,
  small,
  label,
}: {
  value: T;
  options: Array<{ value: T; label: ReactNode; title?: string }>;
  onChange: (v: T) => void;
  small?: boolean;
  label: string;
}) {
  return (
    <div className={cx('seg', small && 'sm')} role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} title={o.title} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Tabs<T extends string>({ value, tabs, onChange }: { value: T; tabs: Array<{ id: T; label: ReactNode; count?: number; hidden?: boolean }>; onChange: (v: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {tabs
        .filter((t) => !t.hidden)
        .map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={t.id === value} onClick={() => onChange(t.id)}>
            {t.label}
            {t.count !== undefined && <span className="count">{t.count}</span>}
          </button>
        ))}
    </div>
  );
}

export function CopyButton({ text, label = 'Copy', small = true, iconOnly }: { text: string; label?: string; small?: boolean; iconOnly?: boolean }) {
  const [done, setDone] = useState(false);
  return (
    <button
      type="button"
      className={cx('btn', small ? 'xs' : 'sm', iconOnly && 'icon')}
      aria-label={label}
      title={label}
      onClick={async () => {
        if (await copyText(text)) {
          setDone(true);
          window.setTimeout(() => setDone(false), 1400);
        }
      }}
    >
      {done ? <Icon.Check /> : <Icon.Copy />}
      {!iconOnly && (done ? 'Copied' : label)}
    </button>
  );
}

export function Field({ label, hint, error, children, className, htmlFor }: { label: ReactNode; hint?: ReactNode; error?: ReactNode; children: ReactNode; className?: string; htmlFor?: string }) {
  return (
    <div className={cx('field', className)}>
      <label className="label" htmlFor={htmlFor}>
        {label}
      </label>
      {children}
      {error ? <div className="err">{error}</div> : hint ? <div className="hint">{hint}</div> : null}
    </div>
  );
}

export function Progress({ value, color, lg, striped, label }: { value: number; color?: string; lg?: boolean; striped?: boolean; label?: string }) {
  const pct = Math.max(0, Math.min(1, Number.isFinite(value) ? value : 0)) * 100;
  return (
    <div className={cx('progress', lg && 'lg', striped && 'striped')} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)} aria-label={label}>
      <span style={{ width: `${pct}%`, background: color }} />
    </div>
  );
}

export function SortHeader<K extends string>({ k, sort, onSort, children, title }: { k: K; sort: { key: K; dir: 1 | -1 }; onSort: (k: K) => void; children: ReactNode; title?: string }) {
  const active = sort.key === k;
  return (
    <button type="button" className={cx('sort-btn', active && 'active')} onClick={() => onSort(k)} title={title} aria-label={`Sort by ${typeof children === 'string' ? children : k}`}>
      {children}
      <span className="arrow" aria-hidden="true">
        {active ? (sort.dir === 1 ? '▲' : '▼') : '↕'}
      </span>
    </button>
  );
}

// ───────────────────────────── Feedback ─────────────────────────────

export function Skeleton({ h = 14, w = '100%', r, style }: { h?: number | string; w?: number | string; r?: number; style?: CSSProperties }) {
  return <div className="skeleton" style={{ height: h, width: w, borderRadius: r, ...style }} aria-hidden="true" />;
}

export function SkeletonRows({ rows = 6, h = 38 }: { rows?: number; h?: number }) {
  return (
    <div className="stack tight" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} h={h} style={{ opacity: 1 - i * 0.1 }} />
      ))}
    </div>
  );
}

export function LoadingPage() {
  return (
    <div className="page" aria-busy="true">
      <div className="stack tight">
        <Skeleton h={12} w={120} />
        <Skeleton h={32} w={360} />
      </div>
      <div className="stats">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} h={78} r={10} />
        ))}
      </div>
      <div className="card pad">
        <SkeletonRows rows={7} />
      </div>
    </div>
  );
}

export function Empty({ icon, title, children, actions }: { icon?: ReactNode; title: ReactNode; children?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="empty">
      <div className="art">{icon ?? <Icon.Sparkles />}</div>
      <h3>{title}</h3>
      {children && <p>{children}</p>}
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, title = 'Something went wrong' }: { error: Error | null | undefined; onRetry?: () => void; title?: string }) {
  return (
    <div className="card">
      <Empty
        icon={<Icon.Alert />}
        title={title}
        actions={
          onRetry && (
            <button className="btn" onClick={onRetry}>
              <Icon.Refresh /> Retry
            </button>
          )
        }
      >
        {error?.message ?? 'Unknown error'}
      </Empty>
    </div>
  );
}

export function Callout({ tone = 'info', icon, children }: { tone?: 'info' | 'warn' | 'bad' | 'plain'; icon?: ReactNode; children: ReactNode }) {
  const ico = icon ?? (tone === 'warn' || tone === 'bad' ? <Icon.Alert /> : <Icon.Info />);
  return (
    <div className={cx('callout', tone !== 'plain' && tone)} role={tone === 'bad' ? 'alert' : undefined}>
      {ico}
      <div>{children}</div>
    </div>
  );
}

// ───────────────────────────── Overlays ─────────────────────────────

function useEscape(onClose: () => void) {
  const ref = useRef(onClose);
  ref.current = onClose;
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      if (e.key === 'Escape') ref.current();
    };
    window.addEventListener('keydown', on);
    return () => window.removeEventListener('keydown', on);
  }, []);
}

function useFocusTrap(open: boolean) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const el = ref.current;
    const first = el?.querySelector<HTMLElement>('[data-autofocus]') ?? el;
    first?.focus({ preventScroll: true });
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prevOverflow;
      prev?.focus?.({ preventScroll: true });
    };
  }, [open]);
  return ref;
}

export function Drawer({
  open,
  onClose,
  title,
  sub,
  children,
  foot,
  width,
  headExtra,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  sub?: ReactNode;
  children: ReactNode;
  foot?: ReactNode;
  width?: number | string;
  headExtra?: ReactNode;
}) {
  const ref = useFocusTrap(open);
  const titleId = useId();
  useEscape(() => open && onClose());
  if (!open) return null;
  return createPortal(
    <div className="drawer-root" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="backdrop" onClick={onClose} />
      <div className="drawer" ref={ref} tabIndex={-1} style={{ ['--drawer-w' as string]: typeof width === 'number' ? `${width}px` : width }}>
        <div className="drawer-head">
          <div className="t">
            <h2 id={titleId}>{title}</h2>
            {sub && <div className="muted" style={{ fontSize: '0.86rem' }}>{sub}</div>}
          </div>
          {headExtra}
          <button className="btn ghost icon sm" onClick={onClose} aria-label="Close panel">
            <Icon.X />
          </button>
        </div>
        <div className="drawer-body">{children}</div>
        {foot && <div className="drawer-foot">{foot}</div>}
      </div>
    </div>,
    document.body,
  );
}

export function Modal({ open, onClose, title, children, foot, width }: { open: boolean; onClose: () => void; title: ReactNode; children: ReactNode; foot?: ReactNode; width?: number }) {
  const ref = useFocusTrap(open);
  const titleId = useId();
  useEscape(() => open && onClose());
  if (!open) return null;
  return createPortal(
    <div className="modal-root" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="backdrop" onClick={onClose} />
      <div className="modal" ref={ref} tabIndex={-1} style={{ ['--modal-w' as string]: width ? `${width}px` : undefined }}>
        <div className="modal-head">
          <h2 id={titleId}>{title}</h2>
        </div>
        <div className="modal-body">{children}</div>
        {foot && <div className="modal-foot">{foot}</div>}
      </div>
    </div>,
    document.body,
  );
}

export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = 'Confirm',
  danger,
  onConfirm,
  onCancel,
  busy,
}: {
  open: boolean;
  title: ReactNode;
  body: ReactNode;
  confirmLabel?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  busy?: boolean;
}) {
  return (
    <Modal
      open={open}
      onClose={onCancel}
      title={title}
      foot={
        <>
          <button className="btn ghost" onClick={onCancel} data-autofocus>
            Cancel
          </button>
          <button className={cx('btn', danger ? 'danger' : 'primary')} onClick={onConfirm} disabled={busy}>
            {busy ? 'Working…' : confirmLabel}
          </button>
        </>
      }
    >
      {body}
    </Modal>
  );
}

/** Pointer-following tooltip rendered in a portal. */
export function FloatingTip({ x, y, children }: { x: number; y: number; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ left: x + 14, top: y + 14 });
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    let left = x + 16;
    let top = y + 16;
    if (left + w > window.innerWidth - 8) left = x - w - 16;
    if (top + h > window.innerHeight - 8) top = y - h - 16;
    setPos({ left: Math.max(8, left), top: Math.max(8, top) });
  }, [x, y]);
  return createPortal(
    <div className="tip" ref={ref} style={pos} role="tooltip">
      {children}
    </div>,
    document.body,
  );
}
