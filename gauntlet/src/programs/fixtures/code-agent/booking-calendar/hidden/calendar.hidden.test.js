'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createCalendar, overlaps, BookingError } = require('../src');

function calendar() {
  const cal = createCalendar();
  cal.rooms.add({ id: 'oak', name: 'Oak Room', capacity: 6, open: '08:00', close: '18:00' });
  cal.rooms.add({ id: 'pine', name: 'Pine Room', capacity: 12, open: '07:00', close: '21:00' });
  return cal;
}

const D = '2024-11-05';

test('overlaps uses half-open intervals', () => {
  assert.equal(overlaps({ start: 600, end: 660 }, { start: 660, end: 720 }), false);
  assert.equal(overlaps({ start: 660, end: 720 }, { start: 600, end: 660 }), false);
  assert.equal(overlaps({ start: 600, end: 660 }, { start: 659, end: 720 }), true);
  assert.equal(overlaps({ start: 600, end: 700 }, { start: 620, end: 640 }), true);
  assert.equal(overlaps({ start: 600, end: 660 }, { start: 600, end: 660 }), true);
});

test('a day of back-to-back meetings', () => {
  const cal = calendar();
  for (const start of ['08:00', '09:00', '10:00', '11:00']) cal.book({ room: 'oak', date: D, start, duration: '1h', title: start });
  assert.deepEqual(cal.list('oak', D).map((b) => `${b.start}-${b.end}`), ['08:00-09:00', '09:00-10:00', '10:00-11:00', '11:00-12:00']);
  assert.deepEqual(cal.freeSlots('oak', D), [{ start: '12:00', end: '18:00' }]);
});

test('booking up to closing time and from opening time', () => {
  const cal = calendar();
  assert.equal(cal.book({ room: 'oak', date: D, start: '17:00', duration: 60 }).end, '18:00');
  assert.equal(cal.book({ room: 'oak', date: D, start: '08:00', duration: 30 }).start, '08:00');
  assert.throws(() => cal.book({ room: 'oak', date: D, start: '17:30', duration: 60 }), /only open 08:00-18:00/);
  assert.throws(() => cal.book({ room: 'oak', date: D, start: '07:45', duration: 30 }), BookingError);
});

test('the default duration is 60 minutes', () => {
  const cal = calendar();
  assert.equal(cal.book({ room: 'pine', date: D, start: '07:00' }).end, '08:00');
  assert.equal(cal.book({ room: 'pine', date: D, start: '20:00' }).end, '21:00');
  assert.throws(() => cal.book({ room: 'pine', date: D, start: '20:30' }), /only open 07:00-21:00/);
  assert.throws(() => cal.book({ room: 'pine', date: D, start: '07:30' }), /overlaps/);
  assert.deepEqual(cal.freeSlots('pine', D, 60), [{ start: '08:00', end: '20:00' }]);
});

test('book never touches its input', () => {
  const cal = calendar();
  const input = { room: 'oak', date: D, start: '10:00', duration: 30, title: 'Sync' };
  const snapshot = JSON.parse(JSON.stringify(input));
  const b = cal.book(input);
  assert.deepEqual(input, snapshot);
  assert.notEqual(b, input);
  input.start = '15:00';
  input.date = '2024-11-06';
  assert.equal(cal.list('oak', D)[0].start, '10:00');
  assert.equal(cal.list('oak', '2024-11-06').length, 0);
});

test('reusing one input object for two bookings', () => {
  const cal = calendar();
  const input = { room: 'oak', date: D, start: '10:00', duration: 30, title: 'Slot' };
  const a = cal.book(input);
  input.start = '11:00';
  const b = cal.book(input);
  assert.notEqual(a.id, b.id);
  assert.deepEqual(cal.list('oak', D).map((x) => x.start), ['10:00', '11:00']);
});

test('recurring bookings are separate bookings on each date', () => {
  const cal = calendar();
  const template = { room: 'oak', date: '2024-10-07', start: '09:00', duration: 15, title: 'Stand-up' };
  const created = cal.bookRecurring(template, 4);
  assert.deepEqual(created.map((b) => b.date), ['2024-10-07', '2024-10-14', '2024-10-21', '2024-10-28']);
  assert.equal(new Set(created.map((b) => b.id)).size, 4);
  assert.deepEqual(template, { room: 'oak', date: '2024-10-07', start: '09:00', duration: 15, title: 'Stand-up' });
});

test('recurring bookings block later conflicts in every week', () => {
  const cal = calendar();
  cal.bookRecurring({ room: 'oak', date: '2024-10-07', start: '09:00', duration: 30, title: 'Stand-up' }, 3);
  assert.throws(() => cal.book({ room: 'oak', date: '2024-10-07', start: '09:15', duration: 30 }), /overlaps "Stand-up"/);
  assert.throws(() => cal.book({ room: 'oak', date: '2024-10-14', start: '09:00', duration: 30 }), /overlaps/);
  assert.deepEqual(cal.freeSlots('oak', '2024-10-14'), [
    { start: '08:00', end: '09:00' },
    { start: '09:30', end: '18:00' },
  ]);
  assert.equal(cal.book({ room: 'oak', date: '2024-10-28', start: '09:00', duration: 30 }).date, '2024-10-28');
});

test('recurring bookings are all-or-nothing', () => {
  const cal = calendar();
  cal.book({ room: 'oak', date: '2024-10-21', start: '09:10', duration: 20, title: 'Interview' });
  assert.throws(() => cal.bookRecurring({ room: 'oak', date: '2024-10-07', start: '09:00', duration: 30 }, 3), /2024-10-21/);
  assert.equal(cal.list('oak', '2024-10-07').length, 0);
  assert.equal(cal.list('oak', '2024-10-14').length, 0);
});

test('cancelling frees the slot', () => {
  const cal = calendar();
  const b = cal.book({ room: 'oak', date: D, start: '13:00', duration: '2h' });
  cal.cancel(b.id);
  assert.deepEqual(cal.freeSlots('oak', D), [{ start: '08:00', end: '18:00' }]);
});

test('free slots ignore gaps shorter than the minimum', () => {
  const cal = calendar();
  cal.book({ room: 'oak', date: D, start: '08:20', duration: '1h' });
  cal.book({ room: 'oak', date: D, start: '09:40', duration: '8h' });
  assert.deepEqual(cal.freeSlots('oak', D, 30), []);
  assert.deepEqual(cal.freeSlots('oak', D, 15), [
    { start: '08:00', end: '08:20' },
    { start: '09:20', end: '09:40' },
    { start: '17:40', end: '18:00' },
  ]);
});
