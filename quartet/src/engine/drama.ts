// The dramatic layer of every scenario: the three acts, the hard choices the
// group must vote on (the last is the finale), and what the cast's items can
// actually do. Merged into SCENARIOS by scenarios.ts.

import type { Dilemma, ItemUse } from './types.ts'

interface Drama { acts: [string, string, string]; dilemmas: Dilemma[]; items?: ItemUse[] }

export const DRAMA: Record<string, Drama> = {
  stranded: {
    acts: ['Washed up', 'The long days', 'The last fire'],
    dilemmas: [
      { id: 'ration', at: 0.3, title: 'The last crate of supplies', text: 'Half the salvaged food is spoiling in the heat. Eat well now and get your strength back — or ration hard and stretch it?',
        options: [
          { id: 'feast', label: 'Eat well now', effects: { food: -12 }, all: { energy: 18, mood: 8 }, lean: { agreeableness: 0.4, conscientiousness: -0.5 }, outcome: 'They eat properly for the first time since the crash. Spirits lift; the stores shrink.' },
          { id: 'ration', label: 'Ration hard', effects: { food: 8 }, all: { mood: -6, energy: -4 }, lean: { conscientiousness: 0.8, neuroticism: 0.3 }, outcome: 'Thin portions, carefully counted. Nobody is happy, but it will last.' },
        ] },
      { id: 'raft', at: 0.58, title: 'The raft', text: 'Someone has lashed wreckage into a raft. One of them could try to paddle to the next island, two days away — or they keep everything here and wait.',
        options: [
          { id: 'go', label: 'Send someone on the raft', effects: { rescue: 18, food: -8, water: -8 }, all: { stress: 10 }, lean: { openness: 0.7, neuroticism: -0.5 }, outcome: 'The raft pushes off through the surf with half the water. The rest watch until it is a dot.' },
          { id: 'stay', label: 'Stay together and wait', effects: { shelter: 8 }, all: { stress: -4 }, lean: { agreeableness: 0.5, neuroticism: 0.4 }, outcome: 'They drag the raft up the beach and use its planks for the shelter. Together, whatever happens.' },
        ] },
      { id: 'burn', finale: true, at: 0.8, title: 'Engines in the night', text: 'A plane is coming — they can hear it. The signal fire is too small. They could burn the shelter to make a blaze the pilot can\'t miss.',
        options: [
          { id: 'burn', label: 'Burn the shelter', effects: { rescue: 30, shelter: -40 }, all: { stress: 6 }, lean: { openness: 0.6, extraversion: 0.4 }, outcome: 'Palm thatch and plane seats go up in a roaring column of flame that lights the whole lagoon.' },
          { id: 'flare', label: 'Keep the shelter, hope the fire is enough', effects: { rescue: 8 }, all: { stress: 10 }, lean: { conscientiousness: 0.6, neuroticism: 0.3 }, outcome: 'They pile on everything else that will burn and wave until their arms ache.' },
        ] },
    ],
    items: [
      { item: 'flare gun', label: 'Fire the flare', effects: { rescue: 22 }, once: true, verb: 'fired the last flare into the sky' },
      { item: 'first-aid kit', label: 'Patch everyone up', effects: {}, self: { mood: 4 }, heal: 22, verb: 'used the first-aid kit on the worst injuries' },
      { item: 'dead phone', label: 'Make a fishing spear with the knife', effects: { food: 10 }, self: { energy: -6 }, verb: 'whittled a fishing spear with the pocket knife' },
      { item: 'satellite messenger', label: 'Coax a signal out of the messenger', effects: { rescue: 14 }, self: { stress: 6 }, once: true, verb: 'got one message out on the cracked satellite messenger' },
    ],
  },
  startup: {
    acts: ['Twelve weeks of cash', 'The grind', 'Demo Day'],
    dilemmas: [
      { id: 'pivot', at: 0.3, title: 'Pivot?', text: 'User interviews say restaurants want a booking tool, not the AI menu writer. Pivot now, or double down on the vision?',
        options: [
          { id: 'pivot', label: 'Pivot to bookings', effects: { product: -10, users: 12, morale: -4 }, lean: { openness: 0.6, agreeableness: 0.2 }, outcome: 'Weeks of code go in the bin. The first restaurant to try the new booking tool asks to pay.' },
          { id: 'stay', label: 'Double down', effects: { product: 8, interest: 4 }, lean: { conscientiousness: 0.5, openness: -0.4 }, outcome: 'They recommit to the original vision and ship it harder.' },
        ] },
      { id: 'acquihire', at: 0.6, title: 'The acqui-hire', text: 'A big company offers to hire all four of them — good salaries, the product shut down. Take the safe landing?',
        options: [
          { id: 'take', label: 'Take the offer', effects: { cash: 40, interest: -20, morale: -10 }, lean: { neuroticism: 0.6, openness: -0.4 }, outcome: 'They sign letters of intent "to keep options open". The dream suddenly feels like a job.' },
          { id: 'refuse', label: 'Refuse and keep building', effects: { morale: 10, interest: 6 }, all: { stress: 6 }, lean: { openness: 0.5, extraversion: 0.4 }, outcome: 'They say no. Someone puts on loud music and they work until 3am.' },
        ] },
      { id: 'demo', finale: true, at: 0.83, title: 'The demo', text: 'Demo Day. The live demo is flaky. Show it live and risk it crashing on stage — or play a polished recording?',
        options: [
          { id: 'live', label: 'Go live', effects: { interest: 22 }, all: { stress: 12 }, lean: { openness: 0.5, extraversion: 0.5, neuroticism: -0.5 }, outcome: 'The product works on stage, in front of three hundred investors. Someone gasps.' },
          { id: 'video', label: 'Play the recording', effects: { interest: 8 }, lean: { conscientiousness: 0.6, neuroticism: 0.4 }, outcome: 'The video is slick. It is also exactly what everyone else showed.' },
        ] },
    ],
    items: [
      { item: 'investor contact list', label: 'Work the contact list', effects: { interest: 12 }, self: { energy: -8 }, verb: 'called in favours from the investor list' },
      { item: 'laptop', label: 'Hack all weekend', effects: { product: 12 }, self: { energy: -16, stress: 6 }, verb: 'hacked through the weekend on the codebase' },
      { item: 'notebook of user interviews', label: 'Mine the interviews', effects: { product: 6, users: 5 }, verb: 'turned the interview notes into a redesign' },
      { item: 'a phone full', label: 'Text every restaurant owner', effects: { users: 11 }, self: { energy: -6 }, verb: 'texted every restaurant owner in the phone' },
    ],
  },
  lifeboat: {
    acts: ['In the dark', 'Adrift', 'Landfall'],
    dilemmas: [
      { id: 'stranger', at: 0.3, title: 'A voice in the water', text: 'Someone is calling for help from the waves. Pulling them aboard means more weight and less water — leaving them is unthinkable.',
        options: [
          { id: 'save', label: 'Pull them in', effects: { water: -6, boat: -5 }, all: { mood: 8 }, lean: { agreeableness: 0.8 }, outcome: 'A half-frozen teenager is hauled over the side and wrapped in every coat they have.' },
          { id: 'row', label: 'Keep rowing', effects: { shore: 6 }, all: { mood: -14, stress: 12 }, lean: { agreeableness: -0.6, conscientiousness: 0.3 }, outcome: 'They row on. Nobody speaks for a long time.' },
        ] },
      { id: 'heading', at: 0.6, title: 'Which way is land?', text: 'The compass is cracked. A light to the north might be a rig; the coast should be east. Trust the light or the compass?',
        options: [
          { id: 'light', label: 'Row for the light', effects: { shore: 14, warmth: -6 }, lean: { openness: 0.6, extraversion: 0.3 }, outcome: 'They row for the light for hours. It is a rig — and its crew have seen them.' },
          { id: 'east', label: 'Hold east', effects: { shore: 8 }, lean: { conscientiousness: 0.6 }, outcome: 'Stroke after stroke, east, by the stars.' },
        ] },
      { id: 'surf', finale: true, at: 0.82, title: 'The breakers', text: 'The coast is close but the surf is huge. Run the boat through the breakers now, or wait for daylight and a calmer sea?',
        options: [
          { id: 'run', label: 'Run the surf now', effects: { shore: 30, boat: -25 }, all: { stress: 14 }, lean: { neuroticism: -0.6, extraversion: 0.4 }, outcome: 'The boat surfs a wave, slews sideways, and grinds onto shingle.' },
          { id: 'wait', label: 'Wait for daylight', effects: { warmth: -12, shore: 10 }, all: { stress: 6 }, lean: { conscientiousness: 0.5, neuroticism: 0.4 }, outcome: 'They hold off the breakers until a grey dawn shows a gap in the rocks.' },
        ] },
    ],
    items: [
      { item: 'crew radio', label: 'Call on the crew radio', effects: { shore: 10 }, self: { stress: -4 }, once: true, verb: 'raised the coastguard on the last of the radio battery' },
      { item: 'lighter', label: 'Share the chocolate, light the lamp', effects: { warmth: 10 }, self: { mood: 5 }, once: true, verb: 'lit the storm lamp and broke the chocolate into four' },
      { item: 'phone in a zip bag', label: 'Check the phone\'s GPS', effects: { shore: 8 }, once: true, verb: 'got a GPS fix on the phone before it died' },
    ],
  },
  flatshare: {
    acts: ['Rent is due', 'Cracks', 'The first of the month'],
    dilemmas: [
      { id: 'party', at: 0.3, title: 'The house party', text: 'The musician wants to throw a ticketed party to raise rent money. The neighbours have already complained twice.',
        options: [
          { id: 'party', label: 'Throw the party', effects: { rent: 6, clean: -18, harmony: -6 }, all: { mood: 8 }, lean: { extraversion: 0.8, conscientiousness: -0.4 }, outcome: 'Sixty strangers, a broken lamp, and £300 in a biscuit tin.' },
          { id: 'no', label: 'No party', effects: { harmony: 4 }, lean: { conscientiousness: 0.5, extraversion: -0.5 }, outcome: 'A quiet weekend. The rent gap is still there.' },
        ] },
      { id: 'cover', at: 0.6, title: 'Who covers the gap?', text: 'One flatmate cannot pay their share. Split it between the others, or tell them they have a month to find somewhere else?',
        options: [
          { id: 'split', label: 'Split it between us', effects: { rent: 4, harmony: 8 }, all: { stress: 6 }, lean: { agreeableness: 0.8 }, outcome: 'Three bank transfers, one long hug in the kitchen.' },
          { id: 'notice', label: 'Give them notice', effects: { harmony: -18, rent: 4 }, lean: { agreeableness: -0.6, conscientiousness: 0.4 }, outcome: 'The words are said. The flat goes very quiet.' },
        ] },
      { id: 'landlord', finale: true, at: 0.82, title: 'The landlord\'s offer', text: 'The landlord will knock £200 off the rent — if they let him "redecorate" while they live there for two months.',
        options: [
          { id: 'accept', label: 'Accept', effects: { rent: 8, clean: -12 }, all: { stress: 8 }, lean: { conscientiousness: 0.4, openness: 0.3 }, outcome: 'Dust sheets and drills every morning — but the rent is covered.' },
          { id: 'refuse', label: 'Refuse', effects: { harmony: 6 }, lean: { agreeableness: -0.3, neuroticism: 0.4 }, outcome: 'They say no, together, and feel oddly proud of it.' },
        ] },
    ],
    items: [
      { item: 'guitar', label: 'Busk at the station', effects: { rent: 7 }, self: { energy: -8, mood: 4 }, verb: 'busked at the tube station with the guitar' },
      { item: 'the spreadsheet', label: 'Rebalance the budget', effects: { rent: 6, harmony: 3 }, verb: 'found savings in the household spreadsheet' },
    ],
  },
  jury: {
    acts: ['Behind closed doors', 'Reasonable doubt', 'The final vote'],
    dilemmas: [
      { id: 'note', at: 0.32, title: 'A note to the judge', text: 'They could send a note asking to see the CCTV again — but it will cost hours and the judge is already impatient.',
        options: [
          { id: 'ask', label: 'Ask to see it again', effects: { clarity: 14, patience: -10 }, lean: { conscientiousness: 0.6, openness: 0.4 }, outcome: 'The usher wheels in a television. They watch the same twelve seconds forty times.' },
          { id: 'skip', label: 'Work from memory', effects: { patience: 6, clarity: -4 }, lean: { conscientiousness: -0.4, extraversion: 0.3 }, outcome: 'They argue about what they each remember seeing.' },
        ] },
      { id: 'outside', at: 0.6, title: 'Outside knowledge', text: 'One juror admits they looked the case up online. Report it — which could collapse the trial — or keep it in the room?',
        options: [
          { id: 'report', label: 'Report it', effects: { civility: -10, clarity: 6 }, all: { stress: 8 }, lean: { conscientiousness: 0.8 }, outcome: 'The judge warns them sternly but lets the deliberation continue.' },
          { id: 'keep', label: 'Keep it in the room', effects: { civility: 6 }, all: { stress: 4 }, lean: { agreeableness: 0.5, conscientiousness: -0.4 }, outcome: 'They agree to forget what was said. It is not that easy.' },
        ] },
      { id: 'deadlock', finale: true, at: 0.82, title: 'Deadlock', text: 'The judge sends word: she will accept a majority verdict if they cannot agree. Keep going for unanimity, or ask for more time?',
        options: [
          { id: 'push', label: 'Keep deliberating', effects: { clarity: 10, patience: -14 }, lean: { conscientiousness: 0.5, agreeableness: 0.3 }, outcome: 'Coffee, again. One more time through the evidence.' },
          { id: 'time', label: 'Ask for the night to think', effects: { patience: 16 }, all: { stress: -8 }, lean: { neuroticism: 0.4, openness: 0.3 }, outcome: 'They are sent to a hotel. Nobody sleeps much.' },
        ] },
    ],
  },
  er: {
    acts: ['The coach crash', 'Overrun', 'Dawn'],
    dilemmas: [
      { id: 'divert', at: 0.3, title: 'Divert the ambulances?', text: 'They could put the department on divert — ambulances go to the next hospital, forty minutes away.',
        options: [
          { id: 'divert', label: 'Go on divert', effects: { waiting: -14, safety: 6 }, all: { stress: -4 }, lean: { conscientiousness: 0.4, neuroticism: 0.4 }, outcome: 'The red phone goes quiet. Somewhere, a crew turns around on the motorway.' },
          { id: 'open', label: 'Stay open', effects: { stable: 6, waiting: 12 }, all: { stress: 8 }, lean: { agreeableness: 0.5, extraversion: 0.3 }, outcome: 'They keep the doors open. The waiting room fills to the corridor.' },
        ] },
      { id: 'bed', at: 0.6, title: 'One bed in intensive care', text: 'Two critical patients, one ICU bed: the elderly coach driver or the young passenger with worse odds.',
        options: [
          { id: 'driver', label: 'The driver — better odds', effects: { stable: 10 }, all: { stress: 10 }, lean: { conscientiousness: 0.6, agreeableness: -0.2 }, outcome: 'The driver goes up. The team keeps the passenger alive in resus by hand.' },
          { id: 'young', label: 'The passenger — more years', effects: { stable: 4, safety: -6 }, all: { stress: 12 }, lean: { agreeableness: 0.5, openness: 0.3 }, outcome: 'The passenger goes up. Nobody talks about the driver for an hour.' },
        ] },
      { id: 'handover', finale: true, at: 0.82, title: 'The day shift', text: 'The day shift is late. Hand over to whoever turns up and go home — or stay until the last crash patient is safe?',
        options: [
          { id: 'stay', label: 'Stay', effects: { stable: 16 }, all: { energy: -14, stress: 6 }, lean: { conscientiousness: 0.6, agreeableness: 0.5 }, outcome: 'They stay past nine, past ten, until the board is clear.' },
          { id: 'go', label: 'Hand over', effects: { safety: 10, waiting: 6 }, all: { stress: -10 }, lean: { neuroticism: 0.4, conscientiousness: -0.3 }, outcome: 'They hand over properly and walk out into the morning light.' },
        ] },
    ],
  },
  newsroom: {
    acts: ['The leak', 'Pressure', 'Press time'],
    dilemmas: [
      { id: 'call', at: 0.3, title: 'Call the minister?', text: 'Ethics say the minister gets a right of reply. Calling now tips them off — and their lawyers.',
        options: [
          { id: 'call', label: 'Call now', effects: { risk: -12, lead: -10 }, lean: { conscientiousness: 0.7 }, outcome: 'The minister\'s office promises a statement — and a call from their lawyers.' },
          { id: 'wait', label: 'Call at the last minute', effects: { lead: 6, risk: 5 }, lean: { extraversion: 0.4, conscientiousness: -0.3 }, outcome: 'They sit on it. The phone stays face down on the desk.' },
        ] },
      { id: 'owner', at: 0.6, title: 'The owner calls', text: 'The paper\'s owner rings the editor: "Are we sure about this?" It is not quite an order.',
        options: [
          { id: 'defy', label: 'Ignore the hint', effects: { verified: 4 }, all: { stress: 10 }, lean: { agreeableness: -0.4, openness: 0.4 }, outcome: 'The editor says "we are checking everything" and hangs up first.' },
          { id: 'soften', label: 'Soften the story', effects: { risk: -10, lead: -8 }, all: { mood: -8 }, lean: { agreeableness: 0.5, neuroticism: 0.4 }, outcome: 'Adjectives come out. So does the best quote.' },
        ] },
      { id: 'press', finale: true, at: 0.82, title: 'Nine minutes to press', text: 'The second source still hasn\'t confirmed. Publish on one source, or hold for tomorrow and risk a rival scooping it?',
        options: [
          { id: 'publish', label: 'Publish', effects: { lead: 16, risk: 9 }, all: { stress: 10 }, lean: { openness: 0.5, extraversion: 0.4 }, outcome: 'The story goes live at 22:59. Phones start ringing at 23:01.' },
          { id: 'hold', label: 'Hold', effects: { risk: -16, lead: -10 }, lean: { conscientiousness: 0.6, neuroticism: 0.3 }, outcome: 'They hold. The front page runs the weather.' },
        ] },
    ],
  },
  antarctic: {
    acts: ['Last plane out', 'Polar night', 'First light'],
    dilemmas: [
      { id: 'experiment', at: 0.3, title: 'Power for science', text: 'The ice-core freezer and the main experiment need more generator time. Run them — or save fuel for heating?',
        options: [
          { id: 'science', label: 'Run the experiment', effects: { science: 14, fuel: -12 }, lean: { openness: 0.7, conscientiousness: 0.3 }, outcome: 'The lab hums all night. The rest of the base is colder.' },
          { id: 'fuel', label: 'Save the fuel', effects: { fuel: 8, science: -4 }, lean: { neuroticism: 0.5, conscientiousness: 0.3 }, outcome: 'The freezer is switched to minimum. A year of samples is at risk.' },
        ] },
      { id: 'evac', at: 0.6, title: 'The medical evacuation', text: 'One of them is unwell. A risky mid-winter flight could take them out — costing the station its funding and the others their sanity.',
        options: [
          { id: 'fly', label: 'Call for the flight', effects: { systems: -8, science: -8 }, all: { stress: -8, mood: -4 }, lean: { agreeableness: 0.6, neuroticism: 0.3 }, outcome: 'A Twin Otter lands on the skiway in the dark and leaves within the hour.' },
          { id: 'tough', label: 'Tough it out', effects: { science: 6 }, all: { stress: 12 }, lean: { conscientiousness: 0.4, agreeableness: -0.3 }, outcome: 'They tell headquarters everyone is fine. Nobody believes it.' },
        ] },
      { id: 'sun', finale: true, at: 0.82, title: 'The last generator', text: 'The backup generator is failing. Strip the lab for parts — or nurse both through to the sunrise?',
        options: [
          { id: 'strip', label: 'Strip the lab', effects: { systems: 22, science: -14 }, lean: { conscientiousness: 0.4, openness: -0.3 }, outcome: 'Parts from the lab keep the heat on. The experiment dies quietly.' },
          { id: 'nurse', label: 'Nurse both', effects: { science: 8, fuel: -10, systems: -6 }, all: { energy: -10 }, lean: { openness: 0.5, neuroticism: -0.4 }, outcome: 'They take turns babysitting the generator around the clock.' },
        ] },
    ],
  },
  will: {
    acts: ['The funeral', 'The house', 'The reading'],
    dilemmas: [
      { id: 'letters', at: 0.3, title: 'The letters', text: 'A box of Mum\'s letters turns up in the attic. Read them together, or burn them unread as she might have wanted?',
        options: [
          { id: 'read', label: 'Read them', effects: { truth: 16, harmony: -8 }, all: { stress: 8 }, lean: { openness: 0.7 }, outcome: 'Old handwriting, old secrets. Someone has to leave the room.' },
          { id: 'burn', label: 'Burn them', effects: { harmony: 8, grief: 8 }, lean: { agreeableness: 0.4, openness: -0.5 }, outcome: 'They burn the box in the garden and say a few words.' },
        ] },
      { id: 'carer', at: 0.6, title: 'The one who stayed', text: 'The sibling who cared for Mum for years asks for a bigger share. Fair is not the same as equal.',
        options: [
          { id: 'more', label: 'Give them more', effects: { harmony: 10, truth: 4 }, all: { mood: 4 }, lean: { agreeableness: 0.8 }, outcome: 'It is agreed. The carer cries, for the first time since the funeral.' },
          { id: 'equal', label: 'Equal shares', effects: { harmony: -12 }, lean: { conscientiousness: 0.4, agreeableness: -0.5 }, outcome: '"Equal" is said very firmly. Doors are closed rather hard.' },
        ] },
      { id: 'house', finale: true, at: 0.82, title: 'The house', text: 'The estate agent needs an answer by morning. A developer\'s offer is generous — and the garden would be gone.',
        options: [
          { id: 'sell', label: 'Take the offer', effects: { truth: 6, grief: -6 }, all: { stress: -6 }, lean: { conscientiousness: 0.3, openness: 0.3 }, outcome: 'They sign. Mum\'s roses will be a car park.' },
          { id: 'keep', label: 'Keep it in the family', effects: { grief: 12, harmony: 6 }, all: { stress: 6 }, lean: { agreeableness: 0.5, neuroticism: 0.3 }, outcome: 'They turn the offer down and nobody knows quite how they will afford it.' },
        ] },
    ],
  },
  roadtrip: {
    acts: ['Chicago', 'The open road', 'Santa Monica'],
    dilemmas: [
      { id: 'detour', at: 0.3, title: 'The Grand Canyon detour', text: 'It is two hundred miles out of the way. "We will never be this close again."',
        options: [
          { id: 'go', label: 'Go to the canyon', effects: { fun: 18, distance: -8, money: -8 }, all: { mood: 10 }, lean: { openness: 0.8 }, outcome: 'They watch the sun go down over the canyon in total silence.' },
          { id: 'skip', label: 'Stay on schedule', effects: { distance: 8 }, all: { mood: -4 }, lean: { conscientiousness: 0.6 }, outcome: 'They see the turn-off sign and keep driving.' },
        ] },
      { id: 'hitch', at: 0.58, title: 'The hitchhiker', text: 'A young woman with a guitar case is thumbing a ride outside Albuquerque. The van is already full.',
        options: [
          { id: 'pick', label: 'Pick her up', effects: { fun: 10, money: 4 }, all: { stress: 4 }, lean: { agreeableness: 0.5, extraversion: 0.5 }, outcome: 'She plays them songs for three hundred miles and pays for gas.' },
          { id: 'drive', label: 'Drive past', effects: { distance: 6 }, lean: { neuroticism: 0.5, agreeableness: -0.3 }, outcome: 'Nobody looks at her as they pass.' },
        ] },
      { id: 'vegas', finale: true, at: 0.82, title: 'Vegas', text: 'Last stop before the coast. Put the last of the money on red for one big night, or limp into LA with gas money?',
        options: [
          { id: 'red', label: 'Put it on red', effects: { money: -14, fun: 22 }, all: { stress: 8 }, lean: { openness: 0.4, conscientiousness: -0.6 }, outcome: 'The wheel spins. Whatever happens, they will tell this story for years.' },
          { id: 'safe', label: 'Keep the gas money', effects: { money: 8, distance: 8 }, lean: { conscientiousness: 0.6 }, outcome: 'They eat cheap noodles in the van, watching the Strip glow.' },
        ] },
    ],
    items: [
      { item: 'van keys', label: 'Do a proper repair', effects: { van: 16 }, self: { energy: -10 }, verb: 'stripped down the engine with the toolbox' },
      { item: 'a credit card', label: 'Pay for everything', effects: { money: 18, fun: 6 }, once: true, verb: 'quietly put the motel and dinner on the credit card' },
    ],
  },
  house: {
    acts: ['Move-in day', 'Week four', 'The final'],
    dilemmas: [
      { id: 'twist', at: 0.3, title: 'The twist', text: 'Producers offer the house a luxury weekend — in exchange, one of them must go up for eviction.',
        options: [
          { id: 'luxury', label: 'Take the luxury', effects: { food: 18, drama: 14, harmony: -10 }, lean: { extraversion: 0.5, agreeableness: -0.4 }, outcome: 'Champagne, lobster, and a nomination envelope on the table.' },
          { id: 'refuse', label: 'Refuse together', effects: { harmony: 12, drama: -6 }, lean: { agreeableness: 0.7 }, outcome: 'They refuse as one. The audience loves it.' },
        ] },
      { id: 'secret', at: 0.6, title: 'The secret task', text: 'Big Brother sets a secret task: make the others believe you are leaving. The prize is the shopping budget.',
        options: [
          { id: 'do', label: 'Play along', effects: { food: 10, drama: 12, harmony: -8 }, lean: { openness: 0.4, agreeableness: -0.3 }, outcome: 'Tears, packed suitcases, a fake farewell. The reveal is chaos.' },
          { id: 'no', label: 'Refuse to deceive', effects: { harmony: 6 }, all: { mood: 4 }, lean: { agreeableness: 0.6, conscientiousness: 0.3 }, outcome: 'They tell Big Brother no on camera. The clip goes viral anyway.' },
        ] },
      { id: 'final', finale: true, at: 0.82, title: 'The live final', text: 'The host offers one of them a cash buyout to leave before the result. Should the house encourage someone to take it?',
        options: [
          { id: 'cash', label: 'Take the money', effects: { drama: 16, harmony: -6 }, all: { stress: 6 }, lean: { neuroticism: 0.4, conscientiousness: 0.3 }, outcome: 'A briefcase, a gasp, a door closing.' },
          { id: 'together', label: 'Go to the vote together', effects: { harmony: 14 }, all: { mood: 6 }, lean: { agreeableness: 0.6, openness: 0.3 }, outcome: 'Arms linked on the sofa, they wait for the result.' },
        ] },
    ],
  },
  snowbound: {
    acts: ['The ridge', 'The storm', 'The break'],
    dilemmas: [
      { id: 'split', at: 0.3, title: 'Go for help?', text: 'The two fittest could try to descend for help in a gap in the weather, leaving the injured with one carer.',
        options: [
          { id: 'go', label: 'Send two down', effects: { contact: 18, warmth: -8 }, all: { stress: 10 }, lean: { openness: 0.5, neuroticism: -0.5 }, outcome: 'Two figures vanish into the whiteout, roped together.' },
          { id: 'stay', label: 'Nobody leaves', effects: { warmth: 6, casualty: 6 }, lean: { agreeableness: 0.5, neuroticism: 0.4 }, outcome: 'They bank the stove and settle in to wait out the storm.' },
        ] },
      { id: 'pills', at: 0.58, title: 'The painkillers', text: 'Only a few strong painkillers are left. Use them now so the injured hiker can sleep, or save them for the descent?',
        options: [
          { id: 'now', label: 'Use them now', effects: { casualty: 12 }, all: { mood: 4 }, lean: { agreeableness: 0.6 }, outcome: 'For the first time in two days, the hiker sleeps.' },
          { id: 'save', label: 'Save them', effects: { casualty: -6 }, lean: { conscientiousness: 0.6 }, outcome: 'The hiker grits their teeth through another night.' },
        ] },
      { id: 'heli', finale: true, at: 0.82, title: 'The helicopter', text: 'A rescue helicopter can reach the col above them, but not the hut. Carry the injured hiker up in deep snow, or signal it to wait for a better window?',
        options: [
          { id: 'carry', label: 'Carry them up', effects: { contact: 24, casualty: -4 }, all: { energy: -18 }, lean: { extraversion: 0.4, neuroticism: -0.5 }, outcome: 'Four people, one stretcher, three hundred metres of snow.' },
          { id: 'signal', label: 'Signal it to wait', effects: { contact: 10, warmth: -10 }, lean: { conscientiousness: 0.5, neuroticism: 0.4 }, outcome: 'The helicopter hovers, thinks, and turns back towards the valley.' },
        ] },
    ],
  },
  escape: {
    acts: ['The door locks', 'The clock', 'The last code'],
    dilemmas: [
      { id: 'hint', at: 0.3, title: 'The hint button', text: 'A red button offers a hint — for five minutes of air off the clock.',
        options: [
          { id: 'press', label: 'Press it', effects: { clues: 16, air: -10 }, lean: { neuroticism: 0.5, openness: -0.3 }, outcome: 'A speaker crackles: "Look up." Above them is a hidden panel.' },
          { id: 'no', label: 'Work it out', effects: { puzzles: 4 }, all: { stress: 6 }, lean: { openness: 0.6, conscientiousness: 0.3 }, outcome: 'They refuse the hint and stare at the walls harder.' },
        ] },
      { id: 'vent', at: 0.6, title: 'The vent', text: 'A narrow air vent might lead out. Someone small could crawl through — into the dark — alone.',
        options: [
          { id: 'crawl', label: 'Send someone through', effects: { clues: 12, puzzles: 8 }, all: { stress: 10 }, lean: { openness: 0.6, neuroticism: -0.5 }, outcome: 'A torch beam disappears into the vent. Scraping. Then silence. Then: "Found something!"' },
          { id: 'seal', label: 'Stay together', effects: { air: 6 }, lean: { agreeableness: 0.5, neuroticism: 0.4 }, outcome: 'They wedge the vent open to let air in instead.' },
        ] },
      { id: 'wires', finale: true, at: 0.82, title: 'The final lock', text: 'The last lock is a keypad — or they could short the wiring and force it, risking a total lockdown.',
        options: [
          { id: 'short', label: 'Short the wires', effects: { puzzles: 24, air: -14 }, all: { stress: 12 }, lean: { openness: 0.4, conscientiousness: -0.4 }, outcome: 'Sparks, a smell of burning plastic, and a heavy clunk.' },
          { id: 'code', label: 'Solve the code', effects: { puzzles: 12 }, all: { stress: 6 }, lean: { conscientiousness: 0.6 }, outcome: 'Four people, one keypad, everyone shouting numbers.' },
        ] },
    ],
    items: [
      { item: 'a multimeter', label: 'Trace the circuits', effects: { clues: 12, puzzles: 4 }, verb: 'traced the hidden circuits with the multimeter' },
    ],
  },
  summit: {
    acts: ['Opening statements', 'The all-nighter', 'The gavel'],
    dilemmas: [
      { id: 'leak', at: 0.3, title: 'The leaked draft', text: 'A draft text has leaked to the press. Denounce it publicly or quietly work from it?',
        options: [
          { id: 'denounce', label: 'Denounce it', effects: { pressure: 12, trust: -8 }, lean: { extraversion: 0.6, agreeableness: -0.4 }, outcome: 'Four press conferences, four different stories.' },
          { id: 'use', label: 'Work from it', effects: { text: 12, trust: 4 }, lean: { conscientiousness: 0.4, agreeableness: 0.4 }, outcome: 'They sit down with the leaked draft and a red pen.' },
        ] },
      { id: 'fund', at: 0.6, title: 'The loss-and-damage fund', text: 'A fund for vulnerable nations could unlock the deal — if the rich countries pay into it.',
        options: [
          { id: 'fund', label: 'Back the fund', effects: { trust: 14, text: 8 }, lean: { agreeableness: 0.7, openness: 0.3 }, outcome: 'The small island delegate has to step outside for a moment.' },
          { id: 'block', label: 'Water it down', effects: { trust: -12, text: 4 }, lean: { agreeableness: -0.5, conscientiousness: 0.3 }, outcome: '"Voluntary contributions." Everyone knows what that means.' },
        ] },
      { id: 'gavel', finale: true, at: 0.82, title: 'The gavel', text: 'The president will gavel the text through unless someone objects. Accept a weak deal — or hold out for more and risk nothing at all?',
        options: [
          { id: 'accept', label: 'Accept the weak deal', effects: { text: 20, pressure: -10 }, all: { mood: -4 }, lean: { conscientiousness: 0.4, neuroticism: 0.3 }, outcome: 'Bang. Applause, tired and thin.' },
          { id: 'hold', label: 'Hold out', effects: { trust: 8, text: -10, pressure: 16 }, all: { stress: 10 }, lean: { openness: 0.5, agreeableness: -0.2 }, outcome: 'A flag goes up. The hall erupts.' },
        ] },
    ],
  },
  kitchen: {
    acts: ['Prep', 'Service', 'The critic\'s table'],
    dilemmas: [
      { id: 'fish', at: 0.3, title: 'The fish', text: 'The fish delivery smells wrong. Pull the signature dish from the menu, or use it and hope?',
        options: [
          { id: 'pull', label: 'Pull the dish', effects: { quality: 6, critic: -6 }, lean: { conscientiousness: 0.7 }, outcome: 'A chalkboard special replaces the signature dish.' },
          { id: 'risk', label: 'Cook it anyway', effects: { tickets: -10, quality: -10 }, all: { stress: 8 }, lean: { conscientiousness: -0.5, neuroticism: -0.3 }, outcome: 'Extra lemon, extra butter, and a prayer.' },
        ] },
      { id: 'walkin', at: 0.6, title: 'The walk-in', text: 'A party of twelve walks in without a booking — friends of the owner. Seat them, and the kitchen will drown.',
        options: [
          { id: 'seat', label: 'Seat them', effects: { tickets: 14, critic: 4 }, all: { stress: 10 }, lean: { agreeableness: 0.6, extraversion: 0.4 }, outcome: 'Twelve more covers. The printer does not stop.' },
          { id: 'turn', label: 'Turn them away', effects: { quality: 6 }, all: { mood: -4 }, lean: { conscientiousness: 0.5, agreeableness: -0.4 }, outcome: 'Awkward smiles at the door. The kitchen breathes.' },
        ] },
      { id: 'critic', finale: true, at: 0.82, title: 'The critic\'s plate', text: 'The critic is at table six. Send the dish everyone practised — or the new one the chef dreamt up this afternoon?',
        options: [
          { id: 'safe', label: 'The practised dish', effects: { critic: 10, quality: 6 }, lean: { conscientiousness: 0.6, neuroticism: 0.3 }, outcome: 'Clean, precise, exactly right.' },
          { id: 'bold', label: 'The new dish', effects: { critic: 22, quality: -8 }, all: { stress: 10 }, lean: { openness: 0.8 }, outcome: 'The critic puts their pen down and just eats.' },
        ] },
    ],
  },
  campaign: {
    acts: ['Launch', 'The trail', 'Election night'],
    dilemmas: [
      { id: 'attack', at: 0.3, title: 'The attack ad', text: 'An ad is ready that hammers the incumbent\'s record. It is true — and nasty.',
        options: [
          { id: 'run', label: 'Run it', effects: { polls: 4, scandal: 10 }, lean: { agreeableness: -0.6, extraversion: 0.3 }, outcome: 'It airs at prime time. The polls twitch.' },
          { id: 'positive', label: 'Stay positive', effects: { volunteers: 10 }, all: { mood: 4 }, lean: { agreeableness: 0.6 }, outcome: 'They film a hopeful ad in a primary school instead.' },
        ] },
      { id: 'donor', at: 0.6, title: 'The big donor', text: 'A property developer offers to fund the final push — and wants "a conversation" about planning law later.',
        options: [
          { id: 'take', label: 'Take the money', effects: { funds: 24, scandal: 12 }, lean: { conscientiousness: -0.3, neuroticism: 0.3 }, outcome: 'The money arrives within the hour. So does a lunch invitation.' },
          { id: 'refuse', label: 'Refuse it', effects: { volunteers: 8, scandal: -6 }, all: { stress: 6 }, lean: { conscientiousness: 0.5, agreeableness: 0.3 }, outcome: 'They refuse — and tell a journalist they did.' },
        ] },
      { id: 'debate', finale: true, at: 0.82, title: 'The last debate', text: 'The incumbent has stumbled. Go for the knockout on live TV — or stay presidential?',
        options: [
          { id: 'knock', label: 'Go for the knockout', effects: { polls: 5, scandal: 8 }, all: { stress: 8 }, lean: { extraversion: 0.6, agreeableness: -0.4 }, outcome: 'The line lands. The studio audience gasps; social media explodes.' },
          { id: 'calm', label: 'Stay presidential', effects: { polls: 2, scandal: -4 }, lean: { conscientiousness: 0.5, agreeableness: 0.4 }, outcome: 'Steady, warm, unflustered. Pundits call it "a safe night".' },
        ] },
    ],
  },
  bakery: {
    acts: ['The rent rise', 'Fighting back', 'The last weekend'],
    dilemmas: [
      { id: 'recipe', at: 0.3, title: 'Grandma\'s recipe', text: 'A food magazine wants to print Grandma\'s secret sourdough recipe. Fame — or keep it in the family?',
        options: [
          { id: 'print', label: 'Share the recipe', effects: { reputation: 9, customers: 6, cash: -4 }, lean: { openness: 0.6, extraversion: 0.4 }, outcome: 'The magazine runs a two-page spread. The queue goes round the corner.' },
          { id: 'keep', label: 'Keep it secret', effects: { reputation: 6 }, all: { mood: 4 }, lean: { conscientiousness: 0.4, openness: -0.4 }, outcome: 'Grandma winks. "Some things are not for sale."' },
        ] },
      { id: 'chain', at: 0.6, title: 'The chain café', text: 'The chain café offers to stock their bread — at a price that barely covers the flour.',
        options: [
          { id: 'deal', label: 'Take the deal', effects: { cash: 14, reputation: -8 }, all: { energy: -8 }, lean: { conscientiousness: 0.4, neuroticism: 0.4 }, outcome: 'Two hundred loaves a day, every day, for very little.' },
          { id: 'no', label: 'Say no', effects: { reputation: 5, cash: -8 }, lean: { agreeableness: -0.3, openness: 0.4 }, outcome: 'The street hears they said no to the chain. Sales tick up.' },
        ] },
      { id: 'crowd', finale: true, at: 0.82, title: 'The crowdfund', text: 'Regulars offer to crowdfund the rent rise. Accept their money — or close with dignity?',
        options: [
          { id: 'accept', label: 'Accept the crowdfund', effects: { cash: 22, customers: 10 }, all: { mood: 8 }, lean: { agreeableness: 0.5, openness: 0.4 }, outcome: 'Six hundred backers in a weekend. Grandma frames the list.' },
          { id: 'pride', label: 'Refuse charity', effects: { reputation: 5, cash: -10 }, all: { stress: 8 }, lean: { conscientiousness: 0.4, agreeableness: -0.4 }, outcome: 'They thank everyone and say they will manage on their own.' },
        ] },
    ],
  },
  wedding: {
    acts: ['The morning', 'Disasters', 'First dance'],
    dilemmas: [
      { id: 'rain', at: 0.3, title: 'The rain', text: 'Rain is forecast. Move the ceremony indoors to the cramped hall, or gamble on the garden?',
        options: [
          { id: 'inside', label: 'Move indoors', effects: { venue: 8, schedule: -8 }, lean: { conscientiousness: 0.6, neuroticism: 0.4 }, outcome: 'Chairs are carried in, two hundred of them, in a chain.' },
          { id: 'garden', label: 'Gamble on the garden', effects: { venue: -6, nerves: 10 }, all: { mood: 6 }, lean: { openness: 0.6, neuroticism: -0.4 }, outcome: 'They leave the chairs in the garden and watch the sky.' },
        ] },
      { id: 'ex', at: 0.58, title: 'The uninvited ex', text: 'The groom\'s ex has turned up at the reception "just to say congratulations".',
        options: [
          { id: 'stay', label: 'Let them stay for one drink', effects: { nerves: 12 }, all: { stress: 6 }, lean: { agreeableness: 0.6 }, outcome: 'One drink. Everyone watches the one drink.' },
          { id: 'out', label: 'Politely show them out', effects: { nerves: -6, schedule: -4 }, lean: { agreeableness: -0.4, conscientiousness: 0.4 }, outcome: 'The maid of honour walks them to their car with a fixed smile.' },
        ] },
      { id: 'speech', finale: true, at: 0.82, title: 'The speech', text: 'The best-man speech has a joke about the stag weekend that could go either way. Keep it in?',
        options: [
          { id: 'keep', label: 'Keep the joke', effects: { nerves: 8, venue: 8 }, all: { mood: 8 }, lean: { extraversion: 0.7, openness: 0.4 }, outcome: 'A beat of silence. Then the bride laughs first, and the room follows.' },
          { id: 'cut', label: 'Cut it', effects: { nerves: -8 }, lean: { conscientiousness: 0.5, neuroticism: 0.4 }, outcome: 'A sweet, short speech. Grandparents approve.' },
        ] },
    ],
  },
}
