/**
 * Format-tolerant readers shared by the verifiers. Lenient about presentation (key case and spacing, "£1,234.50"
 * or 1234.5, "Fri" or "Friday night", ids with a description after them), strict about content: a value that
 * offers two alternatives is never read as either of them.
 */

/** "Total (£)" → "total", "from_person" → "fromperson". */
export function normKey(k: string): string {
  return k.toLowerCase().replace(/[^a-z0-9]/g, '');
}

/** The value of the first key of `obj` whose normalised name is one of `names`. */
export function field(obj: unknown, ...names: string[]): unknown {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return undefined;
  const want = names.map(normKey);
  for (const w of want) for (const [k, v] of Object.entries(obj as Record<string, unknown>)) if (normKey(k) === w) return v;
  return undefined;
}

const SPELLING: Array<[RegExp, string]> = [
  [/colour/g, 'color'],
  [/favourite/g, 'favorite'],
  [/organis/g, 'organiz'],
  [/centre/g, 'center'],
  [/metre/g, 'meter'],
  [/litre/g, 'liter'],
  [/programme/g, 'program'],
  [/cheque/g, 'check'],
  [/travell/g, 'travel'],
  [/jewellery/g, 'jewelry'],
  [/grey/g, 'gray'],
  [/yoghurt/g, 'yogurt'],
  [/aluminium/g, 'aluminum'],
];

/** Lower case, British spelling folded to American, punctuation and repeated spaces removed. */
export function normText(s: string): string {
  let t = s.toLowerCase().replace(/[‘’`´]/g, "'").replace(/[^a-z0-9' ]+/g, ' ');
  for (const [re, to] of SPELLING) t = t.replace(re, to);
  return t.replace(/\s+/g, ' ').trim();
}

/** Words that offer an alternative inside one value. */
const HEDGE = /\b(or|either|maybe|possibly|alternatively|between)\b|±|\+\/-|\bto\s+£?\d/i;

/** Whether a string offers alternatives ("£12 or £13", "either T4 or T5"). */
export function hedgedText(s: string): boolean {
  return HEDGE.test(s);
}

/**
 * A money amount in pence, from 12.5, "12.50", "£12.50", "£1,234.56", "1234.56 GBP" or "-£3.20". Null for
 * anything else, including two amounts ("£12 or £13").
 */
export function readPence(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? Math.round(v * 100) : null;
  if (typeof v !== 'string') return null;
  if (hedgedText(v)) return null;
  const t = v.replace(/[−–—]/g, '-').replace(/(\d),(?=\d{3}(?!\d))/g, '$1').replace(/\s+/g, ' ').trim();
  const nums = t.match(/\d+(?:\.\d+)?/g) ?? [];
  if (nums.length !== 1) return null;
  const neg = /-\s*£?\s*\d/.test(t) || /^\(.*\)$/.test(t);
  const pence = Math.round(Number(nums[0]) * 100);
  return neg ? -pence : pence;
}

/** A plain number from 12, "12", "12.5 kg", "1,250"; null for two numbers or none. */
export function readNumber(v: unknown): number | null {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v !== 'string' || hedgedText(v)) return null;
  const t = v.replace(/[−–—]/g, '-').replace(/(\d),(?=\d{3}(?!\d))/g, '$1');
  const nums = t.match(/-?\d+(?:\.\d+)?/g) ?? [];
  return nums.length === 1 ? Number(nums[0]) : null;
}

/** "£1,234.50" from pence. */
export function gbp(pence: number): string {
  const sign = pence < 0 ? '-' : '';
  const abs = Math.abs(Math.round(pence));
  const pounds = Math.floor(abs / 100).toLocaleString('en-GB');
  return `${sign}£${pounds}.${String(abs % 100).padStart(2, '0')}`;
}

/** A list from an array, or from a comma/semicolon/arrow separated string. */
export function readList(v: unknown): unknown[] | null {
  if (Array.isArray(v)) return v;
  if (typeof v === 'string') return v.split(/\s*(?:,|;|→|->|\bthen\b)\s*/i).filter((x) => x.trim());
  return null;
}

/**
 * Which of `ids` a value names: an exact id ("T12", "t12"), or a string that contains exactly one of the ids as a
 * whole word ("T12 09:40 Leeds → York"), or (with `names`) a full name. Null when none or more than one is named.
 */
export function readId(v: unknown, ids: readonly string[], names?: ReadonlyMap<string, string>): string | null {
  if (typeof v === 'number') v = String(v);
  if (v && typeof v === 'object' && !Array.isArray(v)) v = field(v, 'id', 'code', 'service', 'item', 'name', 'hotel', 'product');
  if (typeof v !== 'string') return null;
  const raw = v.trim();
  const lower = raw.toLowerCase();
  for (const id of ids) if (id.toLowerCase() === lower) return id;
  const hits = ids.filter((id) => new RegExp(`(^|[^A-Za-z0-9])${id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![A-Za-z0-9])`, 'i').test(raw));
  if (hits.length === 1) return hits[0]!;
  if (hits.length > 1) return null;
  if (names) {
    const n = normText(raw);
    const byName = [...names.entries()].filter(([, name]) => normText(name) === n || (n.length >= 4 && normText(name).includes(n)));
    if (byName.length === 1) return byName[0]![0];
  }
  return null;
}
