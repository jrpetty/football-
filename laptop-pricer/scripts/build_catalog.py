"""Generate catalog/cpus.csv and catalog/models.csv from family definitions.

Kept as a generator rather than hand-typed rows so attributes stay consistent
across a family: every EliteBook 840 gets the same chassis and upgradeability
rules, and adding a generation is one line.

Two honesty notes, both repeated in the file headers:
  - bench_score is an ORDINAL used for ranking and for pricing a board. It is
    derived from generation, tier and segment, not copied from a benchmark
    suite. Replace it with real PassMark figures when you have them.
  - launch_rrp is approximate and is only used for a sanity guardrail.
"""
from __future__ import annotations

import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------- CPUs
# base score per generation for a mid-tier U-series part
GEN_BASE = {6: 3700, 7: 4300, 8: 6400, 85: 7000, 10: 7300, 105: 8600,
            11: 10200, 12: 13500, 13: 15600, 14: 16800}
TIER_MULT = {"i3": 0.62, "i5": 1.0, "i7": 1.12, "i9": 1.30,
             "r3": 0.66, "r5": 1.02, "r7": 1.18, "r9": 1.34}
SEG_MULT = {"U": 1.0, "P": 1.45, "H": 2.05, "HK": 2.25, "HQ": 1.5, "G": 1.0}

INTEL = [
    # (gen_key, tier, [(sku, segment)])
    (6, "i5", [("6200U", "U"), ("6300U", "U")]), (6, "i7", [("6500U", "U"), ("6600U", "U"), ("6820HQ", "HQ")]),
    (7, "i5", [("7200U", "U"), ("7300U", "U")]), (7, "i7", [("7500U", "U"), ("7600U", "U"), ("7820HQ", "HQ")]),
    (8, "i5", [("8250U", "U"), ("8350U", "U")]), (8, "i7", [("8550U", "U"), ("8650U", "U"), ("8750H", "H")]),
    (85, "i5", [("8265U", "U"), ("8365U", "U")]), (85, "i7", [("8565U", "U"), ("8665U", "U"), ("8850H", "H")]),
    (10, "i3", [("10110U", "U")]),
    (10, "i5", [("10210U", "U"), ("10310U", "U"), ("1035G1", "G"), ("1035G4", "G"),
                ("1035G7", "G"), ("10300H", "H")]),
    (10, "i7", [("10510U", "U"), ("10610U", "U"), ("10710U", "U"), ("1065G7", "G"),
                ("10750H", "H"), ("10850H", "H")]),
    (11, "i3", [("1115G4", "G")]),
    (11, "i5", [("1135G7", "G"), ("1145G7", "G"), ("11300H", "H"), ("11400H", "H")]),
    (11, "i7", [("1165G7", "G"), ("1185G7", "G"), ("11800H", "H"), ("11850H", "H")]),
    (12, "i3", [("1215U", "U")]),
    (12, "i5", [("1235U", "U"), ("1245U", "U"), ("12500H", "H"), ("12450H", "H")]),
    (12, "i7", [("1255U", "U"), ("1265U", "U"), ("12700H", "H"), ("12800H", "H")]),
    (13, "i5", [("1335U", "U"), ("1345U", "U"), ("13500H", "H"), ("13420H", "H")]),
    (11, "i9", [("11900H", "H")]), (12, "i9", [("12900H", "HK")]), (13, "i9", [("13900H", "HK")]),
    (13, "i7", [("1355U", "U"), ("1365U", "U"), ("13700H", "H"), ("13800H", "H")]),
]
AMD = [
    (8, "r3", [("3200U", "U")]), (8, "r5", [("3500U", "U")]), (8, "r7", [("3700U", "U")]),
    (10, "r3", [("4300U", "U")]), (10, "r5", [("4500U", "U"), ("4650U", "U")]),
    (10, "r7", [("4700U", "U"), ("4750U", "U"), ("4800H", "H")]),
    (11, "r3", [("5400U", "U")]), (11, "r5", [("5500U", "U"), ("5650U", "U")]),
    (11, "r7", [("5700U", "U"), ("5800U", "U"), ("5850U", "U"), ("5800H", "H")]),
    (12, "r5", [("6600U", "U"), ("6650U", "U")]), (12, "r7", [("6800U", "U"), ("6850U", "U")]),
]
GEN_YEAR = {6: 2015, 7: 2016, 8: 2018, 85: 2019, 10: 2019, 105: 2020,
            11: 2021, 12: 2022, 13: 2023, 14: 2024}
APPLE_CPUS = [
    ("apple-m1", "Apple M1", "M1|Apple M1 8-core|M1 chip", 8, 20, 2020, 14800),
    ("apple-m1-pro", "Apple M1 Pro", "M1 Pro|Apple M1 Pro", 10, 30, 2021, 21200),
    ("apple-m1-max", "Apple M1 Max", "M1 Max|Apple M1 Max", 10, 40, 2021, 21800),
    ("apple-m2", "Apple M2", "M2|Apple M2 8-core|M2 chip", 8, 20, 2022, 16800),
    ("apple-m2-pro", "Apple M2 Pro", "M2 Pro|Apple M2 Pro", 12, 30, 2023, 24500),
    ("apple-m3", "Apple M3", "M3|Apple M3 8-core|M3 chip", 8, 20, 2023, 19500),
]


def build_cpus() -> list[dict]:
    rows: list[dict] = []
    for spec, brand in ((INTEL, "intel"), (AMD, "amd")):
        for gen, tier, skus in spec:
            for sku, seg in skus:
                score = round(GEN_BASE[gen] * TIER_MULT[tier] * SEG_MULT[seg] / 10) * 10
                if brand == "intel":
                    name, cpu_id = f"Intel Core {tier}-{sku}", f"intel-{tier}-{sku.lower()}"
                    aliases = [f"{tier}-{sku}", f"{tier} {sku}", f"Core {tier} {sku}", f"{tier}-{sku[:4]}"]
                else:
                    label = {"r3": "3", "r5": "5", "r7": "7", "r9": "9"}[tier]
                    name = f"AMD Ryzen {label} {sku}"
                    cpu_id = f"amd-ryzen{label}-{sku.lower()}"
                    aliases = [f"Ryzen {label} {sku}", f"R{label}-{sku}", f"Ryzen{label} {sku}", sku]
                cores = 2 if gen <= 7 else (4 if seg in {"U", "G"} else 8)
                rows.append({"cpu_id": cpu_id, "canonical": name, "aliases": "|".join(aliases),
                             "cores": cores, "tdp": 15 if seg in {"U", "G", "P"} else 45,
                             "launch_year": GEN_YEAR[gen], "bench_score": score})

    for cpu_id, name, aliases, cores, tdp, year, score in APPLE_CPUS:
        rows.append({"cpu_id": cpu_id, "canonical": name, "aliases": aliases, "cores": cores,
                     "tdp": tdp, "launch_year": year, "bench_score": score})

    # generation medians, so "i5 11th gen" in a title is still usable evidence
    for gen, tier in [(10, "i5"), (10, "i7"), (11, "i5"), (11, "i7"),
                      (12, "i5"), (12, "i7"), (8, "i5"), (8, "i7")]:
        label = {10: "10th", 11: "11th", 12: "12th", 8: "8th"}[gen]
        score = round(GEN_BASE[gen] * TIER_MULT[tier] / 10) * 10
        rows.append({
            "cpu_id": f"intel-gen{gen}-{tier}-median",
            "canonical": f"Intel {label} Gen Core {tier} (generation median)",
            "aliases": f"{tier} {label} Gen|{label} Gen {tier}|{tier} {label}",
            "cores": 4, "tdp": 15, "launch_year": GEN_YEAR[gen], "bench_score": score})
    return rows


# ---------------------------------------------------------------- models
# family: (brand, family, build_class, supply_class, chassis, ram_upg, sto_upg,
#          cert, panel, port, parts, [(id, name, generation, year, rrp)])
E, W, P, B, G, A = ("enterprise", "workstation", "premium_consumer",
                    "budget_consumer", "gaming", "apple")
LEASE, RETAIL = "lease_return_heavy", "retail_only"
MIL = "MIL-STD-810"

FAMILIES = [
    ("Dell", "Latitude", E, LEASE, "magnesium_alloy", 1, 1, MIL, "ips", "thunderbolt", "abundant", [
        ("dell-latitude-5400", "Latitude 5400", "8th Gen", 2019, 1049),
        ("dell-latitude-5410", "Latitude 5410", "10th Gen", 2020, 1079),
        ("dell-latitude-5420", "Latitude 5420", "11th Gen", 2021, 1099),
        ("dell-latitude-5430", "Latitude 5430", "12th Gen", 2022, 1149),
        ("dell-latitude-5440", "Latitude 5440", "13th Gen", 2023, 1199),
        ("dell-latitude-5520", "Latitude 5520", "11th Gen", 2021, 1149),
        ("dell-latitude-5530", "Latitude 5530", "12th Gen", 2022, 1199),
        ("dell-latitude-3410", "Latitude 3410", "10th Gen", 2020, 749),
        ("dell-latitude-3420", "Latitude 3420", "11th Gen", 2021, 779),
    ]),
    ("Dell", "Latitude", E, LEASE, "carbon_fibre", 0, 1, MIL, "ips", "thunderbolt", "abundant", [
        ("dell-latitude-7400", "Latitude 7400", "8th Gen", 2019, 1449),
        ("dell-latitude-7410", "Latitude 7410", "10th Gen", 2020, 1479),
        ("dell-latitude-7420", "Latitude 7420", "11th Gen", 2021, 1499),
        ("dell-latitude-7430", "Latitude 7430", "12th Gen", 2022, 1549),
        ("dell-latitude-7440", "Latitude 7440", "13th Gen", 2023, 1599),
        ("dell-latitude-9420", "Latitude 9420", "11th Gen", 2021, 1899),
        ("dell-latitude-9430", "Latitude 9430", "12th Gen", 2022, 1949),
    ]),
    ("Dell", "Precision", W, LEASE, "magnesium_alloy", 1, 1, MIL, "ips", "thunderbolt", "moderate", [
        ("dell-precision-3550", "Precision 3550", "10th Gen", 2020, 1399),
        ("dell-precision-3560", "Precision 3560", "11th Gen", 2021, 1449),
        ("dell-precision-3570", "Precision 3570", "12th Gen", 2022, 1499),
        ("dell-precision-5550", "Precision 5550", "10th Gen", 2020, 1999),
        ("dell-precision-5560", "Precision 5560", "11th Gen", 2021, 2099),
        ("dell-precision-7550", "Precision 7550", "10th Gen", 2020, 2399),
        ("dell-precision-7560", "Precision 7560", "11th Gen", 2021, 2499),
    ]),
    ("Dell", "XPS", P, RETAIL, "aluminium", 0, 1, "", "uhd", "thunderbolt", "moderate", [
        ("dell-xps-13-9300", "XPS 13 9300", "10th Gen", 2020, 1299),
        ("dell-xps-13-9310", "XPS 13 9310", "11th Gen", 2020, 1399),
        ("dell-xps-13-9315", "XPS 13 9315", "12th Gen", 2022, 1099),
        ("dell-xps-15-9500", "XPS 15 9500", "10th Gen", 2020, 1799),
        ("dell-xps-15-9510", "XPS 15 9510", "11th Gen", 2021, 1899),
        ("dell-xps-15-9520", "XPS 15 9520", "12th Gen", 2022, 1949),
    ]),
    ("Dell", "Inspiron", B, RETAIL, "abs", 1, 1, "", "tn", "usb_c_data", "moderate", [
        ("dell-inspiron-3511", "Inspiron 3511", "11th Gen", 2021, 449),
        ("dell-inspiron-3520", "Inspiron 3520", "12th Gen", 2022, 479),
        ("dell-inspiron-5510", "Inspiron 5510", "11th Gen", 2021, 649),
        ("dell-inspiron-5620", "Inspiron 5620", "12th Gen", 2022, 699),
    ]),
    ("HP", "EliteBook", E, LEASE, "aluminium", 1, 1, MIL, "ips", "thunderbolt", "abundant", [
        ("hp-elitebook-840-g5", "EliteBook 840 G5", "8th Gen", 2018, 1199),
        ("hp-elitebook-840-g6", "EliteBook 840 G6", "8th Gen", 2019, 1229),
        ("hp-elitebook-840-g7", "EliteBook 840 G7", "10th Gen", 2020, 1249),
        ("hp-elitebook-840-g8", "EliteBook 840 G8", "11th Gen", 2021, 1299),
        ("hp-elitebook-840-g9", "EliteBook 840 G9", "12th Gen", 2022, 1349),
        ("hp-elitebook-840-g10", "EliteBook 840 G10", "13th Gen", 2023, 1399),
        ("hp-elitebook-830-g6", "EliteBook 830 G6", "8th Gen", 2019, 1149),
        ("hp-elitebook-830-g7", "EliteBook 830 G7", "10th Gen", 2020, 1179),
        ("hp-elitebook-830-g8", "EliteBook 830 G8", "11th Gen", 2021, 1219),
        ("hp-elitebook-850-g6", "EliteBook 850 G6", "8th Gen", 2019, 1299),
        ("hp-elitebook-850-g7", "EliteBook 850 G7", "10th Gen", 2020, 1329),
        ("hp-elitebook-850-g8", "EliteBook 850 G8", "11th Gen", 2021, 1379),
    ]),
    ("HP", "ProBook", E, LEASE, "abs", 1, 1, "", "ips", "usb_c_data", "abundant", [
        ("hp-probook-440-g6", "ProBook 440 G6", "8th Gen", 2019, 749),
        ("hp-probook-440-g7", "ProBook 440 G7", "10th Gen", 2020, 779),
        ("hp-probook-440-g8", "ProBook 440 G8", "11th Gen", 2021, 809),
        ("hp-probook-440-g9", "ProBook 440 G9", "12th Gen", 2022, 849),
        ("hp-probook-450-g7", "ProBook 450 G7", "10th Gen", 2020, 799),
        ("hp-probook-450-g8", "ProBook 450 G8", "11th Gen", 2021, 829),
        ("hp-probook-450-g9", "ProBook 450 G9", "12th Gen", 2022, 869),
    ]),
    ("HP", "ZBook", W, LEASE, "aluminium", 1, 1, MIL, "ips", "thunderbolt", "moderate", [
        ("hp-zbook-firefly-14-g7", "ZBook Firefly 14 G7", "10th Gen", 2020, 1499),
        ("hp-zbook-firefly-14-g8", "ZBook Firefly 14 G8", "11th Gen", 2021, 1549),
        ("hp-zbook-power-g7", "ZBook Power G7", "10th Gen", 2020, 1699),
        ("hp-zbook-studio-g8", "ZBook Studio G8", "11th Gen", 2021, 2199),
    ]),
    ("HP", "Spectre", P, RETAIL, "aluminium", 0, 1, "", "oled", "thunderbolt", "scarce", [
        ("hp-spectre-x360-13", "Spectre x360 13", "11th Gen", 2021, 1299),
        ("hp-spectre-x360-14", "Spectre x360 14", "12th Gen", 2022, 1399),
    ]),
    ("HP", "Pavilion", B, RETAIL, "abs", 1, 1, "", "ips", "usb_c_data", "moderate", [
        ("hp-pavilion-15", "Pavilion 15", "11th Gen", 2021, 599),
        ("hp-pavilion-14", "Pavilion 14", "11th Gen", 2021, 549),
    ]),
    ("Lenovo", "ThinkPad", E, LEASE, "carbon_fibre", 0, 1, MIL, "ips", "thunderbolt", "abundant", [
        ("lenovo-thinkpad-x1c-g6", "ThinkPad X1 Carbon Gen 6", "8th Gen", 2018, 1599),
        ("lenovo-thinkpad-x1c-g7", "ThinkPad X1 Carbon Gen 7", "8th Gen", 2019, 1649),
        ("lenovo-thinkpad-x1c-g8", "ThinkPad X1 Carbon Gen 8", "10th Gen", 2020, 1699),
        ("lenovo-thinkpad-x1c-g9", "ThinkPad X1 Carbon Gen 9", "11th Gen", 2021, 1729),
        ("lenovo-thinkpad-x1c-g10", "ThinkPad X1 Carbon Gen 10", "12th Gen", 2022, 1799),
        ("lenovo-thinkpad-x1c-g11", "ThinkPad X1 Carbon Gen 11", "13th Gen", 2023, 1849),
        ("lenovo-thinkpad-x1y-g6", "ThinkPad X1 Yoga Gen 6", "11th Gen", 2021, 1799),
    ]),
    ("Lenovo", "ThinkPad", E, LEASE, "magnesium_alloy", 1, 1, MIL, "ips", "thunderbolt", "abundant", [
        ("lenovo-thinkpad-t480", "ThinkPad T480", "8th Gen", 2018, 1099),
        ("lenovo-thinkpad-t490", "ThinkPad T490", "8th Gen", 2019, 1149),
        ("lenovo-thinkpad-t14-g1", "ThinkPad T14 Gen 1", "10th Gen", 2020, 1199),
        ("lenovo-thinkpad-t14-g2", "ThinkPad T14 Gen 2", "11th Gen", 2021, 1249),
        ("lenovo-thinkpad-t14-g3", "ThinkPad T14 Gen 3", "12th Gen", 2022, 1299),
        ("lenovo-thinkpad-t15-g2", "ThinkPad T15 Gen 2", "11th Gen", 2021, 1299),
        ("lenovo-thinkpad-l14-g1", "ThinkPad L14 Gen 1", "10th Gen", 2020, 849),
        ("lenovo-thinkpad-l14-g2", "ThinkPad L14 Gen 2", "11th Gen", 2021, 879),
        ("lenovo-thinkpad-l15-g2", "ThinkPad L15 Gen 2", "11th Gen", 2021, 899),
        ("lenovo-thinkpad-e14-g2", "ThinkPad E14 Gen 2", "11th Gen", 2021, 749),
        ("lenovo-thinkpad-e14-g3", "ThinkPad E14 Gen 3", "11th Gen", 2021, 769),
        ("lenovo-thinkpad-x13-g1", "ThinkPad X13 Gen 1", "10th Gen", 2020, 1099),
        ("lenovo-thinkpad-x13-g2", "ThinkPad X13 Gen 2", "11th Gen", 2021, 1149),
    ]),
    ("Lenovo", "ThinkPad", W, LEASE, "magnesium_alloy", 1, 1, MIL, "ips", "thunderbolt", "moderate", [
        ("lenovo-thinkpad-p14s-g2", "ThinkPad P14s Gen 2", "11th Gen", 2021, 1499),
        ("lenovo-thinkpad-p15s-g2", "ThinkPad P15s Gen 2", "11th Gen", 2021, 1549),
        ("lenovo-thinkpad-p1-g4", "ThinkPad P1 Gen 4", "11th Gen", 2021, 2299),
    ]),
    ("Lenovo", "ThinkBook", P, RETAIL, "aluminium", 1, 1, "", "ips", "usb_c_data", "moderate", [
        ("lenovo-thinkbook-14-g2", "ThinkBook 14 Gen 2", "11th Gen", 2021, 749),
        ("lenovo-thinkbook-15-g2", "ThinkBook 15 Gen 2", "11th Gen", 2021, 799),
    ]),
    ("Lenovo", "Legion", G, RETAIL, "abs", 1, 1, "", "ips", "usb_c_data", "moderate", [
        ("lenovo-legion-5-15", "Legion 5 15", "2021", 2021, 1099),
        ("lenovo-legion-5-pro-16", "Legion 5 Pro 16", "2021", 2021, 1399),
        ("lenovo-legion-7-16", "Legion 7 16", "2021", 2021, 1799),
    ]),
    ("Lenovo", "IdeaPad", B, RETAIL, "abs", 1, 1, "", "ips", "usb_c_data", "moderate", [
        ("lenovo-ideapad-3-15", "IdeaPad 3 15", "11th Gen", 2021, 449),
        ("lenovo-ideapad-5-14", "IdeaPad 5 14", "11th Gen", 2021, 599),
        ("lenovo-yoga-slim-7", "Yoga Slim 7", "11th Gen", 2021, 899),
    ]),
    ("Apple", "MacBook Air", A, RETAIL, "aluminium", 0, 0, "", "ips", "thunderbolt", "moderate", [
        ("apple-macbook-air-2019", "MacBook Air 2019", "Intel", 2019, 1099),
        ("apple-macbook-air-2020-intel", "MacBook Air 2020 Intel", "Intel", 2020, 999),
        ("apple-macbook-air-m1", "MacBook Air M1", "M1", 2020, 999),
        ("apple-macbook-air-m2", "MacBook Air M2", "M2", 2022, 1249),
        ("apple-macbook-air-m3", "MacBook Air M3", "M3", 2024, 1099),
    ]),
    ("Apple", "MacBook Pro", A, RETAIL, "aluminium", 0, 0, "", "uhd", "thunderbolt", "scarce", [
        ("apple-macbook-pro-13-2019", "MacBook Pro 13 2019", "Intel", 2019, 1299),
        ("apple-macbook-pro-13-2020", "MacBook Pro 13 2020", "Intel", 2020, 1299),
        ("apple-macbook-pro-13-m1", "MacBook Pro 13 M1", "M1", 2020, 1299),
        ("apple-macbook-pro-13-m2", "MacBook Pro 13 M2", "M2", 2022, 1349),
        ("apple-macbook-pro-14-m1p", "MacBook Pro 14 M1 Pro", "M1 Pro", 2021, 1899),
        ("apple-macbook-pro-16-m1p", "MacBook Pro 16 M1 Pro", "M1 Pro", 2021, 2399),
        ("apple-macbook-pro-14-m2p", "MacBook Pro 14 M2 Pro", "M2 Pro", 2023, 1999),
    ]),
    ("Microsoft", "Surface", P, LEASE, "magnesium_alloy", 0, 0, "", "ips", "usb_c_data", "scarce", [
        ("microsoft-surface-laptop-3", "Surface Laptop 3", "10th Gen", 2019, 999),
        ("microsoft-surface-laptop-4", "Surface Laptop 4", "11th Gen", 2021, 1049),
        ("microsoft-surface-laptop-5", "Surface Laptop 5", "12th Gen", 2022, 1099),
        ("microsoft-surface-pro-7", "Surface Pro 7", "10th Gen", 2019, 899),
        ("microsoft-surface-pro-8", "Surface Pro 8", "11th Gen", 2021, 1099),
    ]),
    ("Asus", "ZenBook", P, RETAIL, "aluminium", 0, 1, "", "oled", "thunderbolt", "scarce", [
        ("asus-zenbook-14", "ZenBook 14", "11th Gen", 2021, 899),
        ("asus-zenbook-13-oled", "ZenBook 13 OLED", "11th Gen", 2021, 999),
    ]),
    ("Asus", "ROG", G, RETAIL, "aluminium", 1, 1, "", "ips", "usb_c_data", "scarce", [
        ("asus-rog-zephyrus-g14", "ROG Zephyrus G14", "2021", 2021, 1499),
        ("asus-tuf-gaming-a15", "TUF Gaming A15", "2021", 2021, 999),
    ]),
    ("Acer", "Aspire", B, RETAIL, "abs", 1, 1, "", "ips", "usb_c_data", "moderate", [
        ("acer-aspire-5", "Aspire 5", "11th Gen", 2021, 499),
        ("acer-swift-3", "Swift 3", "11th Gen", 2021, 649),
        ("acer-nitro-5", "Nitro 5", "2021", 2021, 899),
    ]),
]

SUCCESSORS = {}


def build_models() -> list[dict]:
    rows: list[dict] = []
    for (brand, family, build_class, supply, chassis, ram_u, sto_u,
         cert, panel, port, parts, models) in FAMILIES:
        ordered = sorted(models, key=lambda m: m[3])
        for i, (model_id, name, generation, year, rrp) in enumerate(ordered):
            successor = ordered[i + 1][0] if i + 1 < len(ordered) else ""
            rows.append({
                "model_id": model_id, "brand": brand, "family": family, "model_name": name,
                "generation": generation, "release_date": f"{year}-01-01", "launch_rrp": rrp,
                "build_class": build_class, "supply_class": supply, "chassis_material": chassis,
                "ram_upgradeable": ram_u, "storage_upgradeable": sto_u,
                "durability_cert": cert, "panel_class": panel, "port_class": port,
                "parts_availability": parts, "successor_model_id": successor,
            })
    return rows


CPU_HEADER = """# GENERATED by scripts/build_catalog.py - edit the script, not this file.
# bench_score is an ORDINAL for ranking and for pricing a board, derived from
# generation, tier and segment. It is NOT copied from a benchmark suite.
# Replace with real PassMark figures when you have them.
"""
MODEL_HEADER = """# GENERATED by scripts/build_catalog.py - edit the script, not this file.
# release_date is the family's launch year (day/month are placeholders).
# launch_rrp is approximate and only feeds a sanity guardrail.
# The attributes that matter for pricing - build_class, supply_class,
# ram_upgradeable, storage_upgradeable - are set per family and are the ones
# worth correcting first if any look wrong for your stock.
"""


def write(path: Path, header: str, rows: list[dict]) -> None:
    with open(path, "w", newline="") as fh:
        fh.write(header)
        writer = csv.DictWriter(fh, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    cpus, models = build_cpus(), build_models()
    ids = [m["model_id"] for m in models]
    assert len(ids) == len(set(ids)), "duplicate model_id"
    write(ROOT / "catalog" / "cpus.csv", CPU_HEADER, cpus)
    write(ROOT / "catalog" / "models.csv", MODEL_HEADER, models)
    print(f"  {len(cpus)} CPUs, {len(models)} models written")
    print(f"  aliases: {sum(len(c['aliases'].split('|')) for c in cpus)} CPU spellings recognised")


if __name__ == "__main__":
    main()
