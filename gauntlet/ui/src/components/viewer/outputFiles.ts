/**
 * Everything a result produced, as viewer files: the replay, stored
 * artifacts (with screenshots attached to the game they show), files inside
 * the reply (fenced blocks and data URLs), the reply itself, the extracted
 * JSON answer against its key, and the transcript.
 */
import { artifactUrl } from '../../api.ts';
import type { CaseResult } from '../../types.ts';
import { extractAttachments } from '../../../../src/grading/attachments.ts';
import { extForLang, sniffText } from './detect.ts';
import type { ViewerFile } from './types.ts';
import type { ReplayContext } from '../ReplayPlayer.tsx';

export function finalReply(r: Pick<CaseResult, 'transcript'>): string {
  const e = [...(r.transcript ?? [])].reverse().find((t) => !t.judge);
  return e?.response ?? '';
}

export function outputFilesFor(r: CaseResult, opts: { runId?: string; replayContext?: ReplayContext; answerKey?: unknown; artifactUrlFor?: (file: string) => string } = {}): ViewerFile[] {
  const out: ViewerFile[] = [];
  const urlFor = opts.artifactUrlFor ?? ((file: string) => (opts.runId ? artifactUrl(opts.runId, file) : ''));
  if (r.replay) out.push({ id: 'replay', name: r.replay.title || 'Replay', hint: 'replay', role: 'Replay', replay: r.replay, replayContext: opts.replayContext });

  const arts = r.artifacts ?? [];
  const shots = arts.filter((a) => a.kind === 'png' && /screenshot|render|frame|shot|playtest/i.test(a.name));
  const main = arts.filter((a) => !shots.includes(a));
  const shotFiles: ViewerFile[] = shots.map((a) => ({ id: `art:${a.file}`, name: a.name, url: urlFor(a.file), size: a.bytes, role: 'Screenshot' }));
  const hasHtml = main.some((a) => a.kind === 'html');
  const hasSvg = main.some((a) => a.kind === 'svg');
  for (const a of main) {
    const role = a.kind === 'html' ? 'The build' : a.kind === 'svg' ? 'The drawing' : a.kind === 'png' ? 'Picture' : /solution|\.js$/.test(a.name) ? 'Code' : 'File';
    out.push({ id: `art:${a.file}`, name: a.name, url: urlFor(a.file), size: a.bytes, role, screenshots: a.kind === 'html' ? shotFiles : undefined, hint: a.kind === 'html' ? 'html' : a.kind === 'svg' ? 'svg' : undefined });
  }
  // Screenshots of an SVG render (no HTML to attach them to) are shown as pictures.
  if (!hasHtml) for (const s of shotFiles) out.push({ ...s, role: s.name.startsWith('render') ? 'How it renders' : 'Screenshot' });

  const reply = finalReply(r);
  if (reply.trim()) {
    for (const [i, a] of extractAttachments(reply).entries()) {
      const lang = (a.lang ?? '').toLowerCase();
      if ((lang === 'html' && hasHtml) || (lang === 'svg' && hasSvg)) continue; // already shown as the stored artifact
      if (a.dataUrl) out.push({ id: `att:${i}`, name: a.name, url: a.dataUrl, mime: a.mime, size: a.bytes });
      else {
        const name = /\.\w+$/.test(a.name) ? a.name : `${a.name}.${extForLang(lang) || 'txt'}`;
        out.push({ id: `att:${i}`, name, text: a.text, lang, size: a.bytes, compareTo: lang === 'json' ? opts.answerKey : undefined });
      }
    }
    const asJson = sniffText(reply) === 'json';
    out.push({ id: 'reply', name: asJson ? 'reply.json' : 'reply.md', text: reply, role: 'Model reply', hint: asJson ? 'json' : 'markdown', compareTo: asJson ? opts.answerKey : undefined });
  }
  // JSON tests: the answer the scorer extracted, against the key.
  const ex = r.scoreDetail?.extracted;
  const exp = r.scoreDetail?.expected ?? opts.answerKey;
  if (typeof ex === 'string' && exp && typeof exp === 'object' && /^[[{]/.test(ex.trim())) {
    try {
      JSON.parse(ex);
      out.splice(r.replay ? 1 : 0, 0, { id: 'extracted-json', name: 'answer.json', text: ex, hint: 'json', role: 'Answer vs key', compareTo: exp });
    } catch {
      /* not JSON */
    }
  }
  if (r.transcript?.length) out.push({ id: 'transcript', name: 'transcript', hint: 'transcript', role: 'Transcript', transcript: r.transcript });
  return out;
}

/** The file to open first: the replay, the build, the JSON answer, else the first file. */
export function primaryFile(files: ViewerFile[]): string | undefined {
  return (files.find((f) => f.id === 'replay') ?? files.find((f) => f.hint === 'html' || f.hint === 'svg') ?? files.find((f) => f.id === 'extracted-json') ?? files.find((f) => f.id.startsWith('att:') && !!f.url) ?? files.find((f) => f.id === 'reply') ?? files[0])?.id;
}
