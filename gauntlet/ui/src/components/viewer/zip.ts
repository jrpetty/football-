/**
 * Minimal, dependency-free ZIP reader: lists the entries of an archive from
 * its central directory (names, sizes, dates, compression). It never
 * decompresses anything, so a malicious archive (zip bomb) costs nothing to
 * list. Also a tiny "stored" (uncompressed) ZIP writer used by the mock
 * fixtures and the tests.
 */

export interface ZipEntry {
  name: string;
  size: number;
  compressedSize: number;
  method: 'stored' | 'deflate' | string;
  modified: string | null;
  dir: boolean;
}

export interface ZipListing {
  entries: ZipEntry[];
  /** Total entries the archive claims (may exceed `entries` when capped). */
  total: number;
  comment: string;
  error?: string;
}

const MAX_ENTRIES = 5000;

function u16(b: Uint8Array, o: number): number {
  return b[o]! | (b[o + 1]! << 8);
}
function u32(b: Uint8Array, o: number): number {
  return (b[o]! | (b[o + 1]! << 8) | (b[o + 2]! << 16) | (b[o + 3]! << 24)) >>> 0;
}

function dosDate(date: number, time: number): string | null {
  const y = ((date >> 9) & 0x7f) + 1980;
  const mo = (date >> 5) & 0x0f;
  const d = date & 0x1f;
  if (!mo || !d) return null;
  const h = (time >> 11) & 0x1f;
  const mi = (time >> 5) & 0x3f;
  return `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')} ${String(h).padStart(2, '0')}:${String(mi).padStart(2, '0')}`;
}

export function listZip(b: Uint8Array): ZipListing {
  // End of central directory: signature 0x06054b50, within the last 64 kB + 22 bytes.
  let eocd = -1;
  for (let i = b.length - 22; i >= Math.max(0, b.length - 65557); i--) {
    if (u32(b, i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) return { entries: [], total: 0, comment: '', error: 'Not a readable ZIP (no central directory found).' };
  const total = u16(b, eocd + 10);
  const cdOffset = u32(b, eocd + 16);
  const commentLen = u16(b, eocd + 20);
  const comment = new TextDecoder().decode(b.slice(eocd + 22, eocd + 22 + commentLen));
  const entries: ZipEntry[] = [];
  let p = cdOffset;
  const dec = new TextDecoder();
  for (let n = 0; n < Math.min(total, MAX_ENTRIES); n++) {
    if (p + 46 > b.length || u32(b, p) !== 0x02014b50) return { entries, total, comment, error: entries.length ? 'The archive is truncated.' : 'Not a readable ZIP (bad central directory).' };
    const method = u16(b, p + 10);
    const time = u16(b, p + 12);
    const date = u16(b, p + 14);
    const compressedSize = u32(b, p + 20);
    const size = u32(b, p + 24);
    const nameLen = u16(b, p + 28);
    const extraLen = u16(b, p + 30);
    const commLen = u16(b, p + 32);
    const name = dec.decode(b.slice(p + 46, p + 46 + nameLen));
    entries.push({ name, size, compressedSize, method: method === 0 ? 'stored' : method === 8 ? 'deflate' : `method ${method}`, modified: dosDate(date, time), dir: name.endsWith('/') });
    p += 46 + nameLen + extraLen + commLen;
  }
  return { entries, total, comment };
}

// ───────────────────────────── Writer (stored, for fixtures) ─────────────────────────────

let CRC_TABLE: Uint32Array | null = null;
export function crc32(b: Uint8Array): number {
  if (!CRC_TABLE) {
    CRC_TABLE = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c >>> 0;
    }
  }
  let c = 0xffffffff;
  for (let i = 0; i < b.length; i++) c = CRC_TABLE[(c ^ b[i]!) & 0xff]! ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** Build an uncompressed ZIP from name → content. */
export function buildStoredZip(files: Array<{ name: string; content: string | Uint8Array }>): Uint8Array {
  const enc = new TextEncoder();
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;
  const date = ((2026 - 1980) << 9) | (9 << 5) | 28;
  const time = (12 << 11) | (0 << 5);
  for (const f of files) {
    const name = enc.encode(f.name);
    const data = typeof f.content === 'string' ? enc.encode(f.content) : f.content;
    const crc = crc32(data);
    const local = new Uint8Array(30 + name.length + data.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(8, 0, true);
    lv.setUint16(10, time, true);
    lv.setUint16(12, date, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, data.length, true);
    lv.setUint32(22, data.length, true);
    lv.setUint16(26, name.length, true);
    local.set(name, 30);
    local.set(data, 30 + name.length);
    const central = new Uint8Array(46 + name.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(12, time, true);
    cv.setUint16(14, date, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, data.length, true);
    cv.setUint32(24, data.length, true);
    cv.setUint16(28, name.length, true);
    cv.setUint32(42, offset, true);
    central.set(name, 46);
    locals.push(local);
    centrals.push(central);
    offset += local.length;
  }
  const cdSize = centrals.reduce((s, c) => s + c.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(8, files.length, true);
  ev.setUint16(10, files.length, true);
  ev.setUint32(12, cdSize, true);
  ev.setUint32(16, offset, true);
  const out = new Uint8Array(offset + cdSize + 22);
  let p = 0;
  for (const part of [...locals, ...centrals, end]) {
    out.set(part, p);
    p += part.length;
  }
  return out;
}
