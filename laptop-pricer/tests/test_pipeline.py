"""Unit tests for the pricing maths and the stock book."""
from __future__ import annotations

import datetime as dt
import math
import os
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
from pricer.inspect import inspect, propose_profile
from pricer.parts import teardown
from pricer.reading import compose_title, find_header_row, read_rows, to_date, to_number
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


SAMPLE_CSV = ROOT / "data" / "samples" / "Refurb Sales Export (Aug).csv"
SAMPLE_XLSX = ROOT / "data" / "samples" / "supplier_list.xlsx"


class TestTolerantReading(unittest.TestCase):
    """Files arrive as they are, not as we would like them."""

    def test_prices_however_they_are_written(self):
        for text, expected in [("£1,234.56", 1234.56), ("1.234,56", 1234.56),
                               ("(99.00)", -99.0), ("1 234", 1234.0), ("$450", 450.0),
                               ("€1.299,00", 1299.0), ("285", 285.0)]:
            with self.subTest(text=text):
                self.assertAlmostEqual(to_number(text), expected, places=2)

    def test_rubbish_is_not_coerced_to_a_number(self):
        for text in ("", "n/a", "-", "TBC", None):
            self.assertIsNone(to_number(text))

    def test_dates_in_every_common_convention(self):
        target = dt.date(2026, 8, 19)
        for text in ("2026-08-19", "19/08/2026", "19 Aug 2026", "19-Aug-26", "20260819", 46253):
            with self.subTest(text=text):
                self.assertEqual(to_date(text), target)

    def test_finds_the_header_under_a_report_banner(self):
        rows, meta = read_rows(SAMPLE_CSV)
        self.assertEqual(meta["header_row"], 4)
        self.assertIn("Item Description", meta["columns"])

    def test_drops_blank_and_total_rows(self):
        rows, meta = read_rows(SAMPLE_CSV)
        self.assertEqual(meta["skipped_total_rows"], 1)
        self.assertGreaterEqual(meta["skipped_blank"], 1)
        self.assertEqual(len(rows), 9)
        self.assertFalse(any("TOTAL" in str(v).upper() for r in rows for v in r.values()))

    def test_xlsx_reads_identically_to_csv(self):
        csv_rows, csv_meta = read_rows(SAMPLE_CSV)
        xls_rows, xls_meta = read_rows(SAMPLE_XLSX)
        self.assertEqual(len(csv_rows), len(xls_rows))
        self.assertEqual(csv_meta["columns"], xls_meta["columns"])


class TestInspection(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.insp = inspect(SAMPLE_CSV)

    def test_identifies_every_column_that_matters(self):
        self.assertEqual(self.insp.mapping["raw_title"], "Item Description")
        self.assertEqual(self.insp.mapping["price_gross"], "Sold For")
        self.assertEqual(self.insp.mapping["sold_at"], "Inv Date")
        self.assertEqual(self.insp.mapping["grade_raw"], "Cond.")
        self.assertEqual(self.insp.mapping["quantity"], "Qty")

    def test_leaves_irrelevant_columns_alone(self):
        assigned = set(self.insp.mapping.values())
        self.assertNotIn("VAT Rate", assigned)
        self.assertNotIn("Cust Ref", assigned)

    def test_detects_currency_and_date_convention(self):
        self.assertEqual(self.insp.currency, "GBP")
        self.assertEqual(self.insp.date_format, "%d/%m/%Y")

    def test_reports_how_much_of_the_file_is_recognised(self):
        r = self.insp.recognition
        self.assertEqual(r["tested"], 9)
        self.assertGreaterEqual(r["resolved"], 6)
        self.assertIn("unknown model", r["reasons"])

    def test_names_the_rows_it_could_not_resolve(self):
        titles = [t for t, _ in self.insp.unresolved]
        self.assertTrue(any("WonderBook" in t for t in titles))

    def test_proposed_profile_maps_your_grade_words(self):
        profile = propose_profile(self.insp, "test_source")
        gm = profile["grade_map"]
        self.assertEqual(gm["Grade B"], "B")
        self.assertEqual(gm["Grade A"], "A")
        self.assertEqual(gm["Mint"], "A_PLUS")
        self.assertEqual(gm["Very Good"], "A")
        self.assertEqual(gm["Spares or Repair"], "SALVAGE")

    def test_profile_flags_what_it_cannot_infer(self):
        profile = propose_profile(self.insp, "test_source")
        self.assertIn("CONFIRM", profile["price"]["vat_treatment"])


class TestPartsValuation(unittest.TestCase):
    LAT = ("dell-latitude-5420",
           {"cpu_id": "intel-i5-1145g7", "ram_gb": 16, "storage_gb": 512,
            "panel": "fhd", "screen_in": 14.0})
    MAC = ("apple-macbook-air-m1",
           {"cpu_id": "apple-m1", "ram_gb": 8, "storage_gb": 256,
            "panel": "ips", "screen_in": 13.3})

    def test_every_component_gets_its_own_value(self):
        td = teardown(*self.LAT, grade="C", battery_health_pct=74)
        names = {p.component for p in td.parts}
        self.assertEqual(names, {"panel", "board", "battery", "ram", "storage",
                                 "chassis", "keyboard", "charger"})
        self.assertTrue(all(p.value >= 0 for p in td.parts))

    def test_net_recovery_is_gross_less_labour_and_weee(self):
        td = teardown(*self.LAT, grade="C", battery_health_pct=74)
        self.assertAlmostEqual(td.net, td.gross - td.labour - td.weee, places=2)

    def test_your_own_prices_override_the_formula(self):
        """catalog/parts_prices.csv wins over the seed formula."""
        td = teardown(*self.LAT, grade="C", battery_health_pct=95)
        panel = next(p for p in td.parts if p.component == "panel")
        self.assertNotEqual(panel.source, "seed formula")
        self.assertAlmostEqual(panel.value, 41.00, places=2)

    def test_soldered_memory_has_no_separate_value(self):
        td = teardown(*self.MAC, grade="B", battery_health_pct=90)
        for component in ("ram", "storage"):
            part = next(p for p in td.parts if p.component == component)
            self.assertEqual(part.value, 0.0)
            self.assertIn("soldered", part.basis)

    def test_upgradeable_memory_does_have_a_value(self):
        td = teardown(*self.LAT, grade="C", battery_health_pct=90)
        self.assertGreater(next(p for p in td.parts if p.component == "ram").value, 0)

    def test_a_defect_zeroes_the_component_it_breaks(self):
        td = teardown(*self.LAT, grade="C", battery_health_pct=90, defects=("screen_cracked",))
        panel = next(p for p in td.parts if p.component == "panel")
        self.assertEqual(panel.value, 0.0)
        self.assertFalse(panel.recoverable)

    def test_dead_battery_recovers_nothing(self):
        td = teardown(*self.LAT, grade="C", battery_health_pct=40)
        self.assertEqual(next(p for p in td.parts if p.component == "battery").value, 0.0)

    def test_worse_grade_recovers_less(self):
        good = teardown(*self.LAT, grade="A", battery_health_pct=90).net
        bad = teardown(*self.LAT, grade="D", battery_health_pct=90).net
        self.assertGreater(good, bad)

    def test_missing_charger_removes_its_line(self):
        with_c = teardown(*self.LAT, grade="C", battery_health_pct=90, has_charger=True)
        without = teardown(*self.LAT, grade="C", battery_health_pct=90, has_charger=False)
        self.assertGreater(with_c.net, without.net)

    def test_salvage_floor_matches_the_itemised_breakdown(self):
        """The floor used in pricing and the breakdown shown to a human must agree."""
        from pricer.commercial import parts_value
        from pricer.config import catalog as cat
        model = cat()["models"]["dell-latitude-5420"]
        td = teardown(*self.LAT, grade="C")
        self.assertAlmostEqual(parts_value(model, self.LAT[1], "C"), td.net, places=2)


FIXTURES = ROOT / "tests" / "fixtures"


class TestAwkwardInputs(unittest.TestCase):
    """Real exports that are not a tidy CSV with a description column."""

    def _recognised(self, filename):
        insp = inspect(FIXTURES / filename)
        return insp, insp.recognition

    def test_semicolon_delimited_with_european_decimals(self):
        insp, r = self._recognised("semicolon.csv")
        self.assertEqual(r["resolved"], 1)
        self.assertIn("price_gross", insp.mapping)      # "Preis" found by value shape

    def test_tab_separated(self):
        _, r = self._recognised("tabs.tsv")
        self.assertEqual(r["resolved"], 1)

    def test_structured_columns_with_no_description_column(self):
        """Brand, model, CPU and memory in separate columns - joined into one."""
        insp, r = self._recognised("structured.csv")
        self.assertIsInstance(insp.mapping["raw_title"], list)
        self.assertIn("Brand", insp.mapping["raw_title"])
        self.assertIn("Processor", insp.mapping["raw_title"])
        self.assertEqual(r["resolved"], 3)

    def test_composite_title_adds_units_to_bare_numbers(self):
        row = {"Brand": "Dell", "Model": "Latitude 5420", "RAM": "16", "Storage": "512"}
        title = compose_title(row, ["Brand", "Model", "RAM", "Storage"])
        self.assertIn("16GB", title)
        self.assertIn("512GB", title)

    def test_header_found_under_a_spanning_title_row(self):
        _, r = self._recognised("twinheader.csv")
        self.assertEqual(r["resolved"], 1)

    def test_a_missing_date_column_is_called_out_loudly(self):
        """Undated rows load but can never be used, so this must not pass quietly."""
        insp, _ = self._recognised("nodate.csv")
        self.assertNotIn("sold_at", insp.mapping)
        self.assertTrue(any("NO DATE COLUMN" in w for w in insp.warnings))

    def test_non_laptop_stock_is_rejected_not_priced(self):
        """Monitors, phones and docks must not become laptop comparables."""
        insp, r = self._recognised("mixed_stock.csv")
        self.assertEqual(r["resolved"], 1)
        self.assertEqual(r["reasons"].get("unknown model"), 3)

    def test_workbook_data_on_a_later_sheet(self):
        _, r = self._recognised("multisheet.xlsx")
        self.assertEqual(r["resolved"], 1)

    def test_an_unknown_machine_is_never_silently_priced(self):
        insp, r = self._recognised("mixed_stock.csv")
        titles = [t for t, _ in insp.unresolved]
        self.assertTrue(any("Monitor" in t or "iPhone" in t or "Docking" in t for t in titles))


class TestConnectors(unittest.TestCase):
    """Connectors are exercised against recorded responses, so the suite needs
    neither credentials nor network."""

    EBAY_REPLAY = {
        "identity/v1/oauth2/token": {"access_token": "tok", "expires_in": 7200},
        "item_summary/search": {"itemSummaries": [
            {"itemId": "v1|1", "title": "Dell Latitude 5420 i5-1145G7 16GB 512GB",
             "price": {"value": "289.99", "currency": "GBP"}, "condition": "Very Good",
             "seller": {"username": "refurbco"}, "buyingOptions": ["FIXED_PRICE"],
             "itemCreationDate": "2026-08-14T10:00:00.000Z"},
            {"itemId": "v1|2", "title": "HP EliteBook 840 G7 i5-10310U 16GB 256GB",
             "price": {"value": "242.00", "currency": "GBP"}, "condition": "Good",
             "seller": {"username": "techtrade"}, "buyingOptions": ["FIXED_PRICE"],
             "itemCreationDate": "2026-08-16T09:00:00.000Z"}]}}

    def setUp(self):
        os.environ["PRICER_EBAY__CLIENT_ID"] = "test"
        os.environ["PRICER_EBAY__CLIENT_SECRET"] = "test"

    def tearDown(self):
        os.environ.pop("PRICER_EBAY__CLIENT_ID", None)
        os.environ.pop("PRICER_EBAY__CLIENT_SECRET", None)

    def _browse(self):
        from pricer.connectors import ReplayTransport
        from pricer.connectors.ebay import EbayBrowse
        return EbayBrowse(transport=ReplayTransport(self.EBAY_REPLAY))

    def test_missing_credentials_explain_themselves(self):
        from pricer.connectors.base import MissingCredentials, require
        os.environ.pop("PRICER_EBAY__CLIENT_ID", None)
        os.environ.pop("PRICER_EBAY__CLIENT_SECRET", None)
        with self.assertRaises(MissingCredentials) as ctx:
            require("nosuchsource", "client_id")
        self.assertIn("secrets.yml", str(ctx.exception))

    def test_browse_rows_carry_price_date_and_condition(self):
        c = self._browse()
        rows = c.to_rows(c.fetch(queries=["dell latitude 5420"], max_pages=1))
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["date"], "2026-08-14")
        self.assertEqual(rows[0]["condition"], "VERY_GOOD")
        self.assertAlmostEqual(float(rows[0]["price"]), 289.99, places=2)

    def test_active_listings_are_asks_not_sales(self):
        """The distinction that decides how much this data is worth."""
        c = self._browse()
        profile = c.profile()
        self.assertEqual(profile["observation_type"], "ask")
        self.assertLess(profile["trust"], 0.5)
        self.assertLess(profile["ask_haircut"], 1.0)

    def test_connector_output_parses_through_the_normal_pipeline(self):
        """A connector's job is to produce a file the existing L0 can read."""
        c = self._browse()
        rows = c.to_rows(c.fetch(queries=["x"], max_pages=1))
        spec = parse_title(rows[0]["title"])
        config_id, conf, _ = resolve(spec)
        self.assertEqual(spec.model_id, "dell-latitude-5420")
        self.assertEqual(verdict(conf), "auto")
        mapped = c.profile()["grade_map"][rows[0]["condition"]]
        self.assertEqual(mapped, "A")

    def test_restricted_api_refuses_with_an_alternative(self):
        from pricer.connectors import ReplayTransport
        from pricer.connectors.base import ConnectorError
        from pricer.connectors.ebay import EbaySold
        c = EbaySold(transport=ReplayTransport(self.EBAY_REPLAY))
        with self.assertRaises(ConnectorError) as ctx:
            c.fetch(queries=["dell latitude 5420"])
        message = str(ctx.exception)
        self.assertIn("Limited Release", message)
        self.assertIn("Terapeak", message)

    def test_rate_limiter_survives_a_restart(self):
        from pricer.connectors.base import RateLimiter
        name = "test_restart"
        a = RateLimiter(name, per_day=2)
        a.take()
        b = RateLimiter(name, per_day=2)          # a fresh process
        self.assertEqual(b.remaining(), 1)
        b.take()
        from pricer.connectors.base import ConnectorError
        with self.assertRaises(ConnectorError):
            RateLimiter(name, per_day=2).take()
        (ROOT / "data" / "raw" / f".ratelimit_{name}.json").unlink(missing_ok=True)

    def test_every_connector_declares_what_it_provides(self):
        from pricer.connectors import REGISTRY
        for name, cls in REGISTRY.items():
            with self.subTest(connector=name):
                self.assertIn(cls.observation_type, {"sold", "ask", "offer", "appraisal"})
                self.assertIn(cls.channel, cfg()["channels"]["ladder"])
                self.assertTrue(0 < cls.trust <= 1.0)
                # own-sales connectors are the only ones allowed full trust
                if cls.trust == 1.0:
                    self.assertEqual(cls.observation_type, "sold")


class TestAllConnectors(unittest.TestCase):
    """Every connector exercised against recorded responses."""

    def setUp(self):
        for k, v in {"PRICER_EBAY__CLIENT_ID": "x", "PRICER_EBAY__CLIENT_SECRET": "y",
                     "PRICER_EBAY__REFRESH_TOKEN": "r", "PRICER_EBAY__REDIRECT_URI": "RuName",
                     "PRICER_AMAZON__REFRESH_TOKEN": "r", "PRICER_AMAZON__CLIENT_ID": "i",
                     "PRICER_AMAZON__CLIENT_SECRET": "s",
                     "PRICER_BACKMARKET__TOKEN": "t"}.items():
            os.environ[k] = v

    def tearDown(self):
        for k in list(os.environ):
            if k.startswith("PRICER_"):
                del os.environ[k]

    def test_ebay_seller_orders_split_by_line_item(self):
        """A two-machine order must become two rows, not one expensive laptop."""
        from pricer.connectors import ReplayTransport
        from pricer.connectors.ebay import EbaySellOrders
        t = ReplayTransport({
            "identity/v1/oauth2/token": {"access_token": "tok", "expires_in": 7200},
            "sell/fulfillment/v1/order": {"orders": [{
                "orderId": "05-1", "creationDate": "2026-08-12T10:00:00.000Z",
                "lineItems": [
                    {"title": "Dell Latitude 5420 i5-1145G7 16GB 512GB", "quantity": 1,
                     "lineItemCost": {"value": "285.00", "currency": "GBP"}, "sku": "LAT-B"},
                    {"title": "HP EliteBook 840 G7 i5-10310U 16GB 256GB", "quantity": 1,
                     "lineItemCost": {"value": "232.00", "currency": "GBP"}, "sku": "EB-B"}]}]}})
        c = EbaySellOrders(transport=t)
        rows = c.to_rows(c.fetch(since_days=30))
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["date"], "2026-08-12")
        self.assertEqual(c.observation_type, "sold")
        self.assertEqual(c.trust, 1.0)

    def test_ebay_seller_data_refuses_an_application_token(self):
        """getOrders needs a user token; an app token cannot stand in."""
        from pricer.connectors import ReplayTransport
        from pricer.connectors.base import ConnectorError
        from pricer.connectors.ebay import EbaySellOrders
        del os.environ["PRICER_EBAY__REFRESH_TOKEN"]
        c = EbaySellOrders(transport=ReplayTransport({}))
        with self.assertRaises(ConnectorError) as ctx:
            c.fetch()
        self.assertIn("authorize ebay", str(ctx.exception))

    def test_ebay_consent_url_carries_the_seller_scope(self):
        from pricer.connectors import ReplayTransport
        from pricer.connectors.ebay import EbayAuth
        url = EbayAuth(ReplayTransport({})).consent_url()
        self.assertIn("response_type=code", url)
        self.assertIn("sell.fulfillment", url)

    def test_amazon_orders_unit_price_a_multi_unit_line(self):
        """SP-API reports the line total; pricing it as a unit doubles the value."""
        from pricer.connectors import ReplayTransport
        from pricer.connectors.amazon import AmazonOrders
        t = ReplayTransport({
            "auth/o2/token": {"access_token": "tok", "expires_in": 3600},
            "/orderItems": {"payload": {"OrderItems": [
                {"Title": "Dell Latitude 5420 i5-1145G7 16GB 512GB",
                 "ItemPrice": {"Amount": "570.00", "CurrencyCode": "GBP"},
                 "QuantityOrdered": 2, "SellerSKU": "LAT-B", "ASIN": "B08X",
                 "ConditionId": "UsedGood"}]}},
            "/orders/v0/orders": {"payload": {"Orders": [
                {"AmazonOrderId": "203-1", "PurchaseDate": "2026-08-12T10:00:00Z",
                 "OrderTotal": {"Amount": "570.00", "CurrencyCode": "GBP"}}]}}})
        c = AmazonOrders(transport=t)
        rows = c.to_rows(c.fetch(since_days=30))
        self.assertEqual(len(rows), 1)
        self.assertAlmostEqual(float(rows[0]["price"]), 285.00, places=2)
        self.assertEqual(rows[0]["quantity"], 2)

    def test_amazon_competitive_separates_offers_from_transaction_data(self):
        from pricer.connectors import ReplayTransport
        from pricer.connectors.amazon import AmazonCompetitive
        t = ReplayTransport({
            "auth/o2/token": {"access_token": "tok", "expires_in": 3600},
            "competitiveSummary": {"responses": [{"body": {
                "asin": "B08X", "title": "Dell Latitude 5420 i5-1145G7 16GB 512GB",
                "lowestPricedOffers": [
                    {"listingPrice": {"amount": 299.0, "currencyCode": "GBP"},
                     "condition": "used"}],
                "referencePrices": [
                    {"name": "AverageSellingPrice",
                     "price": {"amount": 271.5, "currencyCode": "GBP"}}]}}]}})
        c = AmazonCompetitive(transport=t)
        rows = c.to_rows(c.fetch(asins=["B08X"]))
        kinds = {r["kind"] for r in rows}
        self.assertIn("offer", kinds)
        self.assertIn("AverageSellingPrice", kinds)

    def test_backmarket_orders_expand_order_lines(self):
        from pricer.connectors import ReplayTransport
        from pricer.connectors.backmarket import BackMarketOrders
        t = ReplayTransport({"/ws/orders": {"results": [{
            "order_id": 991, "date_creation": "2026-08-10T09:00:00Z", "currency": "GBP",
            "orderlines": [
                {"product": "Dell Latitude 5420 i5-1145G7 16GB 512GB", "price": 279.0,
                 "quantity": 1, "state": "good"}]}], "next": None}})
        c = BackMarketOrders(transport=t)
        rows = c.to_rows(c.fetch(since_days=30))
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["date"], "2026-08-10")
        self.assertEqual(c.trust, 1.0)

    def test_every_connector_title_parses_to_a_machine(self):
        """The point of a connector: its output must flow through the pipeline."""
        for title in ["Dell Latitude 5420 i5-1145G7 16GB 512GB",
                      "HP EliteBook 840 G7 i5-10310U 16GB 256GB"]:
            with self.subTest(title=title):
                spec = parse_title(title)
                _cid, conf, _ = resolve(spec)
                self.assertEqual(verdict(conf), "auto")

    def test_marketplace_condition_words_all_map(self):
        """Each marketplace has its own condition vocabulary."""
        from pricer.connectors.ebay import EbayBrowse
        gm = EbayBrowse().profile()["grade_map"]
        for word, expected in [("VERY_GOOD", "A"), ("GOOD", "B"), ("USEDGOOD", "B"),
                               ("USEDLIKENEW", "A_PLUS"), ("USEDVERYGOOD", "A"),
                               ("FOR_PARTS_OR_NOT_WORKING", "SALVAGE")]:
            with self.subTest(word=word):
                self.assertEqual(gm[word], expected)

    def test_expired_token_is_refreshed_not_reused(self):
        from pricer.connectors.base import Token
        self.assertFalse(Token.lasting("t", 30).valid())
        self.assertTrue(Token.lasting("t", 7200).valid())
