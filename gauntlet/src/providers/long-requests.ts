/**
 * Long generations (The Game Jam asks for a model's full output, which can stream for an hour) must not be cut off
 * by the HTTP client. Node's built-in fetch (undici) closes a response when no bytes arrive for 5 minutes
 * (bodyTimeout) or the headers take over 5 minutes (headersTimeout). A reasoning model can "think" silently for
 * longer than that before its first visible token, so the connection would die and the engine would retry a paid
 * request from scratch.
 *
 * relaxFetchTimeouts() swaps Node's default fetch dispatcher for an identical one with those two idle timers off.
 * It is best effort and conservative: it only acts on the plain default Agent (a proxy agent set by the user, e.g.
 * NODE_USE_ENV_PROXY, is left alone), and failures are ignored. Runs still end: the engine's per-case time limit and
 * the run's cancel button abort a request at any time.
 */

const KEY = Symbol.for('undici.globalDispatcher.1');
let done: Promise<void> | null = null;

export function relaxFetchTimeouts(): Promise<void> {
  if (!done) {
    done = (async () => {
      try {
        // A data: URL makes Node create its default dispatcher without touching the network.
        await fetch('data:,gauntlet').then((r) => r.text());
        const g = globalThis as unknown as Record<symbol, unknown>;
        const current = g[KEY] as { constructor?: new (o: Record<string, unknown>) => unknown } | undefined;
        if (!current || current.constructor?.name !== 'Agent') return;
        const Agent = current.constructor as new (o: Record<string, unknown>) => unknown;
        g[KEY] = new Agent({ bodyTimeout: 0, headersTimeout: 0, keepAliveTimeout: 60_000 });
      } catch {
        /* keep Node's defaults */
      }
    })();
  }
  return done;
}
