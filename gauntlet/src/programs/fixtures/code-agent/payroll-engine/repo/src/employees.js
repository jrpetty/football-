'use strict';

const { toDay } = require('./dates');

const TYPES = ['salaried', 'hourly'];

function validate(e) {
  if (!e || typeof e.id !== 'string' || !e.id) throw new Error('Employee needs an id');
  if (!TYPES.includes(e.type)) throw new Error(`Employee ${e.id}: type must be salaried or hourly`);
  if (e.type === 'salaried' && !(Number.isInteger(e.annualSalaryCents) && e.annualSalaryCents >= 0)) {
    throw new Error(`Employee ${e.id}: annualSalaryCents must be a whole number of cents`);
  }
  if (e.type === 'hourly' && !(Number.isInteger(e.hourlyRateCents) && e.hourlyRateCents >= 0)) {
    throw new Error(`Employee ${e.id}: hourlyRateCents must be a whole number of cents`);
  }
  toDay(e.startDate);
  const pension = e.pensionPct ?? 0;
  if (!(pension >= 0 && pension <= 100)) throw new Error(`Employee ${e.id}: pensionPct must be within 0..100`);
}

class EmployeeRegistry {
  constructor() {
    this.byId = new Map();
  }

  add(employee) {
    validate(employee);
    if (this.byId.has(employee.id)) throw new Error(`Duplicate employee ${employee.id}`);
    const record = { pensionPct: 0, unionDuesCents: 0, ...employee };
    this.byId.set(employee.id, record);
    return record;
  }

  get(id) {
    const e = this.byId.get(id);
    if (!e) throw new Error(`Unknown employee ${id}`);
    return e;
  }

  /** Change details such as salary or pension percentage (the id cannot change). */
  update(id, changes) {
    const current = this.get(id);
    const next = { ...current, ...changes, id };
    validate(next);
    this.byId.set(id, next);
    return next;
  }

  /** Everyone, sorted by id. */
  list() {
    return [...this.byId.values()].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  }

  /** Employees who have started by the end of the period. */
  activeIn(period) {
    return this.list().filter((e) => toDay(e.startDate) <= toDay(period.end));
  }
}

module.exports = { EmployeeRegistry, validate };
