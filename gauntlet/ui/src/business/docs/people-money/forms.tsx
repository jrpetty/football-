/**
 * A paper form (the accounts system's invoice form, a screening form…) filled in by the model: one numbered box
 * per field, ticked or crossed, the wrong value struck out with the correct one beside it; and the helper that
 * turns "which lines each field comes from" into numbered highlighter marks on the source document.
 */
import type { ReactNode } from 'react';
import { cx } from '../../../components/ui.tsx';
import { Paper, Pin, Tick, type Tone } from './kit.tsx';

export interface FormRow {
  n: number;
  label: string;
  got: string;
  want: string;
  ok: boolean | null;
  why?: string | null;
  hint?: string;
}

export function FieldForm({ title, sub, rows, show, columns }: { title: string; sub?: string; rows: FormRow[]; show: 'blank' | 'model' | 'key'; columns?: boolean }) {
  return (
    <Paper className={cx('pm-form', columns && 'cols')} label={title}>
      <div className="pm-form-head">
        <b>{title}</b>
        {sub && <span>{sub}</span>}
      </div>
      <div className="pm-form-rows">
        {rows.map((r) => {
          const tone: Tone = show === 'key' ? 'key' : show === 'blank' ? 'muted' : r.ok === false ? 'bad' : 'good';
          return (
            <div key={r.n} className={cx('pm-form-row', show === 'model' && r.ok === false && 'bad')}>
              <Pin tone={tone} n={r.n} />
              <span className="lbl">{r.label}</span>
              <span>{show === 'model' && r.ok !== null && <Tick ok={r.ok} size="lg" />}</span>
              <span className={cx('box', show === 'blank' && 'empty')}>
                {show === 'blank' ? (
                  (r.hint ?? '')
                ) : show === 'key' ? (
                  r.want
                ) : r.ok === false ? (
                  <>
                    <s>{r.got}</s>
                    <span className="fix">✓ {r.want}</span>
                  </>
                ) : (
                  r.got
                )}
              </span>
              {show === 'model' && r.why && <span className="why">{r.why}</span>}
            </div>
          );
        })}
      </div>
    </Paper>
  );
}

export interface Marks {
  /** line → pins (number, tone) and the line's tone. */
  get: (line: number) => { pins: Array<{ n: number; tone: Tone }>; tone: Tone } | undefined;
}

/** Numbered marks per source line: green / red per field, blue when a line serves right and wrong fields. */
export function marksFrom(sources: Record<string, number[]>, order: string[], toneOf: (path: string) => Tone): Marks {
  const out = new Map<number, { pins: Array<{ n: number; tone: Tone }>; tone: Tone }>();
  order.forEach((path, i) => {
    for (const l of sources[path] ?? []) {
      const m = out.get(l) ?? { pins: [], tone: 'good' as Tone };
      if (!m.pins.some((p) => p.n === i + 1)) m.pins.push({ n: i + 1, tone: toneOf(path) });
      out.set(l, m);
    }
  });
  for (const m of out.values()) {
    const tones = new Set(m.pins.map((p) => p.tone));
    m.tone = tones.size === 1 ? m.pins[0]!.tone : 'info';
    // A long list of pins on one line is hard to read: keep the first six.
    if (m.pins.length > 6) m.pins = m.pins.slice(0, 6);
  }
  return { get: (l) => out.get(l) };
}

export function Pins({ pins }: { pins: Array<{ n: number; tone: Tone }> }): ReactNode {
  return pins.map((p) => <Pin key={p.n} tone={p.tone} n={p.n} />);
}
