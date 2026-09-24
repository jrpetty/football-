'use strict';

/**
 * Append-only journal of every balance change.
 * Entry: { seq, date, accountId, amount, kind, ref, memo }
 */
class Journal {
  constructor() {
    this.entries = [];
    this.counters = new Map();
  }

  nextId(prefix) {
    const n = (this.counters.get(prefix) ?? 0) + 1;
    this.counters.set(prefix, n);
    return `${prefix}${String(n).padStart(5, '0')}`;
  }

  append(entry) {
    const e = Object.freeze({ seq: this.entries.length + 1, memo: '', ...entry });
    this.entries.push(e);
    return e;
  }

  /** Entries for one account, in the order they were written. */
  forAccount(accountId) {
    return this.entries.filter((e) => e.accountId === accountId);
  }

  /** Count and net amount of each kind of entry for an account (for the monthly ops report). */
  summary(accountId) {
    const out = {};
    for (const e of this.forAccount(accountId)) {
      const s = out[e.kind] ?? (out[e.kind] = { count: 0, net: 0 });
      s.count += 1;
      s.net += e.amount;
    }
    return out;
  }

  /** Entries written after sequence number `seq` (for the replication feed). */
  since(seq) {
    return this.entries.slice(Math.max(0, seq));
  }

  byRef(ref) {
    return this.entries.filter((e) => e.ref === ref);
  }
}

module.exports = { Journal };
