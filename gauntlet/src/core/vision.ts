import { createHash } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { dirname, extname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { CUSTOM_TESTS_DIR, ROOT, TESTS_DIR } from './paths.ts';
import type { ChatImage, ChatMessage, Contestant, PromptTest, PromptTestCase, ProviderType, TestDefinition } from './types.ts';

/**
 * Vision tests: images attached to prompt-test cases.
 *
 * Images are PNG/JPEG files stored next to the test JSON (e.g. tests/vision/images/chart-01.png) and
 * referenced by a path relative to the test file's folder. Their bytes are hashed into the test hash,
 * so replacing an image invalidates old results exactly like editing a prompt does.
 */

export const IMAGE_EXTENSIONS = new Set(['.png', '.jpg', '.jpeg']);
/** Largest image accepted from the Test Builder (bytes). Providers reject much larger inline images anyway. */
export const MAX_IMAGE_BYTES = 3_500_000;

export interface CaseImageRef {
  file: string;
  /** 0-based user turn the image is attached to. */
  turn: number;
}

/** Normalised image references of a case (string shorthand = first turn). */
export function caseImageRefs(c: PromptTestCase): CaseImageRef[] {
  if (!Array.isArray(c?.images)) return [];
  return c.images
    .map((x) => (typeof x === 'string' ? { file: x, turn: 0 } : { file: String(x?.file ?? ''), turn: Number.isInteger(x?.turn) ? (x.turn as number) : 0 }))
    .filter((x) => x.file);
}

export function caseHasImages(c: PromptTestCase): boolean {
  return caseImageRefs(c).length > 0;
}

/** True when any case of a prompt test shows the model an image. */
export function testHasImages(def: TestDefinition): boolean {
  return def.kind === 'prompt' && Array.isArray(def.cases) && def.cases.some(caseHasImages);
}

/** Folder that a test's relative image paths resolve against. `file` is the test file (relative to ROOT, or absolute). */
export function testBaseDir(file?: string): string {
  if (!file) return CUSTOM_TESTS_DIR;
  return dirname(isAbsolute(file) ? file : join(ROOT, file));
}

function inside(parent: string, child: string): boolean {
  const rel = relative(parent, child);
  return rel !== '' && !rel.startsWith('..') && !isAbsolute(rel);
}

/** Absolute path of an image reference, or null when it escapes the tests folder or is not a PNG/JPEG path. */
export function resolveTestImage(baseDir: string, file: string): string | null {
  if (typeof file !== 'string' || !file.trim() || file.includes('\0')) return null;
  if (!IMAGE_EXTENSIONS.has(extname(file).toLowerCase())) return null;
  const full = resolve(baseDir, file);
  return inside(resolve(TESTS_DIR), full) ? full : null;
}

/** Path of an image relative to the tests folder, with forward slashes (used by the UI image route). */
export function testsRelativePath(abs: string): string {
  return relative(resolve(TESTS_DIR), abs).split(sep).join('/');
}

/** Media type and pixel size from the file's own header (PNG IHDR / JPEG SOF). Null when not a PNG or JPEG. */
export function imageInfo(buf: Buffer): { mediaType: ChatImage['mediaType']; width: number; height: number } | null {
  if (buf.length >= 24 && buf.readUInt32BE(0) === 0x89504e47 && buf.readUInt32BE(4) === 0x0d0a1a0a) {
    return { mediaType: 'image/png', width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  }
  if (buf.length >= 4 && buf[0] === 0xff && buf[1] === 0xd8) {
    let i = 2;
    while (i + 9 < buf.length) {
      if (buf[i] !== 0xff) {
        i++;
        continue;
      }
      const marker = buf[i + 1]!;
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
        i += 2;
        continue;
      }
      const len = buf.readUInt16BE(i + 2);
      // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC) carry the frame size.
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { mediaType: 'image/jpeg', height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) };
      }
      i += 2 + len;
    }
    return { mediaType: 'image/jpeg', width: 0, height: 0 };
  }
  return null;
}

const digestCache = new Map<string, { key: string; sha: string }>();

/** SHA-256 of an image file ("missing" when it cannot be read). Cached by mtime/size. */
export function imageDigest(abs: string | null): string {
  if (!abs || !existsSync(abs)) return 'missing';
  const st = statSync(abs);
  const key = `${st.mtimeMs}:${st.size}`;
  const hit = digestCache.get(abs);
  if (hit && hit.key === key) return hit.sha;
  const sha = createHash('sha256').update(readFileSync(abs)).digest('hex');
  digestCache.set(abs, { key, sha });
  return sha;
}

/** Map of every referenced image path → SHA-256 of its bytes (part of the test hash). */
export function testImageDigests(def: PromptTest, baseDir: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const c of def.cases ?? []) for (const ref of caseImageRefs(c)) out[ref.file] = imageDigest(resolveTestImage(baseDir, ref.file));
  return out;
}

/** Read an image as a ChatImage (base64 data included). Throws when it is missing or not a PNG/JPEG. */
export function loadTestImage(baseDir: string, file: string): ChatImage {
  const abs = resolveTestImage(baseDir, file);
  if (!abs) throw new Error(`Image "${file}" must be a .png/.jpg file inside the tests folder`);
  if (!existsSync(abs)) throw new Error(`Image "${file}" not found`);
  const buf = readFileSync(abs);
  const info = imageInfo(buf);
  if (!info) throw new Error(`Image "${file}" is not a PNG or JPEG file`);
  return {
    name: file.split(/[\\/]/).pop() ?? file,
    mediaType: info.mediaType,
    data: buf.toString('base64'),
    sha256: createHash('sha256').update(buf).digest('hex'),
    bytes: buf.length,
    width: info.width,
    height: info.height,
    path: testsRelativePath(abs),
  };
}

/** Copy of messages without image bytes (for transcripts and listings). */
export function stripImageData(messages: readonly ChatMessage[]): ChatMessage[] {
  return messages.map((m) => (m.images?.length ? { ...m, images: m.images.map(({ data: _data, ...rest }) => rest) } : { ...m }));
}

/**
 * Can this contestant see images? The Random Baseline and Manual contestants always can (the baseline answers at
 * random; a human pastes the image into any chat app). API models need `vision: true` in config/models.json.
 */
export function supportsVision(c: Pick<Contestant, 'vision'>, providerType: ProviderType | undefined): boolean {
  if (c.vision !== undefined) return c.vision;
  return providerType === 'mock' || providerType === 'manual';
}

// ─────────────────────────────────────────────────────────────────────────────
// Image token estimates (approximate versions of each vendor's published formula)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Approximate input tokens one image costs.
 *  - Anthropic: the image is scaled so its long edge is at most 1568 px (and at most ~1.15 megapixels), then
 *    tokens ≈ width × height / 750.
 *  - OpenAI and other OpenAI-compatible APIs (sent with detail "high"): fit inside 2048 × 2048, scale down so the
 *    short side is at most 768 px, then 170 tokens per 512 × 512 tile + 85.
 *  - Gemini 3.x: a flat 1120 tokens per image (default "high" media resolution). Earlier Gemini: 258 tokens when
 *    both sides are ≤ 384 px, otherwise 258 per 768 × 768 tile.
 * Manual and Random Baseline contestants are not billed.
 */
export function estimateImageTokens(width: number, height: number, providerType: ProviderType | undefined, model = ''): number {
  const w = Math.max(1, width || 1024);
  const h = Math.max(1, height || 1024);
  switch (providerType) {
    case 'anthropic': {
      const scale = Math.min(1, 1568 / Math.max(w, h), Math.sqrt(1_150_000 / (w * h)));
      return Math.ceil((Math.round(w * scale) * Math.round(h * scale)) / 750);
    }
    case 'gemini': {
      if (/gemini-[3-9]/i.test(model)) return 1120;
      if (w <= 384 && h <= 384) return 258;
      return Math.ceil(w / 768) * Math.ceil(h / 768) * 258;
    }
    case 'mock':
    case 'manual':
      return 0;
    default: {
      let sw = w;
      let sh = h;
      const fit = Math.min(1, 2048 / Math.max(sw, sh));
      sw *= fit;
      sh *= fit;
      const shortFit = Math.min(1, 768 / Math.min(sw, sh));
      sw *= shortFit;
      sh *= shortFit;
      return 85 + 170 * Math.ceil(sw / 512) * Math.ceil(sh / 512);
    }
  }
}

/** Pixel sizes of every image of a case (0 × 0 when unreadable, which falls back to 1024 × 1024 in estimates). */
export function caseImageSizes(c: PromptTestCase, baseDir: string): Array<{ width: number; height: number }> {
  return caseImageRefs(c).map((ref) => {
    const abs = resolveTestImage(baseDir, ref.file);
    if (!abs || !existsSync(abs)) return { width: 0, height: 0 };
    const info = imageInfo(readFileSync(abs));
    return { width: info?.width ?? 0, height: info?.height ?? 0 };
  });
}

/** Validation problems of a case's image references. */
export function validateCaseImages(c: PromptTestCase, baseDir: string, where: string, errors: string[]): void {
  if (c.images === undefined) return;
  if (!Array.isArray(c.images)) {
    errors.push(`${where}: images must be an array of file paths`);
    return;
  }
  const turns = Array.isArray(c.turns) && c.turns.length ? c.turns.length : 1;
  for (const raw of c.images) {
    const file = typeof raw === 'string' ? raw : raw?.file;
    const turn = typeof raw === 'string' ? 0 : (raw?.turn ?? 0);
    if (typeof file !== 'string' || !file.trim()) {
      errors.push(`${where}: every image needs a file path`);
      continue;
    }
    if (!Number.isInteger(turn) || turn < 0 || turn >= turns) errors.push(`${where}: image "${file}" is attached to turn ${turn}, but the case has ${turns} turn(s)`);
    const abs = resolveTestImage(baseDir, file);
    if (!abs) {
      errors.push(`${where}: image "${file}" must be a .png/.jpg path inside the tests folder`);
      continue;
    }
    if (!existsSync(abs)) {
      errors.push(`${where}: image "${file}" not found`);
      continue;
    }
    const buf = readFileSync(abs);
    if (!imageInfo(buf)) errors.push(`${where}: image "${file}" is not a PNG or JPEG file`);
    else if (buf.length > MAX_IMAGE_BYTES) errors.push(`${where}: image "${file}" is larger than ${(MAX_IMAGE_BYTES / 1e6).toFixed(1)} MB`);
  }
}
