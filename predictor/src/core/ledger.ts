// ---------------------------------------------------------------------------
// Forecast ledger identity and scoring eligibility.
//
// The ledger is what makes the accuracy record mean anything: it holds the
// forecast as it stood the moment each match kicked off, so a result is always
// scored against a prediction that could not have seen it. Two small decisions
// underpin that, and both have gone wrong here before, so they live in the
// core where they are covered by tests rather than inline in the pipeline.
// ---------------------------------------------------------------------------

/** The minimum identity a fixture needs to be found in the ledger again. */
export interface FixtureIdentity {
  season: string
  /** The data feed's fixture id, or 0 when no feed supplied one. */
  fixtureId: number
  home: string
  away: string
}

/**
 * A fixture's key in the ledger.
 *
 * The feed's fixture id is the natural key, but it is absent whenever that
 * feed is unavailable and the results source is carrying the season alone.
 * Treating a missing id as 0 collapsed every such fixture onto a single key,
 * which pairs one match's result with a different match's probabilities — and
 * does so silently, in whichever direction the stored forecast happened to
 * point. Within one season a league fixture is uniquely identified by its two
 * clubs, so that is the fallback.
 */
export function ledgerKey(f: FixtureIdentity): string {
  return f.fixtureId ? `${f.season}#${f.fixtureId}` : `${f.season}#${f.home}-${f.away}`
}

/**
 * Whether a finished fixture may enter the accuracy record.
 *
 * Only if a forecast for it was sealed before kickoff. The tempting fallback —
 * scoring it against the prediction the current run just produced — is
 * look-ahead: that model has already ingested this result and refitted on it.
 * Worse, it fails in the flattering direction, and the more fixtures the
 * ledger missed the better the published record would look. A fixture with no
 * sealed forecast is not part of the record.
 */
export function isScorable(recorded: { probs?: unknown } | undefined | null): boolean {
  return Boolean(recorded && recorded.probs)
}
