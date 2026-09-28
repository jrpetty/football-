/**
 * The brief's checklist next to a painting: every required element and every rule, with the judges' panel
 * verdict (tick, half, cross) and each judge's one-line reason. A small legend says what the marks mean.
 */
import type { GalleryDetail, GalleryItem } from './galleryModel.ts';
import './gallery.css';

const cx = (...p: Array<string | false | null | undefined>) => p.filter(Boolean).join(' ');

export type Mark = 'yes' | 'partly' | 'no' | 'none';

export function markOf(i: Pick<GalleryItem, 'consensus'>): Mark {
  if (i.consensus === null) return 'none';
  if (i.consensus >= 0.75) return 'yes';
  if (i.consensus >= 0.25) return 'partly';
  return 'no';
}

export function MarkGlyph({ mark, size = 20 }: { mark: Mark; size?: number }) {
  const label = mark === 'yes' ? 'yes' : mark === 'partly' ? 'partly' : mark === 'no' ? 'no' : 'no verdict';
  return (
    <svg className={cx('bc-mark', `is-${mark}`)} width={size} height={size} viewBox="0 0 20 20" role="img" aria-label={label}>
      <circle cx="10" cy="10" r="9" />
      {mark === 'yes' && <path d="m5.8 10.3 2.8 2.8 5.6-6" />}
      {mark === 'partly' && <path d="M10 1a9 9 0 0 1 0 18z" className="bc-half" />}
      {mark === 'no' && <path d="m6.5 6.5 7 7m0-7-7 7" />}
      {mark === 'none' && <path d="M6.5 10h7" />}
    </svg>
  );
}

export function ChecklistLegend() {
  return (
    <div className="bc-legend" aria-label="What the marks mean">
      <span>
        <MarkGlyph mark="yes" size={16} /> clearly there
      </span>
      <span>
        <MarkGlyph mark="partly" size={16} /> partly
      </span>
      <span>
        <MarkGlyph mark="no" size={16} /> missing or broken
      </span>
    </div>
  );
}

export function BriefChecklist({ detail, names, reasons = true, compact, title = 'The brief, line by line', rulesSummary }: { detail: GalleryDetail; names?: (id: string) => string; reasons?: boolean; compact?: boolean; title?: string; /** Show the rules as one line ("Rules respected 3/3") instead of listing them. */ rulesSummary?: boolean }) {
  const name = (id: string) => (names ? names(id) : id.replace(/@judge$/, ''));
  const rules = detail.items.filter((i) => i.kind === 'avoid');
  const groups: Array<{ label: string; items: GalleryItem[] }> = [
    { label: 'Required elements', items: detail.items.filter((i) => i.kind === 'element') },
    ...(rulesSummary ? [] : [{ label: 'Rules (must not appear)', items: rules }]),
  ];
  const judged = detail.items.some((i) => i.verdicts.length > 0);
  return (
    <section className={cx('brief-checklist', compact && 'is-compact')} aria-label="Brief checklist">
      <header className="bc-head">
        <span className="bc-title">{title}</span>
        <span className="bc-count tnum">
          {judged ? (
            <>
              <b>{detail.followed}</b>/{detail.total} followed
            </>
          ) : (
            'not judged'
          )}
        </span>
      </header>
      {groups.map((g) => (
        <div key={g.label} className="bc-group">
          <div className="bc-group-label">{g.label}</div>
          <ul>
            {g.items.map((i) => {
              const mark = markOf(i);
              return (
                <li key={i.id} className={cx('bc-item', `is-${mark}`, i.split && 'is-split')}>
                  <MarkGlyph mark={mark} />
                  <div className="bc-body">
                    <div className="bc-text">{i.text}</div>
                    {reasons && i.verdicts.length > 0 && (
                      <ul className="bc-reasons">
                        {i.verdicts.map((v) => (
                          <li key={v.judgeId}>
                            <span className={cx('bc-v', `is-${v.verdict}`)}>{v.verdict}</span>
                            <span className="bc-judge">{name(v.judgeId)}:</span> {v.reason || <em className="bc-none">no reason given</em>}
                          </li>
                        ))}
                      </ul>
                    )}
                    {i.split && <div className="bc-splitnote">The judges disagree on this line.</div>}
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      ))}
      {rulesSummary && (
        <div className="bc-rules-line">
          <span className="bc-group-label">Rules</span>
          {rules.map((r) => (
            <span key={r.id} title={r.text}>
              <MarkGlyph mark={markOf(r)} size={18} /> {r.id === 'N1' ? 'no text' : r.id === 'N2' ? 'no drawn frame' : r.text.split(/[:,.]/)[0]!.replace(/^No /, 'no ').replace(/^Nothing /, 'nothing ').slice(0, 40)}
            </span>
          ))}
        </div>
      )}
      <ChecklistLegend />
    </section>
  );
}

/** Small marks drawn over the painting's corner: one per checklist line, for a quick read on video. */
export function ChecklistRibbon({ detail }: { detail: GalleryDetail }) {
  return (
    <div className="bc-ribbon" aria-label={`Brief followed ${detail.followed} of ${detail.total}`}>
      {detail.items.map((i) => (
        <span key={i.id} title={`${i.id}: ${i.text}`}>
          <MarkGlyph mark={markOf(i)} size={18} />
        </span>
      ))}
    </div>
  );
}
