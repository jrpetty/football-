// ---------------------------------------------------------------------------
// Canonical club identity.
//
// This exists because the data sources disagree about names, and the
// disagreement is silent and destructive. openfootball renames clubs between
// seasons ("Manchester City" in one file, "Manchester City FC" in the next),
// while the FPL feed uses short forms ("Man City", "Spurs", "Nott'm Forest").
// Treating those as different clubs splits a team's history into fragments,
// makes long-established sides look newly promoted, and quietly degrades every
// rating fitted on top. It measurably degraded an early backtest here.
//
// So: one canonical code per club, and every raw string must resolve to it.
// `normalizeTeam` throws on an unknown name rather than inventing a club —
// a loud failure in the pipeline beats a wrong number on the site.
// ---------------------------------------------------------------------------

export interface ClubIdentity {
  /** Stable 3-letter code used as the primary key everywhere downstream. */
  code: string
  /** Display name, in the register a fan would use. */
  name: string
  /** Very short label for narrow UI (badges, table rows). */
  short: string
  /** Primary shirt colour, for charts and fixture cards. */
  color: string
  /** Every raw spelling seen across sources, pre-normalisation. */
  aliases: string[]
}

export const CLUBS: ClubIdentity[] = [
  { code: 'ARS', name: 'Arsenal', short: 'ARS', color: '#EF0107', aliases: ['arsenal'] },
  { code: 'AVL', name: 'Aston Villa', short: 'AVL', color: '#95BFE5', aliases: ['aston villa'] },
  { code: 'BOU', name: 'Bournemouth', short: 'BOU', color: '#DA291C', aliases: ['bournemouth', 'afc bournemouth'] },
  { code: 'BRE', name: 'Brentford', short: 'BRE', color: '#E30613', aliases: ['brentford'] },
  { code: 'BHA', name: 'Brighton', short: 'BHA', color: '#0057B8', aliases: ['brighton', 'brighton and hove albion', 'brighton hove albion'] },
  { code: 'BUR', name: 'Burnley', short: 'BUR', color: '#6C1D45', aliases: ['burnley'] },
  { code: 'CAR', name: 'Cardiff City', short: 'CAR', color: '#0070B5', aliases: ['cardiff city', 'cardiff'] },
  { code: 'CHE', name: 'Chelsea', short: 'CHE', color: '#034694', aliases: ['chelsea'] },
  { code: 'COV', name: 'Coventry City', short: 'COV', color: '#78D0F3', aliases: ['coventry city', 'coventry'] },
  { code: 'CRY', name: 'Crystal Palace', short: 'CRY', color: '#1B458F', aliases: ['crystal palace'] },
  { code: 'EVE', name: 'Everton', short: 'EVE', color: '#003399', aliases: ['everton'] },
  { code: 'FUL', name: 'Fulham', short: 'FUL', color: '#CC0000', aliases: ['fulham'] },
  { code: 'HUD', name: 'Huddersfield Town', short: 'HUD', color: '#0E63AD', aliases: ['huddersfield town', 'huddersfield'] },
  { code: 'HUL', name: 'Hull City', short: 'HUL', color: '#F5971D', aliases: ['hull city', 'hull'] },
  { code: 'IPS', name: 'Ipswich Town', short: 'IPS', color: '#3A64A3', aliases: ['ipswich town', 'ipswich'] },
  { code: 'LEE', name: 'Leeds United', short: 'LEE', color: '#FFCD00', aliases: ['leeds united', 'leeds'] },
  { code: 'LEI', name: 'Leicester City', short: 'LEI', color: '#003090', aliases: ['leicester city', 'leicester'] },
  { code: 'LIV', name: 'Liverpool', short: 'LIV', color: '#C8102E', aliases: ['liverpool'] },
  { code: 'LUT', name: 'Luton Town', short: 'LUT', color: '#F78F1E', aliases: ['luton town', 'luton'] },
  { code: 'MCI', name: 'Man City', short: 'MCI', color: '#6CABDD', aliases: ['manchester city', 'man city'] },
  { code: 'MUN', name: 'Man Utd', short: 'MUN', color: '#DA291C', aliases: ['manchester united', 'man utd', 'man united'] },
  { code: 'MID', name: 'Middlesbrough', short: 'MID', color: '#E21B23', aliases: ['middlesbrough'] },
  { code: 'NEW', name: 'Newcastle', short: 'NEW', color: '#241F20', aliases: ['newcastle united', 'newcastle'] },
  { code: 'NOR', name: 'Norwich City', short: 'NOR', color: '#FFF200', aliases: ['norwich city', 'norwich'] },
  { code: 'NFO', name: "Nott'm Forest", short: 'NFO', color: '#DD0000', aliases: ['nottingham forest', "nott'm forest", 'nottm forest'] },
  { code: 'SHU', name: 'Sheffield Utd', short: 'SHU', color: '#EE2737', aliases: ['sheffield united', 'sheffield utd'] },
  { code: 'SOU', name: 'Southampton', short: 'SOU', color: '#D71920', aliases: ['southampton'] },
  { code: 'STK', name: 'Stoke City', short: 'STK', color: '#E03A3E', aliases: ['stoke city', 'stoke'] },
  { code: 'SUN', name: 'Sunderland', short: 'SUN', color: '#EB172B', aliases: ['sunderland', 'sunderland afc'] },
  { code: 'SWA', name: 'Swansea City', short: 'SWA', color: '#121212', aliases: ['swansea city', 'swansea'] },
  { code: 'TOT', name: 'Spurs', short: 'TOT', color: '#132257', aliases: ['tottenham hotspur', 'tottenham', 'spurs'] },
  { code: 'WAT', name: 'Watford', short: 'WAT', color: '#FBEE23', aliases: ['watford'] },
  { code: 'WBA', name: 'West Brom', short: 'WBA', color: '#122F67', aliases: ['west bromwich albion', 'west brom', 'west bromwich'] },
  { code: 'WHU', name: 'West Ham', short: 'WHU', color: '#7A263A', aliases: ['west ham united', 'west ham'] },
  { code: 'WOL', name: 'Wolves', short: 'WOL', color: '#FDB913', aliases: ['wolverhampton wanderers', 'wolverhampton', 'wolves'] },
  // Championship sides that appear in promotion priors but not (yet) the PL.
  { code: 'BIR', name: 'Birmingham City', short: 'BIR', color: '#0000FF', aliases: ['birmingham city', 'birmingham'] },
  { code: 'BLB', name: 'Blackburn Rovers', short: 'BLB', color: '#009EE0', aliases: ['blackburn rovers', 'blackburn'] },
  { code: 'BLP', name: 'Blackpool', short: 'BLP', color: '#F68712', aliases: ['blackpool'] },
  { code: 'BRC', name: 'Bristol City', short: 'BRC', color: '#E21C38', aliases: ['bristol city'] },
  { code: 'DER', name: 'Derby County', short: 'DER', color: '#000000', aliases: ['derby county', 'derby'] },
  { code: 'MIL', name: 'Millwall', short: 'MIL', color: '#001D5E', aliases: ['millwall'] },
  { code: 'OXF', name: 'Oxford United', short: 'OXF', color: '#FFE500', aliases: ['oxford united', 'oxford'] },
  { code: 'PLY', name: 'Plymouth Argyle', short: 'PLY', color: '#007B5F', aliases: ['plymouth argyle', 'plymouth'] },
  { code: 'POR', name: 'Portsmouth', short: 'POR', color: '#001489', aliases: ['portsmouth'] },
  { code: 'PNE', name: 'Preston North End', short: 'PNE', color: '#B2B2B2', aliases: ['preston north end', 'preston'] },
  { code: 'QPR', name: 'QPR', short: 'QPR', color: '#1D5BA4', aliases: ['queens park rangers', 'qpr'] },
  { code: 'ROT', name: 'Rotherham United', short: 'ROT', color: '#DD1E3C', aliases: ['rotherham united', 'rotherham'] },
  { code: 'SHW', name: 'Sheffield Wed', short: 'SHW', color: '#0066B3', aliases: ['sheffield wednesday', 'sheffield wed'] },
  { code: 'STO', name: 'Swindon Town', short: 'STO', color: '#DA291C', aliases: ['swindon town', 'swindon'] },
  { code: 'WIG', name: 'Wigan Athletic', short: 'WIG', color: '#0080C5', aliases: ['wigan athletic', 'wigan'] },
]

/**
 * Strip the noise that varies between sources: club suffixes (FC/AFC), the
 * ampersand, punctuation and casing. "Brighton & Hove Albion FC" and
 * "Brighton and Hove Albion" both land on "brighton and hove albion".
 */
export function canonicalizeName(raw: string): string {
  return raw
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/\./g, '')
    .replace(/[^a-z' ]+/g, ' ')
    // Suffixes only at the very start or end, so "Fulham FC" reduces but
    // "AFC Bournemouth" keeps enough to match its own alias.
    .replace(/\s+(fc|afc)$/g, '')
    .replace(/^(fc)\s+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

const BY_ALIAS = new Map<string, ClubIdentity>()
const BY_CODE = new Map<string, ClubIdentity>()
for (const club of CLUBS) {
  BY_CODE.set(club.code, club)
  for (const alias of [club.name, club.code, ...club.aliases]) {
    BY_ALIAS.set(canonicalizeName(alias), club)
  }
}

/** Resolve any raw club string to its canonical code. Throws if unknown. */
export function normalizeTeam(raw: string): string {
  const key = canonicalizeName(raw)
  const hit = BY_ALIAS.get(key)
  if (hit) return hit.code
  // A stray "AFC"/"FC" in the middle of a name is the one case worth retrying.
  const retry = BY_ALIAS.get(key.replace(/\b(fc|afc)\b/g, '').replace(/\s+/g, ' ').trim())
  if (retry) return retry.code
  throw new Error(
    `Unknown club "${raw}" (normalised: "${key}"). Add it to CLUBS in src/config/teams.ts — ` +
      `guessing would silently split this club's history and corrupt its ratings.`,
  )
}

/** Resolve to a code, or null when the caller can legitimately skip unknowns. */
export function tryNormalizeTeam(raw: string): string | null {
  try {
    return normalizeTeam(raw)
  } catch {
    return null
  }
}

/** Look up a club by canonical code. Throws if absent. */
export function club(code: string): ClubIdentity {
  const hit = BY_CODE.get(code)
  if (!hit) throw new Error(`Unknown club code "${code}"`)
  return hit
}

/** Display name for a code, falling back to the code itself. */
export function clubName(code: string): string {
  return BY_CODE.get(code)?.name ?? code
}

/** Shirt colour for a code, falling back to a neutral slate. */
export function clubColor(code: string): string {
  return BY_CODE.get(code)?.color ?? '#64748b'
}
