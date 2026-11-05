/**
 * Monday Inbox (work.inbox-triage) and What Do I Need to Do? (work.email-actions): the emails read from the prompt,
 * and the model's labels, to-dos and dates against the key. Pure; tested in test/customers-data-docs.test.ts.
 */
import type { CaseVisualInput } from '../../../../../src/presenter/visuals/common.ts';
import { byId, pick, replyJson, sameValue, normSetId } from './kit.ts';

// ───────────────────────────── Monday Inbox ─────────────────────────────

export interface Mail {
  id: string;
  /** "Fri 27 Feb 2026, 07:02" as written. */
  when: string;
  fromName: string;
  fromAddr: string;
  to: string;
  subject: string;
  body: string;
  /** The first words of the body (an inbox preview line). */
  preview: string;
}

export interface Action {
  owner: string;
  due: string;
  priority: string;
}

export interface InboxDoc {
  /** Whose inbox ("Declan Kim"). */
  owner: string;
  /** "Monday 9 March 2026, 9:30am". */
  now: string;
  mails: Mail[];
  /** Genuine addresses and domains from the directory. */
  genuine: Array<{ name: string; addr: string }>;
  key: { cats: Record<string, string>; actions: Record<string, Action> } | null;
}

const HEAD = /^\[(M\d+)\]\s+(.+)$/;

export function parseInbox(prompt: string, expected?: unknown): InboxDoc | null {
  const start = prompt.indexOf('<<<');
  const end = prompt.lastIndexOf('>>>');
  if (start < 0 || end < start) return null;
  const block = prompt.slice(start + 3, end);
  const owner = (prompt.match(/helping ([^(]+?) \(/) ?? [])[1]?.trim() ?? 'you';
  const now = (prompt.match(/It's ([^.]+?\d{1,2}:\d{2}\s*[ap]m)/) ?? [])[1] ?? '';
  const mails: Mail[] = [];
  const lines = block.split('\n');
  let cur: { id: string; when: string; head: string[]; body: string[]; inBody: boolean } | null = null;
  const flush = () => {
    if (!cur) return;
    const h = (k: string) => (cur!.head.find((l) => l.startsWith(`${k}:`)) ?? '').slice(k.length + 1).trim();
    const from = h('From');
    const m = from.match(/^(.*?)\s*<([^>]+)>/);
    const body = cur.body.join('\n').trim();
    const flat = body.replace(/\s+/g, ' ').replace(/^(hi|hello|dear|morning|hey)\b[^,]*,\s*/i, '');
    mails.push({ id: cur.id, when: cur.when, fromName: m ? m[1]!.trim() : from, fromAddr: m ? m[2]!.trim() : '', to: h('To'), subject: h('Subject'), body, preview: flat.slice(0, 120) });
  };
  for (const line of lines) {
    const hm = line.match(HEAD);
    if (hm) {
      flush();
      cur = { id: hm[1]!, when: hm[2]!.trim(), head: [], body: [], inBody: false };
      continue;
    }
    if (!cur) continue;
    if (!cur.inBody) {
      if (!line.trim()) {
        cur.inBody = cur.head.length > 0;
        continue;
      }
      cur.head.push(line);
    } else cur.body.push(line);
  }
  flush();
  if (!mails.length) return null;
  const genuine: InboxDoc['genuine'] = [];
  const dir = prompt.slice(0, start).split('\n');
  for (const l of dir) {
    if (!/^-\s/.test(l)) continue;
    const name = l.replace(/^-\s*/, '').split(',')[0]!.trim();
    for (const a of l.matchAll(/[\w.+-]+@[\w.-]+\.\w+/g)) genuine.push({ name, addr: a[0].toLowerCase() });
  }
  let key: InboxDoc['key'] = null;
  if (expected && typeof expected === 'object') {
    const e = expected as { messages?: Record<string, { category?: string }>; actions?: Record<string, Action> };
    const cats: Record<string, string> = {};
    for (const [k, v] of Object.entries(e.messages ?? {})) cats[k] = String(v?.category ?? '');
    key = { cats, actions: { ...(e.actions ?? {}) } };
  }
  return { owner, now, mails, genuine, key };
}

export interface MailMark {
  id: string;
  cat: string | null;
  keyCat: string | null;
  catOk: boolean | null;
  act: Action | null;
  keyAct: Action | null;
  /** right · wrong (some field off) · missed (key has one, model none) · invented (model has one, key none) · none */
  actState: 'right' | 'wrong' | 'missed' | 'invented' | 'none';
  actFields: { owner: boolean; due: boolean; priority: boolean } | null;
}

export interface InboxAnswer {
  marks: MailMark[];
  catRight: number;
  actRight: number;
  actTotal: number;
  scamsMissed: string[];
  falseScams: string[];
}

function readAction(v: unknown): Action | null {
  if (!v || typeof v !== 'object') return null;
  const owner = pick(v, 'owner', 'assignee', 'who');
  const due = pick(v, 'due', 'duedate', 'deadline');
  const priority = pick(v, 'priority', 'p');
  return { owner: owner == null ? '' : String(owner), due: due == null ? '' : String(due), priority: priority == null ? '' : String(priority) };
}

export function inboxAnswer(input: CaseVisualInput, doc: InboxDoc): InboxAnswer | null {
  const j = replyJson(input);
  if (!j || !doc.key) return null;
  const msgs = byId(pick(j, 'messages'));
  const acts = byId(pick(j, 'actions'));
  const marks: MailMark[] = doc.mails.map((m) => {
    const id = normSetId(m.id);
    const raw = msgs.get(id);
    const cat = raw && typeof raw === 'object' ? pick(raw, 'category', 'label') : typeof raw === 'string' ? raw : undefined;
    const keyCat = doc.key!.cats[m.id] ?? null;
    const act = readAction(acts.get(id));
    const keyAct = doc.key!.actions[m.id] ?? null;
    let actState: MailMark['actState'] = 'none';
    let actFields: MailMark['actFields'] = null;
    if (keyAct && act) {
      actFields = { owner: sameValue(keyAct.owner, act.owner), due: sameValue(keyAct.due, act.due), priority: sameValue(keyAct.priority, act.priority) };
      actState = actFields.owner && actFields.due && actFields.priority ? 'right' : 'wrong';
    } else if (keyAct) actState = 'missed';
    else if (act) actState = 'invented';
    return { id: m.id, cat: cat == null ? null : String(cat), keyCat, catOk: keyCat === null ? null : sameValue(keyCat, cat ?? null), act, keyAct, actState, actFields };
  });
  const scamsMissed = marks.filter((m) => m.keyCat === 'phishing' && m.catOk === false).map((m) => m.id);
  const falseScams = marks.filter((m) => m.keyCat !== 'phishing' && m.cat?.toLowerCase() === 'phishing').map((m) => m.id);
  return {
    marks,
    catRight: marks.filter((m) => m.catOk).length,
    actRight: marks.filter((m) => m.actState === 'right').length,
    actTotal: marks.filter((m) => m.keyAct).length,
    scamsMissed,
    falseScams,
  };
}

function editDistance(a: string, b: string): number {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...Array(b.length).fill(0)] as number[]);
  for (let j = 1; j <= b.length; j++) d[0]![j] = j;
  for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) d[i]![j] = Math.min(d[i - 1]![j]! + 1, d[i]![j - 1]! + 1, d[i - 1]![j - 1]! + (a[i - 1] === b[j - 1] ? 0 : 1));
  return d[a.length]![b.length]!;
}

/** For a scam: the genuine domain the sender's address imitates ("marlowfleldworks.co.uk" → "marlowfieldworks.co.uk"). */
export function lookalike(doc: InboxDoc, addr: string): { fake: string; real: string } | null {
  const dom = addr.split('@')[1]?.toLowerCase();
  if (!dom) return null;
  if (doc.genuine.some((g) => g.addr.split('@')[1] === dom)) return null;
  let best: { real: string; d: number } | null = null;
  for (const g of doc.genuine) {
    const real = g.addr.split('@')[1]!;
    const d = editDistance(dom, real);
    if (!best || d < best.d) best = { real, d };
  }
  return best && best.d <= 3 ? { fake: dom, real: best.real } : { fake: dom, real: '' };
}

/** An earlier exact copy of a message (same sender address, subject and text). */
export function earlierCopy(doc: InboxDoc, id: string): string | null {
  const i = doc.mails.findIndex((m) => m.id === id);
  const m = doc.mails[i];
  if (!m) return null;
  const twin = doc.mails.slice(0, i).find((x) => x.fromAddr === m.fromAddr && x.subject === m.subject && x.body === m.body);
  return twin?.id ?? null;
}

/** The sentence of a message that sets its deadline (forwarded text left out), for "why" notes. */
export function deadlineSentence(body: string): string | null {
  const own = body.split(/-{3,}\s*Forwarded message/i)[0]!;
  const sentences = own.replace(/\s+/g, ' ').split(/(?<=[.?!])\s+/);
  const hit = sentences.find((s) => /\b(by|tomorrow|today|within|before|no later than|deadline|end of|moved|cancel|instead|now)\b/i.test(s) && s.length < 260);
  return hit?.trim() ?? null;
}

// ───────────────────────────── What Do I Need to Do? ─────────────────────────────

export interface EmailMsg {
  from: string;
  to: string;
  sent: string;
  subject: string;
  body: string;
  /** Quoted inside a forward ("for info"), not written to me. */
  forwarded: boolean;
}

export interface Job {
  id: string;
  title: string;
  key: { mine: boolean; due: string | null } | null;
  /** The auditor's reason for the key ("mine, weekday Friday from 2026-03-11 → 2026-03-13"). */
  why: string | null;
}

export interface EmailDoc {
  me: string;
  /** The thread, oldest message first as written. */
  messages: EmailMsg[];
  jobs: Job[];
}

export function parseEmailActions(prompt: string, expected?: unknown, notes?: string): EmailDoc | null {
  const me = (prompt.match(/^I'm ([^.]+)\./m) ?? [])[1]?.trim() ?? 'me';
  const a = prompt.indexOf('<<<');
  const b = prompt.indexOf('>>>', a);
  if (a < 0 || b < 0) return null;
  const thread = prompt.slice(a + 3, b).trim();
  // Messages start at a "From:" header line; a "---- Forwarded message ----" line before one marks it as forwarded.
  const parts = thread.split(/\n(?=From: )/);
  const messages: EmailMsg[] = [];
  parts.forEach((raw0, i) => {
    const forwarded = i > 0 && /-{2,}[^\n]*forward[^\n]*-*\s*$/i.test(parts[i - 1]!);
    const raw = raw0.replace(/\n\s*-{2,}[^\n]*forward[^\n]*-*\s*$/i, '');
    const lines = raw.split('\n');
    const head: Record<string, string> = {};
    let k = 0;
    for (; k < lines.length; k++) {
      const m = lines[k]!.match(/^(From|To|Cc|Sent|Date|Subject):\s*(.*)$/);
      if (!m) break;
      head[m[1]!] = m[2]!;
    }
    messages.push({ from: head.From ?? '', to: head.To ?? '', sent: head.Sent ?? head.Date ?? '', subject: head.Subject ?? '', body: lines.slice(k).join('\n').trim(), forwarded });
  });
  const jobs: Job[] = [];
  const tj = prompt.slice(b).match(/Tracker jobs:\n([\s\S]*?)\n\n/);
  for (const l of (tj?.[1] ?? '').split('\n')) {
    const m = l.match(/^(T\d+):\s*(.+)$/);
    if (m) jobs.push({ id: m[1]!, title: m[2]!.trim(), key: null, why: null });
  }
  if (!jobs.length || !messages.length) return null;
  const exp = (expected ?? {}) as Record<string, { mine?: boolean; due?: string | null }>;
  const why = new Map<string, string>();
  for (const part of (notes ?? '').split(/;\s*(?=T\d+ )/)) {
    const m = part.trim().match(/^(T\d+)\s+[^:]+:\s*(.+)$/);
    if (m) why.set(m[1]!, m[2]!.trim());
  }
  for (const j of jobs) {
    const e = exp[j.id];
    if (e) j.key = { mine: !!e.mine, due: e.due ?? null };
    j.why = why.get(j.id) ?? null;
  }
  return { me, messages, jobs };
}

export interface JobMark {
  id: string;
  mine: boolean | null;
  due: string | null;
  mineOk: boolean | null;
  dueOk: boolean | null;
}

export function emailAnswer(input: CaseVisualInput, doc: EmailDoc): JobMark[] | null {
  const j = replyJson(input);
  if (!j) return null;
  const ids = byId(j);
  return doc.jobs.map((job) => {
    const v = ids.get(normSetId(job.id));
    const mineRaw = pick(v, 'mine', 'ismine');
    const dueRaw = pick(v, 'due', 'duedate', 'deadline');
    const mine = typeof mineRaw === 'boolean' ? mineRaw : typeof mineRaw === 'string' ? /^(yes|true)$/i.test(mineRaw.trim()) : null;
    const due = dueRaw == null || dueRaw === '' ? null : String(dueRaw);
    return {
      id: job.id,
      mine,
      due,
      mineOk: job.key ? sameValue(job.key.mine, mineRaw ?? null) : null,
      dueOk: job.key ? sameValue(job.key.due, dueRaw ?? null) : null,
    };
  });
}

const STOP = new Set(['the', 'for', 'and', 'with', 'from', 'that', 'this', 'your', 'our', 'into', 'about', 'please', 'could', 'you', 'can', 'will', 'all', 'new', 'list']);

/** Words of a job title worth finding in the email ("Updated supplier list" → updated, supplier). */
export function jobWords(title: string): string[] {
  return title
    .toLowerCase()
    .replace(/[^a-z0-9\s'-]/g, ' ')
    .split(/\s+/)
    .filter((w) => w.length > 2 && !STOP.has(w))
    .map((w) => w.replace(/s$/, ''));
}

/** The sentences of a message, each tagged with the job whose words it mentions most (for the highlighter). */
export function highlightJobs(body: string, jobs: Job[]): Array<{ text: string; job: string | null }> {
  const out: Array<{ text: string; job: string | null }> = [];
  const words = jobs.map((j) => ({ id: j.id, w: jobWords(j.title) }));
  for (const para of body.split(/\n/)) {
    const sentences = para.split(/(?<=[.?!])\s+|(?<=,)\s+(?=and\b|but\b)|(?<=;)\s+/);
    sentences.forEach((s, i) => {
      const low = s.toLowerCase();
      let best: { id: string; n: number } | null = null;
      for (const j of words) {
        const n = j.w.filter((w) => low.includes(w)).length;
        if (n > 0 && (!best || n > best.n)) best = { id: j.id, n };
      }
      out.push({ text: s + (i < sentences.length - 1 ? ' ' : ''), job: best ? best.id : null });
    });
    out.push({ text: '\n', job: null });
  }
  return out;
}
