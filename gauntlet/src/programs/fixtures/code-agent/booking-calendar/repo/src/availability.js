'use strict';

const { formatTime } = require('./time');
const { subtract } = require('./interval');

/** Free intervals of at least `minMinutes` within the room's opening hours. */
function freeSlots(book, roomId, date, minMinutes = 30) {
  const room = book.rooms.get(roomId);
  const busy = book.list(roomId, date).map((b) => ({ start: b.startMin, end: b.endMin }));
  return subtract({ start: room.open, end: room.close }, busy)
    .filter((iv) => iv.end - iv.start >= minMinutes)
    .map((iv) => ({ start: formatTime(iv.start), end: formatTime(iv.end) }));
}

module.exports = { freeSlots };
