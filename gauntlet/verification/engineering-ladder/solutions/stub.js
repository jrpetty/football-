// Spread solution "do nothing": replies "OK" to every command (the stub gate scores it 0).
function runStore(commands) {
  return commands.map(() => 'OK');
}
