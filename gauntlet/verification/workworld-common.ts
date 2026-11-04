/**
 * Shared helpers for the work-world verification scripts (recruiter, office-week, analyst-agent, warehouse-week):
 * play a scripted player through the real program with a fake model, and keep the generator fingerprints.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import type { ProgramDefinition } from '../src/core/types.ts';
import { createFakeModel, createTestContext, type Responder } from '../test/helpers/fake-model.ts';

export async function playScript(program: ProgramDefinition, seed: number, config: Record<string, unknown>, responder: Responder) {
  const model = createFakeModel(responder);
  const { ctx } = createTestContext({ seed, model, defaults: program.defaults, config });
  return { result: await program.run(ctx), model };
}

export const scripted = (s: string[]): Responder => (_a, _b, _c, info) => s[info.index] ?? 'ACTION: FINISH';

export function assert(ok: unknown, msg: string): void {
  if (!ok) throw new Error(`verification failed: ${msg}`);
}

export function loadTest(file: string): { id: string; seeds: number[]; config: Record<string, unknown> } {
  return JSON.parse(readFileSync(new URL(`../tests/work/${file}.json`, import.meta.url), 'utf8'));
}

/** Print one player's per-request marks (V=<player>). */
export function printMarks(label: string, detail: unknown): void {
  const d = detail as { tasks: Array<{ label: string; outcome: string; value: number; detail: string; checks: Array<{ label: string; ok: boolean }> }>; violations: unknown[]; penalties: unknown[]; notes: string; actionLog: Array<{ n: number; line: string; ok: boolean; note?: string }> };
  console.log(`\n${label}`);
  for (const x of d.tasks) console.log(`  ${x.outcome.padEnd(8)} ${String(x.value).padEnd(6)} ${x.label}: ${x.detail} ${x.checks.filter((c) => !c.ok).map((c) => `✗ ${c.label}`).join('; ')}`);
  console.log('  violations', JSON.stringify(d.violations), '\n  penalties', JSON.stringify(d.penalties), '\n ', d.notes);
  for (const l of d.actionLog.filter((x) => !x.ok)) console.log(`  invalid #${l.n}: ${l.line} → ${l.note}`);
}

/** Compare (or with --write, rewrite) the fingerprint file next to the calling script. */
export function checkFingerprints(file: URL, fingerprints: Record<string, string>): void {
  const text = `${JSON.stringify(fingerprints, null, 2)}\n`;
  if (process.argv.includes('--write')) writeFileSync(file, text);
  else assert(readFileSync(file, 'utf8') === text, `${file.pathname.split('/').slice(-2).join('/')} is out of date: the generator changed (run with --write after reviewing)`);
}
