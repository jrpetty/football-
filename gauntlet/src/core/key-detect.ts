/**
 * "Paste any API key": work out which company a key is from by its shape, and pull keys out of pasted text
 * (a bare key, a `NAME=value` line from a .env file, or several of those, one per line).
 *
 * Pure (no Node APIs): the dashboard uses it too, to say "Looks like an Anthropic key" while you paste.
 * Shapes are hints, never proof: the server always confirms with the company's free model-list check.
 */

export interface Detection {
  /** Best guess, or null when the shape isn't recognised (ask "Which company is this key from?"). */
  providerId: string | null;
  /** sure = a unique prefix; likely = a shape shared with another company (try each in `candidates`). */
  confidence: 'sure' | 'likely' | 'unknown';
  /** Companies to try in order (the free check tells them apart). */
  candidates: string[];
  /** Plain-English note, e.g. an admin key that can't run models. */
  note?: string;
}

const sure = (providerId: string, note?: string): Detection => ({ providerId, confidence: 'sure', candidates: [providerId], ...(note ? { note } : {}) });
const likely = (candidates: string[]): Detection => ({ providerId: candidates[0]!, confidence: 'likely', candidates });

export function detectProvider(key: string): Detection {
  const k = key.trim();
  if (/^sk-ant-admin/i.test(k)) return sure('anthropic', 'This is an Anthropic Admin key, which can’t run models. Create a normal API key instead (it starts “sk-ant-api”).');
  if (/^sk-ant-/.test(k)) return sure('anthropic');
  if (/^sk-or-/.test(k)) return sure('openrouter');
  if (/^sk-admin-/.test(k)) return sure('openai', 'This is an OpenAI Admin key, which can’t run models. Create a normal secret key instead (it starts “sk-proj-”).');
  if (/^sk-(proj|svcacct|None)-/.test(k)) return sure('openai');
  if (/^xai-/.test(k)) return sure('xai');
  if (/^gsk_/.test(k)) return sure('groq');
  if (/^AIza/.test(k)) return sure('google');
  if (/^tgp_v1_/.test(k)) return sure('together');
  // DeepSeek keys are "sk-" + 32 lowercase hex characters; old-style OpenAI keys are "sk-" + ~48 mixed characters.
  if (/^sk-[a-f0-9]{32}$/.test(k)) return likely(['deepseek', 'openai']);
  if (/^sk-/.test(k)) return likely(['openai', 'deepseek']);
  // Older Together keys: 64 hex characters. Mistral keys: 32 letters and digits, no prefix.
  if (/^[a-f0-9]{64}$/.test(k)) return likely(['together']);
  if (/^[A-Za-z0-9]{32}$/.test(k)) return likely(['mistral']);
  return { providerId: null, confidence: 'unknown', candidates: [] };
}

/** Well-known variable names, for pasted `NAME=value` lines (the server also matches every provider's apiKeyEnv). */
export const ENV_NAME_PROVIDER: Record<string, string> = {
  ANTHROPIC_API_KEY: 'anthropic',
  OPENAI_API_KEY: 'openai',
  GEMINI_API_KEY: 'google',
  GOOGLE_API_KEY: 'google',
  GOOGLE_GENERATIVE_AI_API_KEY: 'google',
  XAI_API_KEY: 'xai',
  DEEPSEEK_API_KEY: 'deepseek',
  MISTRAL_API_KEY: 'mistral',
  OPENROUTER_API_KEY: 'openrouter',
  GROQ_API_KEY: 'groq',
  TOGETHER_API_KEY: 'together',
};

export interface PastedKey {
  /** 1-based line number in the pasted text. */
  line: number;
  /** The key itself (never shown back in full). */
  key: string;
  /** The variable name when a `NAME=value` line was pasted. */
  envName?: string;
}

/**
 * Pull API keys out of pasted text. Accepts a bare key, `NAME=value` / `export NAME="value"` lines, and several
 * keys at once (one per line). Blank lines and # comments are ignored; a line like "Anthropic: sk-ant-…" keeps
 * only the key-looking part.
 */
export function parsePastedKeys(text: string): PastedKey[] {
  const out: PastedKey[] = [];
  const lines = String(text ?? '').replace(/\r\n?/g, '\n').split('\n');
  lines.forEach((raw, i) => {
    let line = raw.trim();
    if (!line || line.startsWith('#')) return;
    let envName: string | undefined;
    const m = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (m) {
      envName = m[1]!.toUpperCase();
      line = m[2]!.trim();
      const hash = line.search(/\s#/);
      if (hash >= 0 && !/^["']/.test(line)) line = line.slice(0, hash).trim();
    }
    line = line.replace(/^["'`]+|["'`,;]+$/g, '').trim();
    if (/\s/.test(line)) {
      // "Anthropic: sk-ant-…" or "my key is AIza…": keep the one long token.
      const tokens = line.split(/\s+/).map((t) => t.replace(/^["'`(<]+|["'`)>.,;]+$/g, '')).filter((t) => t.length >= 20);
      if (tokens.length !== 1) return;
      line = tokens[0]!;
    }
    if (line.length < 8) return;
    out.push({ line: i + 1, key: line, ...(envName ? { envName } : {}) });
  });
  return out;
}
