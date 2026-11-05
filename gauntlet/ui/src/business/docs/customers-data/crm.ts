/**
 * Clean the Customer List (work.crm-dedupe) and its ladder (work.crm-ladder): the customer export read from the
 * prompt, the fields that link records, and the model's groups and golden records against the key — right groups,
 * two different people merged, duplicates left in. Pure; tested in test/customers-data-docs.test.ts.
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { normSetId, pick, replyJson, sameValue } from './kit.ts';

export type CrmRecord = Record<string, string> & { id: string };

export interface CrmDoc {
  columns: string[];
  records: CrmRecord[];
  byId: Map<string, CrmRecord>;
  shared: string[];
  key: { clusters: string[][]; golden: Record<string, Record<string, unknown>> } | null;
}

/** One CSV line (quoted fields allowed). */
function csvLine(line: string): string[] {
  const out: string[] = [];
  let cur = '';
  let q = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]!;
    if (q) {
      if (ch === '"' && line[i + 1] === '"') {
        cur += '"';
        i++;
      } else if (ch === '"') q = false;
      else cur += ch;
    } else if (ch === '"') q = true;
    else if (ch === ',') {
      out.push(cur);
      cur = '';
    } else cur += ch;
  }
  out.push(cur);
  return out;
}

export function parseCrm(prompt: string, expected?: unknown): CrmDoc | null {
  const m = prompt.match(/EXPORT \(CSV: ([^)]+)\)\n([\s\S]*?)\n\s*\n/);
  if (!m) return null;
  const columns = m[1]!.split(',').map((c) => c.trim());
  const records: CrmRecord[] = [];
  for (const line of m[2]!.split('\n')) {
    if (!line.trim()) continue;
    const cells = csvLine(line);
    const r = Object.fromEntries(columns.map((c, i) => [c, (cells[i] ?? '').trim()])) as CrmRecord;
    if (r.id) records.push(r);
  }
  if (!records.length) return null;
  const shared = ((prompt.match(/part before the @ is one of ([^.]+?) is a shared mailbox/) ?? [])[1] ?? '').split(/,\s*|\s+or\s+/).map((s) => s.trim()).filter(Boolean);
  const e = expected as { clusters?: string[][]; golden?: Record<string, Record<string, unknown>> } | undefined;
  return { columns, records, byId: new Map(records.map((r) => [normSetId(r.id), r])), shared, key: e?.clusters ? { clusters: e.clusters, golden: e.golden ?? {} } : null };
}

export const phoneDigits = (p: string) => {
  const d = p.replace(/\D/g, '');
  return d.startsWith('44') ? `0${d.slice(2)}` : d;
};

/** The comparable form of a field (blank → null): emails lower-cased, phones as digits from 0, postcodes without spaces. */
export function normField(col: string, v: string): string | null {
  if (!v) return null;
  switch (col) {
    case 'email':
      return v.toLowerCase();
    case 'phone':
      return phoneDigits(v);
    case 'postcode':
      return v.toUpperCase().replace(/\s/g, '');
    case 'address':
      return v.toLowerCase().replace(/[^a-z0-9]/g, '');
    case 'first_name':
    case 'last_name':
      return v.toLowerCase().replace(/[\s'.-]/g, '').replace(/^mc/, 'mac');
    default:
      return v;
  }
}

export const LINK_FIELDS = ['email', 'phone', 'dob', 'postcode', 'address', 'last_name'];

/** For one group of records: each linking field value shared by two or more of them → a colour index. */
export function sharedFields(recs: CrmRecord[]): Map<string, number> {
  const out = new Map<string, number>();
  let n = 0;
  for (const col of LINK_FIELDS) {
    const counts = new Map<string, number>();
    for (const r of recs) {
      const v = normField(col, r[col] ?? '');
      if (v) counts.set(v, (counts.get(v) ?? 0) + 1);
    }
    for (const [v, c] of counts) if (c >= 2) out.set(`${col}|${v}`, n++);
  }
  return out;
}

export interface Group {
  /** The records as the key groups them (a model-only wrong merge lists its records here too). */
  ids: string[];
  kind: 'right' | 'merged-wrong' | 'left-in' | 'split' | 'key-only';
  /** The model's groups that touch these records. */
  model: string[][];
  /** For a wrong merge: the key's people inside it. */
  people?: string[][];
  master: string | null;
  modelMaster: string | null;
  golden: Record<string, unknown> | null;
  modelGolden: Record<string, unknown> | null;
  goldenOk: Record<string, boolean> | null;
}

export interface CrmAnswer {
  groups: Group[];
  rightGroups: number;
  keyGroups: number;
  wrongMerges: number;
  leftIn: number;
  goldenRight: number;
}

function readClusters(v: unknown): string[][] {
  if (!Array.isArray(v)) return [];
  return v.filter(Array.isArray).map((g) => (g as unknown[]).map((x) => String(x)));
}

export function crmAnswer(input: CaseVisualInput, doc: CrmDoc): CrmAnswer | null {
  const j = replyJson(input);
  if (!j || !doc.key) return null;
  const model = readClusters(pick(j, 'clusters', 'groups')).map((g) => g.map(normSetId));
  const golden = (pick(j, 'golden') ?? {}) as Record<string, Record<string, unknown>>;
  const goldenBy = new Map(Object.entries(golden).map(([k, v]) => [normSetId(k), v]));
  const key = doc.key.clusters.map((g) => g.map(normSetId));
  const keyOf = new Map<string, number>();
  key.forEach((g, i) => g.forEach((id) => keyOf.set(id, i)));
  const pretty = (id: string) => doc.byId.get(id)?.id ?? id.toUpperCase();
  const groups: Group[] = [];
  const sameSet = (a: string[], b: string[]) => a.length === b.length && a.every((x) => b.includes(x));
  const shownMerges = new Set<string>();
  key.forEach((g, i) => {
    const touching = model.filter((mg) => mg.some((id) => g.includes(id)));
    const masterKey = Object.keys(doc.key!.golden).find((k) => g.includes(normSetId(k))) ?? null;
    const exact = touching.find((mg) => sameSet(mg, g));
    const merged = touching.some((mg) => mg.some((id) => keyOf.get(id) !== i));
    const modelMasterId = touching.length ? [...goldenBy.keys()].find((k) => touching.some((mg) => mg.includes(k))) ?? null : null;
    const kind: Group['kind'] = exact ? 'right' : merged ? 'merged-wrong' : touching.length ? 'split' : 'left-in';
    let people: string[][] | undefined;
    if (kind === 'merged-wrong') {
      const sig = touching.map((x) => [...x].sort().join('+')).sort().join('|');
      if (shownMerges.has(sig)) return;
      shownMerges.add(sig);
      const by = new Map<string, string[]>();
      for (const id of [...new Set([...touching.flat(), ...g])]) {
        const k = keyOf.has(id) ? `k${keyOf.get(id)}` : `s${id}`;
        by.set(k, [...(by.get(k) ?? []), pretty(id)]);
      }
      people = [...by.values()];
    }
    const kg = masterKey ? doc.key!.golden[masterKey]! : null;
    const mg = modelMasterId ? goldenBy.get(modelMasterId) ?? null : null;
    const goldenOk = kg ? Object.fromEntries(Object.keys(kg).map((f) => [f, !!mg && modelMasterId === normSetId(masterKey!) && sameValue(kg[f], pick(mg, f) ?? null)])) : null;
    groups.push({ ids: people ? people.flat() : g.map(pretty), kind, people, model: touching.map((x) => x.map(pretty)), master: masterKey, modelMaster: modelMasterId ? pretty(modelMasterId) : null, golden: kg, modelGolden: mg, goldenOk });
  });
  // Model groups that merge people the key keeps apart and are not already shown under a key group.
  for (const mg of model) {
    const keys = new Set(mg.map((id) => keyOf.get(id) ?? -1));
    if (keys.size === 1 && !keys.has(-1)) continue;
    if (mg.some((id) => keyOf.has(id))) continue;
    const people = mg.map((id) => [pretty(id)]);
    groups.push({ ids: mg.map(pretty), kind: 'merged-wrong', model: [mg.map(pretty)], people, master: null, modelMaster: null, golden: null, modelGolden: null, goldenOk: null });
  }
  const order: Record<Group['kind'], number> = { 'merged-wrong': 0, 'left-in': 1, split: 2, 'key-only': 3, right: 4 };
  groups.sort((a, b) => order[a.kind] - order[b.kind]);
  return {
    groups,
    rightGroups: groups.filter((g) => g.kind === 'right').length,
    keyGroups: key.length,
    wrongMerges: groups.filter((g) => g.kind === 'merged-wrong').length,
    leftIn: groups.filter((g) => g.kind === 'left-in' || g.kind === 'split').length,
    goldenRight: groups.filter((g) => g.goldenOk && Object.values(g.goldenOk).every(Boolean)).length,
  };
}

/** Why the key keeps records of a wrong merge apart (different birthdays, a shared mailbox, a "not the same person" note). */
export function apartReason(doc: CrmDoc, ids: string[]): string | null {
  const recs = ids.map((id) => doc.byId.get(normSetId(id))).filter((r): r is CrmRecord => !!r);
  const dobs = new Set(recs.map((r) => r.dob).filter(Boolean));
  if (dobs.size > 1) return `Different dates of birth (${[...dobs].join(', ')}): the rules say they are never the same customer.`;
  const note = recs.find((r) => /not the same person/i.test(r.notes ?? ''));
  if (note) return `Staff note on ${note.id}: “${note.notes}”.`;
  const mail = recs.map((r) => r.email.split('@')[0]?.toLowerCase() ?? '').find((l) => doc.shared.includes(l));
  if (mail) return `The only link is a shared mailbox (${mail}@…), which never links records on its own.`;
  const first = new Set(recs.map((r) => normField('first_name', r.first_name ?? '')));
  if (first.size > 1) return `Different first names (${recs.map((r) => r.first_name).join(', ')}) that are not nicknames of each other.`;
  return null;
}
