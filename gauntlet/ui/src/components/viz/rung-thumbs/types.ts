/**
 * The per-level parameters the rung thumbnails draw. Every number here is read from the ladder's test file
 * (tests/**\/<id>.json: case prompts and notes) or from the builders' verification data, by
 * scripts/rung-thumbs-data.ts, which writes data.ts. Nothing is typed in by hand; test/rung-thumbs.test.ts
 * regenerates the data and checks it against the test files and the rung labels of config/ladders.json.
 */

/** Run It In Your Head: program length (source lines) and statements executed. */
export interface MindRunnerRung {
  lines: number;
  steps: number;
}

/** No Calculator: a^e mod m, with the digit counts of m and e. */
export interface ModpowRung {
  mDigits: number;
  eDigits: number;
  multiplications: number;
}

/** The Sliding Ladder: board size, the empty square at the start (row, column) and the proven-shortest plan. */
export interface SlidingRung {
  rows: number;
  cols: number;
  blank: [number, number];
  moves: number;
}

/** The Picture Logic Ladder: an n×n grid, filled cells, and how many runs each row's and column's clue lists. */
export interface NonogramRung {
  size: number;
  filled: number;
  rowRuns: number[];
  colRuns: number[];
}

/** Domino tilings: board size, squares to cover, and the holes (row-major indexes). */
export interface TilingRung {
  rows: number;
  cols: number;
  squares: number;
  holes: number[];
}

/** Deduction Grid: positions × categories, clues (and how many are compound). */
export interface GridRung {
  positions: number;
  categories: number;
  clues: number;
  compound: number;
}

/** Knights & Knaves: islanders, spies, alternators, statements. */
export interface TruthRung {
  islanders: number;
  spies: number;
  alternators: number;
  statements: number;
}

/** Shortest Plans (four-peg Hanoi): disks, the proven minimum, and the start (pegs A–D, bottom up). */
export interface PlansRung {
  disks: number;
  moves: number;
  pegs: number[][];
}

/** Exact Maths (snakes and ladders): squares 0..n, spinner 1..d, ladders, snakes, traps, answer size. */
export interface ExactRung {
  squares: number;
  spinner: number;
  ladders: Array<[number, number]>;
  snakes: Array<[number, number]>;
  traps: number[];
  denominatorDigits: number;
}

/** Engineering: the range operations so far (in order), the ones this level adds, and the array size. */
export interface EngineeringRung {
  ops: string[];
  added: string[];
  updateKinds: number;
  n: number;
}

/** Invoices: documents, dependency pools (count × documents each), rule families. */
export interface InvoicesRung {
  docs: number;
  pools: number;
  poolSize: number;
  families: number;
}

/** Payroll: months × staff, and the planted wrong payslips in each month. */
export interface PayrollRung {
  months: number;
  staff: number;
  wrongPerMonth: number[];
}

/** Expenses: claim lines in each monthly batch, employees and claims. */
export interface ExpensesRung {
  linesPerMonth: number[];
  people: number;
  claims: number;
}

/** Bank reconciliation: statement lines and cash-book entries in each month, and entries that clear a month later. */
export interface ReconciliationRung {
  bankPerMonth: number[];
  booksPerMonth: number[];
  clearedLater: number;
}

/** Customer records: records, duplicate groups by size ({size: count}), refused merges. */
export interface CrmRung {
  records: number;
  groupSizes: Record<string, number>;
  refusedMerges: number;
}

/** Contract red-line: clauses and rows in version 2, changes, replies (response + note items), words. */
export interface ContractRung {
  clauses: number;
  changes: number;
  replies: number;
  words: number;
}

/** SQL: semantics one query composes, and the tables it reads. */
export interface SqlRung {
  rules: number;
  tables: number;
}

/** Support: tickets, customer accounts with limits, rule families, tickets whose outcome an account limit changes. */
export interface SupportRung {
  tickets: number;
  accounts: number;
  families: number;
  changedByAccounts: number;
}

export interface RungThumbDataMap {
  'horizon.mind-runner': MindRunnerRung[];
  'horizon.modpow-ladder': ModpowRung[];
  'horizon.sliding-ladder': SlidingRung[];
  'horizon.nonogram-ladder': NonogramRung[];
  'horizon.tiling-count': TilingRung[];
  'reasoning.grid-ladder': GridRung[];
  'reasoning.truth-ladder': TruthRung[];
  'reasoning.plans-ladder': PlansRung[];
  'math.exact-ladder': ExactRung[];
  'coding.engineering-ladder': EngineeringRung[];
  'work.invoices-ladder': InvoicesRung[];
  'work.payroll-ladder': PayrollRung[];
  'work.expenses-ladder': ExpensesRung[];
  'work.reconciliation-ladder': ReconciliationRung[];
  'work.crm-ladder': CrmRung[];
  'work.contract-ladder': ContractRung[];
  'work.sql-ladder': SqlRung[];
  'work.support-ladder': SupportRung[];
}

export type RungThumbTestId = keyof RungThumbDataMap;
