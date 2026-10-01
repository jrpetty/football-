// The story of Ashford: what happened, who is still out there, and what it
// takes to call the coast. Threads are told through the radio, notes found
// on runs and people found in the city. Text only; the logic is in story.js.

export const DISTRICT_NAMES = { residential: 'the residential streets', commercial: 'the shopping district', downtown: 'downtown', industrial: 'the industrial zone', park: 'the parkland', military: 'the highway' }

// Story threads, in the order they tend to open.
export const THREADS = {
  wheels: {
    name: 'Wheels',
    blurb: 'The old van in the yard is the only vehicle the camp has, and it is dead: a flat battery, two flat tyres and two bald ones. Until it runs, every trip is on foot, and long walks eat food and water.',
    goals: ['Get the van running'],
    hints: ['Abandoned cars on runs give up tyres when you break them down, and now and then a battery. Garages and gas stations keep both.'],
  },
  dana: {
    name: 'The Voice on the Radio',
    blurb: 'A woman\'s voice on the emergency band, the same few lines on a loop: "This is Dana Okoye, WKRN engineering. I can see the mast from here. I have food for a week, maybe two. If anyone hears this, I know how to get the tower working."',
    goals: ['Find Dana Okoye'],
    done: 'Dana is in camp. She knows the broadcast mast better than anyone alive, and she can calibrate the dish array when the time comes.',
  },
  kessler: {
    name: 'Kessler\'s Secret',
    blurb: 'Kessler Biotech had a research campus on the edge of Ashford. Their files keep turning up in the strangest places: desks, lockers, school labs. Somebody wanted them out of the building.',
    goals: ['Find Kessler files (3)', 'Recover the KX-9 sample case'],
    done: 'The sample case is in camp. Dr. Imre\'s antibody notes are taped to the lid: the Infirmary knows how to make antivirals and immune boosters, and a vaccine is within reach.',
  },
  codebook: {
    name: 'Harbor Light',
    blurb: 'Coastal Command answers only authenticated calls. The evacuation codes were kept in the comms safe at the highway checkpoint, and the safe opens with Colonel Hart\'s keycard. Hart never made it out.',
    goals: ['Find Colonel Hart\'s keycard', 'Open the comms safe at the checkpoint'],
    done: 'The codebook is in camp: forty pages of call signs and one-time codes. When the mast is ready, Coastal Command will know the call is real.',
  },
  convoy: {
    name: 'The Lost Convoy',
    blurb: 'An automatic beacon is pinging on the emergency band: "Harbor Light Seven, vehicle disabled, crew status unknown." One of the evacuation convoy\'s trucks never reached the checkpoint.',
    goals: ['Find Harbor Light Seven'],
    done: 'The convoy truck is in camp: armour plate, a winch, and a bed full of supplies nobody came back for.',
  },
}

// Notes found on runs. where: container kinds they turn up in; types: the
// location types that hold them (any if absent); thread: the story thread a
// note belongs to; clue: what finding it does.
export const NOTES = {
  kessler1: {
    title: 'Kessler Biotech memo: Project Lantern',
    thread: 'kessler', where: ['desk', 'filing', 'server'], minLevel: 2,
    text: 'Distribution: Ashford campus leads only.\n\nPhase I results for KX-9 are remarkable. Nerve regrowth in nine of twelve primates within three weeks. The board wants human trials by spring. Keep the side-effect logs internal until we understand the aggression scores.',
  },
  kessler2: {
    title: 'Lab notebook, page 41',
    thread: 'kessler', where: ['desk', 'filing', 'chemshelf', 'locker'], minLevel: 2,
    text: 'Subject 7 bit the handler through the glove. Handler reports a fever of 38.9 the same evening. Dr. Imre says it is a reaction to the sedative.\n\nIt is not a reaction to the sedative.',
  },
  kessler3: {
    title: 'Incident report: Building C',
    thread: 'kessler', where: ['desk', 'filing', 'server'], minLevel: 2,
    text: 'At 02:14 the negative-pressure seals in Building C failed. Three staff exposed. Two missing. Security footage has been removed for review.\n\nDo not discuss this with county health officials. Refer all calls to Legal.',
  },
  kessler4: {
    title: 'Email: Dr. Lena Imre to the board',
    thread: 'kessler', where: ['desk', 'filing', 'server', 'bookshelf'], minLevel: 2,
    text: 'The vector is transmissible through saliva. It rewires the limbic system in 48 to 72 hours. There is no reversing it after that. If you will not tell the county, I will.\n\nI am moving the reference samples somewhere you cannot shred them.',
  },
  kessler5: {
    title: 'Cold storage manifest',
    thread: 'kessler', where: ['desk', 'filing', 'medcab', 'chemshelf'], minLevel: 2, clue: 'kesslerHospital',
    text: 'Item: KX-9 reference samples, antibody series A to F. One insulated case, sealed.\n\nTransferred to {hospital}, sub-level B, cold room 12. Signed for by L. Imre. Not to be released to Kessler staff.',
  },
  kessler6: {
    title: 'Dr. Imre\'s last entry',
    thread: 'kessler', where: ['desk', 'locker', 'medcab', 'chemshelf'], minLevel: 3,
    text: 'If anyone finds the sample case: the antibody work is eighty per cent done. Series D binds the vector before it reaches the brain. A good infirmary could finish it.\n\nMake the vaccine. Please. I started this.',
  },
  evac1: {
    title: 'Emergency broadcast, transcript',
    where: ['desk', 'bookshelf', 'trash', 'filing'],
    text: 'This is an emergency broadcast for the residents of Ashford. Proceed on foot or by vehicle to Checkpoint Bravo on Route 12. Bring identification and medication. Do not bring pets. Anyone showing a fever will be held for observation.\n\nThis message repeats.',
  },
  evac2: {
    title: 'Operation Harbor Light: orders',
    where: ['locker', 'desk', 'filing'], minLevel: 3, clue: 'codebookHint',
    text: 'Evacuees move by convoy to the Port Halden Quarantine Zone. Last convoy departs 06:00, day nine.\n\nCoastal Command will accept contact on the emergency band from authenticated stations only. Authentication codes are held in the comms safe at the checkpoint. Keycard access: Col. R. Hart.',
  },
  evac3: {
    title: 'Torn notice, Checkpoint Bravo',
    where: ['trash', 'dumpster', 'desk'],
    text: 'CHECKPOINT CLOSED. INFECTED INSIDE THE PERIMETER.\n\nDO NOT APPROACH. PROCEED TO THE COAST BY ROUTE 9.\n\n(Someone has written underneath in marker: "Route 9 is worse.")',
  },
  evac4: {
    title: 'Radio log, Harbor PD',
    where: ['desk', 'locker', 'filing'], minLevel: 2, clue: 'hartAt',
    text: '22:14. Col. Hart and two of his people fell back to {police}. He still has the comms keycard. Says he will hold until relieved.\n\n23:50. No answer from {police}.\n\n02:30. No answer.',
  },
  diary1: {
    title: 'A diary, the last pages',
    where: ['dresser', 'desk', 'bookshelf', 'wardrobe'],
    text: 'Day 6. The bus was supposed to come at noon. Mr. Pryce from next door says the army forgot about the east side. Mum says stop listening to Mr. Pryce.\n\nDay 7. Mr. Pryce is in the garden. He doesn\'t answer when we call him.',
  },
  diary2: {
    title: 'Note on a fridge',
    where: ['fridge', 'cabinet', 'counter'],
    text: 'Gone to Grandma\'s on Birch Lane. Took the dog. Took the good knife. Love you. Come find us.\n\nM.',
  },
  diary3: {
    title: 'Scrawled inside a cupboard',
    where: ['wardrobe', 'cabinet', 'locker'],
    text: 'THEY HEAR YOU BEFORE THEY SEE YOU.\nTHEY DON\'T SEE WELL IN THE DARK.\nSTAY LOW. STAY QUIET.\nDON\'T LET THEM BITE.',
  },
  diary4: {
    title: 'A teacher\'s notes',
    where: ['desk', 'bookshelf', 'filing'], types: ['school', 'office'],
    text: 'Forty-one children in the gym tonight. We pushed the bleachers against the doors. The radio says buses in the morning.\n\nIf you are reading this, the buses came. Please let the buses have come.',
  },
  wkrn1: {
    title: 'WKRN engineering handbook',
    thread: 'dana', where: ['bookshelf', 'desk', 'filing', 'server'], minLevel: 2,
    text: 'Chapter 9: Phasing the dish array.\n\nThe array cannot be aligned from the control room. It has to be phased by hand at each stage, with a field meter and somebody on the radio. This chapter was written by D. Okoye. If in doubt, ask Dana.',
  },
  wkrn2: {
    title: 'Dana\'s notebook',
    thread: 'dana', where: ['desk', 'locker', 'bookshelf', 'dresser'], clue: 'danaKitchen',
    text: 'If it goes bad, don\'t go home. Somewhere with a walk-in fridge and a back door. A diner, a school kitchen, a supermarket. Somewhere I can still see the mast from.\n\nI keep thinking: if the mast came back, the coast would hear us.',
  },
  hart: {
    title: 'Colonel Hart\'s field journal',
    thread: 'codebook', where: [], minLevel: 5,
    text: 'They took the checkpoint in under an hour. I have the card. The codes stay with the safe; the card stays with me. Whoever finds this: the safe is in the comms office. Make the call.',
  },
}
// Who the story brings to camp.
export const STORY_PEOPLE = {
  dana: { first: 'Dana', last: 'Okoye', occ: 'electrician', female: true, quality: 4, age: 44 },
}
