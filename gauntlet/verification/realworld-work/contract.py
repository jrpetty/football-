"""work.contract-redline — "What Changed in the Contract?".

A services agreement is built from a clause library with parameters. Version 2 is version 1 with a seeded set
of substantive edits (a value changed, a "not" inserted or removed, a clause added or deleted, a definition
changed, the governing law moved) and a layer of noise that changes nothing: a new section that renumbers
everything after it, a clause moved verbatim, cross-references renumbered to point at the same clauses,
"thirty (30) days" written "30 days", "£250,000" written "£250,000.00", headings recased, British spellings
Americanised. The key is the diff of the two STRUCTURED versions, so noise can never leak into it; the build
also asserts that each version's text renders every parameter value exactly once where expected.
"""
import random

# Points per field for fields where a trap sits (routine fields are worth 1). Stated in the prompt.
TRAP_WEIGHT = 2
import re
from wk import people

NUM_WORDS = {1: 'one', 2: 'two', 3: 'three', 4: 'four', 5: 'five', 6: 'six', 7: 'seven', 8: 'eight', 9: 'nine', 10: 'ten', 12: 'twelve', 14: 'fourteen',
             15: 'fifteen', 18: 'eighteen', 20: 'twenty', 21: 'twenty-one', 24: 'twenty-four', 28: 'twenty-eight', 30: 'thirty', 36: 'thirty-six', 45: 'forty-five',
             48: 'forty-eight', 60: 'sixty', 72: 'seventy-two', 90: 'ninety', 120: 'one hundred and twenty', 180: 'one hundred and eighty'}

# (key, section, text). [unit:param] renders a value; [ref:key] a clause number; [not:param] " not" when true;
# [law:param] a jurisdiction; [time:param] a clock time; {{organisation}} a spelling that varies with the style.
LIBRARY = [
    ('def_bd', 'Definitions', '“Business Day” means a day other than a Saturday, Sunday or public holiday in [region:bd_region].'),
    ('def_bh', 'Definitions', '“Business Hours” means [time:bh_start] to [time:bh_end] on a Business Day.'),
    ('def_charges', 'Definitions', '“Charges” means the fees payable by the Customer under clause [ref:fees], [vat:charges_vat] VAT.'),
    ('def_ci', 'Definitions', '“Confidential Information” means all information of a confidential nature disclosed by one party to the other in connection with this Agreement, whether in writing, orally or by any other means.'),
    ('def_services', 'Definitions', '“Services” means the facilities management services described in this Agreement, delivered at the Sites listed in Schedule 1.'),
    ('term', 'Term', 'This Agreement starts on the Commencement Date and continues for an initial term of [months:term] (the “Initial Term”).'),
    ('term_end', 'Term', 'Unless renewed or terminated earlier under this Agreement, this Agreement expires at the end of the Initial Term.'),
    ('standard', 'Supply of the Services', 'The Supplier shall perform the Services with reasonable skill and care, in accordance with Good Industry Practice and with all applicable laws.'),
    ('subk', 'Supply of the Services', 'The Supplier may[not:subk] subcontract any part of the Services without the Customer’s prior written consent.'),
    ('personnel', 'Supply of the Services', 'The Supplier shall ensure that all personnel engaged in the Services are suitably trained, supervised and security vetted.'),
    ('response', 'Supply of the Services', 'The Supplier shall respond to every Priority 1 incident within [hours:p1] of the incident being reported to the helpdesk, counting Business Hours only.'),
    ('avail', 'Service Levels', 'The helpdesk shall be available for at least [pct:avail] of Business Hours in each calendar month.'),
    ('credit', 'Service Levels', 'If the Supplier misses a service level in any month, the Customer is entitled to a service credit of [pct:credit] of that month’s Charges for each service level missed.'),
    ('credit_cap', 'Service Levels', 'Service credits for any month shall not exceed [pct:credit_cap] of the Charges for that month, and are the Customer’s sole financial remedy for missed service levels.'),
    ('fees', 'Charges and Payment', 'The Customer shall pay the Supplier a monthly fee of [money:fee] for the Services.'),
    ('invoice', 'Charges and Payment', 'The Supplier shall invoice monthly in arrears, and the Customer shall pay each valid invoice within [days:pay_days] of receiving it.'),
    ('withhold', 'Charges and Payment', 'The Customer shall[not:withhold] be entitled to withhold payment of any amount that it disputes in good faith while the dispute is resolved.'),
    ('interest', 'Charges and Payment', 'Interest on late payments accrues daily at [pct:late] a year above the Bank of England base rate from the due date until payment.'),
    ('increase', 'Charges and Payment', 'The Supplier may increase the Charges once in each Contract Year by no more than [pct:inc_cap], by giving the Customer written notice at least [days:inc_notice] in advance.'),
    ('ins_pl', 'Insurance', 'The [party:pl_party] shall maintain public liability insurance with a limit of at least [money:pl] for any one claim.'),
    ('ins_pi', 'Insurance', 'The Supplier shall maintain professional indemnity insurance with a limit of at least [money:pi] in the aggregate.'),
    ('ins_evidence', 'Insurance', 'On request, the Supplier [shall:ev_ob] give the Customer evidence that the insurances required by this clause are in force.'),
    ('conf', 'Confidentiality', 'Each party shall keep the other’s Confidential Information confidential, use it only to perform this Agreement and disclose it only to those of its personnel who need to know it.'),
    ('conf_survive', 'Confidentiality', 'The obligations in clause [xref:surv_ref] survive termination or expiry of this Agreement for [years:conf_years].'),
    ('dp', 'Data Protection', 'Each party shall comply with the Data Protection Legislation in relation to any personal data processed under this Agreement.'),
    ('dp_breach', 'Data Protection', 'The Supplier shall notify the Customer of any personal data breach without undue delay and in any event within [hours:breach] of becoming aware of it.'),
    ('liab_excl', 'Limitation of Liability', 'Nothing in this Agreement limits or excludes either party’s liability for death or personal injury caused by negligence, for fraud, or for any other liability that cannot lawfully be limited.'),
    ('liab_cap', 'Limitation of Liability', 'Subject to clause [xref:cap_ref], each party’s total liability arising under or in connection with this Agreement in each Contract Year shall not exceed [money:cap].'),
    ('liab_indirect', 'Limitation of Liability', 'Subject to clause [ref:liab_excl], neither party is liable for any loss of profit, loss of goodwill or indirect or consequential loss.'),
    ('t_conv', 'Termination', 'Either party may terminate this Agreement for convenience by giving the other written notice of at least [days:conv].'),
    ('t_breach', 'Termination', 'Either party may terminate this Agreement with immediate effect by written notice if the other party commits a material breach which is not remedied within [days:cure] of being notified of it.'),
    ('t_exit', 'Termination', 'On termination or expiry the [party:exit_party] shall provide reasonable exit assistance for up to [months:exit] so that the Services can transfer to the Customer or a replacement {{organisation}}.'),
    ('nonsol', 'Non-Solicitation', 'Neither party shall, without the other’s consent, solicit or employ any person employed by the other in the delivery of the Services during this Agreement and for [months:nonsol] after it ends.'),
    ('notices', 'General', 'Notices under this Agreement must be in writing and delivered by hand or by recorded post to the address set out in the Order Form; a notice by email is valid only if it is also sent by post.'),
    ('assign', 'General', 'Neither party may assign or transfer this Agreement without the prior written consent of the other, such consent not to be unreasonably withheld.'),
    ('entire', 'General', 'This Agreement is the entire agreement between the parties and supersedes all earlier discussions, proposals and agreements about its subject matter.'),
    ('law', 'General', 'This Agreement and any dispute arising out of or in connection with it are governed by the law of [law:law], and the courts of [law:law] have exclusive jurisdiction.'),
]
EXTRA = [  # extra boilerplate for the long case
    ('fm', 'General', 'Neither party is in breach of this Agreement or liable for any delay in performing its obligations if the delay results from events beyond its reasonable control, provided that it notifies the other promptly and uses reasonable endeavours to limit the effect of the event.'),
    ('audit', 'Supply of the Services', 'The Supplier [shall:audit_ob] keep complete and accurate records of the Services and of the Charges, and shall allow the Customer or its auditors to inspect them on reasonable notice during Business Hours.'),
    ('bribery', 'General', 'Each party shall comply with the Bribery Act 2010 and the Modern Slavery Act 2015 and shall maintain policies and procedures designed to ensure compliance.'),
    ('hs', 'Supply of the Services', 'While on any Site the Supplier’s personnel shall comply with the Customer’s health and safety rules and site security procedures as notified to the Supplier from time to time.'),
    ('tupe', 'Termination', 'The parties shall cooperate in good faith on any transfer of employees that may occur on termination under the Transfer of Undertakings (Protection of Employment) Regulations 2006.'),
    ('waiver', 'General', 'A failure or delay by a party to exercise any right or remedy under this Agreement is not a waiver of that right or remedy, and a single or partial exercise does not prevent any further exercise.'),
    ('severance', 'General', 'If any provision of this Agreement is found to be invalid or unenforceable, it shall be modified to the minimum extent necessary to make it valid, and the rest of this Agreement is not affected.'),
    ('variation', 'General', 'No variation of this Agreement is effective unless it is in writing and signed by an authorised representative of each party.'),
    ('third', 'General', 'A person who is not a party to this Agreement has no right under the Contracts (Rights of Third Parties) Act 1999 to enforce any of its terms.'),
    ('counterparts', 'General', 'This Agreement may be signed in any number of counterparts, each of which is an original and which together form one agreement.'),
    ('dp_sub', 'Data Protection', 'The Supplier shall[not:dp_sub_not] engage another processor without the Customer’s prior written authorisation and shall impose on any such processor the same data protection obligations as are set out in this clause.'),
    ('dp_return', 'Data Protection', 'On termination the Supplier shall, at the Customer’s choice, return or securely delete all personal data processed on the Customer’s behalf, unless the law requires it to keep a copy.'),
    ('conf_ex', 'Confidentiality', 'The obligations in this clause do not apply to information that is or becomes public other than through a breach of this Agreement, or that a party is required by law or by a regulator to disclose.'),
    ('ins_notify', 'Insurance', 'The Supplier shall notify the Customer promptly of any event that may give rise to a claim under the insurances required by this clause, and of any cancellation or material change to those insurances.'),
]
NEW_SECTION = ('Business Continuity', [
    ('bc_plan', 'Business Continuity', 'The Supplier shall maintain a business continuity and disaster recovery plan for the Services and test it at least once in each Contract Year.'),
    ('bc_invoke', 'Business Continuity', 'If the plan is invoked, the Supplier shall restore the Services within [hours:bc_restore] and shall keep the Customer informed of progress at least daily.'),
])
RENEWAL = ('renewal', 'Term', 'At the end of the Initial Term this Agreement renews automatically for successive periods of [months:renew] unless either party gives the other written notice of at least [days:renew_notice] before the end of the then-current term.')
SEC_ORDER = ['Definitions', 'Term', 'Supply of the Services', 'Service Levels', 'Charges and Payment', 'Insurance', 'Confidentiality', 'Data Protection',
             'Limitation of Liability', 'Termination', 'Non-Solicitation', 'General']

UNIT_TYPE = {'money': 'amount', 'pct': 'percent', 'days': 'days', 'months': 'months', 'years': 'years', 'hours': 'hours', 'count': 'number'}

# Schedules (medium / hard): generated rows, numbered S1.1, S1.2, ... A row is a clause for the comparison.
PLACES = ['Riverside', 'Mill Lane', 'Northgate', 'Kingsway', 'Station Road', 'Harbour Street', 'Castle Mead', 'Elm Park', 'Fosse Way', 'Queens Dock',
          'Ashton Vale', 'Brook Street', 'Canal Wharf', 'Deansgate', 'Granby Row', 'Hillfoot', 'Larch Close', 'Marsh Lane', 'Orchard Rise', 'Priory Fields']
SITE_TYPES = ['Depot', 'Clinic', 'Office', 'Library', 'Leisure Centre', 'Primary School', 'Health Centre', 'Warehouse']
KPIS = ['Helpdesk calls answered within 30 seconds', 'Reactive jobs attended within 4 hours', 'Planned maintenance completed on time', 'Cleaning audits passed',
        'Invoices issued without error', 'Customer satisfaction score of 4 or more', 'Statutory inspections completed on time', 'Out-of-hours callouts attended within 2 hours',
        'Waste recycled', 'Permits to work closed within 24 hours', 'Security patrols completed', 'Monthly reports delivered on time']
ROLES = ['Site Supervisor', 'Electrician', 'Plumber', 'Multi-skilled Technician', 'Cleaning Operative', 'Security Officer', 'Helpdesk Operator', 'Account Manager',
         'Gas Engineer', 'Fire Systems Engineer', 'Grounds Maintenance Operative', 'Porter', 'Handyperson', 'Mobile Cleaning Supervisor', 'Project Manager', 'Energy Analyst']
CHANGEABLE = {  # param: (unit, choices)
    'term': ('months', [12, 24, 36]), 'p1': ('hours', [2, 4, 8]), 'avail': ('pct', [99.5, 99.0, 98.5]), 'credit': ('pct', [5, 10, 2.5]),
    'credit_cap': ('pct', [15, 20, 25]), 'fee': ('money', [8450, 9250, 12600, 14975]), 'pay_days': ('days', [30, 45, 60]), 'late': ('pct', [4, 3, 8]),
    'inc_notice': ('days', [60, 90, 30]), 'inc_cap': ('pct', [5, 3, 7.5]), 'pl': ('money', [5000000, 10000000, 2000000]), 'pi': ('money', [2000000, 1000000, 5000000]),
    'conf_years': ('years', [3, 5, 2]), 'breach': ('hours', [48, 24, 72]), 'cap': ('money', [250000, 500000, 150000, 1000000]), 'conv': ('days', [90, 60, 180]),
    'cure': ('days', [30, 14, 21]), 'exit': ('months', [3, 6, 2]), 'nonsol': ('months', [12, 6, 18]),
}
LAWS = ['England and Wales', 'Scotland', 'Northern Ireland', 'Ireland']


class Version:
    def __init__(self, params, sections):
        self.params = dict(params)
        self.sections = sections  # list of (section name, [clause keys])


def fmt_value(unit, v, style):
    if unit == 'money':
        if style['money'] == 'plain':
            return f"£{v:,.0f}"
        if style['money'] == 'pence':
            return f"£{v:,.2f}"
        return f"GBP {v:,.0f}"
    if unit == 'pct':
        s = f"{v:g}"
        return f"{s}%" if style['pct'] == 'sign' else f"{s} per cent"
    if unit == 'count':
        n = int(v)
        return f"{NUM_WORDS[n]} ({n})" if style['num'] == 'words' and n in NUM_WORDS else f"{n}"
    word = {'days': 'day', 'months': 'month', 'years': 'year', 'hours': 'hour'}[unit]
    n = int(v)
    plural = word + ('s' if n != 1 else '')
    if style['num'] == 'words' and n in NUM_WORDS:
        return f"{NUM_WORDS[n]} ({n}) {plural}"
    return f"{n} {plural}"


def render(version, texts, style, title):
    numbers = {}
    labels = []
    mi = sch = 0
    for sec, keys in version.sections:
        if sec.startswith('Schedule'):
            sch += 1
            pre = f"S{sch}"
            labels.append((f"SCHEDULE {sch} — " + sec.split(': ', 1)[1].upper() if style['heads'] == 'upper' else f"Schedule {sch} — " + sec.split(': ', 1)[1]))
        else:
            mi += 1
            pre = f"{mi}"
            labels.append(f"{mi}. " + (sec.upper() if style['heads'] == 'upper' else sec))
        for ci, k in enumerate(keys, 1):
            numbers[k] = f"{pre}.{ci}"
    lines = [title, '']
    for (sec, keys), head in zip(version.sections, labels):
        lines.append(head)
        for k in keys:
            t = texts[k]

            def sub(m):
                kind, arg = m.group(1), m.group(2)
                p = version.params
                if kind == 'ref':
                    return numbers[arg]
                if kind == 'xref':
                    return numbers[p[arg]]
                if kind == 'shall':
                    return 'shall' if p[arg] else 'may'
                if kind == 'party':
                    return p[arg]
                if kind == 'not':
                    return ' not' if p[arg] else ''
                if kind == 'law':
                    return p[arg]
                if kind == 'region':
                    return p[arg]
                if kind == 'vat':
                    return 'inclusive of' if p[arg] else 'exclusive of'
                if kind == 'time':
                    return p[arg]
                return fmt_value(kind, p[arg], style)
            t = re.sub(r'\[(\w+):(\w+)\]', sub, t)
            t = t.replace('{{organisation}}', 'organisation' if style['spell'] == 'uk' else 'organization')
            lines.append(f"{numbers[k]} {t}")
        lines.append('')
    return '\n'.join(lines).strip(), numbers


def first_unit(text):
    m = re.search(r'\[(money|pct|days|months|years|hours):(\w+)\]', text)
    return m.groups() if m else None


def diff(v1, v2, texts, n1, n2):
    """Substantive changes from the structured versions, keyed by clause number (new; old for a deleted clause)."""
    out = {}
    keys1 = {k for _, ks in v1.sections for k in ks}
    keys2 = {k for _, ks in v2.sections for k in ks}
    def put(num, v):
        if num in out:
            raise KeyError(f'clause number {num} used twice')
        out[num] = v
    for k in sorted(keys2 - keys1):
        put(n2[k], {'type': 'added', 'old': None, 'new': None})
    for k in sorted(keys1 - keys2):
        put(n1[k], {'type': 'deleted', 'old': None, 'new': None})
    for k in keys1 & keys2:
        t = texts[k]
        ch = []
        for kind, arg in re.findall(r'\[(\w+):(\w+)\]', t):
            if kind == 'ref':
                continue
            a, b = v1.params[arg], v2.params[arg]
            if a == b:
                continue
            if k.startswith('def_'):
                ch.append({'type': 'definition', 'old': None, 'new': None})
            elif kind == 'xref':
                ch.append({'type': 'cross_reference', 'old': n1[a], 'new': n2[b]})
            elif kind == 'shall':
                ch.append({'type': 'obligation', 'old': None, 'new': None})
            elif kind == 'party':
                ch.append({'type': 'party', 'old': a, 'new': b})
            elif kind == 'not':
                ch.append({'type': 'negation', 'old': None, 'new': None})
            elif kind == 'law':
                ch.append({'type': 'governing_law', 'old': a, 'new': b})
            else:
                ch.append({'type': UNIT_TYPE[kind], 'old': a, 'new': b})
        uniq = []
        for c in ch:
            if c not in uniq:
                uniq.append(c)
        if len(uniq) > 1:
            raise KeyError(f'two changes in clause {k}')
        if uniq:
            put(n2[k], uniq[0])
    # keys in clause order, so the file reads naturally
    def order(num):
        a, b = num.split('.')
        return (1, int(a[1:]), int(b)) if a.startswith('S') else (0, int(a), int(b))
    return dict(sorted(out.items(), key=lambda kv: order(kv[0])))


STYLE1 = dict(num='words', money='plain', pct='sign', heads='upper', spell='uk')
STYLE2 = dict(num='digits', money='pence', pct='words', heads='title', spell='us')


def build(seed, tier):
    rng = random.Random(seed)
    names = people(rng, 2)
    customer = rng.choice(['Northgate Housing Trust', 'Larkspur Academy Trust', 'Redcliffe Leisure Ltd', 'Pennine Dental Group Ltd', 'Saltmarsh Hotels Ltd'])
    supplier = rng.choice(['Elmstead Facilities Ltd', 'Corran Workplace Services Ltd', 'Kestrel Property Care Ltd', 'Halden FM Ltd'])
    lib = list(LIBRARY) + (EXTRA if tier != 'easy' else [])
    texts = {k: t for k, _, t in lib + NEW_SECTION[1] + [RENEWAL]}
    secs = {s: [] for s in SEC_ORDER}
    for k, s, _ in lib:
        secs[s].append(k)
    # schedules
    sch_params = {}
    sched = []
    if tier != 'easy':
        n_site, n_kpi, n_rate = {'medium': (10, 6, 8), 'hard': (20, 10, 12), 'long': (34, 12, 16)}[tier]
        places = rng.sample(PLACES, min(n_site, len(PLACES)))
        site_keys = []
        used_names = set()
        for i in range(n_site):
            while True:
                nm = f"{rng.choice(PLACES)} {rng.choice(SITE_TYPES)}"
                if nm not in used_names:
                    used_names.add(nm)
                    break
            k = f"site_{i}"
            texts[k] = f"{nm}, {rng.randint(2, 180)} {rng.choice(PLACES)} Road: [count:{k}_v] planned visits a month, plus reactive call-outs."
            sch_params[f"{k}_v"] = rng.choice([1, 2, 4, 6, 8, 12])
            site_keys.append(k)
        kpi_keys = []
        for i, nm in enumerate(rng.sample(KPIS, n_kpi)):
            k = f"kpi_{i}"
            texts[k] = f"{nm}: target [pct:{k}_t] of occasions each month."
            sch_params[f"{k}_t"] = rng.choice([85, 90, 95, 97.5, 98, 99])
            kpi_keys.append(k)
        rate_keys = []
        roles = rng.sample(ROLES, n_rate)
        for i, nm in enumerate(roles):
            k = f"rate_{i}"
            texts[k] = f"{nm}: [money:{k}_r] per day (8 hours), additional hours pro rata."
            sch_params[f"{k}_r"] = rng.choice([180, 210, 245, 260, 295, 320, 345, 380, 420])
            rate_keys.append(k)
        sched = [('Schedule: Sites', site_keys), ('Schedule: Service levels', kpi_keys), ('Schedule: Rate card', rate_keys)]
        for k in [f"site_x{j}" for j in range(3)]:  # sites that may be added in version 2
            texts[k] = f"{rng.choice(PLACES)} {rng.choice(['Annexe', 'Store', 'Hub'])}, {rng.randint(2, 180)} {rng.choice(PLACES)} Road: [count:{k}_v] planned visits a month, plus reactive call-outs."
            sch_params[f"{k}_v"] = rng.choice([1, 2, 4])
    if tier == 'easy':  # a shorter agreement
        for s in secs:
            secs[s] = [k for k in secs[s] if k not in ('ins_evidence', 'term_end', 'personnel', 'def_services', 'entire', 'credit_cap')]
    params = {p: rng.choice(ch) for p, (u, ch) in CHANGEABLE.items()}
    params.update(subk=True, withhold=False, law='England and Wales', bd_region='England', bh_start='09:00', bh_end='17:30', charges_vat=False,
                  bc_restore=24, renew=12, renew_notice=90, pl_party='Supplier', exit_party='Supplier', ev_ob=True, audit_ob=True, dp_sub_not=True,
                  surv_ref='conf', cap_ref='liab_excl')
    params.update(sch_params)
    if rng.random() < 0.5:
        params['subk'] = False
    v1 = Version(params, [(s, list(secs[s])) for s in SEC_ORDER] + [(n, list(ks)) for n, ks in sched])
    p2 = dict(params)
    s2 = [(s, list(ks)) for s, ks in v1.sections]

    def sec(name):
        return next(ks for s, ks in s2 if s == name)
    present = {k for _, ks in s2 for k in ks}
    n_val = {'easy': 4, 'medium': 6, 'hard': 8, 'long': 10}[tier]
    pool = [p for p in CHANGEABLE if any(f':{p}]' in texts[k] for k in present)]
    rng.shuffle(pool)
    touched = set()
    for p in pool:
        if len(touched) >= n_val:
            break
        clause = next(k for k in present if f':{p}]' in texts[k])
        if clause in touched:
            continue
        touched.add(clause)
        unit, ch = CHANGEABLE[p]
        p2[p] = rng.choice([c for c in ch if c != params[p]])
    if tier != 'easy':
        p2[rng.choice(['subk', 'withhold'])] ^= True
        p2['law'] = rng.choice([l for l in LAWS if l != params['law']]) if tier in ('hard', 'long') or rng.random() < 0.5 else params['law']
        # a definition change
        dch = rng.choice(['bh', 'vat', 'region'])
        if dch == 'bh':
            p2['bh_start'], p2['bh_end'] = rng.choice([('08:00', '18:00'), ('08:30', '17:00'), ('09:00', '17:00')])
        elif dch == 'vat':
            p2['charges_vat'] = True
        else:
            p2['bd_region'] = rng.choice(['the United Kingdom', 'Scotland'])
        # the subtle ones: who must do it, must or may, and a cross-reference that now points elsewhere
        subtle = {'medium': 2, 'hard': 4, 'long': 5}[tier]
        opts = ['party_pl', 'party_exit', 'ob_ev', 'ob_audit', 'xref_cap', 'xref_surv', 'neg_dp']
        rng.shuffle(opts)
        for o in opts[:subtle]:
            if o == 'party_pl':
                p2['pl_party'] = 'Customer'
            elif o == 'party_exit':
                p2['exit_party'] = 'Customer'
            elif o == 'ob_ev':
                p2['ev_ob'] = False
            elif o == 'ob_audit':
                p2['audit_ob'] = False
            elif o == 'xref_cap':
                p2['cap_ref'] = 'liab_indirect'
            elif o == 'xref_surv':
                p2['surv_ref'] = 'conf_ex'
            else:
                p2['dp_sub_not'] = False
        # schedule rows: a few values change
        n_rows = {'medium': 3, 'hard': 6, 'long': 8}[tier]
        rowp = [k for k in sch_params if not k.startswith('site_x')]
        for k in rng.sample(rowp, n_rows):
            if k.endswith('_v'):
                p2[k] = rng.choice([x for x in (1, 2, 4, 6, 8, 12) if x != params[k]])
            elif k.endswith('_t'):
                p2[k] = rng.choice([x for x in (85, 90, 95, 97.5, 98, 99) if x != params[k]])
            else:
                p2[k] = rng.choice([x for x in (180, 210, 245, 260, 295, 320, 345, 380, 420) if x != params[k]])
    v2 = Version(p2, s2)
    noise = []
    # structural edits
    if tier != 'easy':
        t = sec('Term')
        t.insert(t.index('term') + 1, 'renewal')  # auto-renewal added (renumbers term_end)
    if tier in ('medium', 'hard', 'long'):
        victim = rng.choice([k for k in ('personnel', 'nonsol', 'ins_evidence', 't_exit') if k in present])
        for _, ks in s2:
            if victim in ks:
                ks.remove(victim)
        s2[:] = [(s, ks) for s, ks in s2 if ks]
    if tier in ('hard', 'long'):
        # a new section inserted before Insurance renumbers every later section
        i = [s for s, _ in s2].index('Insurance')
        s2.insert(i, (NEW_SECTION[0], [k for k, _, _ in NEW_SECTION[1]]))
        # a clause moved verbatim: the carve-out moves to the end of its section (cross-references follow it)
        lia = sec('Limitation of Liability')
        lia.remove('liab_excl')
        lia.append('liab_excl')
        noise.append('liab_excl moved')
    if tier != 'easy':
        sites = sec('Schedule: Sites')
        sites.insert(rng.randint(1, len(sites) - 1), 'site_x0')  # a site added mid-list renumbers the rest
        sites.remove(rng.choice(sites[:3]))  # an early site deleted
        if tier in ('hard', 'long'):
            sites.append('site_x1')
            # the rate card comes back sorted by role: every row renumbered, unchanged rows are only moved
            rc = sec('Schedule: Rate card')
            rc.sort(key=lambda k: texts[k])
            noise.append('rate card resorted')
    if tier in ('medium',):
        # a clause moved verbatim from General to Confidentiality
        g = sec('General')
        g.remove('notices')
        sec('Confidentiality').append('notices')
        noise.append('notices moved')
    title1 = f"SERVICES AGREEMENT between {customer} (the Customer) and {supplier} (the Supplier) — version 1 (draft sent {rng.choice(['3 March', '14 April', '9 June'])})"
    title2 = f"SERVICES AGREEMENT between {customer} (the Customer) and {supplier} (the Supplier) — version 2 (Supplier's revised draft)"
    t1, n1 = render(v1, texts, STYLE1, title1)
    t2, n2 = render(v2, texts, STYLE2 if tier != 'easy' else dict(STYLE2, heads='upper'), title2)
    key = diff(v1, v2, texts, n1, n2)
    # a deleted clause's old number must not collide with a new-version key
    dels = [k for k in set(n1) - set(n2)]
    assert len(key) == len(set(key)), key
    # numbers are compared as numbers: values must be JSON-friendly
    for c in key.values():
        for f in ('old', 'new'):
            if isinstance(c[f], float) and c[f].is_integer():
                c[f] = int(c[f])
    # the noise really is noise: every unchanged clause renders to the same words once formatting is normalised
    # every kind of change in the key really changed the words of that clause
    for num, c in key.items():
        assert c['type'] in ('added', 'deleted') or c['old'] != c['new'] or c['old'] is None, (num, c)
    return dict(v1=v1, v2=v2, texts=texts, t1=t1, t2=t2, n1=n1, n2=n2, key=key, customer=customer, supplier=supplier, noise=noise)


def careless(b):
    """Old clause numbers for everything, negations and definition changes missed, the moved clause reported as
    added, and the reformatted notice period in the termination clause reported as a change."""
    out = {}
    n1, n2 = b['n1'], b['n2']
    inv2 = {v: k for k, v in n2.items()}
    for num, c in b['key'].items():
        if c['type'] in ('negation', 'definition', 'obligation', 'party', 'cross_reference'):
            continue
        k = inv2.get(num)
        if c['type'] == 'deleted':
            out[num] = c
            continue
        out[n1.get(k, num)] = c
    for note in b['noise']:
        k = note.split()[0]
        if k in n2:
            out[n2[k]] = {'type': 'added', 'old': None, 'new': None}
        elif note == 'rate card resorted':
            # two reordered rows reported as changed prices
            moved = [x for x in n2 if x.startswith('rate_') and n1.get(x) != n2[x] and b['v1'].params[x + '_r'] == b['v2'].params[x + '_r']][:2]
            for x in moved:
                v = b['v2'].params[x + '_r']
                out[n2[x]] = {'type': 'amount', 'old': v, 'new': v}
    if 't_breach' in n2 and n2['t_breach'] not in out:
        v = b['v2'].params['cure']
        out[n2['t_breach']] = {'type': 'days', 'old': v, 'new': v}
    return {'changes': out}


def slip(b):
    """A careful reader who takes a cross-reference that now points at a different clause for ordinary renumbering."""
    return {'changes': {k: v for k, v in b['key'].items() if v['type'] != 'cross_reference'}}


def prompt(b):
    rules = """List every SUBSTANTIVE change between version 1 and version 2 — a change that alters what either party must do, may do, pays or risks. Do NOT list changes that only affect presentation: clause or section renumbering, cross-references renumbered so they still point at the same clause, a clause moved somewhere else with the same words, the same number written differently ("thirty (30) days" / "30 days", "£250,000" / "£250,000.00", "5%" / "5 per cent"), headings, capitals or spelling. Listing a change that is not substantive costs as much as missing one.

For each substantive change give:
- the clause number in VERSION 2 (for a deleted clause, its number in version 1);
- type: one of "amount" (a sum of money), "percent", "number" (a count, e.g. visits a month), "days", "months", "years", "hours" (a time period in that unit), "governing_law", "negation" (a "not" added or removed, reversing the clause), "obligation" ("shall" changed to "may" or back), "party" (the obligation moved to the other party), "cross_reference" (a reference that now points at a DIFFERENT clause), "added" (a new clause or schedule row), "deleted" (a clause or row removed), "definition" (a change inside a definition in section 1);
- old and new: the old and new values for amount (in pounds), percent, number, days, months, years and hours (numbers only), for governing_law (the jurisdiction's name as written), for party ("Supplier" or "Customer") and for cross_reference (the clause number written in version 1 and the one written in version 2); null for negation, obligation, added, deleted and definition.
Schedule rows are numbered S1.1, S1.2, … (S2.x, S3.x for the later schedules) and count as clauses.
Only list a clause whose own wording changed (or that was added or removed); a clause whose words are unchanged is not listed even if a changed definition affects it."""
    schema = '{"changes": {"9.2": {"type": "amount", "old": 500000, "new": 250000}, "2.2": {"type": "added", "old": null, "new": null}, ...}}'
    return (f"Our supplier has sent back a revised draft of our services agreement. Before I sign, tell me exactly what they changed.\n\n{rules}\n\n"
            f"<<< VERSION 1\n{b['t1']}\n>>>\n\n<<< VERSION 2\n{b['t2']}\n>>>\n\n"
            f"Answer with one JSON object in exactly this shape:\n{schema}\n"
            f"Marking: each change has three fields (type, old, new). Fields of a plainly visible change are worth 1 point; fields that need extra care (a renumbered or moved clause or row, a negation, an obligation, a party, a cross-reference, a definition) are worth {TRAP_WEIGHT}. "
            f"A change you miss loses its points, and each clause you list that has no substantive change costs three fields at {TRAP_WEIGHT} points each. "
            "Reply with the JSON object only (a ```json code block is fine); key order, spacing and letter case do not matter, clause keys may be written \"9.2\" or \"Clause 9.2\", and amounts may have a £ sign. "
            "Give one value per field: a field with two values is marked wrong, and if you give more than one JSON answer only the last one counts.")


CASES = [('k01', 1101, 'easy'), ('k02', 1102, 'easy'), ('k03', 1103, 'easy'),
         ('k04', 1204, 'medium'), ('k05', 1205, 'medium'), ('k06', 1206, 'medium'),
         ('k07', 1307, 'hard'), ('k08', 1308, 'hard'), ('k09', 1309, 'long')]


def make_cases():
    out = []
    for cid, seed, tier in CASES:
        for k in range(300):
            try:
                b = build(seed * 10 + k, tier)
                break
            except KeyError:
                continue
        else:
            raise AssertionError(cid)
        out.append(dict(id=cid, tier=tier if tier != 'long' else 'hard', prompt=prompt(b), expected={'changes': b['key']}, careless=careless(b), slip=slip(b), b=b))
    return out


if __name__ == '__main__':
    import json
    cs = make_cases()
    for c in cs:
        w = len(c['b']['t1'].split()) + len(c['b']['t2'].split())
        print(c['id'], c['tier'], len(c['expected']['changes']), 'changes', w, 'words', len(c['prompt']), 'chars')
    print(cs[6]['prompt'])
    print(json.dumps(cs[6]['expected'], indent=1))
