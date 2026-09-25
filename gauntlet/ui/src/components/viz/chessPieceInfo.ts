/** Chess piece names and material values (plain data, shared by the SVG pieces and the Arena analysis). */
export const PIECE_NAME: Record<string, string> = { k: 'king', q: 'queen', r: 'rook', b: 'bishop', n: 'knight', p: 'pawn' };
/** Material points used for the material bar and trays (a count, not an engine evaluation). */
export const PIECE_VALUE: Record<string, number> = { p: 1, n: 3, b: 3, r: 5, q: 9, k: 0 };
