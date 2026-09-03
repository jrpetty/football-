/**
 * Captions for generated posts. Every figure comes from the object that drew
 * the card, so the caption cannot disagree with the image it travels with.
 */
import type { PlannedBuild } from '../plans.ts';
import type { VersusData } from './versus.ts';

const fmt = (n: number) => n.toLocaleString('en-GB');
const pct = (n: number) => `${n > 0 ? '+' : ''}${n}%`;
export const DISCLAIMER = 'Modelled, not measured. Ordering reliable, absolutes ±20%.';
export const PRICE_CAVEAT = 'Prices are recalled, not sourced — price the parts yourself.';
/**
 * Said on any build while one memory type is sourced and the other is not.
 * DDR5 was priced from September 2026 listings after memory roughly
 * quadrupled in a year; DDR4 still runs on the recalled spring figure. A
 * DDR4 build shown beside a DDR5 one is cheaper than reality by an unknown
 * amount, and the caption has to say so until DDR4 is sourced too.
 */
export const MEMORY_CAVEAT = 'Memory: DDR5 is priced from September 2026 listings; DDR4 is still the recalled spring figure and has very likely risen too.';
/** The caveat applies while memory.DDR5.32 is sourced and memory.DDR4.32 is not. */
export const memoryCaveat = (sourced: Set<string>) => (sourced.has('memory.DDR5.32') && !sourced.has('memory.DDR4.32') ? MEMORY_CAVEAT : '');

export function buildCaption(b: PlannedBuild, sourced: Set<string> = new Set()): string {
  const clears = b.rows.filter((r) => r.fps != null && r.fps >= b.refreshHz);
  const under = b.rows.filter((r) => r.fps != null && r.fps < b.refreshHz && r.limiter !== 'engine-cap');
  const capped = b.rows.filter((r) => r.limiter === 'engine-cap');
  const lines = [
    `The £${fmt(b.total)} build — ${clears.length} of ${b.rows.length} games clear its ${b.refreshHz}Hz target at ${b.resolution}.`,
    '',
    `${b.gpuShort} · ${b.cpuShort} · ${b.ram} · ${b.powerW}W draw, ${b.psuPartW}W supply`,
    '',
    `At ${b.resolution}, high, no upscaling:`,
    ...b.rows.map((r) => `· ${r.game} — ${r.fps == null ? 'will not run' : `${r.fps}fps`}`),
    '',
  ];
  if (under.length) lines.push(`${under.map((r) => `${r.game} at ${r.fps}`).join(', ')} ${under.length === 1 ? 'falls' : 'fall'} short of ${b.refreshHz}Hz — for ${under.length === 1 ? 'that game' : 'those'} this is a ${b.resolution} panel, not a ${b.refreshHz}Hz one.`);
  if (capped.length) lines.push(`${capped.map((r) => r.game).join(' and ')} read${capped.length === 1 ? 's' : ''} ${capped[0].fps} because the engine is capped there — that is the game, not the build.`);
  const mem = memoryCaveat(sourced);
  lines.push('', `${DISCLAIMER} ${PRICE_CAVEAT}${mem ? ` ${mem}` : ''}`, '', '#pcbuild #buildapc #gamingpc #pcgaming #pchardware #buildoftheweek');
  return lines.join('\n');
}

export function versusCaption(v: VersusData): string {
  const rows = v.rows.filter((r) => r.a != null && r.b != null);
  const lead = v.summary.meanPct;
  const ahead = lead > 0 ? v.b : v.a;
  const lines = [
    lead === 0 ? `${v.a.short} vs ${v.b.short}: level on average.` : `${v.a.short} vs ${v.b.short}: ${ahead.short} ahead by ${Math.abs(lead)}% on average.`,
    '',
    `Same ${v.partnerName}, same memory, same settings on both sides. ${v.resolution}, high, no upscaling:`,
    ...rows.map((r) => `· ${r.game} — ${r.a} vs ${r.b}fps (${r.pct === 0 ? 'level' : pct(r.pct!)})`),
    '',
    `Wins: ${v.a.short} ${v.summary.wins.a}, ${v.b.short} ${v.summary.wins.b}, level ${v.summary.wins.tie}. "On average" is the geometric mean across these games; change the games and it moves. Ask what you play before you ask which is faster.`,
    '',
    DISCLAIMER,
    '',
    `#${v.kind} #versus #pcbuild #buildapc #pcgaming #pchardware`,
  ];
  return lines.join('\n');
}

export function pollCaption(open: { title: string; votes: number }[], asOf: string): string {
  const lines = open.length
    ? [`Which game should I add to the catalogue next? Votes so far, as of ${asOf}:`, '', ...open.map((r) => `· ${r.title} — ${r.votes} vote${r.votes === 1 ? '' : 's'}`), '', 'Reply with a game and it goes on the list. The top of the list gets added first.']
    : ['Which game should I add to the catalogue next? Reply with one and it goes on the list.', '', 'The top of the list gets added first.'];
  lines.push('', 'Adding a game is data entry, not code: what it needs to launch, how much memory it wants at each resolution, and reference frame rates to anchor the model. It ships when that record is complete and audited.', '', '#pcgaming #buildapc #whichgame #pchardware');
  return lines.join('\n');
}
