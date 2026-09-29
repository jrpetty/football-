/**
 * Copy & paste models: the catalogue's types and the rules that turn "Claude 3 Opus, pasted into claude.ai with
 * thinking off" into a contestant.
 *
 * Pure functions only (no file access), so the UI imports this file too: the picker, the Inbox card and the
 * server all describe a model's availability and name a contestant the same way.
 *
 * Identity rules:
 *   id     manual.<catalog id>.<interface>[.think-on|.think-off][.web]      e.g. manual.claude-3-opus.company-app
 *   model  <catalog id>@<interface>+thinking-<default|on|off>+web-<on|off> (the config hash covers it, so a model used
 *          through a different app or with different settings never silently mixes with another)
 *   vendor the catalogue vendor, so judges from the same company are still excluded
 */
import type { Contestant, ManualInterface, ManualModelInfo } from '../core/types.ts';

export type CatalogStatus = 'available' | 'deprecated' | 'retired' | 'unverified';

export interface CatalogAccess {
  /** On the company's own API: yes / no / by request / still works until its shutdown date / not known. */
  api: 'yes' | 'no' | 'by-request' | 'until-shutdown' | 'unknown';
  /** In the company's own chat app: yes / no / paid plans only / not known. */
  chatApp: 'yes' | 'no' | 'paid-plans' | 'unknown';
  /** Weights are public (so any host can still serve it). */
  openWeights?: boolean;
  /** Places it MIGHT still be reachable. Not checked: always shown with "(not checked)". */
  mayStillBeOn?: string[];
}

export interface CatalogModel {
  id: string;
  label: string;
  vendor: string;
  family: string;
  /** YYYY-MM */
  released: string;
  /** YYYY-MM-DD when the exact day is known. */
  releaseDate?: string;
  apiModelId?: string;
  status: CatalogStatus;
  /** When it left the company's API (YYYY-MM-DD, or YYYY-MM when only the month is known). */
  retiredDate?: string;
  /** Deprecated models: the announced shutdown date. */
  shutdownDate?: string;
  access: CatalogAccess;
  vision: boolean;
  reasoning: boolean;
  tier?: 'flagship' | 'mid' | 'small';
  notes?: string;
  source: string;
  /** Day the entry was checked against its source; null when it could not be confirmed. */
  verifiedAt: string | null;
  /** Added by the owner ("Suggest a model not in the list"): kept in the user folder, never verified. */
  custom?: boolean;
}

export interface CatalogVendor {
  id: string;
  chatApp: string;
  chatAppUrl?: string;
  playground?: string;
  color: string;
}

export interface ModelCatalog {
  checkedAt: string;
  vendors: CatalogVendor[];
  models: CatalogModel[];
}

export const STATUSES: CatalogStatus[] = ['available', 'deprecated', 'retired', 'unverified'];

export const INTERFACES: Array<{ id: ManualInterface; label: string; hint: string }> = [
  { id: 'company-app', label: 'The company’s chat app', hint: 'claude.ai, ChatGPT, the Gemini app, grok.com…' },
  { id: 'api-playground', label: 'API playground / console', hint: 'Claude Console, OpenAI Playground, Google AI Studio…' },
  { id: 'openrouter', label: 'OpenRouter chat', hint: 'openrouter.ai/chat' },
  { id: 'poe', label: 'Poe', hint: 'poe.com' },
  { id: 'arena', label: 'Arena site', hint: 'LMArena and similar side-by-side sites' },
  { id: 'cloud', label: 'Cloud console', hint: 'AWS Bedrock, Google Vertex AI, Azure' },
  { id: 'other', label: 'Other app', hint: 'Anything else (say which in the note)' },
];

const INTERFACE_IDS = new Set(INTERFACES.map((i) => i.id));
export const isInterface = (v: unknown): v is ManualInterface => typeof v === 'string' && INTERFACE_IDS.has(v as ManualInterface);

/** Short name of the interface for labels, e.g. "claude.ai", "API", "OpenRouter". */
export function interfaceShort(iface: ManualInterface, vendor?: CatalogVendor): string {
  switch (iface) {
    case 'company-app':
      return vendor?.chatApp ?? 'chat app';
    case 'api-playground':
      return 'API playground';
    case 'openrouter':
      return 'OpenRouter';
    case 'poe':
      return 'Poe';
    case 'arena':
      return 'arena site';
    case 'cloud':
      return 'cloud console';
    default:
      return 'other app';
  }
}

export function normalizeInfo(info: Partial<ManualModelInfo> & { catalogId: string }): ManualModelInfo {
  const thinking = info.thinking === 'on' || info.thinking === 'off' ? info.thinking : 'default';
  const note = typeof info.note === 'string' && info.note.trim() ? info.note.trim().slice(0, 200) : undefined;
  return { catalogId: info.catalogId, interface: isInterface(info.interface) ? info.interface : 'company-app', thinking, webSearch: info.webSearch === true, ...(note ? { note } : {}) };
}

export function manualContestantId(info: ManualModelInfo): string {
  return `manual.${info.catalogId}.${info.interface}${info.thinking !== 'default' ? `.think-${info.thinking}` : ''}${info.webSearch ? '.web' : ''}`;
}

/** What goes in the contestant's `model` field: every part of the identity, so it is part of the config hash. */
export function manualModelString(info: ManualModelInfo): string {
  return `${info.catalogId}@${info.interface}+thinking-${info.thinking}+web-${info.webSearch ? 'on' : 'off'}`;
}

/** Settings in words, e.g. "thinking off · web search ON" ('' when all defaults). */
export function settingsText(info: Pick<ManualModelInfo, 'thinking' | 'webSearch'>): string {
  const parts: string[] = [];
  if (info.thinking !== 'default') parts.push(`thinking ${info.thinking}`);
  if (info.webSearch) parts.push('web search ON');
  return parts.join(' · ');
}

/** Contestant label, e.g. "Claude 3 Opus (claude.ai)" or "Claude 3 Opus (API playground, thinking off)". */
export function manualLabel(model: Pick<CatalogModel, 'label'>, info: ManualModelInfo, vendor?: CatalogVendor): string {
  const bits = [interfaceShort(info.interface, vendor)];
  if (info.thinking !== 'default') bits.push(`thinking ${info.thinking}`);
  if (info.webSearch) bits.push('web search on');
  return `${model.label} (${bits.join(', ')})`;
}

/** "copied by hand · claude.ai" (+ settings) for badges wherever a result appears. */
export function copiedByHandText(info: ManualModelInfo, vendor?: CatalogVendor): string {
  const s = settingsText(info);
  return `copied by hand · ${interfaceShort(info.interface, vendor)}${s ? ` · ${s}` : ''}`;
}

function hexToRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.replace('#', ''), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
const toHex = (v: number) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0');

function hash32(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

/** The vendor's colour, lightened or darkened a little per model so several models of one company stay tellable apart. */
export function manualColor(vendorColor: string, key: string): string {
  const base = /^#[0-9a-fA-F]{6}$/.test(vendorColor) ? vendorColor : '#64748B';
  const t = ((hash32(key) % 9) - 4) / 14; // -0.29 .. +0.29
  const [r, g, b] = hexToRgb(base);
  const mix = (c: number) => (t >= 0 ? c + (255 - c) * t : c * (1 + t));
  return `#${toHex(mix(r))}${toHex(mix(g))}${toHex(mix(b))}`;
}

/** YYYY-MM-DD for the History chart: the exact day when known, else the 1st of the release month. */
export function releaseDay(m: Pick<CatalogModel, 'released' | 'releaseDate'>): string {
  return m.releaseDate ?? `${m.released}-01`;
}

/** Build the contestant for a catalogue model used through an interface with some settings. */
export function buildManualContestant(model: CatalogModel, infoIn: ManualModelInfo, vendor?: CatalogVendor): Contestant {
  const info = normalizeInfo(infoIn);
  const id = manualContestantId(info);
  return {
    id,
    label: manualLabel(model, info, vendor),
    vendor: model.vendor,
    provider: 'manual',
    model: manualModelString(info),
    color: manualColor(vendor?.color ?? '#64748B', id),
    enabled: true,
    pricing: { inputPerM: 0, outputPerM: 0, source: 'manual — copied by hand (cost entered in the Manual Inbox, if known)' },
    vision: model.vision,
    family: model.family,
    releaseDate: releaseDay(model),
    ...(model.tier ? { tier: model.tier } : {}),
    notes: `Copy & paste contestant from the model catalogue. ${availabilityText(model, vendor)}`,
    manualModel: info,
  };
}

// ───────────────────────────── Availability in plain words ─────────────────────────────

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "5 Jan 2026" / "Jul 2025" (UK style) from YYYY-MM-DD or YYYY-MM. */
export function fmtCatalogDate(d: string | undefined | null): string {
  if (!d) return '';
  const m = /^(\d{4})-(\d{2})(?:-(\d{2}))?$/.exec(d);
  if (!m) return d;
  const mon = MONTHS[Number(m[2]) - 1] ?? m[2];
  return m[3] ? `${Number(m[3])} ${mon} ${m[1]}` : `${mon} ${m[1]}`;
}

export interface AvailabilityBadge {
  tone: 'good' | 'warn' | 'bad' | 'muted';
  text: string;
}

/** The status badge for the picker: "Available", "Shuts down 23 Oct 2026", "Retired", "Unverified". */
export function statusBadge(m: Pick<CatalogModel, 'status' | 'shutdownDate' | 'retiredDate' | 'custom'>): AvailabilityBadge {
  if (m.custom) return { tone: 'muted', text: 'Added by you · unverified' };
  switch (m.status) {
    case 'available':
      return { tone: 'good', text: 'Available' };
    case 'deprecated':
      return { tone: 'warn', text: m.shutdownDate ? `Shuts down ${fmtCatalogDate(m.shutdownDate)}` : 'Deprecated' };
    case 'retired':
      return { tone: 'bad', text: m.retiredDate ? `Retired ${fmtCatalogDate(m.retiredDate)}` : 'Retired' };
    default:
      return { tone: 'muted', text: 'Unverified' };
  }
}

/**
 * One honest sentence about where a model can be used today, e.g.
 * "Retired from Anthropic’s API on 5 Jan 2026 · still in claude.ai on paid plans · API access by request · may still be on AWS Bedrock (not checked)".
 */
export function availabilityText(m: CatalogModel, vendor?: CatalogVendor): string {
  const app = vendor?.chatApp ?? `${m.vendor}’s chat app`;
  const parts: string[] = [];
  if (m.custom) parts.push('Added by you: availability not checked');
  else if (m.status === 'unverified') parts.push('Unverified: we could not confirm where it is available today');
  else if (m.status === 'retired') parts.push(m.retiredDate ? `Retired from ${m.vendor}’s API on ${fmtCatalogDate(m.retiredDate)}` : `Retired from ${m.vendor}’s API`);
  else if (m.status === 'deprecated') parts.push(m.shutdownDate ? `Still works, but ${m.vendor}’s API shuts it down on ${fmtCatalogDate(m.shutdownDate)}` : 'Deprecated: still works for now');
  else parts.push(m.access.api === 'yes' ? `Available on ${m.vendor}’s API` : 'Available');
  if (m.access.chatApp === 'yes') parts.push(`in ${app}`);
  else if (m.access.chatApp === 'paid-plans') parts.push(`still in ${app} on paid plans`);
  else if (m.access.chatApp === 'no' && m.status !== 'retired') parts.push(`not in ${app}`);
  if (m.access.api === 'by-request') parts.push('API access by request');
  if (m.access.openWeights) parts.push('open weights (many hosts)');
  if (m.access.mayStillBeOn?.length) parts.push(`may still be on ${m.access.mayStillBeOn.join(', ')} (not checked)`);
  return parts.join(' · ');
}

/** Can the owner still reach it at all (for sorting "usable today" first in a picker)? */
export function isReachable(m: CatalogModel): boolean {
  return m.status === 'available' || m.status === 'deprecated' || m.access.chatApp === 'paid-plans' || m.access.api === 'by-request' || Boolean(m.access.openWeights);
}

/** The best interface to suggest for a model: its company's app when that works, else the API playground, else OpenRouter. */
export function suggestedInterface(m: CatalogModel): ManualInterface {
  if (m.access.chatApp === 'yes' || m.access.chatApp === 'paid-plans') return 'company-app';
  if (m.access.api === 'yes' || m.access.api === 'until-shutdown' || m.access.api === 'by-request') return 'api-playground';
  return 'openrouter';
}

// ───────────────────────────── Validation ─────────────────────────────

const ID_RE = /^[a-z0-9][a-z0-9.-]{0,39}$/;
const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;
const DAY_RE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;
const validDay = (d: string) => DAY_RE.test(d) && !Number.isNaN(Date.parse(`${d}T00:00:00Z`)) && new Date(`${d}T00:00:00Z`).toISOString().startsWith(d);
const API = new Set(['yes', 'no', 'by-request', 'until-shutdown', 'unknown']);
const APP = new Set(['yes', 'no', 'paid-plans', 'unknown']);

/** Every problem with one catalogue entry (empty = fine). */
export function validateCatalogModel(m: CatalogModel, vendors: CatalogVendor[], checkedAt?: string): string[] {
  const e: string[] = [];
  const where = `model "${m?.id ?? '?'}"`;
  if (!m || typeof m !== 'object') return ['entry must be an object'];
  if (!ID_RE.test(m.id ?? '')) e.push(`${where}: id must be lowercase letters, digits, "." or "-" (max 40)`);
  for (const k of ['label', 'vendor', 'family', 'source'] as const) if (typeof m[k] !== 'string' || !m[k].trim()) e.push(`${where}: ${k} is required`);
  if (m.vendor && !vendors.some((v) => v.id === m.vendor)) e.push(`${where}: vendor "${m.vendor}" is not in the vendors list`);
  if (!MONTH_RE.test(m.released ?? '')) e.push(`${where}: released must be YYYY-MM`);
  if (m.releaseDate !== undefined && (!validDay(m.releaseDate) || !m.releaseDate.startsWith(m.released))) e.push(`${where}: releaseDate must be a real YYYY-MM-DD inside the released month`);
  if (checkedAt && MONTH_RE.test(m.released ?? '') && m.released > checkedAt.slice(0, 7)) e.push(`${where}: released is after the catalogue was checked`);
  if (!STATUSES.includes(m.status)) e.push(`${where}: status must be ${STATUSES.join(', ')}`);
  for (const k of ['retiredDate', 'shutdownDate'] as const) {
    const v = m[k];
    if (v !== undefined && !(validDay(v) || MONTH_RE.test(v))) e.push(`${where}: ${k} must be YYYY-MM-DD or YYYY-MM`);
  }
  if (m.status === 'deprecated' && !m.shutdownDate) e.push(`${where}: a deprecated model needs its shutdownDate`);
  if (m.status !== 'retired' && m.retiredDate) e.push(`${where}: only retired models have a retiredDate`);
  if (!m.access || !API.has(m.access.api) || !APP.has(m.access.chatApp)) e.push(`${where}: access.api / access.chatApp missing or invalid`);
  if (m.access?.mayStillBeOn !== undefined && !(Array.isArray(m.access.mayStillBeOn) && m.access.mayStillBeOn.every((x) => typeof x === 'string' && x.trim()))) e.push(`${where}: access.mayStillBeOn must be a list of names`);
  if (typeof m.vision !== 'boolean' || typeof m.reasoning !== 'boolean') e.push(`${where}: vision and reasoning must be true or false`);
  if (m.tier !== undefined && !['flagship', 'mid', 'small'].includes(m.tier)) e.push(`${where}: tier must be flagship, mid or small`);
  // Honesty rule: an entry either says when it was checked, or says it is unverified.
  if (m.verifiedAt === null) {
    if (m.status !== 'unverified' && !m.custom) e.push(`${where}: an entry that was not verified must have status "unverified"`);
  } else if (typeof m.verifiedAt !== 'string' || !validDay(m.verifiedAt)) e.push(`${where}: verifiedAt must be YYYY-MM-DD or null`);
  else if (m.status === 'unverified') e.push(`${where}: status "unverified" must have verifiedAt null`);
  if (manualContestantId({ catalogId: m.id ?? '', interface: 'api-playground', thinking: 'off', webSearch: true }).length > 64) e.push(`${where}: id too long for a contestant id`);
  return e;
}

export function validateCatalog(c: ModelCatalog): string[] {
  const errors: string[] = [];
  if (!c || !Array.isArray(c.models) || !Array.isArray(c.vendors)) return ['catalogue must have "vendors" and "models" lists'];
  if (!validDay(c.checkedAt ?? '')) errors.push('checkedAt must be YYYY-MM-DD');
  const vids = new Set<string>();
  for (const v of c.vendors) {
    if (!v.id || vids.has(v.id)) errors.push(`vendor "${v.id}" is missing or duplicated`);
    vids.add(v.id);
    if (!/^#[0-9a-fA-F]{6}$/.test(v.color ?? '')) errors.push(`vendor "${v.id}": color must be #RRGGBB`);
    if (!v.chatApp?.trim()) errors.push(`vendor "${v.id}": chatApp is required`);
  }
  const ids = new Set<string>();
  for (const m of c.models) {
    if (ids.has(m.id)) errors.push(`model "${m.id}" appears twice`);
    ids.add(m.id);
    errors.push(...validateCatalogModel(m, c.vendors, c.checkedAt));
  }
  return errors;
}

/** Catalogue sorted for a picker: grouped by vendor (vendor order kept), newest first inside each vendor. */
export function groupedCatalog(c: ModelCatalog, models = c.models): Array<{ vendor: CatalogVendor; models: CatalogModel[] }> {
  const vendors = [...c.vendors];
  for (const m of models) if (!vendors.some((v) => v.id === m.vendor)) vendors.push({ id: m.vendor, chatApp: `${m.vendor} chat app`, color: '#64748B' });
  return vendors
    .map((vendor) => ({
      vendor,
      models: models.filter((m) => m.vendor === vendor.id).sort((a, b) => releaseDay(b).localeCompare(releaseDay(a)) || a.label.localeCompare(b.label)),
    }))
    .filter((g) => g.models.length > 0);
}

/** Case-insensitive search over label, id, vendor, family and API id. */
export function matchesSearch(m: CatalogModel, q: string): boolean {
  const t = q.trim().toLowerCase();
  if (!t) return true;
  const hay = `${m.label} ${m.id} ${m.vendor} ${m.family} ${m.apiModelId ?? ''} ${m.released}`.toLowerCase();
  return t.split(/\s+/).every((w) => hay.includes(w));
}

// ───────────────────────────── API shapes (src/manual-models/reassign.ts) ─────────────────────────────

export interface ReassignOutcome {
  moved: number;
  skipped: Array<{ key: string; reason: string }>;
  to: { id: string; label: string };
}

/** Copy & paste results that don't say which model made them, grouped by run, contestant and test. */
export interface UnspecifiedGroup {
  runId: string;
  runName: string;
  createdAt: string;
  contestantId: string;
  contestantLabel: string;
  testId: string;
  testName: string;
  keys: string[];
  scored: number;
  lastAt: string;
}
