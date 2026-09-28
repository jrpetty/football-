/**
 * Presenter slides for The Gallery: one gallery wall per commission (every model's painting, framed, with
 * its placard) and the "Masterpiece of the Show" slide for the best painting of the whole test.
 */
import { useMemo } from 'react';
import type { CaseResultLite, ContestantSnapshot } from '../../types.ts';
import { GalleryWall } from '../viz/GalleryWall.tsx';
import { GalleryFrame, GalleryRosette } from '../viz/GalleryFrame.tsx';
import { BriefChecklist } from '../viz/BriefChecklist.tsx';
import { bestOf, briefForCase, briefsIn, fmtArtistry, fmtPaintCost, frameFor, mastersOf, wallFor, type WallEntry } from '../viz/galleryModel.ts';
import '../viz/gallery.css';
import './gallery-slide.css';

export interface GallerySlideData {
  runId: string;
  testId: string;
  results: CaseResultLite[];
  contestants: ContestantSnapshot[];
  manualProviders: Set<string>;
}

const modeOf = (testId: string) => (testId.includes('painted-in-code') ? 'code' : 'image');

export function galleryCaseIds(results: CaseResultLite[], testId: string, caseIds: string[]): string[] {
  return briefsIn(results, testId, caseIds).map((b) => b.caseId);
}

export function GalleryBriefSlide({ data, caseId, n, of }: { data: GallerySlideData; caseId: string; n: number; of: number }) {
  const brief = briefForCase(caseId);
  const entries = useMemo(() => wallFor({ ...data, caseId }), [data, caseId]);
  if (!brief) return null;
  const mode = modeOf(data.testId);
  const masters = mastersOf(brief.style);
  return (
    <div className="gal-room s-gallery">
      <header className="sg-head">
        <div className="sg-eyebrow">
          Commission {n} of {of} · {mode === 'code' ? 'painted in SVG code' : 'painted by image generators'}
        </div>
        <h2 className="sg-title">{brief.title}</h2>
        <div className="sg-medium">
          {brief.medium}
          {masters ? `, in the manner of ${masters}` : ''}
        </div>
        <ol className="sg-elements">
          {brief.elements.map((e) => (
            <li key={e.id}>{e.text}</li>
          ))}
        </ol>
      </header>
      <div className="sg-wall">{entries.length ? <GalleryWall brief={brief} entries={entries} mode={mode} variant="slide" ribbons /> : <p className="sg-none">No paintings recorded for this commission.</p>}</div>
    </div>
  );
}

export function masterpieceEntry(data: GallerySlideData, caseIds: string[]): { entry: WallEntry; caseId: string } | null {
  let best: { entry: WallEntry; caseId: string } | null = null;
  for (const caseId of galleryCaseIds(data.results, data.testId, caseIds)) {
    const e = bestOf(wallFor({ ...data, caseId }));
    if (e && (!best || e.score! > best.entry.score! || (e.score === best.entry.score && (e.detail?.artistry ?? 0) > (best.entry.detail?.artistry ?? 0)))) best = { entry: e, caseId };
  }
  return best;
}

export function GalleryWinnerSlide({ data, caseIds, names }: { data: GallerySlideData; caseIds: string[]; names: (id: string) => string }) {
  const best = useMemo(() => masterpieceEntry(data, caseIds), [data, caseIds]);
  const mode = modeOf(data.testId);
  if (!best) return <div className="gal-room s-gallery sg-empty">No painting was judged in this test, so there is no Masterpiece of the Show.</div>;
  const brief = briefForCase(best.caseId)!;
  const e = best.entry;
  const d = e.detail!;
  return (
    <div className="gal-room s-gallery sg-winner">
      <div className="sgw-art">
        <GalleryFrame src={e.url} alt={`${brief.title}, painted by ${e.label}`} width={e.width} height={e.height} frame={frameFor(brief.n)} size="lg" badge={<GalleryRosette label="Masterpiece of the Show" size={72} />} />
      </div>
      <div className="sgw-text">
        <div className="sg-eyebrow">Masterpiece of the Show{mode === 'code' ? ' · painted in code' : ''}</div>
        <h2 className="sg-title">{brief.title}</h2>
        <div className="sgw-by">
          <span className="gp-dot" style={{ background: e.color }} />
          by <b>{e.label}</b>
        </div>
        <div className="sg-medium">
          Commission No. {brief.n} · {brief.medium}
        </div>
        <div className="sgw-nums">
          <span>
            <small>Brief followed</small>
            <b className="tnum">
              {d.followed}/{d.total}
            </b>
          </span>
          <span>
            <small>Artistry{d.owner ? ' (owner)' : ''}</small>
            <b className="tnum">
              {fmtArtistry(d.artistry)}
              <i>/10</i>
            </b>
          </span>
          <span>
            <small>Score</small>
            <b className="tnum">{Math.round((e.score ?? 0) * 100)}</b>
          </span>
          <span>
            <small>{mode === 'code' ? 'Cost to paint' : 'Cost per image'}</small>
            <b className="tnum">{fmtPaintCost(e.costUsd, e.manual)}</b>
          </span>
        </div>
        <BriefChecklist detail={d} names={names} reasons={false} compact title="The brief" rulesSummary />
      </div>
    </div>
  );
}
