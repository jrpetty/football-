'use strict';

class BookingError extends Error {
  constructor(problems) {
    super(`Cannot book: ${problems.join('; ')}`);
    this.name = 'BookingError';
    this.problems = problems;
  }
}

module.exports = { BookingError };
