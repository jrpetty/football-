/** Presenter test-intro card for vision tests: the picture the models are shown, with the question under it. */
import type { TestDetail, TestSnapshot } from '../types.ts';
import { testImageUrl } from '../vision.ts';
import { ImageGlyph } from './VisionImage.tsx';

/** The first case of the run (in test order) that shows the model an image, if any. */
export function examplePicture(detail: TestDetail | null, snap: TestSnapshot): { url: string; name: string; question: string; total: number } | null {
  const rendered = (detail?.rendered ?? []).filter((r) => snap.caseIds.includes(r.caseId) && (r.images?.length ?? 0) > 0 && r.images![0]!.path);
  const first = rendered[0];
  if (!first) return null;
  const img = first.images![0]!;
  const def = detail?.definition;
  const c = def?.kind === 'prompt' ? def.cases.find((x) => x.id === first.caseId) : undefined;
  // The question without the answer-format boilerplate (first paragraph of the raw prompt).
  const raw = (c?.prompt ?? c?.turns?.[0] ?? first.turns[0] ?? '').split(/\n\s*\n/)[0]!.trim();
  return { url: testImageUrl(img.path!), name: img.file.split('/').pop() ?? img.file, question: raw, total: rendered.length };
}

export function VisionExplainerCard({ pic }: { pic: NonNullable<ReturnType<typeof examplePicture>> }) {
  return (
    <div className="x-card x-vision">
      <div className="x-card-k">
        <ImageGlyph /> What the models are shown
      </div>
      <div className="x-vision-frame">
        <img src={pic.url} alt={`Example test image ${pic.name}`} />
      </div>
      <p className="x-vision-q">{pic.question}</p>
      <div className="x-format">{pic.total > 1 ? `One of ${pic.total} pictures. Every model gets the same image and the same question.` : 'Every model gets the same image and the same question.'}</div>
    </div>
  );
}
