/**
 * Studio → Thumbnails: a YouTube thumbnail made from this run's Gallery — the best painting of up to three
 * different artists, framed on a museum wall under spotlights, with a headline. Rendered in this browser
 * (the paintings are embedded, so the PNG needs no server).
 */
import { useEffect, useMemo, useState } from 'react';
import { useToast } from '../context.tsx';
import { Card, Field } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { CardFrame } from '../components/studio/CardsPanel.tsx';
import { downloadBlob, htmlToPng } from '../components/studio/png.ts';
import { briefForCase, type WallEntry } from '../components/viz/galleryModel.ts';
import { useGalleryRun, type GalleryRun } from './useGalleryRun.ts';

const W = 1280;
const H = 720;

interface Pick {
  entry: WallEntry;
  title: string;
}

function picksFor(run: GalleryRun): Pick[] {
  const byArtist = new Map<string, Pick>();
  for (const t of run.tests) {
    for (const { caseId } of run.briefs(t.id)) {
      for (const e of run.wall(t.id, caseId)) {
        if (e.baseline || e.state !== 'painting' || e.score === null || !e.url) continue;
        const cur = byArtist.get(e.contestantId);
        if (!cur || e.score > cur.entry.score!) byArtist.set(e.contestantId, { entry: e, title: briefForCase(caseId)?.title ?? '' });
      }
    }
  }
  return [...byArtist.values()].sort((a, b) => b.entry.score! - a.entry.score!).slice(0, 3);
}

async function toDataUrl(url: string): Promise<string> {
  if (url.startsWith('data:')) return url;
  const blob = await (await fetch(url)).blob();
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);

export function galleryThumbHtml(headline: string, sub: string, items: Array<{ src: string; label: string; color: string; title: string; score: number }>): string {
  const n = items.length;
  const w = n === 1 ? 640 : n === 2 ? 500 : 368;
  const frames = items
    .map(
      (it, i) => `<div class="slot${i === 0 ? ' first' : ''}"><div class="spot"></div><div class="frame" style="width:${w}px"><div class="liner"><img src="${it.src}" style="width:100%;aspect-ratio:3/2;object-fit:cover;display:block"></div></div><div class="plac"><i>${esc(it.title)}</i><div class="pr"><b><span style="background:${esc(it.color)}"></span>${esc(it.label)}</b><em>${Math.round(it.score * 100)}/100</em></div></div>${i === 0 ? '<div class="ros">★</div>' : ''}</div>`,
    )
    .join('');
  return `<!doctype html><html><head><meta charset="utf-8"><style>
*{box-sizing:border-box}html,body{margin:0;width:${W}px;height:${H}px;overflow:hidden}
body{font-family:'Palatino Linotype',Palatino,'Book Antiqua',Charter,'Bitstream Charter',Georgia,serif;color:#f4ecd8;background:radial-gradient(120% 70% at 50% -10%,rgba(255,236,205,.14),transparent 60%),linear-gradient(180deg,#1d2725,#101614 88%,#0a0e0d)}
.h{position:absolute;top:34px;left:0;right:0;text-align:center}
.h b{display:block;font-size:64px;font-style:italic;font-weight:600;letter-spacing:.005em;text-shadow:0 4px 18px rgba(0,0,0,.6)}
.h small{display:block;margin-top:4px;font-family:system-ui,sans-serif;font-size:21px;letter-spacing:.2em;text-transform:uppercase;color:#d9c48f}
.row{position:absolute;left:0;right:0;bottom:48px;display:flex;justify-content:center;align-items:flex-end;gap:44px}
.slot{position:relative;display:flex;flex-direction:column;align-items:center;gap:14px}
.spot{position:absolute;left:50%;top:-70px;width:160%;height:130%;transform:translateX(-50%);background:radial-gradient(ellipse 50% 55% at 50% 12%,rgba(255,228,184,.28),transparent 70%)}
.frame{position:relative;padding:16px;background:repeating-linear-gradient(90deg,rgba(90,60,10,.12) 0 2px,transparent 2px 7px),linear-gradient(135deg,#6f501b,#d6b460 16%,#f7e3a2 27%,#b48b3a 44%,#e8c979 60%,#8c6926 78%,#dcb962 92%,#7a5a20);box-shadow:0 26px 40px -18px rgba(0,0,0,.85),inset 0 2px 1px rgba(255,255,255,.3)}
.liner{padding:5px;background:linear-gradient(135deg,#efe4c8,#d8c9a4)}
.first .frame{transform:scale(1.06);transform-origin:bottom center}
.plac{position:relative;display:grid;gap:1px;width:100%;max-width:340px;padding:8px 14px;background:#f4efe4;color:#231d16;font-size:18px;box-shadow:0 10px 18px -10px rgba(0,0,0,.7)}
.plac i{font-size:17px}.plac b{font-family:system-ui,sans-serif;font-size:19px;display:flex;align-items:center;gap:8px}.plac b span{width:12px;height:12px;border-radius:50%}
.pr{display:flex;justify-content:space-between;align-items:baseline;gap:16px}.plac em{font-style:normal;font-family:system-ui,sans-serif;font-weight:800;font-size:22px;white-space:nowrap}
.ros{position:absolute;top:-26px;right:-18px;width:62px;height:62px;border-radius:50%;display:grid;place-items:center;font-size:30px;color:#8a5f10;background:radial-gradient(circle,#f8dc84 55%,#b8871b 57%);box-shadow:0 6px 12px rgba(0,0,0,.5)}
</style></head><body><div class="h"><b>${esc(headline)}</b><small>${esc(sub)}</small></div><div class="row">${frames}</div></body></html>`;
}

export function StudioGalleryCard({ runId }: { runId: string }) {
  const toast = useToast();
  const { run } = useGalleryRun(runId);
  const picks = useMemo(() => (run ? picksFor(run) : []), [run]);
  const [headline, setHeadline] = useState('Which AI painted the masterpiece?');
  const [srcs, setSrcs] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let alive = true;
    setSrcs(null);
    Promise.all(picks.map((p) => toDataUrl(p.entry.url!)))
      .then((s) => alive && setSrcs(s))
      .catch(() => alive && setSrcs([]));
    return () => {
      alive = false;
    };
  }, [picks]);
  if (!run || !run.tests.length || !picks.length) return null;
  const briefs = run.briefs(run.tests[0]!.id).length;
  const sub = `${briefs} museum commissions · ${new Set(run.tests.flatMap((t) => run.briefs(t.id).flatMap((b) => run.wall(t.id, b.caseId).filter((e) => !e.baseline).map((e) => e.contestantId)))).size} AI artists`;
  const html = srcs && srcs.length === picks.length ? galleryThumbHtml(headline, sub, picks.map((p, i) => ({ src: srcs[i]!, label: p.entry.label, color: p.entry.color, title: p.title, score: p.entry.score ?? 0 }))) : null;
  return (
    <Card className="no-broadcast" title="The Gallery thumbnail" desc="The best painting of the top artists, framed on a museum wall. Recorded paintings only.">
      <div className="stack">
        <Field label="Headline">
          <input className="input" value={headline} maxLength={48} onChange={(e) => setHeadline(e.target.value)} />
        </Field>
        {html ? <CardFrame html={html} kind="thumbnail" maxHeight={420} /> : <p className="muted">Preparing the paintings…</p>}
        <div className="row" style={{ gap: 8 }}>
          <button
            type="button"
            className="btn primary"
            disabled={!html || busy}
            onClick={async () => {
              setBusy(true);
              try {
                downloadBlob(await htmlToPng(html!, W, H), `gallery-thumbnail-${runId}.png`);
              } catch (e) {
                toast.error(e, 'Could not make the PNG');
              } finally {
                setBusy(false);
              }
            }}
          >
            <Icon.Download /> Download PNG (1280×720)
          </button>
        </div>
      </div>
    </Card>
  );
}
