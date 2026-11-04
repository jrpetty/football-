// Prints how many marking points each case of the business pack carries, and its prompt size:
// node verification/realworld-business/points.mts
import { loadTests } from '../../src/core/registry.ts';
import { compareJsonSets } from '../../src/scoring/json-sets.ts';
import type { PromptTest } from '../../src/core/types.ts';

for (const t of loadTests().filter((x) => /^work\.(invoice-matching|support-refunds|cv-shortlist|payroll-check|crm-dedupe)$/.test(x.definition.id))) {
  const def = t.definition as PromptTest;
  const sc = def.scorer as { sets?: string[]; partitions?: string[] };
  const rows = def.cases.map((c) => {
    const r = compareJsonSets(c.expected, c.expected, { sets: sc.sets, partitions: sc.partitions, lenient: true });
    return `${c.id} ${Math.round(r.total)}pts ~${Math.ceil(c.prompt!.length / 3.6)}tok`;
  });
  console.log(`${def.id} (est. input ${def.estimate?.inputTokens} tok/case)\n  ${rows.join(' | ')}`);
}
