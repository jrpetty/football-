// Independent verifier for logic-grid prompts: reads the Categories and Clues from the PROMPT TEXT,
// assigns meaning to each English phrase (hand-written here, not imported from the generator),
// and brute-forces every arrangement. Usage: node verify.mjs grid_cases.json
import fs from 'node:fs';

// ---- per-theme phrase dictionaries ------------------------------------------------------
// subject/predicate/negated-predicate templates: {v} = a value of that category.
// pos: predicate for "at position p" (p is a 1-based index or a label), npos: its negation.
// geometry: 'line' | 'circle' | 'grid:R:C'.
const T = {
  beach: { geo: 'line', cats: ['Names', 'Kayak colours', 'Snacks'],
    subj: [['Names', '{v}'], ['Kayak colours', 'the person with the {v} kayak'], ['Snacks', 'the person who brought {v}']],
    pred: [['Names', 'is {v}'], ['Kayak colours', 'has the {v} kayak'], ['Snacks', 'brought {v}']],
    npred: [['Names', 'is not {v}'], ['Kayak colours', 'does not have the {v} kayak'], ['Snacks', 'did not bring {v}']],
    pos: 'is in spot {p}', npos: 'is not in spot {p}',
    rel: { adj: 'is in a spot next to', notadj: 'is not in a spot next to', left: 'is somewhere to the left of', immleft: 'is immediately to the left of', end: 'is in one of the two end spots' },
    dist: /^The spot numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  boats: { geo: 'line', cats: ['Owners', 'Dogs', 'Hull colours'],
    subj: [['Owners', '{v}'], ['Dogs', 'the owner of the {v}'], ['Hull colours', 'the owner of the {v} houseboat']],
    pred: [['Owners', 'is {v}'], ['Dogs', 'owns the {v}'], ['Hull colours', 'owns the {v} houseboat']],
    npred: [['Owners', 'is not {v}'], ['Dogs', 'does not own the {v}'], ['Hull colours', 'does not own the {v} houseboat']],
    pos: 'is moored in berth {p}', npos: 'is not moored in berth {p}',
    rel: { adj: 'is moored in a berth next to', notadj: 'is not moored in a berth next to', left: 'is moored somewhere to the left of', immleft: 'is moored immediately to the left of', end: 'is moored in one of the two end berths' },
    dist: /^The berth numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  trucks: { geo: 'line', cats: ['Owners', 'Foods', 'Colours'],
    subj: [['Owners', "{v}'s truck"], ['Foods', 'the truck selling {v}'], ['Colours', 'the {v} truck']],
    pred: [['Owners', 'belongs to {v}'], ['Foods', 'sells {v}'], ['Colours', 'is painted {v}']],
    npred: [['Owners', 'does not belong to {v}'], ['Foods', 'does not sell {v}'], ['Colours', 'is not painted {v}']],
    pos: 'is parked in bay {p}', npos: 'is not parked in bay {p}',
    rel: { adj: 'is parked in a bay next to', notadj: 'is not parked in a bay next to', left: 'is parked somewhere to the left of', immleft: 'is parked immediately to the left of', end: 'is parked in one of the two end bays' },
    dist: /^The bay numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  floors: { geo: 'line', cats: ['Residents', 'Instruments', 'Plants'],
    subj: [['Residents', '{v}'], ['Instruments', 'the {v} player'], ['Plants', 'the resident with the {v}']],
    pred: [['Residents', 'is {v}'], ['Instruments', 'plays the {v}'], ['Plants', 'keeps the {v}']],
    npred: [['Residents', 'is not {v}'], ['Instruments', 'does not play the {v}'], ['Plants', 'does not keep the {v}']],
    pos: 'lives on floor {p}', npos: 'does not live on floor {p}',
    rel: { adj: 'lives on a floor directly above or directly below', notadj: 'does not live on a floor directly above or directly below', left: 'lives on some floor below', immleft: 'lives on the floor directly below', end: 'lives on either the top floor or the bottom floor' },
    dist: /^The floor numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  talks: { geo: 'line', cats: ['Speakers', 'Topics', 'Cities', 'Drinks'], labels: ['9:00', '10:00', '11:00', '12:00', '13:00'],
    subj: [['Speakers', '{v}'], ['Topics', 'the speaker on {v}'], ['Cities', 'the speaker from {v}'], ['Drinks', 'the speaker who ordered {v}']],
    pred: [['Speakers', 'is {v}'], ['Topics', 'talks about {v}'], ['Cities', 'is from {v}'], ['Drinks', 'ordered {v}']],
    npred: [['Speakers', 'is not {v}'], ['Topics', 'does not talk about {v}'], ['Cities', 'is not from {v}'], ['Drinks', 'did not order {v}']],
    pos: 'speaks at {p}', npos: 'does not speak at {p}',
    rel: { adj: 'speaks in a slot directly before or directly after', notadj: 'does not speak in a slot directly before or directly after', left: 'speaks at some point earlier than', immleft: 'speaks in the slot directly before', end: 'speaks either first (9:00) or last (13:00)' },
    dist: /^(.+) and (.+) speak exactly (\d+) hours apart$/ },
  train: { geo: 'line', cats: ['Passengers', 'Books (poetry, mystery, history, sci-fi, or a cookbook)', 'Destinations', 'Snacks'],
    subj: [['Passengers', '{v}'], ['Books (poetry, mystery, history, sci-fi, or a cookbook)', (v) => v === 'cookbook' ? 'the passenger reading a cookbook' : `the passenger reading ${v}`], ['Destinations', 'the passenger going to {v}'], ['Snacks', 'the passenger eating {v}']],
    pred: [['Passengers', 'is {v}'], ['Books (poetry, mystery, history, sci-fi, or a cookbook)', (v) => v === 'cookbook' ? 'is reading a cookbook' : `is reading ${v}`], ['Destinations', 'is going to {v}'], ['Snacks', 'is eating {v}']],
    npred: [['Passengers', 'is not {v}'], ['Books (poetry, mystery, history, sci-fi, or a cookbook)', (v) => v === 'cookbook' ? 'is not reading a cookbook' : `is not reading ${v}`], ['Destinations', 'is not going to {v}'], ['Snacks', 'is not eating {v}']],
    pos: 'is in carriage {p}', npos: 'is not in carriage {p}',
    rel: { adj: 'is in a carriage next to the carriage of', notadj: 'is not in a carriage next to the carriage of', left: 'is in a carriage nearer the front than', immleft: 'is in the carriage directly in front of the carriage of', end: 'is in the first or the last carriage' },
    dist: /^The carriage numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  gardeners: { geo: 'line', cats: ['Gardeners', 'Crops', 'Tools', 'Ages (in years)'], numeric: 'Ages (in years)',
    subj: [['Gardeners', '{v}'], ['Crops', 'the gardener growing {v}'], ['Tools', 'the gardener with the {v}'], ['Ages (in years)', 'the {v}-year-old']],
    pred: [['Gardeners', 'is {v}'], ['Crops', 'grows {v}'], ['Tools', 'has the {v}'], ['Ages (in years)', 'is {v} years old']],
    npred: [['Gardeners', 'is not {v}'], ['Crops', 'does not grow {v}'], ['Tools', 'does not have the {v}'], ['Ages (in years)', 'is not {v} years old']],
    pos: 'works plot {p}', npos: 'does not work plot {p}',
    rel: { adj: 'works a plot next to the plot of', notadj: 'does not work a plot next to the plot of', left: 'works a plot somewhere to the left of the plot of', immleft: 'works the plot immediately to the left of the plot of', end: 'works one of the two end plots' },
    numgt: /^(.+) is older than (.+)$/, numdiff: /^(.+) is exactly (\d+) years older than (.+)$/,
    dist: /^The plot numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  table: { geo: 'circle', cats: ['Guests', 'Desserts', 'Hat colours'],
    subj: [['Guests', '{v}'], ['Desserts', 'the guest who ordered {v}'], ['Hat colours', 'the guest in the {v} hat']],
    pred: [['Guests', 'is {v}'], ['Desserts', 'ordered {v}'], ['Hat colours', 'wears the {v} hat']],
    npred: [['Guests', 'is not {v}'], ['Desserts', 'did not order {v}'], ['Hat colours', 'does not wear the {v} hat']],
    pos: 'sits in seat {p}', npos: 'does not sit in seat {p}',
    rel: { adj: 'sits next to', notadj: 'does not sit next to', cw: 'sits immediately clockwise from', opp: 'sits directly opposite' } },
  lockers: { geo: 'grid:2:3', cats: ['Owners', 'Sports (fencer, rower, squash player, archer, judoka, curler)', 'Padlock colours'],
    labels: ['top-left', 'top-middle', 'top-right', 'bottom-left', 'bottom-middle', 'bottom-right'],
    subj: [['Owners', "{v}'s locker"], ['Sports (fencer, rower, squash player, archer, judoka, curler)', (v) => ({ fencing: "the fencer's locker", rowing: "the rower's locker", squash: 'the locker of the squash player', archery: "the archer's locker", judo: "the judoka's locker", curling: "the curler's locker" })[v]], ['Padlock colours', 'the locker with the {v} padlock']],
    pred: [['Owners', 'belongs to {v}'], ['Sports (fencer, rower, squash player, archer, judoka, curler)', (v) => ({ fencing: 'belongs to the fencer', rowing: 'belongs to the rower', squash: 'belongs to the squash player', archery: 'belongs to the archer', judo: 'belongs to the judoka', curling: 'belongs to the curler' })[v]], ['Padlock colours', 'has the {v} padlock']],
    npred: [['Owners', 'does not belong to {v}'], ['Sports (fencer, rower, squash player, archer, judoka, curler)', (v) => ({ fencing: 'does not belong to the fencer', rowing: 'does not belong to the rower', squash: 'does not belong to the squash player', archery: 'does not belong to the archer', judo: 'does not belong to the judoka', curling: 'does not belong to the curler' })[v]], ['Padlock colours', 'does not have the {v} padlock']],
    pos: 'is the {p} locker', npos: 'is not the {p} locker',
    rel: { adj: 'shares a side with', notadj: 'does not share a side with' },
    above: /^(.+) is directly above (.+)$/, gleft: /^(.+) is immediately to the left of (.+), in the same row$/ },
  gallery: { geo: 'line', cats: ['Artists', 'Subjects', 'Frames'],
    subj: [['Artists', "{v}'s painting"], ['Subjects', 'the painting of {v}'], ['Frames', 'the painting in the {v} frame']],
    pred: [['Artists', 'is by {v}'], ['Subjects', 'shows {v}'], ['Frames', 'has the {v} frame']],
    npred: [['Artists', 'is not by {v}'], ['Subjects', 'does not show {v}'], ['Frames', 'does not have the {v} frame']],
    pos: 'hangs on hook {p}', npos: 'does not hang on hook {p}',
    rel: { adj: 'hangs next to', notadj: 'does not hang next to', left: 'hangs somewhere to the left of', immleft: 'hangs immediately to the left of', end: 'hangs at one of the two ends of the row' },
    dist: /^The hook numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  lanes: { geo: 'line', cats: ['Runners', 'Countries', 'Shoe colours', 'Clubs'],
    subj: [['Runners', '{v}'], ['Countries', 'the runner from {v}'], ['Shoe colours', 'the runner in {v} shoes'], ['Clubs', 'the {v} runner']],
    pred: [['Runners', 'is {v}'], ['Countries', 'is from {v}'], ['Shoe colours', 'wears {v} shoes'], ['Clubs', 'runs for the {v}']],
    npred: [['Runners', 'is not {v}'], ['Countries', 'is not from {v}'], ['Shoe colours', 'does not wear {v} shoes'], ['Clubs', 'does not run for the {v}']],
    pos: 'is in lane {p}', npos: 'is not in lane {p}',
    rel: { adj: 'is in a lane next to', notadj: 'is not in a lane next to', left: 'is in a lower-numbered lane than', immleft: 'is in the lane numbered exactly one lower than', end: 'is in lane 1 or lane 6' },
    dist: /^The lane numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  lab: { geo: 'line', cats: ['Scientists', 'Animals', 'Projects', 'Years at the institute'], numeric: 'Years at the institute',
    subj: [['Scientists', '{v}'], ['Animals', 'the scientist who keeps the {v}'], ['Projects', 'the scientist working on {v}'], ['Years at the institute', 'the scientist with {v} years at the institute']],
    pred: [['Scientists', 'is {v}'], ['Animals', 'keeps the {v}'], ['Projects', 'works on {v}'], ['Years at the institute', 'has been at the institute for {v} years']],
    npred: [['Scientists', 'is not {v}'], ['Animals', 'does not keep the {v}'], ['Projects', 'does not work on {v}'], ['Years at the institute', 'has not been at the institute for {v} years']],
    pos: 'has office {p}', npos: 'does not have office {p}',
    rel: { adj: 'has an office next to the office of', notadj: 'does not have an office next to the office of', left: 'has a lower office number than', immleft: 'has the office numbered exactly one lower than the office of', end: 'has office 1 or office 6' },
    numgt: /^(.+) has been at the institute longer than (.+)$/, numdiff: /^(.+) has been at the institute exactly (\d+) years longer than (.+)$/,
    dist: /^The office numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  ferries: { geo: 'line', cats: ['Captains', 'Islands', 'Cargoes', 'Funnel colours'],
    subj: [['Captains', "{v}'s ferry"], ['Islands', 'the ferry bound for {v}'], ['Cargoes', 'the ferry carrying {v}'], ['Funnel colours', 'the ferry with the {v} funnel']],
    pred: [['Captains', 'is captained by {v}'], ['Islands', 'is bound for {v}'], ['Cargoes', 'carries {v}'], ['Funnel colours', 'has the {v} funnel']],
    npred: [['Captains', 'is not captained by {v}'], ['Islands', 'is not bound for {v}'], ['Cargoes', 'does not carry {v}'], ['Funnel colours', 'does not have the {v} funnel']],
    pos: 'is docked at pier {p}', npos: 'is not docked at pier {p}',
    rel: { adj: 'is docked at a pier next to', notadj: 'is not docked at a pier next to', left: 'is docked at a lower-numbered pier than', immleft: 'is docked at the pier numbered exactly one lower than', end: 'is docked at pier 1 or pier 6' },
    dist: /^The pier numbers of (.+) and (.+) differ by exactly (\d+)$/ },
  chess: { geo: 'circle', cats: ['Members', 'Favourite openings', 'Drinks', 'Towns'],
    subj: [['Members', '{v}'], ['Favourite openings', 'the {v} fan'], ['Drinks', 'the {v} drinker'], ['Towns', 'the member from {v}']],
    pred: [['Members', 'is {v}'], ['Favourite openings', 'favours the {v}'], ['Drinks', 'drinks {v}'], ['Towns', 'is from {v}']],
    npred: [['Members', 'is not {v}'], ['Favourite openings', 'does not favour the {v}'], ['Drinks', 'does not drink {v}'], ['Towns', 'is not from {v}']],
    pos: 'sits in seat {p}', npos: 'does not sit in seat {p}',
    rel: { adj: 'sits next to', notadj: 'does not sit next to', cw: 'sits immediately clockwise from', opp: 'sits directly opposite' } },
  cabins: { geo: 'grid:3:2', cats: ['Guests', 'Activities', 'Breakfasts', 'Door colours'],
    labels: ['north-west', 'north-east', 'middle-west', 'middle-east', 'south-west', 'south-east'],
    subj: [['Guests', "{v}'s cabin"], ['Activities', 'the cabin of the guest who chose {v}'], ['Breakfasts', 'the cabin that ordered {v}'], ['Door colours', 'the cabin with the {v} door']],
    pred: [['Guests', "is {v}'s"], ['Activities', 'belongs to the guest who chose {v}'], ['Breakfasts', 'ordered {v}'], ['Door colours', 'has the {v} door']],
    npred: [['Guests', "is not {v}'s"], ['Activities', 'does not belong to the guest who chose {v}'], ['Breakfasts', 'did not order {v}'], ['Door colours', 'does not have the {v} door']],
    pos: 'is the {p} cabin', npos: 'is not the {p} cabin',
    rel: { adj: 'shares a wall with', notadj: 'does not share a wall with' },
    above: /^(.+) is directly north of (.+)$/, gleft: /^(.+) is directly west of (.+), in the same row$/ },
};

function fill(t, v) { return typeof t === 'function' ? t(v) : t.replace('{v}', v); }
const lc = (s) => s[0].toLowerCase() + s.slice(1);

function verify(c) {
  const th = T[c.cfg];
  const p = c.prompt;
  const catBlock = p.split('Categories:\n')[1].split('\n\n')[0].split('\n');
  const cats = {};
  const order = [];
  for (const line of catBlock) {
    const m = line.match(/^- (.+?): (.+)$/);
    cats[m[1]] = m[2].split(', ');
    order.push(m[1]);
  }
  if (JSON.stringify(order) !== JSON.stringify(th.cats)) throw new Error('category labels mismatch ' + order);
  const N = cats[order[0]].length;
  const labels = th.labels || Array.from({ length: N }, (_, i) => String(i + 1));
  // noun phrase -> [cat, value]
  const subj = new Map(), pred = new Map(), npred = new Map();
  for (const [cat, t] of th.subj) for (const v of cats[cat]) subj.set(lc(fill(t, v)), [cat, v]);
  for (const [cat, t] of th.pred) for (const v of cats[cat]) pred.set(fill(t, v), [cat, v]);
  for (const [cat, t] of th.npred) for (const v of cats[cat]) npred.set(fill(t, v), [cat, v]);
  labels.forEach((lab, i) => { pred.set(th.pos.replace('{p}', lab), ['POS', i]); npred.set(th.npos.replace('{p}', lab), ['POS', i]); });
  const S = (s) => { const r = subj.get(lc(s.trim())); if (!r) throw new Error('unknown subject: ' + s); return r; };
  // geometry
  let adj, left, immleft, end, cw, opp, above, gleft, samerow, samecol;
  if (th.geo === 'line') {
    adj = (a, b) => Math.abs(a - b) === 1; left = (a, b) => a < b; immleft = (a, b) => a + 1 === b; end = (a) => a === 0 || a === N - 1;
  } else if (th.geo === 'circle') {
    adj = (a, b) => (a - b + N) % N === 1 || (b - a + N) % N === 1; cw = (y, x) => y === (x + 1) % N; opp = (a, b) => (a - b + N) % N === N / 2;
  } else {
    const [, R, C] = th.geo.split(':').map(Number);
    const rc = (x) => [Math.floor(x / C), x % C];
    adj = (a, b) => { const [r1, c1] = rc(a), [r2, c2] = rc(b); return Math.abs(r1 - r2) + Math.abs(c1 - c2) === 1; };
    above = (a, b) => { const [r1, c1] = rc(a), [r2, c2] = rc(b); return c1 === c2 && r1 + 1 === r2; };
    gleft = (a, b) => { const [r1, c1] = rc(a), [r2, c2] = rc(b); return r1 === r2 && c1 + 1 === c2; };
    samerow = (a, b) => rc(a)[0] === rc(b)[0]; samecol = (a, b) => rc(a)[1] === rc(b)[1];
  }
  // A world: pos[cat][value] = position
  const where = (w, it) => (it[0] === 'POS' ? it[1] : w[it[0]][it[1]]);
  const numOf = (w, it) => { const pp = where(w, it); const nc = th.numeric; return Number(Object.keys(w[nc]).find((v) => w[nc][v] === pp)); };
  const holds = (w, s, pr) => where(w, s) === where(w, pr); // subject s has predicate pr
  const clues = p.split('Clues:\n')[1].split('\n\n')[0].split('\n').map((l) => l.replace(/^\d+\. /, '').replace(/\.$/, ''));
  const fns = [];
  for (const cl of clues) {
    let m, f = null;
    const tryRel = () => {
      for (const [k, phrase] of Object.entries(th.rel)) {
        const i = cl.indexOf(' ' + phrase + ' ');
        if (i > 0 && !(k === 'adj' && cl.includes(' not '))) {
          const a = S(cl.slice(0, i)), b = S(cl.slice(i + phrase.length + 2));
          if (k === 'adj') return (w) => adj(where(w, a), where(w, b));
          if (k === 'notadj') return (w) => !adj(where(w, a), where(w, b));
          if (k === 'left') return (w) => left(where(w, a), where(w, b));
          if (k === 'immleft') return (w) => immleft(where(w, a), where(w, b));
          if (k === 'cw') return (w) => cw(where(w, a), where(w, b)); // "A sits immediately clockwise from B"
          if (k === 'opp') return (w) => opp(where(w, a), where(w, b));
        }
        if (k === 'end' && cl.endsWith(' ' + phrase)) { const a = S(cl.slice(0, cl.length - phrase.length - 1)); return (w) => end(where(w, a)); }
      }
      return null;
    };
    if ((m = cl.match(/^Exactly one of these two statements is true: (.+); (.+)$/))) {
      const parts = [m[1], m[2]].map(parseSimple);
      f = (w) => parts[0](w) !== parts[1](w);
    } else if ((m = cl.match(/^If (.+), then (.+)$/))) {
      const A = parseSimple(m[1]), B = parseSimple(m[2]);
      f = (w) => !A(w) || B(w);
    } else if ((m = cl.match(/^Of (.+) and (.+) \(two different \w+\), one (.+) and the other (.+)$/))) {
      const x = S(m[1]), y = S(m[2]);
      const pa = predOf(m[3]), pb = predOf(m[4]);
      f = (w) => where(w, x) !== where(w, y) && ((holds(w, x, pa) && holds(w, y, pb)) || (holds(w, y, pa) && holds(w, x, pb)));
    } else if (th.dist && (m = cl.match(th.dist))) {
      const a = S(m[1]), b = S(m[2]), d = Number(m[3]);
      f = (w) => Math.abs(where(w, a) - where(w, b)) === d;
    } else if (th.numdiff && (m = cl.match(th.numdiff))) {
      const a = S(m[1]), d = Number(m[2]), b = S(m[3]);
      f = (w) => numOf(w, a) - numOf(w, b) === d;
    } else if (th.numgt && (m = cl.match(th.numgt))) {
      const a = S(m[1]), b = S(m[2]);
      f = (w) => numOf(w, a) > numOf(w, b);
    } else if (th.above && (m = cl.match(th.above))) {
      const a = S(m[1]), b = S(m[2]); f = (w) => above(where(w, a), where(w, b));
    } else if (th.gleft && (m = cl.match(th.gleft))) {
      const a = S(m[1]), b = S(m[2]); f = (w) => gleft(where(w, a), where(w, b));
    } else if ((m = cl.match(/^(.+) and (.+) are in the same row$/)) && samerow) {
      const a = S(m[1]), b = S(m[2]); f = (w) => samerow(where(w, a), where(w, b));
    } else if ((m = cl.match(/^(.+) and (.+) are in the same column$/)) && samecol) {
      const a = S(m[1]), b = S(m[2]); f = (w) => samecol(where(w, a), where(w, b));
    } else if ((m = cl.match(/^(.+) and (.+) are in different rows$/)) && samerow) {
      const a = S(m[1]), b = S(m[2]); f = (w) => !samerow(where(w, a), where(w, b));
    } else {
      f = tryRel() || parseSimple(cl);
    }
    fns.push(f);
  }
  function predOf(s) { const r = pred.get(s.trim()); if (!r) throw new Error('unknown predicate: ' + s); return r; }
  function parseSimple(s) {
    // "<subject> <predicate>" or "<subject> <negated predicate>"
    s = s.trim();
    for (const [phrase, it] of npred) if (s.endsWith(' ' + phrase)) { const subjTxt = s.slice(0, s.length - phrase.length - 1); if (subj.has(lc(subjTxt))) { const a = S(subjTxt); return (w) => !holds(w, a, it); } }
    for (const [phrase, it] of pred) if (s.endsWith(' ' + phrase)) { const subjTxt = s.slice(0, s.length - phrase.length - 1); if (subj.has(lc(subjTxt))) { const a = S(subjTxt); return (w) => holds(w, a, it); } }
    throw new Error('unparsed clue: ' + s);
  }
  // brute force: permutations per category
  const perms = (arr) => arr.length <= 1 ? [arr] : arr.flatMap((x, i) => perms([...arr.slice(0, i), ...arr.slice(i + 1)]).map((r) => [x, ...r]));
  const P = perms([...Array(N).keys()]);
  const sols = [];
  const w = {};
  const catNames = order;
  const rec = (k) => {
    if (k === catNames.length) { if (fns.every((f) => f(w))) sols.push(JSON.parse(JSON.stringify(w))); return; }
    const cat = catNames[k];
    for (const perm of P) {
      w[cat] = {}; cats[cat].forEach((v, i) => { w[cat][v] = perm[i]; });
      // prune: evaluate clues that only involve assigned cats (cheap approach: try/catch on undefined)
      let ok = true;
      for (const f of fns) { let r; try { r = f(w); } catch { continue; } if (r === false && k === catNames.length - 1) { ok = false; break; } }
      if (ok) rec(k + 1);
      if (sols.length > 1) return;
    }
    delete w[cat];
  };
  // simple but slow for 6x4 (720^4); use smarter: fix first category as identity? No: positions matter. Use clue-wise pruning below.
  return { fns, cats, catNames, N, labels };
}

// Faster exhaustive search: assign categories one by one; after each assignment evaluate every clue whose
// referenced categories are all assigned (detected by a dry run that records accessed categories).
function solve(c) {
  const ctx = verify(c);
  const { fns, cats, catNames, N } = ctx;
  const perms = (arr) => arr.length <= 1 ? [arr] : arr.flatMap((x, i) => perms([...arr.slice(0, i), ...arr.slice(i + 1)]).map((r) => [x, ...r]));
  const P = perms([...Array(N).keys()]);
  // detect which categories each clue reads, using a Proxy on a full dummy world
  const deps = fns.map((f) => {
    const used = new Set();
    const dummy = {};
    for (const cat of catNames) { dummy[cat] = {}; cats[cat].forEach((v, i) => { dummy[cat][v] = i; }); }
    const prox = new Proxy(dummy, { get(t, k) { used.add(k); return t[k]; } });
    f(prox);
    // numeric lookups use Object.keys(w[nc]) -> also tracked via get
    return used;
  });
  // second pass with a different dummy to catch branch-dependent reads (short-circuit): run several random worlds
  for (let rep = 0; rep < 200; rep++) {
    const dummy = {};
    for (const cat of catNames) { const perm = P[Math.floor(Math.random() * P.length)]; dummy[cat] = {}; cats[cat].forEach((v, i) => { dummy[cat][v] = perm[i]; }); }
    fns.forEach((f, i) => { const prox = new Proxy(dummy, { get(t, k) { deps[i].add(k); return t[k]; } }); f(prox); });
  }
  // greedy category order: next category = the one completing the most clues (ties: most clues touching it)
  const orderC = [];
  const remaining = new Set(catNames);
  while (remaining.size) {
    let best = null, bestScore = -1;
    for (const cand of remaining) {
      const assigned = new Set([...orderC, cand]);
      const complete = deps.filter((d) => [...d].every((x) => assigned.has(x)) && d.has(cand)).length;
      const touch = deps.filter((d) => d.has(cand)).length;
      const score = complete * 1000 + touch;
      if (score > bestScore) { bestScore = score; best = cand; }
    }
    orderC.push(best); remaining.delete(best);
  }
  catNames.splice(0, catNames.length, ...orderC);
  const levelChecks = catNames.map((_, k) => fns.filter((f, i) => [...deps[i]].every((d) => catNames.indexOf(d) <= k) && [...deps[i]].some((d) => catNames.indexOf(d) === k)));
  const noDep = fns.filter((f, i) => deps[i].size === 0);
  if (noDep.length) throw new Error('clue with no dependency');
  const sols = [];
  const w = {};
  const rec = (k) => {
    if (sols.length > 1) return;
    if (k === catNames.length) { if (fns.every((f) => f(w))) sols.push(JSON.parse(JSON.stringify(w))); return; }
    const cat = catNames[k];
    for (const perm of P) {
      w[cat] = {}; cats[cat].forEach((v, i) => { w[cat][v] = perm[i]; });
      if (levelChecks[k].every((f) => f(w))) rec(k + 1);
      if (sols.length > 1) break;
    }
    delete w[cat];
  };
  rec(0);
  return { sols, ctx };
}

const cases = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
let bad = 0;
for (const c of cases) {
  const t0 = Date.now();
  const { sols, ctx } = solve(c);
  let ok = sols.length === 1;
  if (ok) {
    // reconstruct answer: which value of asked category is at each position, compare with expected
    const exp = c.expected[0].split(', ');
    const askCat = ctx.catNames.find((cat) => exp.every((v) => ctx.cats[cat].includes(v)));
    const ans = [];
    for (let p = 0; p < ctx.N; p++) ans.push(Object.keys(sols[0][askCat]).find((v) => sols[0][askCat][v] === p));
    ok = ans.join(', ') === c.expected[0];
    console.log(c.id, ok ? 'OK' : 'MISMATCH', ans.join(', '), `${Date.now() - t0}ms`);
  } else console.log(c.id, 'NOT UNIQUE/NO SOLUTION', sols.length);
  if (!ok) bad++;
}
process.exit(bad ? 1 : 0);
