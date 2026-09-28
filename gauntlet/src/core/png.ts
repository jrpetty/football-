import { deflateSync } from 'node:zlib';

/**
 * A tiny PNG encoder (RGB, 8-bit, no dependencies). Used for the Random Baseline's noise "painting" and in tests.
 */

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf: Buffer): number {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]!) & 0xff]! ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

/** Encode an RGB pixel buffer (width × height × 3 bytes) as a PNG. */
export function encodePng(width: number, height: number, rgb: Uint8Array): Buffer {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    raw[y * stride] = 0; // filter: none
    raw.set(rgb.subarray(y * width * 3, (y + 1) * width * 3), y * stride + 1);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type: RGB
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

/**
 * A deterministic abstract noise picture: soft value noise in three random colours with grain. It shows where
 * "no skill at all" lands: it follows no brief and has no composition. Small (384 × 256) to keep judging cheap.
 */
export function noisePng(seed: number, width = 384, height = 256): Buffer {
  let s = seed >>> 0 || 1;
  const rand = () => {
    s = (s + 0x6d2b79f5) | 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const colors = Array.from({ length: 3 }, () => [rand() * 255, rand() * 255, rand() * 255]);
  const cell = 32;
  const gw = Math.ceil(width / cell) + 2;
  const gh = Math.ceil(height / cell) + 2;
  const grid = Array.from({ length: gw * gh }, () => rand());
  const smooth = (t: number) => t * t * (3 - 2 * t);
  const v = (i: number, j: number) => grid[(j % gh) * gw + (i % gw)]!;
  const rgb = new Uint8Array(width * height * 3);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const gx = x / cell;
      const gy = y / cell;
      const x0 = Math.floor(gx);
      const y0 = Math.floor(gy);
      const fx = smooth(gx - x0);
      const fy = smooth(gy - y0);
      const n = v(x0, y0) * (1 - fx) * (1 - fy) + v(x0 + 1, y0) * fx * (1 - fy) + v(x0, y0 + 1) * (1 - fx) * fy + v(x0 + 1, y0 + 1) * fx * fy;
      const a = colors[0]!;
      const b = colors[n < 0.5 ? 1 : 2]!;
      const t = n < 0.5 ? n * 2 : (n - 0.5) * 2;
      const grain = (rand() - 0.5) * 28;
      const o = (y * width + x) * 3;
      for (let k = 0; k < 3; k++) rgb[o + k] = Math.max(0, Math.min(255, Math.round(a[k]! * (1 - t) + b[k]! * t + grain)));
    }
  }
  return encodePng(width, height, rgb);
}
