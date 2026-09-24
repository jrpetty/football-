'use strict';

const { parseTime, formatTime, parseDuration } = require('./time');
const { overlaps } = require('./interval');
const { toUtc, weeklyDates } = require('./dates');
const { BookingError } = require('./errors');

const DEFAULT_DURATION = 60;

class BookingBook {
  constructor(rooms) {
    this.rooms = rooms;
    this.bookings = [];
    this.nextId = 1;
  }

  /** Start and end of a booking request in minutes since midnight. */
  span(input) {
    const start = parseTime(input.start);
    const duration = input.duration === undefined ? undefined : parseDuration(input.duration);
    const end = start + (duration ?? DEFAULT_DURATION);
    return { start, end };
  }

  /** Every reason why `input` cannot be booked (empty when it can). */
  problems(input) {
    const problems = [];
    const room = this.rooms.get(input.room);
    toUtc(input.date);
    const { start, end } = this.span(input);
    if (end <= start) problems.push('the booking must end after it starts');
    if (start < room.open || end > room.close) {
      problems.push(`the room is only open ${formatTime(room.open)}-${formatTime(room.close)}`);
    }
    if ((input.attendees ?? 1) > room.capacity) problems.push(`the room holds at most ${room.capacity} people`);
    for (const other of this.list(input.room, input.date)) {
      if (overlaps({ start, end }, { start: other.startMin, end: other.endMin })) {
        problems.push(`it overlaps "${other.title}" (${other.start}-${other.end})`);
      }
    }
    return problems;
  }

  book(input) {
    const problems = this.problems(input);
    if (problems.length) throw new BookingError(problems);
    const { start, end } = this.span(input);
    const booking = Object.assign({ ...input }, {
      id: `b${this.nextId++}`,
      title: input.title ?? 'Meeting',
      attendees: input.attendees ?? 1,
      start: formatTime(start),
      end: formatTime(end),
      startMin: start,
      endMin: end,
    });
    this.bookings.push(booking);
    return booking;
  }

  bookRecurring(template, weeks) {
    const dates = weeklyDates(template.date, weeks);
    const problems = [];
    for (const date of dates) {
      for (const p of this.problems({ ...template, date })) problems.push(`${date}: ${p}`);
    }
    if (problems.length) throw new BookingError(problems);
    return dates.map((date) => this.book({ ...template, date }));
  }

  cancel(id) {
    const index = this.bookings.findIndex((b) => b.id === id);
    if (index === -1) throw new Error(`Unknown booking ${id}`);
    return this.bookings.splice(index, 1)[0];
  }

  /** Bookings of a room on a date, in start order. */
  list(roomId, date) {
    return this.bookings.filter((b) => b.room === roomId && b.date === date).sort((a, b) => a.startMin - b.startMin);
  }
}

module.exports = { BookingBook, DEFAULT_DURATION };
