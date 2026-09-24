'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createCalendar } = require('../src');

function calendar() {
  const cal = createCalendar();
  cal.rooms.add({ id: 'oak', name: 'Oak Room', capacity: 6, open: '08:00', close: '18:00' });
  return cal;
}

test('free slots around a booking', () => {
  const cal = calendar();
  cal.book({ room: 'oak', date: '2024-10-01', start: '10:00', duration: '1h30m', title: 'Planning' });
  assert.deepEqual(cal.freeSlots('oak', '2024-10-01'), [
    { start: '08:00', end: '10:00' },
    { start: '11:30', end: '18:00' },
  ]);
});

test('a weekly stand-up appears in every week', () => {
  const cal = calendar();
  cal.bookRecurring({ room: 'oak', date: '2024-10-07', start: '09:00', duration: 15, title: 'Stand-up' }, 3);
  for (const date of ['2024-10-07', '2024-10-14', '2024-10-21']) {
    assert.equal(cal.list('oak', date).length, 1, date);
  }
});
