/**
 * Artistry, honestly: the six criteria (median across judges, 1–10), every judge's own score as a dot so the
 * spread is visible, and a plain note that artistry is a judgement, not a measurement.
 */
import { fmtArtistry, type GalleryDetail } from './galleryModel.ts';
import './gallery.css';

const cx = (...p: Array<string | false | null | undefined>) => p.filter(Boolean).join(' ');
const pct = (x: number) => `${Math.max(0, Math.min(100, ((x - 1) / 9) * 100)).toFixed(1)}%`;

export function ArtistryBreakdown({ detail, names, note = true }: { detail: GalleryDetail; names?: (id: string) => string; note?: boolean }) {
  const name = (id: string) => (names ? names(id) : id.replace(/@judge$/, ''));
  const judges = detail.judges;
  const ok = judges.filter((j) => j.artistry !== null);
  const owner = detail.owner;
  return (
    <section className="artistry" aria-label="Artistry">
      <header className="ar-head">
        <span className="ar-title">Artistry</span>
        <span className="ar-score tnum">
          <b>{fmtArtistry(detail.artistry)}</b>/10
        </span>
        <span className="ar-sub">
          {owner ? `your rating (the judges said ${fmtArtistry(owner.judgeArtistry)})` : ok.length > 1 ? `median of ${ok.length} judges` : ok.length === 1 ? 'one judge only' : 'not judged'}
        </span>
      </header>
      <div className="ar-rows" role="table" aria-label="Artistry criteria, 1 to 10">
        {detail.criteria.map((c) => (
          <div key={c.id} className="ar-row" role="row">
            <span className="ar-label" role="rowheader">
              {c.label}
            </span>
            <span className="ar-track" role="cell" aria-label={`${c.label}: ${fmtArtistry(c.median)} out of 10`}>
              {c.median !== null && <span className="ar-fill" style={{ width: pct(c.median) }} />}
              {c.scores.map((s, k) => (
                <span key={s.judgeId} className={cx('ar-dot', `j${k % 4}`)} style={{ left: pct(s.score) }} title={`${name(s.judgeId)}: ${s.score}/10${s.reason ? ` — ${s.reason}` : ''}`} />
              ))}
            </span>
            <b className="ar-val tnum" role="cell">
              {fmtArtistry(c.median)}
            </b>
          </div>
        ))}
        <div className="ar-row ar-scale-row" aria-hidden="true">
          <span />
          <span className="ar-scale">
            {([
              [1, 'broken'],
              [5, 'competent'],
              [8, 'accomplished'],
              [10, 'masterpiece'],
            ] as const).map(([v, t]) => (
              <span key={v} style={{ left: pct(v) }} className={cx(v === 1 && 'first', v === 10 && 'last')}>
                <b title={t}>{v}</b>
              </span>
            ))}
          </span>
          <span />
        </div>
        <div className="ar-scale-key">Scale: 1 broken · 5 competent · 8 accomplished · 10 museum masterpiece · dots = each judge</div>
      </div>
      {judges.length > 0 && (
        <ul className="ar-judges">
          {judges.map((j, k) => (
            <li key={j.judgeId} className={cx(j.error && 'is-error')}>
              <span className={cx('ar-dot static', `j${k % 4}`)} aria-hidden="true" />
              <b>{name(j.judgeId)}</b>
              {j.error ? (
                <span className="ar-err">no valid verdict ({j.error.slice(0, 90)})</span>
              ) : (
                <>
                  <span className="tnum">artistry {fmtArtistry(j.artistry)}</span>
                  <span className="tnum">brief {j.adherence === null ? '—' : `${Math.round(j.adherence * 100)}%`}</span>
                  {j.summary && <span className="ar-quote">“{j.summary}”</span>}
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {detail.spread !== null && (
        <p className={cx('ar-spread', detail.spread >= 2 && 'is-wide')}>
          {detail.spread >= 2
            ? `The judges differ by ${detail.spread.toFixed(1)} points on artistry: treat this score with care, and have a look yourself.`
            : `The judges agree within ${detail.spread.toFixed(1)} points on artistry.`}
        </p>
      )}
      {note && (
        <p className="ar-note">
          Artistry is a judgement, not a measurement. Each judge scores six criteria on the same written scale; the median keeps one generous or harsh judge from deciding the result. You can replace it with your own rating in Blind Review.
        </p>
      )}
    </section>
  );
}
