/** First run: with no API key saved yet, the home page hands over to the API Keys page (its welcome). */
import { useEffect, useRef } from 'react';
import { MOCK, request } from '../api.ts';
import { navigate, useRoute } from '../router.tsx';

export function useFirstRunRedirect(): void {
  const route = useRoute();
  const checked = useRef(false);
  useEffect(() => {
    if (checked.current || MOCK) return;
    checked.current = true;
    try {
      // Once per browser tab, so the owner can still open the home page afterwards.
      if (sessionStorage.getItem('gauntlet.firstRunChecked')) return;
      sessionStorage.setItem('gauntlet.firstRunChecked', '1');
    } catch {
      /* storage blocked: check anyway */
    }
    if (route.path !== '/') return;
    request<{ anyKey: boolean }>('GET', '/api/setup')
      .then((s) => {
        if (!s.anyKey) navigate('/keys', undefined, true);
      })
      .catch(() => undefined);
  }, [route.path]);
}
