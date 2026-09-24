'use strict';

/** In-memory audit trail (shipped to the compliance log in production). */
class AuditLog {
  constructor({ limit = 10_000 } = {}) {
    this.events = [];
    this.limit = limit;
  }

  record(type, data) {
    this.events.push({ type, data });
    // Keep memory bounded; the oldest events have already been shipped.
    if (this.events.length > this.limit) this.events.splice(0, this.events.length - this.limit);
  }

  /** Events of one type, newest first. */
  latest(type, n = 10) {
    return this.events.filter((e) => e.type === type).slice(-n).reverse();
  }

  count(type) {
    return this.events.filter((e) => e.type === type).length;
  }
}

module.exports = { AuditLog };
