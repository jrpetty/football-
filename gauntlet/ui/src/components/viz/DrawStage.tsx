/**
 * Replay stage for Draw It Blind (standard and hard).
 *  Step 1 (describe): the original with numbered shapes next to the model's own
 *    description; each phrase is linked to the shape it describes (hover either
 *    side), with the word-limit meter and any deleted numbers.
 *  Step 2 (draw): original vs redrawing, side by side / onion skin / difference.
 *  Then one step per shape: its matching line, score and score breakdown.
 */
import { useMemo, useState } from 'react';
import type { ReplayData } from '../../types.ts';
import { cx } from '../ui.tsx';
import { DrawCompare, svgUrl } from './DrawCompare.tsx';
import { ScoreBars, VizHeadline, WordMeter } from './VizHeadline.tsx';
import { VizLegend } from './VizLegend.tsx';
import type { DrawModel, DrawPair, PhraseLink } from './vizModel.ts';
import { drawModel, pairTone, phraseLinks } from './vizModel.ts';
import './viz.css';

export function useDrawModel(replay: ReplayData, detail: unknown): DrawModel | null {
  return useMemo(() => drawModel(detail, replay), [replay, detail]);
}

const shapeName = (s: { color: string; type: string }) => `${s.color} ${s.type}`;

/** The description with each linked phrase highlighted; hovering a phrase highlights its shape. */
export function LinkedDescription({ text, links, hover, onHover, violations }: { text: string; links: PhraseLink[]; hover: number | null; onHover: (i: number | null) => void; violations: string[] }) {
  const parts: Array<{ t: string; link?: PhraseLink }> = [];
  let at = 0;
  for (const l of links) {
    if (l.start < at) continue;
    if (l.start > at) parts.push({ t: text.slice(at, l.start) });
    parts.push({ t: text.slice(l.start, l.end), link: l });
    at = l.end;
  }
  if (at < text.length) parts.push({ t: text.slice(at) });
  // Numbers the rules delete are struck through (they never reached the drawer).
  const strike = (s: string, key: string) => {
    if (!violations.length) return s;
    const re = new RegExp(`(${violations.map((v) => v.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`, 'g');
    return s.split(re).map((p, i) => (i % 2 ? <del key={`${key}-${i}`} title="Deleted before the drawing step: digits and numbers above ten are not allowed">{p}</del> : p));
  };
  return (
    <p className="vz-desc">
      {parts.map((p, i) =>
        p.link ? (
          <mark key={i} className={cx('vz-link', hover === p.link.shape && 'on')} onMouseEnter={() => onHover(p.link!.shape)} onMouseLeave={() => onHover(null)} tabIndex={0} onFocus={() => onHover(p.link!.shape)} onBlur={() => onHover(null)}>
            <sup>{p.link.shape + 1}</sup>
            {strike(p.t, `l${i}`)}
          </mark>
        ) : (
          <span key={i}>{strike(p.t, `s${i}`)}</span>
        ),
      )}
    </p>
  );
}

function NumberedOriginal({ model, hover, onHover }: { model: DrawModel; hover: number | null; onHover: (i: number | null) => void }) {
  const C = model.canvas;
  if (!model.targetSvg) return <div className="vz-missing">The original picture was not recorded.</div>;
  return (
    <svg className="vz-draw-svg single" viewBox={`0 0 ${C} ${C}`} role="img" aria-label="The original picture with numbered shapes">
      <image href={svgUrl(model.targetSvg)} x={0} y={0} width={C} height={C} />
      <rect x={0} y={0} width={C} height={C} className="vz-draw-frame" />
      {model.shapes.map((s) => (
        <g key={s.i} className={cx('vz-shape-hit', hover === s.i && 'on', hover !== null && hover !== s.i && 'dim')} onMouseEnter={() => onHover(s.i)} onMouseLeave={() => onHover(null)}>
          <rect x={s.cx - s.w / 2 - 5} y={s.cy - s.h / 2 - 5} width={s.w + 10} height={s.h + 10} rx={8} />
          <g transform={`translate(${s.cx - s.w / 2 - 5} ${s.cy - s.h / 2 - 5})`} className="vz-num">
            <circle r={13} />
            <text y={5} textAnchor="middle">
              {s.i + 1}
            </text>
          </g>
        </g>
      ))}
    </svg>
  );
}

function pairSentence(p: DrawPair): string {
  if (!p.drawn) return `#${p.target.i + 1} ${shapeName(p.target)}: never drawn`;
  const same = p.drawn.color === p.target.color && (p.drawn.kind === p.target.type || (p.target.type === 'rectangle' && p.drawn.kind === 'rectangle'));
  const off = p.distance !== null ? `${Math.round(p.distance)} px off` : '';
  return same ? `#${p.target.i + 1} ${shapeName(p.target)}: redrawn ${off}` : `#${p.target.i + 1} ${shapeName(p.target)} came back as a ${p.drawn.color} ${p.drawn.kind}${off ? `, ${off}` : ''}`;
}

export function DrawStage({ model, idx, video }: { model: DrawModel; idx: number; video: boolean }) {
  const [hover, setHover] = useState<number | null>(null);
  const links = useMemo(() => phraseLinks(model.description, model.shapes), [model]);
  const step = idx === 0 ? 'describe' : idx === 1 ? 'draw' : 'shape';
  const pair = step === 'shape' ? model.pairs[idx - 2] : undefined;
  const pct = Math.round(model.match * 100);

  if (step === 'describe') {
    const linked = new Set(links.map((l) => l.shape)).size;
    const tone = !model.description ? 'bad' : model.violations.length || model.truncated ? 'warn' : 'good';
    return (
      <div className={cx('vz-stage vz-drawst', video && 'video')}>
        <VizHeadline
          eyebrow="Step 1 · Describe the picture in words (no digits)"
          title={
            !model.description
              ? 'No description: nothing to draw from'
              : `Described it in ${model.words} words${model.violations.length ? `, but used ${model.violations.length} forbidden number${model.violations.length === 1 ? '' : 's'} (deleted)` : model.truncated ? `: over the limit, the end was cut` : ''}`
          }
          tone={tone}
          big={`${linked}/${model.shapes.length}`}
          bigSub="shapes named"
        />
        <div className="vz-cols even">
          <div className="vz-col">
            <NumberedOriginal model={model} hover={hover} onHover={setHover} />
          </div>
          <div className="vz-col">
            <WordMeter words={model.words} limit={model.limit} label="Description words" />
            {model.description ? <LinkedDescription text={model.description} links={links} hover={hover} onHover={setHover} violations={model.violations} /> : <div className="vz-missing">The model gave no description.</div>}
            <VizLegend items={[{ kind: 'accent', label: 'Phrase that names a shape (hover to find it)' }, ...(model.violations.length ? [{ kind: 'bad' as const, label: 'Deleted number' }] : [])]} />
          </div>
        </div>
      </div>
    );
  }

  if (step === 'draw' || !pair) {
    return (
      <div className={cx('vz-stage vz-drawst', video && 'video')}>
        <VizHeadline
          eyebrow="Step 2 · Redraw it from nothing but its own words"
          title={`Rebuilt ${model.rebuilt} of ${model.shapes.length} shapes${model.extras.length ? ` and added ${model.extras.length} extra` : ''}`}
          tone={model.match >= 0.75 ? 'good' : model.match >= 0.5 ? 'warn' : 'bad'}
          big={`${pct}%`}
          bigSub="match"
        />
        <DrawCompare model={model} hoverShape={hover} onHover={setHover} />
        <VizLegend
          items={[
            { kind: 'good', label: '75%+ match' },
            { kind: 'warn', label: '50–75%' },
            { kind: 'bad', label: 'Under 50% / not drawn' },
            ...(model.extras.length ? [{ kind: 'missed' as const, label: 'Extra shape (costs points)' }] : []),
          ]}
        />
      </div>
    );
  }

  const tone = pairTone(pair.score);
  const link = links.find((l) => l.shape === pair.target.i);
  return (
    <div className={cx('vz-stage vz-drawst', video && 'video')}>
      <VizHeadline eyebrow={`Shape ${pair.target.i + 1} of ${model.shapes.length}`} title={pairSentence(pair)} tone={pair.drawn ? tone : 'bad'} big={`${Math.round(pair.score * 100)}%`} bigSub="this shape" />
      <div className="vz-cols wide-left">
        <div className="vz-col">
          <DrawCompare model={model} focus={pair.target.i} hoverShape={hover} onHover={setHover} />
        </div>
        <div className="vz-col narrow">
          <div className="vz-card">
            <div className="vz-card-k">How this shape scored</div>
            <ScoreBars
              rows={[
                { label: 'Shape type', value: pair.type, tone: pair.type >= 0.99 ? 'good' : pair.type > 0 ? 'warn' : 'bad' },
                { label: 'Colour', value: pair.color, tone: pair.color >= 0.99 ? 'good' : 'bad' },
                { label: 'Position', value: pair.position, tone: pairTone(pair.position), note: pair.distance !== null ? `${Math.round(pair.distance)} px from where it should be` : undefined },
                { label: 'Size', value: pair.size, tone: pairTone(pair.size) },
              ]}
            />
          </div>
          <div className="vz-card">
            <div className="vz-card-k">What its description said</div>
            {link ? <p className="vz-quote">“{model.description.slice(link.start, Math.min(model.description.length, model.description.indexOf('.', link.end) + 1 || link.end))}”</p> : <p className="muted">No phrase names a {shapeName(pair.target)}.</p>}
          </div>
        </div>
      </div>
    </div>
  );
}
