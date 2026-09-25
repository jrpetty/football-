/**
 * Pure "story" helpers for the simulation replays (Survival Island, The
 * Escape Room, The Startup, The Liar's Table): the headline and plain-English
 * line for each step, the island timeline, lock attempts, the suspects board
 * with its contradictions, the best moment for the Presenter and the finale.
 *
 * Everything is derived from recorded replay data (ReplayData.sim /
 * ReplayFrame.sim and the frame texts); nothing is invented. No React here,
 * so the unit tests can import it directly.
 */
import type {
  EscapeSimFrame,
  EscapeSimLock,
  EscapeSimWorld,
  IslandEventTag,
  IslandSimFrame,
  IslandSimWorld,
  LiarsFact,
  LiarsSimFrame,
  LiarsSimWorld,
  ReplayData,
  ReplayFrame,
  StartupSimFrame,
  StartupSimWorld,
} from '../../types.ts';

export type Tone = 'good' | 'bad' | 'neutral';

export interface StepStory {
  /** Short headline, e.g. "Day 5 · Afternoon: finds fresh water". */
  headline: string;
  /** One plain-English sentence: the model's quoted action and its consequence. */
  line: string;
  tone: Tone;
}

export interface Finale {
  tone: Tone | 'warn';
  /** Big verdict, e.g. "Rescued on day 7". */
  title: string;
  subtitle?: string;
  /** The key number, e.g. { value: '7', label: 'days' }. */
  big?: { value: string; label: string };
  stats: Array<{ label: string; value: string; tone?: Tone }>;
  /** Plain-English notes on what went right / wrong. */
  notes: Array<{ text: string; tone: Tone }>;
}

// ─── shared helpers ─────────────────────────────────────────────────────────

export function clip(text: string, max: number): string {
  const t = text.replace(/\s+/g, ' ').trim();
  return t.length > max ? `${t.slice(0, max - 1).trimEnd()}…` : t;
}

export function money(n: number): string {
  const v = Math.round(n);
  const s = `$${Math.abs(v).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')}`;
  return v < 0 ? `−${s}` : s;
}

export function signedMoney(n: number): string {
  return Math.round(n) >= 0 ? `+${money(n)}` : money(n);
}

export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

function andList(items: string[]): string {
  return items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}

function quoted(model: string, action: string | undefined, consequence: string | undefined, max = 210): string {
  const parts: string[] = [];
  if (action) parts.push(`${model} chose “${clip(action, 70)}”.`);
  if (consequence) parts.push(consequence.trim());
  return clip(parts.join(' '), max);
}

/** Frames with sim data of one kind, keeping their index in the replay. */
function simFrames<K extends string, F extends { kind: K }>(frames: ReplayFrame[], kind: K, upTo = frames.length - 1): Array<{ i: number; f: ReplayFrame; s: F }> {
  const out: Array<{ i: number; f: ReplayFrame; s: F }> = [];
  for (let i = 0; i <= Math.min(upTo, frames.length - 1); i++) {
    const s = frames[i]!.sim as unknown as F | undefined;
    if (s && s.kind === kind) out.push({ i, f: frames[i]!, s });
  }
  return out;
}

// ─── Survival Island ────────────────────────────────────────────────────────

export const PHASE_NAMES = ['Morning', 'Afternoon', 'Evening', 'Night'];

export function phaseName(phase: number): string {
  return phase < 0 ? 'Dawn' : (PHASE_NAMES[phase] ?? '');
}

/** Headline phrase and tone for each island event, in priority order. */
export const ISLAND_EVENTS: Record<IslandEventTag, { phrase: string; tone: Tone; label: string }> = {
  rescued: { phrase: 'rescued by the passing ship!', tone: 'good', label: 'Rescued' },
  died: { phrase: 'dies', tone: 'bad', label: 'Died' },
  survived: { phrase: 'survives every day', tone: 'good', label: 'Survived' },
  poison: { phrase: 'eats poisonous berries', tone: 'bad', label: 'Ate poison berries' },
  'ship-ack': { phrase: 'the ship sees the signal!', tone: 'good', label: 'Ship saw the signal' },
  'found-water': { phrase: 'spots fresh water (the spring)', tone: 'good', label: 'Spotted the spring' },
  'signal-lit': { phrase: 'lights the signal fire', tone: 'good', label: 'Lit the signal fire' },
  'ship-pass': { phrase: 'the supply ship sails past', tone: 'bad', label: 'Ship passed by' },
  'signal-built': { phrase: 'builds the signal pile on the summit', tone: 'good', label: 'Built the signal pile' },
  shelter: { phrase: 'builds a shelter', tone: 'good', label: 'Built a shelter' },
  campfire: { phrase: 'builds a campfire', tone: 'good', label: 'Built a campfire' },
  spear: { phrase: 'crafts a fishing spear', tone: 'good', label: 'Crafted a spear' },
  bottle: { phrase: 'finds a message in a bottle', tone: 'good', label: 'Found the bottle' },
  fish: { phrase: 'catches fish', tone: 'good', label: 'Caught fish' },
  invalid: { phrase: 'wastes the turn', tone: 'bad', label: 'Wasted turn' },
  hurt: { phrase: 'gets hurt', tone: 'bad', label: 'Hurt' },
  drink: { phrase: 'drinks', tone: 'neutral', label: 'Drank' },
  eat: { phrase: 'eats', tone: 'neutral', label: 'Ate' },
};

const ISLAND_ORDER = Object.keys(ISLAND_EVENTS) as IslandEventTag[];

/** Events worth a marker on the day timeline. */
export const ISLAND_KEY_EVENTS: IslandEventTag[] = ['rescued', 'died', 'survived', 'poison', 'ship-ack', 'found-water', 'signal-lit', 'ship-pass', 'signal-built', 'shelter', 'campfire', 'spear', 'bottle'];

function mainEvent(tags: IslandEventTag[]): IslandEventTag | null {
  for (const t of ISLAND_ORDER) if (tags.includes(t)) return t;
  return null;
}

const DIR_WORDS: Record<string, string> = { N: 'north', S: 'south', E: 'east', W: 'west' };

function islandVerb(action: string | undefined): string {
  const a = (action ?? '').toUpperCase();
  let m: RegExpMatchArray | null;
  if ((m = a.match(/^MOVE ([NSEW])(?: (\d))?/))) return `walks ${DIR_WORDS[m[1]!]}${m[2] && m[2] !== '1' ? ` ${m[2]} tiles` : ''}`;
  if (a.startsWith('GATHER')) return 'gathers supplies';
  if (a.startsWith('REST')) return 'rests';
  if (a.startsWith('COOK')) return 'cooks fish';
  if (a.startsWith('BUILD FIRE')) return 'refuels the campfire';
  if (a.startsWith('FISH')) return 'fishes without luck';
  return a ? `tries “${clip(action!, 40)}”` : 'waits';
}

export function islandStory(world: IslandSimWorld, frame: ReplayFrame, model: string): StepStory {
  const s = frame.sim as IslandSimFrame;
  const when = s.phase < 0 ? 'Day 1 · Dawn' : `Day ${s.day} · ${phaseName(s.phase)}`;
  const ev = mainEvent(s.events);
  let phrase: string;
  let tone: Tone = frame.tone ?? 'neutral';
  if (s.phase < 0) {
    phrase = 'washed up on the beach';
    tone = 'neutral';
  } else if (ev) {
    phrase = ISLAND_EVENTS[ev].phrase;
    if (ev === 'died' && s.cause) phrase = `dies of ${s.cause}`;
    if (ev === 'survived') phrase = `survives all ${world.maxDays} days`;
    if (ev === 'drink' && (frame.outcome ?? '').toLowerCase().includes('rain')) phrase = 'catches rainwater';
    if (ev === 'ship-pass' && s.signal === 'lit') phrase = 'the ship passes, but the crew is not convinced';
    tone = ISLAND_EVENTS[ev].tone;
  } else if (s.phase === 3) {
    phrase = 'night falls';
    tone = frame.tone ?? 'neutral';
  } else phrase = islandVerb(frame.action);
  const consequence = frame.outcome ?? '';
  return { headline: `${when}: ${phrase}`, line: s.phase === 3 || s.phase < 0 ? clip(consequence, 210) : quoted(model, frame.action, consequence), tone };
}

export interface TimelineMark {
  day: number;
  phase: number;
  frame: number;
  tag: IslandEventTag;
}

/** Key events on the day timeline (every frame, not only up to the cursor). */
export function islandTimeline(frames: ReplayFrame[]): TimelineMark[] {
  const out: TimelineMark[] = [];
  for (const { i, s } of simFrames<'island', IslandSimFrame>(frames, 'island')) {
    for (const tag of s.events) if (ISLAND_KEY_EVENTS.includes(tag)) out.push({ day: s.day, phase: Math.max(0, s.phase), frame: i, tag });
  }
  return out;
}

/** Tile path walked up to (and including) frame `idx`. */
export function islandTrail(frames: ReplayFrame[], idx: number): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  for (const { s } of simFrames<'island', IslandSimFrame>(frames, 'island', idx)) {
    const last = out[out.length - 1];
    if (!last || last[0] !== s.pos[0] || last[1] !== s.pos[1]) out.push([s.pos[0], s.pos[1]]);
  }
  return out;
}

/** Has `tag` happened at or before frame `idx`? */
export function islandHappened(frames: ReplayFrame[], idx: number, tag: IslandEventTag): boolean {
  return simFrames<'island', IslandSimFrame>(frames, 'island', idx).some(({ s }) => s.events.includes(tag));
}

export function islandFinale(world: IslandSimWorld, frames: ReplayFrame[]): Finale {
  const all = simFrames<'island', IslandSimFrame>(frames, 'island');
  const last = all[all.length - 1]?.s;
  const first = (tag: IslandEventTag) => all.find(({ s }) => s.events.includes(tag))?.s;
  const count = (tag: IslandEventTag) => all.filter(({ s }) => s.events.includes(tag)).length;
  const notes: Finale['notes'] = [];
  const water = first('found-water');
  notes.push(water ? { text: `Spotted the spring on day ${water.day}.`, tone: 'good' } : { text: 'Never found the spring.', tone: 'bad' });
  if (!count('drink')) notes.push({ text: 'Never drank any water.', tone: 'bad' });
  const poison = count('poison');
  if (poison) notes.push({ text: `Ate poisonous berries ${poison === 1 ? 'once' : `${poison} times`}.`, tone: 'bad' });
  const shelter = first('shelter');
  notes.push(shelter ? { text: `Built a shelter on day ${shelter.day}.`, tone: 'good' } : { text: 'Never built a shelter.', tone: 'neutral' });
  const lit = all.filter(({ s }) => s.events.includes('signal-lit')).map(({ s }) => s.day);
  const passes = all.filter(({ s }) => s.events.includes('ship-pass')).map(({ s }) => s.day);
  if (lit.length) notes.push({ text: `Lit the signal fire on day${lit.length > 1 ? 's' : ''} ${andList(lit.map(String))}.`, tone: 'good' });
  else if (first('signal-built')) notes.push({ text: 'Built the signal pile but never lit it.', tone: 'bad' });
  else notes.push({ text: 'Never built a signal fire.', tone: 'bad' });
  if (passes.length && !last?.rescued) notes.push({ text: `The ship passed on day${passes.length > 1 ? 's' : ''} ${andList(passes.map(String))} and sailed on.`, tone: 'bad' });
  const wasted = count('invalid');
  if (wasted) notes.push({ text: `${plural(wasted, 'turn')} wasted on invalid commands.`, tone: 'bad' });
  const nights = all.filter(({ s }) => s.phase === 3 && s.alive).length;
  const stats: Finale['stats'] = [
    { label: 'Nights survived', value: `${nights} / ${world.maxDays}` },
    { label: 'Signal sightings', value: `${last?.sightings ?? 0} / ${world.signalsNeeded}` },
  ];
  if (!last) return { tone: 'neutral', title: 'No island data recorded', stats: [], notes: [] };
  if (last.rescued) {
    return { tone: 'good', title: `Rescued on day ${last.day}`, subtitle: `The crew saw the signal fire blazing on the summit.`, big: { value: String(last.day), label: `day of ${world.maxDays}` }, stats, notes };
  }
  if (!last.alive) {
    return { tone: 'bad', title: `Died of ${last.cause ?? 'exhaustion'} on day ${last.day}`, subtitle: 'What went wrong:', big: { value: String(last.day), label: `day of ${world.maxDays}` }, stats, notes };
  }
  return { tone: 'warn', title: `Survived all ${world.maxDays} days`, subtitle: 'Alive — but nobody came.', big: { value: String(world.maxDays), label: 'days alive' }, stats, notes };
}

// ─── The Escape Room ────────────────────────────────────────────────────────

export interface LockState {
  lock: EscapeSimLock;
  open: boolean;
  /** Move at which it opened. */
  openedAt: number | null;
  attempts: Array<{ value: string; move: number; ok: boolean }>;
  /** Frame index where it opened (for the timeline). */
  openedFrame: number | null;
}

export function escapeLocks(world: EscapeSimWorld, frames: ReplayFrame[], idx: number): LockState[] {
  const states = new Map<string, LockState>(world.locks.map((l) => [l.id, { lock: l, open: false, openedAt: null, attempts: [], openedFrame: null }]));
  for (const { i, s } of simFrames<'escape', EscapeSimFrame>(frames, 'escape', idx)) {
    const e = s.event;
    const st = e.target ? states.get(e.target) : undefined;
    if (st && e.type === 'wrong') st.attempts.push({ value: e.value ?? '?', move: s.move, ok: false });
    if (st && (e.type === 'unlock' || e.type === 'escape') && !st.open) {
      if (e.value) st.attempts.push({ value: e.value, move: s.move, ok: true });
    }
    for (const id of s.unlocked) {
      const u = states.get(id);
      if (u && !u.open) {
        u.open = true;
        u.openedAt = s.move;
        u.openedFrame = i;
      }
    }
  }
  return world.locks.map((l) => states.get(l.id)!);
}

function lockLabel(world: EscapeSimWorld, id: string | undefined): string {
  const l = world.locks.find((x) => x.id === id);
  return l ? `the ${l.name}` : 'it';
}

export function escapeStory(world: EscapeSimWorld, frame: ReplayFrame, model: string): StepStory {
  const s = frame.sim as EscapeSimFrame;
  const e = s.event;
  const room = world.rooms[s.room] ?? '';
  const at = s.move === 0 ? 'Start' : `Move ${s.move}`;
  let phrase: string;
  let tone: Tone = 'neutral';
  switch (e.type) {
    case 'start':
      phrase = `locked in ${room}`;
      break;
    case 'escape':
      phrase = 'ESCAPES!';
      tone = 'good';
      break;
    case 'unlock': {
      const l = world.locks.find((x) => x.id === e.target);
      phrase = l?.door ? `${l.name} unlocks` : `the ${l?.name ?? 'lock'} opens`;
      tone = 'good';
      break;
    }
    case 'wrong':
      phrase = `wrong ${world.locks.find((x) => x.id === e.target)?.kind === 'word' ? 'word' : 'code'} on ${lockLabel(world, e.target)}`;
      tone = 'bad';
      break;
    case 'take':
      phrase = `picks up the ${andList(e.items ?? [])}`;
      break;
    case 'combine':
      phrase = `combines items into the ${andList(e.items ?? [])}`;
      tone = 'good';
      break;
    case 'go':
      phrase = `walks into ${room}`;
      tone = 'good';
      break;
    case 'found':
      phrase = `finds the ${andList(e.items ?? [])}`;
      tone = 'good';
      break;
    case 'examine':
      phrase = 'examines something';
      break;
    case 'fail':
      phrase = 'that didn’t work';
      tone = 'bad';
      break;
    case 'invalid':
      phrase = 'wastes a move';
      tone = 'bad';
      break;
    default:
      phrase = 'looks around';
  }
  if (e.type === 'examine' && frame.action) phrase = `reads the ${clip(frame.action.replace(/^\s*(EXAMINE|X|INSPECT|READ|SEARCH|CHECK|OPEN|LOOK AT)\s+/i, '').toUpperCase(), 40)}`;
  const line = e.type === 'start' ? clip(frame.outcome ?? '', 200) : quoted(model, frame.action, frame.outcome);
  return { headline: `${at}: ${phrase}`, line, tone };
}

export function escapeFinale(world: EscapeSimWorld, frames: ReplayFrame[]): Finale {
  const all = simFrames<'escape', EscapeSimFrame>(frames, 'escape');
  const last = all[all.length - 1]?.s;
  if (!last) return { tone: 'neutral', title: 'No escape data recorded', stats: [], notes: [] };
  const locks = escapeLocks(world, frames, frames.length - 1);
  const opened = locks.filter((l) => l.open).length;
  const wrong = all.filter(({ s }) => s.event.type === 'wrong').length;
  const wasted = all.filter(({ s }) => s.event.type === 'invalid').length;
  const stats: Finale['stats'] = [
    { label: 'Locks opened', value: `${opened} / ${locks.length}` },
    { label: 'Moves used', value: `${last.move} / ${world.budget}` },
    { label: 'Par (optimal)', value: String(world.optimal) },
  ];
  const notes: Finale['notes'] = [];
  if (wrong) notes.push({ text: `${plural(wrong, 'wrong code')} entered.`, tone: 'bad' });
  if (wasted) notes.push({ text: `${plural(wasted, 'move')} wasted on unreadable commands.`, tone: 'bad' });
  const stuck = locks.find((l) => !l.open && l.lock.room === last.room);
  if (!last.escaped && stuck) notes.push({ text: `Stuck at the ${stuck.lock.name} (${stuck.lock.tag}).`, tone: 'bad' });
  if (last.escaped) {
    const over = last.move - world.optimal;
    return {
      tone: 'good',
      title: `Escaped in ${last.move} moves`,
      subtitle: over <= 0 ? 'A perfect run — exactly par.' : `${plural(over, 'move')} over par (${world.optimal}).`,
      big: { value: String(last.move), label: `moves · par ${world.optimal}` },
      stats,
      notes,
    };
  }
  return {
    tone: 'bad',
    title: `Trapped in ${world.rooms[last.room] ?? 'the house'}`,
    subtitle: `Out of moves with ${opened} of ${locks.length} locks open.`,
    big: { value: `${opened}/${locks.length}`, label: 'locks opened' },
    stats,
    notes,
  };
}

/** Per-character comparison of an attempt with the answer (same length or not). */
export function charDiff(attempt: string, answer: string): Array<{ ch: string; ok: boolean }> {
  const a = attempt.includes(' ') || answer.includes(' ') ? attempt.split(' ') : Array.from(attempt);
  const b = answer.includes(' ') ? answer.split(' ') : Array.from(answer);
  return a.map((ch, i) => ({ ch, ok: b[i] === ch }));
}

// ─── The Startup ────────────────────────────────────────────────────────────

export function startupStory(world: StartupSimWorld, frame: ReplayFrame, model: string): StepStory {
  const s = frame.sim as StartupSimFrame;
  const head = `Month ${s.month} · ${s.calendar}`;
  let phrase: string;
  let tone: Tone;
  if (s.bankrupt) {
    phrase = 'BANKRUPT — the cash ran out';
    tone = 'bad';
  } else if (s.missed > 0 && s.missed >= 0.15 * Math.max(1, s.sold)) {
    phrase = `sold out — about ${Math.max(10, Math.round(s.missed / 10) * 10)} customers turned away`;
    tone = 'bad';
  } else if (s.inventory > Math.max(50, s.sold * 0.6)) {
    phrase = `${s.inventory} unsold units pile up`;
    tone = 'bad';
  } else {
    phrase = s.net >= 0 ? `profit ${signedMoney(s.net)}` : `loss ${money(s.net)}`;
    tone = s.net >= 0 ? 'good' : 'neutral';
  }
  const d = s.decisions;
  const action = `price $${d.price.toFixed(2)}, make ${d.produce}, marketing ${money(d.marketing)}${d.hire ? `, hire ${d.hire > 0 ? '+' : ''}${d.hire}` : ''}`;
  const line = clip(`${model} set ${action}. Sold ${s.sold} of ${s.demand} wanted; month ${s.net >= 0 ? 'profit' : 'loss'} ${signedMoney(s.net)}, cash ${money(s.cash)}.`, 220);
  void world;
  return { headline: `${head}: ${phrase}`, line, tone };
}

export function startupFinale(world: StartupSimWorld, frames: ReplayFrame[]): Finale {
  const all = simFrames<'startup', StartupSimFrame>(frames, 'startup');
  const last = all[all.length - 1]?.s;
  if (!last) return { tone: 'neutral', title: 'No startup data recorded', stats: [], notes: [] };
  // The final frame (no sim) carries the scored equity in its stats.
  const fin = frames[frames.length - 1]?.stats;
  const equity = typeof fin?.equity === 'number' ? fin.equity : last.equity;
  const base = Math.max(0, world.autopilotEquity);
  const gap = Math.max(5000, world.oracleEquity - base);
  const closed = last.bankrupt ? 0 : Math.max(0, Math.min(1, (equity - base) / gap));
  const stats: Finale['stats'] = [
    { label: 'Model equity', value: money(equity), tone: equity >= base ? 'good' : 'bad' },
    { label: 'Oracle equity', value: money(world.oracleEquity) },
    { label: 'Autopilot equity', value: money(world.autopilotEquity) },
  ];
  const notes: Finale['notes'] = [];
  const soldOut = all.filter(({ s }) => s.missed > 0).length;
  if (soldOut) notes.push({ text: `Sold out in ${plural(soldOut, 'month')}.`, tone: 'bad' });
  const glut = all.filter(({ s }) => s.inventory > Math.max(50, s.sold * 0.6)).length;
  if (glut) notes.push({ text: `Stock piled up unsold in ${plural(glut, 'month')}.`, tone: 'bad' });
  const best = all.reduce((a, b) => (b.s.net > a.s.net ? b : a), all[0]!);
  if (best.s.net > 0) notes.push({ text: `Best month: ${best.s.calendar} (${signedMoney(best.s.net)}).`, tone: 'good' });
  if (last.bankrupt) {
    return { tone: 'bad', title: `Bankrupt in month ${last.month}`, subtitle: 'The company ran out of cash.', stats, notes };
  }
  const vsOracle = equity - world.oracleEquity;
  return {
    tone: closed >= 0.5 ? 'good' : equity > base ? 'warn' : 'bad',
    title: `Finished with ${money(equity)}`,
    subtitle: `${vsOracle >= 0 ? `${money(vsOracle)} ahead of` : `${money(-vsOracle)} behind`} the oracle · closed ${Math.round(closed * 100)}% of the gap from autopilot to oracle`,
    big: { value: `${Math.round(closed * 100)}%`, label: 'of the gap closed' },
    stats,
    notes,
  };
}

// ─── The Liar's Table ───────────────────────────────────────────────────────

export interface BoardCell {
  /** What the suspect said about themself (latest). */
  said: LiarsFact | null;
  /** Everything else placing (or not placing) them here. */
  others: LiarsFact[];
  conflict: null | { hard: boolean; a: LiarsFact; b: LiarsFact; since: number };
}

export interface LiarsBoard {
  cells: Record<string, BoardCell[]>;
  /** Slots the door log / a witness pins the theft to (empty = unknown yet). */
  door: number[];
  receiptsChecked: boolean;
  /** Frame index of the first hard contradiction, if any. */
  firstExposed: number | null;
  /** Hard contradictions revealed by the frame at `idx` itself. */
  newAt: Array<{ who: string; slot: number }>;
}

function clash(a: LiarsFact, b: LiarsFact): boolean {
  if (a.src === b.src) return false;
  if (a.yes && b.yes) return a.room !== b.room;
  if (a.yes !== b.yes) return a.room === b.room;
  return false;
}

export function liarsBoard(world: LiarsSimWorld, frames: ReplayFrame[], idx: number): LiarsBoard {
  const cells: Record<string, BoardCell[]> = {};
  for (const s of world.suspects) cells[s.name] = world.slots.map(() => ({ said: null, others: [], conflict: null }));
  const door = new Set<number>();
  let receipts: Array<{ who: string; slot: number }> | null = null;
  let firstExposed: number | null = null;
  let newAt: Array<{ who: string; slot: number }> = [];
  for (const { i, s } of simFrames<'liars', LiarsSimFrame>(frames, 'liars', idx)) {
    for (const d of s.door ?? []) door.add(d);
    if (s.receipts) receipts = s.receipts;
    const touched = new Set<string>();
    for (const f of s.facts) {
      const cell = cells[f.who]?.[f.slot];
      if (!cell) continue;
      if (f.src === f.who && !f.bought) cell.said = f;
      else cell.others.push(f);
      touched.add(`${f.who}|${f.slot}`);
    }
    if (s.receipts) for (const name of Object.keys(cells)) cells[name]!.forEach((_, slot) => touched.add(`${name}|${slot}`));
    const fresh: Array<{ who: string; slot: number }> = [];
    for (const key of touched) {
      const [who, slotS] = key.split('|');
      const slot = Number(slotS);
      const cell = cells[who!]![slot]!;
      const facts = [...(cell.said ? [cell.said] : []), ...cell.others];
      let found: BoardCell['conflict'] = null;
      for (let a = 0; a < facts.length && !found?.hard; a++)
        for (let b = a + 1; b < facts.length; b++) {
          const fa = facts[a]!;
          const fb = facts[b]!;
          if (!clash(fa, fb)) continue;
          const hard = !fa.hedged && !fb.hedged;
          if (!found || (hard && !found.hard)) found = { hard, a: fa, b: fb, since: i };
          if (hard) break;
        }
      // A claimed bar purchase the receipts don't show.
      if (!found?.hard && receipts) {
        const bought = facts.find((f) => f.bought);
        if (bought && !receipts.some((r) => r.who === who && r.slot === slot)) {
          found = { hard: true, a: bought, b: { who: who!, slot, room: bought.room, yes: false, src: 'RECEIPT' }, since: i };
        }
      }
      if (found) {
        const prev = cell.conflict;
        if (!prev || (found.hard && !prev.hard)) {
          cell.conflict = found;
          if (found.hard) {
            fresh.push({ who: who!, slot });
            if (firstExposed === null) firstExposed = i;
          }
        }
      }
    }
    newAt = i === idx ? fresh : [];
  }
  return { cells, door: [...door].sort((a, b) => a - b), receiptsChecked: receipts !== null, firstExposed, newAt };
}

export function liarsStory(world: LiarsSimWorld, frames: ReplayFrame[], idx: number, model: string): StepStory {
  const frame = frames[idx]!;
  const s = frame.sim as LiarsSimFrame;
  const q = `Q${s.used}`;
  if (s.accuse) {
    const who = s.accuse.who;
    return s.accuse.correct
      ? { headline: `Verdict: ${model} accuses ${who} — correct!`, line: clip(`“${s.accuse.reason}” — ${frame.outcome ?? ''}`, 230), tone: 'good' }
      : { headline: `Verdict: accuses ${who} — wrong! The thief was ${world.culprit}`, line: clip(`“${s.accuse.reason}” — ${frame.outcome ?? ''}`, 230), tone: 'bad' };
  }
  if (frame.label === 'Verdict') return { headline: 'Verdict: no accusation', line: clip(frame.outcome ?? '', 200), tone: 'bad' };
  if (s.used === 0) return { headline: `The case: ${world.object} has vanished`, line: clip(`${world.suspects.length} suspects, ${world.budget} questions. Exactly one of them is lying about where they were.`, 200), tone: 'neutral' };
  if (s.invalid) return { headline: `${q}: wasted question`, line: quoted(model, frame.action, 'Not a valid command — the question is used up anyway.'), tone: 'bad' };
  const board = liarsBoard(world, frames, idx);
  const asked = s.ask ? `asks ${s.ask.who} about ${s.ask.topic === 'ALIBI' ? 'their alibi' : /^\d/.test(s.ask.topic) ? s.ask.topic : s.ask.topic.startsWith('THE ') ? s.ask.topic.toLowerCase() : s.ask.topic}` : `checks the ${s.check?.toLowerCase() ?? 'evidence'}`;
  if (board.newAt.length) {
    const c = board.newAt[0]!;
    return { headline: `${q}: contradiction! ${c.who}'s story breaks at ${world.slots[c.slot]}`, line: quoted(model, frame.action, clip(frame.outcome ?? '', 150)), tone: 'good' };
  }
  return { headline: `${q}: ${asked}`, line: quoted(model, frame.action, clip(frame.outcome ?? '', 150)), tone: 'neutral' };
}

export function liarsFinale(world: LiarsSimWorld, frames: ReplayFrame[]): Finale {
  const last = frames[frames.length - 1];
  const s = last?.sim as LiarsSimFrame | undefined;
  const used = s?.used ?? 0;
  const board = liarsBoard(world, frames, frames.length - 1);
  const stats: Finale['stats'] = [
    { label: 'Questions used', value: `${used} / ${world.budget}` },
    { label: 'Reason', value: s?.accuse ? `${s.accuse.reasonScore} / 3` : '—' },
  ];
  const notes: Finale['notes'] = [];
  notes.push(
    board.firstExposed !== null
      ? { text: `The lie was exposed by question ${(frames[board.firstExposed]?.sim as LiarsSimFrame | undefined)?.used ?? '?'}.`, tone: 'good' }
      : { text: 'The questions never exposed the contradiction.', tone: 'bad' },
  );
  notes.push({ text: `${world.culprit} was really in the ${world.objectRoom} at ${world.slots[world.theftSlot]}, not ${world.claimedRoom === 'Terrace' ? 'on' : 'in'} the ${world.claimedRoom}.`, tone: 'neutral' });
  if (!s?.accuse) return { tone: 'bad', title: 'No accusation', subtitle: `The thief was ${world.culprit}.`, stats, notes };
  return s.accuse.correct
    ? { tone: 'good', title: `Caught ${world.culprit}`, subtitle: `after ${plural(used, 'question')}`, big: { value: String(used), label: `questions of ${world.budget}` }, stats, notes }
    : { tone: 'bad', title: `Accused ${s.accuse.who} — wrong`, subtitle: `The thief was ${world.culprit}.`, stats, notes };
}

// ─── Dispatch ───────────────────────────────────────────────────────────────

/** The headline + line for frame `idx` of a sim replay (null when the frame has no sim data). */
export function simStory(replay: ReplayData, idx: number, model: string): StepStory | null {
  const world = replay.sim;
  const frames = replay.frames;
  const frame = frames[idx];
  if (!world || !frame) return null;
  if (!frame.sim) {
    // e.g. The Startup's closing frame.
    return { headline: frame.label ?? 'Final result', line: clip(frame.outcome ?? '', 220), tone: frame.tone ?? 'neutral' };
  }
  switch (world.kind) {
    case 'island':
      return islandStory(world, frame, model);
    case 'escape':
      return escapeStory(world, frame, model);
    case 'startup':
      return startupStory(world, frame, model);
    case 'liars':
      return liarsStory(world, frames, idx, model);
  }
}

export function simFinale(replay: ReplayData): Finale | null {
  const w = replay.sim;
  if (!w) return null;
  switch (w.kind) {
    case 'island':
      return islandFinale(w, replay.frames);
    case 'escape':
      return escapeFinale(w, replay.frames);
    case 'startup':
      return startupFinale(w, replay.frames);
    case 'liars':
      return liarsFinale(w, replay.frames);
  }
}

/** The single most telling frame for a Presenter slide. */
export function bestMoment(replay: ReplayData): number {
  const w = replay.sim;
  const frames = replay.frames;
  const lastIdx = Math.max(0, frames.length - 1);
  if (!w) return lastIdx;
  if (w.kind === 'island') {
    const all = simFrames<'island', IslandSimFrame>(frames, 'island');
    for (const tag of ['rescued', 'ship-ack', 'signal-lit', 'poison', 'died', 'found-water', 'shelter', 'signal-built', 'campfire'] as IslandEventTag[]) {
      const hit = all.find(({ s }) => s.events.includes(tag));
      if (hit) return hit.i;
    }
    return lastIdx;
  }
  if (w.kind === 'escape') {
    const all = simFrames<'escape', EscapeSimFrame>(frames, 'escape');
    const esc = all.find(({ s }) => s.event.type === 'escape');
    if (esc) return esc.i;
    const unlocks = all.filter(({ s }) => s.event.type === 'unlock');
    if (unlocks.length) return unlocks[unlocks.length - 1]!.i;
    const wrong = all.filter(({ s }) => s.event.type === 'wrong');
    return wrong.length ? wrong[wrong.length - 1]!.i : lastIdx;
  }
  if (w.kind === 'startup') {
    const all = simFrames<'startup', StartupSimFrame>(frames, 'startup');
    const bust = all.find(({ s }) => s.bankrupt);
    if (bust) return bust.i;
    return all.length ? all[all.length - 1]!.i : lastIdx;
  }
  const board = liarsBoard(w, frames, lastIdx);
  return board.firstExposed ?? lastIdx;
}
