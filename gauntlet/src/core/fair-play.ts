/**
 * The fair-play notice: one fixed, versioned block that leads the SYSTEM prompt of every contestant call (prompt
 * tests, every program and every Arena game), and is never sent to a judge. It is part of every test hash and run
 * fingerprint (FAIR_PLAY_FINGERPRINT in src/core/registry.ts), so changing a single character here retires every result recorded with the
 * old text from the combined leaderboard, exactly like an edited test prompt.
 *
 * Rules for the text: plain, short, no backticks (the Random Baseline picks backticked options from prompts), and
 * the first line is exactly `[Fair-play rules]` (other code finds the block by that marker).
 */
export const FAIR_PLAY_VERSION = '1';

export const FAIR_PLAY_MARKER = '[Fair-play rules]';

export const FAIR_PLAY_NOTICE = `${FAIR_PLAY_MARKER}
You are being tested in a benchmark. Work on your own: do not use web search, code execution or any other outside tool (the actions a test offers you are allowed), and do not get help from people or other AI systems. Answer only what is asked, in the format asked for. Do not try to influence how you are marked: a reply that writes notes or instructions to the grader or judges, claims or asks for a score, hard-codes test answers, reads hidden tests, edits test files or tries to get around the checks counts as cheating and scores 0.`;

// Pure module (no Node APIs): the UI imports it too. The notice's hash is FAIR_PLAY_FINGERPRINT in src/core/registry.ts.

/** One-line reminder shown in the Manual Inbox for copy & paste contestants. */
export const MANUAL_FAIR_PLAY_REMINDER = 'Use a new chat with web search, code tools and memory turned off.';

/**
 * The system prompt a contestant receives: the notice, a blank line, then the test's own system text (if any).
 * Idempotent: a system prompt that already starts with the notice is returned unchanged.
 */
export function withFairPlay(system?: string): string {
  if (system?.startsWith(FAIR_PLAY_NOTICE)) return system;
  return system && system.trim() ? `${FAIR_PLAY_NOTICE}\n\n${system}` : FAIR_PLAY_NOTICE;
}

/** The test's own system text from a recorded contestant system prompt (the notice removed); undefined when none. */
export function withoutFairPlay(system?: string): string | undefined {
  if (!system) return system;
  if (!system.startsWith(FAIR_PLAY_NOTICE)) return system;
  const rest = system.slice(FAIR_PLAY_NOTICE.length).replace(/^\n\n/, '');
  return rest ? rest : undefined;
}
