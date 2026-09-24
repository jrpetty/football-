'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createCalendar, BookingError } = require('../src');

function calendar() {
  const cal = createCalendar();
  cal.rooms.add({ id: 'oak', name: 'Oak Room', capacity: 6, open: '08:00', close: '18:00' });
  return cal;
}

test('book a room', () => {
  const cal = calendar();
  const b = cal.book({ room: 'oak', date: '2024-10-01', start: '10:00', duration: '1h30m', title: 'Planning', attendees: 4 });
  assert.equal(b.start, '10:00');
  assert.equal(b.end, '11:30');
  assert.equal(cal.list('oak', '2024-10-01').length, 1);
});

test('overlapping bookings are rejected', () => {
  const cal = calendar();
  cal.book({ room: 'oak', date: '2024-10-01', start: '10:00', duration: 60, title: 'A' });
  assert.throws(() => cal.book({ room: 'oak', date: '2024-10-01', start: '10:30', duration: 60, title: 'B' }), BookingError);
});

test('back-to-back bookings are allowed', () => {
  const cal = calendar();
  cal.book({ room: 'oak', date: '2024-10-01', start: '10:00', duration: 60, title: 'A' });
  const b = cal.book({ room: 'oak', date: '2024-10-01', start: '11:00', duration: 60, title: 'B' });
  assert.equal(b.end, '12:00');
});

test('a booking without a duration lasts an hour', () => {
  const cal = calendar();
  const b = cal.book({ room: 'oak', date: '2024-10-02', start: '14:00', title: 'Chat' });
  assert.equal(b.end, '15:00');
});

test('too many attendees', () => {
  const cal = calendar();
  assert.throws(() => cal.book({ room: 'oak', date: '2024-10-01', start: '09:00', duration: 30, attendees: 9 }), /at most 6 people/);
});
