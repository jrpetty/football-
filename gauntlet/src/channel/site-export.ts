/**
 * `node src/cli.ts publish` — writes the public website to a folder (and
 * optionally a .zip) that can be opened locally or uploaded to any static host.
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, rmdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, extname, isAbsolute, join, normalize, resolve, sep } from 'node:path';
import { deflateRawSync, crc32 } from 'node:zlib';
import { loadContestants } from '../core/config.ts';
import { ROOT } from '../core/paths.ts';
import { simulatedIds } from './history.ts';
import { buildPublicData, type BuildPublicOptions } from './public-data.ts';
import { renderSite } from './site-render.ts';
import type { PublishOptions, PublishResult } from './types.ts';

export const DEFAULT_SITE_DIR = join(ROOT, 'site');
/** Written into every exported folder; lists the files we own so a re-export only removes our own files. */
const MARKER = 'gauntlet-site.json';
const LOGO_TYPES = new Set(['.png', '.jpg', '.jpeg', '.svg', '.webp', '.gif']);

function resolveLogo(logo: string): { src?: string; copyFrom?: string; published?: string; warning?: string } {
  if (!logo) return {};
  if (/^https:\/\//i.test(logo)) return { src: logo, published: logo };
  const full = isAbsolute(logo) ? logo : join(ROOT, logo);
  const ext = extname(full).toLowerCase();
  if (!existsSync(full) || !statSync(full).isFile()) return { warning: `Logo file not found: ${logo} (using the default mark)` };
  if (!LOGO_TYPES.has(ext)) return { warning: `Logo must be a .png, .jpg, .svg, .webp or .gif file (got ${ext || 'no extension'})` };
  const published = `assets/logo${ext}`;
  return { src: published, copyFrom: full, published };
}

/** Build all site files in memory (used by the exporter and by tests). */
export function buildSiteFiles(opts: PublishOptions & Pick<BuildPublicOptions, 'leaderboardFor'> = {}): { files: Map<string, string | Buffer>; result: Omit<PublishResult, 'outDir' | 'files' | 'bytes'> } {
  const { data, warnings } = buildPublicData({ suites: opts.suites, leaderboardFor: opts.leaderboardFor });
  const logo = resolveLogo(data.site.logo);
  if (logo.warning) warnings.push(logo.warning);
  // Never publish a local file path (it can contain the owner's user name).
  data.site = { ...data.site, logo: logo.published ?? '' };
  const pages = renderSite(data, { logoSrc: logo.src, contestants: loadContestants(), historyExclude: simulatedIds() });
  const files = new Map<string, string | Buffer>(pages);
  if (logo.copyFrom && logo.published) files.set(logo.published, readFileSync(logo.copyFrom));
  return {
    files,
    result: {
      pages: [...files.keys()].filter((f) => f.endsWith('.html')).sort(),
      suites: data.suites.map((s) => ({ id: s.id, name: s.name, models: s.leaderboard.rows.length, tests: s.leaderboard.tests.length })),
      hiddenTests: data.tests.filter((t) => t.heldOut).length,
      withheldPrompts: data.tests.filter((t) => !t.heldOut && t.promptWithheld).length,
      warnings,
      generatedAt: data.generatedAt,
    },
  };
}

function safeJoin(base: string, rel: string): string {
  const full = normalize(join(base, ...rel.split('/')));
  if (full !== base && !full.startsWith(base + sep)) throw new Error(`Refusing to write outside the site folder: ${rel}`);
  return full;
}

/** Remove the files a previous export wrote (listed in its marker), then empty folders. */
function cleanPrevious(outDir: string): void {
  const marker = join(outDir, MARKER);
  if (!existsSync(marker)) return;
  let listed: string[] = [];
  try {
    listed = (JSON.parse(readFileSync(marker, 'utf8')) as { files?: string[] }).files ?? [];
  } catch {
    return;
  }
  const dirs = new Set<string>();
  for (const rel of listed) {
    try {
      const full = safeJoin(outDir, rel);
      if (existsSync(full)) unlinkSync(full);
      dirs.add(dirname(full));
    } catch {
      /* ignore unexpected entries */
    }
  }
  for (const d of [...dirs].sort((a, b) => b.length - a.length)) {
    if (d !== outDir && existsSync(d) && readdirSync(d).length === 0) rmdirSync(d);
  }
}

export function exportSite(opts: PublishOptions & Pick<BuildPublicOptions, 'leaderboardFor'> = {}): PublishResult {
  const outDir = resolve(opts.outDir ? (isAbsolute(opts.outDir) ? opts.outDir : resolve(process.cwd(), opts.outDir)) : DEFAULT_SITE_DIR);
  if (existsSync(outDir)) {
    if (!statSync(outDir).isDirectory()) throw new Error(`${outDir} is a file, not a folder`);
    const entries = readdirSync(outDir);
    if (entries.length && !entries.includes(MARKER)) throw new Error(`${outDir} is not empty and was not created by Gauntlet. Choose an empty or new folder (--out) so nothing of yours is overwritten.`);
    cleanPrevious(outDir);
  }
  const { files, result } = buildSiteFiles(opts);
  let bytes = 0;
  for (const [rel, content] of files) {
    const full = safeJoin(outDir, rel);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, content);
    bytes += typeof content === 'string' ? Buffer.byteLength(content) : content.length;
  }
  writeFileSync(join(outDir, MARKER), JSON.stringify({ generator: 'gauntlet', generatedAt: result.generatedAt, files: [...files.keys()].sort() }, null, 2) + '\n');
  let zipPath: string | undefined;
  if (opts.zip) {
    zipPath = `${outDir}.zip`;
    writeFileSync(zipPath, zipFiles(files));
  }
  return { ...result, outDir, zipPath, files: files.size, bytes };
}

// ───────────────────────────── Minimal ZIP writer (deflate, no dependencies) ─────────────────────────────

/** Create a .zip archive from in-memory files (paths use forward slashes). */
export function zipFiles(files: Map<string, string | Buffer>): Buffer {
  const chunks: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  const now = new Date();
  const dosTime = ((now.getHours() << 11) | (now.getMinutes() << 5) | Math.floor(now.getSeconds() / 2)) & 0xffff;
  const dosDate = (((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate()) & 0xffff;
  for (const [name, content] of [...files.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const data = typeof content === 'string' ? Buffer.from(content, 'utf8') : content;
    const deflated = deflateRawSync(data, { level: 9 });
    const useDeflate = deflated.length < data.length;
    const body = useDeflate ? deflated : data;
    const crc = crc32(data) >>> 0;
    const nameBuf = Buffer.from(name, 'utf8');
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(useDeflate ? 8 : 0, 8);
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);
    chunks.push(local, nameBuf, body);
    const cen = Buffer.alloc(46);
    cen.writeUInt32LE(0x02014b50, 0);
    cen.writeUInt16LE(20, 4);
    cen.writeUInt16LE(20, 6);
    cen.writeUInt16LE(0x0800, 8);
    cen.writeUInt16LE(useDeflate ? 8 : 0, 10);
    cen.writeUInt16LE(dosTime, 12);
    cen.writeUInt16LE(dosDate, 14);
    cen.writeUInt32LE(crc, 16);
    cen.writeUInt32LE(body.length, 20);
    cen.writeUInt32LE(data.length, 24);
    cen.writeUInt16LE(nameBuf.length, 28);
    cen.writeUInt32LE(offset, 42);
    central.push(cen, nameBuf);
    offset += local.length + nameBuf.length + body.length;
  }
  const cenBuf = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.size, 8);
  end.writeUInt16LE(files.size, 10);
  end.writeUInt32LE(cenBuf.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...chunks, cenBuf, end]);
}
