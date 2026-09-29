/**
 * Manual Inbox: add files to a pasted reply (upload, drag and drop, or paste).
 * Text files become fenced code blocks ("File: game.html" + ```html), which the
 * artifact scorers already read; pictures, audio, video, PDFs, ZIPs and other
 * binaries become data-URL links. See src/grading/attachments.ts.
 */
import { useRef, useState, type ClipboardEvent, type DragEvent } from 'react';
import { Icon } from '../components/icons.tsx';
import { cx } from '../components/ui.tsx';
import { useToast } from '../context.tsx';
import { MAX_UPLOAD_BYTES, binaryFileToReply, extractAttachments, langForFile, textFileToReply } from '../../../src/grading/attachments.ts';
import { fmtSize, mimeForName } from '../components/viewer/detect.ts';
import './grading.css';

async function toBase64(file: File): Promise<string> {
  const b = new Uint8Array(await file.arrayBuffer());
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}

/** Reply text for one file (null with a reason when it is too big). */
export async function fileToReplyText(file: File): Promise<{ text: string } | { error: string }> {
  if (file.size > MAX_UPLOAD_BYTES) return { error: `${file.name} is ${fmtSize(file.size)}; the limit is ${fmtSize(MAX_UPLOAD_BYTES)} per reply.` };
  if (langForFile(file.name) || (file.type.startsWith('text/') && file.type !== 'text/html')) return { text: textFileToReply(file.name, await file.text()) };
  if (file.type === 'text/html') return { text: textFileToReply(file.name.endsWith('.html') || file.name.endsWith('.htm') ? file.name : `${file.name}.html`, await file.text()) };
  return { text: binaryFileToReply(file.name, file.type || mimeForName(file.name), await toBase64(file)) };
}

export function useReplyFiles(onAdd: (text: string) => void) {
  const toast = useToast();
  const add = async (files: FileList | File[]) => {
    for (const f of Array.from(files)) {
      const r = await fileToReplyText(f);
      if ('error' in r) toast.error(r.error, 'File too big');
      else onAdd(r.text);
    }
  };
  return {
    add,
    onPaste: (e: ClipboardEvent<HTMLTextAreaElement>) => {
      if (e.clipboardData.files.length) {
        e.preventDefault();
        void add(e.clipboardData.files);
      }
    },
  };
}

export function ReplyFiles({ text, onAdd }: { text: string; onAdd: (t: string) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [over, setOver] = useState(false);
  const { add } = useReplyFiles(onAdd);
  const found = text.length < 30_000_000 ? extractAttachments(text).filter((a) => a.dataUrl || /^block-/.test(a.name) === false) : [];
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setOver(false);
    if (e.dataTransfer.files.length) void add(e.dataTransfer.files);
  };
  return (
    <div className={cx('rf', over && 'over')} onDragOver={(e) => (e.preventDefault(), setOver(true))} onDragLeave={() => setOver(false)} onDrop={onDrop}>
      <button type="button" className="btn sm" onClick={() => input.current?.click()}>
        <Icon.Upload /> Attach files
      </button>
      <span className="muted rf-hint">or drop / paste them here · games, pictures, audio, video, PDF, JSON, CSV, code, ZIP… up to {fmtSize(MAX_UPLOAD_BYTES)} each</span>
      <input ref={input} type="file" multiple hidden onChange={(e) => e.target.files && void add(e.target.files).then(() => (e.target.value = ''))} />
      {found.length > 0 && (
        <div className="rf-chips">
          {found.map((a, i) => (
            <span key={i} className="chip pill" title={a.mime ?? a.lang}>
              {a.name} · {fmtSize(a.bytes)}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
