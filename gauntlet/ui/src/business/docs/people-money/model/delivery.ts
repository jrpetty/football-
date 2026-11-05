/**
 * Check the Delivery (work.delivery-note): our purchase order, the supplier's delivery note (and any second note
 * the same day) and our goods-in check, lined up per ordered product; and the model's count per line (received,
 * damaged, short, over, substitute) against the key.
 */
import type { CaseVisualInput } from '../../../../../../src/presenter/visuals/common.ts';
import { fenced, idsOf, isObj, keyOf, looseEq, pick, readAnswer } from './common.ts';

export interface PoItem {
  sku: string;
  item: string;
  pack: string;
  ordered: string;
}
export interface DnLine {
  note: string;
  sku: string;
  item: string;
  qty: string;
  flag: string | null;
}
export interface DeliveryQuestion {
  site: string;
  poId: string;
  poDate: string;
  orderedBy: string;
  po: PoItem[];
  notes: Array<{ id: string; head: string; lines: DnLine[]; footer: string[] }>;
  check: string[];
}

export function deliveryQuestion(prompt: string): DeliveryQuestion | null {
  const po = fenced(prompt, /Purchase order:/);
  const dn = fenced(prompt, /Supplier’s delivery note:/);
  const gi = fenced(prompt, /Our goods-in check[^\n]*/);
  if (!po || !dn) return null;
  const site = prompt.match(/I run the stockroom at ([^.]+)\./)?.[1] ?? '';
  const ph = po.split('\n')[0] ?? '';
  const items: PoItem[] = po
    .split('\n')
    .filter((l) => l.split('|').length === 4 && !/^SKU\s*\|/.test(l))
    .map((l) => {
      const [sku, item, pack, ordered] = l.split('|').map((x) => x.trim());
      return { sku: sku!, item: item!, pack: pack!, ordered: ordered! };
    });
  const notes: DeliveryQuestion['notes'] = [];
  for (const line of dn.split('\n')) {
    const h = line.match(/^(?:SECOND )?DELIVERY NOTE (\S+)/);
    if (h) {
      notes.push({ id: h[1]!, head: line.trim(), lines: [], footer: [] });
      continue;
    }
    const cur = notes[notes.length - 1];
    if (!cur || !line.trim() || /^SKU\s*\|/.test(line)) continue;
    const parts = line.split('|').map((x) => x.trim());
    if (parts.length >= 3) {
      const flag = parts[2]!.match(/\*\*\s*(.+?)\s*\*\*/)?.[1] ?? parts[2]!.match(/\((.+)\)/)?.[1] ?? null;
      cur.lines.push({ note: cur.id, sku: parts[0]!, item: parts[1]!, qty: parts[2]!.replace(/\*\*.*\*\*/, '').replace(/\(.*\)/, '').trim(), flag });
    } else cur.footer.push(line.trim());
  }
  return {
    site,
    poId: ph.match(/PURCHASE ORDER (\S+)/)?.[1] ?? '',
    poDate: ph.match(/Date:\s*(\S+)/)?.[1] ?? '',
    orderedBy: po.match(/Ordered by:\s*(.+)$/m)?.[1]?.trim() ?? '',
    po: items,
    notes,
    check: (gi ?? '').split('\n').map((l) => l.trim()).filter(Boolean),
  };
}

/** The delivery-note lines and goods-in remarks that concern one ordered product. */
export function linesFor(q: DeliveryQuestion, it: PoItem): { dn: DnLine[]; check: string[] } {
  const dn = q.notes.flatMap((n) => n.lines).filter((l) => l.sku === it.sku || (l.flag ?? '').includes(it.sku));
  const name = it.item.split(' (')[0]!.toLowerCase();
  const check = q.check.filter((c) => c.toLowerCase().includes(name) || c.includes(it.sku));
  return { dn, check };
}

export const DN_FIELDS = ['received', 'damaged', 'short', 'over', 'substitute'] as const;
export type DnField = (typeof DN_FIELDS)[number];

export interface DnRow {
  item: PoItem;
  want: Record<DnField, unknown>;
  got: Record<DnField, unknown> | null;
  ok: Record<DnField, boolean | null>;
  right: boolean;
}

export interface DnAnswer {
  answered: boolean;
  rows: DnRow[];
  extraWant: string[];
  extraGot: string[];
  extraOk: boolean | null;
}

export function deliveryAnswer(q: DeliveryQuestion, input: CaseVisualInput): DnAnswer {
  const key = keyOf(input) ?? {};
  const ra = input.replies.length ? readAnswer(input) : null;
  const ans = ra?.parsed;
  const answered = isObj(ans);
  const klines = isObj(key.lines) ? key.lines : {};
  const mlines = answered ? pick(ans, 'lines') : undefined;
  const rows = q.po.map((item): DnRow => {
    const k = isObj(klines[item.sku]) ? (klines[item.sku] as Record<string, unknown>) : {};
    const m = isObj(mlines) ? pick(mlines, item.sku) : undefined;
    const want = Object.fromEntries(DN_FIELDS.map((f) => [f, k[f]])) as Record<DnField, unknown>;
    const got = isObj(m) ? (Object.fromEntries(DN_FIELDS.map((f) => [f, pick(m, f)])) as Record<DnField, unknown>) : null;
    const ok = Object.fromEntries(DN_FIELDS.map((f) => [f, !answered ? null : (ra!.verdict(`lines.${item.sku}.${f}`) ?? (got ? looseEq(want[f], got[f]) : false))])) as Record<DnField, boolean | null>;
    return { item, want, got, ok, right: answered && DN_FIELDS.every((f) => ok[f]) };
  });
  const extraWant = (Array.isArray(key.not_ordered) ? key.not_ordered : []).map(String);
  const extraGot = answered ? idsOf(pick(ans, 'not_ordered')).map((x) => x.toUpperCase()) : [];
  const norm = (xs: string[]) => xs.map((x) => x.replace(/[\s-]/g, '').toUpperCase()).sort().join();
  return { answered, rows, extraWant, extraGot, extraOk: answered ? norm(extraWant) === norm(extraGot) : null };
}

export function deliverySentence(a: DnAnswer): string {
  if (!a.answered) return 'No answer could be read from the reply, so nothing scored.';
  const wrong = a.rows.filter((r) => !r.right);
  if (!wrong.length && a.extraOk) return `Counted every one of the ${a.rows.length} lines exactly, in single units.`;
  const bits = wrong.slice(0, 2).map((r) => {
    const f = DN_FIELDS.find((x) => !r.ok[x])!;
    return `${r.item.item.split(' (')[0]} (${f} ${String(r.got?.[f] ?? 'missing')}, should be ${String(r.want[f] ?? 'none')})`;
  });
  return `Counted ${a.rows.length - wrong.length} of ${a.rows.length} lines right${bits.length ? `; wrong: ${bits.join(' and ')}${wrong.length > 2 ? ` and ${wrong.length - 2} more` : ''}` : ''}${a.extraOk === false ? `; ${a.extraWant.length ? `missed the unordered ${a.extraWant.join(', ')}` : 'listed something as not ordered that was'}` : ''}.`;
}
