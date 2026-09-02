/**
 * The posting rotation and the caption registry — the pure parts of the
 * calendar, kept free of rendering so they can be tested.
 */

export type Kind = 'post' | 'build' | 'versus' | 'poll' | 'story';
export interface RotationSlot { kind: Kind; rotate?: string[]; budgets?: number[]; resolution?: string | string[]; refreshHz?: number[]; pairs?: string[][] }
export interface Rotation { start: string; weeks: number; week: Record<string, RotationSlot> }
export interface Day { date: string; weekday: string; week: number; kind: Kind; index: number; slot: RotationSlot }

const WEEKDAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];

/** Expand a rotation into one entry per day. `index` is the week number, so a slot's lists rotate weekly. */
export function expandRotation(rot: Rotation): Day[] {
  const start = Date.parse(`${rot.start}T00:00:00Z`);
  if (Number.isNaN(start)) throw new Error(`rotation start is not a date: ${rot.start}`);
  const out: Day[] = [];
  for (let d = 0; d < rot.weeks * 7; d++) {
    const t = new Date(start + d * 86400000);
    const weekday = WEEKDAYS[t.getUTCDay()];
    const slot = rot.week[weekday];
    if (!slot) continue;
    const week = Math.floor(d / 7);
    out.push({ date: t.toISOString().slice(0, 10), weekday, week, kind: slot.kind, index: week, slot });
  }
  return out;
}

/** Pick the week's item from a rotating list. */
export const rotating = <T,>(list: T[] | undefined, index: number): T | undefined => (list && list.length ? list[index % list.length] : undefined);

export interface Post { id: string; title: string; images: string[]; caption: string; hashtags: string }

/**
 * Read instagram.md into posts. A post is a "## POST N — title" section; its
 * images are every `images/…png` mentioned before **Caption:**, its caption is
 * the text after **Caption:** up to the trailing hashtag line.
 */
export function parsePosts(md: string): Post[] {
  const posts: Post[] = [];
  const sections = md.split(/^## /m).slice(1);
  for (const sec of sections) {
    const head = sec.split('\n')[0];
    const m = /^(POST \d+)\s*—\s*(.+)$/.exec(head.trim());
    if (!m) continue;
    const body = sec.slice(head.length);
    const capAt = body.indexOf('**Caption:**');
    if (capAt < 0) continue;
    const pre = body.slice(0, capAt);
    const images = [...pre.matchAll(/`images\/([\w-]+\.png)`/g)].map((x) => x[1]);
    let cap = body.slice(capAt + '**Caption:**'.length).split(/\n---\s*\n/)[0].trim();
    let hashtags = '';
    const lines = cap.split('\n');
    if (lines.length && /^#\w/.test(lines[lines.length - 1].trim())) { hashtags = lines.pop()!.trim(); cap = lines.join('\n').trim(); }
    posts.push({ id: m[1], title: m[2].trim(), images, caption: cap, hashtags });
  }
  return posts;
}
