"""work.contract-ladder — "What Changed in the Contract?: Ladder".

Ten levels of the same task as work.contract-redline (verification/realworld-work/contract.py), plus settlement: the
prompt shows version 1, the supplier's version 2, the customer's response and the supplier's final note, and asks for
every substantive change between version 1 and the AGREED version, keyed by the agreed numbering. The drafts come from
build_level() below (that generator's clause library, renderer and diff, at ten sizes; L05 is the original's longest
case, L06-L10 add governance and asset clauses with cross-references, a fourth schedule, a second new section, more
moved clauses and re-sorted lists); the response and note come from contract_settle.py (assign / settle / predict).

Run:  python3 contract_ladder.py            writes tests/work/contract-ladder.json and contract-scripted.json
      python3 contract_ladder.py --check    exits 1 unless both files are byte-identical to a fresh build
      python3 contract_ladder.py --stats    prints the growth parameters and sizes per level
"""
import json, os, random, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'realworld-work'))
sys.path.insert(0, os.path.join(HERE, '..'))
import common  # noqa: E402
from wk import people  # noqa: E402
import contract as K  # noqa: E402
import contract_settle as CS  # noqa: E402

TRAP_WEIGHT = K.TRAP_WEIGHT

# Ladder-only clauses (levels 6-10). Each carries one parameter that version 2 may change; [xref:p] is a
# cross-reference whose target clause is the parameter's value (re-pointing it is a "cross_reference" change).
LADDER_LIB = [
    ('gov_meet', 'Governance', 'The parties shall hold a contract review meeting each quarter to review performance against the service level set out in clause [xref:gov_sl_ref].'),
    ('gov_report', 'Governance', 'The Supplier shall deliver a monthly performance report on the matters set out in clause [xref:gov_rep_ref], no later than the tenth Business Day of the following month.'),
    ('gov_days', 'Governance', 'The Customer shall comment on each monthly performance report within [days:gov_comment] of receiving it, failing which the report is treated as accepted.'),
    ('gov_escalate', 'Governance', 'A dispute that is not resolved at a contract review meeting may be referred by either party for resolution under clause [xref:esc_ref].'),
    ('gov_kpi', 'Governance', 'The service level targets in Schedule 2 may be reviewed once in each Contract Year and changed only under clause [xref:kpi_rev_ref].'),
    ('step_in', 'Governance', 'If the Supplier is in material breach of clause [xref:stepin_ref], the Customer may step in and perform the affected Services itself or through a third party at the Supplier’s reasonable cost.'),
    ('gov_rep_party', 'Governance', 'The [party:minutes_party] shall prepare and circulate the minutes of each contract review meeting within five Business Days.'),
    ('asset_care', 'Assets and Equipment', 'The Supplier shall maintain the assets listed in Schedule 4 in accordance with clause [xref:asset_ref] and the manufacturer’s instructions.'),
    ('asset_replace', 'Assets and Equipment', 'The [party:asset_party] shall bear the cost of replacing any asset in Schedule 4 that reaches the end of its working life during the Term.'),
    ('asset_register', 'Assets and Equipment', 'The Supplier [shall:asset_rec_ob] keep an up-to-date asset register for the Sites and give the Customer a copy on request.'),
    ('asset_days', 'Assets and Equipment', 'The Supplier shall report any asset that fails a statutory inspection within [hours:asset_fail] of the inspection.'),
    ('asset_spares', 'Assets and Equipment', 'The Supplier shall[not:spares_not] hold a stock of critical spare parts for the assets in Schedule 4 at its own cost.'),
    ('asset_new', 'Assets and Equipment', 'Assets installed after the Commencement Date are added to Schedule 4 and maintained under clause [xref:asset_new_ref] from the date of installation.'),
]
LADDER_SECTIONS = {'Governance': 'Service Levels', 'Assets and Equipment': 'Supply of the Services'}  # inserted after
LADDER_PARAMS = dict(gov_sl_ref='avail', gov_rep_ref='credit', gov_comment=10, esc_ref='t_breach', kpi_rev_ref='variation', stepin_ref='standard',
                     minutes_party='Supplier', asset_ref='standard', asset_party='Customer', asset_rec_ob=True, asset_fail=24, spares_not=False, asset_new_ref='asset_care')
# Where a re-pointed cross-reference can go instead (all present on every level that has the clause).
XREF_ALT = dict(gov_sl_ref='credit_cap', gov_rep_ref='avail', esc_ref='t_conv', kpi_rev_ref='increase', stepin_ref='response', asset_ref='personnel', asset_new_ref='asset_register',
                surv_ref='conf_ex', cap_ref='liab_indirect')
LADDER_CHANGEABLE = {'gov_comment': ('days', [10, 15, 20]), 'asset_fail': ('hours', [24, 48, 72])}
SECOND_SECTION = ('Social Value', [
    ('sv_plan', 'Social Value', 'The Supplier shall deliver the social value commitments in its tender, including apprenticeships and spending with local businesses, and report on them in each Contract Year.'),
    ('sv_report', 'Social Value', 'The Supplier shall publish a social value statement within [days:sv_days] of the end of each Contract Year.'),
])
ASSETS = ['Gas boiler', 'Fire alarm panel', 'Passenger lift', 'Air-handling unit', 'Emergency lighting circuit', 'Cold water tank', 'Chiller', 'Standby generator',
          'Sprinkler valve set', 'Roller shutter', 'CCTV recorder', 'Access control panel', 'Lightning protection', 'Fume cupboard', 'Hot water cylinder', 'Pump set']
SUBTLE_LADDER = ['xref_gov_sl', 'xref_gov_rep', 'xref_esc', 'xref_kpi_rev', 'xref_stepin', 'xref_asset', 'xref_asset_new', 'party_minutes', 'party_asset', 'ob_asset_rec', 'neg_spares']
# The clause each subtle change edits (one change per clause, so a value change and a subtle change never collide).
SUBTLE_CLAUSE = dict(party_pl='ins_pl', party_exit='t_exit', ob_ev='ins_evidence', ob_audit='audit', xref_cap='liab_cap', xref_surv='conf_survive', neg_dp='dp_sub',
                     xref_gov_sl='gov_meet', xref_gov_rep='gov_report', xref_esc='gov_escalate', xref_kpi_rev='gov_kpi', xref_stepin='step_in', xref_asset='asset_care',
                     xref_asset_new='asset_new', party_minutes='gov_rep_party', party_asset='asset_replace', ob_asset_rec='asset_register', neg_spares='asset_spares')
SHORT_DROP = ('ins_evidence', 'term_end', 'personnel', 'def_services', 'entire', 'credit_cap', 'def_ci', 'liab_indirect', 'notices', 'assign', 'interest', 'nonsol',
              'dp_breach', 'conf_survive', 'liab_cap', 'ins_pi', 'credit', 'term')

# Growth: (sites, service levels, rate card rows, assets) per schedule; value changes; subtle changes (who must do it,
# shall/may, re-pointed references, a "not"); schedule rows changed; structural edits.
LEVELS = [
    dict(id='L01', resp=dict(accept_all=True), repoint=0, seed=5101, short=True, sched=None, n_val=3, neg=False, law=False, defn=False, subtle=0, n_rows=0, renewal=False, victims=0, new_sections=0, moves=0,
         ladder_lib=False, sites_added=0, sites_deleted=0, assets_added=0, assets_deleted=0, resort=(), heads_upper=True),
    dict(id='L02', resp=dict(accept_all=True, min_reject=1), repoint=0, seed=5102, short=False, sched=None, n_val=5, neg=True, law=False, defn=False, subtle=0, n_rows=0, renewal=True, victims=0, new_sections=0, moves=0,
         ladder_lib=False, sites_added=0, sites_deleted=0, assets_added=0, assets_deleted=0, resort=(), heads_upper=True),
    dict(id='L03', resp=dict(reject=0.2, counter=0.1, none=0.1, min_reject=1), repoint=0, seed=5103, short=False, sched=(6, 4, 5, 0), n_val=6, neg=True, law=False, defn=True, subtle=0, n_rows=2, renewal=True, victims=1, new_sections=0, moves=1,
         ladder_lib=False, sites_added=0, sites_deleted=0, assets_added=0, assets_deleted=0, resort=(), heads_upper=False),
    dict(id='L04', resp=dict(reject=0.2, counter=0.15, none=0.15, withdraw=0.06, correction=0.06, min_reject=2), repoint=1, seed=5104, short=False, extra=True, sched=(10, 6, 8, 0), n_val=6, neg=True, law=True, defn=True, subtle=2, n_rows=3, renewal=True, victims=1, new_sections=0, moves=1,
         ladder_lib=False, sites_added=1, sites_deleted=1, assets_added=0, assets_deleted=0, resort=(), heads_upper=False),
    dict(id='L05', resp=dict(reject=0.2, counter=0.12, none=0.12, withdraw=0.05, correction=0.05, blanket=[(0, 'accept')], exceptions=0.3, reject_sections=1), repoint=2, seed=5105, short=False, extra=True, sched=(34, 12, 16, 0), n_val=10, neg=True, law=True, defn=True, subtle=5, n_rows=8, renewal=True, victims=1, new_sections=1, moves=1,
         ladder_lib=False, sites_added=2, sites_deleted=1, assets_added=0, assets_deleted=0, resort=('rates',), heads_upper=False),
    dict(id='L06', resp=dict(reject=0.22, counter=0.13, none=0.12, withdraw=0.05, correction=0.05, blanket=[(0, 'accept'), (2, 'reject')], exceptions=0.3, reject_sections=1), repoint=3, seed=5106, short=False, extra=True, sched=(44, 12, 16, 30), n_val=12, neg=True, law=True, defn=True, subtle=7, n_rows=12, renewal=True, victims=2, new_sections=1, moves=2,
         ladder_lib=True, sites_added=2, sites_deleted=2, assets_added=1, assets_deleted=1, resort=('rates',), heads_upper=False),
    dict(id='L07', resp=dict(reject=0.24, counter=0.14, none=0.12, withdraw=0.06, correction=0.06, blanket=[(0, 'accept'), (2, 'reject')], exceptions=0.35, reject_sections=1), repoint=4, seed=5107, short=False, extra=True, sched=(60, 12, 16, 70), n_val=14, neg=True, law=True, defn=True, subtle=9, n_rows=17, renewal=True, victims=2, new_sections=2, moves=2,
         ladder_lib=True, sites_added=3, sites_deleted=2, assets_added=2, assets_deleted=1, resort=('rates', 'assets'), heads_upper=False),
    dict(id='L08', resp=dict(reject=0.26, counter=0.15, none=0.12, withdraw=0.06, correction=0.06, blanket=[(0, 'accept'), (3, 'reject')], exceptions=0.35, reject_sections=2), repoint=5, seed=5108, short=False, extra=True, sched=(80, 12, 16, 120), n_val=16, neg=True, law=True, defn=True, subtle=11, n_rows=30, renewal=True, victims=3, new_sections=2, moves=3,
         ladder_lib=True, sites_added=3, sites_deleted=3, assets_added=2, assets_deleted=2, resort=('rates', 'assets'), heads_upper=False),
    dict(id='L09', resp=dict(reject=0.28, counter=0.15, none=0.12, withdraw=0.07, correction=0.06, blanket=[(0, 'accept'), (3, 'reject'), (1, 'accept')], exceptions=0.4, reject_sections=2), repoint=6, seed=5109, short=False, extra=True, sched=(100, 12, 16, 180), n_val=18, neg=True, law=True, defn=True, subtle=12, n_rows=40, renewal=True, victims=3, new_sections=2, moves=4,
         ladder_lib=True, sites_added=4, sites_deleted=3, assets_added=3, assets_deleted=2, resort=('rates', 'assets', 'sites'), heads_upper=False),
    dict(id='L10', resp=dict(reject=0.3, counter=0.16, none=0.12, withdraw=0.07, correction=0.07, blanket=[(0, 'accept'), (3, 'reject'), (1, 'accept')], exceptions=0.4, reject_sections=2), repoint=7, seed=5110, short=False, extra=True, sched=(120, 12, 16, 260), n_val=20, neg=True, law=True, defn=True, subtle=14, n_rows=52, renewal=True, victims=4, new_sections=2, moves=5,
         ladder_lib=True, sites_added=5, sites_deleted=4, assets_added=4, assets_deleted=3, resort=('rates', 'assets', 'sites'), heads_upper=False),
]
MOVABLE = ['liab_excl', 'notices', 'dp_return', 'conf_ex', 'waiver', 'hs']  # clauses that may move verbatim (references follow them)
SITE_V = (1, 2, 4, 6, 8, 12)
KPI_T = (85, 90, 95, 97.5, 98, 99)
RATE_R = (180, 210, 245, 260, 295, 320, 345, 380, 420)
ASSET_V = (1, 2, 4, 6, 12)


def build_level(seed, L):
    rng = random.Random(seed)
    people(rng, 2)
    customer = rng.choice(['Northgate Housing Trust', 'Larkspur Academy Trust', 'Redcliffe Leisure Ltd', 'Pennine Dental Group Ltd', 'Saltmarsh Hotels Ltd', 'Calderbrook NHS Foundation Trust'])
    supplier = rng.choice(['Elmstead Facilities Ltd', 'Corran Workplace Services Ltd', 'Kestrel Property Care Ltd', 'Halden FM Ltd'])
    lib = list(K.LIBRARY) + (K.EXTRA if L.get('extra') else []) + (LADDER_LIB if L['ladder_lib'] else [])
    texts = {k: t for k, _, t in lib + K.NEW_SECTION[1] + SECOND_SECTION[1] + [K.RENEWAL]}
    order = list(K.SEC_ORDER)
    if L['ladder_lib']:
        for sec, after in LADDER_SECTIONS.items():
            order.insert(order.index(after) + 1, sec)
    secs = {s: [] for s in order}
    for k, s, _ in lib:
        secs[s].append(k)
    if L['short']:
        for s in secs:
            secs[s] = [k for k in secs[s] if k not in SHORT_DROP]
    sch_params = {}
    sched = []
    if L['sched']:
        n_site, n_kpi, n_rate, n_asset = L['sched']
        used = set()

        def site_name():
            while True:
                nm = f"{rng.choice(K.PLACES)} {rng.choice(K.SITE_TYPES)}"
                if nm not in used:
                    used.add(nm)
                    return nm
        site_keys = []
        for i in range(n_site):
            k = f"site_{i}"
            texts[k] = f"{site_name()}, {rng.randint(2, 180)} {rng.choice(K.PLACES)} Road: [count:{k}_v] planned visits a month, plus reactive call-outs."
            sch_params[f"{k}_v"] = rng.choice(SITE_V)
            site_keys.append(k)
        kpi_keys = []
        for i, nm in enumerate(rng.sample(K.KPIS, n_kpi)):
            k = f"kpi_{i}"
            texts[k] = f"{nm}: target [pct:{k}_t] of occasions each month" + (" (service credits under clause [ref:credit])." if L['ladder_lib'] else ".")
            sch_params[f"{k}_t"] = rng.choice(KPI_T)
            kpi_keys.append(k)
        rate_keys = []
        for i, nm in enumerate(rng.sample(K.ROLES, n_rate)):
            k = f"rate_{i}"
            texts[k] = f"{nm}: [money:{k}_r] per day (8 hours), additional hours pro rata."
            sch_params[f"{k}_r"] = rng.choice(RATE_R)
            rate_keys.append(k)
        sched = [('Schedule: Sites', site_keys), ('Schedule: Service levels', kpi_keys), ('Schedule: Rate card', rate_keys)]
        for j in range(L['sites_added']):
            k = f"site_x{j}"
            texts[k] = f"{rng.choice(K.PLACES)} {rng.choice(['Annexe', 'Store', 'Hub', 'Pavilion', 'Lodge'])}, {rng.randint(2, 180)} {rng.choice(K.PLACES)} Road: [count:{k}_v] planned visits a month, plus reactive call-outs."
            sch_params[f"{k}_v"] = rng.choice((1, 2, 4))
        if n_asset:
            site_names = [texts[k].split(',')[0] for k in site_keys]
            asset_keys = []
            codes = set()
            for i in range(n_asset + L['assets_added']):
                while True:
                    code = f"{rng.choice('ABCDEFGHJKLMNPRSTW')}-{rng.randint(100, 999)}"
                    if code not in codes:
                        codes.add(code)
                        break
                k = f"asset_{i}" if i < n_asset else f"asset_x{i - n_asset}"
                texts[k] = f"{rng.choice(ASSETS)} {code} at {rng.choice(site_names)}: [count:{k}_v] planned services a year, maintained under clause [ref:asset_care]."
                sch_params[f"{k}_v"] = rng.choice(ASSET_V)
                if i < n_asset:
                    asset_keys.append(k)
            sched.append(('Schedule: Asset register', asset_keys))
    params = {p: rng.choice(ch) for p, (u, ch) in K.CHANGEABLE.items()}
    params.update(subk=True, withhold=False, law='England and Wales', bd_region='England', bh_start='09:00', bh_end='17:30', charges_vat=False,
                  bc_restore=24, renew=12, renew_notice=90, pl_party='Supplier', exit_party='Supplier', ev_ob=True, audit_ob=True, dp_sub_not=True,
                  surv_ref='conf', cap_ref='liab_excl', sv_days=90)
    params.update(LADDER_PARAMS)
    params.update(sch_params)
    if rng.random() < 0.5:
        params['subk'] = False
    v1 = K.Version(params, [(s, list(secs[s])) for s in order if secs[s]] + [(n, list(ks)) for n, ks in sched])
    p2 = dict(params)
    s2 = [(s, list(ks)) for s, ks in v1.sections]

    def sec(name):
        return next(ks for s, ks in s2 if s == name)
    present = {k for _, ks in s2 for k in ks}
    changeable = dict(K.CHANGEABLE, **(LADDER_CHANGEABLE if L['ladder_lib'] else {}))
    pool = [p for p in changeable if any(f':{p}]' in texts[k] for k in present)]
    rng.shuffle(pool)
    if L['ladder_lib'] and 'conf_years' in pool:  # the upper levels carry every kind of change, a period in years included
        pool.remove('conf_years')
        pool.insert(0, 'conf_years')
    touched = set()
    for p in pool:
        if len(touched) >= L['n_val']:
            break
        clause = next(k for k in present if f':{p}]' in texts[k])
        if clause in touched:
            continue
        touched.add(clause)
        p2[p] = rng.choice([c for c in changeable[p][1] if c != params[p]])
    assert len(touched) == L['n_val'], (L['id'], len(touched))
    if L['neg']:
        p2[rng.choice(['subk', 'withhold'])] ^= True
    if L['law']:
        p2['law'] = rng.choice([x for x in K.LAWS if x != params['law']])
    if L['defn']:
        dch = rng.choice(['bh', 'vat', 'region'])
        if dch == 'bh':
            p2['bh_start'], p2['bh_end'] = rng.choice([('08:00', '18:00'), ('08:30', '17:00'), ('09:00', '17:00')])
        elif dch == 'vat':
            p2['charges_vat'] = True
        else:
            p2['bd_region'] = rng.choice(['the United Kingdom', 'Scotland'])
    opts = ['party_pl', 'party_exit', 'ob_ev', 'ob_audit', 'xref_cap', 'xref_surv', 'neg_dp']
    opts = [o for o in opts if L.get('extra') or o not in ('ob_audit', 'neg_dp')]
    rng.shuffle(opts)
    if L['ladder_lib']:
        lad = list(SUBTLE_LADDER)
        rng.shuffle(lad)
        opts = opts + lad
    # Exactly L['repoint'] re-pointed cross-references, the rest of the subtle changes from the other kinds.
    picked = []
    for want, pool in ((L['repoint'], [o for o in opts if o.startswith('xref_')]), (L['subtle'] - L['repoint'], [o for o in opts if not o.startswith('xref_')])):
        got = 0
        for o in pool:
            clause = SUBTLE_CLAUSE[o]
            if got < want and clause in present and clause not in touched:
                picked.append(o)
                touched.add(clause)
                got += 1
        if got != want:
            raise ValueError(f"{L['id']}: only {got} of {want} subtle changes fit")
    for o in picked:
        if o == 'party_pl':
            p2['pl_party'] = 'Customer'
        elif o == 'party_exit':
            p2['exit_party'] = 'Customer'
        elif o == 'ob_ev':
            p2['ev_ob'] = False
        elif o == 'ob_audit':
            p2['audit_ob'] = False
        elif o == 'xref_cap':
            p2['cap_ref'] = XREF_ALT['cap_ref']
        elif o == 'xref_surv':
            p2['surv_ref'] = XREF_ALT['surv_ref']
        elif o == 'neg_dp':
            p2['dp_sub_not'] = False
        elif o.startswith('xref_'):
            par = {'xref_gov_sl': 'gov_sl_ref', 'xref_gov_rep': 'gov_rep_ref', 'xref_esc': 'esc_ref', 'xref_kpi_rev': 'kpi_rev_ref', 'xref_stepin': 'stepin_ref',
                   'xref_asset': 'asset_ref', 'xref_asset_new': 'asset_new_ref'}[o]
            p2[par] = XREF_ALT[par]
        elif o == 'party_minutes':
            p2['minutes_party'] = 'Customer'
        elif o == 'party_asset':
            p2['asset_party'] = 'Supplier'
        elif o == 'ob_asset_rec':
            p2['asset_rec_ob'] = False
        elif o == 'neg_spares':
            p2['spares_not'] = True
    if L['n_rows']:
        rowp = [k for k in sch_params if '_x' not in k]
        for k in rng.sample(rowp, L['n_rows']):
            vals = SITE_V if k.startswith('site_') else ASSET_V if k.startswith('asset_') else KPI_T if k.endswith('_t') else RATE_R
            p2[k] = rng.choice([x for x in vals if x != params[k]])
    v2 = K.Version(p2, s2)
    noise = []
    if L['renewal']:
        t = sec('Term')
        t.insert(t.index('term') + 1 if 'term' in t else 0, 'renewal')
    if L['victims']:
        targets = {v for p, v in list(params.items()) + list(p2.items()) if p.endswith('_ref')}
        changed = {k for k in present for kind, arg in re.findall(r'\[(\w+):(\w+)\]', texts[k]) if kind != 'ref' and params.get(arg) != p2.get(arg)}
        cands = [k for k in ('personnel', 'nonsol', 'ins_evidence', 't_exit', 'counterparts', 'third', 'gov_days', 'tupe', 'severance', 'bribery', 'fm', 'ins_notify') if k in present and k not in changed and k not in targets]
        for victim in rng.sample(cands, L['victims']):
            for _, ks in s2:
                if victim in ks:
                    ks.remove(victim)
        s2[:] = [(s, ks) for s, ks in s2 if ks]
    if L['new_sections'] >= 1:
        i = [s for s, _ in s2].index('Insurance')
        s2.insert(i, (K.NEW_SECTION[0], [k for k, _, _ in K.NEW_SECTION[1]]))
    if L['new_sections'] >= 2:
        s2.insert(1, (SECOND_SECTION[0], [k for k, _, _ in SECOND_SECTION[1]]))
    if L['moves']:
        movers = [k for k in MOVABLE if any(k in ks for _, ks in s2)]
        if L['moves'] == 1 and L['new_sections'] == 0:
            chosen = ['notices'] if 'notices' in present else movers[:1]
        else:
            chosen = ['liab_excl'] + rng.sample([m for m in movers if m != 'liab_excl'], L['moves'] - 1)
        for m in chosen:
            home = next(ks for _, ks in s2 if m in ks)
            last = home[-1] == m
            home.remove(m)
            if m == 'notices' and L['new_sections'] == 0:
                sec('Confidentiality').append(m)  # moved to another section, word for word
            elif last:
                home.insert(0, m)  # moved to the start of its own section
            else:
                home.append(m)  # moved to the end of its own section
            noise.append(f'{m} moved')
    if L['sched']:
        sites = sec('Schedule: Sites')
        for j in range(L['sites_added']):
            if j == 0:
                sites.insert(rng.randint(1, len(sites) - 1), 'site_x0')  # a site added mid-list renumbers the rest
            else:
                sites.append(f'site_x{j}')
        for victim in rng.sample([s for s in sites[:max(3, len(sites) // 3)] if not s.startswith('site_x') and p2[s + '_v'] == params[s + '_v']], L['sites_deleted']):
            sites.remove(victim)
        if 'rates' in L['resort']:
            rc = sec('Schedule: Rate card')
            rc.sort(key=lambda k: texts[k])
            noise.append('rate card resorted')
        if 'sites' in L['resort']:
            sites.sort(key=lambda k: texts[k])
            noise.append('sites resorted')
        if L['sched'][3]:
            assets = sec('Schedule: Asset register')
            for j in range(L['assets_added']):
                assets.insert(rng.randint(1, len(assets) - 1), f'asset_x{j}')
            for victim in rng.sample([a for a in assets if not a.startswith('asset_x') and p2[a + '_v'] == params[a + '_v']], L['assets_deleted']):
                assets.remove(victim)
            if 'assets' in L['resort']:
                assets.sort(key=lambda k: texts[k])
                noise.append('assets resorted')
    title1 = f"SERVICES AGREEMENT between {customer} (the Customer) and {supplier} (the Supplier) — version 1 (draft sent {rng.choice(['3 March', '14 April', '9 June'])})"
    title2 = f"SERVICES AGREEMENT between {customer} (the Customer) and {supplier} (the Supplier) — version 2 (Supplier's revised draft)"
    t1, n1 = K.render(v1, texts, K.STYLE1, title1)
    t2, n2 = K.render(v2, texts, dict(K.STYLE2, heads='upper') if L['heads_upper'] else K.STYLE2, title2)
    key = K.diff(v1, v2, texts, n1, n2)
    for c in key.values():
        for f in ('old', 'new'):
            if isinstance(c[f], float) and c[f].is_integer():
                c[f] = int(c[f])
    for num, c in key.items():
        assert c['type'] in ('added', 'deleted') or c['old'] != c['new'] or c['old'] is None, (num, c)
    # Every re-pointed reference really points at a different clause, and every unchanged reference still reads as
    # renumbered (so the noise is live): its target's number differs between the drafts on the upper levels.
    for num, c in key.items():
        if c['type'] == 'cross_reference':
            assert c['old'] != c['new'], (num, c)
    return dict(v1=v1, v2=v2, texts=texts, t1=t1, t2=t2, n1=n1, n2=n2, key=key, customer=customer, supplier=supplier, noise=noise)


def check_unique(b):
    """Independent second check of the key from the rendered TEXT: every clause whose normalised words differ
    between the drafts (after mapping cross-references to the clause they point at) is in the key, and every key
    entry is such a clause. Normalisation undoes exactly the documented noise (number words, money and percent
    formats, headings, spelling)."""
    def norm_text(t, numbers_inv):
        t = re.sub(r'clause (S?\d+\.\d+)', lambda m: 'clause <' + numbers_inv[m.group(1)] + '>', t)
        t = re.sub(r'\b(?:[a-z-]+(?: and [a-z-]+)?(?: hundred(?: and [a-z-]+)?)?) \((\d+)\)', r'\1', t)
        t = re.sub(r'£([\d,]+)\.00\b', r'£\1', t)
        t = re.sub(r'(\d(?:\.\d+)?) per cent', r'\1%', t)
        t = t.replace('organization', 'organisation')
        return t.lower()
    inv1 = {v: k for k, v in b['n1'].items()}
    inv2 = {v: k for k, v in b['n2'].items()}
    lines1 = {l.split(' ', 1)[0]: l.split(' ', 1)[1] for l in b['t1'].split('\n') if re.match(r'^S?\d+\.\d+ ', l)}
    lines2 = {l.split(' ', 1)[0]: l.split(' ', 1)[1] for l in b['t2'].split('\n') if re.match(r'^S?\d+\.\d+ ', l)}
    found = set()
    for num2, txt in lines2.items():
        k = inv2[num2]
        if k not in b['n1']:
            found.add(num2)
            continue
        if norm_text(lines1[b['n1'][k]], inv1) != norm_text(txt, inv2):
            found.add(num2)
    for num1 in lines1:
        if inv1[num1] not in b['n2']:
            found.add(num1)
    assert found == set(b['key']), (sorted(found - set(b['key'])), sorted(set(b['key']) - found))


def careless(b):
    out = K.careless(b)['changes']
    # The careless reader also takes a re-sorted site or asset list for changed rows, like the rate card.
    for note, pre in (('sites resorted', 'site_'), ('assets resorted', 'asset_')):
        if note in b['noise']:
            moved = [x for x in b['n2'] if x.startswith(pre) and x + '_v' in b['v2'].params and x in b['n1'] and b['n1'][x] != b['n2'][x] and b['v1'].params.get(x + '_v') == b['v2'].params[x + '_v']][:2]
            for x in moved:
                v = b['v2'].params[x + '_v']
                out[b['n2'][x]] = {'type': 'number', 'old': v, 'new': v}
    return {'changes': out}


def make_levels():
    out = []
    for L in LEVELS:
        for k in range(400):
            try:
                b = build_level(L['seed'] * 10 + k, L)
                check_unique(b)
                break
            except (KeyError, ValueError):
                continue
        else:
            raise AssertionError(L['id'])
        for j in range(200):
            st = CS.assign(b, L['resp'], L['seed'] * 7 + j)
            sA = CS.settle(b, st)
            pred = CS.predict(b, st, sA)
            assert pred == sA['key'], (L['id'], [x for x in pred if pred.get(x) != sA['key'].get(x)] + [x for x in sA['key'] if x not in pred])
            check_unique(dict(b, t2=sA['tA'], n2=sA['nA'], key=sA['key']))
            slips = {f: CS.settle(b, st, frozenset([f]))['key'] for f in ('unanswered_ok', 'counter_always', 'blanket_wins')}
            need = [f for f, on in (('unanswered_ok', L['resp'].get('none')), ('counter_always', L['resp'].get('counter')), ('blanket_wins', L['resp'].get('exceptions'))) if on]
            if L['id'] != 'L01' and sA['key'] == b['key']:
                continue
            if all(slips[f] != sA['key'] for f in need):
                break
        else:
            raise AssertionError(f"{L['id']}: no response layout makes every settlement rule bite")
        v2nums = CS.predict(b, st, dict(sA, nA=b['n2']))
        scripted = dict(careless={'changes': b['key']}, slip_v2_numbering={'changes': v2nums}, slip_unanswered_agreed={'changes': slips['unanswered_ok']},
                        slip_counter_without_note={'changes': slips['counter_always']}, slip_general_beats_specific={'changes': slips['blanket_wins']}, old_careless=careless(b))
        out.append(dict(L=L, b=b, st=st, sA=sA, salt=k, rsalt=j, prompt=CS.prompt(b, st), expected={'changes': sA['key']}, careless={'changes': b['key']}, scripted=scripted))
    return out


# ── weights and serialisation (as verification/realworld-work/build.py does for the contract test) ──
def leaves(x, keys=()):
    if isinstance(x, dict) and x:
        for k, v in x.items():
            yield from leaves(v, keys + (k,))
    else:
        yield keys, x


MISSING = object()


def at(x, keys):
    for k in keys:
        if not isinstance(x, dict) or k not in x:
            return MISSING
        x = x[k]
    return x


def trap_weights(expected, careless_ans, W, sets):
    w = {}
    for keys, v in leaves(expected):
        path = '.'.join(keys)
        if at(careless_ans, keys) != v:
            w[path] = W
        elif keys[0] in sets:
            w[path] = 1
    for sp in sets:
        w[sp] = W
    return w


def growth(c):
    b = c['b']
    types = {}
    for v in b['key'].values():
        types[v['type']] = types.get(v['type'], 0) + 1
    words = len(b['t1'].split()) + len(b['t2'].split())
    renumbered = sum(1 for k in b['n2'] if k in b['n1'] and b['n1'][k] != b['n2'][k])
    st, sA = c['st'], c['sA']
    shifted = sum(1 for k in sA['nA'] if k in b['n2'] and sA['nA'][k] != b['n2'][k])
    return dict(level=c['L']['id'], tokens=common.tok(c['prompt']), words=words, clauses=len(b['n2']), v2_changes=len(b['key']), agreed_changes=len(sA['key']), renumbered=renumbered,
                responses=len(st['resp']) + len(st['blanket']) + len(st['section_reject']), note_items=len(st['counter_ok']) + len(st['withdrawn']) + (1 if st['corrections'] else 0),
                shifted_by_settlement=shifted, repointed=types.get('cross_reference', 0), types=types)


def build(out_root=None):
    cs = make_levels()
    cases = []
    for i, c in enumerate(cs):
        exp = json.loads(json.dumps(c['expected']))
        sc = {'type': 'json', 'lenient': True, 'sets': {'changes': 3}}
        sc['weights'] = trap_weights(exp, json.loads(json.dumps(c['careless'])), TRAP_WEIGHT, ['changes'])
        g = growth(c)
        notes = (f"[level {i + 1}] Generated by verification/ladders-data-support/contract_ladder.py with the clause library, renderer and diff of verification/realworld-work/contract.py; "
                 f"version 2 settled by contract_settle.py; key = diff() of version 1 and the agreed version, re-derived independently by predict() from version 2's own diff and the statuses, and re-checked by check_unique() from the rendered agreed text. {g['words']} words across both drafts, {g['clauses']} clauses and rows in version 2, "
                 f"{g['v2_changes']} changes in version 2, {g['responses']} response items and {g['note_items']} note items, {g['agreed_changes']} agreed changes, {g['shifted_by_settlement']} clauses numbered differently in the agreed version than in version 2."
                 f"{' (generator salt %d)' % c['salt'] if c['salt'] else ''} Fields where a trap sits are worth {TRAP_WEIGHT} points, routine fields 1.")
        cases.append(dict(id=c['L']['id'], prompt=c['prompt'], expected=exp, scorer=sc, notes=notes))
    meta = dict(
        id='work.contract-ladder', category='work', name='What Changed in the Contract?: Ladder', version='1.0.0',
        description=('Ten levels of the same job: two drafts of a services agreement, the customer’s response to the supplier’s version 2 and the supplier’s final note, and the question: what changed from version 1 in the AGREED version? '
                     'Level 1 accepts everything; from level 2 the response rejects changes, makes counter-proposals that count only if the supplier agrees them, leaves changes unanswered (not agreed, unless the supplier lists them as corrections, and then only if not rejected), '
                     'and from level 5 makes general statements about a schedule that specific items override and rejects a whole new section, while the supplier withdraws changes. Every rejected addition or kept deletion renumbers the agreed version, and every change is keyed by its agreed clause number, so each answer depends on the settlement decisions before it. '
                     'Levels 6 to 10 grow to 114 changes in two drafts of 17,500 words, about 80 response items and 27 note items, with hundreds of clauses numbered differently from version 2. Listing a non-change costs as much as missing a change; a level is passed at 90% of its points.'),
        difficulty='extreme', tags=['work', 'ladder', 'legal', 'contracts', 'diff', 'reading', 'real-world'],
        hook='Two drafts, a response and a final note: what was actually agreed, clause by clause, as the contract grows?',
        maxOutputTokens=32000,
        scorer={'type': 'json', 'lenient': True, 'sets': {'changes': 3}},
    )
    meta['estimate'] = {'inputTokens': int(sum(common.tok(c['prompt']) for c in cases) / len(cases)), 'outputTokens': 9000}
    files = {}
    old_root = common.ROOT
    common.ROOT = out_root or old_root
    try:
        path = common.write_test(meta, cases)
    finally:
        common.ROOT = old_root
    with open(path, encoding='utf-8') as f:
        files['test'] = (path, f.read())
    scripted = {'work.contract-ladder': {c['L']['id']: c['scripted'] for c in cs}}
    files['scripted'] = (os.path.join(HERE, 'contract-scripted.json'), json.dumps(json.loads(json.dumps(scripted)), indent=1, ensure_ascii=False, sort_keys=True) + '\n')
    return files, [growth(c) for c in cs]


if __name__ == '__main__':
    import tempfile
    check = '--check' in sys.argv
    if check:
        tmp = tempfile.mkdtemp()
        files, _ = build(tmp)
        bad = 0
        live = os.path.join(HERE, '..', '..', 'tests', 'work', 'contract-ladder.json')
        for name, (path, text) in files.items():
            target = live if name == 'test' else path
            cur = open(target, encoding='utf-8').read() if os.path.exists(target) else None
            same = cur == text
            bad += not same
            print(('same   ' if same else 'DIFFERS'), os.path.relpath(target, os.path.join(HERE, '..', '..')))
        sys.exit(1 if bad else 0)
    files, g = build()
    with open(files['scripted'][0], 'w', encoding='utf-8') as f:
        f.write(files['scripted'][1])
    for name, (path, text) in files.items():
        print('wrote  ', os.path.relpath(path, os.path.join(HERE, '..', '..')), f'({len(text) // 1024} KB)')
    if '--stats' in sys.argv:
        for x in g:
            print(x)
