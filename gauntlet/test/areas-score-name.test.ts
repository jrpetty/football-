import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { BUSINESS_SUITE_ID, LIFE_SUITE_ID, SCORE_NAMES, areaOfTests, scoreNameFor, scoreNameForSuite, withArticle } from '../src/core/areas.ts';

describe('the headline score name (one rule for every screen)', () => {
  it('only benchmark results are the Gauntlet Index', () => {
    assert.equal(scoreNameFor([{ id: 'reasoning.river-crossing', category: 'reasoning' }, { id: 'math.olympiad', category: 'math' }]), 'Gauntlet Index');
    assert.equal(scoreNameFor([]), 'Gauntlet Index');
    assert.equal(scoreNameFor(null), 'Gauntlet Index');
  });
  it('Business and Everyday Life results have their own score', () => {
    assert.equal(scoreNameFor([{ id: 'work.invoice-read', category: 'work' }, { id: 'work.month-end', category: 'work' }]), 'Business Score');
    assert.equal(scoreNameFor([{ id: 'life.bill-split', category: 'life' }]), 'Everyday Life Score');
    // by id prefix when the category is missing
    assert.equal(scoreNameFor([{ id: 'work.ticket-tag' }]), 'Business Score');
  });
  it('a mix of areas is never called the Gauntlet Index', () => {
    assert.equal(areaOfTests([{ id: 'work.cv-read', category: 'work' }, { id: 'life.trip-planner', category: 'life' }]), 'mixed');
    assert.equal(scoreNameFor([{ id: 'work.cv-read', category: 'work' }, { id: 'reasoning.x', category: 'reasoning' }]), SCORE_NAMES.mixed);
    assert.notEqual(SCORE_NAMES.mixed, 'Gauntlet Index');
  });
  it('suites: the area suites by id, others by their tests', () => {
    assert.equal(scoreNameForSuite({ id: BUSINESS_SUITE_ID }), 'Business Score');
    assert.equal(scoreNameForSuite({ id: LIFE_SUITE_ID }), 'Everyday Life Score');
    assert.equal(scoreNameForSuite({ id: 'core', tests: [{ id: '*' }] }), 'Gauntlet Index');
    assert.equal(scoreNameForSuite({ id: 'starter', tests: [{ id: 'work.invoice-read' }] }), 'Business Score');
  });
  it('sentences read naturally', () => {
    assert.equal(withArticle('Business Score'), 'a Business Score');
    assert.equal(withArticle('Everyday Life Score'), 'an Everyday Life Score');
    assert.equal(withArticle('Gauntlet Index'), 'a Gauntlet Index');
  });
});
