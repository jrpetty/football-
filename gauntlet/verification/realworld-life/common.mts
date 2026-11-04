/**
 * Shared helpers for the Everyday Life generators: a seeded random stream (independent of the app's own), money
 * formatting and exact rounding. Everything here is deterministic: the same seed always gives the same tests.
 */

/** mulberry32: small, fast, good enough for picking names and jittering prices. */
export class Rng {
  private s: number;
  constructor(seed: number) {
    this.s = seed >>> 0 || 1;
  }
  next(): number {
    let t = (this.s = (this.s + 0x6d2b79f5) >>> 0);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }
  int(lo: number, hi: number): number {
    return lo + Math.floor(this.next() * (hi - lo + 1));
  }
  pick<T>(xs: readonly T[]): T {
    return xs[Math.floor(this.next() * xs.length)]!;
  }
  shuffle<T>(xs: readonly T[]): T[] {
    const a = xs.slice();
    for (let i = a.length - 1; i > 0; i--) {
      const j = Math.floor(this.next() * (i + 1));
      [a[i], a[j]] = [a[j]!, a[i]!];
    }
    return a;
  }
  /** `n` distinct picks. */
  sample<T>(xs: readonly T[], n: number): T[] {
    return this.shuffle(xs).slice(0, n);
  }
}

/** "£1,234.50" from pence. */
export function money(pence: number): string {
  const sign = pence < 0 ? '-' : '';
  const a = Math.abs(Math.round(pence));
  return `${sign}£${Math.floor(a / 100).toLocaleString('en-GB')}.${String(a % 100).padStart(2, '0')}`;
}

/** Pence as a JSON number of pounds (2 dp). */
export function pounds(pence: number): number {
  return Math.round(pence) / 100;
}

/** "07:05" from minutes after midnight (wraps past 24h). */
export function hhmm(min: number): string {
  const m = ((min % 1440) + 1440) % 1440;
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

/** Minutes after midnight from "07:05". */
export function mins(t: string): number {
  const [h, m] = t.split(':').map(Number);
  return h! * 60 + m!;
}

/** Exact rational arithmetic on bigints (for prorating bills to the penny without float drift). */
export class Q {
  readonly n: bigint;
  readonly d: bigint;
  constructor(n: bigint | number, d: bigint | number = 1n) {
    let nn = BigInt(n);
    let dd = BigInt(d);
    if (dd < 0n) {
      nn = -nn;
      dd = -dd;
    }
    const g = gcd(nn < 0n ? -nn : nn, dd);
    this.n = g ? nn / g : nn;
    this.d = g ? dd / g : dd;
  }
  add(o: Q): Q {
    return new Q(this.n * o.d + o.n * this.d, this.d * o.d);
  }
  sub(o: Q): Q {
    return new Q(this.n * o.d - o.n * this.d, this.d * o.d);
  }
  mul(o: Q): Q {
    return new Q(this.n * o.n, this.d * o.d);
  }
  div(o: Q): Q {
    return new Q(this.n * o.d, this.d * o.n);
  }
  /** Round to a whole number, halves away from zero. */
  round(): bigint {
    const neg = this.n < 0n;
    const a = neg ? -this.n : this.n;
    const r = (2n * a + this.d) / (2n * this.d);
    return neg ? -r : r;
  }
  isHalfway(): boolean {
    return (2n * (this.n < 0n ? -this.n : this.n)) % (2n * this.d) === this.d;
  }
  static of(n: number): Q {
    return new Q(BigInt(Math.round(n)));
  }
}

function gcd(a: bigint, b: bigint): bigint {
  while (b) [a, b] = [b, a % b];
  return a;
}

/** Round half up (away from zero) to the nearest whole unit for an ordinary number known to be exact in tenths of a unit or better. */
export function roundHalfUp(x: number): number {
  return Math.sign(x) * Math.floor(Math.abs(x) + 0.5 + 1e-9);
}

/** A pool of invented first names from many backgrounds. */
export const FIRST_NAMES = [
  'Amara', 'Bilal', 'Chloe', 'Dev', 'Eilidh', 'Femi', 'Grace', 'Hamza', 'Ines', 'Jun', 'Kofi', 'Leah', 'Mateusz', 'Niamh', 'Omar',
  'Priya', 'Quinn', 'Rhys', 'Sana', 'Tomasz', 'Uche', 'Vikram', 'Wen', 'Xavier', 'Yasmin', 'Zara', 'Aoife', 'Bongani', 'Callum',
  'Dalia', 'Emeka', 'Farah', 'Gethin', 'Hana', 'Ibrahim', 'Joanna', 'Kai', 'Lucia', 'Malik', 'Nadia', 'Oskar', 'Rosa', 'Sunil', 'Talia',
];

/** A JSON test file in the house style (2-space indent, trailing newline). */
export function testJson(def: unknown): string {
  return JSON.stringify(def, null, 2) + '\n';
}

/** The anti-hedging sentence every prompt in this pack ends with. */
export const ONE_ANSWER =
  'Give exactly one answer as a single JSON block. Do not offer alternatives: a reply that contains two different answers, or an answer that says "or", is marked wrong. You may explain your working before the JSON.';
