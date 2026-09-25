import type { IncomingMessage, ServerResponse } from 'node:http';
import { getContestant, loadContestants, loadProviders } from '../core/config.ts';
import { ENV_FILE, cleanKey, explainKeyError, isRejection, keyFormatWarning, keyStatus, removeKey, saveKey } from '../core/keys.ts';
import { discoverModels } from '../providers/index.ts';

/**
 * API Keys page routes. Keys are written to gauntlet/.env and applied immediately.
 *  GET    /api/keys                      every provider that needs a key: set?, where from, masked hint, help link
 *  PUT    /api/keys/:provider   {key}    check a key (free) and save it; a key the provider rejects is not saved
 *  DELETE /api/keys/:provider            remove a key saved here
 *  POST   /api/keys/:provider/test       free check (lists the provider's models); {send: true, model?} also sends a one-word message (< 1 cent)
 * Writes are only accepted from this computer (loopback), even if the server listens on the network.
 */

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string) => Error;
  /** Sends "Reply with exactly one word: pong" to a model (the Models page ping). */
  ping: (contestantId: string) => Promise<{ ok: boolean; text?: string; error?: string; costUsd?: number; totalMs?: number }>;
}

function isLocal(req: IncomingMessage): boolean {
  const a = req.socket.remoteAddress ?? '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

function providerModels(providerId: string): Array<{ id: string; label: string }> {
  return loadContestants()
    .filter((c) => c.provider === providerId && c.enabled)
    .map((c) => ({ id: c.id, label: c.label }));
}

/** The cheapest enabled model of a provider: the one used for the optional test message. */
function cheapestModel(providerId: string): string | undefined {
  const list = loadContestants().filter((c) => c.provider === providerId && c.enabled);
  list.sort((a, b) => a.pricing.inputPerM + a.pricing.outputPerM * 4 - (b.pricing.inputPerM + b.pricing.outputPerM * 4));
  return list[0]?.id;
}

export function registerKeyRoutes({ route, httpError, ping }: Deps): void {
  const provider = (id: string) => {
    const p = loadProviders().find((x) => x.id === id);
    if (!p || !p.apiKeyEnv) throw httpError(404, 'This provider does not use an API key.');
    return p;
  };

  async function freeCheck(providerId: string, candidate?: string): Promise<{ ok: boolean; models?: number; error?: string; rejected?: boolean }> {
    try {
      const ids = await discoverModels(providerId, candidate);
      return { ok: true, models: ids.length };
    } catch (err) {
      const message = (err as Error).message;
      return { ok: false, error: explainKeyError(message), rejected: isRejection(message) };
    }
  }

  route('GET', '/api/keys', () => ({
    file: ENV_FILE,
    keys: loadProviders()
      .map((p) => keyStatus(p, providerModels(p.id)))
      .filter((k) => k !== null),
  }));

  route('PUT', '/api/keys/:provider', async ({ req, params, body }) => {
    if (!isLocal(req)) throw httpError(403, 'For safety, keys can only be changed from the computer running Gauntlet.');
    const p = provider(params.provider!);
    const b = (await body()) as { key?: unknown } | null;
    const { key, error } = cleanKey(b?.key);
    if (error || !key) throw httpError(400, error ?? 'No key');
    // Check first, using the new key only for this request: a key the provider rejects is never saved.
    const env = p.apiKeyEnv!;
    const check = await freeCheck(p.id, key);
    if (!check.ok && check.rejected) {
      return { ok: false, saved: false, warning: keyFormatWarning(p.id, key), check, status: keyStatus(p, providerModels(p.id)) };
    }
    saveKey(env, key);
    return { ok: true, saved: true, warning: keyFormatWarning(p.id, key), check, status: keyStatus(p, providerModels(p.id)) };
  });

  route('DELETE', '/api/keys/:provider', ({ req, params }) => {
    if (!isLocal(req)) throw httpError(403, 'For safety, keys can only be changed from the computer running Gauntlet.');
    const p = provider(params.provider!);
    removeKey(p.apiKeyEnv!);
    return { ok: true, status: keyStatus(p, providerModels(p.id)) };
  });

  route('POST', '/api/keys/:provider/test', async ({ params, body }) => {
    const p = provider(params.provider!);
    if (!process.env[p.apiKeyEnv!]) return { ok: false, error: 'No key saved for this provider yet.' };
    const b = ((await body()) ?? {}) as { send?: boolean; model?: string };
    if (!b.send) return freeCheck(p.id);
    const modelId = b.model ?? cheapestModel(p.id);
    if (!modelId) return { ok: false, error: 'No enabled model uses this provider. Add one on the Models page.' };
    try {
      getContestant(modelId);
    } catch {
      throw httpError(404, 'Model not found');
    }
    const r = await ping(modelId);
    return r.ok ? { ...r, model: modelId } : { ...r, model: modelId, error: explainKeyError(r.error ?? 'Unknown error') };
  });
}
