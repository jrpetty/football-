// The field manual: one modal, a chapter list on the left and the page on
// the right. Numbers come from the game data so the manual stays true when
// the balance moves.
import { BELTS, BELT_BONUS, SEASONS, SEASON_DAYS, INFECTION, PERK_LEVELS, SKILL_MAX, OUTPOST, SIGNAL, TIERS, CORE_SLOTS, CORE_BOOST, SEC_PER_DAY } from '../game/data.js'
import { S } from '../game/state.js'
import { sfx } from '../core/audio.js'
import { h } from '../core/util.js'
import { icon } from './icons.js'

let page = 'start'
const pct = (x) => `${Math.round(x * 100)}%`
const realDay = Math.round(SEC_PER_DAY / 60)
const P = (...t) => h('p', ...t)
const tip = (title, ...t) => h('div.mn-tip', h('b', title), h('span', ...t))
const keys = (rows) => h('div.mn-keys', rows.map(([k, v]) => h('div', h('span', k.split(' ').map((x) => h('kbd', x))), h('small', v))))

const CHAPTERS = [
  {
    id: 'start',
    name: 'The long haul',
    icon: 'clock',
    body: () => [
      P(`Holdout is built to be played for weeks and months. A day in camp lasts about ${realDay} minutes of real time at normal speed, and the road from a campfire to a working radio mast runs for hundreds of days.`),
      h('h4', 'The arc'),
      h(
        'ol.mn-arc',
        h('li', h('b', 'Days 1 to 20. '), 'Food, water, beds and a wall. Learn the city, finish the starter tasks, reach tier 2.'),
        h('li', h('b', 'Days 20 to 60. '), 'Build the Signal Mast and finish the first phases. Belts take the hauling off your crew.'),
        h('li', h('b', 'Days 60 to 200. '), 'Electronics, research and automation. The hordes grow with you, and winter tests the stores.'),
        h('li', h('b', 'Day 200 and on. '), 'Outposts in the city, overclocked factories and the last two Signal phases. Then the final night.'),
      ),
      h('h4', 'While you are away'),
      P('Close the game and the camp keeps working. When you come back, your crew will have done about one fifteenth of the time you were gone (up to three days of camp work). The clock waits for you: no horde comes and nobody starves to death.'),
      h('h4', 'Your save'),
      P('The game saves every few seconds, and it keeps a backup from the start of each of the last few days. Settings has export, import and restore, so a long camp is never one bad night from gone.'),
    ],
  },
  {
    id: 'sight',
    name: 'Runs and sight',
    icon: 'eye',
    body: () => [
      P('On a supply run you only see what your survivors can see. Each one looks out in a cone the way they face, with a little awareness behind them. Walls block sight. When someone steps into a room, the room is revealed. Rooms you have already explored stay on the map, dimmed, but zombies in them are hidden until someone looks again.'),
      h('h4', 'How far they see'),
      P('A survivor sees about 11 metres in daylight. Night cuts that by up to half, unless they wear night-vision goggles. A torch or flashlight lights a beam in front of them, and fog and rain shorten everyone\'s sight.'),
      h(
        'div.mn-grid',
        tip('Scout', 'Wall sense: sees movement through one wall within 6 m. Sensed zombies glow blue through the wall.'),
        tip('Eagle-Eyed', 'Sees 4 m further.'),
        tip('Hunter, Security Guard', 'Trained eyes: sees 2 to 3 m further.'),
        tip('Binoculars', 'Sees 5 m further and spots traps sooner.'),
        tip('Pathfinder perk', 'Sees 3 m further and spots traps sooner.'),
        tip('Nearsighted', 'Sees 5 m less far, which is dangerous at night.'),
      ),
      h('h4', 'Hearing'),
      P('When a zombie goes out of sight it leaves a "last seen" mark where it was. Survivors close enough to hear one move see a ripple at its rough position. Screamers bring every infected nearby. Stalkers creep in while nobody is looking.'),
      P('Mix your squads: put a scout up front, and do not send a nearsighted survivor in alone after dark.'),
    ],
  },
  {
    id: 'belts',
    name: 'Belts and flow',
    icon: 'belt',
    body: () => [
      P('Every station has an input buffer and an output buffer. Workers carry goods by hand at first. Belts move them automatically: click a station\'s output, then click the station that should receive it.'),
      h(
        'div.mn-grid',
        BELTS.filter(Boolean).map((b) => tip(b.name, b.desc)),
      ),
      h('h4', 'Rules of the line'),
      h(
        'ul',
        h('li', `A station gets ${pct(BELT_BONUS)} faster for each side that is fully belted: inputs fed and outputs carried away.`),
        h('li', 'A station whose output is belted to a consumer works on demand: it makes what the next station needs, not whatever it happens to have.'),
        h('li', 'Belts route around buildings and climb over each other. When the ground is crowded they go up on posts, one layer above another.'),
        h('li', 'A full output belt stops the station. Watch for the red lamps and "Output belt backed up".'),
        h('li', 'Upgrade a belt in place from its own panel. Moving a station reroutes its belts.'),
      ),
      P('Camp shows the whole factory: the rate of every resource, the history and where the line is stuck.'),
    ],
  },
  {
    id: 'milestones',
    name: 'Milestones',
    icon: 'goals',
    body: () => [
      P(`Progress opens a board of ${TIERS.length - 1} tiers. Each milestone is a delivery: pay the cost and the camp learns something new, such as a station, a belt, a wall, an expansion, a recipe or a camp-wide bonus.`),
      P('Tiers open in order. From tier 3 on, a tier also needs the Signal to reach a given phase, so the mast and the factory grow together.'),
      h(
        'div.mn-tiers',
        TIERS.slice(1).map((T, i) => h('div', h('span.t-n', i + 1), h('b', T.name), h('small', T.phase ? `needs Signal phase ${T.phase}` : 'open'))),
      ),
      P('The starter tasks (also under Progress) teach the basics, and each one pays a small reward.'),
    ],
  },
  {
    id: 'signal',
    name: 'The Signal',
    icon: 'radio',
    body: () => [
      P(`The way out is a broadcast mast on the hill. Rebuild it in ${SIGNAL.length} phases, then call the coast for an evacuation. Belt parts straight into the mast, or deliver from storage in the Progress panel.`),
      h(
        'ol.mn-arc',
        SIGNAL.map((p) => h('li', h('b', p.name + '. '), p.desc)),
      ),
      h('h4', 'The last night'),
      P('When the final phase is done, every infected for miles hears the call. One last horde, the biggest you will face, comes for the camp. Hold the wall until dawn and the convoy comes.'),
    ],
  },
  {
    id: 'research',
    name: 'Research and cores',
    icon: 'schematic',
    body: () => [
      P('The Research Desk turns what the runs bring home into knowledge. Schematics come from offices, schools and desks. Specimens come from the special infected: stalkers, screamers and bloaters.'),
      h(
        'ul',
        h('li', h('b', 'Study a schematic: '), 'pick one of three alternate recipes. They use different inputs, make more output or cost less. Choose the ones that fit your factory.'),
        h('li', h('b', 'Field projects: '), 'better first aid, firearm damage and faster searching.'),
        h('li', h('b', 'Infection projects: '), 'specimens become an antiviral, then immunity, then a vaccine.'),
        h('li', h('b', 'Engineering projects: '), 'cheaper power, faster belts and stronger cores.'),
      ),
      h('h4', 'Power cores'),
      P(`Cores turn up in safes, military crates and old electronics, and a military outpost sends one home now and then. Once the Overclocking milestone is done (tier 7), an automated station takes up to ${CORE_SLOTS} cores, and each one adds ${pct(CORE_BOOST)} speed. An overclocked station draws a lot more power, because its draw rises faster than its speed.`),
    ],
  },
  {
    id: 'hordes',
    name: 'Hordes and threat',
    icon: 'horde',
    body: () => [
      P('Every few nights a horde comes for the wall. Its size follows the camp\'s threat level, and threat grows with what you have achieved: milestone tiers, Signal phases, how many people live here and how far the walls reach. Time survived adds a little.'),
      h('h4', 'Blood Moons'),
      P('From day 14, every seventh night is a Blood Moon. The sky goes red, the horde is half again as big, and the dead are tougher and faster. The Horde panel shows the date of the next one. Plan supply runs around it.'),
      h('h4', 'Holding the line'),
      h(
        'ul',
        h('li', 'Upgrade the wall as milestones open better fences, and repair it between nights.'),
        h('li', 'A Watchtower shows a horde\'s size before it arrives. Turrets and floodlights help hold a wall at night.'),
        h('li', 'Everyone in camp fights. A squad out on a run misses the night.'),
      ),
    ],
  },
  {
    id: 'infection',
    name: 'Infection',
    icon: 'specimen',
    body: () => [
      P(`Each bite has a ${pct(INFECTION.bite)} chance to infect, and a bloater's gas has ${pct(INFECTION.gas)} per hit. The infection grows about ${INFECTION.perDay} points a day.`),
      h(
        'div.mn-stages',
        h('div', h('b', `0 to ${INFECTION.fever}`), h('small', 'Incubating. No symptoms yet.')),
        h('div', h('b', `${INFECTION.fever}+`), h('small', 'Feverish. Works at 80%, 15 less health.')),
        h('div', h('b', `${INFECTION.sick}+`), h('small', 'Turning. Works at half speed, cannot go on runs.')),
        h('div.bad', h('b', '100'), h('small', 'Turns. Lost to the dead.')),
      ),
      P(`An antiviral cures the infection only if it is given before ${INFECTION.cureBelow}. A patient in a staffed Infirmary gets worse much more slowly, and its medics give antivirals by themselves when the camp has them. Research Immune Boosters and the Vaccine to end the threat for good.`),
    ],
  },
  {
    id: 'seasons',
    name: 'Seasons',
    icon: 'snow',
    body: () => [
      P(`A year has four seasons of ${SEASON_DAYS} days each. The farms, the rain and the weather follow them.`),
      h(
        'div.mn-grid',
        SEASONS.map((s) => h('div.mn-tip', { style: { '--c': s.color } }, h('b', s.name), h('span', s.desc))),
      ),
      P(`In winter the camp needs heat: about ${SEASONS[3].heat} wood per survivor each day, or fuel once the wood runs out. A freezing camp loses morale, and the healthy slowly lose health. Stock food and wood through the autumn.`),
    ],
  },
  {
    id: 'crew',
    name: 'Survivors and perks',
    icon: 'people',
    body: () => [
      P(`Survivors learn by doing. Skills rise to level ${SKILL_MAX}. At levels ${PERK_LEVELS.join(', ')} of a skill they choose one of two perks, so two cooks or two shooters can end up quite different.`),
      P('Occupations and traits matter: a mechanic builds faster, a nurse heals, a scout senses through walls. Read each newcomer at the gate before you give them a bed.'),
      h('h4', 'Looking after them'),
      h('ul', h('li', 'Hunger, thirst, rest and morale all affect how well they work.'), h('li', 'Injured survivors heal in their beds, and faster in the Infirmary.'), h('li', 'Training levels skills without the risk of a run.')),
    ],
  },
  {
    id: 'outposts',
    name: 'Outposts',
    icon: 'truck',
    body: () => [
      P(`The Convoys milestone (tier 8) lets you hold ground in the city. Claim a place you have already run, leave one to three survivors to hold it, and it sends a convoy home every day with what that place is good for. You can hold up to ${OUTPOST.max}.`),
      h('ul', h('li', 'Upgrading an outpost raises its yield and its defence.'), h('li', 'Outposts can be attacked. A strong crew and a high level keep them standing.'), h('li', 'Crew at an outpost do not work in camp and do not defend it.')),
    ],
  },
  {
    id: 'keys',
    name: 'Controls',
    icon: 'settings',
    body: () => [
      h('h4', 'In camp'),
      keys([
        ['W A S D', 'Move the camera (or drag)'],
        ['Q E', 'Rotate (or right-drag)'],
        ['Space', 'Pause'],
        ['1 2 3', 'Game speed'],
        ['B', 'Build'],
        ['C', 'Crew'],
        ['I', 'Items'],
        ['T', 'Trade'],
        ['P', 'Camp overview'],
        ['M', 'City map'],
        ['G', 'Progress'],
        ['L', 'Log'],
        ['F', 'Wall'],
        ['H', 'Horde intel'],
        ['R', 'Rotate while placing'],
        ['F1', 'This manual'],
        ['Esc', 'Close or menu'],
      ]),
      h('h4', 'On a run'),
      keys([
        ['1 2 3', 'Select a survivor (twice to find them)'],
        ['Tab', 'Select the whole squad'],
        ['Space', 'Pause and plan'],
        ['Z', 'Throw a molotov'],
        ['X', 'Throw a pipe bomb'],
        ['C', 'Throw a noise maker'],
        ['V', 'First aid kit'],
        ['F', 'Find the selected survivor'],
        ['Enter', 'Leave once everyone is by the van'],
        ['Esc', 'Deselect or cancel a throw'],
      ]),
      P('Left-click or drag a box to select survivors (Shift adds to the selection). Right-click the ground to move, a zombie to attack and a container to search it. Left-click a container or a trap for its options. Click a downed survivor and the nearest one goes to help them up.'),
      h('h4', 'City map'),
      keys([
        ['M', 'Back to camp'],
        ['Esc', 'Deselect, then back to camp'],
      ]),
    ],
  },
]

export function manualModal(ui, start) {
  if (start) page = start
  const side = h('nav.mn-side')
  const main = h('div.mn-page')
  const draw = () => {
    side.innerHTML = ''
    side.append(...CHAPTERS.map((c) => h('button' + (c.id === page ? '.on' : ''), { onclick: () => ((page = c.id), sfx('click'), draw()) }, h('i', { html: icon(c.icon) }), c.name)))
    const C = CHAPTERS.find((c) => c.id === page) || CHAPTERS[0]
    main.innerHTML = ''
    main.append(h('h2', h('i', { html: icon(C.icon) }), C.name), ...C.body().flat())
    main.scrollTop = 0
  }
  draw()
  return h('div.manual', h('div.mn-head', h('h2', 'Field manual'), h('small', S ? 'Everything the old-timers wish someone had told them' : '')), h('div.mn-body', side, main))
}
