/**
 * Verifiers: checkers for answers that are plans (an itinerary, a shopping basket, a packing list) or exact
 * multi-part results (a settle-up, a tariff comparison, a loan schedule). A verifier receives the case's
 * structured world (hidden from the model, stored in `expected.world`) and the model's extracted JSON answer,
 * and checks it rule by rule. The generic scorer in src/scoring/verifier.ts turns that into a score.
 * Verifier source code is part of the hash of every test that uses it (src/core/registry.ts → verifierSourceHash).
 *
 * Verifiers must be pure (no Node imports): the in-app explainer imports their marking text.
 */
import type { ScoreBreakdownItem } from '../../core/types.ts';

/** One checked rule. */
export interface VerifierItem extends ScoreBreakdownItem {
  /** Plan verifiers: true for a rule whose failure makes the plan unusable (infeasible). */
  hard?: boolean;
}

/** What a plan verifier reports for one answer. */
export interface PlanCheck {
  /** Every hard rule holds. */
  feasible: boolean;
  /** The plan's objective value (e.g. pence) when it could be worked out. */
  value?: number;
  /** The total the answer quoted, in the same unit (null: none quoted or unreadable). */
  stated?: number | null;
  items: VerifierItem[];
}

/** A checker for plans: infeasible → 0, optimal → 1, feasible but worse → partial credit (src/scoring/verifier.ts). */
export interface PlanVerifier<W = never> {
  kind: 'plan';
  name: string;
  /** Short name of the thing being checked, e.g. "itinerary". */
  noun: string;
  objective: 'min' | 'max';
  /** Top-level keys that mark a JSON object as an answer (compared case- and punctuation-insensitively). */
  answerKeys: string[];
  /** Whether the answer must quote its own total (a wrong or missing total halves the score). */
  quotesTotal: boolean;
  /** Plain-English marking rules, one sentence each (shown in the app). */
  marking: string[];
  formatValue(v: number): string;
  check(world: W, answer: Record<string, unknown>): PlanCheck;
}

/** A checker for exact multi-part answers: the score is the share of checks passed. */
export interface AnswerVerifier<W = never> {
  kind: 'answer';
  name: string;
  noun: string;
  answerKeys: string[];
  marking: string[];
  check(world: W, answer: Record<string, unknown>): { items: VerifierItem[] };
}

export type Verifier = PlanVerifier | AnswerVerifier;

/** The `expected` value of a verifier case. */
export interface VerifierKey {
  /** The structured world the verifier checks against (never sent to the model). */
  world: unknown;
  /** Plan verifiers: the proven optimum of the objective (same unit as `PlanCheck.value`). */
  optimum?: number;
  /** One correct answer, as a model would write it (shown on "Reveal answer"; scores 100). */
  reference: Record<string, unknown>;
  /** Plan verifiers: how many different optimal plans the solver found (information only). */
  optimalPlans?: number;
}
