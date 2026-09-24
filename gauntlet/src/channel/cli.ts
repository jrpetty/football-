/** Terminal commands for the channel tools: publish, newmodel, challenge. */
import { readFileSync } from 'node:fs';
import { importSubmissions, loadQueue, writeChallengeTest } from './challenge.ts';
import { runNewModelWizard } from './newmodel-cli.ts';
import { loadSiteConfig } from './site-config.ts';
import { exportSite } from './site-export.ts';

type Flags = Record<string, string | boolean>;
const list = (v: string | boolean | undefined) => (typeof v === 'string' ? v.split(',').map((s) => s.trim()).filter(Boolean) : undefined);

export async function runChannelCommand(command: string, positional: string[], flags: Flags): Promise<void> {
  if (command === 'publish') {
    const r = exportSite({ suites: list(flags.suite) ?? list(flags.suites), outDir: typeof flags.out === 'string' ? flags.out : undefined, zip: flags.zip === true });
    console.log(`Website written to ${r.outDir}`);
    console.log(`  ${r.pages.length} pages, ${r.files} files, ${(r.bytes / 1024).toFixed(0)} KB · suites: ${r.suites.map((s) => `${s.name} (${s.models} models)`).join(', ')}`);
    if (r.hiddenTests) console.log(`  ${r.hiddenTests} held-out test(s) listed anonymously; their prompts and answers are not published.`);
    if (r.withheldPrompts) console.log(`  ${r.withheldPrompts} test(s) with "publishPrompts": false: example prompts withheld.`);
    if (r.zipPath) console.log(`  Zip: ${r.zipPath}`);
    for (const w of r.warnings) console.log(`  ⚠ ${w}`);
    console.log('Open index.html in that folder to check it, then upload the folder (see docs/PUBLISHING.md).');
    return;
  }
  if (command === 'newmodel') {
    const out = await runNewModelWizard(flags);
    if (out.stopped && !out.runIds.length) process.exitCode = out.contestantId ? 0 : 1;
    return;
  }
  if (command === 'challenge') {
    const season = typeof flags.season === 'string' ? flags.season : loadSiteConfig().season;
    const sub = positional[0];
    if (sub === 'import') {
      const file = positional[1];
      if (!file) throw new Error('Usage: challenge import <submissions.csv|json> [--season 2026-s1]');
      const r = importSubmissions(season, readFileSync(file, 'utf8'), 'auto');
      console.log(`Imported ${r.added} submission(s) into season ${r.queue.season} (${r.skipped} skipped as empty or already imported).`);
      for (const e of r.errors) console.log(`  ⚠ ${e}`);
      const flagged = r.queue.items.filter((i) => i.issues.length);
      if (flagged.length) console.log(`  ${flagged.length} need a closer look. Review them in the dashboard: Viewer Challenge.`);
      return;
    }
    if (sub === 'write') {
      const r = writeChallengeTest(season, { category: typeof flags.category === 'string' ? flags.category : undefined });
      if (r.errors.length) {
        for (const e of r.errors) console.log(`✗ ${e}`);
        process.exitCode = 1;
        return;
      }
      console.log(`Wrote ${r.cases} case(s) to ${r.file} (${r.testId} v${r.version}, hash ${r.hash}). It is private: never committed or published.`);
      return;
    }
    const q = loadQueue(season);
    console.log(`Season ${q.season}: ${q.items.length} submission(s) · test ${q.testId}${q.writtenVersion ? ` v${q.writtenVersion}` : ' (not written yet)'}`);
    for (const i of q.items)
      console.log(`  ${i.id}  ${i.status.padEnd(8)} ${i.answerType.padEnd(6)} ${(i.viewerHandle || i.viewerName || 'anon').padEnd(18)} ${i.question.replace(/\s+/g, ' ').slice(0, 70)}${i.issues.length ? `  ⚠ ${i.issues.map((x) => x.code).join(',')}` : ''}`);
    return;
  }
}
