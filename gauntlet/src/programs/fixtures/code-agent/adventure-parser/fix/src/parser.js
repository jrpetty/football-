'use strict';

const { VERB_SYNONYMS, PREPOSITIONS, DIRECTIONS, INTRANSITIVE } = require('./lexicon');
const { tokenize } = require('./tokenizer');
const { findObject, describeChoices } = require('./matcher');

const EMPTY_COMMAND = {
  verb: null,
  object: null,
  target: null,
  preposition: null,
  direction: null,
  error: null,
};

function fail(command, message) {
  command.error = message;
  return command;
}

/** Resolve a run of words to an object id, or explain why not. */
function resolve(words, scope) {
  const found = findObject(words, scope);
  if (!found) return { error: `You can't see any ${words.join(' ')} here.` };
  if (found.ambiguous) return { error: `Which do you mean: the ${describeChoices(found.ambiguous)}?` };
  return { id: found.object.id };
}

/** Parse one line of player input. See README.md for the command object. */
function parse(input, scope = []) {
  const command = { ...EMPTY_COMMAND };
  const words = tokenize(input);
  if (words.length === 0) return fail(command, 'Say something.');

  let [first, ...rest] = words;

  // A bare direction: "n", "southwest".
  if (DIRECTIONS[first] && rest.length === 0) {
    command.verb = 'go';
    command.direction = DIRECTIONS[first];
    return command;
  }

  const verb = VERB_SYNONYMS[first];
  if (!verb) return fail(command, `I don't know the verb "${first}".`);
  command.verb = verb;

  if (first === 'pick' && rest[0] === 'up') rest = rest.slice(1);
  if (verb === 'look' && rest[0] === 'at') {
    command.verb = 'examine';
    rest = rest.slice(1);
  }

  if (command.verb === 'go') {
    if (rest.length !== 1 || !DIRECTIONS[rest[0]]) return fail(command, 'Go where?');
    command.direction = DIRECTIONS[rest[0]];
    return command;
  }
  if (INTRANSITIVE.includes(command.verb)) return command;

  const prepIndex = rest.findIndex((word) => PREPOSITIONS.includes(word));
  const objectWords = prepIndex === -1 ? rest : rest.slice(0, prepIndex);
  const targetWords = prepIndex === -1 ? [] : rest.slice(prepIndex + 1);

  if (objectWords.length === 0) return fail(command, `What do you want to ${command.verb}?`);
  const object = resolve(objectWords, scope);
  if (object.error) return fail(command, object.error);
  command.object = object.id;

  if (prepIndex !== -1) {
    if (targetWords.length === 0) return fail(command, `${rest[prepIndex]} what?`);
    const target = resolve(targetWords, scope);
    if (target.error) return fail(command, target.error);
    command.target = target.id;
    command.preposition = rest[prepIndex];
  }
  return command;
}

module.exports = { parse, EMPTY_COMMAND };
