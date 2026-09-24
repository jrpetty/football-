'use strict';

const { createEngine } = require('../src');

const HELP_CENTRE = [
  { id: 'kb-1', title: 'Resetting passwords', body: "Don't panic: you can reset your password yourself from the login page." },
  { id: 'kb-2', title: 'Two-factor authentication', body: 'Set up two-factor authentication with an app or with text messages.' },
  { id: 'kb-3', title: 'Deleting your account', body: 'Account deletion is permanent. Download your data first.' },
  { id: 'kb-4', title: 'Tower of London tours', body: 'The Tower of London is closed on Mondays.' },
  { id: 'kb-5', title: 'Tower Bridge', body: 'Tower Bridge and London Bridge are different bridges.' },
  { id: 'kb-6', title: 'Passwords and passkeys', body: 'Passkeys replace passwords. Your password still works as a backup.' },
];

function helpCentre() {
  const engine = createEngine();
  for (const doc of HELP_CENTRE) engine.add(doc);
  return engine;
}

module.exports = { HELP_CENTRE, helpCentre };
