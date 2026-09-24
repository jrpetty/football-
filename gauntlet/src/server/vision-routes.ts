import type { IncomingMessage, ServerResponse } from 'node:http';
import { createReadStream, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { extname, join } from 'node:path';
import { CUSTOM_TESTS_DIR, TESTS_DIR } from '../core/paths.ts';
import { MAX_IMAGE_BYTES, imageInfo, resolveTestImage, testsRelativePath } from '../core/vision.ts';
import { manualImage } from '../providers/manual.ts';

/**
 * Vision-test routes:
 *  GET  /api/test-files/<path>                 a test image (PNG/JPEG inside the tests folder only)
 *  POST /api/test-images                       upload an image for a custom test (Test Builder)
 *  GET  /api/manual/:id/images/:msg/:img       an image of a pending Manual Inbox request
 */

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string) => Error;
  streaming: symbol;
}

const IMAGE_TYPES: Record<string, string> = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg' };

/** Safe file name for an uploaded image: letters, digits, "-", "_"; the extension follows the real image type. */
export function safeImageName(name: string, mediaType: string): string {
  const ext = mediaType === 'image/jpeg' ? '.jpg' : '.png';
  const stem = String(name ?? '')
    .replace(/\.[A-Za-z0-9]+$/, '')
    .replace(/[^A-Za-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60);
  return `${stem || 'image'}${ext}`;
}

export function registerVisionRoutes({ route, httpError, streaming }: Deps): void {
  route('GET', '/api/test-files/(.+)', ({ res, params }) => {
    const rel = decodeURIComponent(params['1'] ?? '');
    const full = resolveTestImage(TESTS_DIR, rel);
    if (!full || !existsSync(full)) throw httpError(404, 'Image not found');
    res.writeHead(200, { 'content-type': IMAGE_TYPES[extname(full).toLowerCase()] ?? 'application/octet-stream', 'x-content-type-options': 'nosniff', 'cache-control': 'no-cache' });
    createReadStream(full).pipe(res);
    return streaming;
  });

  route('POST', '/api/test-images', async ({ body }) => {
    const b = (await body()) as { testId?: unknown; name?: unknown; data?: unknown };
    const testId = String(b.testId ?? '');
    if (!/^[a-z0-9][a-z0-9-]*\.[a-z0-9][a-z0-9.-]*$/.test(testId)) throw httpError(400, 'Set a valid test id before uploading images');
    if (typeof b.data !== 'string' || !b.data) throw httpError(400, 'data (base64 image bytes) is required');
    const buf = Buffer.from(b.data.replace(/^data:[^,]*,/, ''), 'base64');
    const info = imageInfo(buf);
    if (!info) throw httpError(400, 'Only PNG and JPEG images are supported');
    if (buf.length > MAX_IMAGE_BYTES) throw httpError(413, `Images must be under ${(MAX_IMAGE_BYTES / 1e6).toFixed(1)} MB`);
    const name = safeImageName(String(b.name ?? 'image'), info.mediaType);
    const dir = join(CUSTOM_TESTS_DIR, 'images', testId);
    mkdirSync(dir, { recursive: true });
    const abs = join(dir, name);
    writeFileSync(abs, buf);
    return { file: `images/${testId}/${name}`, path: testsRelativePath(abs), width: info.width, height: info.height, bytes: buf.length, mediaType: info.mediaType };
  });

  route('GET', '/api/manual/:id/images/:msg/:img', ({ res, params }) => {
    const img = manualImage(decodeURIComponent(params.id!), Number(params.msg), Number(params.img));
    if (!img?.data) throw httpError(404, 'Image not found (the request may already be answered)');
    const buf = Buffer.from(img.data, 'base64');
    res.writeHead(200, {
      'content-type': img.mediaType,
      'content-disposition': `inline; filename="${img.name.replace(/[^A-Za-z0-9._-]/g, '_')}"`,
      'x-content-type-options': 'nosniff',
      'cache-control': 'no-store',
    });
    res.end(buf);
    return streaming;
  });
}
