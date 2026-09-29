/**
 * Keyboard commands for whichever viewer is on screen (the Grading Station
 * sends "play" on P and "fullscreen" on F). A tiny window event bus, so the
 * page does not need a ref into every viewer type.
 */
export type ViewerCommand = 'play' | 'restart' | 'fullscreen';

const EVENT = 'gauntlet:viewer';

export function sendViewerCommand(cmd: ViewerCommand): void {
  window.dispatchEvent(new CustomEvent<ViewerCommand>(EVENT, { detail: cmd }));
}

/** Listen for commands; returns the unsubscribe function (use it as an effect cleanup). */
export function onViewerCommand(fn: (cmd: ViewerCommand) => void): () => void {
  const h = (e: Event) => fn((e as CustomEvent<ViewerCommand>).detail);
  window.addEventListener(EVENT, h);
  return () => window.removeEventListener(EVENT, h);
}
