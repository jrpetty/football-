import { createInterface } from 'node:readline/promises';
import { loadContestants, loadProviders } from './core/config.ts';
import { ENV_FILE, KEY_HELP, cleanKey, explainKeyError, isRejection, keyFormatWarning, keyStatus, removeKey, saveKey } from './core/keys.ts';
import { discoverModels } from './providers/index.ts';

/**
 * `node src/cli.ts keys`                        list providers and which keys are set
 * `node src/cli.ts keys setup`                  walk through each provider: paste a key or press Enter to skip
 * `node src/cli.ts keys set <provider> <key>`   save one key
 * `node src/cli.ts keys test [provider]`        free check (lists the provider's models)
 * `node src/cli.ts keys remove <provider>`      delete a key saved in .env
 */

const tty = process.stdout.isTTY && !process.env.NO_COLOR;
const green = (s: string) => (tty ? `\x1b[32m${s}\x1b[39m` : s);
const red = (s: string) => (tty ? `\x1b[31m${s}\x1b[39m` : s);
const dim = (s: string) => (tty ? `\x1b[2m${s}\x1b[22m` : s);
const bold = (s: string) => (tty ? `\x1b[1m${s}\x1b[22m` : s);

function statuses() {
  return loadProviders()
    .map((p) => keyStatus(p, loadContestants().filter((c) => c.provider === p.id && c.enabled).map((c) => ({ id: c.id, label: c.label }))))
    .filter((s) => s !== null);
}

async function check(providerId: string): Promise<boolean> {
  try {
    const ids = await discoverModels(providerId);
    console.log(`  ${green('✓')} key works (${ids.length} models visible, free check)`);
    return true;
  } catch (err) {
    console.log(`  ${red('✗')} ${explainKeyError((err as Error).message)}`);
    return false;
  }
}

/** Check a key for free before saving it; a key the provider rejects is not saved. */
async function tryKey(env: string, providerId: string, key: string): Promise<boolean> {
  const w = keyFormatWarning(providerId, key);
  if (w) console.log(`  ${w}`);
  try {
    const ids = await discoverModels(providerId, key);
    saveKey(env, key);
    console.log(`  ${green('✓')} key works (${ids.length} models visible, free check). Saved to ${ENV_FILE}`);
    return true;
  } catch (err) {
    const message = (err as Error).message;
    if (isRejection(message)) {
      console.log(`  ${red('✗')} ${explainKeyError(message)} Not saved.`);
      return false;
    }
    saveKey(env, key);
    console.log(`  ${red('!')} Saved, but it couldn't be checked: ${explainKeyError(message)}`);
    return true;
  }
}

function providerOrExit(id: string | undefined) {
  const p = loadProviders().find((x) => x.id === id && x.apiKeyEnv);
  if (!p) {
    console.error(`Unknown provider "${id ?? ''}". One of: ${statuses().map((s) => s.providerId).join(', ')}`);
    process.exit(1);
  }
  return p;
}

export async function keysCommand(positional: string[]): Promise<void> {
  const [sub, a, b] = positional;

  if (!sub || sub === 'list') {
    console.log(bold('API keys') + dim(`  (stored in ${ENV_FILE})`));
    for (const s of statuses()) {
      const models = s.models.length ? dim(` · ${s.models.length} model${s.models.length === 1 ? '' : 's'}`) : '';
      console.log(`  ${s.set ? green('●') : dim('○')} ${s.label.padEnd(16)} ${s.set ? `${s.masked}${s.source === 'system' ? dim(' (system env)') : ''}` : dim('not set')}${models}`);
    }
    console.log(dim('\nAdd keys: node src/cli.ts keys setup   (or open the dashboard → API Keys)'));
    return;
  }

  if (sub === 'set') {
    const p = providerOrExit(a);
    const { key, error } = cleanKey(b);
    if (!key) {
      console.error(error);
      process.exit(1);
    }
    if (!(await tryKey(p.apiKeyEnv!, p.id, key))) process.exit(1);
    return;
  }

  if (sub === 'remove') {
    const p = providerOrExit(a);
    removeKey(p.apiKeyEnv!);
    console.log(`Removed the ${p.label} key from ${ENV_FILE}`);
    return;
  }

  if (sub === 'test') {
    const list = a ? [providerOrExit(a).id] : statuses().filter((s) => s.set).map((s) => s.providerId);
    if (!list.length) console.log('No keys set yet. Run: node src/cli.ts keys setup');
    for (const id of list) {
      console.log(bold(loadProviders().find((p) => p.id === id)!.label));
      await check(id);
    }
    return;
  }

  if (sub === 'setup') {
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    console.log(bold('Connect AI companies') + dim('  Paste a key and press Enter, or just press Enter to skip. Keys are saved in ' + ENV_FILE));
    console.log(dim('Tip: in PowerShell, right-click pastes.\n'));
    try {
      for (const s of statuses()) {
        if (!s.models.length && !s.set) continue;
        const help = KEY_HELP[s.providerId];
        console.log(`${bold(s.label)}${s.set ? ` ${dim(`(already set: ${s.masked})`)}` : ''}  ${dim(`unlocks ${s.models.map((m) => m.label).join(', ') || 'no models yet'}`)}`);
        if (help) console.log(dim(`  Get a key: ${help.url}  ·  ${help.steps}`));
        const answer = (await rl.question(s.set ? '  New key (Enter keeps the current one): ' : '  Key (Enter to skip): ')).trim();
        if (!answer) {
          console.log('');
          continue;
        }
        const { key, error } = cleanKey(answer);
        if (!key) {
          console.log(`  ${red('✗')} ${error}\n`);
          continue;
        }
        await tryKey(s.env, s.providerId, key);
        console.log('');
      }
    } finally {
      rl.close();
    }
    const set = statuses().filter((s) => s.set);
    console.log(`${set.length} compan${set.length === 1 ? 'y' : 'ies'} connected${set.length === 1 ? dim(' (add one more for the AI judges: they never grade their own company)') : ''}.`);
    return;
  }

  console.error('Usage: keys [list] | keys setup | keys set <provider> <key> | keys test [provider] | keys remove <provider>');
  process.exit(1);
}
