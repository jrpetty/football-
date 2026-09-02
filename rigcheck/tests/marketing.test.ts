import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../src/core/catalogue.ts';
import { expandRotation, parsePosts, rotating, type Rotation } from '../marketing/scripts/lib/rotation.ts';
import { versusData, versusSummary } from '../marketing/scripts/lib/versus.ts';
import { addRequest, missingFields, scaffoldRecord, scaffoldReferences, slugify, vote, type Requests } from '../scripts/game.ts';

const ROOT = new URL('..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});

describe('rotation', () => {
  const rot = load('marketing/rotation.json') as Rotation;
  it('expands to one entry per day with weekly-rotating indices', () => {
    const days = expandRotation(rot);
    expect(days).toHaveLength(rot.weeks * 7);
    expect(days[0].date).toBe(rot.start);
    expect(days[0].weekday).toBe('mon');
    expect(days.filter((d) => d.weekday === 'mon').map((d) => d.index)).toEqual([0, 1, 2, 3]);
    expect(new Set(days.map((d) => d.kind))).toEqual(new Set(['build', 'post', 'versus', 'poll', 'story']));
  });
  it('rotates a list by week and wraps', () => {
    expect(rotating(['a', 'b', 'c'], 0)).toBe('a');
    expect(rotating(['a', 'b', 'c'], 4)).toBe('b');
    expect(rotating(undefined, 1)).toBeUndefined();
  });
  it('refuses a start that is not a date', () => {
    expect(() => expandRotation({ ...rot, start: 'monday' })).toThrow();
  });
  it('every post the rotation names exists in instagram.md, and every image it names is rendered by the standard set', () => {
    const posts = parsePosts(readFileSync(join(ROOT, 'marketing/instagram.md'), 'utf8'));
    const manifest = load('marketing/cards.json') as { name: string }[];
    for (const slot of Object.values(rot.week)) {
      if (slot.kind === 'post') for (const id of slot.rotate ?? []) expect(posts.some((p) => p.id === id), id).toBe(true);
      if (slot.kind === 'story') for (const name of slot.rotate ?? []) expect(manifest.some((m) => m.name === name), name).toBe(true);
      if (slot.kind === 'versus') for (const [a, b] of slot.pairs ?? []) { expect(data.gpus.has(a) || data.cpus.has(a), a).toBe(true); expect(data.gpus.has(b) || data.cpus.has(b), b).toBe(true); }
    }
  });
});

describe('caption registry', () => {
  const posts = parsePosts(readFileSync(join(ROOT, 'marketing/instagram.md'), 'utf8'));
  it('reads every post with its images, caption and hashtags', () => {
    expect(posts.length).toBeGreaterThanOrEqual(12);
    for (const p of posts) {
      expect(p.images.length, p.id).toBeGreaterThan(0);
      expect(p.caption.length, p.id).toBeGreaterThan(40);
      expect(p.hashtags, p.id).toMatch(/^#/);
      expect(p.caption).not.toMatch(/\*\*Caption:\*\*/);
    }
  });
  it('keeps the caption clear of the shows: bookkeeping lines', () => {
    for (const p of posts) expect(p.caption).not.toMatch(/`images\//);
  });
});

describe('versus', () => {
  it('summarises with a geometric mean and counts wins', () => {
    const s = versusSummary([
      { game: 'a', a: 100, b: 150, pct: 50 },   // ×1.5
      { game: 'b', a: 150, b: 100, pct: -33 },  // ×0.667 — the same distance the other way
      { game: 'c', a: 80, b: 80, pct: 0 },
      { game: 'd', a: null, b: 90, pct: null },
    ]);
    expect(s.meanPct).toBe(0);
    expect(s.wins).toEqual({ a: 1, b: 1, tie: 1 });
  });
  it('runs two graphics cards on the same processor and reports per-game figures', () => {
    const v = versusData('nvidia-geforce-rtx-4070', 'amd-radeon-rx-9070', data);
    expect(v.kind).toBe('gpu');
    expect(v.resolution).toBe('1440p');
    expect(v.rows.length).toBeGreaterThanOrEqual(5);
    expect(v.rows.every((r) => r.a == null || r.a > 0)).toBe(true);
    expect(data.cpus.has(v.partnerId)).toBe(true);
  });
  it('drops to 1080p for a processor versus, so the card is out of the way', () => {
    const v = versusData('amd-ryzen-5-7600', 'amd-ryzen-7-7800x3d', data);
    expect(v.kind).toBe('cpu');
    expect(v.resolution).toBe('1080p');
    expect(data.gpus.has(v.partnerId)).toBe(true);
  });
  it('refuses a card against a processor, and a part against itself', () => {
    expect(() => versusData('nvidia-geforce-rtx-4070', 'amd-ryzen-7-7800x3d', data)).toThrow(/cannot compare/);
    expect(() => versusData('nvidia-geforce-rtx-4070', 'nvidia-geforce-rtx-4070', data)).toThrow(/same part/);
    expect(() => versusData('nvidia-geforce-rtx-4070', 'not-a-part', data)).toThrow(/not a catalogue id/);
  });
});

describe('game requests', () => {
  const fresh = (): Requests => ({ note: '', requests: [] });
  it('slugs titles the way the catalogue does', () => {
    expect(slugify('Helldivers 2')).toBe('helldivers-2');
    expect(slugify("Baldur's Gate 3")).toBe('baldur-s-gate-3');
    expect(slugify('Warhammer 40,000: Space Marine 2')).toBe('warhammer-40-000-space-marine-2');
  });
  it('adds a request once and turns a repeat into votes', () => {
    const r = addRequest(fresh(), 'Helldivers 2', 1, '@a', '2026-09-05');
    addRequest(r, 'helldivers 2', 2);
    expect(r.requests).toHaveLength(1);
    expect(r.requests[0]).toMatchObject({ slug: 'helldivers-2', votes: 3, status: 'open', requestedOn: '2026-09-05' });
    vote(r, 'helldivers-2', 4);
    expect(r.requests[0].votes).toBe(7);
    expect(() => vote(r, 'nope')).toThrow(/no request/);
  });
  it('scaffolds the full record shape and reports every field still to fill', () => {
    const rec = scaffoldRecord('helldivers-2', 'Helldivers 2');
    const real = (load('data/catalogue/games.json').records as Record<string, unknown>[])[0];
    for (const k of Object.keys(real)) expect(rec, k).toHaveProperty(k);
    const missing = missingFields(rec);
    expect(missing).toContain('year');
    expect(missing).toContain('requirements.minDxFeatureLevel');
    expect(missing).toContain('vramDemandGB.1080p');
    expect(missing).not.toContain('_checklist');
    // Null is legal where the catalogue itself leaves it null: no cap, no stated minimum.
    expect(missing).not.toContain('fpsCap');
    expect(missing).not.toContain('requirements.minCores');
    expect(missingFields(scaffoldReferences('helldivers-2'))).toContain('gpuBound.1080p');
  });
  it('every real catalogue record passes the completeness check promote applies', () => {
    for (const rec of load('data/catalogue/games.json').records) expect(missingFields(rec), rec.id).toEqual([]);
    for (const ref of load('data/catalogue/references.json').records) expect(missingFields(ref), ref.gameId).toEqual([]);
  });
});
