#!/usr/bin/env python3
"""[growth22] The hundred days' FrozenWatch (soak.py), on days made up to the shape of real runs: python3 test_frozen_watch.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
os.environ.pop("HUNDRED_STALL_DAYS", None)
import soak  # noqa: E402


def day(d, **kw):
    m = {"day": d, "folk": 100, "age": "the Iron Age", "buildings": 64, "renown": 100, "villages": 6, "colonies": 3,
         "world": 300, "beds_made": 117, "food": 1000 + d, "logs": 900, "stone": 1400 + d % 7, "coins": 200 + d,
         "output": 70 + d % 5, "loaded": 90}
    m.update(kw)
    return m


def run(days):
    w = soak.FrozenWatch(days[0]["day"], True)
    for m in days:
        why = w.day(m["day"], m)
        if why:
            return m["day"], why
    return None


class FrozenWatchTest(unittest.TestCase):

    def test_colonising_town_is_not_frozen(self):
        # The 0.319.0 run, days 140 to 200: no new building, its own folk falling as colonists leave, the region growing.
        days = [day(d, folk=103 - (d - 140) // 3, world=298 + 2 * (d - 140), villages=6 + (d - 140) // 15,
                    renown=99 + (d - 140) // 2) for d in range(140, 201)]
        self.assertIsNone(run(days))

    def test_nothing_changes_at_all_is_frozen(self):
        # The 0.315.0 run: the same numbers every day.
        days = [day(1, food=500, stone=400, coins=10, output=3)]
        days += [day(d, food=500, stone=400, coins=10, output=3) for d in range(2, 30)]
        got = run(days)
        self.assertIsNotNone(got)
        self.assertLessEqual(got[0], 7)
        self.assertIn("same numbers", got[1])

    def test_busy_but_never_growing_is_frozen(self):
        # Stores churning every day, but nothing new anywhere in the region for STALL_DAYS days.
        got = run([day(d) for d in range(50, 120)])
        self.assertIsNotNone(got)
        self.assertEqual(got[0], 50 + soak.FrozenWatch.STALL_DAYS)
        self.assertIn("nothing new", got[1])

    def test_a_new_age_or_a_storey_is_progress(self):
        days = [day(d) for d in range(50, 70)]
        days += [day(d, age="the Diamond Age") for d in range(70, 90)]
        days += [day(d, age="the Diamond Age", beds_made=125) for d in range(90, 110)]
        self.assertIsNone(run(days))
        # ... and once those stop, the clock runs again from the last of them.
        days += [day(d, age="the Diamond Age", beds_made=125) for d in range(110, 130)]
        got = run(days)
        self.assertEqual(got[0], 90 + soak.FrozenWatch.STALL_DAYS)

    def test_unloaded_town_is_frozen(self):
        got = run([day(d, loaded=0) for d in range(1, 10)])
        self.assertEqual(got[0], 2)


if __name__ == "__main__":
    unittest.main()
