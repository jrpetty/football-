// Live test: hand the leaders of a young world to a real model and watch what
// they do with war, peace, trade and alliances. Uses real API calls (and real
// money — capped by GENESIS_BUDGET, default $1).
//
//   Claude:            ANTHROPIC_API_KEY=sk-ant-… npm run live
//   Any OpenAI-style:  GENESIS_MODEL_URL=https://api.deepseek.com/v1 GENESIS_MODEL_KEY=sk-… GENESIS_MODEL=deepseek-chat npm run live
//   Local (Ollama):    GENESIS_MODEL_URL=http://localhost:11434/v1 GENESIS_MODEL=qwen3 npm run live
//
// Options: GENESIS_YEARS (default 6), GENESIS_SEED, GENESIS_CLAUDE_MODEL (default claude-sonnet-5-5).

import { World, DEFAULT_CONFIG } from '../src/sim/world.ts'
import { HOUR, YEAR } from '../src/sim/time.ts'
import { MindManager } from '../src/ai/mind.ts'
import { NATIVE_ID, type MindProfile } from '../src/ai/providers.ts'
import { status } from '../src/sim/diplomacy.ts'

const env = process.env
const years = +(env.GENESIS_YEARS ?? 6)
const budget = +(env.GENESIS_BUDGET ?? 1)
const anthropicKey = env.ANTHROPIC_API_KEY ?? ''
let profile: MindProfile | null = null
let modelId = ''
if (env.GENESIS_MODEL_URL) {
  profile = {
    id: 'p_live', label: env.GENESIS_MODEL ?? 'model', kind: 'openai', vendor: 'custom', baseUrl: env.GENESIS_MODEL_URL, apiKey: env.GENESIS_MODEL_KEY ?? '',
    model: env.GENESIS_MODEL ?? '', jsonMode: true, useProxy: false, priceIn: +(env.GENESIS_PRICE_IN ?? 0.3), priceOut: +(env.GENESIS_PRICE_OUT ?? 0.5),
  }
  modelId = profile.id
} else if (anthropicKey) {
  modelId = env.GENESIS_CLAUDE_MODEL ?? 'claude-sonnet-5-5'
} else {
  console.error('No model to talk to. Set ANTHROPIC_API_KEY, or GENESIS_MODEL_URL (+ GENESIS_MODEL, GENESIS_MODEL_KEY). See the top of scripts/live-diplomacy.ts.')
  process.exit(2)
}

const w = new World({ ...DEFAULT_CONFIG, seed: +(env.GENESIS_SEED ?? Math.floor(Math.random() * 1e6)), tribes: 4, popCap: 120 })
const m = new MindManager()
m.update({
  apiKey: anthropicKey, enabled: true, nativeCrowd: true, model: NATIVE_ID, profiles: profile ? [profile] : [],
  maxMinds: 4, maxConcurrent: 4, maxPerMinute: 30, budgetUsd: budget, thinkEveryHours: 8, reflections: false, chronicler: false, effort: 'low',
})
// model-led peoples: the first two; the others think with the Genesis Mind
for (const t of w.tribes.slice(0, 2)) t.model = modelId
m.attach(w)
console.log(`Live test: ${m.label(modelId)} leads the ${w.tribes.slice(0, 2).map((t) => t.name).join(' and the ')}; the Genesis Mind leads the rest. ${years} years, budget $${budget}.`)

const log: string[] = []
w.listeners.add((e) => {
  if (e.kind === 'tribe' && /WAR|PEACE|Alliance|trade|tribute|gifts|First contact|break|decline|refuse|Border/i.test(e.text)) log.push(`  y${Math.floor(e.t / YEAR)} ${e.icon} ${e.text.slice(0, 200)}`)
})
const seen = new Set<number>()
const t0 = Date.now()
const end = w.time + years * YEAR
while (w.time < end && m.stats.cost < budget) {
  w.tick(HOUR / 2)
  m.tick()
  await new Promise((r) => setTimeout(r, m.stats.inFlight ? 40 : 0))
  // print every model thought by a leader, as it lands
  for (const f of m.feed) {
    if (seen.has(f.id)) continue
    seen.add(f.id)
    if (f.model === 'Genesis Mind') continue
    const a = w.agentById.get(f.agentId)
    const lead = a && w.tribeById(a.tribeId)?.leaderId === a.id
    if (f.kind === 'error') console.log(`  ! ${f.name}: ${f.thought}`)
    else if (lead) console.log(`  ✦ ${f.name} (leader, ${f.tribe}): ${f.thought.slice(0, 220)}${f.say ? ` — says “${f.say.slice(0, 140)}”` : ''}\n      plan: ${f.plan}`)
  }
}
await new Promise((r) => setTimeout(r, 3000))
console.log('\nDiplomacy:')
console.log(log.join('\n') || '  (nothing yet)')
const live = w.tribes.filter((t) => t.alive)
console.log('\nWhere things stand:')
for (const a of live) for (const b of live) if (a.id < b.id) { const s = status(w, a.id, b.id); if (s !== 'unknown') console.log(`  ${a.name} – ${b.name}: ${s} (${Math.round(w.tribeRelation(a.id, b.id))})`) }
const st = m.stats
console.log(`\n${st.ok} model thoughts (${st.errors} failed), ${st.native} Genesis Mind decisions, $${st.cost.toFixed(3)} spent, ${((Date.now() - t0) / 1000).toFixed(0)}s.`)
process.exit(0)
