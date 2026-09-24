/** Channel branding for the public website (config/site.json, optional). */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CONFIG_DIR } from '../core/paths.ts';
import { writeJsonAtomic } from '../core/config.ts';
import type { SiteConfig } from './types.ts';

export const SITE_CONFIG_FILE = join(CONFIG_DIR, 'site.json');

export const DEFAULT_SITE: SiteConfig = {
  channelName: 'Gauntlet',
  tagline: 'Independent AI model benchmarks: same tests, same seeds, every model.',
  logo: '',
  youtubeUrl: '',
  accentColor: '#6366F1',
  suites: ['core', 'frontier'],
  siteUrl: '',
  submissionFormUrl: '',
  season: '2026-s1',
  footerNote: '',
};

const HEX = /^#[0-9a-fA-F]{6}$/;

export function validateSiteConfig(c: Partial<SiteConfig>): string[] {
  const errors: string[] = [];
  if (c.accentColor !== undefined && !HEX.test(c.accentColor)) errors.push('accentColor must be a #RRGGBB hex colour');
  for (const k of ['youtubeUrl', 'submissionFormUrl', 'siteUrl'] as const) {
    const v = c[k];
    if (v && !/^https?:\/\//i.test(v)) errors.push(`${k} must start with https://`);
  }
  if (c.logo && /^[a-z]+:/i.test(c.logo) && !/^https:\/\//i.test(c.logo)) errors.push('logo must be a file path or an https:// URL');
  if (c.suites !== undefined && (!Array.isArray(c.suites) || c.suites.some((s) => typeof s !== 'string'))) errors.push('suites must be a list of suite ids');
  if (c.season !== undefined && !/^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$/.test(c.season)) errors.push('season must be letters, digits, "-" or "_" (e.g. 2026-s1)');
  return errors;
}

export function loadSiteConfig(): SiteConfig {
  if (!existsSync(SITE_CONFIG_FILE)) return { ...DEFAULT_SITE };
  try {
    const raw = JSON.parse(readFileSync(SITE_CONFIG_FILE, 'utf8')) as Partial<SiteConfig>;
    const merged: SiteConfig = { ...DEFAULT_SITE, ...raw };
    if (!HEX.test(merged.accentColor)) merged.accentColor = DEFAULT_SITE.accentColor;
    if (!Array.isArray(merged.suites) || !merged.suites.length) merged.suites = DEFAULT_SITE.suites.slice();
    return merged;
  } catch (err) {
    console.warn(`[gauntlet] config/site.json is not valid JSON (${(err as Error).message}); using defaults`);
    return { ...DEFAULT_SITE };
  }
}

export function saveSiteConfig(patch: Partial<SiteConfig>): SiteConfig {
  const errors = validateSiteConfig(patch);
  if (errors.length) throw new Error(errors.join('; '));
  const next: SiteConfig = { ...loadSiteConfig(), ...patch };
  writeJsonAtomic(SITE_CONFIG_FILE, next);
  return next;
}
