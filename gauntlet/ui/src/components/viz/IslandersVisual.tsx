/** Knights, Knaves, Spies & Alternators: each islander drawn with their statements, true role and the model's verdict. */
import type { CSSProperties } from 'react';
import { islandHeadline, type IslandRole, type IslandVisual } from '../../../../src/presenter/visuals/truth.ts';
import { cx } from '../ui.tsx';
import { ToneMark, VizFrame, type VizMode } from './VizFrame.tsx';

const ROLE_LABEL: Record<IslandRole, string> = { knight: 'Knight', knave: 'Knave', spy: 'Spy', alternator: 'Alternator' };
const ROLE_HINT: Record<IslandRole, string> = { knight: 'always true', knave: 'always false', spy: 'either', alternator: 'true / false in turn' };

function Emblem({ role }: { role: IslandRole }) {
  switch (role) {
    case 'knight': // shield
      return <path d="M50 58 l11 4 v9 c0 7 -5 11 -11 14 c-6 -3 -11 -7 -11 -14 v-9 z" />;
    case 'knave': // mask
      return <path d="M37 64 c8 -3 18 -3 26 0 c0 7 -4 11 -8 11 c-3 0 -4 -3 -5 -3 c-1 0 -2 3 -5 3 c-4 0 -8 -4 -8 -11 z" />;
    case 'spy': // hat brim + crown
      return <path d="M36 72 h28 v3 h-28 z M42 62 h16 l2 10 h-20 z" />;
    case 'alternator': // two arrows
      return <path d="M38 64 h18 l-4 -4 M62 76 h-18 l4 4" fill="none" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round" />;
  }
}

/** A simple islander: head, body in the role colour, role emblem on the chest. */
function Islander({ role }: { role: IslandRole }) {
  return (
    <svg className="vz-isl-fig" viewBox="0 0 100 100" aria-hidden="true">
      <ellipse cx="50" cy="96" rx="26" ry="3.5" className="vz-isl-shadow" />
      <path className="vz-isl-body" d="M24 96 C24 70 34 54 50 54 C66 54 76 70 76 96 Z" />
      <circle className="vz-isl-head" cx="50" cy="32" r="17" />
      <circle cx="44" cy="31" r="2.2" className="vz-isl-eye" />
      <circle cx="56" cy="31" r="2.2" className="vz-isl-eye" />
      <g className="vz-isl-emb">
        <Emblem role={role} />
      </g>
    </svg>
  );
}

function RoleBadge({ role, dim }: { role: IslandRole | null; dim?: boolean }) {
  if (!role) return <span className="vz-role none">no verdict</span>;
  return <span className={cx('vz-role', `r-${role}`, dim && 'dim')}>{ROLE_LABEL[role]}</span>;
}

export function IslandersVisual({ v, mode }: { v: IslandVisual; mode: VizMode }) {
  const tone = !v.answered ? 'bad' : v.right === v.people.length ? 'good' : 'bad';
  const dense = v.people.length > 6;
  return (
    <VizFrame
      mode={mode}
      tone={tone}
      eyebrow={`Who is telling the truth? ${v.people.length} islanders${v.rule ? ` · ${v.rule.replace(/\.$/, '')}` : ''}`}
      headline={islandHeadline(v)}
      big={`${v.right}/${v.people.length}`}
      bigSub="roles right"
      legend={[
        ...v.roles.map((r) => ({ tone: `r-${r}`, label: `${ROLE_LABEL[r]}: ${ROLE_HINT[r]}` })),
        { tone: 'good', label: 'Model’s verdict right' },
        { tone: 'bad', label: 'Model’s verdict wrong' },
      ]}
    >
      <ol className={cx('vz-isl', dense && 'dense')} style={{ ['--cols' as string]: v.people.length > 5 ? Math.ceil(v.people.length / 2) : v.people.length } as CSSProperties}>
        {v.people.map((p, i) => (
          <li key={p.name} className={cx('vz-isl-card', `r-${p.truth}`, p.ok ? 'ok' : 'miss', p.statements.length >= 3 && 'many')} style={{ ['--i' as string]: i } as CSSProperties}>
            <div className="vz-isl-bubbles">
              {p.statements.length === 0 && <p className="vz-bubble quiet">(says nothing)</p>}
              {p.statements.map((s, k) => (
                <p key={k} className="vz-bubble">
                  {p.statements.length > 1 && <b className="tnum">{k + 1}</b>}“{s}”
                </p>
              ))}
            </div>
            <Islander role={p.truth} />
            <div className="vz-isl-name">{p.name}</div>
            <div className="vz-isl-verdict">
              <span className="vz-isl-k">Truth</span>
              <RoleBadge role={p.truth} />
              <span className="vz-isl-k">Model</span>
              <span className="vz-isl-model">
                <RoleBadge role={p.model} dim={!p.ok} />
                <ToneMark tone={p.ok ? 'good' : 'bad'} />
              </span>
            </div>
          </li>
        ))}
      </ol>
    </VizFrame>
  );
}
