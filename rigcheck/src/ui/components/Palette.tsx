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
import { engineData, useApp } from '../store.ts';

export interface PaletteAction {
  id: string;
  label: string;
  hint?: string;
  group: string;
  run: () => void;
}

export function CommandPalette({ screens }: { screens: { to: string; label: string; full?: string; hint?: string }[] }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const [cursor, setCursor] = useState(0);
  const navigate = useNavigate();
  // Renamed on the way in: the palette already has a local `games` holding its
  // game ACTIONS, and shadowing the app's selected game ids with it would be a
  // quiet way to add the wrong thing to the wrong list.
  const { builds, setBuilds, games: selectedGames, setGames } = useApp();
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
    // The palette lists the FULL screen name and stays searchable by the short
    // one: the top bar says "Compare", so someone who has only ever seen that
    // must be able to type it, and someone who remembers "Comparison Matrix"
    // must find it too.
    const base = screens.map((s) => ({
      ...go(s.to, s.full ?? s.label, s.hint),
      alias: s.full && s.full !== s.label ? s.label : undefined,
    }));
    const term = q.trim();
    if (term.length < 2) return base;

    // Selecting a part PUTS IT IN THE BUILD and shows you the effect. It used
    // to list the exact part, disambiguated, and then navigate to an unfiltered
    // Data Explorer — throwing away the one piece of information the user had
    // just supplied. "Where is the 4070" is nearly always "what would the 4070
    // do for me".
    const parts = search(term, engineData, 8).map<PaletteAction>((h) => ({
      id: `part-${h.id}`,
      label: h.label,
      hint: `${h.kind.toUpperCase()} · ${h.disambiguator} · swaps into your build`,
      group: h.kind === 'gpu' ? 'graphics cards' : 'processors',
      run: () => {
        const first = builds[0];
        if (first) {
          const next = h.kind === 'gpu' ? { ...first, gpuId: h.id } : { ...first, cpuId: h.id };
          setBuilds([next, ...builds.slice(1)]);
        }
        navigate('/analyser');
      },
    }));

    const games = [...engineData.games.values()]
      .filter((g) => g.name.toLowerCase().includes(term.toLowerCase()))
      .slice(0, 6)
      .map<PaletteAction>((g) => ({
        id: `game-${g.id}`,
        label: g.name,
        hint: `${g.archetype}${g.fpsCap ? ` · capped at ${g.fpsCap}fps` : ''} · adds to your selection`,
        group: 'games',
        run: () => {
          if (!selectedGames.includes(g.id)) setGames([...selectedGames, g.id]);
          navigate('/analyser');
        },
      }));

    return [...base, ...parts, ...games];
  }, [q, screens, navigate]);

  const filtered = useMemo(() => {
    const term = q.trim().toLowerCase();
    if (!term) return actions;
    return actions.filter(
      (a) =>
        a.label.toLowerCase().includes(term) ||
        (a.hint ?? '').toLowerCase().includes(term) ||
        ((a as { alias?: string }).alias ?? '').toLowerCase().includes(term),
    );
  }, [actions, q]);

  useEffect(() => {
    if (cursor >= filtered.length) setCursor(Math.max(0, filtered.length - 1));
  }, [filtered.length, cursor]);

  if (!open) return null;

  const grouped = filtered.reduce<Record<string, PaletteAction[]>>((acc, a) => {
    (acc[a.group] ??= []).push(a);
    return acc;
  }, {});

  /**
   * The rendered order, flattened — and the ONLY index the cursor uses.
   *
   * Grouping reorders: a processor and a graphics card that interleave by score
   * in `filtered` are split into two blocks on screen. The highlight was painted
   * at the rendered position while Enter ran `filtered[cursor]`, so once the two
   * orders diverged the highlighted row and the row that fired were different
   * actions. That was invisible while every part hit did the same harmless
   * thing; now that choosing a part swaps it into the build, it would apply a
   * graphics card the user never highlighted.
   */
  const ordered = Object.values(grouped).flat();
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
            if (e.key === 'ArrowDown') { e.preventDefault(); setCursor((c) => Math.min(c + 1, ordered.length - 1)); }
            if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(c - 1, 0)); }
            if (e.key === 'Enter' && ordered[cursor]) { e.preventDefault(); ordered[cursor].run(); setOpen(false); }
          }}
        />
        <div className="palette-list" role="listbox" aria-label="Results">
          {ordered.length === 0 && <div className="palette-empty">Nothing matches “{q}”.</div>}
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
