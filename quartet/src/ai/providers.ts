// Every kind of mind that can drive a person: Claude (through the official
// Anthropic SDK) plus any OpenAI-compatible endpoint — the big Chinese model
// APIs (DeepSeek, Qwen, Kimi, GLM) and models you download and run yourself
// (Ollama, LM Studio, llama.cpp, vLLM). A "mind profile" bundles a provider,
// endpoint, key and model; tribes and people are assigned profiles by id.

import { MODELS, callClaude, AiError, type Effort, type Usage } from './client.ts'

export type ProviderKind = 'anthropic' | 'openai'

export interface MindProfile {
  id: string
  label: string
  kind: ProviderKind
  vendor: string // preset id: anthropic, deepseek, qwen, kimi, glm, ollama, lmstudio, custom
  baseUrl: string // OpenAI-compatible base, e.g. https://api.deepseek.com/v1
  apiKey: string // '' for local models; '' on Claude profiles = use the main Anthropic key
  model: string
  jsonMode: boolean // send response_format: json_object
  useProxy: boolean // route through the local CORS proxy (npm run proxy)
  priceIn: number // $ per million tokens (0 for local)
  priceOut: number
  builtin?: boolean
}

export interface VendorPreset {
  id: string
  label: string
  flag: string
  baseUrl: string
  models: string[] // examples; "List models" asks the endpoint for the real list
  priceIn: number
  priceOut: number
  local: boolean
  keyUrl?: string
  note: string
  proxyByDefault: boolean
}

export const VENDORS: VendorPreset[] = [
  { id: 'deepseek', label: 'DeepSeek', flag: '🇨🇳', baseUrl: 'https://api.deepseek.com/v1', models: ['deepseek-chat', 'deepseek-reasoner'], priceIn: 0.28, priceOut: 0.42, local: false, keyUrl: 'https://platform.deepseek.com', note: 'Strong and very cheap. deepseek-chat is fast; deepseek-reasoner thinks longer.', proxyByDefault: true },
  { id: 'qwen', label: 'Qwen (Alibaba Cloud)', flag: '🇨🇳', baseUrl: 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1', models: ['qwen-plus', 'qwen-max', 'qwen-turbo', 'qwen-flash'], priceIn: 0.4, priceOut: 1.2, local: false, keyUrl: 'https://www.alibabacloud.com/product/modelstudio', note: 'Use dashscope.aliyuncs.com (no -intl) for a mainland China account.', proxyByDefault: true },
  { id: 'kimi', label: 'Kimi (Moonshot AI)', flag: '🇨🇳', baseUrl: 'https://api.moonshot.ai/v1', models: ['kimi-k2-turbo-preview', 'kimi-latest', 'moonshot-v1-8k'], priceIn: 0.6, priceOut: 2.5, local: false, keyUrl: 'https://platform.moonshot.ai', note: 'Use api.moonshot.cn for a mainland China account.', proxyByDefault: true },
  { id: 'glm', label: 'GLM (Zhipu AI)', flag: '🇨🇳', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', models: ['glm-4.5', 'glm-4-plus', 'glm-4-flash'], priceIn: 0.6, priceOut: 2.2, local: false, keyUrl: 'https://open.bigmodel.cn', note: 'International accounts can use api.z.ai/api/paas/v4.', proxyByDefault: true },
  { id: 'ollama', label: 'Ollama (on your computer)', flag: '💻', baseUrl: 'http://localhost:11434/v1', models: ['qwen3', 'deepseek-r1', 'llama3.1', 'gemma3', 'mistral'], priceIn: 0, priceOut: 0, local: true, note: 'Download a model with `ollama pull qwen3`. For the hosted site, start Ollama with OLLAMA_ORIGINS=* so the browser may talk to it.', proxyByDefault: false },
  { id: 'lmstudio', label: 'LM Studio (on your computer)', flag: '💻', baseUrl: 'http://localhost:1234/v1', models: ['local-model'], priceIn: 0, priceOut: 0, local: true, note: 'Load a model, open the Developer tab, start the server and tick “Enable CORS”.', proxyByDefault: false },
  { id: 'custom', label: 'Any OpenAI-compatible API', flag: '🔌', baseUrl: 'http://localhost:8000/v1', models: [], priceIn: 0, priceOut: 0, local: false, note: 'llama.cpp server, vLLM, OpenRouter, SiliconFlow, Together… anything that speaks /chat/completions.', proxyByDefault: false },
]

export const PROXY_URL = 'http://localhost:8787'

/** Claude models are always available as built-in profiles; their id is the model id. */
export function builtinProfiles(): MindProfile[] {
  return MODELS.map((m) => ({
    id: m.id, label: m.label, kind: 'anthropic' as const, vendor: 'anthropic', baseUrl: 'https://api.anthropic.com', apiKey: '', model: m.id,
    jsonMode: true, useProxy: false, priceIn: m.input, priceOut: m.output, builtin: true,
  }))
}

export function newProfile(v: VendorPreset): MindProfile {
  return {
    id: `p_${v.id}_${Math.random().toString(36).slice(2, 8)}`,
    label: `${v.label.split(' (')[0]} · ${v.models[0] ?? 'model'}`,
    kind: 'openai', vendor: v.id, baseUrl: v.baseUrl, apiKey: '', model: v.models[0] ?? '', jsonMode: true,
    useProxy: v.proxyByDefault, priceIn: v.priceIn, priceOut: v.priceOut,
  }
}

export function vendorOf(p: MindProfile): VendorPreset | undefined {
  return VENDORS.find((v) => v.id === p.vendor)
}

export function profileCost(p: MindProfile, u: Usage): number {
  if (p.kind === 'anthropic') {
    const m = MODELS.find((x) => x.id === p.model) ?? MODELS[0]
    return (u.input * m.input + u.output * m.output + u.cacheRead * m.cacheRead + u.cacheWrite * m.input * 1.25) / 1e6
  }
  return ((u.input + u.cacheRead) * p.priceIn + u.output * p.priceOut) / 1e6
}

export interface CompleteOpts {
  system: string
  user: string
  schema?: Record<string, unknown>
  maxTokens?: number
  effort: Effort
  signal?: AbortSignal
  anthropicKey: string
}

export interface CompleteResult {
  text: string
  usage: Usage
}

function endpoint(p: MindProfile, path: string): string {
  const url = p.baseUrl.replace(/\/+$/, '') + path
  return p.useProxy ? `${PROXY_URL}/${url}` : url
}

/** One completion from whichever kind of mind this profile is. */
export async function complete(p: MindProfile, o: CompleteOpts): Promise<CompleteResult> {
  if (p.kind === 'anthropic') {
    const key = (p.apiKey || o.anthropicKey).trim()
    if (!key) throw new AiError('auth', 'Add your Anthropic API key in Minds & settings.')
    const r = await callClaude({ key, model: p.model, effort: o.effort, system: o.system, user: o.user, schema: o.schema, maxTokens: o.maxTokens, signal: o.signal })
    return { text: r.text, usage: r.usage }
  }
  const local = vendorOf(p)?.local ?? false
  if (!p.apiKey && !local && p.vendor !== 'custom') throw new AiError('auth', `${p.label} needs an API key.`)
  const body: Record<string, unknown> = {
    model: p.model,
    messages: [
      { role: 'system', content: o.schema ? `${o.system}\n\nReply with a single JSON object only — no prose, no markdown fences.` : o.system },
      { role: 'user', content: o.user },
    ],
    max_tokens: Math.min(o.maxTokens ?? 4000, 8000),
    stream: false,
  }
  if (o.schema && p.jsonMode) body.response_format = { type: 'json_object' }
  const headers: Record<string, string> = { 'content-type': 'application/json' }
  if (p.apiKey) headers.authorization = `Bearer ${p.apiKey.trim()}`
  let resp: Response
  try {
    resp = await fetch(endpoint(p, '/chat/completions'), { method: 'POST', headers, body: JSON.stringify(body), signal: o.signal })
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new AiError('network', local
      ? `Couldn't reach ${p.label} at ${p.baseUrl}. Is it running, with browser access (CORS) allowed?`
      : `Couldn't reach ${p.label}. Most cloud APIs block browsers — tick “Use local proxy” and run \`npm run proxy\`.`)
  }
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`
    try {
      const j = await resp.json() as { error?: { message?: string } | string; message?: string }
      msg = typeof j.error === 'string' ? j.error : j.error?.message ?? j.message ?? msg
    } catch { /* not json */ }
    // some servers reject response_format — retry once without it
    if (resp.status === 400 && body.response_format && /response_format|json/i.test(msg)) {
      return complete({ ...p, jsonMode: false }, o)
    }
    if (resp.status === 401 || resp.status === 403) throw new AiError('auth', `${p.label} rejected the key (${resp.status}): ${msg}`)
    if (resp.status === 429) throw new AiError('rate', `${p.label} rate limit (429) — slowing down.`)
    if (resp.status >= 500) throw new AiError('overloaded', `${p.label} server error (${resp.status}).`)
    throw new AiError('bad', `${p.label}: ${msg}`)
  }
  const j = await resp.json() as {
    choices?: { message?: { content?: string | null }; finish_reason?: string }[]
    usage?: { prompt_tokens?: number; completion_tokens?: number; prompt_cache_hit_tokens?: number }
  }
  const raw = j.choices?.[0]?.message?.content ?? ''
  const text = stripThinking(raw)
  const hit = j.usage?.prompt_cache_hit_tokens ?? 0
  const usage: Usage = { input: (j.usage?.prompt_tokens ?? 0) - hit, output: j.usage?.completion_tokens ?? 0, cacheRead: hit, cacheWrite: 0 }
  if (!text.trim()) throw Object.assign(new AiError('bad', `${p.label} returned an empty reply.`), { usage })
  return { text, usage }
}

/** Reasoning models served locally often inline their chain of thought. */
export function stripThinking(s: string): string {
  return s.replace(/<think>[\s\S]*?<\/think>/gi, '').replace(/^```(?:json)?\s*|\s*```$/g, '').trim()
}

/** Ask an OpenAI-compatible endpoint which models it serves. */
export async function listModels(p: MindProfile): Promise<string[]> {
  const headers: Record<string, string> = {}
  if (p.apiKey) headers.authorization = `Bearer ${p.apiKey.trim()}`
  const r = await fetch(endpoint(p, '/models'), { headers })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  const j = await r.json() as { data?: { id: string }[]; models?: { name: string }[] }
  return (j.data?.map((m) => m.id) ?? j.models?.map((m) => m.name) ?? []).sort()
}

/** A tiny round trip to prove a profile works. */
export async function testProfile(p: MindProfile, anthropicKey: string): Promise<string> {
  const r = await complete(p, {
    system: 'You are a connection test. Reply with JSON.',
    user: 'Reply with {"ok": true, "hello": "<one short friendly sentence>"}',
    schema: { type: 'object', properties: { ok: { type: 'boolean' }, hello: { type: 'string' } }, required: ['ok', 'hello'], additionalProperties: false },
    maxTokens: 2000,
    effort: 'low',
    anthropicKey,
  })
  return r.text
}
