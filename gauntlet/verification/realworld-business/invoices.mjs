// work.invoice-matching — "Invoices to Pay" (accounts-payable three-way match).
// generate(spec) builds a world (POs, GRNs, ledger, supplier documents, late emails) in which every document was
// planted with an INTENDED outcome; solve(world) is an independent rules engine that applies the written policy
// to the world's data only. build.mjs asserts the two agree for every document, so the key is the policy's answer.

import { rng, D, addDays, iso, dmy, dMonY, ukShort, ukLong, lastOfMonth, gbp, num2, divHalfUp, assert, ONE_ANSWER } from './lib.mjs';

// ───────────────────────────── Businesses and suppliers ─────────────────────────────
// terms: { kind: 'net', days } | { kind: 'eom', days } | { kind: 'disc', bp, within, days }
const P = (code, desc, price, vat, lo, hi) => ({ code, desc, price, vat, lo, hi });

export const BUSINESSES = {
  cafe: {
    legal: 'Harbour & Hearth Coffee Ltd',
    sister: 'Harbour & Hearth Bakery Ltd',
    address: '14 Wapping Road, Bristol BS1 4RW',
    domain: 'harbourhearth.co.uk',
    manager: { name: 'Priya Raman', role: 'Finance Manager' },
    clerk: 'AP inbox',
    sites: [
      { name: 'Clifton branch', addr: '22 Princess Victoria Street, Bristol BS8 4BX', lead: 'Tom Okafor' },
      { name: 'Gloucester Road branch', addr: '187 Gloucester Road, Bristol BS7 8BG', lead: 'Siobhan Kelly' },
      { name: 'Temple Quay kiosk', addr: 'Unit 2, Temple Quay, Bristol BS1 6DZ', lead: 'Kwame Mensah' },
    ],
    sisterSite: { name: 'Harbour & Hearth Bakery (production kitchen)', addr: 'Unit 9, Feeder Road, Bristol BS2 0SB' },
    suppliers: [
      { id: 'SVD', name: 'Severn Vale Dairies Ltd', addr: 'Unit 4, Lydney Industrial Estate, Lydney GL15 4EJ', vatNo: 'GB 284 5519 07', prefix: 'SVD-', sort: '30-94-57', acct: '41876612', terms: { kind: 'net', days: 14 }, layout: 'formal',
        products: [P('WM2L', 'Whole milk 2 litre', 162, 'Z', 24, 72), P('SS2L', 'Semi-skimmed milk 2 litre', 158, 'Z', 24, 60), P('DC1L', 'Double cream 1 litre', 412, 'Z', 6, 18), P('BUT5', 'Unsalted butter 500g', 365, 'Z', 6, 24)] },
      { id: 'BBR', name: 'Bean & Bough Roasters Ltd', addr: 'Arch 14, Cheese Lane, Bristol BS2 0JJ', vatNo: 'GB 397 1180 52', prefix: 'BB-', sort: '60-83-71', acct: '22051984', terms: { kind: 'disc', bp: 200, within: 10, days: 30 }, layout: 'email',
        products: [P('ESP1K', 'House espresso blend 1kg', 1895, 'Z', 10, 40), P('DEC1K', 'Swiss water decaf 1kg', 2140, 'Z', 3, 10), P('HUI1K', 'Single origin Huila 1kg', 2475, 'Z', 4, 12), P('CLT', 'Group head cleaning tablets (tub 100)', 1250, 'S', 2, 6)] },
      { id: 'AVP', name: 'Avonside Packaging Ltd', addr: '3 Kingsland Trading Estate, Bristol BS2 0JZ', vatNo: 'GB 166 2093 41', prefix: 'AVP/', sort: '20-17-92', acct: '60339127', terms: { kind: 'eom', days: 30 }, layout: 'formal',
        products: [P('CUP12', '12oz double-wall cups (case 500)', 3450, 'S', 2, 8), P('LID12', 'Sip lids 12oz (case 1000)', 2890, 'S', 1, 5), P('NAPK', 'Kraft napkins (case 4000)', 2275, 'S', 1, 4), P('BAGT', 'Paper takeaway bags (case 1000)', 3120, 'S', 1, 4)] },
      { id: 'CHS', name: 'Clearwater Hygiene Supplies Ltd', addr: '41 Brislington Hill, Bristol BS4 5BE', vatNo: 'GB 512 7764 30', prefix: 'CW', sort: '40-35-11', acct: '73302158', terms: { kind: 'net', days: 21 }, layout: 'scan',
        products: [P('SAN5', 'Food-safe sanitiser 5L', 1495, 'S', 2, 8), P('BLUR', 'Blue roll 2-ply (6 pack)', 1860, 'S', 2, 8), P('NGLV', 'Nitrile gloves (box 100)', 545, 'S', 5, 20), P('DWD20', 'Dishwasher detergent 20L', 4250, 'S', 1, 3)] },
      { id: 'ORP', name: 'Orchard Row Produce', addr: 'Stall 6, Bristol Wholesale Fruit Centre, Albert Crescent, Bristol BS2 0XS', vatNo: 'GB 731 0042 88', prefix: 'ORP', sort: '09-01-28', acct: '13579246', terms: { kind: 'net', days: 7 }, layout: 'email',
        products: [P('BAN18', 'Bananas (box 18kg)', 1650, 'Z', 1, 4), P('AVO22', 'Avocados (tray of 22)', 1980, 'Z', 1, 4), P('LEM80', 'Lemons (box of 80)', 1420, 'Z', 1, 3), P('SPN1', 'Baby spinach 1kg', 675, 'Z', 2, 8)] },
      { id: 'KTE', name: 'Kettle & Tap Equipment Ltd', addr: 'Unit 12, Aztec West, Bristol BS32 4TD', vatNo: 'GB 205 6618 73', prefix: 'KT', sort: '30-12-80', acct: '55018266', terms: { kind: 'net', days: 45 }, layout: 'formal',
        products: [P('GB64', 'Grinder burr set 64mm', 8900, 'S', 1, 3), P('GHK', 'Group head seal & screen kit', 2450, 'S', 2, 6), P('WFC', 'Water filter cartridge', 6750, 'S', 1, 4)] },
      { id: 'MBS', name: 'Mendip Bakery Supplies Ltd', addr: 'Unit 3, Station Road Industrial Estate, Wells BA5 1EA', vatNo: 'GB 640 2215 37', prefix: 'MBS', sort: '30-96-34', acct: '58201146', terms: { kind: 'settle', bp: 250, within: 14, days: 30 }, layout: 'formal',
        products: [P('FLR16', 'Strong white flour 16kg', 1450, 'Z', 2, 8), P('BKP', 'Baking parchment roll 50m', 895, 'S', 2, 6), P('CHOC', 'Dark chocolate callets 2.5kg', 2875, 'Z', 1, 4)] },
    ],
  },
  plumbing: {
    legal: 'Pennine Plumbing & Heating Ltd',
    sister: 'Pennine Bathrooms Ltd',
    address: 'Unit 7, Colne Bridge Works, Huddersfield HD5 0RH',
    domain: 'pennineplumbing.co.uk',
    manager: { name: 'Gareth Lloyd', role: 'Office Manager' },
    sites: [
      { name: 'Main depot', addr: 'Unit 7, Colne Bridge Works, Huddersfield HD5 0RH', lead: 'Marek Nowak' },
      { name: 'Van stock (Van 3)', addr: 'Unit 7, Colne Bridge Works, Huddersfield HD5 0RH', lead: 'Daniel Ferreira' },
    ],
    sisterSite: { name: 'Pennine Bathrooms showroom', addr: '62 Westgate, Huddersfield HD1 1NW' },
    suppliers: [
      { id: 'CPM', name: 'Calder Plumbmerchants Ltd', addr: 'Calder Trading Park, Wakefield Road, Brighouse HD6 1PZ', vatNo: 'GB 449 2017 66', prefix: 'CPM', sort: '05-07-62', acct: '31044827', terms: { kind: 'disc', bp: 250, within: 10, days: 30 }, layout: 'formal',
        products: [P('CU15', '15mm copper tube 3m', 1385, 'S', 10, 40), P('PF15', '15mm press-fit elbow (pack 10)', 1640, 'S', 4, 20), P('TRVP', 'Thermostatic radiator valve pair', 2295, 'S', 4, 16), P('RAD22', 'Compact radiator type 22 600x1000', 9850, 'S', 1, 6)] },
      { id: 'NBS', name: 'Northern Boiler Spares Ltd', addr: '18 Canal Street, Halifax HX3 9HY', vatNo: 'GB 318 8820 14', prefix: 'NBS-', sort: '40-26-19', acct: '90215573', terms: { kind: 'eom', days: 30 }, layout: 'email',
        products: [P('DIVV', 'Diverter valve assembly', 7425, 'S', 1, 4), P('PCB2', 'Boiler main PCB (combi)', 18950, 'S', 1, 2), P('FANC', 'Combustion fan', 12600, 'S', 1, 2), P('EXV8', 'Expansion vessel 8L', 3990, 'S', 1, 5)] },
      { id: 'KFX', name: 'Kirklees Fixings & Fasteners', addr: 'Units 3-4, Leeds Road Industrial Estate, Huddersfield HD2 1UR', vatNo: 'GB 667 1093 25', prefix: 'KFF', sort: '20-55-34', acct: '00812244', terms: { kind: 'eom', days: 30 }, layout: 'scan',
        products: [P('SCR440', 'Wood screws 4x40 (box 200)', 495, 'S', 4, 20), P('PTFE', 'PTFE tape (pack 10)', 380, 'S', 5, 25), P('SILW', 'Silicone sealant white 310ml', 425, 'S', 6, 24), P('CLP15', '15mm pipe clips (bag 100)', 650, 'S', 3, 12)] },
      { id: 'HVW', name: 'Holme Valley Workwear', addr: '9 Huddersfield Road, Holmfirth HD9 3JH', vatNo: 'GB 820 3341 90', prefix: 'HVW', sort: '30-65-41', acct: '44190327', terms: { kind: 'net', days: 21 }, layout: 'email',
        products: [P('BTS3', 'Safety boots S3', 4995, 'S', 2, 6), P('TRSW', 'Work trousers (kneepad pockets)', 2650, 'S', 2, 8), P('HIVV', 'Hi-vis vest, branded', 425, 'S', 6, 20)] },
      { id: 'DFL', name: 'Dales Fuel Ltd', addr: 'Station Yard, Penistone S36 6HE', vatNo: 'GB 291 4457 03', prefix: 'DF', sort: '16-00-38', acct: '20774519', terms: { kind: 'net', days: 14 }, layout: 'formal',
        products: [P('KER28', 'Kerosene 28-sec, domestic delivery (litre)', 68, 'R', 500, 1200), P('ADDK', 'Kerosene additive 1L, domestic', 1450, 'R', 1, 4), P('TNKG', 'Oil tank level gauge (workshop stock)', 3450, 'S', 1, 3)] },
      { id: 'BTH', name: 'Brighouse Tool Hire Ltd', addr: '2 Mill Royd Street, Brighouse HD6 1EY', vatNo: 'GB 512 0098 44', prefix: 'BTH', sort: '40-19-77', acct: '62045513', terms: { kind: 'settle', bp: 300, within: 14, days: 30 }, layout: 'email',
        products: [P('PCUT', 'Pipe cutter set 15-28mm', 3450, 'S', 1, 4), P('PJAW', 'Press-fit jaw set 15/22/28', 18900, 'S', 1, 2), P('SDSB', 'SDS drill bit set (8 piece)', 2995, 'S', 1, 5)] },
    ],
  },
  baby: {
    legal: 'Lark & Lantern Ltd',
    sister: 'Lark & Lantern Wholesale Ltd',
    address: '3 St Benedicts Street, Norwich NR2 4PE',
    domain: 'larkandlantern.co.uk',
    manager: { name: 'Mei Lin Chau', role: 'Head of Finance' },
    sites: [
      { name: 'Fulfilment unit', addr: 'Unit 5, Hellesdon Park Road, Norwich NR6 5DR', lead: 'Aisha Begum' },
      { name: 'Shop', addr: '3 St Benedicts Street, Norwich NR2 4PE', lead: 'Jonah Feld' },
    ],
    sisterSite: { name: 'Lark & Lantern Wholesale warehouse', addr: 'Unit 11, Hellesdon Park Road, Norwich NR6 5DR' },
    suppliers: [
      { id: 'LOB', name: 'Little Owl Books Ltd', addr: '27 Magdalen Street, Norwich NR3 1LE', vatNo: 'GB 703 5521 40', prefix: 'LOB', sort: '20-98-61', acct: '83310045', terms: { kind: 'net', days: 60 }, layout: 'formal',
        products: [P('BK-MH', "'The Moon and the Hare' board book", 425, 'Z', 12, 48), P('BK-TE', "'Tiny Explorers' sticker atlas", 560, 'Z', 10, 40), P('BK-BT', 'Bedtime Stories Treasury (hardback)', 1150, 'Z', 6, 24), P('BK-GW', 'Gift wrap & card set', 350, 'S', 6, 24)] },
      { id: 'TTC', name: 'Tumbletots Clothing Co', addr: '4 Mill Lane, Leicester LE2 7HU', vatNo: 'GB 158 0039 72', prefix: 'TT', sort: '23-05-80', acct: '61428830', terms: { kind: 'disc', bp: 200, within: 10, days: 30 }, layout: 'email',
        products: [P('SLP03', 'Organic sleepsuit 0-3m', 840, 'Z', 12, 48), P('RMP612', 'Knitted romper 6-12m', 1275, 'Z', 6, 30), P('HATT', 'Bobble hat (toddler)', 395, 'Z', 12, 40)] },
      { id: 'SRC', name: 'SafeRide Child Seats Ltd', addr: 'Unit 1, Ely Road, Waterbeach CB25 9PG', vatNo: 'GB 944 6120 07', prefix: 'SR', sort: '30-90-89', acct: '16620873', terms: { kind: 'net', days: 30 }, layout: 'formal',
        products: [P('CS0', 'Infant car seat, group 0+', 6450, 'R', 2, 8), P('CSHB', 'High-back booster seat', 3875, 'R', 2, 10), P('MIRR', 'Back-seat baby mirror', 1195, 'S', 4, 12)] },
      { id: 'WWC', name: 'Wick & Wax Candle Co', addr: 'The Old Forge, Aylsham NR11 6EH', vatNo: 'GB 389 7105 61', prefix: 'WW', sort: '60-02-13', acct: '27750914', terms: { kind: 'eom', days: 30 }, layout: 'scan',
        products: [P('CND-FC', "Soy candle 200g 'Fig & Cassis'", 525, 'S', 12, 48), P('DIF100', 'Reed diffuser 100ml', 640, 'S', 12, 36)] },
      { id: 'BBM', name: 'Broadland Box & Mailer Ltd', addr: '7 Gapton Hall Road, Great Yarmouth NR31 0NL', vatNo: 'GB 471 2288 19', prefix: 'BBM', sort: '20-61-55', acct: '90044321', terms: { kind: 'net', days: 21 }, layout: 'email',
        products: [P('MLR-S', 'Kraft mailer box small (pack 100)', 3290, 'S', 2, 10), P('TSP-IV', 'Tissue paper ivory (ream 480)', 2150, 'S', 1, 5), P('TAPE6', 'Branded paper tape (6 rolls)', 2475, 'S', 1, 5)] },
      { id: 'NKW', name: 'Norfolk Knitwear Co', addr: '14 Market Place, North Walsham NR28 9BP', vatNo: 'GB 377 6104 92', prefix: 'NKW', sort: '20-30-81', acct: '47716032', terms: { kind: 'settle', bp: 250, within: 14, days: 30 }, layout: 'formal',
        products: [P('CARD612', 'Knitted cardigan 6-12m', 1650, 'Z', 6, 24), P('BLNK', 'Cellular baby blanket', 1395, 'Z', 6, 24), P('GBOX', 'Gift box with ribbon', 275, 'S', 12, 48)] },
    ],
  },
  dental: {
    legal: 'Brightsmile Dental Care Ltd',
    sister: 'Brightsmile Orthodontics Ltd',
    address: '41 Granby Street, Leicester LE1 6EJ',
    domain: 'brightsmiledental.co.uk',
    manager: { name: 'Rhiannon Price', role: 'Practice Manager' },
    sites: [
      { name: 'Granby Street practice', addr: '41 Granby Street, Leicester LE1 6EJ', lead: 'Fatima Zahra' },
      { name: 'Oadby practice', addr: '12 The Parade, Oadby, Leicester LE2 5BJ', lead: 'Callum Reid' },
    ],
    sisterSite: { name: 'Brightsmile Orthodontics clinic', addr: '43 Granby Street, Leicester LE1 6EJ' },
    suppliers: [
      { id: 'SDS', name: 'Soar Dental Supplies Ltd', addr: '2 Soar Valley Way, Enderby, Leicester LE19 1WA', vatNo: 'GB 610 4473 28', prefix: 'SDS', sort: '30-80-47', acct: '50927716', terms: { kind: 'disc', bp: 200, within: 10, days: 30 }, layout: 'formal',
        products: [P('NGL-M', 'Nitrile gloves size M (box 200)', 785, 'S', 10, 40), P('MSK2R', 'Type IIR face masks (box 50)', 430, 'S', 10, 40), P('CMP-A2', 'Composite syringe A2 4g', 2865, 'S', 4, 16), P('BIB500', 'Patient bibs (case 500)', 1990, 'S', 2, 8)] },
      { id: 'CVI', name: 'ClearView Imaging Ltd', addr: 'Suite 3, Pegasus House, Nottingham NG7 2RU', vatNo: 'GB 255 9081 34', prefix: 'CVI-', sort: '40-11-62', acct: '81236607', terms: { kind: 'net', days: 30 }, layout: 'email',
        products: [P('PSP2', 'Phosphor imaging plates size 2 (pack 4)', 13400, 'S', 1, 3), P('SSL500', 'Sensor sleeves (box 500)', 3850, 'S', 1, 4), P('XRM100', 'X-ray film mounts (pack 100)', 1250, 'S', 4, 12)] },
      { id: 'MMW', name: 'Midshires Medical Waste Ltd', addr: 'Unit 20, Coalville Business Park, Coalville LE67 3NR', vatNo: 'GB 731 6650 92', prefix: 'MMW', sort: '20-45-77', acct: '33061894', terms: { kind: 'net', days: 14 }, layout: 'scan',
        products: [P('SHB5', 'Sharps bin 5L', 465, 'S', 6, 24), P('AMGP', 'Amalgam waste pot', 2150, 'S', 1, 4), P('GYPB', 'Gypsum waste bin 30L', 1875, 'S', 1, 4)] },
      { id: 'CPS', name: 'Charnwood Print & Signs', addr: '88 Belvoir Road, Loughborough LE11 1PT', vatNo: 'GB 402 3378 15', prefix: 'CPS', sort: '30-15-99', acct: '62710058', terms: { kind: 'eom', days: 30 }, layout: 'formal',
        products: [P('APC1K', 'Appointment cards (1000)', 4600, 'S', 1, 3), P('LTH500', 'Letterheads (500)', 3800, 'S', 1, 3), P('CHLD', "Children's oral-health leaflets (500)", 2950, 'S', 1, 3)] },
      { id: 'NPC', name: 'North Point Child Products Ltd', addr: 'Unit 8, Grove Park, Enderby LE19 1SA', vatNo: 'GB 588 0412 66', prefix: 'NPC', sort: '09-01-29', acct: '76543210', terms: { kind: 'net', days: 21 }, layout: 'email',
        products: [P('CHB', "Children's picture books for waiting room", 650, 'Z', 6, 20), P('CHCS', 'Infant car seat (staff benefit scheme)', 5995, 'R', 1, 3)] },
      { id: 'MDS', name: 'Midland Dental Sundries Ltd', addr: 'Unit 6, Meridian Business Park, Leicester LE19 1WZ', vatNo: 'GB 298 4471 05', prefix: 'MDS', sort: '30-54-66', acct: '19983307', terms: { kind: 'settle', bp: 300, within: 14, days: 30 }, layout: 'formal',
        products: [P('ITRAY', 'Impression trays (box 50)', 2400, 'S', 1, 6), P('ALG500', 'Alginate 500g', 1150, 'S', 4, 16), P('BITE', 'Bite registration cartridges (pack 4)', 3200, 'S', 1, 5)] },
    ],
  },
};

const VAT_BP = { S: 2000, R: 500, Z: 0 };
const VAT_PCT = { S: '20%', R: '5%', Z: '0%' };

export const OUTCOMES = ['approve', 'hold-bank-details', 'hold-no-po', 'hold-qty', 'hold-price', 'hold-vat', 'hold-linked', 'reject-duplicate', 'reject-wrong-entity'];

// ───────────────────────────── Case specs ─────────────────────────────
// Intents: what each planted document is meant to be. "pair:" intents create two documents.
export const SPECS = [
  { id: 'c01', tier: 'easy', biz: 'cafe', seed: 101, run: D(2026, 11, 5), intents: ['ok', 'ok-disc', 'hold-price-clear', 'ok', 'ok-partial'] },
  { id: 'c02', tier: 'easy', biz: 'baby', seed: 202, run: D(2026, 11, 12), intents: ['ok', 'ok-tolvar', 'hold-qty', 'reject-entity', 'ok-disc', 'ok'] },
  { id: 'c03', tier: 'easy', biz: 'plumbing', seed: 303, run: D(2026, 11, 19), intents: ['ok', 'reject-dup-ledger', 'hold-vat', 'ok-disc-expired', 'ok-partial', 'ok', 'hold-no-po'] },
  { id: 'c04', tier: 'medium', biz: 'dental', seed: 404, run: D(2026, 11, 26), intents: ['ok', 'ok-tolvar', 'hold-price-5', 'credit-ledger', 'ok-disc', 'hold-qty', 'reject-entity', 'hold-price-total', 'ok-settle', 'grn-revert', 'pair:consume-hold'] },
  { id: 'c05', tier: 'medium', biz: 'cafe', seed: 505, run: D(2026, 12, 3), intents: ['ok-ocr', 'hold-bank', 'ok', 'hold-price-2pc', 'ok-disc', 'pair:dup-batch', 'hold-vat', 'ok-eom', 'pair:consume-freed', 'po-amend-supplier', 'ok-settle', 'ok-tolvar-multi'] },
  { id: 'c06', tier: 'medium', biz: 'baby', seed: 606, run: D(2026, 12, 10), intents: ['ok', 'pair:credit-combined-qty', 'hold-price-under', 'ok-delivered-sister', 'hold-no-po-wrong-supplier', 'ok-tolvar', 'ok-disc-expired', 'hold-vat-rate', 'po-amend-ok', 'grn-double', 'hold-price-total', 'credit-exceeds'] },
  { id: 'c07', tier: 'hard', biz: 'plumbing', seed: 707, run: D(2026, 12, 17), intents: ['hold-qty-correction', 'ok-correction', 'pair:credit-linked-hold', 'ok-bank-format', 'hold-price-5', 'reject-dup-ledger', 'ok-disc', 'hold-bank', 'pair:consume-hold', 'pair:consume-freed', 'hold-price-total', 'ok-tolvar-multi', 'po-amend-ok', 'po-amend-supplier', 'grn-revert', 'grn-double', 'ok-settle', 'credit-exceeds'] },
  { id: 'c08', tier: 'hard', biz: 'dental', seed: 808, run: D(2027, 1, 7), intents: ['pair:credit-combined-price', 'hold-price-2pc', 'reject-entity', 'ok-delivered-sister', 'hold-qty-correction', 'ok-disc', 'pair:dup-batch', 'hold-vat', 'ok-ocr', 'ok-partial-earlier', 'pair:consume-hold', 'pair:consume-freed', 'hold-price-total', 'po-amend-supplier', 'po-amend-ok', 'grn-double', 'grn-revert', 'ok-settle', 'credit-exceeds'] },
  { id: 'c09', tier: 'hard', biz: 'cafe', seed: 909, run: D(2027, 1, 14), intents: ['ok-disc', 'pair:credit-combined-qty', 'hold-bank', 'hold-price-under', 'reject-dup-ledger', 'ok-correction', 'hold-qty-correction', 'ok-ocr', 'hold-vat-rate', 'credit-ledger', 'reject-entity', 'pair:consume-freed', 'pair:consume-hold', 'hold-price-total', 'ok-tolvar-multi', 'po-amend-ok', 'po-amend-supplier', 'grn-double', 'grn-revert', 'ok-settle', 'credit-exceeds'] },
  { id: 'c10', tier: 'hard', biz: 'baby', seed: 1010, run: D(2027, 1, 21), intents: [
    'ok', 'ok-mixed-vat', 'pair:credit-combined-price', 'pair:credit-linked-hold', 'pair:dup-batch', 'hold-qty', 'hold-price-2pc', 'hold-price-5', 'ok-ocr', 'hold-no-po-wrong-supplier',
    'ok-delivered-sister', 'ok-correction', 'hold-qty-correction', 'hold-bank', 'ok-disc', 'ok-disc-expired', 'ok-vat-rounding', 'reject-dup-ledger', 'credit-ledger', 'ok-tolvar',
    'pair:consume-hold', 'pair:consume-freed', 'pair:consume-hold', 'hold-price-total', 'hold-price-total', 'ok-tolvar-multi', 'ok-tolvar-multi', 'po-amend-ok', 'po-amend-supplier', 'po-amend-ok',
    'grn-revert', 'grn-double', 'grn-double', 'ok-settle', 'ok-settle', 'credit-exceeds', 'pair:credit-combined-qty', 'hold-vat-rate', 'reject-entity', 'ok-partial-earlier',
    'ok-eom', 'hold-price-under', 'pair:consume-freed', 'ok-bank-format', 'ok-partial'] },
];

// ───────────────────────────── Generator ─────────────────────────────
function lineNet(l) {
  return l.qty * l.price;
}

function correctVat(lines) {
  const byRate = { S: 0, R: 0, Z: 0 };
  for (const l of lines) byRate[l.vat] += lineNet(l);
  return Object.entries(byRate).reduce((s, [code, net]) => s + divHalfUp(net * VAT_BP[code], 10000), 0);
}

export function generate(spec) {
  const r = rng(spec.seed);
  const biz = BUSINESSES[spec.biz];
  const run = spec.run;
  const world = { spec, biz, run, nextRun: addDays(run, 7), pos: [], grns: [], ledger: [], docs: [], emails: [], intent: {} };
  let poNo = 4000 + r.int(10, 80) * 10 + (spec.seed % 7);
  let grnNo = 7000 + r.int(100, 600);
  let docNo = 30000 + r.int(100, 900);
  const invCounters = {};
  const nextInvNo = (sup) => {
    invCounters[sup.id] = (invCounters[sup.id] ?? 100000 + r.int(1000, 89999)) + r.int(3, 40);
    return `${sup.prefix}${invCounters[sup.id]}`;
  };
  const reserved = new Set();
  let currentIntent = '';
  const pickSupplier = (pred = () => true) => {
    const ok = biz.suppliers.filter((s) => pred(s) && !reserved.has(s.id));
    assert(ok.length, `${spec.id}: no supplier left`);
    return r.pick(ok);
  };
  const site = () => r.pick(biz.sites);

  function makePO(sup, nLines, opts = {}) {
    const avail = sup.products.filter((p) => !opts.vat || opts.vat.includes(p.vat));
    assert(avail.length >= 1, `${spec.id}: not enough products for ${sup.id}`);
    const prods = r.sample(avail, Math.min(nLines, avail.length));
    poNo += r.int(1, 9);
    const st = opts.site ?? site();
    const po = {
      no: `PO-${poNo}`,
      supplier: sup.id,
      date: addDays(run, -r.int(36, 50)),
      raisedBy: st.lead,
      site: st,
      lines: prods.map((p) => ({ code: p.code, desc: p.desc, qty: r.int(p.lo, p.hi), price: p.price, vat: p.vat, invoicedToDate: 0 })),
    };
    world.pos.push(po);
    return po;
  }
  function makeGRN(po, qtys, opts = {}) {
    grnNo += r.int(1, 12);
    const g = { no: `GRN-${grnNo}`, po: po.no, date: opts.date ?? addDays(po.date, r.int(2, 6)), site: po.site, by: po.site.lead, lines: qtys.map(([code, qty]) => ({ code, qty })), note: opts.note ?? null };
    world.grns.push(g);
    return g;
  }
  function invoiceDateFor(intent) {
    if (intent === 'ok-disc') return addDays(run, -r.int(2, 8));
    if (intent === 'ok-settle') return addDays(run, -r.int(3, 12));
    if (intent === 'ok-disc-expired') return addDays(run, -r.int(13, 19));
    return addDays(run, -r.int(6, 34));
  }
  function makeDoc(sup, po, lines, opts = {}) {
    docNo += r.int(1, 7);
    const vat = opts.vatOverride ?? correctVat(lines);
    const net = lines.reduce((s, l) => s + lineNet(l), 0);
    const d = {
      docId: `DOC-${docNo}`,
      kind: opts.kind ?? 'invoice',
      supplier: sup.id,
      number: opts.number ?? nextInvNo(sup),
      date: opts.date ?? addDays(run, -r.int(6, 34)),
      poRef: opts.poRef ?? (po ? po.no : null),
      addressee: opts.addressee ?? biz.legal,
      deliverTo: opts.deliverTo ?? (po ? po.site : site()),
      lines: lines.map((l) => ({ ...l, chargedVat: l.chargedVat ?? l.vat })),
      net,
      vat,
      gross: net + vat,
      bank: opts.bank ?? { sort: sup.sort, acct: sup.acct, style: 0 },
      layout: opts.layout ?? sup.layout,
      creditFor: opts.creditFor ?? null,
      reason: opts.reason ?? null,
      phoneOrder: !!opts.phoneOrder,
      reissue: !!opts.reissue,
      tag: currentIntent,
      received: null,
    };
    world.docs.push(d);
    return d;
  }
  const invLines = (po, qtyOf = (l) => l.qty, priceOf = (l) => l.price) =>
    po.lines.map((l) => ({ code: l.code, desc: l.desc, qty: qtyOf(l), price: priceOf(l), vat: l.vat }));

  function tolerance(poValue) {
    return Math.min(poValue * 0.02, 500);
  }
  // A unit price delta (pence) whose line variance |delta*qty| lies strictly in (lo, hi) with a margin.
  function deltaIn(qty, lo, hi, margin = 15) {
    const cands = [];
    for (let dlt = 1; dlt <= 4000; dlt++) {
      const v = dlt * qty;
      if (v > lo + margin && v < hi - margin) cands.push(dlt);
      if (v > hi) break;
    }
    return cands.length ? r.pick(cands) : null;
  }

  function plant(intent) {
    switch (intent) {
      case 'ok':
      case 'ok-settle':
      case 'ok-disc':
      case 'ok-disc-expired':
      case 'ok-eom':
      case 'ok-mixed-vat':
      case 'ok-ocr':
      case 'ok-delivered-sister':
      case 'ok-bank-format':
      case 'ok-vat-rounding': {
        let pred = () => true;
        if (intent === 'ok-disc' || intent === 'ok-disc-expired') pred = (s) => s.terms.kind === 'disc';
        if (intent === 'ok-settle') pred = (s) => s.terms.kind === 'settle';
        if (intent === 'ok-eom') pred = (s) => s.terms.kind === 'eom';
        if (intent === 'ok-ocr') pred = (s) => s.layout === 'scan';
        if (intent === 'ok-mixed-vat') pred = (s) => new Set(s.products.map((p) => p.vat)).size > 1;
        if (intent === 'ok-bank-format') pred = (s) => s.layout !== 'scan';
        const sup = pickSupplier(pred);
        const po = makePO(sup, intent === 'ok-mixed-vat' ? Math.min(sup.products.length, 3) : r.int(1, Math.min(3, sup.products.length)), intent === 'ok-settle' ? { vat: ['S'] } : {});
        if (intent === 'ok-mixed-vat') assert(new Set(po.lines.map((l) => l.vat)).size > 1 || sup.products.length < 3, 'mixed vat');
        makeGRN(po, po.lines.map((l) => [l.code, l.qty]));
        const lines = invLines(po);
        const opts = { date: invoiceDateFor(intent) };
        if (intent === 'ok-ocr') opts.poRef = po.no.replace(/0/g, 'O');
        if (intent === 'ok-delivered-sister') opts.deliverTo = biz.sisterSite;
        if (intent === 'ok-bank-format') opts.bank = { sort: sup.sort, acct: sup.acct, style: 1 };
        if (intent === 'ok-vat-rounding') {
          const v = correctVat(lines);
          opts.vatOverride = v + (r.chance(0.5) ? 1 : -1);
          if (v === 0) opts.vatOverride = v + 1;
        }
        if (intent === 'ok-ocr' && !/0/.test(po.no)) opts.poRef = po.no.replace(/^PO-/, 'P0-');
        return [makeDoc(sup, po, lines, opts)];
      }
      case 'ok-partial': {
        const sup = pickSupplier((s) => s.products.some((p) => p.hi >= 8));
        const po = makePO(sup, r.int(1, 2));
        const got = po.lines.map((l) => [l.code, Math.max(1, Math.floor(l.qty * r.int(40, 80) / 100))]);
        po.lines.forEach((l, i) => { if (got[i][1] >= l.qty) l.qty = got[i][1] + r.int(2, 6); });
        makeGRN(po, got, { note: 'Part delivery - balance to follow' });
        const lines = invLines(po, (l) => got.find((g) => g[0] === l.code)[1]);
        return [makeDoc(sup, po, lines)];
      }
      case 'ok-partial-earlier': {
        // Second delivery of a PO whose first delivery was already invoiced and paid.
        const sup = pickSupplier((s) => s.products.some((p) => p.hi >= 8));
        const po = makePO(sup, 1, { minAge: 30 });
        const l = po.lines[0];
        if (l.qty < 6) l.qty = 6 + r.int(0, 6);
        if (l.qty % 2 === 0) l.qty += 1;
        const first = Math.floor(l.qty / 2);
        const second = l.qty - first;
        makeGRN(po, [[l.code, first]], { date: addDays(po.date, 3), note: 'Part delivery' });
        makeGRN(po, [[l.code, second]], { date: addDays(run, -r.int(5, 8)), note: 'Balance of order' });
        l.invoicedToDate = first;
        const earlierGross = first * l.price + divHalfUp(first * l.price * VAT_BP[l.vat], 10000);
        world.ledger.push({ supplier: sup.id, number: nextInvNo(sup), po: po.no, gross: earlierGross, paid: addDays(run, -r.int(8, 14)) });
        return [makeDoc(sup, po, [{ code: l.code, desc: l.desc, qty: second, price: l.price, vat: l.vat }])];
      }
      case 'hold-qty': {
        const sup = pickSupplier((s) => s.products.some((p) => p.hi >= 8));
        const po = makePO(sup, r.int(1, 2));
        const got = po.lines.map((l, i) => [l.code, i === 0 ? Math.max(1, l.qty - r.int(2, Math.max(2, Math.floor(l.qty / 3)))) : l.qty]);
        makeGRN(po, got, { note: r.pick(['Short delivered - driver says rest on next drop', 'Part delivery', null]) });
        return [makeDoc(sup, po, invLines(po))];
      }
      case 'hold-qty-correction':
      case 'ok-correction': {
        const sup = pickSupplier((s) => s.products.some((p) => p.hi >= 8));
        const po = makePO(sup, 1);
        const l = po.lines[0];
        if (l.qty < 6) l.qty = 6 + r.int(0, 10);
        const short = r.int(1, Math.max(1, Math.floor(l.qty / 4)));
        const g = makeGRN(po, [[l.code, intent === 'ok-correction' ? l.qty - short : l.qty]]);
        const corrected = intent === 'ok-correction' ? l.qty : l.qty - short;
        const doc = makeDoc(sup, po, invLines(po));
        world.emails.push({ kind: 'grn-correction', grn: g.no, code: l.code, desc: l.desc, from: po.site.lead, site: po.site, was: g.lines[0].qty, now: corrected, up: intent === 'ok-correction' });
        return [doc];
      }
      case 'hold-price-clear':
      case 'hold-price-5':
      case 'hold-price-2pc':
      case 'hold-price-under':
      case 'ok-tolvar': {
        for (let tries = 0; tries < 200; tries++) {
          const sup = pickSupplier();
          const p = r.pick(sup.products);
          const qty = r.int(p.lo, p.hi);
          const poValue = qty * p.price;
          let dlt = null;
          if (intent === 'ok-tolvar') dlt = deltaIn(qty, 0, tolerance(poValue));
          if (intent === 'hold-price-clear') dlt = deltaIn(qty, Math.max(tolerance(poValue) * 2, 800), poValue * 0.15);
          if (intent === 'hold-price-5' && poValue * 0.02 > 700) dlt = deltaIn(qty, 500, poValue * 0.02);
          if (intent === 'hold-price-2pc' && poValue * 0.02 < 430) dlt = deltaIn(qty, poValue * 0.02, 500);
          if (intent === 'hold-price-under') dlt = deltaIn(qty, tolerance(poValue), Math.min(poValue * 0.12, tolerance(poValue) + 1500));
          if (!dlt || dlt >= p.price) continue;
          poNo += r.int(1, 9);
          const st = site();
          const po = { no: `PO-${poNo}`, supplier: sup.id, date: addDays(run, -r.int(36, 50)), raisedBy: st.lead, site: st, lines: [{ code: p.code, desc: p.desc, qty, price: p.price, vat: p.vat, invoicedToDate: 0 }] };
          // Sometimes a second, clean line on the same PO.
          const extra = sup.products.filter((q) => q.code !== p.code);
          if (extra.length && r.chance(0.5)) {
            const q = r.pick(extra);
            po.lines.push({ code: q.code, desc: q.desc, qty: r.int(q.lo, q.hi), price: q.price, vat: q.vat, invoicedToDate: 0 });
          }
          world.pos.push(po);
          makeGRN(po, po.lines.map((l) => [l.code, l.qty]));
          const sign = intent === 'hold-price-under' ? -1 : 1;
          return [makeDoc(sup, po, invLines(po, (l) => l.qty, (l) => (l.code === p.code ? l.price + sign * dlt : l.price)))];
        }
        assert(false, `${spec.id}: could not plant ${intent}`);
        return [];
      }
      case 'hold-vat':
      case 'hold-vat-rate': {
        const pred = intent === 'hold-vat-rate' ? (s) => s.products.some((p) => p.vat !== 'S') : () => true;
        const sup = pickSupplier(pred);
        const po = makePO(sup, r.int(1, Math.min(2, sup.products.length)), intent === 'hold-vat-rate' ? { vat: ['Z', 'R'] } : {});
        makeGRN(po, po.lines.map((l) => [l.code, l.qty]));
        const lines = invLines(po);
        if (intent === 'hold-vat-rate') {
          lines[0].chargedVat = 'S';
          const wrong = lines.map((l) => ({ ...l, vat: l.chargedVat ?? l.vat }));
          return [makeDoc(sup, po, lines, { vatOverride: correctVat(wrong) })];
        }
        const v = correctVat(lines);
        const off = v > 600 ? r.pick([Math.max(150, Math.round(v * 0.1)), r.int(150, 900), -r.int(150, Math.floor(v / 3))]) : r.int(300, 900);
        return [makeDoc(sup, po, lines, { vatOverride: v + off })];
      }
      case 'hold-bank': {
        const sup = pickSupplier((s) => s.layout !== 'scan');
        const po = makePO(sup, r.int(1, 2));
        makeGRN(po, po.lines.map((l) => [l.code, l.qty]));
        const newBank = { sort: `${r.int(10, 99)}-${r.int(10, 99)}-${r.int(10, 99)}`, acct: String(r.int(10000000, 99999999)), style: 0 };
        const doc = makeDoc(sup, po, invLines(po), { bank: newBank });
        world.emails.push({ kind: 'bank-change', supplier: sup.id, bank: newBank });
        return [doc];
      }
      case 'hold-no-po':
      case 'hold-no-po-wrong-supplier': {
        const sup = pickSupplier();
        const p = r.pick(sup.products);
        const qty = r.int(p.lo, p.hi);
        let poRef = null;
        if (intent === 'hold-no-po-wrong-supplier') {
          const other = world.pos.filter((x) => x.supplier !== sup.id);
          poRef = other.length ? r.pick(other).no : null;
          assert(poRef, 'wrong-supplier PO needs another PO first');
        }
        return [makeDoc(sup, null, [{ code: p.code, desc: p.desc, qty, price: p.price, vat: p.vat }], { poRef, deliverTo: site(), phoneOrder: true })];
      }
      case 'reject-entity': {
        const sup = pickSupplier();
        const po = makePO(sup, r.int(1, 2));
        makeGRN(po, po.lines.map((l) => [l.code, l.qty]));
        return [makeDoc(sup, po, invLines(po), { addressee: biz.sister })];
      }
      case 'reject-dup-ledger': {
        const sup = pickSupplier((s) => s.terms.kind !== 'disc');
        const po = makePO(sup, r.int(1, 2), { minAge: 25 });
        makeGRN(po, po.lines.map((l) => [l.code, l.qty]));
        const lines = invLines(po);
        const gross = lines.reduce((s, l) => s + lineNet(l), 0) + correctVat(lines);
        po.lines.forEach((l) => (l.invoicedToDate = l.qty));
        world.ledger.push({ supplier: sup.id, number: nextInvNo(sup), po: po.no, gross, paid: addDays(run, -r.int(3, 12)) });
        return [makeDoc(sup, po, lines, { date: addDays(run, -r.int(2, 9)), reissue: true })];
      }
      case 'pair:dup-batch': {
        const sup = pickSupplier((s) => s.terms.kind !== 'disc');
        const po = makePO(sup, r.int(1, 2));
        makeGRN(po, po.lines.map((l) => [l.code, l.qty]));
        const lines = invLines(po);
        const a = makeDoc(sup, po, lines, { layout: 'email' });
        const b = makeDoc(sup, po, lines, { date: addDays(a.date, r.int(1, 4)), layout: sup.layout === 'email' ? 'formal' : sup.layout, reissue: true });
        world.intent[a.docId] = 'approve';
        return [b];
      }
      case 'credit-ledger': {
        const sup = pickSupplier((s) => s.layout !== 'scan');
        const po = makePO(sup, 1, { minAge: 25 });
        const l = po.lines[0];
        if (l.qty < 4) l.qty = 4 + r.int(0, 4);
        makeGRN(po, [[l.code, l.qty]]);
        l.invoicedToDate = l.qty;
        const origNo = nextInvNo(sup);
        const lines0 = invLines(po);
        world.ledger.push({ supplier: sup.id, number: origNo, po: po.no, gross: lines0.reduce((s, x) => s + lineNet(x), 0) + correctVat(lines0), paid: addDays(run, -r.int(4, 15)) });
        const back = r.int(1, Math.max(1, Math.floor(l.qty / 3)));
        return [makeDoc(sup, po, [{ code: l.code, desc: l.desc, qty: back, price: l.price, vat: l.vat, cnKind: 'return' }], { kind: 'credit', number: `CN-${origNo}`, creditFor: origNo, date: addDays(run, -r.int(1, 6)), reason: r.pick(['damaged in transit, returned with driver', 'short-dated stock returned', 'wrong size sent and collected']) })];
      }
      case 'pair:credit-combined-qty':
      case 'pair:credit-combined-price':
      case 'pair:credit-linked-hold': {
        const sup = pickSupplier((s) => s.terms.kind === 'net' && s.terms.days <= 30 && s.layout !== 'scan' && s.products.some((p) => p.hi >= 8));
        const po = makePO(sup, 1, { minAge: 18 });
        const l = po.lines[0];
        if (l.qty < 8) l.qty = 8 + r.int(0, 8);
        const date = addDays(run, -r.int(24, 27)); // net terms: due before the next run
        if (intent === 'pair:credit-combined-qty') {
          const back = r.int(1, Math.max(1, Math.floor(l.qty / 4)));
          makeGRN(po, [[l.code, l.qty - back]], { note: `${back} x ${l.desc} refused on delivery (damaged), sent back with driver` });
          const inv = makeDoc(sup, po, invLines(po), { date });
          const cn = makeDoc(sup, po, [{ code: l.code, desc: l.desc, qty: back, price: l.price, vat: l.vat, cnKind: 'return' }], { kind: 'credit', number: `CN-${inv.number}`, creditFor: inv.number, date: addDays(date, r.int(1, 3)), reason: 'goods refused on delivery' });
          world.intent[inv.docId] = 'approve';
          world.intent[cn.docId] = 'approve';
          return [];
        }
        makeGRN(po, [[l.code, l.qty]]);
        if (intent === 'pair:credit-combined-price') {
          // Invoice over-charged beyond tolerance; credit note brings it back to the PO price.
          let dlt = deltaIn(l.qty, Math.max(tolerance(l.qty * l.price) + 100, 600), l.qty * l.price * 0.2);
          if (!dlt) dlt = Math.ceil((tolerance(l.qty * l.price) + 700) / l.qty);
          const inv = makeDoc(sup, po, invLines(po, (x) => x.qty, (x) => x.price + dlt), { date });
          const cn = makeDoc(sup, po, [{ code: l.code, desc: `Price adjustment: ${l.desc} (agreed price ${gbp(l.price)})`, qty: l.qty, price: dlt, vat: l.vat, cnKind: 'price' }], { kind: 'credit', number: `CN-${inv.number}`, creditFor: inv.number, date: addDays(date, r.int(1, 4)), reason: 'price charged above agreed price' });
          world.intent[inv.docId] = 'approve';
          world.intent[cn.docId] = 'approve';
          return [];
        }
        // linked hold: the invoice has wrong VAT; its credit note must wait.
        const lines = invLines(po);
        const v = correctVat(lines);
        const inv = makeDoc(sup, po, lines, { date, vatOverride: v + r.int(300, 1200) });
        const back = r.int(1, 2);
        const cn = makeDoc(sup, po, [{ code: l.code, desc: l.desc, qty: back, price: l.price, vat: l.vat, cnKind: 'return' }], { kind: 'credit', number: `CN-${inv.number}`, creditFor: inv.number, date: addDays(date, 2), reason: 'goods returned (damaged packaging)' });
        world.intent[inv.docId] = 'hold-vat';
        world.intent[cn.docId] = 'hold-linked';
        return [];
      }
      case 'pair:consume-hold':
      case 'pair:consume-freed': {
        // Two invoices on one PO line. The first one uses up most of what was received, unless it is itself held.
        const sup = pickSupplier((s) => s.terms.kind === 'net' && s.products.some((p) => p.hi >= 8));
        const prod = r.pick(sup.products.filter((p) => p.hi >= 8));
        poNo += r.int(1, 9);
        const st = site();
        const po = { no: `PO-${poNo}`, supplier: sup.id, date: addDays(run, -r.int(40, 50)), raisedBy: st.lead, site: st, lines: [{ code: prod.code, desc: prod.desc, qty: Math.max(prod.hi, 12) + r.int(0, 8), price: prod.price, vat: prod.vat, invoicedToDate: 0 }] };
        world.pos.push(po);
        const l = po.lines[0];
        const received = l.qty - r.int(1, 3);
        makeGRN(po, [[l.code, received]], { note: 'Part delivery - balance to follow' });
        const a = Math.max(3, Math.floor((received * r.int(45, 65)) / 100));
        let bq = received - a + r.int(1, 2);
        if (bq === a) bq = bq + 1 <= received ? bq + 1 : bq - 1;
        assert(bq !== a && bq <= received && a + bq > received, 'consume quantities');
        const freed = intent === 'pair:consume-freed';
        let priceA = l.price;
        if (freed) priceA = l.price + (deltaIn(a, Math.max(tolerance(a * l.price) * 2, 700), a * l.price * 0.2) ?? Math.ceil((tolerance(a * l.price) + 800) / a));
        const dA = addDays(run, -r.int(16, 30));
        const A = makeDoc(sup, po, [{ code: l.code, desc: l.desc, qty: a, price: priceA, vat: l.vat }], { date: dA });
        const B = makeDoc(sup, po, [{ code: l.code, desc: l.desc, qty: bq, price: l.price, vat: l.vat }], { date: addDays(dA, r.int(3, 8)) });
        B.after = A;
        world.intent[A.docId] = freed ? 'hold-price' : 'approve';
        world.intent[B.docId] = freed ? 'approve' : 'hold-qty';
        return [];
      }
      case 'hold-price-total':
      case 'ok-tolvar-multi': {
        // Every line is inside its own tolerance; only the £10 cap on the whole document decides.
        for (let tries = 0; tries < 400; tries++) {
          const sup = pickSupplier((s) => s.products.length >= 3);
          const prods = r.sample(sup.products, Math.min(sup.products.length, intent === 'hold-price-total' ? 4 : 3));
          const ls = prods.map((p) => ({ p, qty: r.int(Math.ceil((p.lo + p.hi) / 2), p.hi) }));
          const deltas = [];
          for (const x of ls) {
            const tol = tolerance(x.qty * x.p.price);
            const cand = [];
            for (let dl = 1; dl * x.qty < tol - 15; dl++) if (intent === 'hold-price-total' ? dl * x.qty > tol * 0.55 : dl * x.qty > 15) cand.push(dl);
            if (!cand.length) break;
            deltas.push(intent === 'hold-price-total' ? r.pick(cand) : cand[0]);
          }
          if (deltas.length !== ls.length || deltas.some((dl, i) => dl >= ls[i].p.price)) continue;
          const sum = ls.reduce((t, x, i) => t + deltas[i] * x.qty, 0);
          if (intent === 'hold-price-total' ? sum <= 1040 : sum >= 960) continue;
          poNo += r.int(1, 9);
          const st = site();
          const po = { no: `PO-${poNo}`, supplier: sup.id, date: addDays(run, -r.int(36, 50)), raisedBy: st.lead, site: st, lines: ls.map((x) => ({ code: x.p.code, desc: x.p.desc, qty: x.qty, price: x.p.price, vat: x.p.vat, invoicedToDate: 0 })) };
          world.pos.push(po);
          makeGRN(po, po.lines.map((x) => [x.code, x.qty]));
          const signs = intent === 'hold-price-total' ? [1, -1, 1, -1] : [1, 1, -1, 1];
          return [makeDoc(sup, po, po.lines.map((x, i) => ({ code: x.code, desc: x.desc, qty: x.qty, price: x.price + signs[i] * deltas[i], vat: x.vat })))];
        }
        assert(false, `${spec.id}: could not plant ${intent}`);
        return [];
      }
      case 'po-amend-ok':
      case 'po-amend-supplier': {
        // The invoice price is above tolerance against the PO; an email amends the price, but only the buyer's counts.
        for (let tries = 0; tries < 200; tries++) {
          const sup = pickSupplier((s) => s.layout !== 'scan');
          const prod = r.pick(sup.products);
          const qty = r.int(prod.lo, prod.hi);
          const dl = deltaIn(qty, Math.max(tolerance(qty * prod.price) * 2, 600), qty * prod.price * 0.12);
          if (!dl) continue;
          poNo += r.int(1, 9);
          const st = site();
          const po = { no: `PO-${poNo}`, supplier: sup.id, date: addDays(run, -r.int(36, 50)), raisedBy: st.lead, site: st, lines: [{ code: prod.code, desc: prod.desc, qty, price: prod.price, vat: prod.vat, invoicedToDate: 0 }] };
          world.pos.push(po);
          makeGRN(po, [[prod.code, qty]]);
          const doc = makeDoc(sup, po, [{ code: prod.code, desc: prod.desc, qty, price: prod.price + dl, vat: prod.vat }]);
          const buyer = intent === 'po-amend-ok';
          world.emails.push({ kind: 'po-amend', po: po.no, code: prod.code, desc: prod.desc, price: prod.price + dl, was: prod.price, from: buyer ? po.raisedBy : `${sup.name} sales`, supplier: buyer ? null : sup.id, site: po.site });
          return [doc];
        }
        assert(false, `${spec.id}: could not plant ${intent}`);
        return [];
      }
      case 'grn-revert':
      case 'grn-double': {
        const sup = pickSupplier((s) => s.products.some((p) => p.hi >= 10));
        const po = makePO(sup, 1);
        const l = po.lines[0];
        if (l.qty < 10) l.qty = 10 + r.int(0, 10);
        const g = makeGRN(po, [[l.code, l.qty]]);
        const first = l.qty - r.int(3, Math.max(3, Math.floor(l.qty / 3)));
        world.emails.push({ kind: 'grn-correction', grn: g.no, code: l.code, desc: l.desc, from: po.site.lead, site: po.site, was: l.qty, now: first, up: false });
        let billed = l.qty;
        if (intent === 'grn-revert') world.emails.push({ kind: 'grn-revert', grn: g.no, code: l.code, desc: l.desc, from: po.site.lead, site: po.site, original: l.qty });
        else {
          const second = first + r.int(1, l.qty - first - 1 > 1 ? l.qty - first - 1 : 1);
          world.emails.push({ kind: 'grn-correction', grn: g.no, code: l.code, desc: l.desc, from: po.site.lead, site: po.site, was: first, now: second, up: true, again: true });
          billed = second;
        }
        return [makeDoc(sup, po, [{ code: l.code, desc: l.desc, qty: billed, price: l.price, vat: l.vat }])];
      }
      case 'credit-exceeds': {
        // A big credit note and one small invoice from the same supplier: their net payment this run is negative.
        const fits = (s) => s.terms.kind === 'net' && s.terms.days <= 21 && s.products.some((p) => p.price * p.hi >= 9000);
        // Keep the scanning supplier free when the case needs an OCR invoice; otherwise use it first.
        const wantScan = !spec.intents.includes('ok-ocr') && biz.suppliers.some((s) => fits(s) && s.layout === 'scan');
        const sup = wantScan ? pickSupplier((s) => fits(s) && s.layout === 'scan') : pickSupplier((s) => fits(s) && s.layout !== 'scan');
        reserved.add(sup.id);
        const big = r.pick(sup.products.filter((p) => p.price * p.hi >= 9000));
        const po1 = makePO(sup, 1);
        po1.lines = [{ code: big.code, desc: big.desc, qty: big.hi, price: big.price, vat: big.vat, invoicedToDate: big.hi }];
        makeGRN(po1, [[big.code, big.hi]]);
        const origNo = nextInvNo(sup);
        const l0 = po1.lines[0];
        const g0 = l0.qty * l0.price + divHalfUp(l0.qty * l0.price * VAT_BP[l0.vat], 10000);
        world.ledger.push({ supplier: sup.id, number: origNo, po: po1.no, gross: g0, paid: addDays(run, -r.int(4, 15)) });
        let back = Math.max(2, Math.floor(l0.qty * 0.6));
        const cn = makeDoc(sup, po1, [{ code: l0.code, desc: l0.desc, qty: back, price: l0.price, vat: l0.vat, cnKind: 'return' }], { kind: 'credit', number: `CN-${origNo}`, creditFor: origNo, date: addDays(run, -r.int(2, 6)), reason: 'faulty batch returned for credit' });
        const small = r.pick(sup.products.filter((p) => p.code !== big.code)) ?? big;
        const po2 = makePO(sup, 1);
        po2.lines = [{ code: small.code, desc: small.desc, qty: small.lo, price: small.price, vat: small.vat, invoicedToDate: 0 }];
        makeGRN(po2, [[small.code, small.lo]]);
        const inv = makeDoc(sup, po2, [{ code: small.code, desc: small.desc, qty: small.lo, price: small.price, vat: small.vat }], { date: addDays(run, -r.int(sup.terms.days + 1, sup.terms.days + 6)) });
        assert(cn.gross > inv.gross, 'credit must exceed the invoice');
        world.intent[cn.docId] = 'approve';
        world.intent[inv.docId] = 'approve';
        world.netNegative = sup.id;
        return [];
      }
      default:
        throw new Error(`unknown intent ${intent}`);
    }
  }

  const INTENT_OUTCOME = (i) =>
    i.startsWith('ok') ? 'approve' : i.startsWith('hold-price') ? 'hold-price' : i.startsWith('hold-qty') ? 'hold-qty' : i.startsWith('hold-vat') ? 'hold-vat' : i.startsWith('hold-no-po') ? 'hold-no-po' : i === 'hold-bank' ? 'hold-bank-details' : i === 'reject-entity' ? 'reject-wrong-entity' : i.startsWith('reject-dup') || i === 'pair:dup-batch' ? 'reject-duplicate' : i === 'credit-ledger' || i === 'po-amend-ok' || i.startsWith('grn-') ? 'approve' : i === 'po-amend-supplier' ? 'hold-price' : null;

  // Plant in an order that lets "wrong supplier PO" intents find other POs; then shuffle the inbox.
  const rank = (x) => (x === 'credit-exceeds' ? -1 : x.includes('wrong-supplier') ? 1 : 0);
  const order = spec.intents.slice().sort((a, b) => rank(a) - rank(b));
  for (const intent of order) {
    currentIntent = intent;
    const docs = plant(intent);
    for (const d of docs) world.intent[d.docId] = INTENT_OUTCOME(intent);
    if (intent === 'ok-ocr') docs[0].layout = 'scan';
  }
  // Inbox order = order of receipt (a few days after the document date, all before the run). DOC numbers follow it.
  const cutoff = addDays(run, -2);
  for (const d of world.docs) {
    let day = addDays(d.date, r.int(1, 4));
    if (day > cutoff) day = cutoff;
    d.received = { date: day, hh: r.int(7, 15), mm: r.int(0, 59) };
  }
  const rk = (d) => d.received.date.getTime() + (d.received.hh * 60 + d.received.mm) * 60000;
  let docs = world.docs.slice().sort((a, b) => rk(a) - rk(b));
  for (let i = 0; i < docs.length; i++) {
    const d = docs[i];
    if (!d.reissue && !d.after) continue;
    const j = d.after ? docs.indexOf(d.after) : docs.findIndex((o) => o !== d && o.kind === 'invoice' && o.supplier === d.supplier && o.poRef === d.poRef && o.gross === d.gross);
    if (j > i) {
      d.received = { ...docs[j].received, mm: Math.min(59, docs[j].received.mm + 1 + r.int(0, 3)), hh: docs[j].received.hh + (docs[j].received.mm >= 56 ? 1 : 0) };
      docs = world.docs.slice().sort((a, b) => rk(a) - rk(b));
      i = -1;
    }
  }
  let n = 30000 + (spec.seed % 89) * 7 + 120;
  const remap = {};
  for (const d of docs) {
    n += r.int(1, 6);
    remap[d.docId] = `DOC-${n}`;
    d.docId = `DOC-${n}`;
  }
  world.intent = Object.fromEntries(Object.entries(world.intent).map(([k, v]) => [remap[k], v]));
  world.docs = docs;
  // Paperwork chronology: every PO is raised before its GRNs, and GRNs come before the invoices that bill them.
  for (const po of world.pos) {
    const billed = world.docs.filter((d) => d.poRef === po.no || (d.poRef && d.poRef.replace(/O/g, '0') === po.no.replace(/O/g, '0')));
    const firstDoc = billed.length ? new Date(Math.min(...billed.map((d) => d.date.getTime()))) : addDays(run, -3);
    const grns = world.grns.filter((g) => g.po === po.no).sort((a, b) => a.date - b.date);
    const earlyPaid = world.ledger.some((L) => L.po === po.no);
    const latestFirst = earlyPaid ? addDays(firstDoc, -20) : firstDoc;
    if (po.date > addDays(latestFirst, -(3 + grns.length * 3))) po.date = addDays(latestFirst, -(4 + grns.length * 3 + r.int(0, 6)));
    grns.forEach((g, i) => {
      const lo = addDays(po.date, 1 + i * 3);
      const hi = i === grns.length - 1 ? firstDoc : addDays(firstDoc, -20);
      if (g.date < lo || g.date > hi) g.date = addDays(lo, r.int(0, 1));
    });
  }
  // Filler ledger lines (other suppliers' paid invoices) so the ledger is not a list of answers.
  for (let k = 0; k < 3 + Math.floor(world.docs.length / 3); k++) {
    const sup = pickSupplier();
    world.ledger.push({ supplier: sup.id, number: nextInvNo(sup), po: `PO-${3700 + r.int(10, 290)}`, gross: r.int(4000, 90000), paid: addDays(run, -r.int(3, 40)) });
  }
  world.ledger.sort((a, b) => a.paid - b.paid || a.number.localeCompare(b.number));
  world.pos.sort((a, b) => a.no.localeCompare(b.no));
  world.grns.sort((a, b) => a.no.localeCompare(b.no));
  return world;
}

// ───────────────────────────── Independent rules engine ─────────────────────────────
const digits = (s) => String(s).replace(/\D/g, '');
const refNorm = (s) => (s == null ? '' : String(s).toUpperCase().replace(/O/g, '0').replace(/\s/g, ''));

/** Apply policy FIN-04 to the world's documents. `flags` switch rules off to script careless answers. */
export function solve(world, flags = {}) {
  const { biz, run, nextRun } = world;
  const sup = Object.fromEntries(biz.suppliers.map((s) => [s.id, s]));
  // GRN figures after the site managers' emails (in the order sent: the latest word counts, "ignore my last
  // email" restores the GRN), and PO lines after amendments by the person who raised the PO.
  const grnLine = {};
  for (const g of world.grns) for (const l of g.lines) grnLine[`${g.no}:${l.code}`] = l.qty;
  const poPrice = {};
  const poQty = {};
  for (const po of world.pos) for (const l of po.lines) {
    poPrice[`${po.no}:${l.code}`] = l.price;
    poQty[`${po.no}:${l.code}`] = l.qty;
  }
  if (!flags.ignoreEmails) {
    for (const e of world.emails) {
      if (e.kind === 'grn-correction') grnLine[`${e.grn}:${e.code}`] = e.now;
      if (e.kind === 'grn-revert') grnLine[`${e.grn}:${e.code}`] = e.original;
      if (e.kind === 'po-amend') {
        const po = world.pos.find((x) => x.no === e.po);
        if (flags.anyAmendment || e.from === po.raisedBy) {
          if (e.price !== undefined) poPrice[`${e.po}:${e.code}`] = e.price;
          if (e.qty !== undefined) poQty[`${e.po}:${e.code}`] = e.qty;
        }
      }
    }
  }
  const grnQty = {};
  for (const g of world.grns)
    for (const l of g.lines) {
      const k = `${g.po}:${l.code}`;
      grnQty[k] = (grnQty[k] ?? 0) + grnLine[`${g.no}:${l.code}`];
    }
  const findPO = (ref, supplierId) => world.pos.find((p) => (flags.noOcr ? p.no === ref : refNorm(p.no) === refNorm(ref)) && p.supplier === supplierId);
  const vatOk = (doc, po) => {
    if (flags.noVat) return true;
    const lines = doc.lines.map((l) => ({ ...l, vat: po?.lines.find((x) => x.code === l.code)?.vat ?? l.vat }));
    return Math.abs(doc.vat - correctVat(lines)) <= 2;
  };
  const bankOk = (doc) => flags.noBank || (digits(doc.bank.sort) === digits(sup[doc.supplier].sort) && digits(doc.bank.acct) === digits(sup[doc.supplier].acct));
  const credits = {};
  for (const d of world.docs) if (d.kind === 'credit') (credits[d.creditFor] ??= []).push(d);
  const consumed = {};

  const out = {};
  const invoiceOutcome = (doc, idx) => {
    if (!flags.noEntity && doc.addressee !== biz.legal) return 'reject-wrong-entity';
    if (!flags.noDup) {
      const dupL = world.ledger.some((L) => L.supplier === doc.supplier && refNorm(L.po) === refNorm(doc.poRef) && L.gross === doc.gross);
      const dupB = world.docs.slice(0, idx).some((o) => o.kind === 'invoice' && o.supplier === doc.supplier && refNorm(o.poRef) === refNorm(doc.poRef) && o.gross === doc.gross);
      if (dupL || dupB) return 'reject-duplicate';
    }
    if (!bankOk(doc)) return 'hold-bank-details';
    const po = doc.poRef ? findPO(doc.poRef, doc.supplier) : null;
    if (!po) return 'hold-no-po';
    const cns = flags.noCredits ? [] : credits[doc.number] ?? [];
    const eff = [];
    for (const l of doc.lines) {
      const pl = po.lines.find((x) => x.code === l.code);
      assert(pl, `${doc.docId}: line ${l.code} not on ${po.no}`);
      let qty = l.qty;
      let value = lineNet(l);
      for (const cn of cns)
        for (const c of cn.lines)
          if (c.code === l.code) {
            value -= lineNet(c);
            if (c.cnKind === 'return') qty -= c.qty;
          }
      eff.push({ key: `${po.no}:${l.code}`, qty, value, pl });
    }
    if (flags.consumeHeld) for (const x of eff) consumed[x.key] = (consumed[x.key] ?? 0) + x.qty;
    for (const x of eff) {
      const used = flags.noConsume ? 0 : (consumed[x.key] ?? 0) - (flags.consumeHeld ? x.qty : 0);
      const available = (grnQty[x.key] ?? 0) - x.pl.invoicedToDate - used;
      if (!flags.noQty && x.qty > available) return 'hold-qty';
    }
    let totalDiff = 0;
    for (const x of eff) {
      const poValue = poPrice[x.key] * x.qty;
      const diff = Math.abs(x.value - poValue);
      totalDiff += diff;
      const tolOk = flags.tolFiveOnly ? diff <= 500 : diff * 50 <= poValue && diff <= 500;
      if (!flags.noPrice && !tolOk) return 'hold-price';
    }
    if (!flags.noPrice && !flags.noTotalCap && totalDiff > 1000) return 'hold-price';
    if (!vatOk(doc, po)) return 'hold-vat';
    if (!flags.consumeHeld) for (const x of eff) consumed[x.key] = (consumed[x.key] ?? 0) + x.qty;
    return 'approve';
  };
  world.docs.forEach((doc, idx) => {
    if (doc.kind === 'invoice') out[doc.docId] = { outcome: invoiceOutcome(doc, idx) };
  });
  world.docs.forEach((doc) => {
    if (doc.kind !== 'credit') return;
    let o;
    const po = findPO(doc.poRef, doc.supplier);
    if (!flags.noEntity && doc.addressee !== biz.legal) o = 'reject-wrong-entity';
    else if (!bankOk(doc)) o = 'hold-bank-details';
    else if (!vatOk(doc, po)) o = 'hold-vat';
    else {
      const inBatch = world.docs.find((d) => d.kind === 'invoice' && d.supplier === doc.supplier && d.number === doc.creditFor);
      const onLedger = world.ledger.some((L) => L.supplier === doc.supplier && L.number === doc.creditFor);
      assert(inBatch || onLedger, `${doc.docId}: credit note for an unknown invoice`);
      o = inBatch && !flags.noCredits ? (out[inBatch.docId].outcome === 'approve' ? 'approve' : 'hold-linked') : 'approve';
    }
    out[doc.docId] = { outcome: o };
  });
  // Amounts and pay-by dates.
  const docs = {};
  const inRun = [];
  for (const doc of world.docs) {
    const o = out[doc.docId].outcome;
    let amount = 0;
    let payBy = null;
    if (o === 'approve') {
      if (doc.kind === 'credit') {
        amount = -doc.gross;
        payBy = run;
      } else {
        const t = sup[doc.supplier].terms;
        if (t.kind === 'net') payBy = addDays(doc.date, t.days);
        if (t.kind === 'eom') payBy = addDays(lastOfMonth(doc.date), t.days);
        if (t.kind === 'disc' || t.kind === 'settle') {
          const deadline = addDays(doc.date, t.within);
          if (!flags.noDiscount && deadline >= run) {
            payBy = deadline;
            const base = t.kind === 'settle' && !flags.settleOnGross ? doc.net : doc.gross;
            amount = doc.gross - divHalfUp(base * t.bp, 10000);
          } else payBy = addDays(doc.date, t.days);
        }
        if (!amount) amount = doc.gross;
      }
      if (payBy < nextRun) inRun.push({ doc, amount });
    }
    docs[doc.docId] = { outcome: o, amount_to_pay: amount / 100, pay_by: payBy ? iso(payBy) : null };
  }
  // A supplier whose documents in this run come to zero or less is not paid in this run at all.
  const bySupplier = {};
  for (const x of inRun) bySupplier[x.doc.supplier] = (bySupplier[x.doc.supplier] ?? 0) + x.amount;
  let total = 0;
  for (const x of inRun) if (flags.noSupplierNet || bySupplier[x.doc.supplier] > 0) total += x.amount;
  return { documents: docs, total_payable_this_run: Math.round(total) / 100 };
}

/** A careless clerk: approves everything at face value, net 30 from the invoice date, pays it all now. */
export function careless(world) {
  const docs = {};
  let total = 0;
  for (const d of world.docs) {
    const amt = d.kind === 'credit' ? -d.gross : d.gross;
    total += amt;
    docs[d.docId] = { outcome: 'approve', amount_to_pay: amt / 100, pay_by: iso(addDays(d.date, 30)) };
  }
  return { documents: docs, total_payable_this_run: Math.round(total) / 100 };
}

// ───────────────────────────── Rendering ─────────────────────────────
function termsText(t) {
  if (t.kind === 'net') return `Net ${t.days}`;
  if (t.kind === 'eom') return `${t.days} days EOM`;
  if (t.kind === 'settle') return `${t.bp / 100}% on goods in ${t.within} days, net ${t.days}`;
  return `${t.bp / 100}/${t.within} net ${t.days}`;
}

function bankText(b, style) {
  if (style === 1) return `Sort code ${digits(b.sort)}, account no. ${b.acct.slice(0, 4)} ${b.acct.slice(4)}`;
  return `Sort code ${b.sort}  Account ${b.acct}`;
}

const ocr = (s) => s.toUpperCase().replace(/ LTD/g, ' LTD.').replace(/&/g, '+');

function renderDoc(world, d) {
  const s = world.biz.suppliers.find((x) => x.id === d.supplier);
  const rcv = `received ${dMonY(d.received.date)} ${String(d.received.hh).padStart(2, '0')}:${String(d.received.mm).padStart(2, '0')}`;
  const isCN = d.kind === 'credit';
  const title = isCN ? 'CREDIT NOTE' : 'TAX INVOICE';
  const lineRows = d.lines.map((l) => {
    const rate = VAT_PCT[l.chargedVat];
    if (isCN && l.cnKind === 'price') return `  ${l.code.padEnd(7)} ${l.desc}\n          ${l.qty} x ${num2(l.price)} credit, VAT ${rate}, net ${num2(lineNet(l))}`;
    return `  ${l.code.padEnd(7)} ${l.desc.padEnd(42)} ${String(l.qty).padStart(5)} x ${num2(l.price).padStart(7)}  VAT ${rate.padEnd(3)}  ${num2(lineNet(l)).padStart(9)}`;
  });
  const orderRef = d.poRef ? d.poRef : d.phoneOrder ? '(phone order - no PO quoted)' : '-';
  const bank = bankText(d.bank, d.bank.style);
  if (d.layout === 'scan' && !isCN) {
    return [
      `[${d.docId}] ${rcv} - scanned paper invoice (posted to the office). OCR text below, may contain misreads.`,
      `  ${ocr(s.name)}   ${ocr(s.addr)}`,
      `  VAT N0. ${s.vatNo.replace(/0/g, 'O')}`,
      `  ${title}   N0: ${d.number}   DATE ${dmy(d.date)}`,
      `  INVOICE T0: ${ocr(d.addressee)}  ${ocr(world.biz.address)}`,
      `  DELIVERED: ${ocr(d.deliverTo.name)}`,
      `  CUST ORDER REF: ${d.poRef ?? 'N/A'}`,
      ...lineRows.map((x) => x.toUpperCase()),
      `  G00DS TOTAL ${num2(d.net)}   VAT ${num2(d.vat)}   TOTAL DUE ${num2(d.gross)}`,
      `  PAYMENT BY BACS: ${bank.toUpperCase()}`,
      `  E.+O.E.  Goods remain our property until paid in full.`,
    ].join('\n');
  }
  if (d.layout === 'email' && !isCN) {
    const who = r0(d.docId, ['Lou', 'Nadia', 'Sam', 'Imran', 'Beth', 'Oskar']);
    return [
      `[${d.docId}] ${rcv} - email body, no attachment`,
      `  From: accounts@${s.name.toLowerCase().replace(/ (ltd|co)$/, '').replace(/[^a-z]+/g, '')}.co.uk`,
      `  Subject: Invoice ${d.number}`,
      `  Hi, please see our invoice below for ${d.deliverTo.name}. Any probs give me a shout.`,
      `  INVOICE ${d.number} | dated ${dMonY(d.date)} | your PO: ${orderRef}`,
      `  Bill to: ${d.addressee}, ${world.biz.address}`,
      ...lineRows,
      `  Subtotal ${gbp(d.net)} | VAT ${gbp(d.vat)} | Total ${gbp(d.gross)}`,
      `  Please pay to: ${bank}`,
      `  Thanks, ${who} - ${s.name}`,
    ].join('\n');
  }
  return [
    `[${d.docId}] ${rcv} - PDF attachment ${d.number.replace(/[/ ]/g, '-')}.pdf`,
    `  ${s.name.toUpperCase()} | ${s.addr} | VAT reg ${s.vatNo}`,
    `  ${title} ${d.number}    Date: ${dmy(d.date)}${isCN ? `    Against invoice: ${d.creditFor}    Reason: ${d.reason}` : ''}`,
    `  Invoice to: ${d.addressee}, ${world.biz.address}`,
    `  Deliver to: ${d.deliverTo.name}, ${d.deliverTo.addr}`,
    `  Your order ref: ${orderRef}`,
    ...lineRows,
    `  Net ${num2(d.net)}    VAT ${num2(d.vat)}    ${isCN ? 'Total credit' : 'Invoice total'} ${num2(d.gross)}`,
    isCN ? `  This credit will be offset against your account.` : `  BACS: ${bank}    Terms: as per account`,
  ].join('\n');
}

function r0(key, xs) {
  let h = 0;
  for (const c of key) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return xs[h % xs.length];
}

function renderEmail(world, e, i) {
  const s = e.supplier ? world.biz.suppliers.find((x) => x.id === e.supplier) : null;
  const mins = 8 * 60 + i * 53;
  const when = `${ukShort(addDays(world.run, -3 + Math.floor(mins / (10 * 60))))} ${String(Math.floor((mins % (10 * 60)) / 60) + 8).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
  if (e.kind === 'grn-correction') {
    const first = e.from.split(' ')[0].toLowerCase();
    return [
      `From: ${e.from} <${first}@${world.biz.domain}> (site manager, ${e.site.name})`,
      `Sent: ${when}`,
      `Subject: RE: ${e.grn} - miscount`,
      e.again
        ? `Me again on ${e.grn} - found more ${e.desc} behind the cold-room door, so it's ${e.now}, not the ${e.was} I said. Please correct the GRN to ${e.now}. Last word on this one, promise.`
        : e.up
        ? `Hi both - sorry, on ${e.grn} I put ${e.was} x ${e.desc} but the rest turned up on a second cage the same afternoon. We received ${e.now} in total. Please correct the GRN to ${e.now}.`
        : `Hi both - recount on ${e.grn}: we only had ${e.now} x ${e.desc}, not ${e.was}. The driver took the rest back (short-dated). Please correct the GRN to ${e.now}.`,
      `Cheers, ${e.from.split(' ')[0]}`,
    ].join('\n');
  }
  if (e.kind === 'grn-revert') {
    const first = e.from.split(' ')[0].toLowerCase();
    return [
      `From: ${e.from} <${first}@${world.biz.domain}> (site manager, ${e.site.name})`,
      `Sent: ${when}`,
      `Subject: RE: RE: ${e.grn} - miscount`,
      `Ignore my last email about ${e.grn}, I was looking at the wrong delivery. The GRN was right first time (${e.original} x ${e.desc}). Sorry!`,
    ].join('\n');
  }
  if (e.kind === 'po-amend') {
    const po = world.pos.find((x) => x.no === e.po);
    if (!e.supplier) {
      const first = e.from.split(' ')[0].toLowerCase();
      return [
        `From: ${e.from} <${first}@${world.biz.domain}> (${po.site.name}, raised ${e.po})`,
        `Sent: ${when}`,
        `Subject: ${e.po} amended (rev B)`,
        `FYI I've agreed a new price with the supplier on ${e.po}: ${e.desc} now ${gbp(e.price)} each (was ${gbp(e.was)}). Please treat the PO as amended.`,
      ].join('\n');
    }
    const s2 = world.biz.suppliers.find((x) => x.id === e.supplier);
    return [
      `From: ${s2.name} Sales <sales@${s2.name.toLowerCase().replace(/ (ltd|co)$/, '').replace(/[^a-z]+/g, '')}.co.uk>`,
      `Sent: ${when}`,
      `Subject: Price update - your ${e.po}`,
      `Hello, as discussed with ${po.raisedBy.split(' ')[0]} on the phone, ${e.desc} is now ${gbp(e.price)} each (was ${gbp(e.was)}) because of our supplier increases. Our invoice reflects this. Please update your PO. Kind regards, Sales Team`,
    ].join('\n');
  }
  if (e.kind === 'bank-change') {
    return [
      `From: ${s.name} Accounts <accounts-dept@${s.name.toLowerCase().replace(/ (ltd|co)$/, '').replace(/[^a-z]+/g, '')}-billing.com>`,
      `Sent: ${when}`,
      `Subject: URGENT - change of bank details`,
      `Dear valued customer, please note we have moved our banking. With immediate effect please make all payments to Sort code ${e.bank.sort}, Account ${e.bank.acct}. Please update your records and confirm by reply. Regards, Credit Control`,
    ].join('\n');
  }
  return '';
}

const POLICY = (biz) => `ACCOUNTS PAYABLE POLICY FIN-04: THREE-WAY MATCH (v3.2)
Every document in the AP inbox gets exactly one outcome. Run the checks below in order. The first check a document fails gives its outcome. A document that passes every check is approved.
Check 1, entity. The invoice must be addressed ("Invoice to" / "Bill to") to ${biz.legal}. Anything addressed to another company, including our sister company ${biz.sister}, is rejected: reject-wrong-entity. Where the goods were delivered does not matter.
Check 2, duplicate. An invoice is a duplicate if it has the same supplier, the same PO number and the same invoice total as an invoice on the paid ledger, or as an invoice earlier in this inbox (lower DOC number), even when the invoice number or date differs: reject-duplicate. Credit notes are never duplicates.
Check 3, bank details. The sort code and account number printed on the document must match the supplier master file (spacing and dashes do not matter). If they differ: hold-bank-details. Bank details change only after a call-back to the phone number on the master file; an email asking us to change them changes nothing.
Check 4, purchase order. The document must quote a PO number that is on the PO list for the same supplier; otherwise hold-no-po. Scanned documents are OCR text: the scanner sometimes reads the digit 0 as the letter O (or the reverse) in references, so treat O and 0 as the same character.
Check 5, quantity. For each line, the invoiced quantity must not be more than the quantity received for that PO line (all GRNs, as corrected) minus the quantity already invoiced to date (shown on the PO list) minus the quantities on EARLIER documents in this inbox (lower DOC numbers) that you approve and that bill the same PO line (earlier documents you hold or reject use up nothing); otherwise hold-qty. A correction to a GRN emailed by the site manager who signed it replaces the GRN figure; if they email more than once about the same GRN line, their latest email counts, and "ignore my last email" puts the GRN figure back.
Check 6, price. Use the PO unit price as amended: an email from the person who raised the PO that changes its price amends the PO; a price change claimed by anyone else (including the supplier) changes nothing. For each line, compare the invoiced line value with PO unit price x invoiced quantity. The difference, in either direction, may be at most 2% of that PO value or £5.00, whichever is LOWER; and the differences of all the document's lines added together (each counted as a positive amount) may be at most £10.00. Otherwise hold-price.
Check 7, VAT. The VAT charged must equal the correct VAT to within £0.02. Correct VAT: for each rate, apply the rate of the PO line's VAT code (S 20%, R 5%, Z 0%) to the document's net total at that rate, round to the nearest penny (half a penny rounds up), then add the rates up. Otherwise hold-vat.
Credit notes. A credit note against an invoice on the paid ledger: checks 1, 3 and 7 apply; if it passes, approve it. A credit note against an invoice in this inbox: assess the two together. Before checks 5 and 6, reduce the invoice's lines by the credit note's lines (returned quantity and value, or a price adjustment's value); every other check applies to each document on its own. If the invoice is then approved, approve the credit note too; otherwise hold the credit note: hold-linked. An approved credit note has amount_to_pay equal to minus its total and pay_by equal to the run date.
Payment terms (supplier master file). "Net N": pay_by = N days after the invoice date; amount_to_pay = the invoice total. "N days EOM": pay_by = N days after the last day of the invoice's month; amount_to_pay = the invoice total. "D/10 net 30" (an early-payment discount): if the discount deadline (invoice date + 10 days) is on or after the run date, take the discount: pay_by = that deadline and amount_to_pay = the invoice total minus D% of the invoice total (the discount rounded to the nearest penny, half up); if the deadline is before the run date, pay_by = invoice date + 30 days (even if that has already passed) and amount_to_pay = the invoice total. "P% on goods in 14 days, net 30" (a settlement discount): if the deadline (invoice date + 14 days) is on or after the run date, pay_by = that deadline and amount_to_pay = the invoice total minus P% of the NET goods total before VAT (the discount rounded to the nearest penny, half up); otherwise pay_by = invoice date + 30 days and amount_to_pay = the invoice total.
This run. Approved documents whose pay_by date is BEFORE the next run date are paid in this run (overdue ones included); the rest wait for a later run. Exception: if a supplier's documents that would be paid in this run add up to zero or less (credit notes bigger than the invoices), none of that supplier's documents are paid in this run; they keep their outcome, amount_to_pay and pay_by and wait for a later run. total_payable_this_run is the sum of amount_to_pay over the documents paid in this run, credit notes included.
Held or rejected documents: amount_to_pay 0 and pay_by null.`;

export function render(world) {
  const { biz, run, nextRun } = world;
  const supName = (id) => biz.suppliers.find((s) => s.id === id).name;
  const m = biz.manager;
  const out = [];
  out.push(`From: ${m.name} <${m.name.split(' ')[0].toLowerCase()}@${biz.domain}> (${m.role}, ${biz.legal})
Sent: ${ukShort(addDays(run, -2))} 16:52
To: AP inbox
Subject: Payment run ${ukShort(run)} - can you check the inbox?

Hi,

Can you check every document in the AP inbox against the POs and GRNs before ${ukLong(run)}'s payment run? The next run after that is ${ukLong(nextRun)}. Policy FIN-04 is below as usual, plus the supplier master file, the paid ledger, the PO list, the GRNs and the emails that came in since Monday. I need an outcome for every document and the total we're paying on ${ukShort(run)}.

Thanks,
${m.name.split(' ')[0]}`);
  out.push(POLICY(biz));
  out.push(
    'SUPPLIER MASTER FILE (bank details verified by call-back)\n' +
      biz.suppliers.map((s) => `  ${s.name.padEnd(34)} terms ${termsText(s.terms).padEnd(15)} bank ${s.sort} / ${s.acct}`).join('\n'),
  );
  out.push('PAID LEDGER (last 6 weeks)\n' + world.ledger.map((L) => `  ${dMonY(L.paid).padEnd(12)} ${supName(L.supplier).padEnd(34)} inv ${L.number.padEnd(12)} ${L.po.padEnd(8)} ${gbp(L.gross)}`).join('\n'));
  out.push(
    'PO LIST\n' +
      world.pos
        .map(
          (p) =>
            `  ${p.no}  ${supName(p.supplier)} - raised ${dMonY(p.date)} by ${p.raisedBy} for ${p.site.name}\n` +
            p.lines.map((l) => `      ${l.code.padEnd(7)} ${l.desc.padEnd(42)} ordered ${String(l.qty).padStart(4)} @ ${gbp(l.price).padStart(8)}  VAT ${l.vat}  invoiced to date ${l.invoicedToDate}`).join('\n'),
        )
        .join('\n'),
  );
  out.push(
    'GOODS RECEIVED NOTES\n' +
      world.grns.map((g) => `  ${g.no}  ${g.po}  received ${dMonY(g.date)} at ${g.site.name}, signed ${g.by}: ${g.lines.map((l) => `${l.code} x ${l.qty}`).join(', ')}${g.note ? `  (note: ${g.note})` : ''}`).join('\n'),
  );
  out.push('AP INBOX (in DOC order)\n\n' + world.docs.map((d) => renderDoc(world, d)).join('\n\n'));
  const emails = world.emails.map((e, i) => renderEmail(world, e, i)).filter(Boolean);
  out.push('EMAILS RECEIVED SINCE MONDAY\n' + (emails.length ? emails.join('\n\n') : '(none)'));
  out.push(`YOUR ANSWER
Reply with one JSON object in this shape, with one entry for every DOC in the inbox (keyed by its DOC number):
{
  "documents": {
    "DOC-12345": { "outcome": "approve", "amount_to_pay": 123.45, "pay_by": "YYYY-MM-DD" },
    "DOC-12346": { "outcome": "hold-price", "amount_to_pay": 0, "pay_by": null }
  },
  "total_payable_this_run": 0.00
}
outcome is one of: ${OUTCOMES.join(', ')}. Amounts are in pounds as numbers with two decimals (credit notes negative). ${ONE_ANSWER} Each field is marked separately against the policy's answer.`);
  return out.join('\n\n');
}

/** Planted intent vs the rules engine: the list of disagreements (must be empty). */
export function selfCheck(world, sol) {
  const bad = Object.entries(world.intent)
    .filter(([k, v]) => sol.documents[k].outcome !== v)
    .map(([k, v]) => `${k}: planted ${v}, rules say ${sol.documents[k].outcome}`);
  if (Object.keys(world.intent).length !== world.docs.length) bad.push('every document needs a planted intent');
  // Chronology: PO raised before its GRNs and before any invoice that bills it; documents dated before the run.
  for (const po of world.pos) {
    for (const g of world.grns.filter((x) => x.po === po.no)) if (!(g.date > po.date)) bad.push(`${g.no} before ${po.no}`);
    for (const d of world.docs.filter((x) => refNorm(x.poRef) === refNorm(po.no))) if (!(d.date > po.date)) bad.push(`${d.docId} dated before ${po.no}`);
  }
  for (const d of world.docs) if (!(d.date < world.run) || !(d.received.date < world.run)) bad.push(`${d.docId} not before the run`);
  // Every planted rule must change the answer when it is ignored.
  const tags = new Set(world.docs.map((d) => d.tag));
  const differs = (f) => JSON.stringify(solve(world, f)) !== JSON.stringify(sol);
  if (tags.has('credit-exceeds') && !differs({ noSupplierNet: true })) bad.push('supplier-net exception has no effect');
  if (tags.has('pair:consume-hold') && !differs({ noConsume: true })) bad.push('consumption has no effect');
  if (tags.has('pair:consume-freed') && !differs({ consumeHeld: true })) bad.push('held invoices consuming has no effect');
  if (tags.has('hold-price-total') && !differs({ noTotalCap: true })) bad.push('total cap has no effect');
  if (tags.has('po-amend-supplier') && !differs({ anyAmendment: true })) bad.push('amendment rule has no effect');
  if (tags.has('ok-settle') && !differs({ settleOnGross: true })) bad.push('settlement base has no effect');
  return bad;
}

export function summary(world, sol) {
  const counts = {};
  for (const d of Object.values(sol.documents)) counts[d.outcome] = (counts[d.outcome] ?? 0) + 1;
  return `${world.docs.length} docs, total ${sol.total_payable_this_run} ${JSON.stringify(counts)}`;
}

/** Hard skills weigh more: a planted problem's outcome is worth 4 points, its amount 3 and its pay-by date 2; the run total 8. */
export function caseScorer(world, base) {
  const plain = new Set(['ok', 'ok-partial', 'ok-eom', 'ok-mixed-vat']);
  const weights = { total_payable_this_run: 8 };
  for (const d of world.docs)
    if (!plain.has(d.tag)) {
      weights[`documents.${d.docId}.outcome`] = 4;
      weights[`documents.${d.docId}.amount_to_pay`] = 3;
      weights[`documents.${d.docId}.pay_by`] = 2;
    }
  return { ...base, weights };
}

/** Scripted answers for the spread report and the scorer tests (never shown to a model). */
export function scripted(world) {
  return {
    careless: careless(world),
    flawed_new_rules: solve(world, { noConsume: true, noTotalCap: true, anyAmendment: true, settleOnGross: true, noSupplierNet: true }),
    careful_human_slips: solve(world, { noSupplierNet: true, settleOnGross: true }),
    flawed_tolerance_ocr_emails: solve(world, { tolFiveOnly: true, noOcr: true, ignoreEmails: true }),
    flawed_discounts_credits: solve(world, { noDiscount: true, noCredits: true }),
    flawed_misses_duplicates_and_bank: solve(world, { noDup: true, noBank: true }),
  };
}
