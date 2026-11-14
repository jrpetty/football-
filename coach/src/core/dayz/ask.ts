// Answers to DayZ place questions, on this computer: "where is Typobo?",
// "how do I get from Cherno to NWAF?", "nearest water to Turovo", "where am I?"
// (from the last road sign read off the screen).

import { DAYZ_MAPS, dayzMap, describePlace, featureLine, findPlace, km, neighbours, placesIn, region, routeLine, type DayzMapId, type FeatureKind } from './places.ts'

const FEATURE_WORDS: readonly (readonly [RegExp, FeatureKind])[] = [
  [/\b(water|pump|well|drink)\b/, 'water'],
  [/\b(hospital|medical|medic|clinic|med(?:s|icine)?|doctor)\b/, 'medical'],
  [/\b(fuel|gas station|petrol|gasoline)\b/, 'fuel'],
  [/\b(police|cop)\b/, 'police'],
  [/\b(fire ?station|firefighter)\b/, 'fire'],
  [/\b(military|army|barracks|base)\b/, 'military'],
  [/\b(lighthouse)\b/, 'lighthouse'],
  [/\b(heli|helicopter|crash ?site|crash)\b/, 'heliCrash'],
  [/\b(contaminated|toxic|gas zone)\b/, 'contaminated'],
]

/** A question about places: where something is, the way between two, or the nearest of something. */
export function isPlaceQuestion(question: string): boolean {
  const q = question.toLowerCase()
  if (/\b(where am i|where(?:'s| is| are)|how (?:do i |to )?get (?:to|from)|directions?|how far|which way|what(?:'s| is) (?:at|in|near)|tell me about|nearest|closest|sign (?:says|reads)|find)\b/.test(q)) return true
  // A bare place name, or what a sign looks like ("Typobo?").
  return q.trim().split(/\s+/).length <= 3 && findPlace(q.replace(/[?!.]/g, '')) !== null
}

/** The map being played: the screen's reading, else the player's choice. */
export function mapPreference(context: Readonly<Record<string, string | number>>): DayzMapId | null {
  for (const k of ['mapName', 'map']) {
    const m = dayzMap(typeof context[k] === 'string' ? (context[k] as string) : null)
    if (m) return m.id
  }
  return null
}

export function answerPlaceQuestion(question: string, context: Readonly<Record<string, string | number>>): string | null {
  return placeAnswer(question, context)?.text ?? null
}

/**
 * The answer, and whether it rests on the screen (the last road sign read:
 * "where am I", "how far from here"). One that does not is a plain map
 * look-up from the built-in data.
 */
export function placeAnswer(question: string, context: Readonly<Record<string, string | number>>): { readonly text: string; readonly fromScreen: boolean } | null {
  const text = placeAnswerText(question, context)
  if (text === null) return null
  // Answered the same without the sign: it did not use the screen.
  const { roadSign: _sign, ...rest } = context
  return { text, fromScreen: _sign !== undefined && placeAnswerText(question, rest) !== text }
}

function placeAnswerText(question: string, context: Readonly<Record<string, string | number>>): string | null {
  const q = question.toLowerCase()
  const prefer = mapPreference(context)
  const feature = FEATURE_WORDS.find(([re]) => re.test(q))?.[1] ?? null
  const named = placesIn(question, prefer)
  const sign = typeof context.roadSign === 'string' ? context.roadSign : null
  const here = sign ? findPlace(sign, prefer) : null

  if (/\bwhere am i\b/.test(q)) {
    if (!here) return 'I have not read a road sign yet. Walk to the next town sign, or tell me the name on it (as it looks is fine, e.g. "Typobo").'
    return `The last sign I read was ${sign}: ${describePlace(here)}`
  }
  // From A to B.
  if (named.length >= 2 && /\b(from|to|get|way|far|between|route|directions?)\b/.test(q)) return routeLine(named[0], named[1])
  if (named.length === 1 && here && /\b(get to|how far|which way|directions? to|way to)\b/.test(q)) return `From ${here.place.name}: ${routeLine(here, named[0])}`
  // The nearest of something.
  if (feature && /\b(nearest|closest|near|where|find)\b/.test(q)) {
    const from = named[0] ?? here
    if (!from) {
      const maps = prefer ? DAYZ_MAPS.filter((m) => m.id === prefer) : DAYZ_MAPS
      const count = maps.map((m) => `${m.name}: ${featureCount(m.id, feature)}`).join('; ')
      return `Nearest to where? Tell me a town (or read me the sign). ${count}.`
    }
    const line = featureLine(from.map, from.place, feature)
    return line ? `From ${from.place.name}: ${line}.` : `${from.map.name} has no ${feature === 'water' ? 'water pumps' : `${feature} places`} in the game's files.`
  }
  // Where a place is.
  if (named[0]) return describePlace(named[0])
  return null
}

function featureCount(map: DayzMapId, kind: FeatureKind): string {
  const w = DAYZ_MAPS.find((m) => m.id === map)!.world
  const n = { water: w.water, medical: w.medical, fuel: w.fuel, police: w.police, fire: w.fire, military: w.military, lighthouse: w.lighthouses, heliCrash: w.heliCrash, contaminated: w.contaminated }[kind].length
  return `${n} on the map`
}

/** One line for commentary when a new road sign is read: the place and what is next to it. */
export function signLine(sign: string, context: Readonly<Record<string, string | number>>): string | null {
  const m = findPlace(sign, mapPreference(context))
  if (!m) return null
  const nb = neighbours(m.map, m.place, 1)[0]
  const water = featureLine(m.map, m.place, 'water')
  return `${m.place.name}${m.place.cyrillic && m.map.signs === 'cyrillic' ? ` (${m.place.cyrillic})` : ''}, ${region(m.map, m.place.x, m.place.y).replace(/^the /, '')}.${nb ? ` ${nb.place.name} is ${km(nb.m)} ${nb.dir}.` : ''}${water ? ` ${water.replace(/^n/, 'N')}.` : ''}`
}

// --- Survival questions, from the pack's own reference ------------------------------------------

/** Topic words → the playbook entry and reference item that answer them. */
const MECHANICS: readonly (readonly [RegExp, string, string])[] = [
  [/\bcholera\b|\bdirty water\b|\bpond water\b|\bwater (?:safe|clean)\b|\bpurif/, 'safe-water', 'Diseases from food and water'],
  [/\bsalmonell|\braw meat\b|\bcook(?:ing)? meat\b|\bfood poison|\bpoison(?:ed|ing)?\b|\bcharcoal\b/, 'cook-your-food', 'Diseases from food and water'],
  [/\bprion\b|\bkuru\b|\bhuman (?:meat|steak)\b|\bcannibal|\blaughing\b/, 'cook-your-food', 'Diseases from food and water'],
  [/\b(?:influenza|flu|common cold|sneez|cough|hypotherm|freez|wet clothes|too cold|overheat|hypertherm)/, 'stay-warm-and-dry', 'Other illnesses'],
  [/\b(?:wound )?infect|\bdisinfect|\btetracycline\b/, 'stop-bleeding', 'Bleeding and wounds'],
  [/\bbleed|\bbandage|\brags?\b|\bsewing kit\b/, 'stop-bleeding', 'Bleeding and wounds'],
  [/\bsplint|\bfractur|\bbroken (?:leg|bone|limb)\b|\bmorphine\b|\bcodeine\b/, 'splint-fractures', 'Fractures'],
  [/\bblood type|\btransfus|\bblood bag|\bsaline\b|\bhemolytic\b/, 'blood-types', 'Blood types'],
  [/\bcontaminat|\btoxic zone\b|\bgas (?:zone|mask|poison)|\bnbc\b|\bpo-?x\b/, 'contaminated-zones', 'Contaminated zones'],
  [/\bunconscious|\bknocked out\b|\bshock\b|\bpass(?:ed)? out\b/, 'stop-bleeding', 'Health, blood and shock'],
  [/\bstamina\b|\bweight\b|\boverloaded\b|\bheavy\b/, '', 'Stamina and weight'],
  [/\bstomach\b|\bvomit|\bstuffed\b|\bthrow(?:ing)? up\b/, 'cook-your-food', 'Food, water and stomach'],
  [/\binfected\b|\bzombies?\b/, 'quiet-around-infected', ''],
  [/\bloot\b|\bcentral economy\b|\btier\b/, '', 'Loot and the Central Economy'],
  [/\bjam(?:s|med|ming)?\b|\bmisfire|\bcleaning kit\b|\bweapon condition\b|\bruined gun\b/, '', 'Weapon condition'],
  [/\bzero(?:ing|ed)?\b|\bpage ?(?:up|down)\b/, '', 'Zeroing'],
]

/** A survival question answered from the pack (what it is, the fix, the numbers), or null. */
export function answerMechanics(question: string, profile: { readonly knowledge: { readonly playbook?: readonly { readonly id: string; readonly fix: string; readonly why?: string; readonly principle: string }[]; readonly reference?: readonly { readonly items: readonly { readonly name: string; readonly detail: string }[] }[] } }): string | null {
  const q = question.toLowerCase()
  const hit = MECHANICS.find(([re]) => re.test(q))
  if (!hit) return null
  const [, playbookId, refName] = hit
  const entry = profile.knowledge.playbook?.find((e) => e.id === playbookId)
  const item = profile.knowledge.reference?.flatMap((s) => s.items).find((i) => i.name === refName)
  const parts = [item?.detail, entry ? `${entry.fix}${entry.why ? ` ${entry.why}` : ''}` : null].filter((x): x is string => !!x)
  return parts.length ? parts.join(' ') : null
}
