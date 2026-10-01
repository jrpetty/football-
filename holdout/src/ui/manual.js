// The field manual: one modal, a chapter list on the left and the page on
// the right. Numbers come from the game data so the manual stays true when
// the balance moves.
import { BELTS, BELT_BONUS, SEASONS, SEASON_DAYS, INFECTION, PERK_LEVELS, SKILL_MAX, OUTPOST, SIGNAL, TIERS, CORE_SLOTS, CORE_BOOST, SEC_PER_DAY, STATIONS, UPKEEP, VEHICLES, VAN_REPAIR, RES } from '../game/data.js'
import { S, travelCost } from '../game/state.js'
import { HAND_RATE } from '../game/economy.js'
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
        tip('Thermal Goggles, Sixth Sense', 'Wall sense from gear (8 m) or a rare trait (4 m).'),
        tip('Eagle-Eyed', 'Sees 4 m further.'),
        tip('Hunter, Security Guard', 'Trained eyes: sees 2 to 3 m further.'),
        tip('Binoculars, Pathfinder perk', 'See further and spot traps sooner.'),
        tip("Cat's Eyes, flashlight, night vision", 'Less of the night penalty, or none at all with goggles.'),
        h('div.mn-tip', { style: { '--c': 'var(--bad)' } }, h('b', 'Nearsighted'), h('span', 'Sees 5 m less far. Dangerous after dark.')),
        h('div.mn-tip', { style: { '--c': 'var(--bad)' } }, h('b', 'Night-Blind'), h('span', 'Nearly blind once the sun goes down.')),
        h('div.mn-tip', { style: { '--c': 'var(--bad)' } }, h('b', 'Hard of Hearing'), h('span', 'Hears 7 m less far, so fewer ripples.')),
      ),
      h('h4', 'Hearing'),
      P('When a zombie goes out of sight it leaves a "last seen" mark where it was. Survivors close enough to hear one move see a ripple at its rough position, even through walls. Keen Hearing, the Police Officer, the Ex-Con and the Drifter hear further. Screamers bring every infected nearby. Stalkers creep in while nobody is looking.'),
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
        h('li', 'Belts run on posts above head height, so people walk underneath. They route around buildings, and where two cross, one runs a layer higher.'),
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
      P('From day 21, every seventh night is a Blood Moon. The sky goes red, the horde is half again as big, and the dead are tougher and faster. The Horde panel shows the date of the next one. Plan supply runs around it.'),
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
    id: 'materials',
    name: 'Metal and machines',
    icon: 'hammer',
    body: () => [
      P('Everything past the first few tents is built from metal, and metal has to be made. Scrap from runs and the Scrap Yard is the ore of this world.'),
      h(
        'ol.mn-arc',
        h('li', h('b', 'Metal bars. '), 'The Forge melts scrap into bars. Bars build the simple things: a bench, a farm plot, the first wall.'),
        h('li', h('b', 'Plates and bolts. '), 'Bars are cut into plates and threaded into bolts, by hand at the Workbench or by the Fabricator. Every upgraded station asks for them.'),
        h('li', h('b', 'Coal and steel. '), 'The Charcoal Kiln bakes wood into coal. A level 2 Forge folds coal into bars to make steel.'),
        h('li', h('b', 'Beams. '), 'Steel and bolts become structural beams at a level 2 Workbench or in the Machine Shop. Pylons, the mast and the top tiers are made of them.'),
      ),
      h('h4', 'By hand or by machine'),
      P(`Anything a machine makes, a survivor can make at a bench, only slower. Machines (the Fabricator, Assembler, Electronics Bench and the rest) need power. Without it, their crew still works them by hand at ${pct(HAND_RATE)} speed. With power and belts feeding them, they run day and night.`),
    ],
  },
  {
    id: 'power',
    name: 'Power',
    icon: 'power',
    body: () => [
      P('Power comes from engines you feed and from the weather. The Power panel (in the Camp overview) shows where every unit comes from and where it goes.'),
      h(
        'div.mn-grid',
        tip(STATIONS.boiler.name, `${STATIONS.boiler.power.join(' / ')} power by level. Burns wood, or coal for three times as long, only as hard as the camp needs.`),
        tip(STATIONS.generator.name, `${STATIONS.generator.power.join(' / ')} power from diesel fuel. Steady and strong, and thirsty.`),
        tip(STATIONS.solar.name, 'Free in daylight, nothing at night. Clouds cut it.'),
        tip(STATIONS.wind.name, 'Free whenever the wind blows. Storms and snow are best, still fog the worst.'),
        tip(STATIONS.battery.name, 'Stores power that solar and wind make beyond what the camp is using, and gives it back after dark.'),
      ),
      h('h4', 'Who gets power first'),
      P('When there is not enough, defence goes first (turrets and floodlights), then machines with people working them, then automation. The rest wait in the dark. Fuel engines burn only for the load they carry, so build renewables and a battery bank to save coal and diesel.'),
    ],
  },
  {
    id: 'upkeep',
    name: 'Upkeep',
    icon: 'wrench',
    body: () => [
      P('A camp wears out. Each day it uses a little of what you have, enough that a camp that stops scavenging slowly runs down, but never so much that it bleeds you dry.'),
      h(
        'ul',
        h('li', `Each survivor: ${UPKEEP.person.cloth} cloth and ${UPKEEP.person.meds} meds a day for clothes, bedding and small cuts.`),
        h('li', `Each station: ${UPKEEP.station.scrap} scrap a day per level for patching.`),
        h('li', `Upgraded stations: ${UPKEEP.upgraded.bolts} bolts a day for every level past the first.`),
        h('li', `Machines: ${UPKEEP.machine.parts} parts a day per level.`),
      ),
      P(`If the stores cannot cover it, the camp falls into disrepair: every station works at ${pct(UPKEEP.slow)} and morale drops until the shortfall is made good. The Camp overview shows the daily bill.`),
    ],
  },
  {
    id: 'travel',
    name: 'Travel and vehicles',
    icon: 'truck',
    body: () => {
      const rows = [['Next door', 0.25], ['Four streets over', 0.7], ['Across town', 1.2], ['Out on the highway', 2.5]]
      return [
        P('Every survivor on a run carries food and water for the trip there and back. Next door costs almost nothing; the far side of the city costs a great deal on foot. Vehicles cut the provisions but burn fuel.'),
        h(
          'table.vtable',
          h('tr', h('th', ''), h('th', 'On foot'), h('th', 'Bicycles'), h('th', 'Vehicle')),
          rows.map(([label, km]) => h('tr', h('td', h('b', label), h('small', ` ${km} km`)), ['foot', 'bikes', 'van'].map((k) => {
            const c = travelCost(km, k, 1)
            return h('td', `${c.food} / ${c.water}`, c.fuel ? h('small.dim', ` +${c.fuel} fuel`) : null)
          }))),
        ),
        h('p.note', 'Food and water per person, there and back.'),
        h('h4', 'Getting wheels'),
        h(
          'ul',
          h('li', `The van in the yard is dead. It needs ${Object.entries(VAN_REPAIR).map(([k, n]) => `${n} ${RES[k].name.toLowerCase()}`).join(', ')}. Strip wrecked cars on runs for tyres and batteries.`),
          h('li', 'Bicycles are made at the Workbench: a cheap first step off your feet.'),
          h('li', 'Some cars on runs still work. Find their keys in the building, or let a mechanic, ex-con or engineer hotwire one, and it drives home with you.'),
          h('li', `The vehicle decides what comes home: on foot and on bikes only what the squad carries; a car's boot holds about ${VEHICLES.car.stash}; the van and the truck take everything.`),
        ),
      ]
    },
  },
  {
    id: 'story',
    name: 'The story',
    icon: 'book',
    body: () => [
      P('Ashford fell for a reason, and some of the people who know why are still out there. The Journal (J) keeps every thread you have found: what you know, and where it might lead.'),
      h(
        'ul',
        h('li', 'Notes turn up in desks, filing cabinets, lockers and bookshelves on runs. Some are just people\'s last words; some point somewhere.'),
        h('li', 'Leads show on the city map in violet. A thread rarely says exactly where to go: it narrows things down, and you check the places it might be.'),
        h('li', 'People in camp sometimes ask you to find someone they lost. Those trails go cold after a week.'),
        h('li', 'Two Signal phases need more than parts: someone who knows the dish array, and the codes Coastal Command will answer to.'),
      ),
    ],
  },
  {
    id: 'gear',
    name: 'Gear and swapping',
    icon: 'items',
    body: () => [
      P('Weapons, armour and gear move between survivors in camp. Open someone\'s sheet, click a slot and pick from storage or from what others carry: taking someone else\'s item swaps it, so they get yours.'),
      P('Gear only changes hands at home. Someone out on a run or holding an outpost keeps what they left with.'),
    ],
  },
  {
    id: 'multiplayer',
    name: 'Playing together',
    icon: 'people',
    body: () => [
      P('Multiplayer (on the title screen) runs a camp of its own, separate from your single-player save. One player hosts: their browser runs the camp and keeps the save. Friends join with the five-letter camp code, or pick the camp from the list.'),
      h(
        'ul',
        h('li', 'Everyone builds, crafts, researches and trades for the same camp, and everyone sees it change live.'),
        h('li', 'The host (or the admin of an always-on camp) hands survivors out in the Players panel (O); Share out splits the unled ones evenly. You give orders only to the survivors you lead, plus anyone nobody leads. Whoever rescues or recruits someone leads them.'),
        h('li', 'The camp keeps one pace for everyone, set by the host, and it does not slow down while someone is on a run.'),
        h('li', 'Press Enter to chat. Coloured rings show where your friends are looking. Press Q or Alt+click to ping a spot: everyone sees a pulse in your colour.'),
        h('li', 'If someone drops out mid-run, their squad walks home with nothing. If the host leaves, the camp waits, saved, until they host again.'),
      ),
      h('h4', 'Runs together'),
      h(
        'ul',
        h('li', 'Plan a run on the city map as usual, then press Invite friends. Everyone in camp gets a card: they pick up to four of their own survivors and join. Up to six survivors go on one run.'),
        h('li', 'Whoever planned the run leads it and pays the trip; their game runs the street. Everyone else sees the same street live and gives orders to their own survivors (right-click to move, attack or search, as usual). Sight is shared.'),
        h('li', 'There is no pausing on a run with friends. The leader calls the extraction; what anyone carries home lands in the camp stores.'),
        h('li', 'You can still run alone: just set out without inviting anyone, while friends are elsewhere.'),
      ),
      h('h4', 'Always-on camps'),
      h(
        'ul',
        h('li', 'On the Holdout website, Start it under Always-on camp makes a camp that lives on the server. Nobody has to host: friends come and go whenever they like, and you are its admin.'),
        h('li', 'While nobody is on, the crew keeps working at a slow pace and the clock waits, as in single player. Nothing piles up against you while you sleep.'),
        h('li', 'When a horde comes, the server hands the defence to a player in camp, who fights it live while everyone watches. With nobody in camp the fight is worked out by the numbers.'),
      ),
      h('h4', 'Where it connects'),
      P('Inside Claude, camps connect through the artifact\'s live room: friends open the same artifact and see your camp in the list. On the Holdout website, camps connect through its server, which also keeps the always-on camps. The standalone file connects browser to browser over the internet.'),
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
        ['J', 'Journal'],
        ['O', 'Players (multiplayer)'],
        ['Enter', 'Chat (multiplayer)'],
        ['Q / Alt+click', 'Ping a spot for friends (multiplayer)'],
        ['F', 'Wall'],
        ['H', 'Horde intel'],
        ['R', 'Rotate while placing'],
        ['F1', 'This manual'],
        ['Alt+Enter', 'Full screen (or the button above Settings)'],
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
        ['Q / Alt+click', 'Ping a spot for the friends on the run'],
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
