/**
 * npm run marketing:next [-- --dry] [--date YYYY-MM-DD]
 *
 * The daily two minutes. Takes the next unposted day from the calendar (today
 * or the earliest overdue), regenerates it against the data as it is now,
 * re-runs the checks, copies it to marketing/drop/<date>/, prints the caption
 * and records the date in posted.json. Nothing here posts to Instagram: the
 * drop folder is what you upload, and the caption is what you paste.
 */
import { cpSync, existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { expandRotation, type Rotation } from './lib/rotation.ts';

const args = process.argv.slice(2);
const dry = args.includes('--dry');
const forced = args.includes('--date') ? args[args.indexOf('--date') + 1] : undefined;
const json = (f: string) => JSON.parse(readFileSync(f, 'utf8'));
const rot = json('marketing/rotation.json') as Rotation;
const posted = json('marketing/posted.json') as { note: string; posted: string[] };
const today = new Date().toISOString().slice(0, 10);

const days = expandRotation(rot).map((d) => d.date);
const pending = days.filter((d) => !posted.posted.includes(d));
const date = forced ?? pending.find((d) => d >= today) ?? pending[0];
if (!date) { console.log('nothing left in the rotation — extend marketing/rotation.json'); process.exit(0); }
if (date < today) console.log(`(${date} is overdue — posting it now)`);

let out = '';
try { out = execFileSync('npx', ['tsx', 'marketing/scripts/calendar.ts', '--only', date], { encoding: 'utf8', stdio: 'pipe' }); }
catch (e) { console.error(String((e as { stdout?: string }).stdout ?? '')); console.error(`\n${date} did not pass its checks — nothing dropped.`); process.exit(1); }

const dir = `marketing/calendar/${date}`;
const drop = `marketing/drop/${date}`;
cpSync(dir, drop, { recursive: true });
const files = readdirSync(drop).filter((f) => f.endsWith('.png'));
console.log(`\n${date} → ${drop}/`);
for (const f of files) console.log(`  ${f}`);
console.log(`\n--- caption ---\n${readFileSync(`${drop}/caption.txt`, 'utf8')}`);
if (!dry) { posted.posted.push(date); posted.posted.sort(); writeFileSync('marketing/posted.json', JSON.stringify(posted, null, 2) + '\n'); console.log(`recorded in marketing/posted.json (${pending.length - 1} day(s) left)`); }
else console.log('(dry run — not recorded)');
