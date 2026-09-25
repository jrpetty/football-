/**
 * Debate motions and courtroom case files.
 *
 * Motions are balanced, non-political and harmless: reasonable people argue
 * both sides, and neither side needs anything but good arguments. `pro` and
 * `con` list a few starting points for each side (used by the mock-mode
 * simulator and as a sanity check that the motion really has two sides; they
 * are never sent to the models).
 *
 * Case files are fictional. Every case has facts that cut both ways, so the
 * verdict turns on how well each side uses the actual exhibits, not on a
 * "right answer". Any change here changes the debate / courtroom fingerprint.
 */

export interface Motion {
  id: string;
  title: string;
  /** One sentence of neutral context shown to both sides and the judges. */
  context: string;
  pro: string[];
  con: string[];
}

export interface Exhibit {
  id: string;
  title: string;
  text: string;
}

export interface CaseFile {
  id: string;
  title: string;
  /** The charge, e.g. "Theft of a violin worth £40,000". */
  charge: string;
  /** Agreed facts both sides accept. */
  summary: string;
  exhibits: Exhibit[];
  /** Mock-mode starting points (never sent to the models). */
  prosecution: string[];
  defence: string[];
}

export const MOTIONS: Motion[] = [
  {
    id: 'car-free-centres',
    title: 'Cities should ban cars from their centres',
    context: 'Several European cities have closed central districts to private cars; others have tried and reversed the policy.',
    pro: ['cleaner air and fewer road deaths', 'streets become places for people, cafés and markets', 'public transport gets faster when it is not stuck in traffic'],
    con: ['disabled and elderly residents depend on door-to-door driving', 'deliveries and trades still need vehicles', 'shops in the centre can lose customers to out-of-town malls'],
  },
  {
    id: 'no-primary-homework',
    title: 'Homework should be abolished in primary schools',
    context: 'Research on homework for children under 11 finds small or unclear benefits; many parents still expect it.',
    pro: ['young children learn more through play, reading and rest', 'homework widens the gap between families with and without time to help', 'evenings are for family, sport and sleep'],
    con: ['short practice builds habits needed at secondary school', 'parents see what their children are learning', 'reading and times tables need regular practice'],
  },
  {
    id: 'free-museums',
    title: 'Museums should be free to enter',
    context: 'Some countries fund national museums so entry is free; others rely on ticket income.',
    pro: ['culture belongs to everyone, not only those who can pay', 'free entry brings in people who would never plan a visit', 'museums hold public collections already paid for by taxes'],
    con: ['ticket income pays for conservation and new exhibitions', 'free entry mostly benefits tourists and regular visitors', 'overcrowding can damage fragile objects'],
  },
  {
    id: 'four-day-week',
    title: 'The four-day working week should become the standard',
    context: 'Trials of a four-day week at the same pay have reported mixed but often positive results.',
    pro: ['rested staff are more productive per hour', 'better retention and fewer sick days', 'more time for family, volunteering and exercise'],
    con: ['hospitals, schools and shops need cover five or more days a week', 'small firms may not absorb the cost', 'work can be squeezed into longer, more stressful days'],
  },
  {
    id: 'phase-out-zoos',
    title: 'Zoos should be phased out',
    context: 'Modern zoos fund conservation and breeding programmes, but critics question keeping wild animals in enclosures.',
    pro: ['large, wide-ranging animals suffer in enclosures', 'money could go directly to protecting habitats', 'documentaries and sanctuaries can educate without captivity'],
    con: ['breeding programmes have saved species from extinction', 'seeing living animals inspires children to care about nature', 'zoos fund research and field conservation'],
  },
  {
    id: 'later-school-start',
    title: 'Secondary schools should start no earlier than 9 a.m.',
    context: 'Teenagers’ body clocks shift later in adolescence; many schools start around 8 a.m.',
    pro: ['teenagers sleep more and concentrate better', 'fewer absences and late arrivals', 'safer driving and better mood'],
    con: ['parents who start work early lose a morning routine', 'after-school sport and jobs get squeezed', 'buses and timetables would be costly to change'],
  },
  {
    id: 'ebooks-better',
    title: 'E-books are better than printed books for readers',
    context: 'E-readers and phones make thousands of books portable; printed book sales remain strong.',
    pro: ['a whole library fits in a pocket', 'adjustable text size helps readers with poor eyesight', 'cheaper and instantly available'],
    con: ['many readers remember more from paper', 'printed books need no battery and last for centuries', 'books can be lent, signed and shared'],
  },
  {
    id: 'replace-tipping',
    title: 'Restaurant tipping should be replaced by higher menu prices',
    context: 'Customs differ: some countries expect large tips, others include service in the price.',
    pro: ['staff get a predictable wage', 'customers see the real price up front', 'removes awkwardness and bias in who gets tipped well'],
    con: ['tips reward good service', 'staff in busy restaurants can earn more with tips', 'higher menu prices may put customers off'],
  },
  {
    id: 'ocean-over-space',
    title: 'Ocean exploration deserves more funding than space exploration',
    context: 'Most of the deep ocean is unmapped, while space programmes receive far larger budgets.',
    pro: ['the ocean regulates our climate and feeds billions', 'unknown species may hold new medicines', 'discoveries are closer and cheaper to reach'],
    con: ['space research gave us satellites, GPS and weather forecasts', 'space inspires students into science', 'planetary defence and long-term survival need space capability'],
  },
  {
    id: 'coursework-over-exams',
    title: 'Final exams should be replaced by coursework',
    context: 'Many school systems rely on a few high-stakes exams at the end of a course.',
    pro: ['coursework measures skills over time, not one bad day', 'less exam stress', 'closer to how work is done in real jobs'],
    con: ['exams are harder to cheat on', 'coursework favours students with help at home', 'exams test knowledge under fair, equal conditions'],
  },
  {
    id: 'board-games-families',
    title: 'Board games are better than video games for family time',
    context: 'Families play both; the question is which does more for time spent together.',
    pro: ['everyone sits around one table and talks', 'no screens, no in-game purchases', 'easy for grandparents and young children to join'],
    con: ['co-operative video games can bring families together too', 'video games can include relatives far away', 'many children are more motivated by video games'],
  },
  {
    id: 'video-review-sport',
    title: 'Video review does more good than harm in professional sport',
    context: 'Football, cricket, tennis and rugby use video review to check close decisions.',
    pro: ['fewer wrong decisions decide big matches', 'players and fans trust the result', 'referees are protected from abuse over mistakes'],
    con: ['long delays kill the excitement of a goal', 'decisions still depend on judgement', 'fans in the stadium are left in the dark'],
  },
  {
    id: 'learn-to-cook',
    title: 'Cooking should be a compulsory school subject',
    context: 'Many young people leave school unable to prepare a simple meal.',
    pro: ['a basic life skill that improves health', 'saves money compared with takeaways', 'teaches maths, science and planning in a practical way'],
    con: ['the timetable is already full', 'kitchens and ingredients are expensive for schools', 'families can teach cooking at home'],
  },
  {
    id: 'record-lectures',
    title: 'Universities should record every lecture',
    context: 'Recorded lectures help students revise; some lecturers say they reduce attendance.',
    pro: ['students who are ill or working can still learn', 'revision is easier and fairer', 'helps students whose first language is different'],
    con: ['attendance and discussion suffer', 'lecturers speak less freely', 'students put off watching and fall behind'],
  },
];

export const CASES: CaseFile[] = [
  {
    id: 'missing-violin',
    title: 'The Missing Violin',
    charge: 'Theft of an antique violin (valued at £40,000) from the Harlow Conservatory',
    summary:
      'On the night of 14 March the conservatory’s antique violin disappeared from Practice Room 6. The defendant, Mara Quinn (22), is a violin student. The violin was found nine days later at a pawn shop across town. Mara says she practised in Room 6 until 9 p.m. and left the violin in its case.',
    exhibits: [
      { id: 'A', title: 'Keycard log, Practice Room 6', text: '20:02 Quinn M. (entry) · 21:04 Quinn M. (exit) · 22:47 Maintenance master card (entry) · 22:51 Maintenance master card (exit). The master card is shared by three caretakers and was reported "left on the desk" on 12 March.' },
      { id: 'B', title: 'Pawn shop ticket', text: 'Received 16 March, 11:20. Item: violin in black case. Seller ID: student card in the name "M. Quinn". Paid £900 cash. The clerk wrote: "young woman, dark hood, kept head down".' },
      { id: 'C', title: 'Text messages (defendant’s phone)', text: '13 March, Mara to her sister: "Rent is due Friday and I’m £700 short, I don’t know what to do." · 17 March, Mara to her sister: "Sorted it, don’t worry."' },
      { id: 'D', title: 'Statement of Ben Ortiz (fellow student)', text: '"I lost my wallet with my student card in the canteen on 10 March. Mara’s card went missing the same week — she complained about it." University records show Mara requested a replacement student card on 11 March.' },
      { id: 'E', title: 'Bank statement (defendant)', text: '17 March: cash deposit £700. Mara says it was a loan from her aunt; the aunt has not been called as a witness.' },
    ],
    prosecution: ['the pawn ticket used her student card', 'she was £700 short and deposited £700 the day after the sale', 'she was the last named person in the room'],
    defence: ['her student card was reported lost before the theft', 'a shared master card entered the room at 22:47, after she left', 'the clerk never saw the seller’s face'],
  },
  {
    id: 'bakery-recipe',
    title: 'The Sourdough Secret',
    charge: 'Theft of trade secrets from Crumb & Co. bakery',
    summary:
      'Leo Brandt worked at Crumb & Co. for four years and left in January to open his own bakery, Rise. By March, Rise’s "Harbour Loaf" was winning local awards. Crumb & Co. says it is their secret recipe. Leo says he developed it himself.',
    exhibits: [
      { id: 'A', title: 'Leo’s employment contract', text: 'Clause 9: "Recipes, methods and supplier lists are confidential property of Crumb & Co." There is no clause preventing him from opening a competing bakery.' },
      { id: 'B', title: 'Laboratory comparison', text: 'Both loaves use the same unusual flour blend (70% rye, 30% spelt) and a 36-hour ferment. The report adds: "This combination is described in at least two published baking books (2019, 2021)."' },
      { id: 'C', title: 'Email, 3 January', text: 'From Leo’s work account to his personal account, subject "notes": an attachment named "ferment_schedule_v7.xlsx". Crumb & Co.’s IT says the file matches their internal schedule. Leo says it was his own schedule he had built at work.' },
      { id: 'D', title: 'Leo’s notebook', text: 'Dated pages from 2021 to 2023 show experiments with rye and spelt blends, including one marked "36h — best crumb yet!" dated before he joined Crumb & Co.’s product team.' },
      { id: 'E', title: 'Statement of Priya Shah (Crumb & Co. head baker)', text: '"The 36-hour ferment was my idea in 2022. Leo was in the room when we tested it." Under questioning she agreed that Leo "often suggested changes to recipes".' },
    ],
    prosecution: ['he emailed the ferment schedule to himself days before leaving', 'the loaves are chemically identical', 'the head baker says the method was hers'],
    defence: ['the flour blend is published in baking books', 'his notebook shows the 36-hour ferment before it was tested at work', 'the contract let him compete'],
  },
  {
    id: 'marathon-shortcut',
    title: 'The Marathon Shortcut',
    charge: 'Fraud: claiming third place and £5,000 prize money in the City Marathon by cutting the course',
    summary:
      'Dana Holt finished third in the City Marathon in 2:31:40, eleven minutes faster than her previous best. A rival runner complained that Dana was not seen on the riverside section. The prize money has been withheld.',
    exhibits: [
      { id: 'A', title: 'Timing-chip splits', text: '10 km: 36:10 · 21.1 km: 1:16:30 · 30 km: [no reading] · 35 km: 2:01:05 · finish: 2:31:40. The 30 km timing mat on the riverside failed for 41 other runners between 1:40 and 1:55 race time.' },
      { id: 'B', title: 'Race photographs', text: 'Official photographer at 28 km (riverside): no photo of Dana; the photographer took pictures of about 60% of runners. A spectator’s phone video at 33 km shows Dana running in a group of four.' },
      { id: 'C', title: 'GPS watch data', text: 'Dana’s watch recorded 41.1 km (a full marathon is 42.2 km). Race officials say most watches read 0.3–0.8 km short in the tunnel section. The file’s track cuts a corner near the stadium.' },
      { id: 'D', title: 'Training log', text: 'Dana’s coach supplied logs showing a 2:33 pace training run six weeks before the race, and a new coach from October.' },
      { id: 'E', title: 'Statement of rival runner, Iris Pell', text: '"I was on the riverside from 25 km to 30 km and she never passed me or was ahead of me." Iris finished fourth, 40 seconds behind Dana, and would receive the prize if Dana is disqualified.' },
    ],
    prosecution: ['the missing 30 km split', 'her watch recorded a short distance with a cut corner', 'an eyewitness never saw her on the riverside'],
    defence: ['the 30 km mat failed for 41 runners', 'video shows her at 33 km in a group', 'the witness gains the prize money'],
  },
  {
    id: 'warehouse-fire',
    title: 'The Harbour Warehouse Fire',
    charge: 'Insurance fraud: deliberately starting a fire at an empty warehouse to claim £300,000',
    summary:
      'On 2 August an empty warehouse owned by Tom Reyes burned down at 03:10. Nobody was hurt. Tom had increased the insurance cover four months earlier. The fire service found the fire started near an electrical panel.',
    exhibits: [
      { id: 'A', title: 'Fire investigator’s report', text: '"Origin: the area of the main electrical panel. The cause could be an electrical fault or an ignition source placed there. Traces of a flammable liquid were found, consistent with paint thinner. Paint and thinner were stored in that corner by the previous tenant."' },
      { id: 'B', title: 'Insurance documents', text: 'Cover raised from £180,000 to £300,000 in April. An insurance surveyor’s valuation in March put the building’s value at £290,000 after a roof repair.' },
      { id: 'C', title: 'Phone location data', text: 'Tom’s phone connected to a mast 400 metres from the warehouse at 02:20 on 2 August. The same mast serves Tom’s flat, 700 metres away.' },
      { id: 'D', title: 'Electrician’s invoice', text: 'June: "Panel inspected — old wiring, recommend full replacement (quote £6,800)." No replacement was booked. Tom says he could not afford it that summer.' },
      { id: 'E', title: 'Bank records', text: 'Tom’s business account was £22,000 overdrawn in July. He had listed the warehouse for rent; one viewing was booked for 5 August.' },
    ],
    prosecution: ['he raised the cover four months before the fire', 'he was deeply overdrawn', 'his phone was near the warehouse at 02:20 and thinner was found at the origin'],
    defence: ['the electrician warned of faulty wiring in June', 'the new cover matched a surveyor’s valuation', 'the phone mast also serves his flat, and a viewing was booked for three days later'],
  },
  {
    id: 'lottery-ticket',
    title: 'The Winning Ticket',
    charge: 'Theft of a winning lottery ticket (£25,000) from a customer at a corner shop',
    summary:
      'Mr Albert Finch (78) bought lottery tickets at Sunny Newsagents every week. He says he asked the shop assistant, Kyle Dunn (24), to check his ticket, was told "sorry, not a winner", and left it on the counter. Two weeks later Kyle claimed a £25,000 prize.',
    exhibits: [
      { id: 'A', title: 'Lottery terminal log', text: 'Ticket #4471 checked at 09:14 on 6 May: "WINNER — refer to head office." A second ticket, #5520, was checked at 09:15: "Not a winner."' },
      { id: 'B', title: 'Shop CCTV (no sound)', text: '09:13–09:16: Mr Finch hands over two slips. Kyle scans both, shakes his head, and puts one slip under the counter. Mr Finch leaves at 09:16.' },
      { id: 'C', title: 'Mr Finch’s notebook', text: 'He writes his numbers every week. The 6 May page lists one line of numbers — which matches ticket #5520, not the winning ticket #4471.' },
      { id: 'D', title: 'Kyle’s statement', text: '"The winning ticket was mine. I buy one most weeks and checked it with his. The slip under the counter was the shop’s rubbish pile." The shop owner confirms staff may buy tickets but should not check their own tickets on the till.' },
      { id: 'E', title: 'Purchase records', text: 'Ticket #4471 was sold at Sunny Newsagents on 3 May at 18:42, paid in cash. Kyle’s shift that day ended at 17:00. Mr Finch usually shops on Saturday afternoons; 3 May was a Saturday.' },
    ],
    prosecution: ['the winning ticket was scanned during Mr Finch’s transaction', 'Kyle hid a slip under the counter after shaking his head', 'the ticket was sold on a Saturday afternoon when Mr Finch shops, after Kyle’s shift'],
    defence: ['Mr Finch’s own notebook matches the losing ticket', 'Kyle bought tickets regularly and could have bought it after his shift', 'the slip under the counter is not shown to be the winner'],
  },
  {
    id: 'chess-engine',
    title: 'The Hidden Engine',
    charge: 'Cheating at the Northfield Open chess tournament by receiving computer help (prize: £8,000)',
    summary:
      'Sam Okafor, rated 2150, won the Northfield Open ahead of three higher-rated players. The organisers say his moves matched a chess engine too often. Sam says he had the tournament of his life.',
    exhibits: [
      { id: 'A', title: 'Engine-match analysis', text: 'In rounds 4–7, 71% of Sam’s moves matched the top engine choice (his average in 2023: 52%). The analyst notes that "in rounds 1–3 the match rate was 49%" and that "strong human performances of 65–70% occur a few times per year at this level".' },
      { id: 'B', title: 'Arbiter’s note', text: '"Sam left the playing hall 11 times in round 6, more than any other player. He was checked with a metal detector after round 6: nothing found."' },
      { id: 'C', title: 'Earpiece receipt', text: 'A receipt for a "micro wireless earpiece" found in the car park bin on the final day. No name on it; paid in cash. The hotel next door was hosting an interpreters’ conference that weekend.' },
      { id: 'D', title: 'Rating history', text: 'Sam gained 90 rating points in the six months before the event, after starting lessons with a grandmaster coach. He lost to a 1900-rated player in round 2 of the same tournament.' },
      { id: 'E', title: 'Statement of Sam’s roommate', text: '"Sam studied the opening he played in rounds 5 and 7 every night for a month." The roommate is Sam’s cousin.' },
    ],
    prosecution: ['a 71% engine match in the rounds that decided the prize', 'eleven trips out of the hall in one game', 'an earpiece receipt found at the venue'],
    defence: ['he was searched and nothing was found', 'a 71% match happens to strong humans; he lost to a much weaker player in round 2', 'the receipt has no link to him and there was an interpreters’ conference next door'],
  },
];

export function motionById(id: string): Motion | undefined {
  return MOTIONS.find((m) => m.id === id);
}

export function caseById(id: string): CaseFile | undefined {
  return CASES.find((c) => c.id === id);
}
