/**
 * The museum label under a painting: the commission's title, "Artist: <model>", the medium, and the two
 * judged numbers (brief followed, artistry) with the cost of the picture. Only recorded values are shown;
 * anything missing reads "not recorded" or is left out.
 */
import { fmtArtistry, fmtPaintCost, spreadNote, styleShort, type GalleryDetail, type WallState } from './galleryModel.ts';
import './gallery.css';

const cx = (...p: Array<string | false | null | undefined>) => p.filter(Boolean).join(' ');

export function MuseumPlacard({
  title,
  artist,
  color,
  medium,
  style,
  detail,
  state,
  score,
  costUsd,
  manual,
  baseline,
  mode = 'image',
  size = 'md',
  anonymous,
  className,
}: {
  title: string;
  artist: string;
  color?: string;
  medium: string;
  style: string;
  detail: GalleryDetail | null;
  state: WallState;
  score: number | null;
  costUsd: number | null;
  manual?: boolean;
  baseline?: boolean;
  mode?: 'image' | 'code';
  size?: 'sm' | 'md' | 'lg';
  /** Blind vote: hide the artist. */
  anonymous?: boolean;
  className?: string;
}) {
  const judged = state === 'painting' && detail && detail.artistry !== null;
  const spread = spreadNote(detail);
  const owner = !!detail?.owner;
  const costLabel = mode === 'code' ? 'to paint' : 'per image';
  return (
    <div className={cx('gal-placard', `gal-placard-${size}`, className)}>
      <div className="gp-title">{title}</div>
      <div className="gp-artist">
        {anonymous ? (
          <span>Artist: hidden until the vote</span>
        ) : (
          <>
            <span className="gp-dot" style={{ background: color }} aria-hidden="true" />
            <span>
              Artist: <b>{artist}</b>
              {manual && <span className="gp-tag"> · made in a chat app</span>}
              {baseline && <span className="gp-tag"> · random floor</span>}
            </span>
          </>
        )}
      </div>
      <div className="gp-medium">
        {mode === 'code' ? 'SVG code, rendered' : medium} · {styleShort(style)}
      </div>
      {!anonymous && (
        <div className="gp-facts">
          {judged ? (
            <>
              <span className="gp-fact">
                <span className="gp-k">Brief followed</span>
                <b className="tnum">
                  {detail!.followed}/{detail!.total}
                </b>
              </span>
              <span className="gp-fact">
                <span className="gp-k">Artistry{owner ? ' · owner' : ''}</span>
                <b className="tnum">
                  {fmtArtistry(detail!.artistry)}
                  <small>/10</small>
                </b>
              </span>
            </>
          ) : (
            <span className="gp-fact gp-wide">
              <span className="gp-k">{state === 'no-output' ? 'Not scored' : state === 'awaiting' ? 'Awaiting judges' : state === 'painting' ? 'Not judged yet' : 'Score'}</span>
              <b className="tnum">{state === 'refused' || state === 'no-picture' ? '0' : '—'}</b>
            </span>
          )}
          <span className="gp-fact gp-cost">
            <span className="gp-k">Cost {costLabel}</span>
            <b className="tnum">{baseline ? '$0' : fmtPaintCost(costUsd, manual)}</b>
          </span>
        </div>
      )}
      {!anonymous && size !== 'sm' && judged && score !== null && (
        <div className="gp-foot">
          Score <b className="tnum">{Math.round(score * 100)}</b>/100
          {spread && <span className="gp-spread"> · judges differ: artistry {spread}</span>}
        </div>
      )}
      {!anonymous && size === 'sm' && spread && <div className="gp-foot gp-spread">Judges differ: {spread}</div>}
    </div>
  );
}
