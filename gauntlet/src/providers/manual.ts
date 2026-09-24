import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import type { ChatImage, CompletionRequest, CompletionResult, ManualRequest, ManualSubmission } from '../core/types.ts';
import { estimateImageTokens, stripImageData } from '../core/vision.ts';
import type { AdapterContext, ProviderAdapter } from './types.ts';

/**
 * Manual (copy & paste) provider.
 *
 * Lets Gauntlet grade ANY model — including chat-only products, old models
 * without an API, or models that don't exist yet — with exactly the same
 * prompts and scorers as API models. Each call becomes a pending request in
 * the Manual Inbox; a person pastes the prompt into the model's chat UI and
 * pastes the reply back. The reply is then graded like any other.
 */

interface Pending {
  request: ManualRequest;
  resolve: (s: ManualSubmission) => void;
  reject: (e: Error) => void;
}

const pending = new Map<string, Pending>();
export const manualEvents = new EventEmitter();
manualEvents.setMaxListeners(200);

/** Pending requests, oldest first. Image bytes are left out (fetch them with manualImage). */
export function listManualRequests(runId?: string): ManualRequest[] {
  return [...pending.values()]
    .map((p) => p.request)
    .filter((r) => !runId || r.runId === runId)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
    .map((r) => (r.messages.some((m) => m.images?.length) ? { ...r, messages: stripImageData(r.messages) } : r));
}

/** One image of a pending request (for the Manual Inbox's preview, download and copy buttons). */
export function manualImage(id: string, messageIndex: number, imageIndex: number): ChatImage | undefined {
  return pending.get(id)?.request.messages[messageIndex]?.images?.[imageIndex];
}

export function submitManual(id: string, submission: ManualSubmission): boolean {
  const p = pending.get(id);
  if (!p) return false;
  if (typeof submission.text !== 'string') throw new Error('text is required');
  pending.delete(id);
  manualEvents.emit('resolved', p.request);
  p.resolve(submission);
  return true;
}

export function failManual(id: string, reason: string): boolean {
  const p = pending.get(id);
  if (!p) return false;
  pending.delete(id);
  manualEvents.emit('resolved', p.request);
  p.reject(new Error(`Manual entry marked as failed: ${reason || 'no reason given'}`));
  return true;
}

export function combinePrompt(system: string | undefined, messages: Array<{ role: string; content: string }>): string {
  const parts: string[] = [];
  if (system) parts.push(`[SYSTEM INSTRUCTIONS — follow these for the whole conversation]\n${system}`);
  if (messages.length === 1 && !system) return messages[0]!.content;
  for (const m of messages) parts.push(`[${m.role === 'user' ? 'USER' : 'ASSISTANT (your earlier reply)'}]\n${m.content}`);
  if (system || messages.length > 1) parts.push('[Reply to the last USER message only.]');
  return parts.join('\n\n');
}

const estimateTokens = (text: string) => Math.ceil(text.length / 4);

export function createManualAdapter(ctx: AdapterContext): ProviderAdapter {
  return {
    complete(req: CompletionRequest): Promise<Omit<CompletionResult, 'retries'>> {
      const startedAt = Date.now();
      const id = randomUUID();
      const cc = req.callContext;
      const lastUser = [...req.messages].reverse().find((m) => m.role === 'user')?.content ?? '';
      const request: ManualRequest = {
        id,
        runId: cc?.runId ?? 'adhoc',
        key: cc?.key ?? id,
        contestantId: ctx.contestant.id,
        contestantLabel: ctx.contestant.label,
        testId: cc?.testId ?? '',
        testName: cc?.testName ?? '',
        caseId: cc?.caseId ?? '',
        label: cc?.label ?? 'response',
        system: req.system,
        messages: req.messages.map((m) => ({ ...m })),
        combinedPrompt: combinePrompt(req.system, req.messages),
        latestUserMessage: lastUser,
        isContinuation: req.messages.length > 1,
        createdAt: new Date().toISOString(),
      };
      return new Promise((resolve, reject) => {
        const onAbort = () => {
          if (pending.delete(id)) manualEvents.emit('resolved', request);
          reject(Object.assign(new Error('Aborted'), { name: 'AbortError' }));
        };
        if (req.signal?.aborted) return onAbort();
        req.signal?.addEventListener('abort', onAbort, { once: true });
        pending.set(id, {
          request,
          resolve: (s) => {
            req.signal?.removeEventListener('abort', onAbort);
            const imageTokens = req.messages.reduce((sum, m) => sum + (m.images ?? []).reduce((t, img) => t + estimateImageTokens(img.width ?? 0, img.height ?? 0, 'anthropic'), 0), 0);
            const input = s.inputTokens ?? estimateTokens((req.system ?? '') + req.messages.map((m) => m.content).join('\n')) + imageTokens;
            const output = s.outputTokens ?? estimateTokens(s.text);
            req.onDelta?.(s.text);
            resolve({
              text: s.text,
              usage: { inputTokens: input, outputTokens: output, reasoningTokens: s.reasoningTokens ?? 0, cachedInputTokens: 0, cacheWriteTokens: 0 },
              startedAt,
              ttftMs: null,
              totalMs: Date.now() - startedAt,
              stopReason: s.text.trim() ? 'end' : 'other',
              rawStopReason: 'manual',
              servedModel: `manual/${ctx.contestant.model}`,
              costUsd: s.costUsd,
              manual: true,
            });
          },
          reject: (e) => {
            req.signal?.removeEventListener('abort', onAbort);
            reject(e);
          },
        });
        manualEvents.emit('request', request);
      });
    },
  };
}
