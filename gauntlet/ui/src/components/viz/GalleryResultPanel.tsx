/**
 * Result inspector for The Gallery Masterpiece: the painting on a museum wall with its placard, the brief's
 * checklist (tick / half / cross, with each judge's reason) and the artistry breakdown with the judges' spread.
 */
import { useState } from 'react';
import { artifactUrl } from '../../api.ts';
import type { CaseResult } from '../../types.ts';
import { GalleryFrame } from './GalleryFrame.tsx';
import { MuseumPlacard } from './MuseumPlacard.tsx';
import { BriefChecklist, ChecklistRibbon } from './BriefChecklist.tsx';
import { ArtistryBreakdown } from './ArtistryBreakdown.tsx';
import { briefForCase, frameFor, galleryOf, mastersOf, paintingArtifact, stateOf, svgSourceArtifact } from './galleryModel.ts';
import './gallery.css';

export function GalleryResultPanel({ res, modelLabel, modelColor, names, manual }: { res: CaseResult; modelLabel?: string; modelColor?: string; names?: Map<string, string>; manual?: boolean }) {
  const [ribbon, setRibbon] = useState(true);
  const g = galleryOf(res);
  const brief = briefForCase(res.caseId);
  if (!brief) return null;
  const art = paintingArtifact(res);
  const svg = svgSourceArtifact(res);
  const url = art ? artifactUrl(res.runId, art.file) : null;
  const state = stateOf(res, g);
  const mode = g?.mode ?? (res.testId.includes('painted-in-code') ? 'code' : 'image');
  const label = modelLabel ?? res.contestantId;
  const judgeName = (id: string) => names?.get(id) ?? names?.get(id.replace(/@judge$/, '')) ?? id.replace(/@judge$/, '');
  const baseline = /(^|[-_.])(random|baseline)([-_.]|$)/i.test(res.contestantId);
  const judged = !!g && g.artistry !== null && state === 'painting';
  return (
    <section className="gal-room gal-result" aria-label="The Gallery">
      <div className="gal-headline" style={{ marginBottom: 26 }}>
        <div className="gh-eyebrow">
          Commission No. {brief.n} · {mode === 'code' ? 'Painted in code' : 'The Gallery Masterpiece'}
        </div>
        <div className="gh-title">{brief.title}</div>
        <div className="gh-sub">
          {brief.medium}
          {mastersOf(brief.style) ? `, in the manner of ${mastersOf(brief.style)}` : ''}
        </div>
      </div>
      <div className="gal-result-grid">
        <div className="gal-result-left">
          <GalleryFrame
            src={url}
            alt={`${brief.title}, painted by ${label}`}
            width={g?.painting?.width}
            height={g?.painting?.height}
            frame={frameFor(brief.n)}
            state={state}
            mode={mode}
            size="lg"
            overlay={ribbon && judged ? <ChecklistRibbon detail={g!} /> : undefined}
          />
          <MuseumPlacard
            title={brief.title}
            artist={label}
            color={modelColor}
            medium={brief.medium}
            style={brief.style}
            detail={g}
            state={state}
            score={res.score}
            costUsd={res.metrics?.costUsd ?? null}
            manual={manual}
            baseline={baseline}
            mode={mode}
            size="lg"
          />
          <div className="gal-toolbar no-broadcast">
            {judged && (
              <button type="button" className="gal-toggle" aria-pressed={ribbon} onClick={() => setRibbon((x) => !x)}>
                {ribbon ? 'Hide' : 'Show'} the ticks on the painting
              </button>
            )}
            {url && (
              <a className="gal-toggle" href={url} target="_blank" rel="noreferrer noopener">
                Open full size
              </a>
            )}
            {svg && (
              <a className="gal-toggle" href={artifactUrl(res.runId, svg.file)} target="_blank" rel="noreferrer noopener">
                Open the SVG code
              </a>
            )}
          </div>
          {g && (g.artistry !== null || g.judges.length > 0) && <ArtistryBreakdown detail={g} names={judgeName} />}
        </div>
        <div className="gal-result-right">
          {g && g.items.length > 0 && judged ? (
            <BriefChecklist detail={g} names={judgeName} />
          ) : (
            <div className="brief-checklist">
              <div className="bc-head">
                <span className="bc-title">The brief, line by line</span>
              </div>
              <p className="gal-muted" style={{ margin: 0 }}>
                {state === 'no-output'
                  ? 'Not asked: this model has no image output. Chat apps can take part as a Manual (copy & paste) contestant.'
                  : state === 'refused' || state === 'no-picture'
                    ? 'No painting, so nothing to check. This commission scores 0.'
                    : state === 'awaiting'
                      ? 'Fewer than two judges gave a valid verdict. Rate this painting in Blind Review: your rating becomes its score.'
                      : 'Not recorded for this result.'}
              </p>
              <ul className="bc-reasons" style={{ marginTop: 10 }}>
                {brief.elements.map((e) => (
                  <li key={e.id}>
                    <b>{e.id}</b> {e.text}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {g?.votes && g.votes.of > 0 && (
            <p className="gal-muted">
              Blind vote: {g.votes.votes} of {g.votes.of} votes ({Math.round(g.votes.share * 100)}%). Votes are recorded, not scored.
            </p>
          )}
        </div>
      </div>
    </section>
  );
}
