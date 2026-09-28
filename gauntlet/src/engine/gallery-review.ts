/**
 * Human scores on Gallery paintings (Blind Review and blind votes).
 *
 *  - Ratings from a named rater (the owner, in Blind Review) are ARTISTRY ratings: their mean replaces the
 *    judges' artistry, and brief adherence stays as judged. Score = 0.5 × adherence + 0.5 × artistry ÷ 10. When
 *    no judge answered, the owner's rating is the whole score.
 *  - Blind votes (rater "Blind vote") record the audience's share of votes. They are stored as human scores and
 *    shown ("People's choice"), but never change the score.
 */
import { mean } from '../core/stats.ts';
import type { CaseResult } from '../core/types.ts';
import { galleryScore, type GalleryDetail } from '../programs/lib/gallery-judge.ts';

export const BLIND_VOTE_RATER = 'Blind vote';

export function isGalleryResult(r: Pick<CaseResult, 'scoreDetail'>): boolean {
  const g = r.scoreDetail?.gallery as GalleryDetail | undefined;
  return !!g && typeof g === 'object' && g.version === 1;
}

const isVote = (rater: string) => rater === BLIND_VOTE_RATER || rater.startsWith(`${BLIND_VOTE_RATER} `);

/** Recompute a Gallery result after its human scores changed. */
export function applyGalleryHumanScores(r: CaseResult): CaseResult {
  const g = r.scoreDetail.gallery as GalleryDetail;
  const all = r.humanScores ?? [];
  const owners = all.filter((h) => !isVote(h.rater));
  const vote = all.find((h) => isVote(h.rater));
  const voteInfo = vote ? parseVoteNote(vote.note, vote.score) : undefined;
  const judgeArtistry = g.owner ? g.owner.judgeArtistry : g.artistry;
  const next: GalleryDetail = { ...g, ...(voteInfo ? { votes: voteInfo } : {}) };
  if (!owners.length) {
    const { owner: _o, ...rest } = next;
    const restored: GalleryDetail = { ...rest, artistry: judgeArtistry };
    return { ...r, scoreDetail: { ...r.scoreDetail, gallery: restored } };
  }
  const artistry = Math.round(mean(owners.map((h) => h.score))! * 1000) / 100;
  next.owner = { artistry, raters: owners.map((h) => h.rater), judgeArtistry };
  next.artistry = artistry;
  const automated = (r.scoreDetail.automatedScore as number | null | undefined) ?? (r.status === 'pending-human' ? null : r.score);
  const score = galleryScore(g.adherence, artistry)!;
  const followed = g.total ? ` · Brief followed ${g.followed}/${g.total}` : '';
  return {
    ...r,
    status: 'ok',
    score,
    passed: score >= 0.7,
    summary: `Artistry ${artistry.toFixed(1)}/10 (owner)${g.adherence === null ? ' · no judge checklist' : followed}`,
    scoreDetail: { ...r.scoreDetail, gallery: next, automatedScore: automated, humanScored: true },
  };
}

/** "7 of 20 votes" → { votes: 7, of: 20, share }. */
function parseVoteNote(note: string | undefined, score: number): GalleryDetail['votes'] {
  const m = note?.match(/(\d+)\s+of\s+(\d+)/);
  const votes = m ? Number(m[1]) : 0;
  const of = m ? Number(m[2]) : 0;
  return { votes, of, share: of ? Math.round((votes / of) * 1000) / 1000 : Math.round(score * 1000) / 1000 };
}
