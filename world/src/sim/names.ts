import type { Rng } from './rng.ts'

// Each tribe gets its own phonology so names drift apart as cultures diverge.
const ONSETS = [
  ['k', 't', 'm', 'n', 'r', 'l', 's', 'h', 'b', 'd', 'a', 'e'],
  ['th', 'v', 'l', 'r', 'm', 's', 'n', 'f', 'el', 'ar', 'i'],
  ['z', 'k', 'q', 'sh', 'n', 'm', 't', 'y', 'a', 'u', 'o'],
  ['b', 'g', 'd', 'r', 'br', 'gr', 'dr', 'h', 'w', 'o', 'e'],
  ['s', 'p', 'm', 'n', 'l', 'ch', 'j', 'v', 'i', 'a', 'e'],
]
const VOWELS = [
  ['a', 'e', 'i', 'o', 'u', 'a', 'ai'],
  ['a', 'e', 'i', 'ia', 'ae', 'y'],
  ['a', 'u', 'o', 'aa', 'i'],
  ['o', 'a', 'e', 'u', 'or', 'ar'],
  ['i', 'e', 'a', 'io', 'ea'],
]
const CODAS = [
  ['', '', 'n', 'r', 'k', 'l', 'm'],
  ['', 'n', 'l', 'th', 's', 'r'],
  ['', '', 'n', 'z', 'q', 'h'],
  ['', 'n', 'd', 'k', 'rn', 'g'],
  ['', '', 'n', 's', 'l', 'ra'],
]

export function personName(rng: Rng, culture: number, sex: 'female' | 'male'): string {
  const c = culture % ONSETS.length
  const syl = rng.chance(0.55) ? 2 : rng.chance(0.7) ? 3 : 1
  let s = ''
  for (let i = 0; i < syl; i++) {
    s += rng.pick(ONSETS[c]) + rng.pick(VOWELS[c])
    if (i === syl - 1 || rng.chance(0.3)) s += rng.pick(CODAS[c])
  }
  if (sex === 'female' && rng.chance(0.55)) s += rng.pick(['a', 'ia', 'e', 'is', 'ra', 'el'])
  if (sex === 'male' && rng.chance(0.3)) s += rng.pick(['o', 'an', 'ek', 'us', 'or'])
  s = s.replace(/(.)\1\1+/g, '$1$1')
  if (s.length < 3) s += rng.pick(['n', 'ra', 'ko', 'li'])
  if (s.length > 10) s = s.slice(0, 9)
  return s.charAt(0).toUpperCase() + s.slice(1)
}

export const TRIBE_COLORS = ['#ff7a59', '#4fc3f7', '#ffd166', '#b388ff', '#69f0ae', '#ff8fb1', '#f6a04d', '#7ee0d6', '#e6ee9c', '#90a4ff']
export const TRIBE_EMBLEMS = ['🦅', '🐺', '🦌', '🐻', '🌙', '☀️', '🌊', '🔥', '🌲', '⛰️', '🐍', '🦉']
const TRIBE_WORDS = ['River', 'Stone', 'Ember', 'Wolf', 'Sun', 'Moon', 'Ash', 'Oak', 'Hawk', 'Salt', 'Cedar', 'Thunder', 'Dawn', 'Bear']
const TRIBE_SUFFIX = ['folk', 'kin', 'clan', 'people', 'band', 'walkers', 'born']

export function tribeName(rng: Rng): string {
  return `${rng.pick(TRIBE_WORDS)}${rng.pick(TRIBE_SUFFIX)}`.replace(/^(\w)/, (m) => m.toUpperCase())
}
