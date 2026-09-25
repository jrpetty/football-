/**
 * Data extraction: expected JSON vs the model's JSON, leaf by leaf, with the
 * source document beside it. Lines that correct / retract / change something
 * are marked as traps; lines holding a wrong value the model copied are red.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { extractionHeadline, type ExtractionVisual, type JsonLeaf } from '../../../../src/presenter/visuals/extraction.ts';
import { cx } from '../ui.tsx';
import { ToneMark, VizFrame, type VizMode } from './VizFrame.tsx';

const show = (v: unknown) => (typeof v === 'string' ? `"${v}"` : JSON.stringify(v));

export function JsonTruthDiff({ v, mode, score }: { v: ExtractionVisual; mode: VizMode; score?: number | null }) {
  // Start on the first wrong field, so its document line is in view.
  const [focus, setFocus] = useState<JsonLeaf | null>(() => v.leaves.find((l) => !l.passed && (l.wrongLines.length > 0 || l.sourceLine !== null)) ?? v.leaves.find((l) => !l.passed) ?? null);
  const docRef = useRef<HTMLOListElement>(null);
  const wrong = v.leaves.filter((l) => !l.passed);
  const wrongLines = useMemo(() => new Set(wrong.flatMap((l) => l.wrongLines)), [wrong]);
  const traps = useMemo(() => new Set(v.trapLines), [v.trapLines]);
  // Slides show the wrong fields first (and only as many as fit).
  const nodes = mode === 'slide' ? v.nodes.filter((n) => n.leaf && !n.leaf.passed).slice(0, 9) : v.nodes;
  const tone = !v.parsedOk ? 'bad' : v.matched === v.total ? 'good' : (score ?? 0) > 0 ? 'half' : 'bad';
  const target = focus?.sourceLine ?? focus?.wrongLines[0] ?? null;
  // On a slide, only the lines that matter: traps, and where wrong and right values sit.
  const docLines = useMemo(() => {
    const all = v.document.map((_, i) => i);
    if (mode !== 'slide') return all;
    const keep = new Set<number>([...v.trapLines, ...wrongLines, ...wrong.flatMap((l) => (l.sourceLine === null ? [] : [l.sourceLine]))]);
    const picked = all.filter((i) => keep.has(i) && v.document[i]!.trim());
    return picked.length ? picked.slice(0, 12) : all;
  }, [mode, v.document, v.trapLines, wrongLines, wrong]);
  const excerpt = docLines.length < v.document.length;

  useEffect(() => {
    if (target === null) return;
    const el = docRef.current?.querySelector<HTMLElement>(`[data-line="${target}"]`);
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [target]);

  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow="Messy text → exact JSON"
      headline={extractionHeadline(v)}
      big={`${v.matched}/${v.total}`}
      bigSub={v.matched === v.total ? 'fields exact' : (score ?? 0) > 0 ? 'checks right · partial credit' : 'checks right · all needed'}
      legend={[
        { tone: 'good', label: 'Field matches the key' },
        { tone: 'bad', label: 'Wrong value (struck) · key value beside it' },
        { tone: 'trap', label: 'Document line that corrects, cancels or changes something' },
        { tone: 'bad', label: 'Document line holding the model’s wrong value' },
      ]}
    >
      {wrong.length > 0 && mode === 'inspector' && (
        <div className="vz-json-wrong">
          <span className="vz-card-k">Wrong fields</span>
          {wrong.map((l) => (
            <button key={l.path} type="button" className={cx('vz-chip bad', focus === l && 'on')} onClick={() => setFocus(l)}>
              {l.path}
            </button>
          ))}
        </div>
      )}
      <div className={cx('vz-json', mode === 'slide' && 'slide')}>
        <div className="vz-json-tree" role="table" aria-label="Expected vs model JSON">
          <div className="vz-json-h" role="row">
            <span role="columnheader">Field</span>
            <span role="columnheader">Answer key</span>
            <span role="columnheader">Model</span>
          </div>
          {mode === 'slide' && wrong.length === 0 && <div className="vz-missing">Every field matches.</div>}
          {nodes.map((n, i) =>
            n.leaf ? (
              <div
                key={i}
                role="row"
                className={cx('vz-json-row', n.leaf.passed ? 'good' : 'bad', focus === n.leaf && 'focus')}
                style={{ ['--d' as string]: mode === 'slide' ? 0 : n.depth } as CSSProperties}
                onMouseEnter={() => setFocus(n.leaf)}
                onFocus={() => setFocus(n.leaf)}
                tabIndex={0}
              >
                <span className="k" role="cell">
                  {mode === 'slide' ? n.leaf.path : n.key}
                </span>
                <span className="e" role="cell">
                  {show(n.leaf.expected)}
                </span>
                <span className="m" role="cell">
                  {n.leaf.passed ? <span>{n.leaf.got}</span> : <s>{n.leaf.got}</s>}
                  <ToneMark tone={n.leaf.passed ? 'good' : 'bad'} />
                </span>
              </div>
            ) : (
              <div key={i} role="row" className={cx('vz-json-sec', n.lengthOk === false && 'bad')} style={{ ['--d' as string]: n.depth } as CSSProperties}>
                <span role="cell">{n.header}</span>
                {n.lengthOk === false && <small role="cell">wrong number of items{n.lengthDetail ? ` (${n.lengthDetail})` : ''}</small>}
              </div>
            ),
          )}
        </div>
        {v.document.length > 0 && (
          <div className="vz-doc">
            <div className="mini-title">{excerpt ? 'Key lines of the source document' : `Source document${focus ? ` · ${focus.path}` : ''}`}</div>
            <ol ref={docRef}>
              {docLines.map((i, k) => {
                const line = v.document[i]!;
                const gap = excerpt && k > 0 && docLines[k - 1]! < i - 1;
                return (
                  <li
                    key={i}
                    data-line={i}
                    className={cx(traps.has(i) && 'trap', wrongLines.has(i) && 'wrongsrc', target === i && 'focus', focus?.sourceLine === i && 'src', gap && 'gap')}
                  >
                    <i className="tnum">{i + 1}</i>
                    <span>{line || ' '}</span>
                    {traps.has(i) && <em className="vz-tag trap">trap</em>}
                    {wrongLines.has(i) && <em className="vz-tag bad">model’s value</em>}
                  </li>
                );
              })}
            </ol>
          </div>
        )}
      </div>
    </VizFrame>
  );
}
