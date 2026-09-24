'use strict';

const { parseTime } = require('./time');

class Rooms {
  constructor() {
    this.byId = new Map();
  }

  add({ id, name, capacity, open = '08:00', close = '18:00' }) {
    if (!id) throw new Error('Room needs an id');
    if (this.byId.has(id)) throw new Error(`Duplicate room ${id}`);
    if (!Number.isInteger(capacity) || capacity < 1) throw new Error(`Invalid capacity for ${id}`);
    const room = { id, name: name ?? id, capacity, open: parseTime(open), close: parseTime(close) };
    if (room.close <= room.open) throw new Error(`Room ${id} closes before it opens`);
    this.byId.set(id, room);
    return room;
  }

  get(id) {
    const room = this.byId.get(id);
    if (!room) throw new Error(`Unknown room ${id}`);
    return room;
  }

  list() {
    return [...this.byId.values()];
  }
}

module.exports = { Rooms };
