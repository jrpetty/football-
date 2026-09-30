import type { World } from '../match/world'

// One line about what you have done in a training session, for the pause
// screen. Nothing before you have touched the ball: an empty tally is noise.
export function sessionLine(world: World): string | null {
  if (world.config.mode !== 'training') return null
  const s = world.session
  if (!s.touches && !s.strikes && !s.goals) return null
  const plural = (n: number, one: string) => `${n} ${one}${n === 1 ? '' : 's'}`
  const parts = [plural(s.touches, 'touch').replace('touchs', 'touches'), plural(s.strikes, 'strike'), plural(s.goals, 'goal')]
  if (s.topSpeed > 1) parts.push(`fastest ${Math.round(s.topSpeed * 3.6)} km/h`)
  return parts.join(' · ')
}
