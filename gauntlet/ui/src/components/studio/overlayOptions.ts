/** Options shared by the OBS overlay page and the Studio's overlay builder. */
export type OverlayView = 'scoreboard' | 'ticker' | 'lower-third' | 'bracket-lite' | 'live';
export type OverlayTheme = 'glass' | 'solid' | 'light' | 'minimal';
export type OverlayPos = 'tl' | 'tr' | 'bl' | 'br' | 'top' | 'bottom';

export const OVERLAY_VIEWS: Array<{ id: OverlayView; label: string; hint: string; pos: OverlayPos }> = [
  { id: 'scoreboard', label: 'Scoreboard', hint: 'Current standings, best first', pos: 'tr' },
  { id: 'ticker', label: 'Ticker', hint: 'A crawl of the latest results', pos: 'bottom' },
  { id: 'lower-third', label: 'Lower third', hint: '“Now testing: Survival Island — A vs B”', pos: 'bl' },
  { id: 'bracket-lite', label: 'Test board', hint: 'Every test and who is winning it', pos: 'tl' },
  { id: 'live', label: 'Live run', hint: 'Leader, progress, spend and a commentary crawl', pos: 'bottom' },
];
