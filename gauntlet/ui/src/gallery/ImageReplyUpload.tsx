/**
 * Manual Inbox, picture replies (The Gallery Masterpiece): drop, choose or paste the painting a chat app
 * made. Any picture the browser can open (PNG, JPEG, WebP, AVIF, GIF…) is accepted; formats other than PNG
 * and JPEG, and very large files, are converted here before upload. The picture is judged exactly like one
 * from an image API.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api.ts';
import { useToast } from '../context.tsx';
import { cx } from '../components/ui.tsx';
import { Icon } from '../components/icons.tsx';
import { GalleryFrame } from '../components/viz/GalleryFrame.tsx';
import type { ManualRequest } from '../types.ts';
import '../components/viz/gallery.css';
import './gallery-pages.css';

interface Picked {
  dataUrl: string;
  width: number;
  height: number;
  bytes: number;
  type: string;
  name: string;
  converted: boolean;
}

/** Largest file sent as is; bigger (or non-PNG/JPEG) pictures are re-encoded in the browser. */
const DIRECT_MAX = 12 * 1024 * 1024;
const LONG_EDGE = 2048;

function readAsDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error ?? new Error('Could not read the file'));
    r.readAsDataURL(blob);
  });
}

async function prepare(file: Blob, name: string): Promise<Picked> {
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode().catch(() => {
      throw new Error('This file is not a picture the browser can open');
    });
    const direct = (file.type === 'image/png' || file.type === 'image/jpeg') && file.size <= DIRECT_MAX;
    if (direct) return { dataUrl: await readAsDataUrl(file), width: img.naturalWidth, height: img.naturalHeight, bytes: file.size, type: file.type, name, converted: false };
    const scale = Math.min(1, LONG_EDGE / Math.max(img.naturalWidth, img.naturalHeight));
    const w = Math.round(img.naturalWidth * scale);
    const h = Math.round(img.naturalHeight * scale);
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Canvas is not available in this browser');
    ctx.drawImage(img, 0, 0, w, h);
    const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not convert the picture'))), 'image/jpeg', 0.93));
    return { dataUrl: await readAsDataUrl(blob), width: w, height: h, bytes: blob.size, type: 'image/jpeg', name, converted: true };
  } finally {
    URL.revokeObjectURL(url);
  }
}

const mb = (b: number) => `${(b / 1e6).toFixed(b < 1e6 ? 2 : 1)} MB`;

export function ImageReplyUpload({ req, onDone }: { req: ManualRequest; onDone: (id: string) => void }) {
  const toast = useToast();
  const [picked, setPicked] = useState<Picked | null>(null);
  const [over, setOver] = useState(false);
  const [busy, setBusy] = useState(false);
  const [cost, setCost] = useState('');
  const [note, setNote] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const zone = useRef<HTMLDivElement>(null);

  const take = useCallback(
    async (file: Blob | null | undefined, name = 'painting') => {
      if (!file) return;
      if (file.type && !file.type.startsWith('image/')) {
        toast.error('That is not a picture. Drop the image file the chat app made.');
        return;
      }
      try {
        setPicked(await prepare(file, name));
      } catch (e) {
        toast.error(e, 'Could not use this picture');
      }
    },
    [toast],
  );

  // Ctrl+V anywhere while this card is open pastes a copied picture.
  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      const item = [...(e.clipboardData?.items ?? [])].find((i) => i.type.startsWith('image/'));
      if (!item) return;
      e.preventDefault();
      void take(item.getAsFile(), 'pasted picture');
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, [take]);

  const costNum = cost.trim() === '' ? undefined : Number(cost);
  const costBad = costNum !== undefined && !(Number.isFinite(costNum) && costNum >= 0);
  const small = !!picked && (picked.width < 512 || picked.height < 340);
  const ratio = picked ? picked.width / picked.height : 0;
  const offRatio = !!picked && (ratio < 1.2 || ratio > 1.9);

  const submit = async () => {
    if (!picked) return;
    if (costBad) {
      toast.error('Cost must be a number of dollars (or blank).');
      return;
    }
    setBusy(true);
    try {
      await api.manualImage(req.id, { data: picked.dataUrl, costUsd: costNum, note: note.trim() || undefined });
      toast.success(`${req.contestantLabel} · ${req.testName}: the painting goes to the judges exactly like one from an image API.`, 'Painting recorded');
      onDone(req.id);
    } catch (e) {
      toast.error(e, 'Could not upload the painting');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="stack tight" style={{ minWidth: 0, flex: 1, alignContent: 'start' }}>
      <span className="step-title">Upload the picture it made</span>
      <div className="gp-upload">
        <div
          ref={zone}
          className={cx('gp-drop', over && 'is-over', picked && 'has-picture')}
          role="button"
          tabIndex={0}
          aria-label="Drop, paste or choose the picture"
          onClick={() => input.current?.click()}
          onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current?.click()}
          onDragOver={(e) => {
            e.preventDefault();
            setOver(true);
          }}
          onDragLeave={() => setOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            setOver(false);
            const f = e.dataTransfer.files?.[0];
            void take(f, f?.name);
          }}
        >
          {picked ? (
            <GalleryFrame src={picked.dataUrl} alt="The uploaded painting" width={picked.width} height={picked.height} frame="gilt" size="sm" lit={false} />
          ) : (
            <div className="gp-drop-empty">
              <Icon.Upload />
              <b>Drop the picture here</b>
              <span>
                or click to choose a file · or copy the picture in the chat app and press <kbd>Ctrl</kbd>+<kbd>V</kbd>
              </span>
              <span className="muted">PNG, JPEG, WebP… any picture your browser can open</span>
            </div>
          )}
          <input
            ref={input}
            type="file"
            accept="image/*"
            hidden
            onChange={(e) => {
              const f = e.target.files?.[0];
              void take(f, f?.name);
              e.target.value = '';
            }}
          />
        </div>
        <div className="gp-upload-side">
          {picked ? (
            <>
              <dl className="gp-facts-list">
                <dt>Size</dt>
                <dd className="tnum">
                  {picked.width} × {picked.height}
                </dd>
                <dt>File</dt>
                <dd className="tnum">
                  {picked.type === 'image/png' ? 'PNG' : 'JPEG'} · {mb(picked.bytes)}
                  {picked.converted ? ' (converted here)' : ''}
                </dd>
              </dl>
              {small && <p className="warn-text gp-warn">This looks like a thumbnail. Download the full-size picture from the chat app.</p>}
              {offRatio && <p className="warn-text gp-warn">The brief asks for a landscape 3:2 painting; this one is {ratio.toFixed(2)}:1. Upload what the model made anyway: judges score what they see.</p>}
              <button type="button" className="btn sm ghost" onClick={() => setPicked(null)}>
                <Icon.X /> Choose another
              </button>
            </>
          ) : (
            <p className="muted" style={{ margin: 0, fontSize: '0.84rem' }}>
              Paste the prompt into the chat app exactly as shown, ask for nothing else, and upload the first picture it makes. Don’t regenerate or edit it.
            </p>
          )}
          <label className="field">
            <span className="label">Cost of this picture in USD (optional)</span>
            <input className={cx('input sm tnum', costBad && 'invalid')} inputMode="decimal" placeholder="unknown" value={cost} onChange={(e) => setCost(e.target.value)} />
          </label>
          <label className="field">
            <span className="label">Note (optional)</span>
            <input className="input sm" placeholder="e.g. made in the chat app, first attempt" value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <button type="button" className="btn primary" onClick={submit} disabled={!picked || busy || costBad}>
            <Icon.Check /> {busy ? 'Uploading…' : 'Submit painting'}
          </button>
        </div>
      </div>
    </div>
  );
}
