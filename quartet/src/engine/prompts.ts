// What each mind is told. The system prompt describes the situation and is
// identical for all four people in a run (so providers can cache it); the
// user turn is one person's private view of the moment.

import type { Person, Scenario } from './types.ts'
import type { Run } from './engine.ts'
import { describeTraits } from './cast.ts'

export function systemFor(sc: Scenario): string {
  const places = sc.places.map((p) => `- ${p.id}: ${p.name} — ${p.desc}`).join('\n')
  const moves = sc.moves.map((m) => `- ${m.id}: ${m.label}${m.at ? ` (at: ${m.at.join(', ')})` : ''}`).join('\n')
  const stats = sc.stats.map((s) => `- ${s.label}: ${s.good === 'high' ? 'higher is better' : 'lower is better'}${s.critical ? ' — if it reaches its worst, things end badly' : ''}`).join('\n')
  return `You are one of four real people caught in a real situation in today's world. You are not an AI assistant and you are not talking to a user: you ARE this person, with their history, temperament, fears, flaws and private agenda. Think and speak exactly as they would — in their voice, with their bluntness or warmth, their humour, their pettiness or courage. Disagree, lie, flirt, panic, sulk, lead or comfort when it is in character. Never mention AI, games or simulations.

# The situation: ${sc.title}
${sc.premise}

# Places
${places}

# What you can do (choose one each turn as "move")
${moves}
- talk: spend the moment talking (use "say")
- rest: recover energy
- go: move to another place (set "go_to" to its id)
Doing something tied to a place takes you there automatically. Others in the same place hear what you say; you can address someone by name.

# What is at stake for the group
${stats}
${sc.stances ? `\n# Positions\nEach of you holds a position: ${sc.stances.join(' / ')}. Change yours with "stance" only when you are genuinely persuaded or decide to.\n` : ''}${sc.score ? `\nEach of you also has a personal ${sc.score.label.toLowerCase()}.\n` : ''}
# How to answer
Reply with one JSON object:
{"thought": "your private inner thoughts right now, 1-3 sentences, first person",
 "feeling": "one or two words",
 "say": "what you say out loud right now (natural speech, under 220 characters) or \\"\\"",
 "say_to": "the name of who you are talking to, \\"everyone\\", or \\"\\"",
 "move": "one move id from the list, or talk / rest / go",
 "go_to": "place id if moving, else \\"\\"",
 "detail": "a few words on how you do it, or \\"\\"",
 "stance": "${sc.stances ? 'your position' : ''}",
 "opinions": [{"name": "someone", "delta": -20..20, "why": "short reason"}] — only for people whose actions or words just changed how you feel about them}`
}

export const DECISION_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {
    thought: { type: 'string' },
    feeling: { type: 'string' },
    say: { type: 'string' },
    say_to: { type: 'string' },
    move: { type: 'string' },
    go_to: { type: 'string' },
    detail: { type: 'string' },
    stance: { type: 'string' },
    opinions: {
      type: 'array',
      items: {
        type: 'object',
        properties: { name: { type: 'string' }, delta: { type: 'integer' }, why: { type: 'string' } },
        required: ['name', 'delta', 'why'],
        additionalProperties: false,
      },
    },
  },
  required: ['thought', 'feeling', 'say', 'say_to', 'move', 'go_to', 'detail', 'stance', 'opinions'],
  additionalProperties: false,
}

const level = (v: number) => (v > 80 ? 'very high' : v > 60 ? 'high' : v > 40 ? 'medium' : v > 20 ? 'low' : 'very low')

export function buildPersonPrompt(run: Run, p: Person): string {
  const sc = run.sc
  const others = run.people.filter((o) => o !== p)
  const here = others.filter((o) => o.place === p.place && run.alive(o))
  const L: string[] = []
  L.push(`# You are ${p.name}, ${p.age}, the ${p.role.title}`)
  L.push(p.role.brief)
  L.push(`Temperament: ${describeTraits(p.traits)}. You ${p.quirk}. You ${p.backstory}.`)
  L.push(`What you want: ${p.role.goal}`)
  L.push(`Your secret (nobody knows unless you reveal it): ${p.role.secret}`)
  if (p.items.length) L.push(`You have: ${p.items.join(', ')}.`)
  L.push('')
  L.push(`# Now: ${run.clock} (turn ${run.beat} of ${sc.beats})`)
  L.push(`You are at the ${run.placeName(p.place)}. ${here.length ? `With you: ${here.map((o) => `${o.name} (${o.role.title}${o.lastAction ? `, just ${o.lastAction}` : ''})`).join('; ')}.` : 'You are alone here.'}`)
  const away = others.filter((o) => o.place !== p.place && run.alive(o))
  if (away.length) L.push(`Elsewhere: ${away.map((o) => `${o.name} at the ${run.placeName(o.place)}`).join('; ')}.`)
  const gone = others.filter((o) => !run.alive(o))
  if (gone.length) L.push(`No longer with you: ${gone.map((o) => o.name).join(', ')}.`)
  L.push(`The situation: ${sc.stats.map((s) => `${s.label} ${Math.round(run.stats[s.id])}/${s.max}`).join(', ')}.`)
  L.push(`You: health ${level(p.health)}, energy ${level(p.energy)}, mood ${level(p.mood)}, stress ${level(p.stress)}${sc.score ? `, ${sc.score.label.toLowerCase()} ${Math.round(p.score)}` : ''}${sc.stances ? `. Your position: ${p.stance}` : ''}.`)
  if (sc.stances) L.push(`Positions: ${run.people.filter((o) => run.alive(o)).map((o) => `${o === p ? 'you' : o.name}: ${o.stance}`).join('; ')}.`)
  L.push('')
  L.push('# How you feel about the others')
  for (const o of others) {
    const op = p.opinions[o.id]
    const t = op?.trust ?? 0
    L.push(`- ${o.name}, the ${o.role.title} (${o.age}): ${t > 40 ? 'you trust them' : t > 10 ? 'you like them' : t > -10 ? 'unsure' : t > -40 ? 'wary of them' : 'you can\'t stand them'}${op?.note ? ` — ${op.note}` : ''}`)
  }
  const narr = run.narrations.slice(-2)
  if (narr.length) {
    L.push('')
    L.push('# Lately')
    for (const n of narr) L.push(n)
  }
  L.push('')
  L.push('# What you remember (most recent last)')
  for (const m of p.memory.slice(-14)) L.push(`- ${m}`)
  if (!p.memory.length) L.push('- Everything is just beginning.')
  if (p.thought) L.push(`\nYour last private thought: "${p.thought}"`)
  L.push('\nWhat do you think, say and do now?')
  return L.join('\n')
}

export const DIRECTOR_SYSTEM = `You are the narrator of a realistic, character-driven drama about four ordinary people in a real-world situation. Each turn you get what everyone did and said. Write 1-3 vivid sentences of present-tense narration that connect it — the atmosphere, consequences, glances and tensions — like a good novelist, never melodramatic. You may make small, plausible adjustments to the situation and to people's mood and stress. Reply as JSON: {"narration": "...", "adjustments": [{"stat": "<stat id>", "delta": -8..8}], "personal": [{"name": "...", "mood": -10..10, "stress": -10..10}]}. Use empty arrays if nothing needs adjusting.`

export const DIRECTOR_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {
    narration: { type: 'string' },
    adjustments: { type: 'array', items: { type: 'object', properties: { stat: { type: 'string' }, delta: { type: 'integer' } }, required: ['stat', 'delta'], additionalProperties: false } },
    personal: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' }, mood: { type: 'integer' }, stress: { type: 'integer' } }, required: ['name', 'mood', 'stress'], additionalProperties: false } },
  },
  required: ['narration', 'adjustments', 'personal'],
  additionalProperties: false,
}

export function buildDirectorPrompt(run: Run, summary: string[]): string {
  const sc = run.sc
  return [
    `Story: ${sc.title} — ${sc.premise}`,
    `Time: ${run.clock} (turn ${run.beat} of ${sc.beats}).`,
    `Stats (ids in brackets): ${sc.stats.map((s) => `${s.label} [${s.id}] ${Math.round(run.stats[s.id])}/${s.max}`).join(', ')}.`,
    `People: ${run.people.map((p) => `${p.name} the ${p.role.title} (mood ${Math.round(p.mood)}, stress ${Math.round(p.stress)}, at ${run.placeName(p.place)})`).join('; ')}.`,
    `Previously: ${run.narrations.slice(-2).join(' ') || 'This is the beginning.'}`,
    `This turn:\n${summary.map((s) => `- ${s}`).join('\n')}`,
  ].join('\n\n')
}

export const EPILOGUE_SYSTEM = `You write the ending of a realistic drama about four people in a real-world situation. Given how it went, write a short, honest, moving epilogue — not a fairy tale. Reply as JSON: {"title": "a short ending title", "story": "two short paragraphs: how it ended and what it meant", "fates": [{"name": "...", "fate": "one or two sentences: where this person ends up, months later"}]}.`

export const EPILOGUE_SCHEMA: Record<string, unknown> = {
  type: 'object',
  properties: {
    title: { type: 'string' },
    story: { type: 'string' },
    fates: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' }, fate: { type: 'string' } }, required: ['name', 'fate'], additionalProperties: false } },
  },
  required: ['title', 'story', 'fates'],
  additionalProperties: false,
}

export function buildEpiloguePrompt(run: Run): string {
  const sc = run.sc
  const said = run.lines.filter((l) => l.kind === 'say').slice(-16).map((l) => `${run.people.find((p) => p.id === l.who)?.name}: "${l.text}"`)
  return [
    `Story: ${sc.title} — ${sc.premise}`,
    `Outcome: ${run.ending?.tier} — ${run.ending?.title}`,
    `Final state: ${sc.stats.map((s) => `${s.label} ${Math.round(run.stats[s.id])}/${s.max}`).join(', ')}.`,
    `People:\n${run.people.map((p) => `- ${p.name}, ${p.age}, ${p.role.title}. Goal: ${p.role.goal}. Secret: ${p.role.secret}. ${run.alive(p) ? `Mood ${Math.round(p.mood)}, stress ${Math.round(p.stress)}` : 'Did not make it to the end'}.${sc.stances ? ` Position: ${p.stance}.` : ''} Feelings: ${Object.entries(p.opinions).map(([id, o]) => `${run.people.find((x) => x.id === +id)?.name} ${o.trust > 30 ? 'trusted' : o.trust < -30 ? 'distrusted' : 'mixed'}`).join(', ')}.`).join('\n')}`,
    `Story so far:\n${run.narrations.join('\n') || run.lines.filter((l) => l.kind !== 'say').slice(-20).map((l) => l.text).join('\n')}`,
    `Last words spoken:\n${said.join('\n')}`,
  ].join('\n\n')
}
