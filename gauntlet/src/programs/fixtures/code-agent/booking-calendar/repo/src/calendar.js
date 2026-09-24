'use strict';

const { Rooms } = require('./rooms');
const { BookingBook } = require('./bookings');
const { freeSlots } = require('./availability');

function createCalendar() {
  const rooms = new Rooms();
  const book = new BookingBook(rooms);
  return {
    rooms,
    book: (input) => book.book(input),
    bookRecurring: (template, weeks) => book.bookRecurring(template, weeks),
    cancel: (id) => book.cancel(id),
    list: (roomId, date) => book.list(roomId, date),
    freeSlots: (roomId, date, minMinutes) => freeSlots(book, roomId, date, minMinutes),
  };
}

module.exports = { createCalendar };
