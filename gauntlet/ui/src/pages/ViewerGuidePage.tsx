/** Viewer guide: the words a viewer hears in a Gauntlet video, in plain English, with a drawing each. */
import { useEffect, useState } from 'react';
import { Link, navigate, useRoute } from '../router.tsx';
import { PageHead } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { useViewerCaption } from '../context.tsx';
import { useHotkeys } from '../hooks.ts';
import { GLOSSARY } from '../components/clarity/glossary.tsx';

export default function ViewerGuidePage({ show }: { show?: boolean }) {
  return show ? <GuideShow /> : <GuideGrid />;
}

function GuideGrid() {
  useViewerCaption('A viewer’s guide to the words used in this video, each explained in one or two plain sentences.', `${GLOSSARY.length} terms`);
  return (
    <div className="page">
      <PageHead
        eyebrow={
          <span className="row" style={{ gap: 8 }}>
            <Icon.Info style={{ width: 14, height: 14 }} /> Viewer guide
          </span>
        }
        title="What the words mean"
        sub="Every term you’ll hear in a Gauntlet video, explained without jargon. Show it on screen, or open the full-screen version to walk through one term at a time."
        actions={
          <Link to="/guide/show" className="btn primary">
            <Icon.Maximize /> Full screen
          </Link>
        }
      />
      <div className="guide-grid">
        {GLOSSARY.map((g, i) => (
          <article key={g.id} className="guide-card" id={`term-${g.id}`}>
            <Link to={`/guide/show?t=${i + 1}`} className="guide-art" aria-label={`Show “${g.term}” full screen`}>
              {g.art}
            </Link>
            <div>
              <h3>{g.term}</h3>
              <p className="g-say">{g.say}</p>
              {g.eg && <p className="g-eg">{g.eg}</p>}
            </div>
          </article>
        ))}
      </div>
    </div>
  );
}

/** One term per screen, big type, for recording. ← / → or Space to move, Esc to leave. */
function GuideShow() {
  const { query } = useRoute();
  const start = Math.min(GLOSSARY.length, Math.max(1, Number(query.get('t')) || 1)) - 1;
  const [i, setI] = useState(start);
  useEffect(() => setI(start), [start]);
  const go = (d: number) => setI((x) => Math.min(GLOSSARY.length - 1, Math.max(0, x + d)));
  useHotkeys({
    ArrowRight: () => go(1),
    ' ': () => go(1),
    PageDown: () => go(1),
    ArrowLeft: () => go(-1),
    PageUp: () => go(-1),
    Home: () => setI(0),
    End: () => setI(GLOSSARY.length - 1),
    Escape: () => navigate('/guide'),
  });
  const g = GLOSSARY[i];
  return (
    <div className="guide-show" role="region" aria-label="Viewer guide, full screen">
      <div className="guide-show-top">
        <span className="eyebrow">Viewer guide · what the words mean</span>
        <span className="count">
          {i + 1} / {GLOSSARY.length}
        </span>
      </div>
      <div className="guide-show-body" key={g.id}>
        <div className="guide-show-art">{g.art}</div>
        <div>
          <h1>{g.term}</h1>
          <p className="g-say">{g.say}</p>
          {g.eg && <p className="g-eg">{g.eg}</p>}
        </div>
      </div>
      <div className="guide-show-foot">
        <Link to="/guide" className="btn sm guide-exit">
          <Icon.X /> Exit <kbd>Esc</kbd>
        </Link>
        <nav className="guide-dots" aria-label="Terms">
          {GLOSSARY.map((t, k) => (
            <button key={t.id} type="button" aria-label={t.term} aria-current={k === i} onClick={() => setI(k)} />
          ))}
        </nav>
        <button className="btn sm guide-exit" onClick={() => go(-1)} disabled={i === 0} aria-label="Previous term">
          <Icon.StepBack />
        </button>
        <button className="btn sm guide-exit" onClick={() => go(1)} disabled={i === GLOSSARY.length - 1} aria-label="Next term">
          <Icon.StepFwd />
        </button>
      </div>
    </div>
  );
}
