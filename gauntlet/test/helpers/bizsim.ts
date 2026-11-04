/**
 * Helpers for the business work-world simulation tests (accounts clerk, month-end, helpdesk, support week):
 * play a scripted run, swap one scripted action, add actions before the last one, restyle actions.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type { ProgramDefinition } from '../../src/core/types.ts';
import { checkReply } from '../../src/scoring/integrity.ts';
import { createFakeModel, createTestContext, type Responder } from './fake-model.ts';

export interface TestFile {
  id: string;
  seeds: number[];
  config: Record<string, unknown>;
}
export const loadTest = (f: string): TestFile => JSON.parse(readFileSync(new URL(`../../tests/work/${f}.json`, import.meta.url), 'utf8'));
export const fingerprints = (): Record<string, string> => JSON.parse(readFileSync(new URL('../../verification/business-sims/worlds.json', import.meta.url), 'utf8'));

export const scripted = (s: string[]): Responder => (_a, _b, _c, info) => s[info.index] ?? 'ACTION: FINISH';

export interface Detail {
  tasks: Array<{ id: string; label: string; level: string; outcome: string; value: number; checks: Array<{ label: string; ok: boolean }> }>;
  violations: Array<{ severity: string; rule: string }>;
  penalties: Array<{ kind: string; rule: string }>;
  reported: string[];
  actionsUsed: number;
  actionLog: Array<{ n: number; ok: boolean; line: string; note?: string }>;
  components: { task: number; bonus: number };
}

export async function playWith(program: ProgramDefinition, t: TestFile, seed: number, responder: Responder) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config: t.config });
  const result = await program.run(ctx);
  const d = result.detail as unknown as Detail;
  return { result, model, d, score: Math.round(result.score * 1000) / 10, task: (id: string) => d.tasks.find((x) => x.id === id)! };
}

/** Replace the first scripted action whose ACTION line starts with `startsWith` (null removes it). */
export function swap(script: string[], startsWith: string, replacement: string | null): string[] {
  const i = script.findIndex((s) => s.replace(/^MEMO:[^\n]*\n/, '').startsWith(startsWith));
  assert.ok(i >= 0, `no scripted action starts with ${startsWith}`);
  const out = script.slice();
  if (replacement === null) out.splice(i, 1);
  else out[i] = replacement;
  return out;
}

/** Insert actions just before the script's last action (FINISH / the final END_DAY). */
export const beforeLast = (script: string[], ...extra: string[]) => [...script.slice(0, -1), ...extra, script[script.length - 1]!];

/** Markdown, lower-case action names with spaces instead of underscores, "£1,234.56" money. */
export function restyle(line: string): string {
  return line
    .replace(/^ACTION: ([A-Z_]+)/m, (_m, name: string) => `**Action:** ${name.toLowerCase().replace(/_/g, ' ')}`)
    .replace(/(\| ?)(\d{4,})\.(\d{2})\b/g, (_m, p: string, a: string, b: string) => `${p}£${Number(a).toLocaleString('en-GB')}.${b}`);
}

/** No scripted reply trips the fair-play check. */
export function assertFairPlay(model: ReturnType<typeof createFakeModel>): void {
  const task = [model.calls[0]!.system ?? '', ...model.calls.flatMap((c) => c.messages.filter((m) => m.role === 'user').map((m) => m.content))].join('\n\n');
  for (const c of model.calls) assert.equal(checkReply(c.reply.text, { taskText: task, codeClaims: false }), null, c.reply.text.slice(0, 80));
}
