/**
 * Knights, Knaves, Spies & Alternators: the islanders in a row, each with
 * their statements, the true role from the answer key and the model's verdict.
 */
import { splitList, statedAnswer, type CaseVisualInput } from './common.ts';

export type IslandRole = 'knight' | 'knave' | 'spy' | 'alternator';
const ROLES: IslandRole[] = ['knight', 'knave', 'spy', 'alternator'];

export interface Islander {
  name: string;
  statements: string[];
  truth: IslandRole;
  /** The model's verdict, or null when it gave none for this person. */
  model: IslandRole | null;
  ok: boolean;
}

export interface IslandVisual {
  roles: IslandRole[];
  people: Islander[];
  answered: boolean;
  right: number;
  /** "Exactly one of them is a spy" style rule line, when stated. */
  rule: string | null;
}

/** "Ada: knave, Bruno: knight" or "knave, knight" (in introduction order) → roles. */
export function parseAssignment(text: string, names: string[]): Map<string, IslandRole> | null {
  const out = new Map<string, IslandRole>();
  const pairs = [...text.matchAll(/([A-Z][a-z]+)\s*[:=-]\s*(knight|knave|spy|alternator)s?\b/gi)];
  if (pairs.length) {
    for (const p of pairs) {
      const name = names.find((n) => n.toLowerCase() === p[1]!.toLowerCase());
      if (name) out.set(name, p[2]!.toLowerCase() as IslandRole);
    }
    return out.size ? out : null;
  }
  const bare = text.split(/[,;]\s*/).map((s) => s.trim().toLowerCase());
  if (bare.length === names.length && bare.every((b) => (ROLES as string[]).includes(b))) {
    names.forEach((n, i) => out.set(n, bare[i] as IslandRole));
    return out;
  }
  return null;
}

export function parseIsland(prompt: string): { names: string[]; roles: IslandRole[]; statements: Map<string, string[]>; rule: string | null } | null {
  const roles = ROLES.filter((r) => new RegExp(`^- An? ${r}:`, 'm').test(prompt));
  if (roles.length < 2) return null;
  const meet = prompt.match(/You meet \w+ inhabitants?:\s*([^.]+)\./);
  if (!meet) return null;
  const names = splitList(meet[1]!);
  if (names.length < 2 || names.some((n) => !/^[A-Z][a-z]+$/.test(n))) return null;
  const rule = (prompt.match(/(Exactly one of them is a spy[^.]*\.|There may be any number of each type[^.]*\.)/) ?? [])[1] ?? null;
  const start = prompt.indexOf('\nStatements:');
  if (start < 0) return null;
  const block = prompt.slice(start + 12).split(/\n\s*\n/)[0]!;
  const statements = new Map<string, string[]>();
  for (const line of block.split('\n')) {
    const said = line.match(/^([A-Z][a-z]+) says: "(.*)"\s*$/);
    if (said) {
      statements.set(said[1]!, [...(statements.get(said[1]!) ?? []), said[2]!]);
      continue;
    }
    const many = line.match(/^([A-Z][a-z]+) makes \w+ statements, in this order: (.*)$/);
    if (many) {
      const list = [...many[2]!.matchAll(/\((\d+)\)\s*"([^"]*)"/g)].map((m) => m[2]!);
      if (!list.length) return null;
      statements.set(many[1]!, [...(statements.get(many[1]!) ?? []), ...list]);
    }
  }
  if (!statements.size) return null;
  return { names, roles, statements, rule };
}

export function islandVisual(input: CaseVisualInput): IslandVisual | null {
  const parsed = parseIsland(input.turns[0] ?? '');
  if (!parsed) return null;
  const exp = Array.isArray(input.expected) ? String(input.expected[0] ?? '') : typeof input.expected === 'string' ? input.expected : '';
  const truth = parseAssignment(exp, parsed.names);
  if (!truth || truth.size !== parsed.names.length) return null;
  const stated = statedAnswer(input);
  const model = stated ? parseAssignment(stated.answer, parsed.names) : null;
  const people: Islander[] = parsed.names.map((name) => {
    const m = model?.get(name) ?? null;
    return { name, statements: parsed.statements.get(name) ?? [], truth: truth.get(name)!, model: m, ok: m === truth.get(name) };
  });
  const right = people.filter((p) => p.ok).length;
  // Never contradict the scorer.
  if (input.passed === true && right !== people.length) return null;
  return { roles: parsed.roles, people, answered: !!model, right, rule: parsed.rule };
}

export function islandHeadline(v: IslandVisual): string {
  if (!v.answered) return 'No verdict given';
  const wrong = v.people.filter((p) => !p.ok);
  if (!wrong.length) return `Unmasked all ${v.people.length} islanders`;
  if (wrong.length === 1) {
    const p = wrong[0]!;
    return p.model ? `Called ${p.name} a ${p.model}, but ${p.name} is a ${p.truth}` : `Gave no verdict for ${p.name} (a ${p.truth})`;
  }
  return `${wrong.length} of ${v.people.length} islanders misjudged`;
}
