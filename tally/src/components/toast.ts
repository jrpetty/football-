// ---------------------------------------------------------------------------
// The toast.
//
// Every screen had the same three lines — set the message, set a timer to clear
// it — and the same bug in them. The timer is started but never cancelled, so
// the timer belonging to an *earlier* message wipes a later one off the screen
// the moment it comes due. Do two things four seconds apart and the second one
// tells you nothing happened.
//
// That is worse than it sounds here, because a toast is how this app says a
// thing is done: "3 lines are now counted as one", "restored, with 4 receipts".
// A confirmation that silently does not appear reads exactly like an action
// that silently did not happen.
//
// So: one timer, held in a ref, cancelled before each new one and on unmount.
// ---------------------------------------------------------------------------

import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * A message that clears itself after `ms`, and never has its own clearing
 * cancelled out by the message before it.
 *
 * Returns the message to render, and `say` to set one. Calling `say('')` clears
 * it at once.
 */
export function useToast(ms = 4000): [string, (message: string) => void] {
  const [toast, setToast] = useState('')
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)

  // A message left in flight when the screen closes has nothing to clear, and a
  // timer that outlives its component sets state on something that is gone.
  useEffect(() => () => clearTimeout(timer.current), [])

  const say = useCallback(
    (message: string) => {
      clearTimeout(timer.current)
      setToast(message)
      if (message) timer.current = setTimeout(() => setToast(''), ms)
    },
    [ms],
  )

  return [toast, say]
}
