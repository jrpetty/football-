import { createRng, hashString } from '../core/rng.ts';
import type { CompletionRequest, CompletionResult } from '../core/types.ts';
import type { AdapterContext, ProviderAdapter } from './types.ts';

/**
 * The Random Baseline. Makes no network calls. It answers the way a
 * know-nothing player would:
 *  - environments: picks one of the backticked actions offered in the prompt;
 *  - answer-format questions: a random letter / number / word;
 *  - code: a stub function; JSON: an empty object; prose: filler.
 * Deterministic for a given prompt, so baseline results are reproducible.
 */
export function createMockAdapter(ctx: AdapterContext): ProviderAdapter {
  return {
    async complete(req: CompletionRequest): Promise<Omit<CompletionResult, 'retries'>> {
      const last = [...req.messages].reverse().find((m) => m.role === 'user')?.content ?? '';
      const all = (req.system ?? '') + '\n' + req.messages.map((m) => m.content).join('\n');
      const rng = createRng(hashString(`${ctx.contestant.id}\n${all}`));
      const text = respond(last, rng);

      const startedAt = Date.now();
      const latency = 40 + Math.floor(rng.next() * 160);
      await sleep(latency, req.signal);
      // Stream in a few chunks so the live view animates like a real model.
      const chunks = text.match(/[\s\S]{1,24}/g) ?? [];
      for (const c of chunks) {
        req.onDelta?.(c);
        await sleep(8, req.signal);
      }
      const totalMs = Date.now() - startedAt;
      return {
        text,
        usage: {
          inputTokens: Math.ceil(all.length / 4),
          outputTokens: Math.ceil(text.length / 4),
          reasoningTokens: 0,
          cachedInputTokens: 0,
          cacheWriteTokens: 0,
        },
        startedAt,
        ttftMs: latency,
        totalMs,
        stopReason: 'end',
        rawStopReason: 'end',
        servedModel: `mock/${ctx.contestant.model}`,
      };
    },
  };
}

function respond(prompt: string, rng: ReturnType<typeof createRng>): string {
  const options = [...prompt.matchAll(/`([^`\n]{1,120})`/g)].map((m) => m[1]!.trim()).filter(Boolean);
  if (options.length > 0) {
    // Options formatted as "TAG: value" are decision lines — choose one per tag.
    const tagged = options.filter((o) => /^[A-Z][A-Z_ ]{1,24}:\s*\S/.test(o));
    if (tagged.length > 0) {
      const byTag = new Map<string, string[]>();
      for (const o of tagged) {
        const tag = o.slice(0, o.indexOf(':')).trim();
        byTag.set(tag, [...(byTag.get(tag) ?? []), o]);
      }
      return [...byTag.values()].map((opts) => rng.pick(opts)).join('\n');
    }
    return `I will try this.\nACTION: ${rng.pick(options)}`;
  }
  if (/```html|single self-contained html/i.test(prompt)) {
    return '```html\n<!doctype html><html><body><canvas id="c" width="300" height="200"></canvas><p>Game</p></body></html>\n```';
  }
  if (/svg/i.test(prompt) && /draw|illustrat|svg/i.test(prompt)) {
    const x = rng.int(20, 300);
    const y = rng.int(20, 300);
    return `\`\`\`svg\n<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><circle cx="${x}" cy="${y}" r="40" fill="gray"/></svg>\n\`\`\``;
  }
  if (/```javascript|javascript code block/i.test(prompt)) {
    const fn = prompt.match(/function\s+([A-Za-z_$][\w$]*)/)?.[1] ?? prompt.match(/`([A-Za-z_$][\w$]*)\(/)?.[1] ?? 'solution';
    return `\`\`\`javascript\nfunction ${fn}() {\n  return null;\n}\n\`\`\``;
  }
  if (/\bA\d+\s*:/.test(prompt) || /A1:/.test(prompt)) {
    const n = Math.max(1, (prompt.match(/\bQ\d+\b/g) ?? []).length);
    return Array.from({ length: n }, (_, i) => `A${i + 1}: unknown`).join('\n');
  }
  if (/FINAL ANSWER/.test(prompt)) {
    if (/\b[A-E]\)|\([A-E]\)|letter/i.test(prompt)) return `FINAL ANSWER: ${rng.pick(['A', 'B', 'C', 'D'])}`;
    return `FINAL ANSWER: ${rng.int(0, 100)}`;
  }
  if (/json/i.test(prompt)) return '```json\n{}\n```';
  const words = ['the', 'model', 'result', 'answer', 'benchmark', 'quickly', 'random', 'value', 'system', 'data', 'story', 'river', 'light'];
  return Array.from({ length: 40 + rng.int(0, 40) }, () => rng.pick(words)).join(' ') + '.';
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
    const t = setTimeout(resolve, ms);
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(t);
        reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
      },
      { once: true },
    );
  });
}
