// For players new to DayZ: a first-hour walkthrough, a glossary of the words
// players use (KOS, NWAF, freshie…), and plainer answers (jargon explained the
// first time it comes up) while beginner mode is on.

export interface GuideStep {
  readonly title: string
  readonly line: string
}

/** The first hour after spawning, in order. */
export const FIRST_HOUR: readonly GuideStep[] = [
  { title: 'Find out where you are', line: 'Walk to the next town sign (on Chernarus and Sakhal they are in Cyrillic: say it the way it looks). Tell me "I\'m at …" and I can plan from there.' },
  { title: 'Drink and eat', line: 'Most villages have a water pump: use it to drink. Pick apples and plums from trees. Pond and river water needs boiling or purification tablets, or you can catch cholera.' },
  { title: 'Search houses', line: 'Small villages are calmer than Chernogorsk or Elektrozavodsk. Look for a knife, a canteen or bottles, matches, a backpack and warm clothes.' },
  { title: 'Stop bleeding', line: 'A red drop on the right of the screen means you are bleeding: use a bandage or rags. A knife cuts spare clothing into rags.' },
  { title: 'Stay warm and dry', line: 'Rain and swimming soak you, and cold, wet players get sick. Dry off by a fire, change wet clothes, and a raincoat helps.' },
  { title: 'Deal with the infected', line: 'Crouch-walk past them where you can. Fight one at a time with a melee weapon; running draws more of them.' },
  { title: 'Be careful with players', line: 'On busy servers many players shoot first. Keep to tree lines, cross open fields quickly, and talk from cover if you want to be friendly.' },
  { title: 'Get a first gun', line: 'Police stations and hunting stands first; military buildings further inland have better guns, and more players.' },
  { title: 'Let me remember things', line: 'Tell me where your base, stash or car is and what you saw ("I saw two players near Gorka"); I mark it and guide you back. Log out somewhere quiet: your character stays where you left it.' },
]

export interface GlossaryEntry {
  readonly term: string
  readonly re: RegExp
  readonly meaning: string
}

export const GLOSSARY: readonly GlossaryEntry[] = [
  { term: 'KOS', re: /\bk\.?o\.?s\b|\bkill on sight\b/i, meaning: 'Kill on sight: shooting other players without talking first. Common on busy public servers.' },
  { term: 'NWAF', re: /\bnwaf\b|\bnorth[- ]?west air ?field\b/i, meaning: 'North-West Airfield, near Vybor on Chernarus: some of the best military loot on the map, and many players.' },
  { term: 'NEAF', re: /\bneaf\b|\bnorth[- ]?east air ?field\b/i, meaning: 'North-East Airfield, at Krasnostav on Chernarus: military loot in the north-east.' },
  { term: 'Freshie', re: /\bfreshies?\b|\bfresh ?spawns?\b|\bfresh\b(?= player)/i, meaning: 'A player who has just spawned on the coast with almost nothing.' },
  { term: 'Geared', re: /\bgeared\b|\bgeared up\b/i, meaning: 'A player with good kit: guns, armour, a backpack.' },
  { term: 'Infected', re: /\binfected\b|\bzeds?\b|\bzombies?\b/i, meaning: 'The game\'s zombies. They hear running and gunshots and hit hard; crouching quietly past them is often safest.' },
  { term: 'Loot tier', re: /\bloot tiers?\b|\btier [1-4]\b/i, meaning: 'How good the loot is in an area: tier 1 near the coast up to tier 4 at the north-west military bases on Chernarus. Higher tiers have better guns and gear.' },
  { term: 'Contaminated zone', re: /\b(contaminated|toxic|gas) (zones?|areas?)\b/i, meaning: 'An area with poison gas. You need a gas mask and an NBC suit; the permanent ones are on the map.' },
  { term: 'Persistence', re: /\bpersistence\b|\bpersistent\b/i, meaning: 'Things left in tents, barrels, stashes and bases stay through restarts until they despawn; a flag pole keeps a base from despawning while it is raised.' },
  { term: 'Despawn', re: /\bdespawns?\b|\bdespawning\b/i, meaning: 'When the server removes an item left alone too long: every item has a lifetime.' },
  { term: 'Restart', re: /\bserver restarts?\b|\brestarts?\b(?= (?:mean|is))/i, meaning: 'The server reboots on a schedule. You stay where you logged out; loot keeps respawning over time.' },
  { term: 'Combat logging', re: /\bcombat log(?:ging|ged)?\b/i, meaning: 'Logging out during a fight to escape it. Most servers ban it, and your character stays in the world for a few seconds while logging out.' },
  { term: 'Ghosting', re: /\bghost(?:ing)?\b/i, meaning: 'Switching servers to come back somewhere else and flank players. Against the rules of most servers.' },
  { term: 'Server hopping', re: /\bserver hop(?:ping)?\b|\bhopping\b/i, meaning: 'Switching servers to loot places that are picked clean on yours. Many community servers ban it.' },
  { term: 'Pristine … ruined', re: /\b(pristine|worn|badly damaged|ruined)\b/i, meaning: 'An item\'s condition: pristine, worn, damaged, badly damaged, ruined. Ruined items are useless; repair kits fix some damage.' },
  { term: 'Shock', re: /\bshock (?:damage)?\b|\bunconscious\b|\bknocked out\b/i, meaning: 'Shock damage knocks you out: blunt hits, some bullets and blood loss. You come round when it wears off, if nobody finishes you.' },
  { term: 'Hypothermia', re: /\bhypothermia\b|\bfreezing\b/i, meaning: 'Being too cold. Wet clothes and wind make it worse; a fire, dry clothes and warm layers fix it.' },
  { term: 'PvP / PvE', re: /\bpvp\b|\bpve\b/i, meaning: 'PvP: players fight each other. PvE: players fight only the world (infected, animals, weather); some servers are PvE only.' },
  { term: 'Low pop / high pop', re: /\b(low|high)[- ]?pop\b/i, meaning: 'How many players a server has. Low pop is quieter, high pop busier and more dangerous.' },
  { term: 'Friendly', re: /\bfriendly\??\b(?= (?:mean|means))|\bshout friendly\b/i, meaning: 'Players say "friendly" to show they do not want a fight. Trust it carefully.' },
  { term: 'Raid', re: /\braid(?:s|ing|ed)?\b/i, meaning: 'Breaking into someone\'s base for their loot. Ask me "how does raiding work" for the details.' },
  { term: 'Heli crash', re: /\bheli(?:copter)? crash(?:es)?\b/i, meaning: 'Crashed helicopters that appear around the map with military loot; the burning wreck can be seen from far away.' },
  { term: 'Trader', re: /\btraders?\b|\bsafe ?zones?\b/i, meaning: 'Modded community servers often have traders (shops) and safe zones. They are not in vanilla DayZ; add yours with "mark the trader at …" or paste a list under Your marks.' },
  { term: 'Vanilla / modded', re: /\bvanilla\b|\bmodded\b/i, meaning: 'Vanilla: the game as Bohemia ships it. Modded: community servers with extra content (traders, guns, cars, base parts).' },
  { term: 'Cherno, Elektro, Bere…', re: /\b(cherno|elektro|bere|svetlo|zeleno|krasno|novo|stary|novy)\b/i, meaning: 'Short names for Chernarus towns: Chernogorsk, Elektrozavodsk, Berezino, Svetlojarsk, Zelenogorsk, Krasnostav, Novodmitrovsk, Stary Sobor, Novy Sobor.' },
]

/** "What does KOS mean?", "what's a freshie", "define NWAF": the glossary entry, or null. */
export function glossaryAnswer(question: string): string | null {
  const q = question.toLowerCase()
  if (!/\b(what does|what do|what's|what is|what are|whats|define|meaning of|mean\b|stand for|what's a|what is a)\b/.test(q)) return null
  const e = GLOSSARY.find((g) => g.re.test(q))
  return e ? `${e.term}: ${e.meaning}` : null
}

export function isFirstHourQuestion(q: string): boolean {
  return /\b(first hour|i'?m new|new (?:player|to (?:dayz|the game|this))|how do i (?:start|begin)|beginner(?:'?s)? guide|getting started|just started playing|never played|teach me (?:the basics|how to play))\b/i.test(q)
}

export function firstHourText(): string {
  return `Your first hour: ${FIRST_HOUR.map((s, i) => `${i + 1}. ${s.title}`).join('; ')}. The steps are on the screen. Ask me about any of them, or what any word means.`
}

/** Words explained the first time they come up in an answer, for beginner mode. */
const PLAIN: readonly (readonly [RegExp, string])[] = [
  [/\bloot tiers? ([1-4](?:-[1-4])?)\b/i, ' (how good the loot is: 1 near the coast, higher further inland)'],
  [/\bKOS\b/, ' (kill on sight)'],
  [/\bNWAF\b/, ' (the North-West Airfield)'],
  [/\binfected\b/i, ' (zombies)'],
  [/\bNBC suit\b/i, ' (a protective suit against gas)'],
  [/\btetracycline\b/i, ' (antibiotics)'],
  [/\bdisinfectant\b/i, ' (cleans wounds so they do not get infected)'],
  [/\bsplint\b/i, ' (for a broken leg or arm)'],
  [/\bzero(?:ing|ed)?\b/i, ' (setting the sights for a distance)'],
  [/\bwith sprints\b/i, ' (sprinting while your stamina lasts)'],
]

/** The same answer with jargon explained the first time (beginner mode). */
export function plainer(text: string): string {
  let out = text
  for (const [re, add] of PLAIN) {
    const m = out.match(re)
    if (!m || m.index === undefined) continue
    const end = m.index + m[0].length
    if (out.slice(end, end + 2) === ' (') continue
    out = `${out.slice(0, end)}${add}${out.slice(end)}`
  }
  return out
}

/** One line for Claude when the player is new. */
export const BEGINNER_NOTE = 'The player is new to DayZ: use plain words, explain any game jargon briefly, and give one clear next step.'
