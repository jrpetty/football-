import type { IncomingMessage, ServerResponse } from 'node:http';
import { manualRequest, submitManual } from '../providers/manual.ts';
import { toGeneratedImage } from '../providers/image-gen.ts';

/**
 * The Gallery Masterpiece routes:
 *  POST /api/manual/:id/image   answer a Manual Inbox picture request with an uploaded PNG/JPEG
 *                               body: { data: base64 or data: URL, costUsd?: number, note?: string }
 * (Blind votes and the owner's artistry ratings use the existing POST /api/review/score.)
 */

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string) => Error;
}

/** Largest uploaded painting (bytes, after base64 decoding). */
export const MAX_PAINTING_BYTES = 20 * 1024 * 1024;

async function readLargeJson(req: IncomingMessage, limit: number, httpError: Deps['httpError']): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > limit) throw httpError(413, `The picture is too large (limit ${Math.round(MAX_PAINTING_BYTES / 1e6)} MB)`);
    chunks.push(chunk as Buffer);
  }
  try {
    const v = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
    return v && typeof v === 'object' ? (v as Record<string, unknown>) : {};
  } catch {
    throw httpError(400, 'Body must be valid JSON');
  }
}

export function registerGalleryRoutes({ route, httpError }: Deps): void {
  route('POST', '/api/manual/:id/image', async ({ req, params }) => {
    const id = decodeURIComponent(params.id!);
    const pending = manualRequest(id);
    if (!pending) throw httpError(404, 'This request is no longer waiting (answered, cancelled or timed out)');
    if (pending.expects !== 'image') throw httpError(400, 'This request expects a text reply, not a picture');
    const b = await readLargeJson(req, Math.ceil(MAX_PAINTING_BYTES * 1.4) + 4096, httpError);
    if (typeof b.data !== 'string' || !b.data) throw httpError(400, 'data (the picture as base64 or a data: URL) is required');
    const image = toGeneratedImage(b.data);
    if (!image) throw httpError(400, 'Only PNG and JPEG pictures are accepted (the Manual Inbox converts other formats for you)');
    if (image.bytes > MAX_PAINTING_BYTES) throw httpError(413, `The picture is too large (limit ${Math.round(MAX_PAINTING_BYTES / 1e6)} MB)`);
    if (image.width < 64 || image.height < 64) throw httpError(400, `The picture is only ${image.width}×${image.height} pixels: upload the full-size painting`);
    const cost = b.costUsd === undefined || b.costUsd === null || b.costUsd === '' ? undefined : Number(b.costUsd);
    if (cost !== undefined && !(Number.isFinite(cost) && cost >= 0)) throw httpError(400, 'costUsd must be a non-negative number');
    const note = typeof b.note === 'string' ? b.note.slice(0, 2000) : '';
    if (!submitManual(id, { text: note, image, costUsd: cost })) throw httpError(404, 'This request is no longer waiting');
    return { ok: true, width: image.width, height: image.height, bytes: image.bytes, mediaType: image.mediaType };
  });
}
