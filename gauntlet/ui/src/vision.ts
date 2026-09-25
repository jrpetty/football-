/**
 * Vision tests in the UI: where test images live, uploads from the Test Builder, and the
 * download / copy-to-clipboard helpers used by the Manual Inbox.
 *
 * Real mode: test images are served by GET /api/test-files/<path> (path relative to the tests folder);
 * pending Manual Inbox images by GET /api/manual/:id/images/:message/:image.
 * Mock mode (?mock=1): a few real test images are bundled so every vision screen can be demoed offline.
 */
import { MOCK, ApiError } from './api.ts';
import type { ChatImage } from './types.ts';
import chartC01 from '../../tests/vision/images/chart-c01.png?url';
import chartC05 from '../../tests/vision/images/chart-c05.png?url';
import spotS01 from '../../tests/vision/images/spot-s01.png?url';
import handH05 from '../../tests/vision/images/hand-h05.png?url';
import countK02 from '../../tests/vision/images/count-k02.png?url';
import chartC03 from '../../tests/vision/images/chart-c03.png?url';
import spotS04 from '../../tests/vision/images/spot-s04.png?url';
import handH01 from '../../tests/vision/images/hand-h01.png?url';
import countK01 from '../../tests/vision/images/count-k01.png?url';

const enc = encodeURIComponent;

/** Bundled images for mock mode, keyed by path relative to the tests folder. */
export const MOCK_TEST_IMAGES: Record<string, string> = {
  'vision/images/chart-c01.png': chartC01,
  'vision/images/chart-c05.png': chartC05,
  'vision/images/spot-s01.png': spotS01,
  'vision/images/hand-h05.png': handH05,
  'vision/images/count-k02.png': countK02,
  'vision/images/chart-c03.png': chartC03,
  'vision/images/spot-s04.png': spotS04,
  'vision/images/hand-h01.png': handH01,
  'vision/images/count-k01.png': countK01,
};

/** Images uploaded in mock mode (object URLs), keyed by their would-be path. */
const mockUploads = new Map<string, string>();

/** URL of a test image from its path relative to the tests folder (e.g. "vision/images/chart-c01.png"). */
export function testImageUrl(path: string): string {
  if (MOCK) return MOCK_TEST_IMAGES[path] ?? mockUploads.get(path) ?? '';
  return `/api/test-files/${path.split('/').map(enc).join('/')}`;
}

/** Path (relative to the tests folder) of an image reference inside a test file. */
export function imagePathFor(testFile: string | undefined, ref: string): string {
  // testFile is relative to the Gauntlet root, e.g. "tests/vision/read-the-chart.json".
  const dir = (testFile ?? 'tests/custom/x.json').replace(/\\/g, '/').replace(/^tests\//, '').split('/').slice(0, -1);
  const parts = [...dir];
  for (const seg of ref.replace(/\\/g, '/').split('/')) {
    if (seg === '..') parts.pop();
    else if (seg && seg !== '.') parts.push(seg);
  }
  return parts.join('/');
}

/** URL of one image of a pending Manual Inbox request. */
export function manualImageUrl(requestId: string, messageIndex: number, imageIndex: number, img?: ChatImage): string {
  if (MOCK) return img?.path ? testImageUrl(img.path) : '';
  return `/api/manual/${enc(requestId)}/images/${messageIndex}/${imageIndex}`;
}

export interface UploadedImage {
  /** Reference to store in the test case (relative to tests/custom/). */
  file: string;
  /** Path relative to the tests folder (for previews). */
  path: string;
  width: number;
  height: number;
  bytes: number;
}

function readAsDataUrl(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error ?? new Error('Could not read the file'));
    r.readAsDataURL(file);
  });
}

function imageSize(url: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => resolve({ width: img.naturalWidth, height: img.naturalHeight });
    img.onerror = () => resolve({ width: 0, height: 0 });
    img.src = url;
  });
}

/** Upload a PNG/JPEG for a custom test case (saved next to the test as tests/custom/images/<testId>/<name>). */
export async function uploadTestImage(testId: string, file: File): Promise<UploadedImage> {
  if (!/^image\/(png|jpeg)$/.test(file.type)) throw new ApiError('Only PNG and JPEG images are supported', 400);
  if (file.size > 3_500_000) throw new ApiError('Images must be under 3.5 MB', 413);
  const name = file.name.replace(/\.[A-Za-z0-9]+$/, '').replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'image';
  const ext = file.type === 'image/jpeg' ? '.jpg' : '.png';
  if (MOCK) {
    const url = URL.createObjectURL(file);
    const { width, height } = await imageSize(url);
    const path = `custom/images/${testId}/${name}${ext}`;
    mockUploads.set(path, url);
    return { file: `images/${testId}/${name}${ext}`, path, width, height, bytes: file.size };
  }
  const data = await readAsDataUrl(file);
  const res = await fetch('/api/test-images', {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'application/json' },
    body: JSON.stringify({ testId, name: file.name, data }),
  });
  const body = (await res.json().catch(() => ({}))) as UploadedImage & { error?: string };
  if (!res.ok) throw new ApiError(body.error ?? `Upload failed (${res.status})`, res.status);
  return body;
}

/** Save an image to the user's Downloads folder. */
export async function downloadImage(url: string, name: string): Promise<void> {
  const blob = await (await fetch(url)).blob();
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(href), 2000);
}

/** Put an image on the clipboard (as PNG, the one image type every browser clipboard accepts). */
export async function copyImage(url: string): Promise<boolean> {
  try {
    if (!('ClipboardItem' in window) || !navigator.clipboard?.write) return false;
    const blob = await (await fetch(url)).blob();
    let png = blob;
    if (blob.type !== 'image/png') {
      const bitmap = await createImageBitmap(blob);
      const canvas = document.createElement('canvas');
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      canvas.getContext('2d')!.drawImage(bitmap, 0, 0);
      png = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('PNG conversion failed'))), 'image/png'));
    }
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]);
    return true;
  } catch {
    return false;
  }
}
