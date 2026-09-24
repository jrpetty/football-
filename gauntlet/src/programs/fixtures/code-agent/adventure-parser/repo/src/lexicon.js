'use strict';

/** Every verb the parser understands, mapped to its canonical form. */
const VERB_SYNONYMS = {
  take: 'take',
  get: 'take',
  grab: 'take',
  pick: 'take',
  drop: 'drop',
  discard: 'drop',
  look: 'look',
  l: 'look',
  examine: 'examine',
  x: 'examine',
  inspect: 'examine',
  read: 'read',
  open: 'open',
  close: 'close',
  shut: 'close',
  put: 'put',
  place: 'put',
  insert: 'put',
  unlock: 'unlock',
  use: 'use',
  go: 'go',
  walk: 'go',
  run: 'go',
  inventory: 'inventory',
  i: 'inventory',
};

const ARTICLES = ['the', 'a', 'an', 'some'];

const PREPOSITIONS = ['in', 'into', 'on', 'onto', 'with', 'under', 'at', 'to'];

const DIRECTIONS = {
  n: 'north',
  s: 'south',
  e: 'east',
  w: 'west',
  ne: 'northeast',
  nw: 'northwest',
  se: 'southeast',
  sw: 'southwest',
  u: 'up',
  d: 'down',
  north: 'north',
  south: 'south',
  east: 'east',
  west: 'west',
  northeast: 'northeast',
  northwest: 'northwest',
  southeast: 'southeast',
  southwest: 'southwest',
  up: 'up',
  down: 'down',
};

/** Verbs that never take an object. */
const INTRANSITIVE = ['look', 'inventory'];

module.exports = { VERB_SYNONYMS, ARTICLES, PREPOSITIONS, DIRECTIONS, INTRANSITIVE };
