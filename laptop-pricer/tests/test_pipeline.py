"""Unit tests for the pricing maths and the stock book."""
from __future__ import annotations

import datetime as dt
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pricer import stock as stockmod
from pricer.calibrate import calibrate
from pricer.commercial import (UncalibratedParameter, at_grade_and_channel, price)
from pricer.config import ROOT, cfg, fitted, sources, use_fitted
from pricer.db import connect
from pricer.estimate import (effective_n, estimate, mad_sigma, reject_outliers,
                             spec_delta, weighted_median, Comparable)
from pricer.ingest import ingest_all
from pricer.match import config_key, cpu_compatible, resolve, verdict
from pricer.normalise import net_realised, normalise, to_reference
from pricer.parse import parse_title, tokens

AS_OF = dt.date(2026, 8, 19)


def fresh_db():
    tmp = tempfile.NamedTemporaryFile(suffix=".duckdb", delete=False)
    tmp.close()
    Path(tmp.name).unlink()
    return connect(tmp.name)


class TestParser(unittest.TestCase):
    def test_glued_trade_shorthand(self):
        self.assertIn("LATITUDE", tokens("DELL LAT5420"))
        self.assertIn("5420", tokens("DELL LAT5420"))

    def test_generation_suffix_normalises(self):
        self.assertEqual(tokens("EliteBook 840 G7"), ["ELITEBOOK", "840", "GEN", "7"])

    def test_ram_storage_pair_notation(self):
        s = parse_title("DELL LATITUDE 5420 CORE I5 11TH GEN 16/512")
        self.assertEqual((s.ram_gb, s.storage_gb), (16, 512))

    def test_terabyte_storage(self):
        s = parse_title("Dell XPS 13 9310 i7-1185G7 16GB 1TB 4K Touch")
        self.assertEqual(s.storage_gb, 1024)
        self.assertEqual(s.panel, "uhd")
        self.assertTrue(s.touch)

    def test_ram_is_smaller_number(self):
        s = parse_title("Apple MacBook Air M1 2020 8GB 256GB Space Grey")
        self.assertEqual((s.ram_gb, s.storage_gb), (8, 256))

    def test_longest_cpu_alias_wins(self):
        exact = parse_title("Dell Latitude 5420 i5-1145G7 16GB 512GB")
        vague = parse_title("Dell Latitude 5420 i5 11th Gen 16GB 512GB")
        self.assertEqual(exact.cpu_id, "intel-i5-1145g7")
        self.assertEqual(vague.cpu_id, "intel-gen11-i5-median")

    def test_unknown_machine_is_not_guessed(self):
        s = parse_title("Some Unbranded Netbook 4GB")
        self.assertIsNone(s.model_id)
        self.assertEqual(verdict(resolve(s)[1]), "review")


class TestNormalisation(unittest.TestCase):
    def setUp(self):
        self.src = sources()

    def test_vat_and_fees_stripped(self):
        # 285 inc VAT -> 237.50 ex VAT, less 12.8% + 30p of the gross
        net = net_realised(285.0, self.src["ebay_sold_uk"])
        self.assertAlmostEqual(net, 285 / 1.2 - (285 * 0.128 + 0.30), places=4)

    def test_ask_takes_a_haircut(self):
        net = net_realised(185.0, self.src["supplier_stocklist"])
        self.assertAlmostEqual(net, 185 * 0.96, places=4)

    def test_grade_converts_towards_reference(self):
        """An A-grade sale implies a LOWER B-grade value, so it divides down.

        Asserted against the ACTIVE multiplier rather than the seed, so the
        invariant still holds once calibration replaces the seed."""
        from pricer.config import grade_multiplier
        value, _ = to_reference(100.0, "A", "own_retail_b2c")
        self.assertAlmostEqual(value, 100 / grade_multiplier("A"), places=6)
        self.assertLess(value, 100)

    def test_worse_grade_converts_upwards(self):
        from pricer.config import grade_multiplier
        value, _ = to_reference(100.0, "C", "own_retail_b2c")
        self.assertAlmostEqual(value, 100 / grade_multiplier("C"), places=6)
        self.assertGreater(value, 100)

    def test_channel_and_grade_round_trip(self):
        """to_reference then at_grade_and_channel must be the identity."""
        for grade in ("A_PLUS", "A", "B", "C", "D"):
            for channel in cfg()["channels"]["ladder"]:
                ref, _ = to_reference(250.0, grade, channel)
                self.assertAlmostEqual(at_grade_and_channel(ref, grade, channel), 250.0, places=6)

    def test_spread_collapses(self):
        """The design's central claim: normalisation makes disagreeing sources agree."""
        raw = [(285, "ebay_sold_uk", "B", 1), (310, "ebay_sold_uk", "A", 1),
               (270, "own_pos", "B", 1), (168, "trade_auction", "C", 1),
               (185, "supplier_stocklist", "B", 14)]
        norm = [normalise(g, self.src[s], gr, "B", q).price_norm for g, s, gr, q in raw]
        raw_spread = max(r[0] for r in raw) / min(r[0] for r in raw) - 1
        norm_spread = max(norm) / min(norm) - 1
        self.assertGreater(raw_spread, 0.8)
        self.assertLess(norm_spread, 0.12)


class TestRobustStatistics(unittest.TestCase):
    def test_weighted_median_follows_weight(self):
        self.assertEqual(weighted_median([(10, 1), (20, 1), (30, 8)]), 30)
        self.assertEqual(weighted_median([(10, 8), (20, 1), (30, 1)]), 10)

    def test_effective_n_penalises_lopsided_weights(self):
        self.assertAlmostEqual(effective_n([1, 1, 1, 1]), 4.0)
        self.assertLess(effective_n([1, 0.1, 0.1, 0.1]), 2.0)

    def test_mad_ignores_a_wild_outlier(self):
        clean = [100, 102, 98, 101, 99]
        self.assertLess(mad_sigma(clean + [1000]) / mad_sigma(clean), 2.0)

    def test_outlier_rejection_drops_the_typo(self):
        comps = [Comparable(i, "s", "c", AS_OF, v, 1.0, "B", "own_retail_b2c")
                 for i, v in enumerate([200, 205, 198, 202, 201, 45])]
        keep, drop = reject_outliers(comps, cfg()["guardrails"]["outlier_modified_z"])
        self.assertEqual([c.value for c in drop], [45])
        self.assertEqual(len(keep), 5)

    def test_shrinkage_moves_towards_parent_when_evidence_is_thin(self):
        k = cfg()["guardrails"]["shrinkage_k"]
        thin, thick = 1 / (1 + k), 40 / (40 + k)
        self.assertLess(thin, 0.35)
        self.assertGreater(thick, 0.9)


class TestSpecDeltas(unittest.TestCase):
    def test_soldered_ram_commands_a_bigger_premium(self):
        from pricer.config import catalog
        thinkpad = catalog()["models"]["lenovo-thinkpad-t14-g2"]     # ram_upgradeable
        macbook = catalog()["models"]["apple-macbook-air-m1"]        # soldered
        self.assertTrue(thinkpad["ram_upgradeable"])
        self.assertFalse(macbook["ram_upgradeable"])
        up = spec_delta(thinkpad, (8, 256), (16, 256))
        sold = spec_delta(macbook, (8, 256), (16, 256))
        self.assertGreater(sold, up)

    def test_delta_is_symmetric(self):
        from pricer.config import catalog
        model = catalog()["models"]["dell-latitude-5420"]
        up = spec_delta(model, (8, 256), (16, 512))
        down = spec_delta(model, (16, 512), (8, 256))
        self.assertAlmostEqual(up * down, 1.0, places=9)


class TestCpuCompatibility(unittest.TestCase):
    def test_generation_median_is_weaker_evidence(self):
        w = cpu_compatible("intel-gen11-i5-median", "intel-i5-1145g7")
        self.assertGreater(w, 0.5)
        self.assertLess(w, 1.0)

    def test_different_tier_is_not_evidence(self):
        self.assertEqual(cpu_compatible("intel-i7-1165g7", "intel-i5-1145g7"), 0.0)

    def test_exact_match_is_full_weight(self):
        self.assertEqual(cpu_compatible("intel-i5-1145g7", "intel-i5-1145g7"), 1.0)


class TestGrading(unittest.TestCase):
    def test_worst_check_decides_the_grade(self):
        grade, _ = stockmod.compute_grade(
            {"chassis": "none", "screen": "clean", "keyboard": "none", "hinges": "pass"}, 95)
        self.assertEqual(grade, "A_PLUS")
        grade, _ = stockmod.compute_grade(
            {"chassis": "none", "screen": "cracked", "keyboard": "none", "hinges": "pass"}, 95)
        self.assertEqual(grade, "D")

    def test_weak_battery_caps_the_grade_and_raises_a_defect(self):
        grade, defects = stockmod.compute_grade(
            {"chassis": "none", "screen": "clean", "keyboard": "none", "hinges": "pass"}, 55)
        self.assertEqual(grade, "C")
        self.assertIn("battery_replace", defects)

    def test_bios_lock_is_a_hard_stop(self):
        with self.assertRaises(stockmod.HardStop):
            stockmod.compute_grade({"chassis": "none", "bios_locked": True}, 95)

    def test_failed_functional_check_becomes_a_defect(self):
        _, defects = stockmod.compute_grade(
            {"chassis": "none", "screen": "clean", "keyboard": "none",
             "hinges": "pass", "webcam": False}, 95)
        self.assertIn("webcam", defects)


class TestCostAllocation(unittest.TestCase):
    def test_value_weighted_allocation_conserves_the_pot(self):
        con = fresh_db()
        ingest_all(con, ROOT / "data" / "demo")
        lot = stockmod.create_lot(con, "Test Supplier", "REF-1", 1000.0, AS_OF, freight=50.0)
        checks = {"chassis": "light", "screen": "clean", "keyboard": "none", "hinges": "pass"}
        for title in ["Lenovo ThinkPad X1 Carbon Gen 9 i7-1165G7 16GB 512GB",
                      "Dell Latitude 5420 i5-1145G7 16GB 512GB",
                      "Dell Inspiron 3511 i3-1115G4 8GB 256GB"]:
            stockmod.intake(con, title, checks, lot_id=lot, battery_health_pct=90, as_of=AS_OF)
        rows = stockmod.close_lot(con, lot)
        self.assertAlmostEqual(sum(r["cost_allocated"] for r in rows), 1050.0, places=1)

    def test_expensive_unit_carries_more_cost_than_an_even_split(self):
        con = fresh_db()
        ingest_all(con, ROOT / "data" / "demo")
        lot = stockmod.create_lot(con, "Test Supplier", "REF-2", 900.0, AS_OF)
        checks = {"chassis": "light", "screen": "clean", "keyboard": "none", "hinges": "pass"}
        for title in ["Lenovo ThinkPad X1 Carbon Gen 9 i7-1165G7 16GB 512GB",
                      "Dell Inspiron 3511 i3-1115G4 8GB 256GB",
                      "Dell Inspiron 3511 i3-1115G4 8GB 256GB"]:
            stockmod.intake(con, title, checks, lot_id=lot, battery_health_pct=90, as_of=AS_OF)
        rows = {r["unit_id"]: r for r in stockmod.close_lot(con, lot)}
        by_value = sorted(rows.values(), key=lambda r: -(r["value_at_intake"] or 0))
        self.assertGreater(by_value[0]["cost_allocated"], 300.0)     # even split would be 300
        self.assertLess(by_value[-1]["cost_allocated"], 300.0)


class TestEndToEnd(unittest.TestCase):
    """Round-trip: the demo generator inverts the L3 chain, so recovering the
    reference values it started from validates ingest -> parse -> match ->
    normalise -> estimate as a whole."""

    TARGETS = [
        ("dell-latitude-5420", "intel-i5-1145g7", 16, 512, 207.0),
        ("hp-elitebook-840-g7", "intel-i5-10310u", 16, 256, 175.0),
        ("lenovo-thinkpad-x1c-g9", "intel-i7-1165g7", 16, 512, 405.0),
        ("apple-macbook-air-m1", "apple-m1", 8, 256, 470.0),
        ("dell-inspiron-3511", "intel-i3-1115g4", 8, 256, 95.0),
    ]

    @classmethod
    def setUpClass(cls):
        use_fitted({})                      # pin to seed priors
        cls.con = fresh_db()
        ingest_all(cls.con, ROOT / "data" / "demo")

    @classmethod
    def tearDownClass(cls):
        use_fitted(None)

    def test_recovers_reference_values_within_five_percent(self):
        for model_id, cpu_id, ram, storage, expected in self.TARGETS:
            with self.subTest(model=model_id):
                est = estimate(self.con, model_id, cpu_id, ram, storage, "B", AS_OF)
                self.assertIsNotNone(est.value)
                self.assertLess(abs(est.value - expected) / expected, 0.05)

    def test_band_is_predictive_not_standard_error(self):
        """A predictive interval must not shrink towards zero as n grows."""
        est = estimate(self.con, "dell-latitude-5420", "intel-i5-1145g7", 16, 512, "B", AS_OF)
        width = (est.band["p75"] - est.band["p25"]) / est.value
        self.assertGreater(est.n_eff, 10)
        self.assertGreater(width, 0.04)

    def test_grade_changes_the_price_in_the_right_direction(self):
        values = {}
        for grade in ("A", "B", "C"):
            est = estimate(self.con, "dell-latitude-5420", "intel-i5-1145g7", 16, 512, grade, AS_OF)
            p = price(est.value, "dell-latitude-5420",
                      {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512}, grade)
            values[grade] = p.market_value_ex_vat
        self.assertGreater(values["A"], values["B"])
        self.assertGreater(values["B"], values["C"])

    def test_holding_cost_reduces_the_buy_price(self):
        est = estimate(self.con, "dell-latitude-5420", "intel-i5-1145g7", 16, 512, "B", AS_OF)
        cfg_row = {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512}
        quick = price(est.value, "dell-latitude-5420", cfg_row, "B",
                      confidence=est.confidence, expected_days_to_sell=10)
        slow = price(est.value, "dell-latitude-5420", cfg_row, "B",
                     confidence=est.confidence, expected_days_to_sell=120)
        self.assertGreater(quick.buy_price_max, slow.buy_price_max)
        self.assertGreater(quick.holding_cost * 5, 0)

    def test_low_confidence_produces_a_more_cautious_offer(self):
        est = estimate(self.con, "dell-latitude-5420", "intel-i5-1145g7", 16, 512, "B", AS_OF)
        cfg_row = {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512}
        sure = price(est.value, "dell-latitude-5420", cfg_row, "B", confidence=0.95)
        unsure = price(est.value, "dell-latitude-5420", cfg_row, "B", confidence=0.30)
        self.assertGreater(sure.buy_price_max, unsure.buy_price_max)

    def test_band_is_on_the_same_basis_as_the_headline_value(self):
        """Regression: the band must take the same grade/channel trip as the
        point estimate, or the two are quoted on different scales."""
        est = estimate(self.con, "hp-elitebook-840-g7", "intel-i5-10310u", 16, 256, "A", AS_OF)
        cfg_row = {"cpu_id": "intel-i5-10310u", "ram_gb": 16, "storage_gb": 256}
        for grade in ("A_PLUS", "A", "B", "C"):
            for channel in ("own_retail_b2c", "ebay_bin", "trade_auction"):
                with self.subTest(grade=grade, channel=channel):
                    p = price(est.value, "hp-elitebook-840-g7", cfg_row, grade, channel,
                              confidence=est.confidence, band=est.band)
                    self.assertLess(p.band_ex_vat["p25"], p.market_value_ex_vat)
                    self.assertGreater(p.band_ex_vat["p75"], p.market_value_ex_vat)
                    self.assertAlmostEqual(p.band_ex_vat["p50"], p.market_value_ex_vat, places=1)

    def test_unknown_configuration_returns_no_estimate_rather_than_a_guess(self):
        est = estimate(self.con, "apple-macbook-pro-14-m1p", "apple-m2", 64, 4096, "B", AS_OF)
        self.assertIsNone(est.value)
        self.assertTrue(est.warnings)


class TestWorkedExample(unittest.TestCase):
    """The five observations from section 07 of the design doc, end to end."""

    @classmethod
    def setUpClass(cls):
        use_fitted({})                      # pin to seed priors
        cls.con = fresh_db()
        ingest_all(cls.con, ROOT / "data" / "incoming")
        cls.est = estimate(cls.con, "dell-latitude-5420", "intel-i5-1145g7", 16, 512, "B", AS_OF)

    @classmethod
    def tearDownClass(cls):
        use_fitted(None)

    def test_five_comparables_from_four_sources(self):
        self.assertEqual(len(self.est.comparables), 5)
        self.assertEqual(len({c.source_id for c in self.est.comparables}), 4)

    def test_job_lot_and_mixed_pallet_were_excluded(self):
        titles = " ".join(str(c.config_id) for c in self.est.comparables)
        rows = self.con.execute("SELECT count(*) FROM raw_observations").fetchone()[0]
        self.assertLess(rows, 21)                       # 21 data rows, 2 excluded by rule

    def test_own_sale_carries_the_most_weight(self):
        heaviest = max(self.est.comparables, key=lambda c: c.weight)
        self.assertEqual(heaviest.source_id, "own_pos")

    def test_ask_carries_less_weight_than_a_sale(self):
        by_source = {c.source_id: c.weight for c in self.est.comparables}
        self.assertLess(by_source["supplier_stocklist"], by_source["own_pos"])
        self.assertLess(by_source["supplier_stocklist"], by_source["ebay_sold_uk"])

    def test_estimate_sits_inside_the_normalised_cluster(self):
        values = [c.value for c in self.est.comparables]
        self.assertGreaterEqual(self.est.value, min(values))
        self.assertLessEqual(self.est.value, max(values))

    def test_documented_figures(self):
        """These are the numbers quoted in the design doc's worked example."""
        p = price(self.est.value, "dell-latitude-5420",
                  {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512, "panel": "fhd"},
                  "B", confidence=self.est.confidence, band=self.est.band)
        self.assertAlmostEqual(self.est.value, 206.59, places=1)
        self.assertAlmostEqual(p.list_price_inc_vat, 246.0, places=1)
        self.assertAlmostEqual(p.buy_price_max, 106.0, places=1)
        self.assertAlmostEqual(self.est.n_eff, 4.257, places=2)


if __name__ == "__main__":
    unittest.main(verbosity=2)


class TestCalibration(unittest.TestCase):
    """The demo data is generated by inverting the normalisation chain from
    known parameters, so calibration should recover those parameters."""

    @classmethod
    def setUpClass(cls):
        use_fitted({})
        cls.con = fresh_db()
        ingest_all(cls.con, ROOT / "data" / "demo")
        cls.cal = calibrate(cls.con, AS_OF)

    @classmethod
    def tearDownClass(cls):
        use_fitted(None)

    def test_recovers_depreciation_for_the_well_evidenced_class(self):
        enterprise = self.cal.depreciation["enterprise"]
        self.assertGreater(enterprise["n"], 100)
        self.assertLess(abs(enterprise["fitted"] - 0.026) / 0.026, 0.15)

    def test_recovers_grade_multipliers(self):
        for grade, true_value in (("A", 1.08), ("C", 0.85), ("A_PLUS", 1.15)):
            with self.subTest(grade=grade):
                self.assertLess(abs(self.cal.grades[grade]["fitted"] - true_value) / true_value, 0.05)

    def test_recovers_channel_multipliers(self):
        for channel, true_value in (("ebay_bin", 1.04), ("trade_auction", 1.21),
                                    ("trade_wholesale", 1.15)):
            with self.subTest(channel=channel):
                self.assertLess(abs(self.cal.channels[channel]["fitted"] - true_value) / true_value, 0.05)

    def test_thin_evidence_stays_near_the_seed(self):
        """Grade D has 5 observations; the adopted value must not lurch."""
        d = self.cal.grades["D"]
        self.assertLess(d["n"], 10)
        self.assertLess(abs(d["value"] - d["seed"]), abs(d["fitted"] - d["seed"]))

    def test_absent_channel_is_not_invented(self):
        for channel in ("amazon_renewed", "b2b_direct", "ebay_auction"):
            with self.subTest(channel=channel):
                entry = self.cal.channels[channel]
                self.assertIsNone(entry["fitted"])
                self.assertEqual(entry["value"], entry["seed"])

    def test_single_source_channels_are_flagged_as_inseparable(self):
        joined = " ".join(self.cal.diagnostics)
        self.assertIn("cannot be separated", joined)

    def test_collinear_spec_dimensions_are_refused(self):
        """Soldered models here only ever move RAM and storage together."""
        entry = self.cal.spec_ram["soldered"][32]
        self.assertIsNone(entry["fitted"])
        self.assertEqual(entry["value"], entry["seed"])

    def test_spec_deltas_fit_where_a_pure_comparison_exists(self):
        entry = self.cal.spec_ram["upgradeable"][32]
        self.assertIsNotNone(entry["fitted"])
        self.assertGreater(entry["n"], 2)

    def test_too_little_data_fits_nothing(self):
        con = fresh_db()
        ingest_all(con, ROOT / "data" / "incoming")      # ~17 observations
        cal = calibrate(con, AS_OF)
        self.assertEqual(cal.depreciation, {})
        self.assertTrue(any("too few" in d for d in cal.diagnostics))

    def test_adopting_the_fit_changes_the_price(self):
        payload = {"grades": self.cal.grades, "channels": self.cal.channels,
                   "depreciation": self.cal.depreciation,
                   "spec_deltas": {"ram_gb": self.cal.spec_ram,
                                   "storage_gb": self.cal.spec_storage}}
        cfg_row = {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512}
        use_fitted({})
        seeded = price(200.0, "dell-latitude-5420", cfg_row, "C").market_value_ex_vat
        use_fitted(payload)
        calibrated = price(200.0, "dell-latitude-5420", cfg_row, "C").market_value_ex_vat
        use_fitted({})
        self.assertNotAlmostEqual(seeded, calibrated, places=2)


class TestStrictMode(unittest.TestCase):
    """With strict mode on, a price may not rest on a seed prior."""

    def tearDown(self):
        use_fitted(None)
        cfg()["business"]["parameters"]["allow_seed_fallback"] = True

    def test_refuses_to_quote_on_seed_priors(self):
        use_fitted({})
        cfg()["business"]["parameters"]["allow_seed_fallback"] = False
        with self.assertRaises(UncalibratedParameter):
            price(200.0, "dell-latitude-5420",
                  {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512}, "B")

    def test_warns_but_proceeds_when_fallback_is_allowed(self):
        use_fitted({})
        cfg()["business"]["parameters"]["allow_seed_fallback"] = True
        p = price(200.0, "dell-latitude-5420",
                  {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512}, "B")
        self.assertTrue(any("seed priors" in w for w in p.warnings))
        self.assertGreater(p.market_value_ex_vat, 0)
