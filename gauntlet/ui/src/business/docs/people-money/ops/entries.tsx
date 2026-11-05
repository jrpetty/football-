import type { DocEntry } from '../types.ts';
import { boardQuestion, reorderQuestion } from '../model/opsdata.ts';
import { BoardBeats, BoardInline, ReorderBeats, ReorderInline } from './Ops.tsx';

export const BOARD_REPORT: DocEntry = {
  family: 'extraction',
  caption: 'The twelve KPI tiles the model gave the board, each against the correct figure: green exact, red wrong with the right number underneath.',
  build: (input) => (boardQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <BoardInline input={input} mode={mode} />,
  Beats: BoardBeats,
};

export const STOCK_REORDER: DocEntry = {
  family: 'extraction',
  caption: 'The stock list with the model’s order beside the cheapest workable order, each item’s lowest stock before the next delivery against its safety stock, and the order’s cost against the optimum.',
  build: (input) => (reorderQuestion(input.turns[0] ?? '') ? input : null),
  render: (_m, mode, input) => <ReorderInline input={input} mode={mode} />,
  Beats: ReorderBeats,
};
