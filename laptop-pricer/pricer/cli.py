"""L6 - explanation. Every quote ships with its comparables, its adjustment
waterfall and its warnings. An unexplainable price gets overridden and ignored
by staff (design doc s02)."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys

from . import stock as stockmod
from .calibrate import calibrate, write as write_calibration
from .commercial import UncalibratedParameter, price
from .config import ROOT, cfg, strict_mode
from .db import connect, reset
from .estimate import estimate
from .ingest import ingest_all
from .match import config_key, verdict
from .parse import parse_title

RULE = "─" * 78


def _date(value: str | None) -> dt.date | None:
    return dt.datetime.strptime(value, "%Y-%m-%d").date() if value else None


def _money(v) -> str:
    return "     -" if v is None else f"£{v:,.2f}"


# ---------- commands ----------

def cmd_ingest(args):
    con = reset() if args.reset else connect()
    totals = ingest_all(con, ROOT / args.incoming if args.incoming else None)
    print(f"{'source : file':<46}{'read':>6}{'excl':>6}{'dup':>6}{'review':>8}{'loaded':>8}")
    print(RULE)
    agg = {"read": 0, "excluded": 0, "duplicate": 0, "review": 0, "loaded": 0}
    for key, s in totals.items():
        print(f"{key:<46}{s['read']:>6}{s['excluded']:>6}{s['duplicate']:>6}{s['review']:>8}{s['loaded']:>8}")
        for k in agg:
            agg[k] += s[k]
    print(RULE)
    print(f"{'total':<46}{agg['read']:>6}{agg['excluded']:>6}{agg['duplicate']:>6}"
          f"{agg['review']:>8}{agg['loaded']:>8}")
    if agg["review"]:
        print(f"\n{agg['review']} row(s) queued for review - run `review` to see them. "
              "Nothing is dropped silently.")


def cmd_quote(args):
    con = connect()
    as_of = _date(args.as_of) or dt.date.today()
    spec = parse_title(args.title)
    if not (spec.model_id and spec.cpu_id and spec.ram_gb and spec.storage_gb):
        print(f"Could not resolve a configuration from:\n  {args.title}")
        print(f"  parsed: {json.dumps(spec.as_dict(), indent=2, default=str)}")
        print("\nAdd the model to catalog/models.csv or the CPU alias to catalog/cpus.csv.")
        return 2

    est = estimate(con, spec.model_id, spec.cpu_id, spec.ram_gb, spec.storage_gb, args.grade, as_of)
    print(RULE)
    print(f"  {args.title}")
    print(f"  {spec.model_id}  ·  {spec.cpu_id}  ·  {spec.ram_gb}GB / {spec.storage_gb}GB"
          f"  ·  grade {args.grade}  ·  as of {as_of}")
    print(RULE)

    if est.value is None:
        print("  No comparable evidence.")
        for w in est.warnings:
            print(f"  ! {w}")
        return 1

    cfg_row = {"cpu_id": spec.cpu_id, "ram_gb": spec.ram_gb,
               "storage_gb": spec.storage_gb, "panel": spec.panel}
    try:
        p = price(est.value, spec.model_id, cfg_row, args.grade, args.channel,
                  confidence=est.confidence, expected_days_to_sell=args.days, band=est.band)
    except UncalibratedParameter as exc:
        print(f"  Refusing to quote.\n  {exc}")
        return 4

    print(f"  Market value (ex-VAT)   {_money(p.market_value_ex_vat):>12}")
    print(f"  List price (inc VAT)    {_money(p.list_price_inc_vat):>12}")
    print(f"  Max buy price           {_money(p.buy_price_max):>12}")
    print(f"  Parts value (floor)     {_money(p.parts_value):>12}")
    print()
    b = p.band_ex_vat or est.band
    print(f"  Band ex-VAT   P10 {_money(b['p10'])}   P25 {_money(b['p25'])}"
          f"   P75 {_money(b['p75'])}   P90 {_money(b['p90'])}")
    print(f"  Confidence    {est.confidence:.2f}   tier {est.tier}   n_eff {est.n_eff}"
          f"   alpha {est.alpha}   sources {len({c.source_id for c in est.comparables})}")

    print(f"\n  Adjustment waterfall")
    for label, value in p.waterfall:
        print(f"    {label:<52}{_money(value):>12}")

    print(f"\n  Comparables used ({len(est.comparables)})")
    print(f"    {'source':<20}{'sold':<12}{'raw grade':<11}{'value':>10}{'weight':>9}  factors")
    for c in sorted(est.comparables, key=lambda c: -c.weight):
        f = c.parts
        factors = (f"trust {f['trust']:.2f} x time {f['time']:.2f} x grade {f['grade']:.2f}"
                   f" x qty {f['qty']:.2f} x cpu {f['cpu']:.2f} x spec {f['spec']:.2f}"
                   f"  ({f['days']}d)")
        print(f"    {c.source_id:<20}{str(c.sold_at):<12}{c.grade_obs:<11}"
              f"{c.value:>10.2f}{c.weight:>9.4f}  {factors}")

    print(f"\n  Parameters applied")
    for name, key, value, prov in p.parameters:
        mark = "·" if prov.startswith("fitted") else "!"
        print(f"    {mark} {name + ' [' + key + ']':<42}{value:>9.4f}   {prov}")

    for w in est.warnings + p.warnings:
        print(f"\n  ! {w}")
    print()


def cmd_intake(args):
    con = connect()
    checks = json.loads(args.checks) if args.checks else {
        "chassis": "light", "screen": "clean", "keyboard": "none", "hinges": "pass"}
    try:
        res = stockmod.intake(con, args.title, checks, lot_id=args.lot, serial=args.serial,
                             service_tag=args.service_tag, battery_health_pct=args.battery,
                             as_of=_date(args.as_of))
    except stockmod.HardStop as e:
        print(f"HARD STOP - {e}\nBook in as salvage; do not spend refurb time on it.")
        return 3
    print(f"{res['unit_id']}  {res['config_id']}")
    print(f"  grade {res['grade']}   defects {res['defects'] or 'none'}")
    if res["pricing"]:
        p = res["pricing"]
        print(f"  market {_money(p.market_value_ex_vat)} ex-VAT   list {_money(p.list_price_inc_vat)} inc VAT"
              f"   max buy {_money(p.buy_price_max)}")
    else:
        print("  no valuation - no comparable evidence yet")


def cmd_stock(args):
    con = connect()
    rep = stockmod.ageing(con, _date(args.as_of))
    t = rep["totals"]
    print(RULE)
    print(f"  STOCK OVERVIEW   as of {rep['as_of']}")
    print(RULE)
    print(f"  Units in stock {t['units']:>8}        Landed cost   {_money(t['cost']):>12}")
    print(f"  Market value   {_money(t['value']):>12}    Unrealised margin {_money(t['margin']):>12}"
          f"  ({t['margin']/t['value']:.1%})" if t["value"] else "")
    print(f"  Value burn     {_money(t['burn']):>12}/mo  — {_money(t['burn']/30.4):>10} every day")
    print()
    print(f"  {'age':<16}{'units':>7}{'landed':>12}{'value':>12}{'margin':>12}{'burn/mo':>10}   state")
    print(RULE)
    for b in rep["buckets"]:
        print(f"  {b['bucket']:<16}{b['units']:>7}{_money(b['cost']):>12}{_money(b['value']):>12}"
              f"{_money(b['margin']):>12}{_money(b['burn']):>10}   {b['state']}")
    print(RULE)
    print(f"  {'all stock':<16}{t['units']:>7}{_money(t['cost']):>12}{_money(t['value']):>12}"
          f"{_money(t['margin']):>12}{_money(t['burn']):>10}")


def cmd_actions(args):
    con = connect()
    queue = stockmod.action_queue(con, _date(args.as_of))
    if not queue:
        print("Nothing to act on - every unit is on track.")
        return
    print(f"  {'unit':<8}{'model':<24}{'grade':<8}{'days':>5}{'landed':>10}{'value':>10}"
          f"{'margin':>8}  {'signal':<44}action")
    print(RULE + "─" * 44)
    for e in queue:
        margin = f"{e.margin_pct:.0%}" if e.margin_pct is not None else "   -"
        print(f"  {e.unit_id:<8}{e.model_id[:23]:<24}{e.grade:<8}{e.days_held:>5}"
              f"{e.cost_landed:>10.2f}{(e.value_current or 0):>10.2f}{margin:>8}  "
              f"{e.signal[:43]:<44}{e.action}")


def cmd_revalue(args):
    con = connect()
    print(stockmod.revalue_all(con, _date(args.as_of)))


def cmd_review(args):
    con = connect()
    rows = con.execute("""SELECT id, stage, reason, payload FROM review_queue
                          WHERE status='open' ORDER BY id""").fetchall()
    if not rows:
        print("Review queue is empty.")
        return
    for rid, stage, reason, payload in rows:
        data = json.loads(payload)
        title = next((v for k, v in data.items() if "title" in k.lower()
                      or "desc" in k.lower() or "model" in k.lower()), "?")
        print(f"  #{rid}  [{stage}] {reason}\n        {title}")
    print(f"\n{len(rows)} open. Each resolution should be written back to catalog/aliases.csv "
          "so the parser learns it permanently.")


def cmd_calibrate(args):
    """Fit the pricing parameters from your own observations."""
    con = connect()
    cal = calibrate(con, _date(args.as_of))
    print(RULE)
    print(f"  CALIBRATION   {cal.observations} observations   as of {cal.as_of}")
    print(RULE)

    def table(title, rows, fmt="{:.4f}"):
        print(f"\n  {title}")
        print(f"    {'parameter':<22}{'seed':>9}{'fitted':>10}{'n':>7}{'used':>10}   note")
        for key, v in rows.items():
            fit = fmt.format(v["fitted"]) if v["fitted"] is not None else "—"
            print(f"    {str(key):<22}{fmt.format(v['seed']):>9}{fit:>10}{v['n']:>7}"
                  f"{fmt.format(v['value']):>10}   {v['note']}")

    if not cal.depreciation and not cal.grades:
        for d in cal.diagnostics:
            print(f"  ! {d}")
        return 1

    table("Depreciation (monthly lambda)", cal.depreciation)
    table("Grade multipliers", cal.grades)
    table("Channel multipliers", cal.channels)
    for kind, tbl in (("RAM", cal.spec_ram), ("Storage", cal.spec_storage)):
        for group in ("upgradeable", "soldered"):
            table(f"{kind} deltas — {group} chassis", tbl[group], "{:.3f}")

    if cal.diagnostics:
        print()
        for d in cal.diagnostics:
            print(f"  ! {d}")

    if args.write:
        path = write_calibration(cal)
        print(f"\n  Written to {path}. The engine now uses these instead of the seeds.")
    else:
        print("\n  Nothing written. Re-run with --write to adopt these values.")


def cmd_params(args):
    """Show every parameter that moves a price, and where it came from."""
    from .config import catalog, cfg, channel_multiplier, grade_multiplier, lambda_for, spec_delta_table
    print(RULE)
    print(f"  PARAMETERS IN USE   strict mode: {'ON' if strict_mode() else 'off'}")
    print(RULE)
    print(f"  {'parameter':<40}{'value':>10}   provenance")
    seeds = 0
    rows = []
    for grade in cfg()["grades"]["ladder"]:
        v, prov = grade_multiplier(grade, with_provenance=True)
        if v is not None:
            rows.append((f"grade multiplier [{grade}]", v, prov))
    for channel in cfg()["channels"]["ladder"]:
        v, prov = channel_multiplier(channel, with_provenance=True)
        rows.append((f"channel multiplier [{channel}]", v, prov))
    for bc in cfg()["depreciation"]["by_build_class"]:
        v, prov = lambda_for({"model_id": "-", "build_class": bc}, with_provenance=True)
        rows.append((f"depreciation lambda [{bc}]", v, prov))
    for kind in ("ram_gb", "storage_gb"):
        for group in ("upgradeable", "soldered"):
            _t, prov = spec_delta_table(kind, group)
            rows.append((f"spec deltas [{kind}/{group}]", float("nan"), prov))

    for name, value, prov in rows:
        mark = "·" if prov.startswith("fitted") else "!"
        seeds += 0 if prov.startswith("fitted") else 1
        shown = "     —" if value != value else f"{value:>10.4f}"
        print(f"  {mark} {name:<38}{shown}   {prov}")
    print(RULE)
    print(f"  {len(rows) - seeds} fitted from your data · {seeds} still on seed priors")
    if seeds:
        print("  Run `pricer calibrate --write` after loading more of your own files.")


def main(argv=None):
    ap = argparse.ArgumentParser(prog="pricer", description="Laptop pricing and stock engine")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("ingest", help="load pricing files using their source profiles")
    p.add_argument("--reset", action="store_true", help="rebuild the warehouse from scratch")
    p.add_argument("--incoming", help="directory of files to ingest (default data/incoming)")
    p.set_defaults(fn=cmd_ingest)

    p = sub.add_parser("quote", help="price a machine from a free-text description")
    p.add_argument("title")
    p.add_argument("--grade", default="B")
    p.add_argument("--channel", default=None)
    p.add_argument("--as-of", default=None)
    p.add_argument("--days", type=float, default=None, help="expected days to sell")
    p.set_defaults(fn=cmd_quote)

    p = sub.add_parser("intake", help="book a physical machine into stock")
    p.add_argument("title")
    p.add_argument("--lot"); p.add_argument("--serial"); p.add_argument("--service-tag")
    p.add_argument("--battery", type=int); p.add_argument("--as-of")
    p.add_argument("--checks", help='JSON, e.g. {"chassis":"visible","screen":"marks"}')
    p.set_defaults(fn=cmd_intake)

    p = sub.add_parser("calibrate", help="fit pricing parameters from your own data")
    p.add_argument("--write", action="store_true", help="adopt the fitted values")
    p.add_argument("--as-of", default=None)
    p.set_defaults(fn=cmd_calibrate)

    p = sub.add_parser("params", help="show every parameter and where it came from")
    p.set_defaults(fn=cmd_params)

    for name, fn, helptext in [("stock", cmd_stock, "stock overview and ageing report"),
                               ("actions", cmd_actions, "the morning action queue"),
                               ("revalue", cmd_revalue, "re-price all held stock"),
                               ("review", cmd_review, "rows needing a human decision")]:
        p = sub.add_parser(name, help=helptext)
        p.add_argument("--as-of", default=None)
        p.set_defaults(fn=fn)

    args = ap.parse_args(argv)
    return args.fn(args) or 0


if __name__ == "__main__":
    sys.exit(main())
