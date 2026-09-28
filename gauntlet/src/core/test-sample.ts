/**
 * Viewer-facing "sample question" for a test (GET /api/tests/:id/sample).
 *
 * Prompt tests: the first case's prompt, trimmed to a readable length, with its
 * pictures for vision tests. Programs: the opening message the model really sees
 * in the first seeded world, captured by running the program against a stand-in
 * model that stops at its first call (no API call, nothing spent), next to a
 * one-line description of the opening situation. Answer keys are only included
 * when the caller asks for them (reveal).
 */
import type { ChatMessage, ModelHandle, ModelReply, ProgramContext, ProgramTest, PromptTest, Rng, TestDefinition } from './types.ts';
import { createRng } from './rng.ts';
import { caseImageRefs, resolveTestImage, testsRelativePath } from './vision.ts';
import { explainerFor } from './explainers.ts';
import type { SampleImage, TestSample } from './explainers.ts';

import { promptSampleFrom, trimForViewer, viewerExcerpt } from './sample-text.ts';

export type { SampleImage, TestSample };
export { answerForViewer, collapseRepeats, trimForViewer, viewerExcerpt } from './sample-text.ts';

// ───────────────────────────── Programs: capture the opening message ─────────────────────────────

class OpeningCaptured extends Error {
  constructor() {
    super('opening captured');
  }
}

/**
 * Runs `run` against a stand-in model that records the first message it is sent and then stops the
 * program. Nothing is sent to any API.
 */
async function captureOpening(run: (model: ModelHandle, signal: AbortSignal) => Promise<unknown>, timeoutMs = 20_000): Promise<{ system?: string; text: string } | null> {
  let first: { system?: string; text: string } | null = null;
  const ctrl = new AbortController();
  const stop = (system: string | undefined, messages: readonly ChatMessage[]): never => {
    if (!first) {
      const last = [...messages].reverse().find((m) => m.role === 'user');
      first = { system, text: last?.content ?? '' };
    }
    ctrl.abort();
    throw new OpeningCaptured();
  };
  const model: ModelHandle = {
    complete: async (req): Promise<ModelReply> => stop(req.system, req.messages),
    chat(system) {
      const history: ChatMessage[] = [];
      return {
        get history() {
          return history;
        },
        send: async (userText: string): Promise<ModelReply> => stop(system, [...history, { role: 'user', content: userText }]),
      };
    },
  };
  let timer: ReturnType<typeof setTimeout> | undefined;
  const limit = new Promise<void>((resolve) => {
    timer = setTimeout(() => {
      ctrl.abort();
      resolve();
    }, timeoutMs);
  });
  try {
    await Promise.race([run(model, ctrl.signal).catch(() => undefined), limit]);
  } finally {
    clearTimeout(timer);
  }
  return first;
}

// ───────────────────────────── Public ─────────────────────────────

export interface ProgramRunner {
  defaults?: Record<string, unknown>;
  run(ctx: ProgramContext): Promise<unknown>;
}

const programCache = new Map<string, Promise<{ system?: string; text: string } | null>>();

/** The first case of a test as a viewer-friendly sample. `baseDir` resolves vision images. */
export async function buildSample(def: TestDefinition, opts: { reveal?: boolean; baseDir?: string; program?: ProgramRunner; hash?: string } = {}): Promise<TestSample> {
  const ex = explainerFor(def.id);
  if (def.kind === 'prompt') return promptSample(def, opts);
  return programSample(def, opts, ex?.opening ?? null);
}

function promptSample(def: PromptTest, opts: { reveal?: boolean; baseDir?: string }): TestSample {
  const c = def.cases[0];
  const images: SampleImage[] = c
    ? caseImageRefs(c).map((r) => {
        const abs = opts.baseDir ? resolveTestImage(opts.baseDir, r.file) : null;
        return { name: r.file.split('/').pop() ?? r.file, ...(abs ? { path: testsRelativePath(abs) } : {}) };
      })
    : [];
  return promptSampleFrom(def, images, !!opts.reveal);
}

async function programSample(def: ProgramTest, opts: { reveal?: boolean; program?: ProgramRunner; hash?: string }, situation: string | null): Promise<TestSample> {
  const seed = def.seeds[0] ?? 0;
  let opening: { system?: string; text: string } | null = null;
  if (opts.program) {
    const cacheKey = `${def.id}@${opts.hash ?? def.version}#${seed}`;
    let p = programCache.get(cacheKey);
    if (!p) {
      const program = opts.program;
      p = captureOpening((model, signal) =>
        program.run({
          seed,
          rng: createRng(seed),
          config: { ...(program.defaults ?? {}), ...(def.config ?? {}) },
          model,
          maxOutputTokens: def.maxOutputTokens ?? 16000,
          signal,
          artifact: () => undefined,
        }),
      );
      programCache.set(cacheKey, p);
    }
    opening = await p;
  }
  const raw = opening?.text ?? '';
  const trimmed = viewerExcerpt(raw, 900);
  return {
    testId: def.id,
    kind: 'program',
    caseId: `seed-${seed}`,
    caseCount: def.seeds.length,
    situation,
    context: opening?.system ? trimForViewer(opening.system, 320).text : null,
    ...(opening?.system ? { contextKind: 'system' as const } : {}),
    ...trimmed,
    fullChars: raw.length,
    turns: 1,
    images: [],
    // Program worlds have no answer key to reveal: the program scores what the model does.
    hasAnswer: false,
  };
}

/** The minimal slice of an Arena game this module needs (keeps core free of arena imports). */
export interface ArenaGameLike {
  defaults: Record<string, unknown>;
  setup(rng: Rng, config: never): unknown;
  view(state: never, side: 0 | 1): string;
  prompt?(state: never, side: 0 | 1, ctx: never): string;
}

/** An Arena game's opening position as the first player sees it (seed 1). There is no answer to reveal. */
export function buildArenaSample(id: string, game: ArenaGameLike, situation: string | null): TestSample {
  let raw = '';
  try {
    const config = { ...game.defaults };
    const state = game.setup(createRng(1), config as never);
    raw = game.prompt ? game.prompt(state as never, 0, { config, strikes: [0, 0], maxStrikes: 3 } as never) : game.view(state as never, 0);
  } catch {
    raw = '';
  }
  return {
    testId: id,
    kind: 'program',
    caseId: 'opening',
    caseCount: 1,
    situation,
    context: null,
    ...viewerExcerpt(raw, 900),
    fullChars: raw.length,
    turns: 1,
    images: [],
    hasAnswer: false,
  };
}
