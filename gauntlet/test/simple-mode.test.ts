/**
 * Simple mode: the navigation model (simple vs all tools, every page reachable), page-help coverage for every
 * route, and the Home checklist's state derivation.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROUTE_EXAMPLES, SECTIONS, SIMPLE_NAV, groupAllTools, pageKeyFor, sectionFor, tabsFor, type PageKey } from '../ui/src/simple/nav.ts';
import { PAGE_HELP, helpFor } from '../ui/src/help/pageHelp.ts';
import { deriveChecklist, latestFinishedRun } from '../ui/src/simple/checklist.ts';

const APP = readFileSync(join(import.meta.dirname, '..', 'ui', 'src', 'App.tsx'), 'utf8');

/** The menu items in App.tsx's NAV array. */
function appNav(): Array<{ to: string; label: string }> {
  const block = APP.slice(APP.indexOf('const NAV: NavItem[] = ['), APP.indexOf('];', APP.indexOf('const NAV: NavItem[] = [')));
  return [...block.matchAll(/\{ to: '([^']+)', label: '([^']+)'/g)].map((m) => ({ to: m[1]!, label: m[2]! }));
}

/** Every first path segment App.tsx's router handles (`a === 'runs'` …). */
function appTopSegments(): string[] {
  const body = APP.slice(APP.indexOf('function resolve('), APP.indexOf('function Shell('));
  return [...new Set([...body.matchAll(/\ba === '([a-z-]+)'/g)].map((m) => m[1]!))];
}

// ───────────── page keys ─────────────

test('pageKeyFor: every example route maps back to its own key', () => {
  for (const [key, path] of Object.entries(ROUTE_EXAMPLES)) assert.equal(pageKeyFor(path), key, path);
});

test('pageKeyFor: queries, encoded ids, bare full-screen routes and old leaderboard links', () => {
  assert.equal(pageKeyFor('/'), 'home');
  assert.equal(pageKeyFor('/run/new?suite=quick-check'), 'run-new');
  assert.equal(pageKeyFor('/runs/run%2012'), 'run-detail');
  assert.equal(pageKeyFor('/present/run-1'), 'present');
  assert.equal(pageKeyFor('/present/versus'), 'versus');
  assert.equal(pageKeyFor('/guide/show'), 'guide');
  assert.equal(pageKeyFor('/arena/t-1/card'), 'arena-tournament');
  assert.equal(pageKeyFor('/tests/x/edit'), 'test-builder');
  assert.equal(pageKeyFor('/no-such-page'), 'unknown');
});

test('every route the app handles has a page key and a help entry', () => {
  const segs = appTopSegments();
  assert.ok(segs.length >= 25, `found only ${segs.length} routes in App.tsx`);
  for (const seg of segs) {
    const key = pageKeyFor(seg === 'run' ? '/run/new' : `/${seg}`);
    assert.notEqual(key, 'unknown', `route /${seg} has no page key in ui/src/simple/nav.ts`);
    assert.ok(PAGE_HELP[key], `route /${seg} (${key}) has no help in ui/src/help/pageHelp.ts`);
  }
});

// ───────────── page help ─────────────

test('page help: every page key has plain help (purpose, steps) and short first-visit guides', () => {
  for (const key of Object.keys(ROUTE_EXAMPLES) as PageKey[]) {
    const h = PAGE_HELP[key];
    assert.ok(h, `${key} has no help`);
    assert.ok(h.title.trim() && h.purpose.trim().length > 20, `${key}: purpose too short`);
    assert.ok(h.steps.length >= 1, `${key}: no steps`);
    if (h.guide) {
      const sentences = h.guide.text.split(/[.!?](\s|$)/).filter((s) => s && s.trim()).length;
      assert.ok(sentences <= 3, `${key}: guide is ${sentences} sentences (max 3)`);
      if (h.guide.next) assert.notEqual(pageKeyFor(h.guide.next.to), 'unknown', `${key}: guide button goes nowhere`);
    }
    for (const l of h.links) assert.notEqual(pageKeyFor(l.to), 'unknown', `${key}: help link ${l.to} goes nowhere`);
  }
  assert.equal(helpFor('unknown').title, 'Help');
});

// ───────────── navigation model ─────────────

test('simple mode: about six task-named items, Home first, each with tabs that point at real pages', () => {
  assert.ok(SIMPLE_NAV.length >= 5 && SIMPLE_NAV.length <= 7, `simple menu has ${SIMPLE_NAV.length} items`);
  assert.deepEqual(
    SIMPLE_NAV.map((s) => s.label),
    ['Home', 'Run a test', 'Results', 'Make a video', 'Copy & paste tests', 'Settings'],
  );
  for (const s of SIMPLE_NAV) {
    assert.notEqual(pageKeyFor(s.to), 'unknown', s.label);
    assert.equal(sectionFor(pageKeyFor(s.to))?.id, s.id, `${s.label}'s link lights up ${s.label}`);
    for (const t of s.tabs) {
      assert.ok(t.keys.includes(pageKeyFor(t.to)), `${s.label} → ${t.label} tab link is one of its pages`);
      for (const k of t.keys) assert.ok(s.keys.includes(k), `${s.label} → ${t.label}: ${k} belongs to the section`);
    }
  }
});

test('every page belongs to exactly one section, so the sidebar always shows where you are', () => {
  for (const key of Object.keys(ROUTE_EXAMPLES) as PageKey[]) {
    const owners = SECTIONS.filter((s) => s.keys.includes(key));
    assert.equal(owners.length, 1, `${key} is in ${owners.map((o) => o.id).join(', ') || 'no section'}`);
  }
});

test('tabs: Results groups the leaderboard, best on each test, past runs and history', () => {
  const t = tabsFor('leaderboard');
  assert.ok(t);
  assert.deepEqual(
    t.tabs.map((x) => x.label),
    ['Leaderboard', 'Best on each test', 'Past runs', 'History'],
  );
  assert.equal(tabsFor('run-detail')?.current.label, 'Past runs');
  assert.equal(tabsFor('keys')?.section.label, 'Settings');
  assert.equal(tabsFor('inbox')?.current.label, 'Manual Inbox');
  assert.equal(tabsFor('home'), null);
  assert.equal(tabsFor('run-live'), null, 'a live run has no tab bar');
});

test('all tools: every one of the app’s menu items is in a labelled group, none lost', () => {
  const items = appNav();
  const paths = items.map((i) => i.to);
  for (const p of ['/', '/run/new', '/leaderboard', '/runs', '/arena', '/versus', '/best', '/present', '/studio', '/gallery', '/inbox', '/grading', '/review', '/tests', '/grade', '/keys', '/models', '/costs', '/budget', '/newmodel', '/history', '/challenge', '/publish', '/methodology', '/guide']) {
    assert.ok(paths.includes(p), `App.tsx NAV is missing ${p}`);
  }
  const groups = groupAllTools(items);
  const flat = groups.flatMap((g) => g.items);
  assert.equal(flat.length, items.length, 'every item appears exactly once');
  assert.deepEqual(new Set(flat), new Set(items));
  assert.ok(!groups.some((g) => g.id === 'more'), `ungrouped: ${groups.find((g) => g.id === 'more')?.items.map((i) => i.to).join(', ')}`);
  for (const g of groups) assert.ok(g.label && g.blurb, g.id);
  assert.equal(groups[0]!.items[0]!.to, '/', 'Home comes first');
  // Each item's page is in the group of the same name, so both menus agree.
  for (const g of groups) for (const it of g.items) assert.equal(sectionFor(pageKeyFor(it.to))?.id, g.id, it.to);
});

test('all tools: a menu item no group knows about still shows, under "More tools"', () => {
  const groups = groupAllTools([{ to: '/' }, { to: '/brand-new-tool' }]);
  assert.deepEqual(
    groups.map((g) => g.id),
    ['home', 'more'],
  );
});

// ───────────── Home checklist ─────────────

const run = (id: string, status: string, createdAt: string, completedJobs = 5) => ({ id, status, createdAt, completedJobs });

test('checklist: still loading until the key check and the runs list arrive', () => {
  const c = deriveChecklist({ anyKey: null, runs: null, seenResults: false, seenPresenter: false });
  assert.equal(c.loading, true);
  assert.equal(deriveChecklist({ anyKey: true, runs: null, seenResults: false, seenPresenter: false }).loading, true);
});

test('checklist: brand-new install → step 1, add a key', () => {
  const c = deriveChecklist({ anyKey: false, runs: [], seenResults: false, seenPresenter: false });
  assert.equal(c.loading, false);
  assert.equal(c.next?.id, 'key');
  assert.equal(c.next?.cta.to, '/keys');
  assert.equal(c.doneCount, 0);
  assert.equal(c.allDone, false);
});

test('checklist: key saved, no runs → run the 2p Quick Check', () => {
  const c = deriveChecklist({ anyKey: true, runs: [], seenResults: false, seenPresenter: false });
  assert.equal(c.next?.id, 'run');
  assert.equal(c.next?.cta.to, '/run/new?suite=quick-check');
  assert.match(c.next!.cta.label, /2p/);
  assert.equal(c.doneCount, 1);
});

test('checklist: a run exists → see the results of the newest finished run', () => {
  const runs = [run('old', 'completed', '2026-09-01T10:00:00Z'), run('new', 'completed', '2026-10-01T10:00:00Z'), run('live', 'running', '2026-10-02T10:00:00Z', 1)];
  const c = deriveChecklist({ anyKey: true, runs, seenResults: false, seenPresenter: false });
  assert.equal(c.next?.id, 'results');
  assert.equal(c.next?.cta.to, '/runs/new');
  assert.equal(c.steps[3]!.cta.to, '/present/new');
  assert.equal(c.doneCount, 2);
});

test('checklist: runs with nothing finished yet point at the run list and the Presenter picker', () => {
  const c = deriveChecklist({ anyKey: true, runs: [run('r', 'running', '2026-10-02T10:00:00Z', 0)], seenResults: false, seenPresenter: false });
  assert.equal(c.next?.cta.to, '/runs');
  assert.equal(c.steps[3]!.cta.to, '/present');
});

test('checklist: results seen → make slides; everything seen → all done', () => {
  const runs = [run('a', 'completed', '2026-10-01T10:00:00Z')];
  assert.equal(deriveChecklist({ anyKey: true, runs, seenResults: true, seenPresenter: false }).next?.id, 'video');
  const all = deriveChecklist({ anyKey: true, runs, seenResults: true, seenPresenter: true });
  assert.equal(all.allDone, true);
  assert.equal(all.next, null);
  assert.equal(all.doneCount, 4);
});

test('checklist: "seen" flags do not count before there is a run to have seen', () => {
  const c = deriveChecklist({ anyKey: true, runs: [], seenResults: true, seenPresenter: true });
  assert.equal(c.doneCount, 1);
  assert.equal(c.next?.id, 'run');
});

test('checklist: a run without a key (Random Baseline) still leaves "add a key" as the next step', () => {
  const c = deriveChecklist({ anyKey: false, runs: [run('b', 'completed', '2026-10-01T10:00:00Z')], seenResults: false, seenPresenter: false });
  assert.equal(c.next?.id, 'key');
  assert.equal(c.steps[1]!.done, true);
});

test('latestFinishedRun ignores running, queued and empty runs', () => {
  assert.equal(latestFinishedRun(null), null);
  assert.equal(latestFinishedRun([run('q', 'queued', '2026-10-03T00:00:00Z', 0), run('e', 'failed', '2026-10-04T00:00:00Z', 0)]), null);
  assert.equal(latestFinishedRun([run('c', 'cancelled', '2026-10-01T00:00:00Z', 3), run('r', 'running', '2026-10-05T00:00:00Z', 9)])?.id, 'c');
});
