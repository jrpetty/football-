/**
 * A calm "do this first" prompt: one icon, one plain sentence, one obvious button. Used wherever a page needs
 * something set up before it is useful (no key yet, no runs yet, nothing to paste yet), instead of a red error.
 */
import type { ReactNode } from 'react';
import { cx } from '../components/ui.tsx';
import { Link } from '../router.tsx';

export function SetupPrompt({
  icon,
  title,
  children,
  action,
  secondary,
  compact,
}: {
  icon: ReactNode;
  title: ReactNode;
  children?: ReactNode;
  action?: { label: ReactNode; to: string };
  secondary?: { label: ReactNode; to: string };
  /** A slim one-line banner instead of a centred card. */
  compact?: boolean;
}) {
  return (
    <div className={cx('setup-prompt', compact && 'compact')} role="note">
      <span className="sp-icon" aria-hidden="true">
        {icon}
      </span>
      <div className="sp-text">
        <h3>{title}</h3>
        {children && <p>{children}</p>}
      </div>
      {(action || secondary) && (
        <div className="sp-actions">
          {action && (
            <Link to={action.to} className={cx('btn primary', !compact && 'lg')}>
              {action.label}
            </Link>
          )}
          {secondary && (
            <Link to={secondary.to} className="btn ghost">
              {secondary.label}
            </Link>
          )}
        </div>
      )}
    </div>
  );
}
