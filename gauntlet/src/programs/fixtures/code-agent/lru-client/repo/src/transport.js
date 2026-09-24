'use strict';

/**
 * In-memory transport for tests and demos. Resolves `routes[path]` after
 * `delayMs`, or rejects with a 404-style error. Counts every call.
 */
function memoryTransport(routes, { delayMs = 0, failures = {} } = {}) {
  const calls = [];
  async function transport(path) {
    calls.push(path);
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    if (failures[path] > 0) {
      failures[path] -= 1;
      const err = new Error(`503 Service Unavailable: ${path}`);
      err.status = 503;
      throw err;
    }
    if (!Object.prototype.hasOwnProperty.call(routes, path)) {
      const err = new Error(`404 Not Found: ${path}`);
      err.status = 404;
      throw err;
    }
    return JSON.parse(JSON.stringify(routes[path]));
  }
  transport.calls = calls;
  return transport;
}

module.exports = { memoryTransport };
