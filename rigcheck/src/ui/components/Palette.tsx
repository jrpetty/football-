/**
 * Command palette.
 *
 * Eleven screens is past the point where a navigation bar is a way of getting
 * somewhere — it becomes a list you read. Ctrl-K takes you straight there, and
 * it searches parts and games as well as screens, because "where is the 4070"
 * is asked far more often than "which screen is the Data Explorer".
 *
 * Deliberately keyboard-complete: arrows move, Enter picks, Escape closes,
 * and focus returns to whatever had it before. A palette you have to reach for
 * the mouse to finish using is a menu with extra steps.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { search } from '../../core/catalogue.ts';
import { engineData } from '../store.ts';

export interface PaletteAction {
  id: string;
  label: string;
  hint?: string;
  group: string;
  run: () => void;
}

export function CommandPalette({ screens }: { screens: { to: string; label: string; hint?: string }[] }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [cursor, setCursor] = useState(0);
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const restoreTo = useRef<HTMLElement | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        restoreTo.current = document.activeElement as HTMLElement;
        setOpen((o) => !o);
        setQ('');
        setCursor(0);
        return;
      }
      if (e.key === 'Escape' && open) {
        e.preventDefault();
        setOpen(false);
      }
      // "/" is a search shortcut everywhere except inside a field, where it is
      // a slash. Getting that wrong makes typing a path into a text box open a
      // palette, which is maddening.
      const t = e.target as HTMLElement | null;
      const typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
      if (e.key === '/' && !typing && !open) {
        e.preventDefault();
        restoreTo.current = document.activeElement as HTMLElement;
        setOpen(true);
        setQ('');
        setCursor(0);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  useEffect(() => {
    if (open) inputRef.current?.focus();
    else restoreTo.current?.focus?.();
  }, [open]);

  const actions = useMemo<PaletteAction[]>(() => {
    const go = (to: string, label: string, hint?: string): PaletteAction => ({
      id: `nav${to}`, label, hint, group: 'screens', run: () => navigate(to),
    });
    const base = screens.map((s) => go(s.to, s.label, s.hint));
    const term = q.trim();
    if (term.length < 2) return base;

    const parts = search(term, engineData, 8).map<PaletteAction>((h) => ({
      id: `part-${h.id}`,
      label: h.label,
      hint: `${h.kind.toUpperCase()} · ${h.disambiguator}`,
      group: h.kind === 'gpu' ? 'graphics cards' : 'processors',
      // The Data Explorer's filter is the closest thing to "show me this part",
      // so a part hit goes there rather than nowhere.
      run: () => navigate('/data'),
    }));

    const games = [...engineData.games.values()]
      .filter((g) => g.name.toLowerCase().includes(term.toLowerCase()))
      .slice(0, 6)
      .map<PaletteAction>((g) => ({
        id: `game-${g.id}`,
        label: g.name,
        hint: `${g.archetype}${g.fpsCap ? ` · capped at ${g.fpsCap}fps` : ''}`,
        group: 'games',
        run: () => navigate('/wizard'),
      }));

    return [...base, ...parts, ...games];
  }, [q, screens, navigate]);

  const filtered = useMemo(() => {
    const term = q.trim().toLowerCase();
    if (!term) return actions;
    return actions.filter((a) => a.label.toLowerCase().includes(term) || (a.hint ?? '').toLowerCase().includes(term));
  }, [actions, q]);

  useEffect(() => {
    if (cursor >= filtered.length) setCursor(Math.max(0, filtered.length - 1));
  }, [filtered.length, cursor]);

  if (!open) return null;

  const grouped = filtered.reduce<Record<string, PaletteAction[]>>((acc, a) => {
    (acc[a.group] ??= []).push(a);
    return acc;
  }, {});
  let flat = -1;

  return (
    <div
      className="palette-backdrop"
      role="dialog"
      aria-modal="true"
      aria-label="Command palette"
      onMouseDown={(e) => e.target === e.currentTarget && setOpen(false)}
    >
      <div className="palette">
        <input
          ref={inputRef}
          type="text"
          value={q}
          placeholder="go to a screen, or search parts and games…"
          aria-label="Search screens, parts and games"
          onChange={(e) => { setQ(e.target.value); setCursor(0); }}
          onKeyDown={(e) => {
            if (e.key === 'ArrowDown') { e.preventDefault(); setCursor((c) => Math.min(c + 1, filtered.length - 1)); }
            if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(c - 1, 0)); }
            if (e.key === 'Enter' && filtered[cursor]) { e.preventDefault(); filtered[cursor].run(); setOpen(false); }
          }}
        />
        <div className="palette-list" role="listbox" aria-label="Results">
          {filtered.length === 0 && <div className="palette-empty">Nothing matches “{q}”.</div>}
          {Object.entries(grouped).map(([group, items]) => (
            <div key={group}>
              <div className="palette-group">{group}</div>
              {items.map((a) => {
                flat += 1;
                const i = flat;
                return (
                  <div
                    key={a.id}
                    role="option"
                    aria-selected={i === cursor}
                    className={`palette-item${i === cursor ? ' on' : ''}`}
                    onMouseEnter={() => setCursor(i)}
                    onMouseDown={(e) => { e.preventDefault(); a.run(); setOpen(false); }}
                  >
                    <span className="l">{a.label}</span>
                    {a.hint && <span className="h">{a.hint}</span>}
                  </div>
                );
              })}
            </div>
          ))}
        </div>
        <div className="palette-foot">
          <span><kbd>↑</kbd><kbd>↓</kbd> move</span>
          <span><kbd>↵</kbd> open</span>
          <span><kbd>esc</kbd> close</span>
          <span className="spacer" style={{ flex: 1 }} />
          <span><kbd>ctrl</kbd>+<kbd>k</kbd> or <kbd>/</kbd> anywhere</span>
        </div>
      </div>
    </div>
  );
}
