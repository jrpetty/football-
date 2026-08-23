/**
 * Where do I start?
 *
 * Screens with names like "Model Health" are a menu for someone who already
 * knows what the tool does. A person arriving for the first time has a question,
 * not a screen in mind, so this asks the question back and routes on the answer.
 *
 * It matters less than it did — the bar is two items now rather than eleven —
 * but the second and third visits are still spent wondering which screen answers
 * a particular question, and a question is easier to scan than a noun.
 *
 * Shown automatically on a first visit and reachable from the navigation
 * afterwards, because the second and third visits are also spent wondering
 * which screen answers a particular question.
 */
import { Link } from 'react-router-dom';

interface Route {
  q: string;
  to: string;
  screen: string;
  detail: string;
}

const ROUTES: Route[] = [
  {
    q: 'I want to build a PC and I do not know where to start',
    to: '/wizard',
    screen: 'Build a PC',
    detail: 'Tell it your screen, your games and your budget. It works backwards to a parts list, explains every line, and ends with what to set each game to. Start here if you are buying.',
  },
  {
    q: 'My PC feels slower than it should',
    to: '/system',
    screen: 'Check my PC',
    detail: 'Checks the handful of faults that are invisible on a spec sheet — single-channel memory, a memory profile never enabled, a card in the wrong slot — and tells you what each is costing. Save the check and it can verify your fix worked.',
  },
  {
    q: 'Should I upgrade, and which part?',
    to: '/upgrade',
    screen: 'What to upgrade first',
    detail: 'Plots price against performance for every upgrade path from where you are, marks the point where spending more stops buying much, and says when the other component is the real wall.',
  },
  {
    q: 'Is this specific build any good?',
    to: '/analyser',
    screen: 'Analyse one build',
    detail: 'Pick the parts, get frame rates per game with the working shown — every term, its value, its confidence and where it came from.',
  },
  {
    q: 'Will it fit, what will it draw, and how loud will it be?',
    to: '/machine',
    screen: 'Power, heat and noise',
    detail:
      'Sustained draw and transient peak, the power supply that needs, whether the cooling holds the clocks, roughly how loud it gets and what the input latency looks like.',
  },
  {
    q: 'What is actually in this machine?',
    to: '/detect',
    screen: 'Identify a machine',
    detail: 'Paste a dxdiag dump, the harness output, or a line you typed from memory. Ambiguous matches are asked about rather than guessed.',
  },
  {
    q: 'I want the numbers behind all of this',
    to: '/data',
    screen: 'The catalogue',
    detail: '279 graphics cards and 442 processors with their derived indices, the specs those came from, and filters by capability rather than by name.',
  },
  {
    q: 'How much should I trust any of these numbers?',
    to: '/health',
    screen: 'What the estimates rest on',
    detail: 'The one screen that argues against the others: how much of the evidence is a measurement rather than a recollection, how accuracy is moving, and which parts of the model nothing has ever validated. Worth reading before you spend money on the basis of anything here.',
  },
];

export function Start({ onDismiss }: { onDismiss: () => void }) {
  return (
    <>
      <div className="page-head">
        <h1>What are you trying to do?</h1>
        <p>
          Pick the question closest to yours. Everything is also under <b>more</b> in the navigation,
          and <kbd>ctrl</kbd>+<kbd>k</kbd> jumps anywhere from anywhere.
        </p>
      </div>

      <div className="start-grid">
        {ROUTES.map((r) => (
          <Link key={r.to} to={r.to} className="start-card" onClick={onDismiss}>
            <b>{r.q}</b>
            <span className="screen">{r.screen}</span>
            <span className="detail">{r.detail}</span>
          </Link>
        ))}
      </div>

      <div className="note" style={{ marginTop: 16 }}>
        <b>One thing worth knowing before you use any of it.</b> The performance figures come from a
        model seeded with recalled data rather than measurements — the specification sources it was
        designed around are unreachable from where it was built. Relative orderings are reliable;
        absolute frame rates are not, and prices least of all.{' '}
        <Link to="/health">Model Health</Link> shows exactly how much is evidence and how much is not,
        and it is the honest place to start if you are about to spend real money.
      </div>
    </>
  );
}
