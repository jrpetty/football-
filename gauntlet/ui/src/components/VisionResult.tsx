/** Result inspector panel for vision cases: the image the model saw, next to its answer and the answer key. */
import type { CaseResult, ChatImage, ScoreDetail } from '../types.ts';
import { prettyJson } from '../format.ts';
import { cx } from './ui.tsx';
import { Icon } from './icons.tsx';
import { VisionImage } from './VisionImage.tsx';
import { testImageUrl } from '../vision.ts';
import { VisionAnswerOverlay, overlayPlan, visionLayout } from './viz/VisionAnswerOverlay.tsx';

/** Images sent to the contestant in a result (from its transcript; judge calls excluded). */
export function resultImages(r: Pick<CaseResult, 'transcript'>): ChatImage[] {
  const seen = new Set<string>();
  const out: ChatImage[] = [];
  for (const e of r.transcript ?? []) {
    if (e.judge) continue;
    for (const m of e.messages ?? [])
      for (const img of m.images ?? []) {
        const k = img.path ?? img.sha256 ?? img.name;
        if (seen.has(k)) continue;
        seen.add(k);
        out.push(img);
      }
  }
  return out;
}

function show(v: unknown): string {
  if (v === undefined || v === null || v === '') return '—';
  if (typeof v === 'string') return v;
  if (Array.isArray(v) && v.every((x) => typeof x === 'string')) return v.length > 1 ? `${v[0]}  (also accepted: ${v.slice(1).join(', ')})` : String(v[0]);
  return prettyJson(v);
}

export function VisionResultPanel({ images, detail, passed }: { images: ChatImage[]; detail: ScoreDetail; passed: boolean | null }) {
  if (!images.length) return null;
  // Pictures with known geometry get the answer drawn on top (viz/VisionAnswerOverlay.tsx).
  const layout = images.length === 1 ? visionLayout(images[0]) : null;
  if (layout && overlayPlan(layout, detail, passed)) return <VisionAnswerOverlay image={images[0]!} layout={layout} detail={detail} passed={passed} />;
  return (
    <div className="vi-inspect">
      <div className="stack tight">
        {images.map((img, i) => (
          <VisionImage key={i} src={img.path ? testImageUrl(img.path) : ''} name={img.name} width={img.width} height={img.height} size="fill" caption="The picture this model was shown. Click to enlarge." />
        ))}
      </div>
      <div className="vi-inspect-side">
        <div className={cx('vi-answer', passed === true ? 'good' : passed === false ? 'bad' : '')}>
          <div className="k">
            {passed === true ? <Icon.Check style={{ width: 12, height: 12, display: 'inline', verticalAlign: '-1px' }} /> : null} Model’s answer
          </div>
          <div className="v">{show(detail.extracted)}</div>
          {detail.formatOk === false && <div className="warn-text" style={{ fontSize: '0.8rem', marginTop: 6 }}>⚠ Answer format not followed (fallback extraction used)</div>}
        </div>
        <div className="vi-answer">
          <div className="k">
            <Icon.Key style={{ width: 12, height: 12, display: 'inline', verticalAlign: '-1px' }} /> Answer key
          </div>
          <div className="v">{show(detail.expected)}</div>
        </div>
      </div>
    </div>
  );
}
