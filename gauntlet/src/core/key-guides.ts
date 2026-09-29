/**
 * "Get a key" guides for the API Keys page, written for someone who has never used an API.
 *
 * Checked against each company's own help pages in September 2026 where they could be checked; where a menu name
 * couldn't be confirmed the step stays general ("find API keys in the menu") rather than inventing a path.
 * Pure data: shared by the server and the dashboard (and its mock mode).
 */

export interface KeyGuide {
  providerId: string;
  /** Company name as people know it. */
  name: string;
  /** Short line under the name. */
  blurb: string;
  /** The page where keys are created (the big button). */
  url: string;
  /** Where to add credit, when it's a separate page. */
  creditUrl?: string;
  steps: string[];
  /** Money: what to add and roughly how much to start with. */
  credit: string;
  /** What the key looks like, e.g. "starts with sk-ant-". */
  looksLike?: string;
  /** Which models it unlocks, in plain words. */
  unlocks: string;
  recommended?: boolean;
}

export const KEY_GUIDES: KeyGuide[] = [
  {
    providerId: 'openrouter',
    name: 'OpenRouter',
    blurb: 'One key for every AI: Claude, GPT, Gemini, Grok, DeepSeek and more, on one bill.',
    url: 'https://openrouter.ai/settings/keys',
    creditUrl: 'https://openrouter.ai/settings/credits',
    steps: [
      'Open openrouter.ai and sign up (signing in with Google works).',
      'Add credit: open Credits, press Add Credits and pay by card.',
      'Open Keys and press Create Key. Call it “Gauntlet”; leave the limit empty or set one.',
      'Copy the key (it starts with sk-or-). It is only shown once, so paste it here straight away.',
    ],
    credit: 'About £5 is plenty to start: the Quick Check costs about 2p, a Quick Look on two cheap models about £1. OpenRouter adds a small fee (about 5%) when you buy credit; its model prices are close to each company’s own (Gauntlet uses OpenRouter’s listed prices).',
    looksLike: 'starts with sk-or-',
    unlocks: 'Almost every model in Gauntlet',
    recommended: true,
  },
  {
    providerId: 'anthropic',
    name: 'Anthropic (Claude)',
    blurb: 'Claude’s own company. Best for published results with Claude models.',
    url: 'https://platform.claude.com/settings/keys',
    steps: [
      'Open the Claude Console (platform.claude.com) and sign up. This is separate from a Claude.ai chat subscription.',
      'Add credit: Settings → Billing, then buy credits by card.',
      'Settings → API keys → Create key. Give it any name.',
      'Copy the key (it starts with sk-ant-). It is only shown once.',
    ],
    credit: 'A $5 (about £4) top-up is enough to try it. A Claude Pro subscription does not include API credit.',
    looksLike: 'starts with sk-ant-',
    unlocks: 'Claude models',
  },
  {
    providerId: 'openai',
    name: 'OpenAI (GPT)',
    blurb: 'GPT’s own company. Best for published results with GPT models.',
    url: 'https://platform.openai.com/api-keys',
    creditUrl: 'https://platform.openai.com/settings/organization/billing/overview',
    steps: [
      'Open platform.openai.com and sign in (a ChatGPT login works, but a ChatGPT subscription does not include API credit).',
      'Add credit: Settings → Billing → Add to credit balance.',
      'Open API keys and press Create new secret key.',
      'Copy the key (it starts with sk-proj-). It is only shown once.',
    ],
    credit: 'The smallest top-up ($5, about £4) is enough to try it.',
    looksLike: 'starts with sk-proj-',
    unlocks: 'GPT models (and GPT Image for The Gallery)',
  },
  {
    providerId: 'google',
    name: 'Google (Gemini)',
    blurb: 'Gemini’s own company. Easy to start: a Google account is enough.',
    url: 'https://aistudio.google.com/apikey',
    steps: ['Open Google AI Studio and sign in with your Google account.', 'Press Create API key (pick or create a project if asked).', 'Copy the key (it starts with AIza).'],
    credit: 'There is a free allowance with low limits. For full runs, turn on billing for the key’s project in AI Studio; a few pounds goes a long way.',
    looksLike: 'starts with AIza',
    unlocks: 'Gemini models (and Gemini image models for The Gallery)',
  },
  {
    providerId: 'xai',
    name: 'xAI (Grok)',
    blurb: 'Grok’s own company.',
    url: 'https://console.x.ai',
    steps: ['Open console.x.ai and sign up.', 'Add credit on the billing page.', 'Find API Keys in the menu and create a key.', 'Copy the key (it starts with xai-).'],
    credit: '$5 (about £4) is enough to try it.',
    looksLike: 'starts with xai-',
    unlocks: 'Grok models',
  },
  {
    providerId: 'deepseek',
    name: 'DeepSeek',
    blurb: 'Very cheap models.',
    url: 'https://platform.deepseek.com/api_keys',
    creditUrl: 'https://platform.deepseek.com/top_up',
    steps: ['Open platform.deepseek.com and sign up.', 'Top up your balance.', 'Open API keys and create a new key.', 'Copy the key (it starts with sk-). It is only shown once.'],
    credit: '$2 (under £2) lasts a long time: DeepSeek is one of the cheapest.',
    looksLike: 'starts with sk-',
    unlocks: 'DeepSeek models',
  },
  {
    providerId: 'mistral',
    name: 'Mistral',
    blurb: 'European AI company.',
    url: 'https://console.mistral.ai/api-keys',
    steps: ['Open console.mistral.ai and sign up.', 'Choose a plan or add billing if asked.', 'Open API Keys and create a new key.', 'Copy the key (32 letters and numbers).'],
    credit: 'Check the plan options on the console; a few pounds is enough to try.',
    unlocks: 'Mistral models you add on the Models page',
  },
  {
    providerId: 'groq',
    name: 'Groq',
    blurb: 'Very fast open models.',
    url: 'https://console.groq.com/keys',
    steps: ['Open console.groq.com and sign up.', 'Press Create API Key.', 'Copy the key (it starts with gsk_).'],
    credit: 'There is a free allowance to start with.',
    looksLike: 'starts with gsk_',
    unlocks: 'Models you add on the Models page',
  },
  {
    providerId: 'together',
    name: 'Together AI',
    blurb: 'Open models such as Llama and Qwen.',
    url: 'https://api.together.ai/settings/api-keys',
    steps: ['Open together.ai and sign up.', 'Add credit if asked.', 'Find API Keys in your settings and copy the key.'],
    credit: 'A few pounds is enough to try.',
    unlocks: 'Models you add on the Models page',
  },
];

export function guideFor(providerId: string): KeyGuide | undefined {
  return KEY_GUIDES.find((g) => g.providerId === providerId);
}
