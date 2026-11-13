// Builds the DayZ pack's guns (src/profiles/dayz/weapons.json, in the pack's
// arsenal format) from three sources:
//
//   - the DayZ Wiki's weapon pages (dayz.fandom.com, CC BY-NC-SA 3.0): the
//     in-game description, fire modes, rate of fire, recoil, noise, sights
//     and their zeroing ranges, variants;
//   - the wiki's "Weapons, damage and armor" ammunition table (game config
//     values written down for DayZ 1.12): damage, shock, muzzle speed and air
//     friction per round, from which the bullet drop is worked out here;
//   - Bohemia Interactive's DayZ Central Economy files (ADPL-SA): where each
//     gun spawns on each map, how many, its loot tiers, and the magazines and
//     attachments it can spawn with.
//
// The look of each gun (what it resembles, shape, stock, magazine) is written
// here in our own words for recognising it on screen; no images are bundled.
//
//   node scripts/build-dayz-weapons.mjs <wiki-guns-dir> <central-economy-repo>
//
// <wiki-guns-dir> holds gun_pages.json (title → wikitext of each weapon page)
// and extra_pages.json (the "Weapons, damage and armor" and "List of
// ammunition" pages), as the wiki's API returned them.

import { readFileSync, writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const [wikiDir, ceDir] = process.argv.slice(2)
if (!wikiDir || !ceDir) {
  console.error('usage: node scripts/build-dayz-weapons.mjs <wiki-guns-dir> <central-economy-repo>')
  process.exit(1)
}
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'profiles', 'dayz', 'weapons.json')
const pages = JSON.parse(readFileSync(join(wikiDir, 'gun_pages.json'), 'utf8'))
const extra = JSON.parse(readFileSync(join(wikiDir, 'extra_pages.json'), 'utf8'))

// --- The guns: wiki page → game class names, the real gun, how it looks --------------------------
// cls: the Central Economy class names (colour variants are found by prefix).

const GUNS = {
  // Assault rifles
  'KA-M': { cls: ['AKM'], cls2: 'assault_rifle', real: 'AKM', aka: ['AKM', 'AK', 'AK-47', 'AK47', 'KAM'], look: 'Classic AK shape: curved 30-round magazine (or a round 75-round drum), gas tube over the barrel, slanted muzzle brake, wood or black polymer stock and handguard; a side rail takes KA optics such as the PSO-1.' },
  'KA-74': { cls: ['AK74'], cls2: 'assault_rifle', real: 'AK-74', aka: ['AK74', 'AK-74', 'AK 74'], look: 'AK shape with a slimmer, more gently curved orange-brown or black magazine and a large two-port muzzle brake; plum or black furniture; takes KA optics on the side rail.' },
  'KAS-74U': { cls: ['AKS74U'], cls2: 'assault_rifle', real: 'AKS-74U', aka: ['AKS74U', 'AKS-74U', 'AKS', 'Krinkov', 'KAS'], look: 'Very short AK carbine: stubby barrel ending in a fat cylindrical muzzle booster, folding skeleton stock, hinged top cover with a flip rear sight; same curved 5.45 magazines as the KA-74.' },
  'KA-101': { cls: ['AK101'], cls2: 'assault_rifle', real: 'AK-101', aka: ['AK101', 'AK-101', 'AK 101'], look: 'Modern black AK with a side-folding polymer stock, a nearly straight black 5.56 magazine and a two-port muzzle brake; takes KA optics.' },
  'M4-A1': { cls: ['M4A1'], cls2: 'assault_rifle', real: 'M4A1 carbine', aka: ['M4', 'M4A1', 'M4 A1'], look: 'Black carbine: telescoping stock, straight 30-round STANAG magazine, flat-top rail (often a carry-handle sight), short ribbed or railed handguard; many stocks, handguards, optics and suppressors fit.' },
  'M16-A2': { cls: ['M16A2'], cls2: 'assault_rifle', real: 'M16A2', aka: ['M16', 'M16A2', 'M16 A2'], look: 'Long black rifle with a fixed solid stock, fixed carry handle with the rear sight on top, triangular front sight post and round ribbed handguard; takes M4 magazines but no optics.' },
  'LE-MAS': { cls: ['FAMAS'], cls2: 'assault_rifle', real: 'FAMAS F1', aka: ['FAMAS', 'LEMAS', 'LE MAS'], look: 'Bullpup (magazine behind the grip) with a long carrying handle running along the top that holds the sights; short 25-round magazine; bipod legs folded under the barrel.' },
  'AUR A1': { cls: ['AugShort'], cls2: 'assault_rifle', real: 'Steyr AUG A1 (short barrel)', aka: ['AUG A1', 'AUG', 'Aug Short'], look: 'Green bullpup with a smooth one-piece stock, a large trigger guard enclosing the whole hand, a translucent magazine and an integral low-power scope on a handle.' },
  'AUR AX': { cls: ['Aug'], cal: '5.56x45mm', cls2: 'assault_rifle', real: 'Steyr AUG A3', aka: ['AUG AX', 'AUG A3'], look: 'Same bullpup body as the AUR A1 with a long top rail in place of the built-in scope, so it takes western optics; uses standard M4 magazines.' },
  SVAL: { cls: ['ASVAL'], cls2: 'assault_rifle', real: 'AS Val', aka: ['AS VAL', 'ASVAL', 'AS Val'], look: 'Thick integrated suppressor making up the whole front half, skeleton folding stock, short 20-round 9x39 magazine; a top rail takes universal optics.' },
  Vikhr: { cls: ['Vikhr'], cls2: 'assault_rifle', real: 'SR-3M Vikhr', aka: ['SR-3M', 'SR3M'], look: 'Compact carbine like a shortened VSS without the suppressor: short barrel, top folding stock, vertical foregrip, 30-round 9x39 magazine; side rail for optics.' },
  // Battle and marksman rifles
  LAR: { cls: ['FAL'], cls2: 'battle_rifle', real: 'FN FAL', aka: ['FAL', 'FN FAL'], look: 'Long battle rifle with a carrying handle at the balance point, straight 20-round .308 magazine, slotted flash hider, and a fixed or folding stock; rail for western optics.' },
  'SCR 17': { cls: ['SCARH'], cls2: 'battle_rifle', real: 'FN SCAR-H', aka: ['SCAR', 'SCAR-H', 'SCARH', 'SCR'], look: 'Angular modern rifle with a full-length top rail, folding adjustable stock, flat receiver sides and a straight 20-round .308 magazine.' },
  DMR: { cls: ['M14'], cls2: 'marksman_rifle', real: 'M14', aka: ['M14', 'M-14'], look: 'Traditional wooden stock running almost the full length, short straight 20-round magazine, rear aperture sight, rail on top for western scopes.' },
  VSD: { cls: ['SVD'], cls2: 'marksman_rifle', real: 'SVD Dragunov', aka: ['SVD', 'Dragunov'], look: 'Very long, slim rifle with a skeleton thumbhole stock, short 10-round magazine, long thin barrel with slotted flash hider; the PSO-1 scope sits on the left side rail.' },
  VSS: { cls: ['VSS'], band: 'mid', cls2: 'marksman_rifle', real: 'VSS Vintorez', aka: ['Vintorez'], look: 'Wooden skeleton stock with a fat integrated suppressor over the whole barrel; short 10-round 9x39 magazine (20s fit); side rail for KA optics.' },
  // Bolt-action, lever and break-action rifles
  'Mosin 91/30': { cls: ['Mosin9130'], cls2: 'bolt_action_rifle', real: 'Mosin-Nagant 91/30', aka: ['Mosin', 'Mosin Nagant', 'Mosin 9130', 'Mosin 91 30'], look: 'Long wooden rifle with a straight bolt handle sticking out to the side, no detachable magazine (loads 5 from the top), hooded front sight; the PU scope mounts offset above the bolt.' },
  'SK 59/66': { cls: ['SKS'], cls2: 'semi_auto_rifle', real: 'SKS (M59/66)', aka: ['SKS', 'SK 59', 'SK59'], look: 'Wooden semi-automatic with a fixed 10-round magazine in front of the trigger guard, folding bayonet under the barrel and a grenade-launcher sleeve on the muzzle.' },
  'CR-527': { cls: ['CZ527'], cls2: 'bolt_action_rifle', real: 'CZ 527', aka: ['CZ527', 'CZ 527', 'CZ'], look: 'Light wooden (or painted) hunting rifle with a short bolt and a small flush 5-round magazine; takes a hunting scope or Sportpoint sight.' },
  'CR-550 Savanna': { cls: ['CZ550'], cls2: 'bolt_action_rifle', real: 'CZ 550', aka: ['CZ550', 'CZ 550', 'Savanna', 'CR-550', 'CR550'], look: 'Full-size wooden hunting rifle with a long barrel, turned-down bolt and small detachable magazine; scope mounts on top.' },
  'M70 Tundra': { cls: ['Winchester70'], cls2: 'bolt_action_rifle', real: 'Winchester Model 70', aka: ['Winchester', 'Winchester 70', 'M70', 'Tundra'], look: 'Slim hunting rifle with a smooth sporter stock (wood or painted), internal 5-round magazine and a bolt handle bent down; usually wears a hunting scope.' },
  'SSG 82': { cls: ['SSG82'], cls2: 'bolt_action_rifle', real: 'SSG 82', aka: ['SSG82', 'SSG'], look: 'Wooden police sniper rifle with a thumbhole-style stock, built-in fixed scope and a small 5.45 magazine.' },
  'VS-89': { cls: ['SV98'], cls2: 'bolt_action_rifle', real: 'SV-98', aka: ['SV98', 'SV-98', 'VS89'], look: 'Heavy sniper rifle with an adjustable skeleton stock, thick barrel with a muzzle brake, bipod and 10-round box magazine; long top rail for scopes.' },
  Pioneer: { cls: ['Scout'], cls2: 'bolt_action_rifle', real: 'Steyr Scout', aka: ['Steyr Scout'], look: 'Compact black or painted bolt-action with a forward rail for a long eye-relief scope, folded bipod in the fore-end and a small 5.56 magazine.' },
  'Repeater Carbine': { cls: ['Repeater'], cls2: 'lever_action_rifle', real: 'Winchester 1873 style lever-action', aka: ['Repeater', 'Lever action'], look: 'Old-West carbine: wooden stock, brass-coloured or steel receiver, lever loop under the grip and a tube magazine under the barrel.' },
  'BK-18': { cls: ['Izh18'], cls2: 'break_action_rifle', real: 'IZh-18', aka: ['Izh18', 'IZH-18', 'BK18'], look: 'Simple single-barrel break-action rifle with a plain wooden stock; it opens at the hinge to load one round.' },
  Blaze: { cls: ['B95'], cls2: 'break_action_rifle', real: 'Blaser B95 style double rifle', aka: ['B95', 'Blaser'], look: 'Elegant over-and-under double rifle with a wooden stock, opening at the hinge for two .308 rounds; usually scoped.' },
  // Submachine guns
  'SG5-K': { cls: ['MP5K'], cls2: 'smg', real: 'MP5K', aka: ['MP5', 'MP5K', 'MP5-K', 'SG5', 'SG5K'], look: 'Short black SMG with a curved 30-round magazine, front vertical grip, no barrel to speak of; rails for optics and a suppressor.' },
  'USG-45': { cls: ['UMP45'], cls2: 'smg', real: 'HK UMP45', aka: ['UMP', 'UMP45', 'UMP-45', 'USG', 'USG45'], look: 'Boxy black polymer SMG with a side-folding stock and a straight 25-round .45 magazine; rails on top.' },
  Bizon: { cls: ['PP19'], cls2: 'smg', real: 'PP-19 Bizon', aka: ['PP19', 'PP-19', 'PP-19 Bizon'], look: 'AK-like receiver and folding stock with a long cylindrical helical magazine running under the barrel instead of a box magazine.' },
  'CR-61 Skorpion': { cls: ['CZ61'], cls2: 'smg', real: 'vz. 61 Skorpion', aka: ['Skorpion', 'Scorpion', 'CZ61', 'CR-61', 'CR61'], look: 'Tiny machine pistol with a wire stock folded over the top and a slightly curved 20-round magazine in front of the trigger.' },
  'RAK-37': { cls: ['PM73Rak'], cls2: 'smg', real: 'PM-63 RAK', aka: ['PM73', 'PM-63', 'RAK', 'RAK37'], look: 'Pistol-sized machine pistol with a sliding top, a spoon-shaped muzzle compensator, folding front grip and a magazine in the grip.' },
  // Shotguns
  'BK-133': { cls: ['Mp133Shotgun'], cls2: 'shotgun', real: 'MP-133', aka: ['MP133', 'MP-133', 'Pump', 'Pump shotgun', 'BK133'], look: 'Pump-action shotgun with a wooden or black stock, sliding fore-end under the barrel and a tube magazine; takes mini sights.' },
  'BK-43': { cls: ['Izh43Shotgun'], cls2: 'shotgun', real: 'IZh-43', aka: ['Izh43', 'IZH-43', 'Double barrel', 'Double-barrel', 'BK43'], look: 'Side-by-side double-barrel shotgun with a wooden stock, breaking open at the hinge for two shells.' },
  'BK-12': { cls: ['Izh18Shotgun'], cls2: 'shotgun', real: 'IZh-18 shotgun', aka: ['Izh18 shotgun', 'BK12', 'Spartan'], look: 'Single-barrel break-action shotgun with a plain wooden stock: one shell at a time.' },
  Vaiga: { cls: ['Saiga'], cls2: 'shotgun', real: 'Saiga-12', aka: ['Saiga', 'Saiga 12', 'Saiga-12'], look: 'AK-shaped shotgun: AK receiver and stock with a thick, boxy curved magazine (or a big drum) holding 12-gauge shells; takes KA optics.' },
  R12: { cls: ['R12'], cls2: 'shotgun', real: 'Remington 1100 style semi-auto', aka: ['R-12', 'Remington'], look: 'Black tactical semi-automatic shotgun with a pistol-grip stock, long tube magazine and a top rail for optics and a light.' },
  // Handguns
  'Mlock-91': { cls: ['Glock19'], cls2: 'pistol', real: 'Glock 19', aka: ['Glock', 'Glock 19', 'Mlock', 'Mlock91'], look: 'Blocky black or tan polymer pistol with a squared slide and a 15-round magazine; rail for a light, threads for a suppressor.' },
  'CR-75': { cls: ['CZ75'], cls2: 'pistol', real: 'CZ 75', aka: ['CZ75', 'CZ 75', 'CR75'], look: 'Steel pistol with a slide that rides inside the frame, rounded hammer and a 15-round magazine.' },
  'FX-45': { cls: ['FNX45'], cal: '.45 ACP', cls2: 'pistol', real: 'FNX-45', aka: ['FNX', 'FNX45', 'FNX-45', 'FX45'], look: 'Large black polymer .45 with slide serrations front and back, a rail for a light and a 15-round magazine.' },
  'Kolt 1911': { cls: ['Colt1911'], cls2: 'pistol', real: 'Colt M1911', aka: ['1911', 'Colt', 'Colt 1911', 'M1911'], look: 'Classic slim steel pistol with a grip safety, exposed hammer and a 7-round single-stack magazine.' },
  'Engraved Kolt 1911': { cls: ['Engraved1911'], cls2: 'pistol', real: 'Colt M1911 (engraved)', aka: ['Engraved 1911', 'Engraved Colt'], look: 'The Kolt 1911 with ornate engraving over the slide and frame.' },
  'IJ-70': { cls: ['MakarovIJ70'], cls2: 'pistol', real: 'IZh-70 (Makarov)', aka: ['Makarov', 'IJ70'], look: 'Small rounded pistol with a brown grip, double-action hammer and a short 8-round magazine.' },
  'MK II': { cls: ['MKII'], cls2: 'pistol', real: 'Ruger Mk II style suppressed .22', aka: ['MK2', 'MKII', 'Mk 2', 'Ruger'], look: 'Long thin pistol whose whole barrel is a thick integral suppressor; angled grip like a Luger.' },
  P1: { cls: ['P1'], cls2: 'pistol', real: 'Walther P1', aka: ['Walther', 'P38', 'Walther P1'], look: 'Old black pistol with a slide cut open over the barrel and an exposed hammer; 8-round magazine.' },
  Deagle: { cls: ['Deagle'], cls2: 'pistol', real: 'Desert Eagle', aka: ['Desert Eagle', 'Deag'], look: 'Big, heavy pistol with a triangular barrel profile and top rail; silver (or gold) finish; 9-round magazine.' },
  Revolver: { cls: ['Magnum'], cls2: 'revolver', real: '.357 Magnum revolver', aka: ['Magnum', '357', '.357'], look: 'Six-shot revolver with a swing-out cylinder, long barrel and wooden grip.' },
  Derringer: { cls: ['Derringer'], cls2: 'pistol', real: 'Derringer', aka: [], look: 'Palm-sized two-barrel pistol (over-and-under) that tips open to load; black, grey or pink.' },
  Longhorn: { cls: ['Longhorn'], cls2: 'pistol', real: 'Thompson/Center Contender style hunting pistol', aka: ['Contender'], look: 'Long single-shot break-action hunting pistol with a long barrel, usually with a handgun scope.' },
  'Sporter 22': { cls: ['Ruger1022'], cls2: 'rimfire_rifle', real: 'Ruger 10/22', aka: ['Ruger 10/22', '10/22', '1022', 'Sporter', '22 rifle'], look: 'Small light rifle with a wooden or painted stock and a rotary or curved .22 magazine; takes a hunting scope.' },
  // Other
  Crossbow: { cls: ['Crossbow'], cls2: 'crossbow', real: 'Hunting crossbow', aka: [], look: 'Rifle stock with horizontal bow limbs at the front and a string across; fires bolts, almost silent.' },
  M79: { cls: ['M79'], cls2: 'grenade_launcher', real: 'M79 grenade launcher', aka: ['Thumper', 'Grenade launcher', '40mm'], look: 'Short fat single barrel on a wooden stock, breaking open to load one 40 mm grenade; flip-up ladder sight.' },
  'Signal Pistol': { cls: ['Flaregun'], cls2: 'flare_gun', real: 'Flare pistol', aka: ['Flare gun', 'Flaregun', 'Flare pistol'], look: 'Wide-bore orange or black single-shot pistol that breaks open to load a flare.' },
  'Sawed-off Mosin 91/30': { cls: ['SawedoffMosin9130'], cls2: 'bolt_action_rifle', real: 'Mosin-Nagant (cut down)', aka: ['Sawed off Mosin', 'Sawn-off Mosin', 'Obrez'], look: 'Mosin cut down to pistol length: short barrel and stock sawn off behind the grip.', crafted: true },
  'Sawed-off BK-43': { cls: ['SawedoffIzh43Shotgun'], cls2: 'shotgun', real: 'IZh-43 (cut down)', aka: ['Sawed off shotgun', 'Sawn-off shotgun', 'Sawed-off double barrel'], look: 'Double-barrel shotgun with the barrels and stock cut short.', crafted: true },
  'Sawed-off LE-MAS': { cls: ['SawedoffFAMAS'], cls2: 'assault_rifle', real: 'FAMAS (handle cut off)', aka: ['Sawed off FAMAS'], look: 'The LE-MAS bullpup with its long carrying handle sawn off, so no iron sights on top.', crafted: true },
  'Sawed-off Blaze': { cls: ['SawedoffB95'], cls2: 'break_action_rifle', real: 'Blaze (cut down)', aka: ['Sawed off Blaze'], look: 'Blaze double rifle with the barrels and stock cut short.', crafted: true },
  'Sawed-off BK-18': { cls: ['SawedoffIzh18'], cls2: 'break_action_rifle', real: 'IZh-18 (cut down)', aka: ['Sawed off BK-18'], look: 'Single-shot rifle cut down to a short barrel and pistol grip.', crafted: true },
  'Sawed-off BK-12': { cls: ['SawedoffIzh18Shotgun'], cls2: 'shotgun', real: 'IZh-18 shotgun (cut down)', aka: ['Sawed off BK-12'], look: 'Single-barrel shotgun cut down to a short barrel and pistol grip.', crafted: true },
  'Sawed-off Revolver': { cls: ['SawedoffMagnum'], cls2: 'revolver', real: '.357 revolver (snub)', aka: ['Snub revolver', 'Sawed off Revolver'], look: 'The revolver with its barrel cut to a stub.', crafted: true },
}

// --- Words the coach must not say (the pack's rules), kept out of wiki text --------------------

const BANNED = [
  [/\bbecause\b/gi, 'as'],
  [/\balways\b/gi, 'usually'],
  [/\bdefinitely\b/gi, 'clearly'],
  [/\bcertainly\b/gi, 'clearly'],
  [/\bguarantee[sd]?\b/gi, 'give'],
  [/\bcaused\b/gi, 'brought'],
  [/\bproves?\b/gi, 'shows'],
]
const tidy = (s) =>
  BANNED.reduce((t, [re, w]) => t.replace(re, w), s)
    // British spelling in the wiki's text.
    .replace(/\b(moderni|customi|standardi|normali|speciali|organi|reali|optimi|stabili)z/gi, '$1s')
    .replace(/\bcaliber/gi, (m) => (m[0] === 'C' ? 'Calibre' : 'calibre'))
    .replace(/\bcolor/gi, (m) => (m[0] === 'C' ? 'Colour' : 'colour'))

const clean = (s) =>
  tidy(
    s
      .replace(/<br\s*\/?>/g, '; ')
      .replace(/\[\[(?:[^\]|]*\|)?([^\]]*)\]\]/g, '$1')
      .replace(/'''?/g, '')
      .replace(/<[^>]+>/g, '')
      .replace(/\{\{[^}]*\}\}/g, '')
      .replace(/\s+/g, ' ')
      .trim(),
  )

function infobox(title) {
  const w = pages[title]
  if (!w) throw new Error(`no wiki page for ${title}`)
  const m = w.match(/\{\{Infobox\w*([\s\S]*?)\n\}\}/)
  const f = {}
  if (m) for (const x of m[1].matchAll(/^\|\s*(\w+)\s*=([\s\S]*?)(?=^\||(?![\s\S]))/gm)) f[x[1]] = clean(x[2])
  // The overview: the first plain paragraph after the infobox.
  const body = w.slice(m ? m.index + m[0].length : 0).replace(/\{\{PAGENAME\}\}/g, title).replace(/\{\{[\s\S]*?\}\}/g, '')
  const para = body.split(/\n\s*\n/).map((p) => p.trim()).find((p) => p && !p.startsWith('=') && !p.startsWith('[[') && !p.startsWith('{') && !p.startsWith('|') && p.length > 60)
  f.overview = para ? clean(para) : ''
  return f
}

// --- Ammunition: damage, speed, air friction ------------------------------------------------------

const AMMO = {}
{
  const w = extra['Weapons, damage and armor']
  const tab = w.slice(w.indexOf('Contact weapons'), w.indexOf('=== Description'))
  for (const row of tab.split('\n|-').slice(1)) {
    const cells = row.split(/\n\|/).map((c) => c.trim())
    const label = clean(cells[1] ?? '')
    const num = (c) => Number(String(c).replace(/^.*\|/, '').split(',')[0])
    if (!label || !cells[6]) continue
    AMMO[label] = { cls: cells[2], damage: num(cells[3]), shock: num(cells[4]), speed: num(cells[6]), airFriction: num(cells[7]) }
  }
}
// The calibre named in the description → the ammo row.
const CALIBRES = [
  [/12\s?ga|12 gauge/i, '12ga', '12x70 Slug / 12ga Slug'],
  [/\.22/i, '.22 LR', null],
  [/5\.45/i, '5.45x39mm', '5.45x39'],
  [/\.380|380 auto/i, '.380 ACP', '.380'],
  [/9\s?[x×]\s?19/i, '9x19mm', '9x19'],
  [/(?<!\d)\.45\b/i, '.45 ACP', '.45'],
  [/\.357/i, '.357', '.357'],
  [/5\.56/i, '5.56x45mm', '5.56x45'],
  [/7\.62\s?x\s?39/i, '7.62x39mm', '7.62x39'],
  [/9\s?x\s?39/i, '9x39mm', '9x39'],
  [/\.308|7\.62\s?x\s?51/i, '.308 WIN', '7.62x51 or .308'],
  [/7\.62\s?x\s?54/i, '7.62x54mmR', '7.62x54'],
  [/40\s?mm/i, '40mm grenade', null],
  [/flare/i, 'flare', null],
  [/bolts?\b/i, 'crossbow bolt', null],
]

/**
 * Flat-fire drop and speed, with the drag the game's config gives
 * (acceleration = airFriction × v × |v|, as in Bohemia's engines) and
 * gravity 9.81. Then the hold over a sight zeroed at `zero` metres.
 */
function ballistics(speed, airFriction, zero) {
  const dt = 0.0005
  let x = 0, y = 0, vx = speed, vy = 0, t = 0
  const at = []
  const marks = [50, 100, 200, 300, 400, 500, 600, 800]
  let k = 0
  while (k < marks.length && t < 6) {
    const v = Math.hypot(vx, vy)
    vx += airFriction * v * vx * dt
    vy += (airFriction * v * vy - 9.81) * dt
    x += vx * dt
    y += vy * dt
    t += dt
    if (x >= marks[k]) {
      at.push({ m: marks[k], drop: -y, speed: Math.hypot(vx, vy), t })
      k++
    }
  }
  const z = at.find((a) => a.m === zero) ?? at.find((a) => a.m === 100)
  const lift = z ? z.drop / z.m : 0
  return at.map((a) => ({ m: a.m, holdM: Math.round((a.drop - lift * a.m) * 100) / 100, speed: Math.round(a.speed), timeS: Math.round(a.t * 100) / 100 }))
}

// --- The Central Economy: spawns, tiers, magazines, attachments ---------------------------------

const CE_MAPS = [
  ['chernarus', 'Chernarus', 'dayzOffline.chernarusplus'],
  ['livonia', 'Livonia', 'dayzOffline.enoch'],
  ['sakhal', 'Sakhal', 'dayzOffline.sakhal'],
]
const types = {}
const spawnable = {}
for (const [id, , dir] of CE_MAPS) {
  const x = readFileSync(join(ceDir, dir, 'db', 'types.xml'), 'utf8')
  types[id] = {}
  for (const m of x.matchAll(/<type name="([^"]+)">([\s\S]*?)<\/type>/g)) {
    const b = m[2]
    types[id][m[1]] = {
      nominal: Number(b.match(/<nominal>(\d+)/)?.[1] ?? 0),
      usage: [...b.matchAll(/<usage name="(\w+)"/g)].map((u) => u[1]),
      tiers: [...b.matchAll(/<value name="Tier(\d)"/g)].map((u) => Number(u[1])),
      unique: /<value name="Unique"/.test(b),
      deloot: /deloot="1"/.test(b),
    }
  }
  const s = readFileSync(join(ceDir, dir, 'cfgspawnabletypes.xml'), 'utf8')
  spawnable[id] = {}
  for (const m of s.matchAll(/<type name="([^"]+)">([\s\S]*?)<\/type>/g)) spawnable[id][m[1]] = [...m[2].matchAll(/<item name="([^"]+)"/g)].map((i) => i[1])
}

const USAGE_WORDS = { Military: 'military buildings', Police: 'police stations', Hunting: 'hunting stands and lodges', Farm: 'farms', Town: 'town houses', Village: 'village houses', Prison: 'the prison', ContaminatedArea: 'contaminated zones', Underground: 'underground bunkers', Special: 'special event spots', SeasonalEvent: 'seasonal events', Lunapark: 'the amusement park', Unique: 'unique spots', Industrial: 'industrial buildings', Medic: 'medical buildings', Firefighter: 'fire stations', Coast: 'the coast', Office: 'offices', School: 'schools' }

const COLOURS = /^_(Black|Green|Woodland|Desert|Winter|Camo|Tan|Autumn|Summer|Wood|Grey|Pink|Chernarus|Livonia|Snow)$/
/** Every class of the gun, colour variants included (AKM, AKM_Black…), but not the sawn-off or gold ones of a different gun. */
function classesOf(base, map) {
  return Object.keys(types[map]).filter((n) => n === base || (n.startsWith(`${base}_`) && COLOURS.test(n.slice(base.length))))
}

function spawnOn(cls, map) {
  let nominal = 0
  const usage = new Set(), tiers = new Set()
  let deloot = false, unique = false, found = false
  for (const base of cls) {
    for (const n of classesOf(base, map)) {
      const t = types[map][n]
      found = true
      nominal += t.nominal
      t.usage.forEach((u) => usage.add(u))
      t.tiers.forEach((u) => tiers.add(u))
      deloot ||= t.deloot
      unique ||= t.unique
    }
  }
  if (!found) return null
  return { nominal, usage: [...usage], tiers: [...tiers].sort(), deloot, unique }
}

function spawnText(sp) {
  if (!sp) return 'not in this map’s loot'
  if (!sp.nominal && !sp.deloot) return 'not in the loot (event or crafted only)'
  if (!sp.nominal) return 'helicopter crashes and convoys only'
  const where = sp.usage.map((u) => USAGE_WORDS[u] ?? u.toLowerCase())
  const parts = []
  if (where.length) parts.push(where.join(', '))
  if (sp.deloot) parts.push('helicopter crashes and convoys')
  const tiers = sp.tiers.length ? ` (loot tier${sp.tiers.length > 1 ? `s ${sp.tiers[0]}-${sp.tiers.at(-1)}` : ` ${sp.tiers[0]}`})` : ''
  return `${parts.join('; ') || 'events only'}${tiers}, about ${sp.nominal} on the map`
}

const ATTACHMENT_NAMES = {
  Mag_: null,
  PSO1Optic: 'PSO-1 scope', PSO11Optic: 'PSO-1-1 scope', KashtanOptic: 'Kashtan scope', KobraOptic: 'Kobra sight', PUScopeOptic: 'PU scope', HuntingOptic: 'hunting scope', SportingOptic: 'Sportpoint sight',
  ACOGOptic: 'ATOG 4x32 scope', ACOGOptic_6x: 'ATOG 6x48 scope', M68Optic: 'Combat sight', M4_T3NRDSOptic: 'RVN sight', ReflexOptic: 'mini sight', HoloOptic: 'Baraka sight', BUISOptic: 'backup iron sights', M4_CarryHandleOptic: 'carry-handle sight', StarlightOptic: 'NV-PVS4 scope', FNP45_MRDSOptic: 'pistol red dot', PistolOptic: 'handgun scope',
  AK_Suppressor: 'KA suppressor', M4_Suppressor: 'standardised suppressor', PistolSuppressor: 'pistol suppressor', ImprovisedSuppressor: 'plastic bottle suppressor',
  Mosin_Compensator: 'Mosin compensator', MP5_Compensator: 'SG5 compensator', AK_Bayonet: 'KA bayonet', M9A1_Bayonet: 'M9 bayonet', Mosin_Bayonet: 'Mosin bayonet', SKS_Bayonet: 'SKS bayonet',
  TLRLight: 'pistol flashlight', UniversalLight: 'universal flashlight', GhillieAtt_Tan: 'ghillie wrap', GhillieAtt_Woodland: 'ghillie wrap', GhillieAtt_Mossy: 'ghillie wrap',
}
const partName = (n) => ATTACHMENT_NAMES[n] ?? (/Bttstck/.test(n) ? 'other buttstocks' : /Hndgrd/.test(n) ? 'other handguards' : /Optic$/.test(n) ? n.replace(/Optic$/, ' sight').replace(/_/g, ' ') : null)

function magsAndParts(cls) {
  const mags = new Set(), parts = new Set()
  for (const [map] of CE_MAPS) for (const base of cls) for (const n of classesOf(base, map)) for (const it of spawnable[map][n] ?? []) {
    if (it.startsWith('Mag_')) {
      const cap = it.match(/(\d+)\s*rnd/i)?.[1]
      if (cap) mags.add(`${cap}${/drum/i.test(it) ? '-round drum' : '-round'}`)
    } else if (!/^Ammo_/.test(it) && partName(it)) parts.add(partName(it))
  }
  const capOrder = (s) => Number(s.match(/\d+/)[0])
  const named = [...parts].filter((p) => !/^other /.test(p))
  return { mags: [...mags].sort((a, b) => capOrder(a) - capOrder(b)), parts: [...named, ...[...parts].filter((p) => /^other /.test(p))] }
}

// --- Range bands and the guns -------------------------------------------------------------------

const BAND = { pistol: 'close', revolver: 'close', smg: 'close-mid', shotgun: 'close', assault_rifle: 'mid', battle_rifle: 'mid-long', marksman_rifle: 'long', bolt_action_rifle: 'long', semi_auto_rifle: 'mid', lever_action_rifle: 'close-mid', break_action_rifle: 'mid', rimfire_rifle: 'close-mid', crossbow: 'close-mid', grenade_launcher: 'mid', flare_gun: 'close' }
const CLASS_WORDS = { pistol: 'pistol', revolver: 'revolver', smg: 'submachine gun', shotgun: 'shotgun', assault_rifle: 'assault rifle', battle_rifle: 'battle rifle', marksman_rifle: 'marksman rifle', bolt_action_rifle: 'bolt-action rifle', semi_auto_rifle: 'semi-automatic rifle', lever_action_rifle: 'lever-action rifle', break_action_rifle: 'break-action rifle', rimfire_rifle: '.22 rifle', crossbow: 'crossbow', grenade_launcher: 'grenade launcher', flare_gun: 'flare pistol' }

const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
const sentence = (s) => (s && !/[.!?]$/.test(s) ? `${s}.` : s)
const cap1 = (s) => s.charAt(0).toUpperCase() + s.slice(1)

const items = []
for (const [title, g] of Object.entries(GUNS)) {
  const f = infobox(title)
  const desc = f.description || ''
  const calRow = CALIBRES.find(([re]) => re.test(`${g.cal ?? ''} ${desc} ${f.ammunition ?? ''} ${title === 'Crossbow' ? 'bolts' : ''}`))
  const calibre = calRow?.[1] ?? null
  const ammo = calRow?.[2] ? AMMO[calRow[2]] : null
  // The iron-sight zero: the first distance in the zeroing field.
  const zeroing = (f.zeroing ?? '').replace(/\(\(?\d+m default\)?\)?/g, '').replace(/\s+/g, ' ')
  const zero = Number(zeroing.match(/(\d+)\s?m/)?.[1] ?? 100)
  const maxSight = Math.max(0, ...[...zeroing.matchAll(/(\d+)\s?m/g)].map((m) => Number(m[1])).filter((n) => n <= 1500))
  const sights = [...new Set([...zeroing.matchAll(/\(([^()]+)\)/g)].map((m) => m[1]).flatMap((s) => s.split('/')).map((s) => s.trim()).filter((s) => s && !/hip|iron|no sights|fixed|default|integral/i.test(s)))]
  const shots = ammo && !/12ga/.test(calibre) ? ballistics(ammo.speed, ammo.airFriction, [25, 50, 100, 200].includes(zero) ? (zero < 50 ? 50 : zero) : 100) : null
  const spawns = Object.fromEntries(CE_MAPS.map(([id]) => [id, spawnOn(g.cls, id)]))
  const { mags, parts } = magsAndParts(g.cls)
  const internal = f.internalmag ? f.internalmag.replace(/\s*\(.*\)$/, '').replace(/ rounds?| shells?/, '') : null
  const rpm = Number((f.rateoffire ?? '').match(/(\d{2,4})\s*(?:rounds|rpm|round|RPM)/i)?.[1] ?? 0) || null
  const modes = (f.mode ?? '').replace(/;/g, ',').replace(/\s+/g, ' ').trim()
  const weight = Number(f.weight) ? Math.round(Number(f.weight) / 100) / 10 : null
  const noise = (f.noise ?? '').split(';')[0].trim()
  const quietNote = /suppress|quiet/i.test(f.noise ?? '') && !/integral/i.test(f.noise ?? '') && /2 \(|1 \(/.test((f.noise ?? '').split(';').slice(1).join(';'))
  const where = CE_MAPS.map(([id, name]) => `${name}: ${spawnText(spawns[id])}`).join('. ')

  const stats = {}
  if (calibre) stats.calibre = calibre
  if (mags.length) stats.magazines = mags.join(', ')
  if (internal) stats.internalMagazine = internal
  if (modes) stats.fireModes = modes
  if (rpm) stats.rpm = rpm
  if (f.recoil) stats.recoil = f.recoil.replace(/\s*\(.*$/, '')
  if (noise) stats.noise = noise
  if (weight) stats.weightKg = weight
  if (zeroing) stats.zeroing = zeroing.slice(0, 300)
  if (maxSight) stats.maxSightM = maxSight
  if (ammo) {
    stats.damage = ammo.damage
    stats.shock = ammo.shock
    stats.muzzleSpeed = ammo.speed
  }
  if (shots) {
    for (const s of shots) if ([100, 200, 300, 400, 600].includes(s.m)) {
      stats[`hold${s.m}`] = s.holdM
      stats[`speed${s.m}`] = s.speed
    }
    stats.zeroForHold = shots === null ? 0 : ([25, 50, 100, 200].includes(zero) ? (zero < 50 ? 50 : zero) : 100)
  }
  stats.whereChernarus = spawnText(spawns.chernarus)
  stats.whereLivonia = spawnText(spawns.livonia)
  stats.whereSakhal = spawnText(spawns.sakhal)
  if (f.rarity && !/n\/a/i.test(f.rarity)) stats.rarity = f.rarity.replace(/\s+/g, ' ')
  if (f.variants) stats.variants = f.variants.slice(0, 120)
  if (parts.length) stats.spawnsWith = parts.slice(0, 10).join(', ')
  if (sights.length) stats.sights = sights.slice(0, 10).join(', ')

  const recoil = (f.recoil ?? '').toLowerCase()
  const strengths = [], weaknesses = [], tips = []
  if (ammo && ammo.damage >= 100) strengths.push(`Hard-hitting ${calibre} (${ammo.damage} damage, ${ammo.shock} shock a hit)`)
  if (ammo && ammo.speed >= 700) strengths.push(`Fast round (${ammo.speed} m/s): little lead and drop at range`)
  if (rpm && rpm >= 800) strengths.push(`High rate of fire (about ${rpm} rounds a minute)`)
  if (/very quiet|integral/i.test(f.noise ?? '')) strengths.push('Very quiet: integral suppressor')
  else if (quietNote) strengths.push('Takes a suppressor')
  if (maxSight >= 600) strengths.push(`Optics zero out to ${maxSight} m`)
  if (/very high|^high/.test(recoil)) weaknesses.push(`${cap1(recoil)} recoil: hard to control in bursts`)
  if (ammo && ammo.speed < 400 && !/12ga/.test(calibre)) weaknesses.push(`Slow round (${ammo.speed} m/s): big drop past 100 m`)
  if (/12ga/.test(calibre ?? '')) weaknesses.push('Buckshot spreads fast: close range only (slugs reach further)')
  if (internal && Number(internal) <= 2) weaknesses.push(`${internal === '1' ? 'One shot' : 'Two shots'} before reloading`)
  if (/extremely rare|very rare/i.test(f.rarity ?? '')) weaknesses.push(`${cap1(f.rarity.toLowerCase())}: magazines and ammo are scarce too`)
  if (g.crafted) tips.push(`Made by sawing down the ${title.replace(/^Sawed-off /, '')} with a hacksaw: shorter and handier, less accurate at range.`)
  if (/full-?auto/i.test(modes) && /high/.test(recoil)) tips.push('Use single shots past about 50 m; full auto only up close.')
  if (BAND[g.cls2] === 'long' && !g.crafted) tips.push('Zero the scope to the range (Page Up / Page Down) before you shoot past 200 m.')
  if (/12ga/.test(calibre ?? '')) tips.push('Carry slugs for anything past 30 m and buckshot for buildings.')

  const kind = CLASS_WORDS[g.cls2]
  const overview = f.overview && f.overview.length < 400 ? ` ${sentence(f.overview)}` : ''
  const shortDesc = desc.replace(/\s*(Uses|Chambered in)\b.*$/i, '').trim()
  const summary = tidy(`${title}: ${kind}, modelled on the ${g.real}${calibre ? `, firing ${calibre}` : ''}. ${sentence(shortDesc)}${overview}`).replace(/\s+/g, ' ').slice(0, 900)

  items.push({
    id: `dayz-${slug(title)}`,
    name: title,
    aliases: [...new Set([...g.aka, ...g.cls])].filter((a) => a.toLowerCase() !== title.toLowerCase()).slice(0, 12),
    category: 'weapon',
    class: g.cls2,
    bestRange: g.band ?? (g.crafted ? (g.cls2 === 'shotgun' ? 'close' : 'close-mid') : BAND[g.cls2]),
    stats,
    summary,
    ...(strengths.length ? { strengths } : {}),
    ...(weaknesses.length ? { weaknesses } : {}),
    ...(tips.length ? { tips } : {}),
    look: g.look,
    notes: `Wiki: https://dayz.fandom.com/wiki/${encodeURIComponent(title.replace(/ /g, '_'))}. Ammo numbers from the wiki's DayZ 1.12 table; spawns from Bohemia's Central Economy files. ${where}`.slice(0, 400),
  })
}

writeFileSync(
  OUT,
  `${JSON.stringify(
    {
      source: 'DayZ Wiki weapon pages and ammunition table (dayz.fandom.com, CC BY-NC-SA 3.0); Bohemia Interactive DayZ Central Economy (ADPL-SA); looks written for this pack.',
      licence: 'Non-commercial use only (CC BY-NC-SA 3.0 and ADPL-SA). Replace before any commercial use.',
      items,
    },
    null,
    1,
  )}\n`,
)
console.log(`${items.length} guns → ${OUT}`)
for (const i of items.slice(0, 3)) console.log(JSON.stringify(i, null, 1).slice(0, 2500))
