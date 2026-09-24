/** Studio → Thumbnails & Shorts: pick a template and style, preview live, download PNGs (server Chrome or in-browser). */
import { useEffect, useMemo, useState } from 'react';
import { api } from '../../api.ts';
import { useToast } from '../../context.tsx';
import { useElementSize } from '../../hooks.ts';
import { Callout, Card, Field, Seg, cx } from '../ui.tsx';
import { Icon } from '../icons.tsx';
import { CARD_KINDS, CARD_STYLES, cardFileName, cardSize, cardTitle, defaultCards, renderCardHtml } from '../../../../src/media/cards.ts';
import { downloadBlob, htmlToPng } from './png.ts';
import type { CardKind, CardSpec, CardStyle, StudioPayload } from '../../types.ts';

/** A card rendered live in a sandboxed iframe, scaled to fit its box. */
export function CardFrame({ html, kind, maxHeight }: { html: string; kind: CardKind; maxHeight?: number }) {
  const { width, height } = cardSize(kind);
  const [ref, box] = useElementSize<HTMLDivElement>();
  const byW = box.width ? box.width / width : 0;
  const k = maxHeight ? Math.min(byW, maxHeight / height) : byW;
  return (
    <div ref={ref} className="card-frame-box">
      <div className="card-frame" style={{ width: width * k || '100%', height: height * k || 0 }}>
        {k > 0 && <iframe title="Card preview" sandbox="" srcDoc={html} width={width} height={height} style={{ transform: `scale(${k})` }} tabIndex={-1} loading="lazy" />}
      </div>
    </div>
  );
}

export function CardsPanel({ s, highlightId }: { s: StudioPayload; highlightId?: string }) {
  const toast = useToast();
  const data = s.cardData;
  const firstTest = data.tests.find((t) => t.results.length)?.id;
  const firstHook = data.tests.find((t) => t.results.length && t.hook)?.id ?? firstTest;
  const [spec, setSpec] = useState<CardSpec>(() => (highlightId ? { kind: 'short-highlight', style: 'versus', highlightId } : { kind: 'thumbnail', style: 'versus' }));
  const [busy, setBusy] = useState<string | null>(null);
  const [engine, setEngine] = useState<'server' | 'browser' | null>(null);

  useEffect(() => {
    if (highlightId) setSpec((p) => ({ ...p, kind: 'short-highlight', highlightId }));
  }, [highlightId]);

  const effective: CardSpec = {
    ...spec,
    testId: spec.kind === 'short-question' ? (spec.testId ?? firstHook) : (spec.testId ?? firstTest),
    highlightId: spec.highlightId ?? data.highlights[0]?.id,
  };
  const html = renderCardHtml(data, effective);
  const gallery = useMemo(() => defaultCards(data, spec.style, spec.headline).map((sp) => ({ sp, html: renderCardHtml(data, sp) })), [data, spec.style, spec.headline]);
  const comps = data.contestants.filter((c) => !c.baseline);

  /** One PNG: the server's headless Chrome when it has one, else this browser. */
  const render = async (sp: CardSpec): Promise<{ data: Blob | string; name: string }> => {
    const name = cardFileName(sp);
    if (s.browser.available) {
      try {
        const r = await api.studioRender(s.runId, sp);
        setEngine('server');
        return { data: r.png, name };
      } catch {
        /* fall back to the browser */
      }
    }
    const { width, height } = cardSize(sp.kind);
    const blob = await htmlToPng(renderCardHtml(data, sp), width, height);
    setEngine('browser');
    return { data: blob, name };
  };

  const downloadOne = async (sp: CardSpec) => {
    setBusy(cardFileName(sp));
    try {
      const r = await render(sp);
      downloadBlob(r.data, r.name);
    } catch (e) {
      toast.error(e, 'Could not make the PNG');
    } finally {
      setBusy(null);
    }
  };

  const exportAll = async () => {
    setBusy('all');
    try {
      const r = await api.studioExport(s.runId, { style: spec.style, headline: spec.headline });
      if (r.pngs > 0) {
        toast.success(`${r.pngs} PNGs, the script and the highlights were saved to ${r.folder}`, 'Exported');
      } else {
        // No browser on the server: render each card here and download them one by one.
        for (const [i, { sp, html: page }] of gallery.entries()) {
          const { width, height } = cardSize(sp.kind);
          downloadBlob(await htmlToPng(page, width, height), cardFileName(sp, i));
          await new Promise((res) => window.setTimeout(res, 250));
        }
        setEngine('browser');
        toast.success(`${gallery.length} PNGs downloaded by your browser. The script and highlights were saved to ${r.folder}.`, 'Exported');
      }
    } catch (e) {
      toast.error(e, 'Export failed');
    } finally {
      setBusy(null);
    }
  };

  const set = (patch: Partial<CardSpec>) => setSpec((p) => ({ ...p, ...patch }));
  const { width, height } = cardSize(effective.kind);

  return (
    <div className="stack loose">
      <div className="cards-grid">
        <Card title="Template" desc="Pick what to make; the preview updates as you type." className="no-broadcast">
          <div className="stack">
            <Field label="Format">
              <div className="kind-list" role="radiogroup" aria-label="Card format">
                {CARD_KINDS.map((k) => (
                  <button key={k.id} type="button" role="radio" aria-checked={spec.kind === k.id} className={cx('kind-opt', spec.kind === k.id && 'on')} onClick={() => set({ kind: k.id })}>
                    <span className={cx('kind-shape', k.id === 'thumbnail' ? 'wide' : 'tall')} />
                    <span className="stack tight">
                      <b>{k.label}</b>
                      <span className="muted">{k.hint}</span>
                    </span>
                  </button>
                ))}
              </div>
            </Field>
            <Field label="Style">
              <Seg<CardStyle> label="Style" value={spec.style} onChange={(v) => set({ style: v })} options={CARD_STYLES.map((x) => ({ value: x.id, label: x.label, title: x.hint }))} />
            </Field>
            <Field label="Headline" hint="Optional. Leave empty for the automatic one.">
              <input className="input" value={spec.headline ?? ''} maxLength={80} placeholder={effective.kind === 'thumbnail' ? 'e.g. THE CHEAP ONE WON?!' : 'Automatic'} onChange={(e) => set({ headline: e.target.value || undefined })} />
            </Field>
            {effective.kind === 'thumbnail' && comps.length > 2 && (
              <div className="row" style={{ gap: 10 }}>
                <Field label="Left">
                  <select className="select" value={spec.a ?? comps[0]?.id} onChange={(e) => set({ a: e.target.value })}>
                    {comps.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Right">
                  <select className="select" value={spec.b ?? comps[1]?.id} onChange={(e) => set({ b: e.target.value })}>
                    {comps.map((c) => (
                      <option key={c.id} value={c.id}>
                        {c.label}
                      </option>
                    ))}
                  </select>
                </Field>
              </div>
            )}
            {(effective.kind === 'short-test' || effective.kind === 'short-question') && (
              <Field label="Test">
                <select className="select" value={effective.testId} onChange={(e) => set({ testId: e.target.value })}>
                  {data.tests
                    .filter((t) => t.results.length)
                    .map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}
                      </option>
                    ))}
                </select>
              </Field>
            )}
            {effective.kind === 'short-highlight' && (
              <Field label="Highlight">
                <select className="select" value={effective.highlightId} onChange={(e) => set({ highlightId: e.target.value })}>
                  {data.highlights.map((h, i) => (
                    <option key={h.id} value={h.id}>
                      {i + 1}. {h.title}
                    </option>
                  ))}
                </select>
              </Field>
            )}
          </div>
        </Card>

        <Card
          title={cardTitle(data, effective)}
          desc={`${width}×${height} PNG`}
          tools={
            <div className="row no-broadcast" style={{ gap: 8 }}>
              <button type="button" className="btn primary" onClick={() => downloadOne(effective)} disabled={busy !== null}>
                <Icon.Download /> {busy === cardFileName(effective) ? 'Rendering…' : 'Download PNG'}
              </button>
            </div>
          }
        >
          <div className={cx('card-stage', effective.kind === 'thumbnail' ? 'wide' : 'tall')}>
            <CardFrame html={html} kind={effective.kind} maxHeight={effective.kind === 'thumbnail' ? undefined : 620} />
          </div>
          <div className="muted no-broadcast" style={{ fontSize: '0.84rem', marginTop: 10 }}>
            {s.browser.available ? 'PNGs are rendered by headless Chrome on the server — pixel-identical to this preview.' : s.browser.hint}
            {engine === 'browser' && s.browser.available ? ' (Last one fell back to your browser.)' : ''}
          </div>
        </Card>
      </div>

      <Card
        className="no-broadcast"
        title="Everything for this run"
        desc={`${gallery.length} cards in the "${CARD_STYLES.find((x) => x.id === spec.style)?.label}" style: every thumbnail style, the standings, one card per test, question cards and the top highlights.`}
        tools={
          <button type="button" className="btn" onClick={exportAll} disabled={busy !== null}>
            <Icon.Layers /> {busy === 'all' ? 'Exporting…' : 'Export all'}
          </button>
        }
      >
        <Callout tone="plain">
          <strong>Export all</strong> saves every PNG plus <code>script.md</code>, <code>script.txt</code> and <code>highlights.json</code> into <code className="mono">{s.exportDir}</code>
          {s.browser.available ? '.' : ' (the PNGs download through your browser, because the server has no Chrome).'}
        </Callout>
        <div className="card-gallery">
          {gallery.map(({ sp, html: page }, i) => (
            <figure key={`${cardFileName(sp, i)}`} className={cx('gal-item', sp.kind === 'thumbnail' ? 'wide' : 'tall')}>
              <button type="button" className="gal-open" onClick={() => setSpec(sp)} aria-label={`Edit ${cardTitle(data, sp)}`}>
                <CardFrame html={page} kind={sp.kind} />
              </button>
              <figcaption>
                <span className="ellipsis" title={cardTitle(data, sp)}>
                  {cardTitle(data, sp)}
                </span>
                <button type="button" className="btn xs icon" onClick={() => downloadOne(sp)} aria-label="Download PNG" disabled={busy !== null}>
                  <Icon.Download />
                </button>
              </figcaption>
            </figure>
          ))}
        </div>
      </Card>
    </div>
  );
}
