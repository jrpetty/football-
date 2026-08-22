/**
 * Where contributed data lives between visits.
 *
 * Same pattern as the health sessions and the build library: localStorage,
 * because there is no backend and there is not going to be one. A price
 * somebody typed has to survive a closed tab or nobody types a second one.
 *
 * Reads are defensive. This key holds data the user could have edited by hand,
 * that a previous version of this app may have written, or that a browser may
 * have truncated — so nothing here trusts what it finds, and everything goes
 * back through the same validator an imported file does.
 */
import {
  emptyUserData, mergeUserData, readUserData, type UserData, type UserMeasurement, type UserPrice,
} from '../core/userdata.ts';

const KEY = 'rigcheck.userdata.v1';

/**
 * The key the pricing layer has always read for operator overrides.
 *
 * Nothing ever wrote it: the override tier existed in the read path, was
 * documented as the strongest kind of evidence, and had no way in. It is read
 * once here so that anyone who set it by hand keeps their figures, and then
 * never written again — the versioned bundle is the store now.
 */
const LEGACY_OVERRIDE_KEY = 'rigcheck.priceOverride';

export function loadUserData(): UserData {
  let stored = emptyUserData();
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) stored = readUserData(JSON.parse(raw)).data;
  } catch {
    // Corrupt or unreadable. An empty store is recoverable; throwing here would
    // take down every screen that shows a price.
  }
  try {
    const legacy = localStorage.getItem(LEGACY_OVERRIDE_KEY);
    if (legacy) {
      const map = JSON.parse(legacy) as Record<string, number>;
      const migrated: UserPrice[] = Object.entries(map)
        .filter(([, v]) => typeof v === 'number' && v > 0)
        // Condition unknown — the old shape did not record one. Filed as used,
        // which is the side the tools default to and the side a hand-entered
        // figure is far more likely to have come from.
        .map(([partId, gbp]) => ({ partId, condition: 'used' as const, gbp, at: new Date(0).toISOString(), note: 'migrated from an earlier price override' }));
      if (migrated.length) stored = mergeUserData({ ...stored, prices: migrated }, stored);
    }
  } catch {
    // Nothing to migrate.
  }
  return stored;
}

export function saveUserData(data: UserData): UserData {
  try {
    localStorage.setItem(KEY, JSON.stringify(data));
  } catch {
    // Storage full or blocked. The in-memory copy still drives this session.
  }
  return data;
}

export function addPrice(p: UserPrice): UserData {
  return saveUserData(mergeUserData(loadUserData(), { ...emptyUserData(), prices: [p] }));
}

export function addMeasurement(m: UserMeasurement): UserData {
  return saveUserData(mergeUserData(loadUserData(), { ...emptyUserData(), measurements: [m] }));
}

export function removePrice(partId: string, condition: 'new' | 'used'): UserData {
  const d = loadUserData();
  return saveUserData({ ...d, prices: d.prices.filter((p) => !(p.partId === partId && p.condition === condition)) });
}

export function removeMeasurement(id: string): UserData {
  const d = loadUserData();
  return saveUserData({ ...d, measurements: d.measurements.filter((m) => m.id !== id) });
}

export function clearUserData(): UserData {
  return saveUserData(emptyUserData());
}
