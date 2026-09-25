/**
 * `node src/cli.ts arena …` — head-to-head tournaments from the terminal.
 *
 *   arena games
 *   arena estimate --game chess --models a,b,c,d [--format knockout] [--games 2]
 *   arena new --game connect4 --models a,b,c,d [--format knockout|round-robin] [--games 2|4|6]
 *             [--seeding index|manual] [--max-cost 5] [--concurrency 2] [--max-plies 120]
 *             [--sudden-death 2] [--strikes 3] [--seed 1] [--name "..."] [--yes]
 *   arena list
 *   arena show <id>
 *   arena resume <id> [--max-cost 10]
 */
import { createInterface } from 'node:readline/promises';
import { loadContestants } from '../core/config.ts';
import type { ManualRequest } from '../core/types.ts';
import { failManual, manualEvents, submitManual } from '../providers/manual.ts';
import { GAMES } from './games/index.ts';
import { cancelTournament, estimateTournament, listTournaments, resumeTournament, startTournament, subscribeTournament, tournamentDetail, waitForTournament } from './tournament.ts';
import type { ArenaEvent, ArenaFormat, ArenaRequest, ArenaSeeding, TournamentDetail } from './types.ts';

type Flags = Record<string, string | boolean>;

const color = process.stdout.isTTY && !process.env.NO_COLOR;
const c = {
  bold: (s: string) => (color ? `\x1b[1m${s}\x1b[22m` : s),
  dim: (s: string) => (color ? `\x1b[2m${s}\x1b[22m` : s),
  green: (s: string) => (color ? `\x1b[32m${s}\x1b[39m` : s),
  red: (s: string) => (color ? `\x1b[31m${s}\x1b[39m` : s),
  yellow: (s: string) => (color ? `\x1b[33m${s}\x1b[39m` : s),
  cyan: (s: string) => (color ? `\x1b[36m${s}\x1b[39m` : s),
};

export const ARENA_HELP = `  arena games                                List the head-to-head games
  arena estimate --game chess --models a,b,c,d [--format knockout] [--games 2]
  arena new --game connect4 --models a,b,c,d [--format knockout|round-robin] [--games 2|4|6]
      [--seeding index|manual] [--max-cost 5] [--concurrency 2] [--max-plies 120] [--yes]
                                             Start a tournament (shows the cost estimate first)
      Poker: --hands 10|20|40|60 · Debate: --motion <id>|random · Courtroom: --case <id>|random
  arena list | arena show <id> | arena resume <id> [--max-cost 10]`;

const str = (v: string | boolean | undefined) => (typeof v === 'string' ? v : undefined);
const num = (v: string | boolean | undefined) => (typeof v === 'string' && v.trim() !== '' ? Number(v) : undefined);
const fmtUsd = (n: number) => (n === 0 ? '$0' : n < 0.01 ? `$${n.toFixed(4)}` : `$${n.toFixed(2)}`);

/** Game-specific options: --hands 20 (poker), --motion <id>|random (debate), --case <id>|random (courtroom). */
function gameOptions(flags: Flags): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  if (str(flags.hands)) out.hands = str(flags.hands)!;
  const topic = str(flags.motion) ?? str(flags.case);
  if (topic) out.topic = topic;
  return Object.keys(out).length ? out : undefined;
}

function request(flags: Flags): ArenaRequest {
  const models = str(flags.models)?.split(',').map((s) => s.trim()).filter(Boolean);
  if (!models?.length) throw new Error('--models is required (comma-separated model ids; see `node src/cli.ts models`)');
  const game = str(flags.game);
  if (!game) throw new Error(`--game is required (${Object.keys(GAMES).join(', ')})`);
  return {
    game,
    contestantIds: models,
    format: (str(flags.format) as ArenaFormat | undefined) ?? 'knockout',
    seeding: (str(flags.seeding) as ArenaSeeding | undefined) ?? 'index',
    gamesPerMatch: num(flags.games),
    suddenDeath: num(flags['sudden-death']),
    maxStrikes: num(flags.strikes),
    maxPlies: num(flags['max-plies']),
    concurrency: num(flags.concurrency),
    maxCostUsd: num(flags['max-cost']),
    seed: num(flags.seed),
    listLegalMoves: flags['no-legal-moves'] ? false : undefined,
    name: str(flags.name),
    options: gameOptions(flags),
  };
}

function printEstimate(req: ArenaRequest): void {
  const est = estimateTournament(req);
  const labels = new Map(loadContestants().map((x) => [x.id, x.label]));
  console.log(c.bold('Seeding'));
  for (const e of est.entrants) console.log(`  ${String(e.seed).padStart(2)}. ${(labels.get(e.id) ?? e.id).padEnd(28)} ${e.index === null ? c.dim('no index') : `index ${e.index.toFixed(1)}`}`);
  console.log(c.bold('Estimate'));
  for (const p of est.perContestant)
    console.log(`  ${(labels.get(p.contestantId) ?? p.contestantId).padEnd(28)} ${p.manual ? 'manual (you paste the replies)' : `~${fmtUsd(p.perGameUsd)} per game  (${fmtUsd(p.perMoveUsd)}/move, ${p.basis})`}`);
  console.log(`  ${c.bold('Total'.padEnd(28))} ${est.games} games (up to ${est.maxGames} with sudden death), ~${est.moves} moves: ~${c.bold(fmtUsd(est.estCostUsd))} (up to ${fmtUsd(est.estCostUsdHigh)}) · fingerprint ${est.fingerprint}`);
  if (est.judgeCalls !== undefined) console.log(`  ${'Judges (included above)'.padEnd(28)} ${est.judgeCalls} judge calls, ~${fmtUsd(est.judgeCostUsd ?? 0)} · panel: ${(est.judges ?? []).map((j) => j.label).join(', ') || 'none (human judging)'}`);
  for (const w of est.warnings) console.log(c.yellow(`  ⚠ ${w}`));
}

async function confirm(q: string): Promise<boolean> {
  if (!process.stdin.isTTY) return false;
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const a = (await rl.question(q)).trim().toLowerCase();
  rl.close();
  return a === 'y' || a === 'yes';
}

/** Copy & paste loop for manual contestants in terminal tournaments. */
function attachManual(tournamentId: string): () => void {
  const queue: ManualRequest[] = [];
  let busy = false;
  const next = async () => {
    if (busy) return;
    const req = queue.shift();
    if (!req) return;
    busy = true;
    console.log(c.bold(`\n━━━ MANUAL MOVE · ${req.contestantLabel} · ${req.caseId} · ${req.label} ━━━`));
    console.log(c.dim('Open a NEW chat with the model and paste this prompt exactly:'));
    if (req.testId.startsWith('arena.') && GAMES[req.testId.slice(6)]?.sequentialPairs) console.log(c.yellow('Use a brand-new chat for every decision. Remembering the other game’s cards breaks the duplicate format.'));
    console.log(c.cyan('----- COPY BELOW -----'));
    console.log(req.combinedPrompt);
    console.log(c.cyan('----- COPY ABOVE -----'));
    console.log(c.dim('Paste the model reply, then a line containing only END (or FAIL to give up on this game):'));
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: false });
    const lines: string[] = [];
    for await (const line of rl) {
      if (line.trim() === 'END' || line.trim() === 'FAIL') {
        rl.close();
        if (line.trim() === 'FAIL') failManual(req.id, 'marked failed in terminal');
        else submitManual(req.id, { text: lines.join('\n') });
        break;
      }
      lines.push(line);
    }
    busy = false;
    void next();
  };
  const on = (r: ManualRequest) => {
    if (r.runId !== tournamentId) return;
    queue.push(r);
    void next();
  };
  manualEvents.on('request', on);
  return () => manualEvents.off('request', on);
}

function attachConsole(id: string): void {
  attachManual(id);
  const labels = new Map(loadContestants().map((x) => [x.id, x.label]));
  const L = (cid: string) => labels.get(cid) ?? cid;
  subscribeTournament(id, (e: ArenaEvent) => {
    if (e.type === 'game.finished') {
      const g = e.game;
      const res = g.status !== 'ok' ? c.red(`[${g.status}] ${g.error ?? g.reason}`) : g.winner === null ? c.yellow(`draw (${g.reason})`) : c.green(`${L(g.players[g.winner])} wins (${g.reason})`);
      console.log(`  ${g.key.padEnd(12)} ${L(g.players[0])} vs ${L(g.players[1])}: ${res}  ${c.dim(`${g.plies} plies · strikes ${g.strikes.join('/')} · ${fmtUsd(g.metrics[0].costUsd + g.metrics[1].costUsd)}`)}`);
    } else if (e.type === 'match.finished') {
      console.log(c.bold(`▶ ${e.match.roundName} ${e.match.id}: ${e.match.summary}`));
    } else if (e.type === 'log' && e.level !== 'info') {
      console.log((e.level === 'error' ? c.red : c.yellow)(`  ${e.message}`));
    } else if (e.type === 'tournament.status') {
      console.log(c.bold(`Tournament ${e.tournamentId}: ${e.status}${e.error ? ` — ${e.error}` : ''}`));
    }
  });
}

function printDetail(d: TournamentDetail): void {
  const L = (cid: string | null) => (cid ? (d.manifest.entrants.find((e) => e.id === cid)?.label ?? cid) : 'TBD');
  const m = d.manifest;
  console.log(`${c.bold(m.name)}  ${c.dim(m.id)}`);
  console.log(`${m.game.name} v${m.game.version} · ${m.settings.format} · ${m.settings.gamesPerMatch} games per match · status ${m.status} · ${d.state.gamesDone}/${d.state.gamesTotal} games · ${fmtUsd(d.state.costUsd)} · fingerprint ${m.fingerprint}`);
  if (m.error) console.log(c.yellow(m.error));
  let round = 0;
  for (const x of d.state.matches) {
    if (x.round !== round) {
      round = x.round;
      console.log(c.bold(`\n${x.roundName}`));
    }
    const line = x.status === 'bye' ? x.summary : x.status === 'done' ? x.summary : `${L(x.players[0])} vs ${L(x.players[1])} (${x.status}${x.games.some((g) => g.game) ? `, ${x.score[0]}–${x.score[1]}` : ''})`;
    console.log(`  ${x.id.padEnd(8)} ${line}`);
  }
  console.log(c.bold('\nStandings'));
  for (const s of d.state.standings)
    console.log(`  ${String(s.rank).padStart(2)}. ${L(s.contestantId).padEnd(28)} ${String(s.points).padStart(4)} pts  W${s.wins} D${s.draws} L${s.losses}  illegal ${s.illegal}  ${fmtUsd(s.costUsd)}`);
  if (d.state.champion) console.log(c.green(`\nChampion: ${L(d.state.champion)}`));
}

async function follow(id: string): Promise<void> {
  let cancelling = false;
  const onSig = () => {
    if (cancelling) process.exit(130);
    cancelling = true;
    console.log(c.yellow(`\nCancelling… (Ctrl+C again to force quit). Resume later with: node src/cli.ts arena resume ${id}`));
    cancelTournament(id);
  };
  process.on('SIGINT', onSig);
  await waitForTournament(id);
  process.off('SIGINT', onSig);
  const d = tournamentDetail(id);
  if (d) {
    console.log('');
    printDetail(d);
  }
}

export async function arenaCommand(positional: string[], flags: Flags): Promise<void> {
  const [sub, arg] = positional;
  switch (sub) {
    case 'games':
      for (const g of Object.values(GAMES)) {
        console.log(`${c.bold(g.id.padEnd(10))} ${g.name} v${g.version} — ${g.tagline}`);
        for (const o of g.options ?? []) console.log(c.dim(`           ${o.label}: ${o.choices.map((x) => x.value).join(' | ')} (default ${o.default})`));
      }
      return;
    case 'estimate':
      printEstimate(request(flags));
      return;
    case 'new': {
      const req = request(flags);
      printEstimate(req);
      if (!flags.yes && !(await confirm('\nStart this tournament? [y/N] '))) {
        console.log(process.stdin.isTTY ? 'Aborted.' : 'Not a terminal: pass --yes to start without confirmation.');
        process.exitCode = 1;
        return;
      }
      const id = startTournament(req);
      console.log(c.bold(`\nTournament ${id} started`) + c.dim(`  (watch it live: node src/cli.ts serve → Arena)`));
      attachConsole(id);
      await follow(id);
      return;
    }
    case 'list': {
      const list = listTournaments();
      if (!list.length) return console.log('No tournaments yet. Start one with: node src/cli.ts arena new --game connect4 --models random-baseline,<model> --yes');
      for (const t of list) {
        const champ = t.champion ? t.entrants.find((e) => e.id === t.champion)?.label : null;
        console.log(`${t.id}  ${t.status.padEnd(11)} ${t.game.name.padEnd(13)} ${t.format.padEnd(11)} ${String(t.entrants.length).padStart(2)} models  ${t.gamesDone}/${t.gamesTotal} games  ${fmtUsd(t.costUsd).padStart(8)}  ${champ ? `champion: ${champ}` : ''}`);
      }
      return;
    }
    case 'show': {
      const d = arg ? tournamentDetail(arg) : null;
      if (!d) throw new Error('Usage: arena show <tournamentId> (see `arena list`)');
      if (flags.format === 'json') console.log(JSON.stringify(d, null, 2));
      else printDetail(d);
      return;
    }
    case 'resume': {
      if (!arg) throw new Error('Usage: arena resume <tournamentId> [--max-cost 10]');
      resumeTournament(arg, { maxCostUsd: num(flags['max-cost']) });
      console.log(c.bold(`Resuming ${arg}`));
      attachConsole(arg);
      await follow(arg);
      return;
    }
    default:
      console.log(`Arena commands:\n${ARENA_HELP}`);
      if (sub) process.exitCode = 1;
  }
}
