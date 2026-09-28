/**
 * The vertical 1080×1920 "A vs B" Shorts card: live preview + PNG download.
 * Same pipeline as Studio → Thumbnails & Shorts: the server's headless Chrome
 * when it has one, otherwise the card is drawn in this browser (png.ts).
 */
import { useMemo, useState } from 'react';
import { useToast } from '../context.tsx';
import { useElementSize } from '../hooks.ts';
import { Icon } from '../components/icons.tsx';
import { downloadBlob, htmlToPng } from '../components/studio/png.ts';
import { renderVersusCardHtml, versusCardFileName, VERSUS_CARD } from '../../../src/versus/card.ts';
import { versusApi, type VersusData } from './client.ts';

export function VersusCardPreview({ html, maxHeight = 640 }: { html: string; maxHeight?: number }) {
  const { width, height } = VERSUS_CARD;
  const [ref, box] = useElementSize<HTMLDivElement>();
  const k = box.width ? Math.min(box.width / width, maxHeight / height) : 0;
  return (
    <div ref={ref} className="vx-card-box">
      <div className="vx-card-frame" style={{ width: width * k || 1, height: height * k || 0 }}>
        {k > 0 && <iframe title="Shorts card preview" sandbox="" srcDoc={html} width={width} height={height} style={{ transform: `scale(${k})` }} tabIndex={-1} />}
      </div>
    </div>
  );
}

export function useVersusCardDownload(d: VersusData | null, runId?: string) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const html = useMemo(() => (d ? renderVersusCardHtml(d) : ''), [d]);
  const download = async () => {
    if (!d) return;
    setBusy(true);
    try {
      try {
        const r = await versusApi.render(d.a.id, d.b.id, runId);
        downloadBlob(r.png, r.fileName);
        return;
      } catch {
        /* no browser on the server (or demo mode): draw it here */
      }
      downloadBlob(await htmlToPng(html, VERSUS_CARD.width, VERSUS_CARD.height), versusCardFileName(d));
    } catch (e) {
      toast.error(e, 'Could not make the PNG');
    } finally {
      setBusy(false);
    }
  };
  return { html, busy, download };
}

/** Preview + download button, for the Head to Head page and the Studio. */
export function VersusCardPanel({ data, runId, maxHeight }: { data: VersusData; runId?: string; maxHeight?: number }) {
  const { html, busy, download } = useVersusCardDownload(data, runId);
  return (
    <div className="vx-card-panel">
      <VersusCardPreview html={html} maxHeight={maxHeight} />
      <div className="vx-card-side">
        <p>
          A vertical <b>1080×1920</b> card for YouTube Shorts, TikTok or Reels: who won, the score in rounds, every round’s scores and the cost. Made from the same stored results as this page.
        </p>
        <button type="button" className="btn primary" onClick={download} disabled={busy}>
          <Icon.Download /> {busy ? 'Rendering…' : 'Download PNG'}
        </button>
        <p className="muted small">File: {versusCardFileName(data)}</p>
      </div>
    </div>
  );
}
