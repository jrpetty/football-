// ---------------------------------------------------------------------------
// Matching names on a team sheet to players in the squad.
//
// This is the part of reading a line-up photograph that actually goes wrong.
// The data feed uses short forms chosen for a fantasy game — Liverpool's
// goalkeeper is "A.Becker", their captain is "Virgil" — while a broadcast
// graphic prints "Alisson" and "Van Dijk". Neither string contains the other.
//
// So matching works on the full name, token by token, with the short form as
// one more candidate rather than the primary key. Where two players at a club
// cannot be told apart by what is printed, the match is reported as ambiguous
// instead of guessed: a confidently wrong eleven would quietly move a forecast
// and give the reader no way to notice.
// ---------------------------------------------------------------------------

/** The minimum a player needs to look like to be matched at all. */
export interface MatchCandidate {
  id: number
  /** Feed short form, e.g. "B.Fernandes". */
  name: string
  /** Full name, e.g. "Bruno Borges Fernandes". */
  fullName: string
  position: string
}

export type MatchQuality = 'exact' | 'strong' | 'weak' | 'ambiguous' | 'none'

export interface NameMatch {
  query: string
  player: MatchCandidate | null
  quality: MatchQuality
  /** Populated when more than one candidate fits equally well. */
  alternatives: MatchCandidate[]
}

/**
 * Letters that Unicode decomposition does not take apart.
 *
 * NFKD splits é into e plus an accent, but ø, ß and ł are distinct letters
 * with no decomposition — so stripping "non-letters" turned Nørgaard into
 * "n rgaard" and lost the match entirely. The Premier League is full of these:
 * Nørgaard, Højlund, Šeško, Ødegaard.
 */
const LETTER_EQUIVALENTS: Record<string, string> = {
  ø: 'o', Ø: 'o', æ: 'ae', Æ: 'ae', œ: 'oe', Œ: 'oe', ß: 'ss',
  đ: 'd', Đ: 'd', ð: 'd', Ð: 'd', þ: 'th', Þ: 'th',
  ł: 'l', Ł: 'l', ı: 'i', İ: 'i', ŋ: 'n', Ŋ: 'n', ħ: 'h', Ħ: 'h',
}

/**
 * Lowercase, fold accents and punctuation, collapse whitespace.
 *
 * Defensive about its input: this runs over feed data and over whatever a
 * vision model returned from a photograph, and neither is guaranteed to hand
 * over a string.
 */
export function normalizeName(raw: string | null | undefined): string {
  if (typeof raw !== 'string') return ''
  return raw
    .replace(/[øØæÆœŒßđĐðÐþÞłŁıİŋŊħĦ]/g, (ch) => LETTER_EQUIVALENTS[ch] ?? ch)
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    // A printed initial ("B. Fernandes") should tokenise the same as the
    // feed's "B.Fernandes".
    .replace(/\./g, ' ')
    .replace(/[^a-z\s'-]/g, ' ')
    .replace(/[-']/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function tokens(raw: string): string[] {
  return normalizeName(raw).split(' ').filter(Boolean)
}

/**
 * Particles are part of a surname, not separate names.
 *
 * "Van Dijk" and "Dijk" must reach the same player, and "de Bruyne" should not
 * match every player whose full name happens to contain "de".
 */
const PARTICLES = new Set(['van', 'de', 'der', 'den', 'dos', 'da', 'di', 'del', 'la', 'le', 'bin', 'al'])

/** The surname as printed, particles kept attached. */
function surname(raw: string): string {
  const t = tokens(raw)
  if (t.length === 0) return ''
  let start = t.length - 1
  while (start > 0 && PARTICLES.has(t[start - 1]!)) start--
  return t.slice(start).join(' ')
}

/** Score a candidate against a printed name. Higher is better; 0 is no match. */
function score(query: string, c: MatchCandidate): number {
  const q = normalizeName(query)
  const qTokens = tokens(query)
  if (q.length === 0 || qTokens.length === 0) return 0

  const short = normalizeName(c.name)
  const full = normalizeName(c.fullName)
  const fullTokens = tokens(c.fullName)
  const shortTokens = tokens(c.name)

  // An exact hit on either form is as good as it gets.
  if (q === full || q === short) return 100

  const qSurname = surname(query)
  const cSurname = surname(c.fullName)
  const cShortSurname = surname(c.name)

  // Every printed token appears in the full name — "Bruno Fernandes" inside
  // "Bruno Borges Fernandes".
  const allPresent = qTokens.every((t) => fullTokens.includes(t) || shortTokens.includes(t))
  if (allPresent && qTokens.length > 1) return 90

  // Surnames agree, which is what a team sheet usually prints.
  if (qSurname.length > 2 && (qSurname === cSurname || qSurname === cShortSurname)) {
    // A matching first initial makes it stronger still.
    const qFirst = qTokens[0]!
    const cFirst = fullTokens[0] ?? ''
    if (qTokens.length > 1 && cFirst && qFirst[0] === cFirst[0]) return 85
    return 70
  }

  // A single printed token that is somebody's whole short form ("Gakpo").
  if (qTokens.length === 1 && (qTokens[0] === short || qTokens[0] === cShortSurname)) return 80

  // Last resort: the printed name appears inside the full name.
  if (q.length >= 4 && full.includes(q)) return 50

  return 0
}

/**
 * Match one printed name against a squad.
 *
 * Equal-scoring candidates are reported as ambiguous rather than resolved by
 * order — two brothers at the same club is exactly the case where a silent
 * guess does damage.
 */
export function matchName(query: string, squad: readonly MatchCandidate[]): NameMatch {
  const scored = squad
    .map((player) => ({ player, value: score(query, player) }))
    .filter((x) => x.value > 0)
    .sort((a, b) => b.value - a.value)

  if (scored.length === 0) return { query, player: null, quality: 'none', alternatives: [] }

  const best = scored[0]!
  const tied = scored.filter((x) => x.value === best.value)
  if (tied.length > 1) {
    return { query, player: null, quality: 'ambiguous', alternatives: tied.map((x) => x.player) }
  }

  const quality: MatchQuality = best.value >= 100 ? 'exact' : best.value >= 70 ? 'strong' : 'weak'
  return { query, player: best.player, quality, alternatives: [] }
}

export interface LineupMatchResult {
  matched: { query: string; player: MatchCandidate; quality: MatchQuality }[]
  /** Names that could not be resolved, with any near misses. */
  unresolved: NameMatch[]
  /** Player ids of everyone matched. */
  ids: number[]
}

/**
 * Match a whole printed eleven.
 *
 * A player already claimed by an earlier name is not offered again, so two
 * similar printed names cannot both collapse onto the same player and silently
 * produce a ten-man side. Confident names are resolved first, so a certain
 * match is never blocked by a weaker one having taken its player.
 */
export function matchLineup(names: readonly string[], squad: readonly MatchCandidate[]): LineupMatchResult {
  const rank: Record<MatchQuality, number> = { exact: 0, strong: 1, weak: 2, ambiguous: 3, none: 4 }
  const order = names
    .map((query) => ({ query, quality: matchName(query, squad).quality }))
    .sort((a, b) => rank[a.quality] - rank[b.quality])
    .map((x) => x.query)

  const matched: LineupMatchResult['matched'] = []
  const unresolved: NameMatch[] = []
  const claimed = new Set<number>()

  for (const query of order) {
    const available = squad.filter((p) => !claimed.has(p.id))
    const result = matchName(query, available)
    if (result.player) {
      claimed.add(result.player.id)
      matched.push({ query, player: result.player, quality: result.quality })
    } else {
      unresolved.push(result)
    }
  }

  // Report in the order the names were given, not the order they were resolved.
  matched.sort((a, b) => names.indexOf(a.query) - names.indexOf(b.query))
  return { matched, unresolved, ids: matched.map((m) => m.player.id) }
}
